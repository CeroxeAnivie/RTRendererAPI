package top.ceroxe.mcvulkanrt.renderer.api;

/** Renderer-owned policy that must remain stable for one renderer lifetime. */
public record RayTracingRendererConfig(
        int maxFramesInFlight,
        boolean validationEnabled,
        boolean gpuTimingsEnabled
) {
    public static final int DEFAULT_MAX_FRAMES_IN_FLIGHT = 3;

    public RayTracingRendererConfig {
        if (maxFramesInFlight < 2 || maxFramesInFlight > 16) {
            throw new IllegalArgumentException("maxFramesInFlight must be in [2, 16]");
        }
    }

    public static RayTracingRendererConfig defaults() {
        return new RayTracingRendererConfig(DEFAULT_MAX_FRAMES_IN_FLIGHT, false, true);
    }
}
