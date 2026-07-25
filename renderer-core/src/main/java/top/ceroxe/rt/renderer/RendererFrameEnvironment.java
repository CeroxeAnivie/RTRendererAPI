package top.ceroxe.rt.renderer;

/**
 * Per-frame environment constants consumed by the ray-generation uniform buffer.
 *
 * <p>Clock interpolation and fog alpha change at frame cadence, while the rest
 * of {@link DynamicRenderScene.EnvironmentState} is descriptor-visible dynamic
 * scene data. Keeping these facts separate prevents animation from advancing a
 * dynamic-scene, descriptor, or TLAS generation every frame.</p>
 *
 * @param gameTime     non-negative frame clock value
 * @param partialTicks finite interpolation fraction clamped to {@code [0, 1]}
 * @param fogAlpha     finite fog alpha clamped to {@code [0, 1]}
 */
public record RendererFrameEnvironment(long gameTime, float partialTicks, float fogAlpha) {
    private static final RendererFrameEnvironment UNKNOWN = new RendererFrameEnvironment(0L, 0.0F, 0.0F);

    /**
     * Clamps finite unit values and normalizes negative clock values to zero.
     */
    public RendererFrameEnvironment {
        gameTime = Math.max(0L, gameTime);
        partialTicks = clampUnit(partialTicks, "partialTicks");
        fogAlpha = clampUnit(fogAlpha, "fogAlpha");
    }

    /**
     * Returns the shared unknown environment.
     *
     * @return immutable unknown environment
     */
    public static RendererFrameEnvironment unknown() {
        return UNKNOWN;
    }

    /**
     * Projects dynamic environment state into frame-cadence constants.
     *
     * @param environmentState source environment, or {@code null}
     * @return immutable frame environment, or {@link #unknown()} for {@code null}
     */
    public static RendererFrameEnvironment from(DynamicRenderScene.EnvironmentState environmentState) {
        if (environmentState == null) {
            return UNKNOWN;
        }
        return new RendererFrameEnvironment(
                environmentState.gameTime(),
                environmentState.partialTicks(),
                environmentState.fogAlpha()
        );
    }

    private static float clampUnit(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
