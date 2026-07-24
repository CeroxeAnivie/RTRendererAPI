package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

/** Deterministic proof that bootstrap raygen, miss and closest-hit paths wrote the expected image. */
record RtBootstrapReadback(int origin, int center, int last, long checksum) {
    static RtBootstrapReadback verify(byte[] bytes, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bootstrap readback dimensions must be positive");
        }
        long expectedBytes = (long) width * height * Integer.BYTES;
        if (expectedBytes > Integer.MAX_VALUE || bytes.length != (int) expectedBytes) {
            throw new IllegalStateException(
                    "bootstrap readback byte size mismatch: expected " + expectedBytes + ", actual " + bytes.length
            );
        }
        int origin = RtFrameSnapshot.pixel(bytes, width, 0, 0);
        int centerX = width / 2;
        int centerY = height / 2;
        int center = RtFrameSnapshot.pixel(bytes, width, centerX, centerY);
        int last = RtFrameSnapshot.pixel(bytes, width, width - 1, height - 1);
        RtBootstrapReadback readback = new RtBootstrapReadback(
                origin, center, last, RtFrameSnapshot.checksum(bytes)
        );
        assertPixel(origin, expectedPixel(0, 0, width), 0, 0, readback);
        assertPixel(center, expectedPixel(centerX, centerY, width), centerX, centerY, readback);
        assertPixel(last, expectedPixel(width - 1, height - 1, width), width - 1, height - 1, readback);
        return readback;
    }

    String summary(String name) {
        return name
                + "{origin=" + RtFrameSnapshot.hex(origin)
                + ", center=" + RtFrameSnapshot.hex(center)
                + ", last=" + RtFrameSnapshot.hex(last)
                + ", checksum=" + RtFrameSnapshot.hex(checksum)
                + "}";
    }

    private static int expectedPixel(int x, int y, int width) {
        return x < width / 2 ? RtSceneMaterialTable.bootstrapHitRgba8() : RtSceneMaterialTable.missRgba8();
    }

    private static void assertPixel(
            int actual,
            int expected,
            int x,
            int y,
            RtBootstrapReadback readback
    ) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "bootstrap ray tracing readback mismatch at ("
                            + x + "," + y + "): expected " + RtFrameSnapshot.hex(expected)
                            + ", actual " + RtFrameSnapshot.hex(actual)
                            + ", " + readback.summary("readback")
            );
        }
    }
}
