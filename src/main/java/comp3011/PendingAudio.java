package comp3011;

// The decoder thread creates these and the UI thread consumes them. Only the UI thread
// calls advance(), so offset needs no synchronisation.
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