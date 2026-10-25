/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: load test that compares the pipeline with the original
 * single-threaded way of running the same effects. Runs under "mvn verify".
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * Runs all eleven real effects, twice over (22 effects), on synthetic frames. It does the same work
 * the original way (every effect one after another on one thread) and through the pipeline, then:
 *
 *   1. checks every frame is identical, pixel for pixel (a checksum of each finished frame), and
 *   2. reports frames per second for both, and checks the pipeline is faster on a multi-core machine.
 *
 * Named *IT so Maven runs it in the "verify" step and not during "package".
 */
class PipelineLoadIT {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int FRAMES = 120;
    private static final int WARM_UP_FRAMES = 10;

    private static Frame frame(int number) {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) (i * 31 + number * 7)); // a repeatable pattern that changes every frame
        }
        return frame;
    }

    private static long checksum(Frame frame) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    @Test
    @DisplayName("The pipeline gives identical frames to the single-threaded way, and plays them faster")
    void pipelineMatchesSingleThreadedAndIsFaster() throws Exception {
        List<FrameProcessor> effects = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (FrameProcessorOption option : FrameProcessorOption.values()) {
                effects.add(option.create());
            }
        }
        Frame probe = frame(0);
        InfoVideo info = new InfoVideo("load-test", FRAMES, WIDTH, HEIGHT, probe.imageDepth, probe.imageChannels,
                probe.imageStride, 24.0, 24, 41_667, 0);
        for (FrameProcessor effect : effects) {
            effect.initialise(info);
        }

        // Warm up so the JIT compiler has seen the effects before anything is timed.
        for (int n = 0; n < WARM_UP_FRAMES; n++) {
            Frame warm = frame(n);
            for (FrameProcessor effect : effects) {
                effect.process(warm, new InfoFrame(n, 0));
            }
        }

        // 1. The original way: every effect, one after another, on one thread.
        List<Long> singleThreaded = new ArrayList<>();
        long startNs = System.nanoTime();
        for (int n = 0; n < FRAMES; n++) {
            Frame f = frame(n);
            for (FrameProcessor effect : effects) {
                effect.process(f, new InfoFrame(n, n * 41_667L));
            }
            singleThreaded.add(checksum(f));
        }
        double singleFps = FRAMES / ((System.nanoTime() - startNs) / 1e9);

        // 2. The pipeline, using the same effect objects.
        ConcurrentLinkedQueue<Long> piped = new ConcurrentLinkedQueue<>();
        FramePipeline pipeline = new FramePipeline(effects, f -> {
            piped.add(checksum(f));
            return null;
        }, e -> {
            throw new RuntimeException(e);
        });
        pipeline.start();
        try {
            startNs = System.nanoTime();
            Thread producer = new Thread(() -> {
                try {
                    for (int n = 0; n < FRAMES; n++) {
                        pipeline.input().put(new FrameJob(frame(n), new InfoFrame(n, n * 41_667L), null));
                    }
                    pipeline.input().put(FrameJob.END);
                } catch (InterruptedException e) {
                    // test is being torn down
                }
            });
            producer.start();
            while (pipeline.output().take() != FrameJob.END) {
                // frames are checked through the checksum recorded by the convert stage
            }
            double pipelineFps = FRAMES / ((System.nanoTime() - startNs) / 1e9);
            producer.join();

            int cores = Runtime.getRuntime().availableProcessors();
            System.out.printf("%nLoad test: %d effects, %d frames of %dx%d, %d cores, one thread per effect%n"
                    + "  single-threaded: %6.1f frames/s%n  pipeline:        %6.1f frames/s  (%.2fx)%n",
                    effects.size(), FRAMES, WIDTH, HEIGHT, cores, singleFps, pipelineFps,
                    pipelineFps / singleFps);

            // Correctness first: the same pixels, in the same order.
            assertEquals(singleThreaded, new ArrayList<>(piped), "the pipeline produced different frames");

            // Speed only means something with enough cores to share the work.
            assumeTrue(cores >= 4, "speed check skipped: only " + cores + " core(s)");
            assertTrue(pipelineFps >= 1.2 * singleFps,
                    String.format("pipeline %.1f frames/s is not clearly faster than single-threaded %.1f frames/s",
                            pipelineFps, singleFps));
        } finally {
            pipeline.stop();
        }
    }
}