/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: unit tests that show the threaded frame pipeline is correct.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import comp3011.VideoPlayerModel.FrameJob;
import comp3011.VideoPlayerModel.FramePipeline;

/**
 * Each test names one promise the design makes: frames stay in order, effects run in the requested
 * order, no effect ever handles two frames at once, memory is bounded, stop leaves nothing running,
 * and errors are reported. They use small synthetic frames and no JavaFX, so they run anywhere.
 */
class FramePipelineTest {
    private static final Duration LIMIT = Duration.ofSeconds(30); // a hang must fail the test, not freeze the build

    private final List<FramePipeline> started = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        for (FramePipeline pipeline : started) {
            pipeline.stop();
        }
    }

    // ---- helpers ----

    /** A tiny frame whose first byte holds {@code number}. */
    private static FrameJob job(int number) {
        Frame frame = new Frame(4, 2, Frame.DEPTH_UBYTE, 3);
        ((ByteBuffer) frame.image[0]).put(0, (byte) number);
        return new FrameJob(frame, new InfoFrame(number, number * 41_667L), null);
    }

    private FramePipeline start(List<FrameProcessor> effects, int maxStages, List<Exception> errors,
            ConcurrentLinkedQueue<Integer> converted) {
        FramePipeline pipeline = new FramePipeline(
                effects,
                frame -> {
                    // The convert stage is a single thread, so this records frames in the order they arrive.
                    converted.add(((ByteBuffer) frame.image[0]).get(0) & 0xff);
                    return null;
                },
                errors::add,
                maxStages);
        started.add(pipeline);
        pipeline.start();
        return pipeline;
    }

    /** Feeds {@code count} frames then END, and returns the frame numbers that come out. */
    private static List<Integer> runFrames(FramePipeline pipeline, int count) throws Exception {
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    pipeline.input().put(job(i));
                }
                pipeline.input().put(FrameJob.END);
            } catch (InterruptedException e) {
                // test is being torn down
            }
        });
        producer.start();

        List<Integer> numbers = new ArrayList<>();
        FrameJob out;
        while ((out = pipeline.output().take()) != FrameJob.END) {
            numbers.add(out.info().frameNumber());
        }
        producer.join();
        return numbers;
    }

    /** Changes the first byte so that the order effects run in changes the result. */
    private static class OrderSensitiveEffect extends FrameProcessor {
        private final int k;

        OrderSensitiveEffect(int k) {
            this.k = k;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            pixels.put(0, (byte) (pixels.get(0) * 3 + k));
        }
    }

    /** Detects two frames inside it at once, and remembers which threads called it. */
    private static class ExclusiveUseEffect extends FrameProcessor {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger overlaps = new AtomicInteger();
        final Set<Thread> threads = Collections.synchronizedSet(new HashSet<>());

        @Override
        public void process(Frame frame, InfoFrame info) throws Exception {
            threads.add(Thread.currentThread());
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            Thread.sleep(1); // long enough that a second frame would arrive while we are busy
            inside.decrementAndGet();
        }
    }

    /** A named effect so its threads can be found by name. */
    private static class ProbeEffect extends FrameProcessor {
        @Override
        public void process(Frame frame, InfoFrame info) throws Exception {
            Thread.sleep(1);
        }
    }

    private static class FailingEffect extends FrameProcessor {
        @Override
        public void process(Frame frame, InfoFrame info) throws Exception {
            if (info.frameNumber() == 3) {
                throw new Exception("boom on frame 3");
            }
        }
    }

    private static long liveThreadsNamed(String part) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().contains(part))
                .count();
    }

    // ---- tests ----

    @Test
    @DisplayName("Frames come out in the order they went in")
    void framesStayInOrder() {
        assertTimeoutPreemptively(LIMIT, () -> {
            List<FrameProcessor> effects = List.of(new ProbeEffect(), new ProbeEffect(), new ProbeEffect());
            FramePipeline pipeline = start(effects, 3, new ArrayList<>(), new ConcurrentLinkedQueue<>());

            List<Integer> numbers = runFrames(pipeline, 100);

            assertEquals(100, numbers.size());
            for (int i = 0; i < numbers.size(); i++) {
                assertEquals(i, numbers.get(i), "frame " + i + " came out in the wrong place");
            }
        });
    }

    @Test
    @DisplayName("Effects are applied in the requested order, however many threads they are spread over")
    void effectsRunInRequestedOrder() {
        assertTimeoutPreemptively(LIMIT, () -> {
            for (int maxStages : new int[] { 1, 3, 100 }) {
                List<FrameProcessor> effects = new ArrayList<>();
                for (int k = 1; k <= 8; k++) {
                    effects.add(new OrderSensitiveEffect(k));
                }
                ConcurrentLinkedQueue<Integer> converted = new ConcurrentLinkedQueue<>();
                FramePipeline pipeline = start(effects, maxStages, new ArrayList<>(), converted);

                runFrames(pipeline, 50);

                List<Integer> actual = new ArrayList<>(converted);
                assertEquals(50, actual.size());
                for (int frame = 0; frame < 50; frame++) {
                    int expected = frame; // what running the 8 effects one after another gives
                    for (int k = 1; k <= 8; k++) {
                        expected = ((byte) (expected * 3 + k)) & 0xff;
                    }
                    assertEquals(expected, actual.get(frame), "frame " + frame + " with " + maxStages + " stage(s)");
                }
            }
        });
    }

    @Test
    @DisplayName("Each effect is used by one thread and never by two frames at once")
    void eachEffectHasOneOwner() {
        assertTimeoutPreemptively(LIMIT, () -> {
            ExclusiveUseEffect first = new ExclusiveUseEffect();
            ExclusiveUseEffect second = new ExclusiveUseEffect();
            ExclusiveUseEffect third = new ExclusiveUseEffect();
            FramePipeline pipeline = start(List.of(first, second, third), 3, new ArrayList<>(),
                    new ConcurrentLinkedQueue<>());

            runFrames(pipeline, 60);

            for (ExclusiveUseEffect effect : List.of(first, second, third)) {
                assertEquals(1, effect.threads.size(), "an effect was called from more than one thread");
                assertEquals(0, effect.overlaps.get(), "an effect handled two frames at the same time");
            }
        });
    }

    @Test
    @DisplayName("Effects are grouped without losing, repeating or reordering any of them")
    void groupingKeepsEveryEffectInOrder() {
        List<FrameProcessor> effects = new ArrayList<>();
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            effects.add(option.create());
        }
        List<FrameProcessor> all = new ArrayList<>(effects);
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            all.add(option.create()); // the same list requested twice: 22 effects
        }

        for (int maxStages : new int[] { 1, 4, 6, 100 }) {
            List<List<FrameProcessor>> groups = FramePipeline.groupEffects(all, maxStages);

            assertTrue(groups.size() <= maxStages, "too many stages for a limit of " + maxStages);
            List<FrameProcessor> flattened = new ArrayList<>();
            long bottleneck = 0;
            for (List<FrameProcessor> group : groups) {
                assertFalse(group.isEmpty(), "an empty stage");
                flattened.addAll(group);
                bottleneck = Math.max(bottleneck, group.stream().mapToLong(FramePipeline::costOf).sum());
            }
            assertEquals(all, flattened, "effects were lost, repeated or reordered");

            // The slowest stage sets the speed, so it should not be much above an even share of the work.
            long total = all.stream().mapToLong(FramePipeline::costOf).sum();
            long largest = all.stream().mapToLong(FramePipeline::costOf).max().orElse(0);
            assertTrue(bottleneck <= total / Math.min(maxStages, all.size()) + largest,
                    "stages are badly balanced: slowest " + bottleneck + "us of " + total + "us in total");
        }
    }

    @Test
    @DisplayName("Memory is bounded: a stalled display holds the pipeline back instead of letting frames pile up")
    void queuesAreBounded() {
        assertTimeoutPreemptively(LIMIT, () -> {
            FramePipeline pipeline = start(List.of(new ProbeEffect(), new ProbeEffect()), 2, new ArrayList<>(),
                    new ConcurrentLinkedQueue<>());

            // Nobody reads the output, like a display that has stopped. Count what the pipeline will take.
            int accepted = 0;
            while (pipeline.input().offer(job(accepted), 300, TimeUnit.MILLISECONDS)) {
                accepted++;
                assertTrue(accepted < 100, "the pipeline never pushed back");
            }

            // 1 input slot + 2 effect stages + 6 display slots, and each of the 3 threads holds one frame.
            assertTrue(accepted >= 6, "should at least fill the display queue, took " + accepted);
            assertTrue(accepted <= 15, "took " + accepted + " frames; memory is not bounded");

            // Once the display reads again, frames flow again.
            for (int i = 0; i < accepted; i++) {
                assertEquals(i, pipeline.output().take().info().frameNumber());
            }
            assertTrue(pipeline.input().offer(job(accepted), 2, TimeUnit.SECONDS), "did not resume after reading");
        });
    }

    @Test
    @DisplayName("Stop ends every thread and empties every queue, and the pipeline can then be started again")
    void stopLeavesNothingRunningAndRestartWorks() {
        assertTimeoutPreemptively(LIMIT, () -> {
            FramePipeline pipeline = start(List.of(new ProbeEffect(), new ProbeEffect()), 2, new ArrayList<>(),
                    new ConcurrentLinkedQueue<>());

            for (int i = 0; i < 6; i++) {
                pipeline.input().put(job(i)); // leave frames in flight
            }
            Thread.sleep(50);
            pipeline.stop();

            assertEquals(0, liveThreadsNamed("ProbeEffect"), "an effect thread is still running");
            assertEquals(0, liveThreadsNamed("convert"), "the convert thread is still running");
            assertTrue(pipeline.input().isEmpty() && pipeline.output().isEmpty(), "frames were left in the queues");

            // Same pipeline, started again, as after a seek.
            pipeline.start();
            List<Integer> numbers = runFrames(pipeline, 20);
            assertEquals(20, numbers.size());
            assertEquals(0, numbers.get(0));
        });
    }

    @Test
    @DisplayName("An effect that fails is reported, and the pipeline can still be stopped")
    void failureIsReported() {
        assertTimeoutPreemptively(LIMIT, () -> {
            List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
            FramePipeline pipeline = start(List.of(new FailingEffect()), 1, errors, new ConcurrentLinkedQueue<>());

            for (int i = 0; i < 5; i++) {
                pipeline.input().offer(job(i), 300, TimeUnit.MILLISECONDS);
            }
            Thread.sleep(200);

            assertEquals(1, errors.size(), "the failure should be reported once");
            assertEquals("boom on frame 3", errors.get(0).getMessage());
            pipeline.stop();
            assertEquals(0L, liveThreadsNamed("FailingEffect"));
        });
    }

    @Test
    @DisplayName("With no effects the frames still get through")
    void worksWithNoEffects() {
        assertTimeoutPreemptively(LIMIT, () -> {
            FramePipeline pipeline = start(List.of(), 4, new ArrayList<>(), new ConcurrentLinkedQueue<>());

            assertEquals(List.of(0, 1, 2, 3, 4), runFrames(pipeline, 5));
        });
    }
}