# Testing

Tests are in `src/test/java/comp3011/`. Run the quick unit tests with `mvn package`, and everything including the load test with `mvn verify`. In Eclipse, use Run As > Maven build and type `verify` in Goals.

| Test | What it shows |
|---|---|
| `FramePipelineTest` | Frames stay in order; effects run in the requested order, whether there is 1, 3 or 8 of them; no more effects work at once than the limit allows; each effect has one owner and never handles two frames at once; a stalled display holds the pipeline back (memory is bounded); stop leaves no threads or queued frames and a restart works; a failing effect is reported once; no effects still works |
| `CommandLineControllerTest` | Effects keep the order typed and can be repeated, and each repeat is a separate object; short flags, long names and separate options give the same result; every effect in `FrameProcessorOption` can be parsed by its short flag and its long name; audio, maximise and display options are read next to the effects; an unknown option, a wrong-case name, a missing file, no file, two files, or two different displays are rejected; `--help` on its own is not an error and lists every effect. The total of the test is 10. |
| `PipelineLoadIT` | Runs 22 real effects through the single-threaded way and through the pipeline. It checks the frames are **pixel-identical**, prints frames per second for both, and on 4 or more cores requires the pipeline to be at least 1.2 times faster |

`PipelineDemo` is not a test. It is a small program that prints when each frame finishes, for the single-threaded way and for the pipeline, using fake effects that only wait. Run it from Eclipse with Run As > Java Application. See "Frame-by-frame demo" below.

## Why these tests

- The unit tests use small fake effects and a fake converter, so they run in about a second and do not need JavaFX or a video file.
- Every test that waits on threads has a time limit, so a hang fails the test and does not freeze the build.
- The load test is named `*IT`, so it only runs under `mvn verify`. It uses the real effects, because correctness (same pixels as the single-threaded way) matters as much as speed.
- The speed check is skipped on machines with fewer than 4 cores, because the pipeline cannot be faster without cores to share the work.

## Load test

The load test (`mvn verify`) compares both ways on your own machine. Result on 8 cores, with 22 effects and 120 frames of 960x540:

![load tests](/assets/load_tests.png)

The test also checks that the pipeline gives exactly the same pixels, in the same order, as the single-threaded way. The speed-up depends on the machine and on how busy it is. On my machine it has ranged from about 2.7 to 6.1 times faster. On 4 or more cores the test requires the pipeline to be at least 1.2 times faster, not an exact number, so it does not fail by accident on a busy machine.  

## Frame-by-frame demo

The load test shows the result. `PipelineDemo` shows the reason. It runs three fake effects that only wait **10 ms**,
**20 ms** and **10 ms**, so one frame needs **40 ms** to pass all of them. Then, it prints when each frame finishes, first the original way
and then through the pipeline. Run it from Eclipse with Run As > Java Application.

![comparison-version](/assets/comparison_version.png)

Both ways are run once without timing first, so that Java loading classes is not counted as frame time.

The first frame is not faster, because it still has to pass every effect in order. While one
frame is in the second effect, the next frame is already in the first, so a finished frame comes out every 20 ms instead of every 40 ms.

Starting the pipeline's threads costs a small amount of time, once, while the old way has no threads to start. The demo waits 300 ms for the threads to start before it begins timing, so this one-off cost is not counted in the first frame. It does not change the 20 ms rate that every later frame shows.