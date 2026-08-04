package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Produces a compact, dependency-free 5x7 diagnostic HUD for transfer-only presentation. */
final class VulkanTextOverlayRasterizer {
    static final int MAX_WIDTH = 1_024;
    // Twelve lines are required by the public technology HUD (two performance plus ten features).
    // Keep a hard cap so arbitrary overlay text cannot create an unbounded upload allocation.
    static final int MAX_HEIGHT = 384;

    private static final int SCALE = 3;
    private static final int GLYPH_WIDTH = 5;
    private static final int GLYPH_HEIGHT = 7;
    private static final int GLYPH_ADVANCE = 6 * SCALE;
    private static final int LINE_ADVANCE = 9 * SCALE;
    private static final int PADDING = 12;

    private VulkanTextOverlayRasterizer() {
    }

    static Raster rasterize(String text, int availableWidth, int availableHeight, boolean bgra) {
        Objects.requireNonNull(text, "text");
        int widthLimit = Math.min(MAX_WIDTH, Math.max(0, availableWidth));
        int heightLimit = Math.min(MAX_HEIGHT, Math.max(0, availableHeight));
        if (text.isEmpty()
                || widthLimit < PADDING * 2 + GLYPH_WIDTH * SCALE
                || heightLimit < PADDING * 2 + GLYPH_HEIGHT * SCALE) {
            return Raster.EMPTY;
        }

        String[] requestedLines = text.replace("\r", "").toUpperCase(Locale.ROOT).split("\n", -1);
        int maximumLines = 1 + (heightLimit - PADDING * 2 - GLYPH_HEIGHT * SCALE) / LINE_ADVANCE;
        int lineCount = Math.min(requestedLines.length, maximumLines);
        int maximumCharacters = Math.max(
                1,
                (widthLimit - PADDING * 2 + SCALE) / GLYPH_ADVANCE
        );
        int widestCharacters = 0;
        for (int line = 0; line < lineCount; line++) {
            widestCharacters = Math.max(
                    widestCharacters,
                    Math.min(requestedLines[line].length(), maximumCharacters)
            );
        }
        int width = Math.min(
                widthLimit,
                PADDING * 2 + Math.max(1, widestCharacters) * GLYPH_ADVANCE - SCALE
        );
        int height = PADDING * 2 + GLYPH_HEIGHT * SCALE + (lineCount - 1) * LINE_ADVANCE;
        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        for (int line = 0; line < lineCount; line++) {
            String value = requestedLines[line];
            int characters = Math.min(value.length(), maximumCharacters);
            int red = line == 0 ? 245 : 103;
            int green = line == 0 ? 248 : 232;
            int blue = line == 0 ? 255 : 249;
            int baselineY = PADDING + line * LINE_ADVANCE;
            for (int character = 0; character < characters; character++) {
                drawGlyph(
                        pixels,
                        width,
                        height,
                        PADDING + character * GLYPH_ADVANCE,
                        baselineY,
                        glyph(value.charAt(character)),
                        bgra,
                        red,
                        green,
                        blue
                );
            }
        }
        return new Raster(width, height, pixels, copySpans(pixels, width, height));
    }

    private static List<CopySpan> copySpans(byte[] pixels, int width, int height) {
        ArrayList<SpanBuilder> completed = new ArrayList<>();
        Map<Long, SpanBuilder> active = Map.of();
        for (int y = 0; y < height; y++) {
            Map<Long, SpanBuilder> next = new HashMap<>();
            int x = 0;
            while (x < width) {
                while (x < width && pixels[(y * width + x) * 4 + 3] == 0) x++;
                int start = x;
                while (x < width && pixels[(y * width + x) * 4 + 3] != 0) x++;
                if (start == x) continue;
                int spanWidth = x - start;
                long key = ((long) start << 32) | Integer.toUnsignedLong(spanWidth);
                SpanBuilder span = active.get(key);
                if (span == null) {
                    span = new SpanBuilder(start, y, spanWidth);
                } else {
                    span.height++;
                }
                next.put(key, span);
            }
            for (Map.Entry<Long, SpanBuilder> entry : active.entrySet()) {
                if (!next.containsKey(entry.getKey())) completed.add(entry.getValue());
            }
            active = next;
        }
        completed.addAll(active.values());
        completed.sort(Comparator.comparingInt((SpanBuilder span) -> span.y)
                .thenComparingInt(span -> span.x));
        return completed.stream()
                .map(span -> new CopySpan(span.x, span.y, span.width, span.height))
                .toList();
    }

    private static void drawGlyph(
            byte[] pixels,
            int width,
            int height,
            int originX,
            int originY,
            long glyph,
            boolean bgra,
            int red,
            int green,
            int blue
    ) {
        for (int row = 0; row < GLYPH_HEIGHT; row++) {
            int bits = (int) ((glyph >>> ((GLYPH_HEIGHT - 1 - row) * GLYPH_WIDTH)) & 0x1fL);
            for (int column = 0; column < GLYPH_WIDTH; column++) {
                if ((bits & (1 << (GLYPH_WIDTH - 1 - column))) == 0) continue;
                for (int dy = 0; dy < SCALE; dy++) {
                    int y = originY + row * SCALE + dy;
                    if (y >= height) continue;
                    for (int dx = 0; dx < SCALE; dx++) {
                        int x = originX + column * SCALE + dx;
                        if (x < width) putPixel(pixels, width, x, y, bgra, red, green, blue);
                    }
                }
            }
        }
    }

