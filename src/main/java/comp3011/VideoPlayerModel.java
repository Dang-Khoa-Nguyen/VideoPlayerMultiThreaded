/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Dang Khoa Nguyen - a3141297
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;
 
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
 
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;
 
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */

public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    // Timing lines are off unless run with -Dplayer.debug=true. Printing from the UI thread can block it
    // if whoever reads our output is slow, and a blocked UI thread shows up as dropped frames.
    private static final boolean DEBUG_TIMING = Boolean.getBoolean("player.debug");
 
    private final List<FrameProcessor> frameProcessors;
    // The decoder thread adds audio and the UI thread drains it, so it must be thread-safe.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
 
    // JavaFX calls handle() on every heartbeat, on the UI thread. It only plays audio and shows
    // frames, so it never blocks.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };
 
    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // 3rd party decoder. Not thread-safe: only the decoder thread uses it while running.
    private JavaFXFrameConverter converter; // Frame to JavaFX Image. Only the pipeline's convert stage uses it while running.
    private volatile AudioPlayer audioPlayer; // volatile: the decoder thread checks it; null while audio is off
    private long playRequestedNs; // for the startup timing line
    private boolean startupReported; // the startup line is printed once per start, not after every seek
    private FramePipeline pipeline; // effects and conversion, one thread each. Created per playback.
    private Thread decoderThread;
    private FrameJob nextFrame; // UI thread only: the image taken from the pipeline, waiting for its due time
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // volatile: the decoder thread reads it while the UI thread sets it
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
 
    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        // Copy so the caller cannot change the list while the decoder thread is using it.
        this.frameProcessors = List.copyOf(frameProcessors);
    }
 
    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }
 
    public void startOver() {
        if (videoFile == null) {
            return;
        }
 
        seekTo(0);
    }
 
    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }
 
    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }
 
    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }
 
        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
 
        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }
 
        notifyPlaybackStateChanged();
    }
 
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        if (audioOutputEnabled && playbackOpen && audioAvailable && audioPlayer == null) {
            try {
                openAudioPlayer();
            } catch (Exception e) {
                e.printStackTrace();
                audioOutputEnabled = false;
            }
        }
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }
 
    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }
 
    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }
 
    public void shutdown() {
        closePlaybackResources();
    }
 
    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }
 
        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }
 
    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);
 
        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }
 
        try {
            // Order matters. Stop the decoder first so nothing from before the jump (frames or audio)
            // can arrive after we move the grabber, and so only one thread touches the grabber.
            stopDecoder();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
 
    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        playRequestedNs = System.nanoTime();
        startupReported = false;
        closePlaybackResources();
 
        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;
 
        notifyStatusChanged(videoFile.getName());
 
        try {
            openPlaybackResources(startTimestampUs);
            pipeline = new FramePipeline(
                    frameProcessors,
                    converter::convert,
                    this::reportPipelineError,
                    FramePipeline.defaultMaxEffectStages());
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
 
    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
 
        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());
 
        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();
 
        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;
 
        // Opening and warming up the sound line is slow, so only do it when sound is wanted.
        // Pressing A later opens it then.
        audioAvailable = grabber.hasAudio();
        if (audioAvailable && audioOutputEnabled) {
            openAudioPlayer();
        }
 
        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }
 
    private void openAudioPlayer() throws Exception {
        AudioPlayer player = new AudioPlayer();
        player.open(grabber.getSampleRate(), grabber.getAudioChannels());
        audioPlayer = player; // published only once it is open, so the decoder never sees a half-open line
    }
 
    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        nextFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }
 
    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }
 
    // Starts the pipeline threads first so they are ready to take the decoder's frames.
    private void startDecoder() {
        pipeline.start();
        Thread thread = new Thread(this::decodeLoop, "decoder");
        thread.setDaemon(true); // never keep the JVM alive after the window closes
        decoderThread = thread;
        thread.start();
    }
 
    // Stops the producer (decoder) first, then the pipeline. join() is what makes this safe: once it
    // returns, no thread is using the grabber, the effects or the converter, and the queues can be
    // emptied without a race.
    private void stopDecoder() {
        Thread thread = decoderThread;
        decoderThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pipeline != null) {
            pipeline.stop();
        }
        nextFrame = null;
    }
 
    // Runs on the decoder thread. put() waits when the first queue is full, so the decoder never
    // runs far ahead of the effects.
    private void decodeLoop() {
        Thread self = Thread.currentThread();
        BlockingQueue<FrameJob> input = pipeline.input();
        try {
            while (!self.isInterrupted()) {
                FrameJob job = readNextVideoFrame();
                if (job == null) {
                    input.put(FrameJob.END); // tells every stage the video has ended
                    return;
                }
                try {
                    input.put(job);
                } catch (InterruptedException e) {
                    job.close(); // the job was never handed over, so we must free its frame
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            // Asked to stop by a seek, stop or close. Nothing to clean up.
        } catch (Exception e) {
            reportError(e, () -> decoderThread == self);
        }
    }
 
    // Called from a pipeline thread when an effect or the conversion fails.
    private void reportPipelineError(Exception e) {
        reportError(e, () -> playbackOpen);
    }
 
    // Only the UI thread may change the UI. The check drops errors from threads that were already stopped.
    private void reportError(Exception e, java.util.function.BooleanSupplier stillCurrent) {
        Platform.runLater(() -> {
            if (stillCurrent.getAsBoolean()) {
                handlePlaybackError(e);
            }
        });
    }
 
    // Decoder thread only. Returns null at the end of the video or when asked to stop.
    private FrameJob readNextVideoFrame() throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = grabFrame();
            if (frame == null) {
                return null;
            }
 
            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }
 
            if (frame.image == null) {
                continue;
            }
 
            if (!frameProcessorsInitialised) {
                // Done here, before the first frame is handed over. The queue hand-off then makes the
                // initialised effects visible to the effect threads.
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }
 
            // The grabber reuses this Frame on its next grab, so the pipeline gets its own copy.
            return new FrameJob(frame.clone(), new InfoFrame(grabber.getFrameNumber(), timestampUs), null);
        }
 
        return null;
    }
 
    // Runs on the UI thread every heartbeat. It never decodes: it plays audio, takes the next
    // frame from the queue and shows it when it is due.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
 
        writeDueAudio(now);
 
        if (nextFrame == null) {
            nextFrame = pipeline.output().poll(); // poll never waits, so the UI never stalls
        }
        if (nextFrame == null) {
            return;
        }
 
        // While paused, show one frame after a seek or start so the user sees where they are, then hold.
        boolean firstAfterReset = firstTimestampUs == NO_SEEK_REQUEST;
        if (pauseRequested && !firstAfterReset) {
            return;
        }
        if (nextFrame == FrameJob.END) {
            finishPlayback();
            return;
        }
 
        // Start the clock when the first frame is shown, not when it was decoded. A frame can wait
        // in the queue, and that waiting must not make it look late.
        if (firstAfterReset) {
            if (!startupReported) {
                startupReported = true;
                if (DEBUG_TIMING) System.out.printf("First frame shown %d ms after playback was requested%n",
                        (now - playRequestedNs) / 1_000_000L);
            }
            firstTimestampUs = nextFrame.info().timestampUs();
            playbackStartNs = now;
            if (pauseRequested) {
                pauseStartedNs = now;
            }
        }
 
        long relativeUs = Math.max(0, nextFrame.info().timestampUs() - firstTimestampUs);
        long targetTimeNs = playbackStartNs + relativeUs * 1_000L;
        if (targetTimeNs <= now) {
            displayNextFrame(now, targetTimeNs);
        }
    }
 
    private void displayNextFrame(long now, long targetTimeNs) {
        FrameJob frame = nextFrame;
        nextFrame = null;
        currentTimestampUs = logicalPlaybackBaseUs + Math.max(0, frame.info().timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = NO_SEEK_REQUEST;
 
        // Once per second: how late the frame was shown, and how many frames are waiting.
        if (DEBUG_TIMING && intFrameRate > 0 && frame.info().frameNumber() % intFrameRate == 0) {
            System.out.printf("Frame shown %dus after due, queue depth %d%n",
                    (now - targetTimeNs) / 1_000L,
                    pipeline.output().size());
        }
 
        notifyFrameReady(frame.image());
    }
 
    // Decoder thread only.
    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }
 
        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }
 
    // UI thread only.
    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }
 
        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }
 
            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }
 
            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }
 
    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;
 
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }
 
    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }
 
    private void closePlaybackResources() {
        // The decoder must be stopped before the grabber and converter are closed under it.
        stopDecoder();
        pipeline = null;
        playbackTimer.stop();
        playbackOpen = false;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
 
        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }
 
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }
 
        if (converter != null) {
            converter.close();
            converter = null;
        }
    }
 
    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }
 
    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }
 
    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }
 
        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }
 
        return Math.max(0, durationUs - frameDurationUs);
    }
 
    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }
 
    private Frame grabFrame() throws Exception {
        // While sound is off there is no reason to decode the audio track.
        if (audioAvailable && audioOutputEnabled) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }
 
    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }
 
    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }
 
    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }
 
    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }
 
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }
 
    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
 
    // The decoder thread creates these and the UI thread consumes them. Only the UI thread
    // calls advance(), so offset needs no synchronisation.
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;
 
        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }
 
        long timestampUs() {
            return timestampUs;
        }
 
        byte[] samples() {
            return samples;
        }
 
        int offset() {
            return offset;
        }
 
        int remaining() {
            return samples.length - offset;
        }
 
        void advance(int byteCount) {
            offset += byteCount;
        }
 
        boolean finished() {
            return offset >= samples.length;
        }
    }
 
    // ------------------------------------------------------------------------------------------
    // The frame pipeline. It lives in this file so the whole playback design can be read in one place.
    // The nested classes are package-private (not private) only so the tests can reach them.
    // ------------------------------------------------------------------------------------------
 
    /**
     * One frame on its way through the pipeline.
     *
     * Before the convert stage it holds a decoded frame (and no image). The convert stage replaces
     * it with a job that holds the finished image (and no frame). Exactly one thread owns a job at
     * any moment: it is handed from stage to stage through a queue, which is what makes it safe to
     * change the pixels without locks.
     */
    record FrameJob(Frame frame, InfoFrame info, Image image) {
 
        /** Sent after the last frame so every stage knows the video has ended. */
        static final FrameJob END = new FrameJob(null, null, null);
 
        /** Frees the native memory of the decoded frame. Safe on a job with no frame. */
        void close() {
            if (frame != null) {
                frame.close();
            }
        }
    }
 
    /**
     * One stage of the pipeline: a thread that takes a job from its input queue, does one piece of
     * work on it, and puts the result on its output queue.
     *
     * Why one thread per stage: an effect keeps scratch buffers that change while it runs, so it
     * must never process two frames at once. Its own thread guarantees that without locks, while
     * different frames can still be in different effects at the same time.
     */
    static final class PipelineStage {
 
        /** The work of a stage. An effect returns the same job; the convert stage returns a new one. */
        @FunctionalInterface
        interface Work {
            FrameJob apply(FrameJob job) throws Exception;
        }
 
        private final String name;
        private final BlockingQueue<FrameJob> input;
        private final BlockingQueue<FrameJob> output;
        private final Work work;
        private final Consumer<Exception> errorHandler;
        private Thread thread;
 
        PipelineStage(
                String name,
                BlockingQueue<FrameJob> input,
                BlockingQueue<FrameJob> output,
                Work work,
                Consumer<Exception> errorHandler) {
            this.name = name;
            this.input = input;
            this.output = output;
            this.work = work;
            this.errorHandler = errorHandler;
        }
 
        /** Starts a new thread. A stopped stage can be started again (used after a seek). */
        void start() {
            thread = new Thread(this::run, name);
            thread.setDaemon(true);
            thread.start();
        }
 
        /** Asks the thread to stop. It does not wait: call awaitStop() afterwards. */
        void interrupt() {
            if (thread != null) {
                thread.interrupt();
            }
        }
 
        /** Waits until the thread has really finished, so nothing is still using the effect. */
        void awaitStop() {
            if (thread == null) {
                return;
            }
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
 
        private void run() {
            // The job this thread currently owns. If we are stopped while holding one, we must free it
            // ourselves, because no other stage knows about it.
            FrameJob held = null;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    FrameJob job = input.take(); // waits while there is nothing to do
                    held = job;
 
                    if (job == FrameJob.END) {
                        output.put(job); // pass the end marker on, then this stage is finished
                        held = null;
                        return;
                    }
 
                    FrameJob result = work.apply(job);
                    if (result != job) {
                        job.close(); // the work made a new job, so the old frame is no longer needed
                    }
                    held = result;
 
                    output.put(result); // waits while the next stage is full (back-pressure)
                    held = null;
                }
            } catch (InterruptedException e) {
                // Asked to stop by a seek, stop or close. Nothing to report.
            } catch (Exception e) {
                errorHandler.accept(e);
            } finally {
                if (held != null) {
                    held.close();
                }
            }
        }
    }
 
    /**
     * Runs every effect, then the conversion to a JavaFX image, each on its own thread.
     *
     *   input -q-> [effect 1] -q-> [effect 2] -q-> ... -q-> [convert] -q-> output
     *
     * Speed is set by the slowest single stage, not by the sum of all of them, so adding an effect
     * does not slow playback unless that stage alone is slower than one video frame.
     *
     * By default each effect runs on its own thread, so each effect instance is used by exactly one
     * thread and needs no locks. The effects can optionally be grouped into fewer stages (see
     * groupEffects), but on TITAN grouping into cores-2 stages dropped frames at 40 effects, so it
     * is off by default. The order of the effects never changes.
     *
     * Why queues: a frame has exactly one owner at a time and the queue hands it over, so the pixels
     * need no locks. Every queue is bounded, so a fast stage waits for a slow one (back-pressure) and
     * memory cannot grow without limit. Frames stay in order because every queue is first-in
     * first-out and every stage is a single thread.
     */
    static final class FramePipeline {
 
        // Between stages one slot is enough, because each stage also holds one frame while it works.
        // A decoded frame is several MB, so a small number here keeps total memory down.
        private static final int STAGE_QUEUE_CAPACITY = 1;
        // The last queue feeds the display. It is larger so a slow frame does not show as a stutter.
        private static final int OUTPUT_QUEUE_CAPACITY = 6;
 
        // Rough cost of each effect on one HD frame, in microseconds, measured on a development
        // machine. Only the relative sizes matter: they decide how effects are grouped into stages.
        private static final Map<String, Integer> EFFECT_COST_US = Map.ofEntries(
                Map.entry("FrameNumberer", 25),
                Map.entry("FrameScratcher", 30),
                Map.entry("FrameFlickerer", 1200),
                Map.entry("FrameBlackAndWhiter", 4100),
                Map.entry("FrameYellower", 1850),
                Map.entry("FrameVignetter", 6200),
                Map.entry("FrameDuster", 60),
                Map.entry("FrameJitterer", 200),
                Map.entry("FrameMottler", 5600),
                Map.entry("FrameBleeder", 8300),
                Map.entry("FramePepperer", 470));
        private static final int UNKNOWN_EFFECT_COST_US = 1_000;
 
        /** Turns a processed frame into an image. An interface so tests can supply a fake without JavaFX. */
        @FunctionalInterface
        interface Converter {
            Image convert(Frame frame) throws Exception;
        }
 
        private final List<PipelineStage> stages = new ArrayList<>();
        private final List<BlockingQueue<FrameJob>> queues = new ArrayList<>();
        private final BlockingQueue<FrameJob> input;
        private final BlockingQueue<FrameJob> output;
 
        /**
         * @param processors   the effects in the order they must be applied. Each instance must appear
         *                     only once, because one instance is owned by one thread (the one running its group).
         * @param converter    turns a processed frame into an image. Only the convert stage uses it.
         * @param errorHandler called, from a pipeline thread, if a stage fails
         * @param maxEffectStages the most threads the effects may be spread over
         */
        FramePipeline(
                List<FrameProcessor> processors,
                Converter converter,
                Consumer<Exception> errorHandler,
                int maxEffectStages) {
            input = newQueue(STAGE_QUEUE_CAPACITY);
            BlockingQueue<FrameJob> previous = input;
 
            for (List<FrameProcessor> group : groupEffects(processors, maxEffectStages)) {
                BlockingQueue<FrameJob> next = newQueue(STAGE_QUEUE_CAPACITY);
                stages.add(new PipelineStage(
                        stageName(group),
                        previous,
                        next,
                        job -> {
                            for (FrameProcessor processor : group) { // in the order they were requested
                                processor.process(job.frame(), job.info()); // changes the pixels in place
                            }
                            return job;
                        },
                        errorHandler));
                previous = next;
            }
 
            // Convert is its own stage so the cost of building the image is not added to the last effect.
            output = newQueue(OUTPUT_QUEUE_CAPACITY);
            stages.add(new PipelineStage(
                    "convert",
                    previous,
                    output,
                    job -> new FrameJob(null, job.info(), converter.convert(job.frame())),
                    errorHandler));
        }
 
        // By default every effect gets its own thread. On TITAN this scored better than grouping effects
        // into cores-2 stages: with 40 effects the grouped version dropped frames, because one thread
        // per effect lets the operating system balance the unequal effects across all cores.
        // Override with -Dplayer.maxEffectStages=N to try grouping.
        static int defaultMaxEffectStages() {
            return Integer.getInteger("player.maxEffectStages", Integer.MAX_VALUE);
        }
 
        /**
         * Splits the effects, keeping their order, into at most maxGroups groups so that the most
         * expensive group is as cheap as possible. That group sets the speed of the whole pipeline.
         * It finds the smallest cost limit that fits in maxGroups groups, then packs to that limit.
         */
        static List<List<FrameProcessor>> groupEffects(List<FrameProcessor> processors, int maxGroups) {
            if (maxGroups >= processors.size()) {
                List<List<FrameProcessor>> single = new ArrayList<>();
                for (FrameProcessor processor : processors) {
                    single.add(List.of(processor)); // no limit reached: one thread per effect
                }
                return single;
            }
            long largest = 0;
            long total = 0;
            for (FrameProcessor processor : processors) {
                largest = Math.max(largest, costOf(processor));
                total += costOf(processor);
            }
 
            long low = largest;
            long high = total;
            while (low < high) {
                long limit = (low + high) / 2;
                if (pack(processors, limit).size() <= maxGroups) {
                    high = limit;
                } else {
                    low = limit + 1;
                }
            }
            return pack(processors, low);
        }
 
        // Fills each group in order until the next effect would push it over the limit.
        private static List<List<FrameProcessor>> pack(List<FrameProcessor> processors, long limit) {
            List<List<FrameProcessor>> groups = new ArrayList<>();
            List<FrameProcessor> current = new ArrayList<>();
            long currentCost = 0;
            for (FrameProcessor processor : processors) {
                long cost = costOf(processor);
                if (!current.isEmpty() && currentCost + cost > limit) {
                    groups.add(current);
                    current = new ArrayList<>();
                    currentCost = 0;
                }
                current.add(processor);
                currentCost += cost;
            }
            if (!current.isEmpty()) {
                groups.add(current);
            }
            return groups;
        }
 
        static long costOf(FrameProcessor processor) {
            return EFFECT_COST_US.getOrDefault(processor.getClass().getSimpleName(), UNKNOWN_EFFECT_COST_US);
        }
 
        private static String stageName(List<FrameProcessor> group) {
            StringBuilder name = new StringBuilder();
            for (FrameProcessor processor : group) {
                name.append(name.length() == 0 ? "" : "+").append(processor.getClass().getSimpleName());
            }
            return name.toString();
        }
 
        /** The producer side. Put copied frames here, then FrameJob.END. */
        BlockingQueue<FrameJob> input() {
            return input;
        }
 
        /** The consumer side. Takes finished images, in order. */
        BlockingQueue<FrameJob> output() {
            return output;
        }
 
        void start() {
            for (PipelineStage stage : stages) {
                stage.start();
            }
        }
 
        /**
         * Stops every stage and throws away any frames still in the queues. Call it only after the
         * producer has stopped, or new frames could arrive while it clears. When it returns no
         * pipeline thread is running, so the effects and the converter are safe to touch from the
         * calling thread. The pipeline can be started again.
         */
        void stop() {
            // Ask all stages to stop first, then wait for all of them: faster than one by one.
            for (PipelineStage stage : stages) {
                stage.interrupt();
            }
            for (PipelineStage stage : stages) {
                stage.awaitStop();
            }
            for (BlockingQueue<FrameJob> queue : queues) {
                FrameJob job;
                while ((job = queue.poll()) != null) {
                    job.close(); // free the native memory of frames that were never shown
                }
            }
        }
 
        private BlockingQueue<FrameJob> newQueue(int capacity) {
            BlockingQueue<FrameJob> queue = new ArrayBlockingQueue<>(capacity);
            queues.add(queue);
            return queue;
        }
    }
}
 	 