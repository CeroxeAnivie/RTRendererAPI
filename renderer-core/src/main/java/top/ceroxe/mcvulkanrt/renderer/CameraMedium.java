package top.ceroxe.mcvulkanrt.renderer;

/**
 * Render-thread copy of the camera's current volumetric medium.
 *
 * <p>This intentionally does not expose host's {@code FluidState} to the
 * RT backend.  The renderer only needs stable optical properties: a tint and an
 * extinction density.  Keeping the boundary data-oriented lets sourceEngine water,
 * lava, and modded fluids use the same shader path instead of growing a new
 * special-case every time another fluid appears.</p>
 */
public record CameraMedium(
        boolean active,
        int packedRgba,
        float density
) {
    private static final CameraMedium AIR = new CameraMedium(false, 0, 0.0F);

    public CameraMedium {
        if (!active) {
            packedRgba = 0;
            density = 0.0F;
        } else {
            if (!Float.isFinite(density) || density <= 0.0F) {
                throw new IllegalArgumentException("active camera fluid density must be positive and finite");
            }
            density = clamp(density, 0.001F, 4.0F);
            if (((packedRgba >>> 24) & 0xFF) == 0) {
                packedRgba = (packedRgba & 0x00FF_FFFF) | 0xFF00_0000;
            }
        }
    }

    public static CameraMedium air() {
        return AIR;
    }

    public static CameraMedium fluid(int rgb24, int alpha8, float density) {
        return new CameraMedium(true, packRgba8(rgb24, alpha8), density);
    }

    public static int packRgba8(int rgb24, int alpha8) {
        int red = (rgb24 >>> 16) & 0xFF;
        int green = (rgb24 >>> 8) & 0xFF;
        int blue = rgb24 & 0xFF;
        int alpha = clamp(alpha8, 0, 255);
        return red | (green << 8) | (blue << 16) | (alpha << 24);
    }

    public String asLogFragment() {
        if (!active) {
            return "cameraMedium=air";
        }
        return "cameraMedium=fluid"
                + ", mediumRgba=0x" + String.format(java.util.Locale.ROOT, "%08X", packedRgba)
                + ", mediumDensity=" + density;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
