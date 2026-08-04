package demo;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.time.Duration;

record DemoConfig(
        int width,
        int height,
        int samplesPerPixel,
        boolean windowed,
        boolean benchmark,
        boolean cpuPresentation,
        int targetFps,
        long frameLimit,
        Duration duration
) {
    private static final int MAX_IMAGE_DIMENSION = 16_384;

    DemoConfig {
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            throw new IllegalArgumentException("render extent must be within [1, 16384]");
        }
        Math.multiplyExact(Math.multiplyExact(width, height), Integer.BYTES);
        if (samplesPerPixel != 1 && samplesPerPixel != 2
                && samplesPerPixel != 4 && samplesPerPixel != 8) {
            throw new IllegalArgumentException("samples per pixel must be one of [1, 2, 4, 8]");
        }
        if (frameLimit < 0L) {
            throw new IllegalArgumentException("frame limit must not be negative");
        }
        if (targetFps != 0 && (targetFps < 15 || targetFps > 1000)) {
            throw new IllegalArgumentException("target FPS must be 0 (uncapped) or within [15, 1000]");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }

    static DemoConfig parse(String[] arguments) {
        Integer width = null;
        Integer height = null;
        int samples = 2;
        boolean windowed = true;
        boolean benchmark = false;
        boolean cpuPresentation = false;
        int targetFps = 0;
        long frameLimit = 0L;
        Duration duration = Duration.ZERO;

        for (String argument : arguments) {
            if (argument.equals("--windowed")) {
                windowed = true;
            } else if (argument.equals("--fullscreen")) {
                windowed = false;
            } else if (argument.equals("--benchmark")) {
                benchmark = true;
            } else if (argument.equals("--cpu-present")) {
                cpuPresentation = true;
            } else if (argument.startsWith("--width=")) {
                width = parsePositiveInt(argument, "--width=");
            } else if (argument.startsWith("--height=")) {
                height = parsePositiveInt(argument, "--height=");
            } else if (argument.startsWith("--spp=")) {
                samples = parsePositiveInt(argument, "--spp=");
            } else if (argument.startsWith("--target-fps=")) {
                targetFps = parseNonNegativeInt(argument, "--target-fps=");
            } else if (argument.startsWith("--frames=")) {
                frameLimit = parseNonNegativeLong(argument, "--frames=");
            } else if (argument.startsWith("--duration-seconds=")) {
                duration = Duration.ofSeconds(parseNonNegativeLong(argument, "--duration-seconds="));
            } else {
                throw new IllegalArgumentException("unknown argument: " + argument);
            }
        }

        if ((width == null) != (height == null)) {
            throw new IllegalArgumentException("--width and --height must be supplied together");
        }
        if (width == null) {
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("a display is required when no explicit extent is supplied");
            }
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            DisplayMode mode = device.getDisplayMode();
            width = mode.getWidth();
            height = mode.getHeight();
        }
        if (benchmark && frameLimit == 0L && duration.isZero()) frameLimit = 300L;
        if (frameLimit > 0L && !duration.isZero()) {
            throw new IllegalArgumentException("--frames and --duration-seconds are mutually exclusive");
        }
        if (benchmark && cpuPresentation) {
            throw new IllegalArgumentException("--benchmark and --cpu-present are mutually exclusive");
        }
        return new DemoConfig(
                width, height, samples, windowed, benchmark, cpuPresentation, targetFps, frameLimit, duration
        );
    }

    private static int parsePositiveInt(String argument, String prefix) {
        try {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) {
                throw new IllegalArgumentException(prefix + " value must be positive");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("invalid integer argument: " + argument, malformed);
        }
    }

    private static int parseNonNegativeInt(String argument, String prefix) {
        try {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) {
                throw new IllegalArgumentException(prefix + " value must not be negative");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("invalid integer argument: " + argument, malformed);
        }
    }

    private static long parseNonNegativeLong(String argument, String prefix) {
        try {
            long value = Long.parseLong(argument.substring(prefix.length()));
            if (value < 0L) {
                throw new IllegalArgumentException(prefix + " value must not be negative");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("invalid long argument: " + argument, malformed);
        }
    }
}
