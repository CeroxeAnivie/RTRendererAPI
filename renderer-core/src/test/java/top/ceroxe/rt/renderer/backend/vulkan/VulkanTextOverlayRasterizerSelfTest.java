package top.ceroxe.rt.renderer.backend.vulkan;

/** Deterministic byte-level checks for the dependency-free presenter HUD rasterizer. */
public final class VulkanTextOverlayRasterizerSelfTest {
    private VulkanTextOverlayRasterizerSelfTest() {
    }

    public static void main(String[] arguments) {
        require(arguments.length == 0, "self-test accepts no arguments");
        verifyEmptyAndConstrainedInputs();
        verifyRgbaAndBgraPacking();
        verifyMultilineBounds();
        System.out.println("VulkanTextOverlayRasterizerSelfTest passed");
    }

    private static void verifyEmptyAndConstrainedInputs() {
        VulkanTextOverlayRasterizer.Raster empty = VulkanTextOverlayRasterizer.rasterize(
                "", 1_024, 256, false
        );
        require(empty.width() == 0 && empty.height() == 0 && empty.pixels().length == 0,
                "empty text must disable the overlay");
        VulkanTextOverlayRasterizer.Raster constrained = VulkanTextOverlayRasterizer.rasterize(
                "PRESENT", 20, 20, false
        );
        require(constrained.width() == 0 && constrained.height() == 0,
                "an extent smaller than one padded glyph must disable the overlay");
    }

    private static void verifyRgbaAndBgraPacking() {
        VulkanTextOverlayRasterizer.Raster rgba = VulkanTextOverlayRasterizer.rasterize(
                "A", 128, 64, false
        );
        VulkanTextOverlayRasterizer.Raster bgra = VulkanTextOverlayRasterizer.rasterize(
                "A", 128, 64, true
        );
        require(rgba.width() == bgra.width() && rgba.height() == bgra.height(),
                "channel order must not alter overlay geometry");

        int backgroundOffset = 0;
        require(unsigned(rgba.pixels()[backgroundOffset]) == 10
                        && unsigned(rgba.pixels()[backgroundOffset + 1]) == 15
                        && unsigned(rgba.pixels()[backgroundOffset + 2]) == 23,
                "RGBA background bytes are incorrect");
        require(unsigned(bgra.pixels()[backgroundOffset]) == 23
                        && unsigned(bgra.pixels()[backgroundOffset + 1]) == 15
                        && unsigned(bgra.pixels()[backgroundOffset + 2]) == 10,
                "BGRA background bytes are incorrect");

        int litPixel = ((12 * rgba.width()) + 15) * 4;
        require(unsigned(rgba.pixels()[litPixel]) == 245
                        && unsigned(rgba.pixels()[litPixel + 1]) == 248
                        && unsigned(rgba.pixels()[litPixel + 2]) == 255,
                "RGBA foreground bytes are incorrect");
        require(unsigned(bgra.pixels()[litPixel]) == 255
                        && unsigned(bgra.pixels()[litPixel + 1]) == 248
                        && unsigned(bgra.pixels()[litPixel + 2]) == 245,
                "BGRA foreground bytes are incorrect");
    }

    private static void verifyMultilineBounds() {
        VulkanTextOverlayRasterizer.Raster raster = VulkanTextOverlayRasterizer.rasterize(
                "PRESENT: 145.2 FPS\nTRACE CAPACITY: 266.3 FPS",
                1_024,
                256,
                false
        );
        require(raster.width() > 400 && raster.width() <= 1_024,
                "two-line HUD width is unexpected: " + raster.width());
        require(raster.height() == 72, "two-line HUD height is unexpected: " + raster.height());
        require(raster.pixels().length == raster.width() * raster.height() * 4,
                "HUD byte count must exactly match its extent");
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
