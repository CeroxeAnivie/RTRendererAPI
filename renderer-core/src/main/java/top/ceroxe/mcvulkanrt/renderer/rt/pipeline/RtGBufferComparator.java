package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.Objects;

/** First-pixel semantic diff for sourceEngine and RT G-buffer snapshots. */
public final class RtGBufferComparator {
    private RtGBufferComparator() {
    }

    public static Result compare(RtGBufferSnapshot expected, RtGBufferSnapshot actual, float depthTolerance) {
        return compare(expected, actual, depthTolerance, Fields.all());
    }

    public static Result compare(
            RtGBufferSnapshot expected,
            RtGBufferSnapshot actual,
            float depthTolerance,
            Fields fields
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(fields, "fields");
        if (!Float.isFinite(depthTolerance) || depthTolerance < 0.0F) {
            throw new IllegalArgumentException("depthTolerance must be finite and non-negative");
        }
        if (expected.width() != actual.width() || expected.height() != actual.height()) {
            return Result.difference("extent", -1, expected.width() + "x" + expected.height(), actual.width() + "x" + actual.height());
        }
        float[] expectedDepth = expected.depth();
        float[] actualDepth = actual.depth();
        int[] expectedNormals = expected.normalOct16();
        int[] actualNormals = actual.normalOct16();
        int[] expectedAlbedo = expected.albedoRgba8();
        int[] actualAlbedo = actual.albedoRgba8();
        int[] expectedMaterial = expected.materialIds();
        int[] actualMaterial = actual.materialIds();
        int[] expectedEmissive = expected.emissiveRgba8();
        int[] actualEmissive = actual.emissiveRgba8();
        byte[] expectedMedium = expected.cameraMediumIds();
        byte[] actualMedium = actual.cameraMediumIds();
        for (int pixel = 0; pixel < expectedDepth.length; pixel++) {
            if (fields.depth() && !sameDepth(expectedDepth[pixel], actualDepth[pixel], depthTolerance)) {
                return Result.difference("depth", pixel, Float.toString(expectedDepth[pixel]), Float.toString(actualDepth[pixel]));
            }
            if (fields.material() && expectedMaterial[pixel] != actualMaterial[pixel]) return integers("materialId", pixel, expectedMaterial[pixel], actualMaterial[pixel]);
            if (fields.normal() && expectedNormals[pixel] != actualNormals[pixel]) return integers("normalOct16", pixel, expectedNormals[pixel], actualNormals[pixel]);
            if (fields.albedo() && expectedAlbedo[pixel] != actualAlbedo[pixel]) return integers("albedoRgba8", pixel, expectedAlbedo[pixel], actualAlbedo[pixel]);
            if (fields.emissive() && expectedEmissive[pixel] != actualEmissive[pixel]) return integers("emissiveRgba8", pixel, expectedEmissive[pixel], actualEmissive[pixel]);
            if (fields.cameraMedium() && expectedMedium[pixel] != actualMedium[pixel]) return integers("cameraMediumId", pixel,
                    Byte.toUnsignedInt(expectedMedium[pixel]), Byte.toUnsignedInt(actualMedium[pixel]));
        }
        return new Result(true, null);
    }

    private static boolean sameDepth(float expected, float actual, float tolerance) {
        return expected == actual || (Float.isFinite(expected) && Float.isFinite(actual) && Math.abs(expected - actual) <= tolerance);
    }

    private static Result integers(String field, int pixel, int expected, int actual) {
        return Result.difference(field, pixel, Integer.toUnsignedString(expected), Integer.toUnsignedString(actual));
    }

    public record Result(boolean matches, Difference firstDifference) {
        private static Result difference(String field, int pixel, String expected, String actual) {
            return new Result(false, new Difference(field, pixel, expected, actual));
        }
    }

    public record Difference(String field, int pixelIndex, String expected, String actual) {
    }

    public record Fields(
            boolean depth,
            boolean material,
            boolean normal,
            boolean albedo,
            boolean emissive,
            boolean cameraMedium
    ) {
        public Fields {
            if (!(depth || material || normal || albedo || emissive || cameraMedium)) {
                throw new IllegalArgumentException("at least one G-buffer field must be selected");
            }
        }

        public static Fields all() {
            return new Fields(true, true, true, true, true, true);
        }

        public static Fields geometry() {
            return new Fields(true, true, true, false, false, false);
        }
    }
}
