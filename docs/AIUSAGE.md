# AI usage

Tool used: Claude Sonnet 5.5 (Anthropic), through Claude Code.

## What I did myself
- Researched why the starter code fell behind real time and where the time went.
- Chose and revised the architecture: a pipeline with one thread per effect, joined by bounded queues.
- Ran every build, test and TITAN submission, read the results, and decided what to change next.
- Drew the architecture diagram and wrote the explanations in my own words.
- Reviewed the code and comments, and corrected mistakes (for example the debug print format and the test imports).

## What the AI did
- Explained the starter code, and explained ideas such as `volatile`, `ConcurrentLinkedQueue`, back-pressure and why effects need an owner thread.
- Recommended me to add those classes (`FramePipeline`, `PipelineStage`, `FrameJob`), the unit tests and the load test. It also support on providing information where I should code, run, and change those classes and tests.
- Helped improving code comments, the README and the testing notes.
- Suggested commit messages.
- Suggested and measured alternatives that did not work (grouping effects, a worker pool), which are described in the README.
