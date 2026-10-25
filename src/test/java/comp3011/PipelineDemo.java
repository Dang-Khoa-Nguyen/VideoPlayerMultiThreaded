/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: a small demo that shows, frame by frame,
 * the difference between the old approach, where effects run one after another,
 * and the new approach, where each effect runs in its own thread.
 */
package comp3011;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Run it from Eclipse (Run As > Java Application) or with the pom's exec/test classpath. It uses fake
 * effects that only wait, so the numbers are easy to read: no real pixels, no JavaFX, no video.
 *
 *<p>
 * Three effects take 10 ms, 20 ms and 10 ms. One frame needs 40 ms to pass all three.
 * It prints the moment each finished frame comes out, for both designs.
 *</p>
 */
public class PipelineDemo {
    private static final int FRAMES = 8;
    private static final int[] EFFECT_MS = { 10, 20, 10 };

    /** A fake effect: it does nothing to the pixels, it just takes a fixed time. */
    private static class SlowEffect extends FrameProcessor {
        private final int millis;

        SlowEffect(int millis) {
            this.millis = millis;
        }

        @Override
        public void process(Frame frame, InfoFrame info) throws Exception {
            Thread.sleep(millis);
        }
    }

    private static List<FrameProcessor> effects() {
        List<FrameProcessor> list = new ArrayList<>();
        for (int ms : EFFECT_MS) {
            list.add(new SlowEffect(ms));
        }
        return list;
    }

    private static Frame smallFrame() {
        Frame frame = new Frame(4, 2, Frame.DEPTH_UBYTE, 3);
        ((ByteBuffer) frame.image[0]).put(0, (byte) 0);
        return frame;
    }

    public static void main(String[] args) throws Exception {
        int sum = 0;
        int slowest = 0;
        for (int ms : EFFECT_MS) {
            sum += ms;
            slowest = Math.max(slowest, ms);
        }
        System.out.printf("Effects take %s ms. One frame needs %d ms to pass all of them. The slowest effect is %d ms.%n%n",
                java.util.Arrays.toString(EFFECT_MS), sum, slowest);

        // ==== OLD: one thread, one frame goes through every effect, then the next frame starts ====
        System.out.println("OLD: one frame goes through all effects, then the next frame starts");
        List<FrameProcessor> oldEffects = effects();
        
        // Run the old way a few times without timing it to warm it up, so Java loading classes is not counted as frame time.
        for (int n = 0; n < 3; n++) {
            Frame warm = smallFrame();
            for (FrameProcessor effect : oldEffects) {
                effect.process(warm, new InfoFrame(n, 0));
            }
            warm.close();
        }
        
        long start = System.nanoTime();
        long previous = start;
        for (int n = 1; n <= FRAMES; n++) {
            Frame frame = smallFrame();
            for (FrameProcessor effect : oldEffects) {
                effect.process(frame, new InfoFrame(n, 0));
            }
            long now = System.nanoTime();
            System.out.printf("  frame %d finished at %4d ms   (%3d ms after the previous one)%n",
                    n, (now - start) / 1_000_000, (now - previous) / 1_000_000);
            previous = now;
            frame.close();
        }

        // ===== NEW: one thread per effect, different frames are in different effects at the same time =====
        System.out.println();
        System.out.println("NEW: one thread per effect, several frames are being worked on at once");
        FramePipeline pipeline = new FramePipeline(effects(), f -> null, e -> e.printStackTrace(), EFFECT_MS.length);
        pipeline.start();
        
        // let the threads start first, so thread start-up is not counted as frame time. 
        // This is a pay cost when applying the architecture.
        Thread.sleep(300); 
        Thread producer = new Thread(() -> {
            try {
                for (int n = 1; n <= FRAMES; n++) {
                    pipeline.input().put(new FrameJob(smallFrame(), new InfoFrame(n, 0), null));
                }
                pipeline.input().put(FrameJob.END);
            } catch (InterruptedException e) {
                // The demo is ending
            }
        });
        start = System.nanoTime();
        previous = start;
        producer.start();
        FrameJob out;
        while ((out = pipeline.output().take()) != FrameJob.END) {
            long now = System.nanoTime();
            System.out.printf("  frame %d finished at %4d ms   (%3d ms after the previous one)%n",
                    out.info().frameNumber(), (now - start) / 1_000_000, (now - previous) / 1_000_000);
            previous = now;
        }
        producer.join();
        pipeline.stop();

        System.out.println();
        System.out.printf("Old: a finished frame every about %d ms (the sum of the effects).%n", sum);
        System.out.printf("New: the first frame still takes about %d ms, then a finished frame every about %d ms (the slowest effect).%n",
                sum, slowest);
    }
}