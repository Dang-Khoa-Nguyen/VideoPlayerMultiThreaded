/*
 * Authors:
 *   1. Dang Khoa Nguyen - a3141297
 *
 * Added for COMP3011 Assignment 2: the list of command line effects, used to parse flags and print help. 
 */

package comp3011;

import java.util.function.Supplier;

/**
 * Every frame processor you can request on the command line.
 * To add a new effect, write its FrameProcessor subclass and add one constant here.
 * Parsing and help text update automatically.
 * Constants hold constructors, not instances, because each effect's random seed depends on how many have been created.
 */
public enum FrameProcessorOption {
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortFlag;
    private final String longName;
    private final String description;
    private final Supplier<FrameProcessor> factory;
    
    FrameProcessorOption(char shortFlag, String longName, String description, Supplier<FrameProcessor> factory) {
        this.shortFlag = shortFlag;
        this.longName = longName;
        this.description = description;
        this.factory = factory;
    }
    
    
    public FrameProcessor create() { return factory.get(); }
    
    // One line of the help text, padded so the descriptions line up.
    public String helpLine() {
        return String.format("  %-23s%s", "-" + shortFlag + ", --" + longName, description);
    }
    
    // Returns null if the flag isn't a frame processor. The parser reports that as an unknown option.
    public static FrameProcessorOption fromShortFlag(char flag) {
        for (FrameProcessorOption option : values()) {
            if (option.shortFlag == flag) return option;
        }
        return null;
    }

    // Same as above for long names. The match is exact, so "--Flicker-frames" is rejected.
    public static FrameProcessorOption fromLongName(String name) {
        for (FrameProcessorOption option : values()) {
            if (option.longName.equals(name)) return option;
        }
        return null;
    }
}