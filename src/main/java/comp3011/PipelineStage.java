/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: one thread of the pipeline: take a job, do one piece of work, hand it on.
 */


package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;


/**
 * One stage of the pipeline. It is a thread that takes a job from its input queue, does one piece of
 * work on it, and puts the result on its output queue.
 *
 * <p>
 * {@link FramePipeline} creates one of these for every effect and one for the convert step, and joins
 * them with bounded queues.
 * </p>
 *
 * <p>
 * Each stage has its own thread because an effect keeps scratch buffers that change while it runs, so it
 * must never process two frames at once. Its own thread guarantees that without locks, while different
 * frames can still be in different effects at the same time.
 * </p>
 */
public class PipelineStage {

    /** The work of a stage. An effect returns the same job; the convert stage returns a new one. */
    @FunctionalInterface
    interface Work {
        FrameJob apply(FrameJob job) throws Exception;
    }

    private final String name;
    private final int priority; // the thread priority this stage runs at
    private final BlockingQueue<FrameJob> input;
    private final BlockingQueue<FrameJob> output;
    private final Work work;
    private final Consumer<Exception> errorHandler;
    private Thread thread;

    PipelineStage(
            String name,
            int priority,
            BlockingQueue<FrameJob> input,
            BlockingQueue<FrameJob> output,
            Work work,
            Consumer<Exception> errorHandler) {
        this.name = name;
        this.priority = priority;
        this.input = input;
        this.output = output;
        this.work = work;
        this.errorHandler = errorHandler;
    }

    /** Starts a new thread. A stopped stage can be started again (used after a seek). */
    void start() {
        thread = new Thread(this::run, name);
        thread.setDaemon(true);
        thread.setPriority(priority);
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