package top.ceroxe.rt.renderer.api;

/**
 * Ordinary Vulkan surface reflection/transmission workload, distinct from managed RTXPT media.
 * These renderer-lifetime budgets populate the Vulkan frame uniforms; RTXPT uses
 * {@link PathTracingOptions} instead. One event includes the primary surface, and one sample
 * is the minimum optical surface sample count. Reducing depth can omit later reflections.
 *
 * @param maxEvents primary plus secondary surface event limit, 1..64
 * @param samples optical surface samples, 1..16, independent of camera SPP
 */
public record SurfaceTransportOptions(int maxEvents,int samples) {
    public SurfaceTransportOptions {
        if(maxEvents<1||maxEvents>64||samples<1||samples>16)
            throw new IllegalArgumentException("Surface transport requires events in [1,64] and samples in [1,16]");
    }
    public static SurfaceTransportOptions low(){return new SurfaceTransportOptions(4,1);}
    public static SurfaceTransportOptions medium(){return new SurfaceTransportOptions(8,1);}
    public static SurfaceTransportOptions high(){return new SurfaceTransportOptions(12,1);}
    public static SurfaceTransportOptions defaults(){return medium();}
}
