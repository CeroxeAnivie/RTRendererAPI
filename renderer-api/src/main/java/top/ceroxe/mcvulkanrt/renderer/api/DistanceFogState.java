package top.ceroxe.mcvulkanrt.renderer.api;

/** Frame-local two-distance linear fog independent of any source renderer. */
public record DistanceFogState(
        float red,
        float green,
        float blue,
        float opacity,
        float sphericalStart,
        float sphericalEnd,
        float cylindricalStart,
        float cylindricalEnd
) {
    private static final DistanceFogState DISABLED = new DistanceFogState(
            0.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 1.0F
    );

    public DistanceFogState {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(opacity, "opacity");
        requireFinite(sphericalStart, "sphericalStart");
        requireFinite(sphericalEnd, "sphericalEnd");
        requireFinite(cylindricalStart, "cylindricalStart");
        requireFinite(cylindricalEnd, "cylindricalEnd");
    }

    public static DistanceFogState disabled() {
        return DISABLED;
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
