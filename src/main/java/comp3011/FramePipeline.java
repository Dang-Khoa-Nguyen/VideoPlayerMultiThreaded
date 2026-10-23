package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Runs every effect, then the conversion to a JavaFX image, each on its own thread.
 *
 *   input -q-> [effect 1] -q-> [effect 2] -q-> ... -q-> [convert] -q-> output
 *
 * Speed is set by the slowest single stage, not by the sum of all of them, so adding an effect
 * does not slow playback unless that stage alone is slower than one video frame.
 *
 * Each effect runs on its own thread, so each effect instance is used by exactly one thread and
 * needs no locks. Grouping several effects into fewer threads was tried and removed: on TITAN it
 * dropped frames at 40 effects. The order of the effects never changes.
 *
 * Keeping the machine responsive: with many effects there are far more busy threads than cores.
 * Two things stop them from taking every core. The effect threads run a little below normal
 * priority, and at most (cores - 1) of them may be inside an effect at the same moment. That
 * leaves one core free for the UI thread, the decoder, the convert stage and anything else on
 * the machine (for example a tool that takes screenshots of the window), so the window keeps
 * being drawn on time even when every effect is busy.
 *
 * Why queues: a frame has exactly one owner at a time and the queue hands it over, so the pixels
 * need no locks. Every queue is bounded, so a fast stage waits for a slow one (back-pressure) and
 * memory cannot grow without limit. Frames stay in order because every queue is first-in
 * first-out and every stage is a single thread.
 */
public class FramePipeline {

    // Between stages one slot is enough, because each stage also holds one frame while it works.
    // A decoded frame is several MB, so a small number here keeps total memory down.
    private static final int STAGE_QUEUE_CAPACITY = 1;
    // The last queue feeds the display. It is larger so a slow frame does not show as a stutter.
    private static final int OUTPUT_QUEUE_CAPACITY = 6;

    // Effect threads run a little below normal priority. With many effects there are more busy
    // threads than cores, and this lets the UI, decoder and convert threads get a core on time,
    // so frames are still drawn on time when the machine is full.
    private static final int EFFECT_THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;

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
     * Uses the default limit on how many effects may work at once (see defaultMaxConcurrentEffects).
     *
     * @param processors   the effects in the order they must be applied. Each instance must appear
     *                     only once, because one instance is owned by exactly one thread.
     * @param converter    turns a processed frame into an image. Only the convert stage uses it.
     * @param errorHandler called, from a pipeline thread, if a stage fails
     */
    FramePipeline(
            List<FrameProcessor> processors,
            Converter converter,
            Consumer<Exception> errorHandler) {
        this(processors, converter, errorHandler, defaultMaxConcurrentEffects());
    }

    /**
     * @param maxConcurrentEffects the most effects that may be working at the same moment. Every effect
     *                             still has its own thread, but a thread must hold one of these permits
     *                             while it is inside its effect, so the rest wait without using a core.
     */
    FramePipeline(
            List<FrameProcessor> processors,
            Converter converter,
            Consumer<Exception> errorHandler,
            int maxConcurrentEffects) {
        // Fair, so the effect that has waited longest goes first and no frame is held up for ever.
        Semaphore workPermits = new Semaphore(Math.max(1, maxConcurrentEffects), true);

        input = newQueue(STAGE_QUEUE_CAPACITY);
        BlockingQueue<FrameJob> previous = input;

        for (FrameProcessor processor : processors) {
            BlockingQueue<FrameJob> next = newQueue(STAGE_QUEUE_CAPACITY);
            stages.add(new PipelineStage(
                    processor.getClass().getSimpleName(), // the thread name, to tell stages apart when debugging
                    EFFECT_THREAD_PRIORITY,
                    previous,
                    next,
                    job -> {
                        // Wait for a turn. A thread holds the permit only while it is inside its effect and never
                        // while it waits on a queue, so permits cannot deadlock. If the stage is interrupted while
                        // waiting, acquire() throws and the stage ends quietly like any other stop.
                        workPermits.acquire();
                        try {
                            processor.process(job.frame(), job.info()); // changes the pixels in place
                        } finally {
                            workPermits.release();
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
                Thread.NORM_PRIORITY, // the convert stage is on the display's critical path, so normal priority
                previous,
                output,
                job -> new FrameJob(null, job.info(), converter.convert(job.frame())),
                errorHandler));
    }

    /**
     * How many effects may work at once: one less than the number of cores, so one core stays free for
     * the UI thread and everything else. Override with -Dplayer.maxConcurrentEffects=N to experiment.
     */
    static int defaultMaxConcurrentEffects() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Integer.getInteger("player.maxConcurrentEffects", Math.max(1, cores - 1));
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