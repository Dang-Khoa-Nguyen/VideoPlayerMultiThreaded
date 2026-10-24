/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: a piece of decoded sound that waits for the UI thread to play it.
 */

package comp3011;

/**
 * A piece of decoded sound waiting to be played. The decoder thread creates it and the UI thread plays it.
 * Only the UI thread calls advance(), so offset needs no synchronisation.
 */
public class PendingAudio {
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