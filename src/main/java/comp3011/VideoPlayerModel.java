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
import java.util.List;
import java.util.Queue;
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
    
    // The decoder thread adds audio to this queue, and the UI thread removes it when it is due to be played,
    // so the queue is used by two threads and must be thread-safe. ConcurrentLinkedQueue is thread-safe,
    // keeps first-in-first-out order, and uses no locks, so the UI thread never has to wait for the decoder.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
 
    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };
 
    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    
    // The UI thread opens and closes the audio player, and the decoder thread reads it to see
    // whether audio is available. It is volatile so the decoder always sees the latest value.
    // Without volatile, the decoder could keep seeing an old value, which is either null or a
    // player that has already been closed. It is null while audio is off.
    private volatile AudioPlayer audioPlayer;
    
    // Set when playback is requested, and used to print how long the first frame took to appear.
    private long playRequestedNs; 
    
    // The startup time is printed once per start, not again after every seek.
    private boolean startupReported;
    
    // Runs the effects and the conversion, one thread each. A new pipeline is created for each playback.
    private FramePipeline pipeline; 
    private Thread decoderThread;
    
    // This is for UI thread only. The next finished frame taken from the pipeline, held here until its
    // due time. It is null when no frame is waiting.
    private FrameJob nextFrame; 
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    
    // The UI thread turns sound on and off, and the decoder thread reads this flag to decide
    // whether to decode audio. It is volatile so the decoder always sees the latest value after user click the sound button.
    // Without volatile, the decoder could keep seeing the old value, so it might keep queuing
    // audio after sound was turned off, or never start after it was turned on.
    private volatile boolean audioOutputEnabled;
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
        // Copy the list so the caller cannot change it while the decoder and effect threads are using it.
        // List.copyOf also makes the copy read-only, so no effect can be added or removed during playback 
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
        
        // Open the audio line only if sound is on, the video is playing, the file has audio,
        // and the line has not been opened yet, which means that audioPlayer is still null.
        if (audioOutputEnabled && playbackOpen && audioAvailable && audioPlayer == null) {
            try {
                openAudioPlayer();
            } catch (Exception e) {
                e.printStackTrace();
                // If the sound line cannot be opened, for example because there is no audio device,
                // turn sound off and keep playing the video without it.
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
                    this::reportPipelineError);
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
    
    
    // Opens the audio line. This is called only when sound is on, because opening and warming up
    // make the sound line is slow, and doing it every time made startup slower for users who never use sound.
    private void openAudioPlayer() throws Exception {
        AudioPlayer player = new AudioPlayer();
        player.open(grabber.getSampleRate(), grabber.getAudioChannels());
        
        // The player is stored in the shared field only after it is fully open, so the decoder
        // thread can never see a half-open audio line.
        audioPlayer = player; 
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
            // Asked to stop by a seek, stop or close quietly. The frame being handed over was already freed above.
        } catch (Exception e) {
            reportError(e, () -> decoderThread == self);
        }
    }
 
    // Called from a pipeline thread when an effect or the conversion fails.
    // The error only matters while a video is still open.
    private void reportPipelineError(Exception e) {
        reportError(e, () -> playbackOpen);
    }
 
    // Shows an error to the user. It can be called from any thread, but only the UI thread may
    // change the window, so the work is passed to it with runLater. By the time it runs, the
    // thread that failed may already have been stopped, for example, by a seek.
    private void reportError(Exception e, java.util.function.BooleanSupplier stillCurrent) {
        Platform.runLater(() -> {
            if (stillCurrent.getAsBoolean()) {
                handlePlaybackError(e);
            }
        });
    }
 
    // Runs on the decoder thread only, and reads from the file until it finds a video frame and returns.
    // Then, it ready for the pipeline. Returns null when the video has ended or we were asked to stop.
    private FrameJob readNextVideoFrame() throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = grabFrame();
            if (frame == null) {
                return null;
            }
 
            long timestampUs = grabber.getTimestamp();
            
            // Sound is queued here, and the UI thread plays it when its time comes.
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }
            
            // If this frame only had sound and no picture, so go back and read the next one.
            if (frame.image == null) {
                continue;
            }
 
            if (!frameProcessorsInitialised) {
            	// The effects need to know the video size and format, and we only learn that from
                // the first picture. So they are set up here, before the first frame goes into the
                // pipeline. Putting that frame into the queue then makes the set-up visible to the
                // effect threads.
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
 
    // It run on the UI thread many times a second and never decodes and never waits. 
    // It plays any audio that is due, takes the next finished frame if there is one, and shows it when its
    // time has come.
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
    
    // Shows the frame that is due and empties the slot so the next frame can be taken.
    private void displayNextFrame(long now, long targetTimeNs) {
        FrameJob frame = nextFrame;
        nextFrame = null;
        
        // The position shown to the user is where playback started or was seeked to, plus how far
        // this frame is past the first frame shown. Math.max(0, ...) stops a negative value if a
        // frame's timestamp is earlier than the first one.
        currentTimestampUs = logicalPlaybackBaseUs + Math.max(0, frame.info().timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = NO_SEEK_REQUEST;
 
        // Once per second: how late the frame was shown, and how many frames are waiting.
        if (DEBUG_TIMING && intFrameRate > 0 && frame.info().frameNumber() % intFrameRate == 0) {
            long lateUs = (now - targetTimeNs) / 1_000L;
            System.out.printf("Frame headroom %5dus out of %dus spare, queue depth %d%n",
                    videoFrameDurationUs - lateUs,        
                    videoFrameDurationUs,                 
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
 
}
 	 