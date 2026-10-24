/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: one frame on its way through the pipeline, and the END marker.
 */

package comp3011;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * One frame on its way through the pipeline.
 *
 * Before the convert stage it holds a decoded frame and no image. The convert stage replaces
 * it with a job that holds the finished image and no frame. Exactly one thread owns a job at
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