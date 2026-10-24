/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: regression tests for the command line (Part 1).
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These tests check that effects are applied in the order entered, can be
 * repeated, and work the same with short and long option names. They also
 * verify that invalid input is rejected and that the help text includes all
 * available effects.
 *
 * No video is played. A real (empty) file is needed only because the parser
 * checks that the input file exists.
 */
class CommandLineControllerTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final List<File> tempFiles = new ArrayList<>();

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        for (File file : tempFiles) {
            file.delete();
        }
    }

    // ====== helpers ======

    /** The parser prints errors and help, so capture that here to keep the test output clean and checkable. */
    private CommandLineController parse(String... args) {
        System.setOut(new PrintStream(printed));
        return new CommandLineController(args);
    }

    private String printedText() {
        return printed.toString();
    }

    private File videoFile() throws Exception {
        File file = File.createTempFile("commandline-test", ".mp4");
        tempFiles.add(file);
        return file;
    }

    private static List<Class<?>> effectTypes(CommandLineController controller) {
        List<Class<?>> types = new ArrayList<>();
        for (FrameProcessor processor : controller.getFrameProcessors()) {
            types.add(processor.getClass());
        }
        return types;
    }

    // ====== Tests ======

    @Test
    @DisplayName("Effects are applied in the order they are typed, and can be repeated")
    void effectsKeepTheOrderTyped() throws Exception {
        CommandLineController controller = parse("-nssnw", videoFile().getPath());

        assertEquals(
                List.of(FrameNumberer.class, FrameScratcher.class, FrameScratcher.class, FrameNumberer.class,
                        FrameBlackAndWhiter.class),
                effectTypes(controller));
        assertTrue(controller.shouldLaunchApplication(), "a valid command line must launch the player");
    }

    @Test
    @DisplayName("A repeated effect is a separate object each time, because one effect object has one owner thread")
    void repeatedEffectsAreSeparateObjects() throws Exception {
        CommandLineController controller = parse("-ss", videoFile().getPath());

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(2, processors.size());
        assertFalse(processors.get(0) == processors.get(1), "the same instance must never appear twice");
    }

    @Test
    @DisplayName("Short flags, long names and separate options give the same effects in the same order")
    void shortAndLongNamesAgree() throws Exception {
        String path = videoFile().getPath();

        List<Class<?>> stacked = effectTypes(parse("-dj", path));
        List<Class<?>> separate = effectTypes(parse("-d", "-j", path));
        List<Class<?>> longNames = effectTypes(parse("--dust-frames", "--jitter-frames", path));

        assertEquals(List.of(FrameDuster.class, FrameJitterer.class), stacked);
        assertEquals(stacked, separate);
        assertEquals(stacked, longNames);
    }

    @Test
    @DisplayName("Every effect in FrameProcessorOption can be reached by its short flag and its long name")
    void everyOptionCanBeParsed() throws Exception {
        String path = videoFile().getPath();
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            Class<?> expected = option.create().getClass();
            String helpLine = option.helpLine();                       // like "-n, --number-frames"
            String shortFlag = helpLine.trim().substring(0, 2);        // "-n"
            String longName = helpLine.trim().split("[ ,]+")[1];       // "--number-frames"

            assertEquals(List.of(expected), effectTypes(parse(shortFlag, path)), "short flag " + shortFlag);
            assertEquals(List.of(expected), effectTypes(parse(longName, path)), "long name " + longName);
        }
    }

    @Test
    @DisplayName("Audio, maximise and display options are read next to the effects")
    void otherOptionsAreRead() throws Exception {
        CommandLineController controller = parse("-axw", "-2", videoFile().getPath());

        assertTrue(controller.isAudioRequested(), "-a means audio");
        assertTrue(controller.isMaximiseRequested(), "-x means maximise");
        assertEquals(Integer.valueOf(2), controller.getDisplayId());
        assertEquals(List.of(FrameBlackAndWhiter.class), effectTypes(controller));
    }

    @Test
    @DisplayName("An unknown short flag or long option is an error, and the player does not launch")
    void unknownOptionsAreRejected() throws Exception {
        String path = videoFile().getPath();

        CommandLineController shortFlag = parse("-nZ", path);
        assertEquals("Unknown option: -Z", shortFlag.getErrorMessage());
        assertFalse(shortFlag.shouldLaunchApplication(), "must not launch with an unknown flag");
        assertEquals(1, shortFlag.getExitCode());

        CommandLineController longOption = parse("--sepia", path);
        assertEquals("Unknown option: --sepia", longOption.getErrorMessage());
        assertFalse(longOption.shouldLaunchApplication(), "must not launch with an unknown option");
    }

    @Test
    @DisplayName("Long names must match exactly")
    void longNamesAreCaseSensitive() throws Exception {
        CommandLineController controller = parse("--Dust-frames", videoFile().getPath());

        assertEquals("Unknown option: --Dust-frames", controller.getErrorMessage());
    }

    @Test
    @DisplayName("A missing file, no file, or two files are errors")
    void videoFileProblemsAreRejected() {
        CommandLineController missing = parse("no-such-video-file.mp4");
        assertTrue(missing.getErrorMessage().startsWith("File not found"), "got: " + missing.getErrorMessage());
        assertFalse(missing.shouldLaunchApplication(), "must not launch without the file");

        CommandLineController none = parse("-n");
        assertEquals("No video file specified.", none.getErrorMessage());

        CommandLineController two = parse("one.mp4", "two.mp4");
        assertEquals("Usage: VideoPlayer [options] [video-file]", two.getErrorMessage());
    }

    @Test
    @DisplayName("Two different display options are an error")
    void conflictingDisplaysAreRejected() throws Exception {
        CommandLineController controller = parse("-1", "-2", videoFile().getPath());

        assertEquals("Only one display option can be used", controller.getErrorMessage());
    }

    @Test
    @DisplayName("--help on its own is not an error, and the help text lists every effect")
    void helpListsEveryEffect() {
        CommandLineController controller = parse("--help");

        assertEquals(null, controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication(), "help alone must not launch the player");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            assertTrue(printedText().contains(option.helpLine()), "help is missing: " + option.helpLine());
        }
    }
}