    private static void putPixel(
            byte[] pixels,
            int width,
            int x,
            int y,
            boolean bgra,
            int red,
            int green,
            int blue
    ) {
        int offset = (y * width + x) * 4;
        pixels[offset] = (byte) (bgra ? blue : red);
        pixels[offset + 1] = (byte) green;
        pixels[offset + 2] = (byte) (bgra ? red : blue);
        pixels[offset + 3] = (byte) 0xff;
    }

    private static long glyph(char value) {
        return switch (value) {
            case 'A' -> 0b01110_10001_10001_11111_10001_10001_10001L;
            case 'B' -> 0b11110_10001_10001_11110_10001_10001_11110L;
            case 'C' -> 0b01111_10000_10000_10000_10000_10000_01111L;
            case 'D' -> 0b11110_10001_10001_10001_10001_10001_11110L;
            case 'E' -> 0b11111_10000_10000_11110_10000_10000_11111L;
            case 'F' -> 0b11111_10000_10000_11110_10000_10000_10000L;
            case 'G' -> 0b01111_10000_10000_10111_10001_10001_01111L;
            case 'H' -> 0b10001_10001_10001_11111_10001_10001_10001L;
            case 'I' -> 0b11111_00100_00100_00100_00100_00100_11111L;
            case 'J' -> 0b00111_00010_00010_00010_10010_10010_01100L;
            case 'K' -> 0b10001_10010_10100_11000_10100_10010_10001L;
            case 'L' -> 0b10000_10000_10000_10000_10000_10000_11111L;
            case 'M' -> 0b10001_11011_10101_10101_10001_10001_10001L;
            case 'N' -> 0b10001_11001_10101_10011_10001_10001_10001L;
            case 'O' -> 0b01110_10001_10001_10001_10001_10001_01110L;
            case 'P' -> 0b11110_10001_10001_11110_10000_10000_10000L;
            case 'Q' -> 0b01110_10001_10001_10001_10101_10010_01101L;
            case 'R' -> 0b11110_10001_10001_11110_10100_10010_10001L;
            case 'S' -> 0b01111_10000_10000_01110_00001_00001_11110L;
            case 'T' -> 0b11111_00100_00100_00100_00100_00100_00100L;
            case 'U' -> 0b10001_10001_10001_10001_10001_10001_01110L;
            case 'V' -> 0b10001_10001_10001_10001_10001_01010_00100L;
            case 'W' -> 0b10001_10001_10001_10101_10101_10101_01010L;
            case 'X' -> 0b10001_10001_01010_00100_01010_10001_10001L;
            case 'Y' -> 0b10001_10001_01010_00100_00100_00100_00100L;
            case 'Z' -> 0b11111_00001_00010_00100_01000_10000_11111L;
            case '0' -> 0b01110_10001_10011_10101_11001_10001_01110L;
            case '1' -> 0b00100_01100_00100_00100_00100_00100_01110L;
            case '2' -> 0b01110_10001_00001_00010_00100_01000_11111L;
            case '3' -> 0b11110_00001_00001_01110_00001_00001_11110L;
            case '4' -> 0b00010_00110_01010_10010_11111_00010_00010L;
            case '5' -> 0b11111_10000_10000_11110_00001_00001_11110L;
            case '6' -> 0b01110_10000_10000_11110_10001_10001_01110L;
            case '7' -> 0b11111_00001_00010_00100_01000_01000_01000L;
            case '8' -> 0b01110_10001_10001_01110_10001_10001_01110L;
            case '9' -> 0b01110_10001_10001_01111_00001_00001_01110L;
            case '.' -> 0b00000_00000_00000_00000_00000_00110_00110L;
            case ':' -> 0b00000_00110_00110_00000_00110_00110_00000L;
            case '-' -> 0b00000_00000_00000_11111_00000_00000_00000L;
            case '|' -> 0b00100_00100_00100_00100_00100_00100_00100L;
            case '/' -> 0b00001_00010_00010_00100_01000_01000_10000L;
            case '(' -> 0b00010_00100_01000_01000_01000_00100_00010L;
            case ')' -> 0b01000_00100_00010_00010_00010_00100_01000L;
            case ' ' -> 0L;
            default -> 0b01110_10001_00001_00010_00100_00000_00100L;
        };
    }

    record Raster(int width, int height, byte[] pixels, List<CopySpan> copySpans) {
        private static final Raster EMPTY = new Raster(0, 0, new byte[0], List.of());

        Raster {
            if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
                throw new IllegalArgumentException("overlay dimensions must both be zero or positive");
            }
            pixels = Objects.requireNonNull(pixels, "pixels");
            copySpans = List.copyOf(Objects.requireNonNull(copySpans, "copySpans"));
            if (pixels.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
                throw new IllegalArgumentException("overlay byte count does not match its dimensions");
            }
            for (CopySpan span : copySpans) {
                if (span.x() + span.width() > width || span.y() + span.height() > height) {
                    throw new IllegalArgumentException("overlay copy span exceeds its dimensions");
                }
            }
        }
    }

    record CopySpan(int x, int y, int width, int height) {
        CopySpan {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("overlay copy span must have positive geometry");
            }
        }
    }

    private static final class SpanBuilder {
        private final int x;
        private final int y;
        private final int width;
        private int height = 1;

        private SpanBuilder(int x, int y, int width) {
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }
}
