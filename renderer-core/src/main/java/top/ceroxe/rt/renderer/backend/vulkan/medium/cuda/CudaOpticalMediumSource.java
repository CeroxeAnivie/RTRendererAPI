package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Pinned LuxCore glass/priority/segment code with separate public transport-budget glue. */
public final class CudaOpticalMediumSource {
    private CudaOpticalMediumSource() { }
    public static String source(){return (OpticalFrameTimings.ENTRY_PHASES?"#define RTR_OPTICAL_ENTRY_PROFILE 1\n":"")
            +(OpticalFrameTimings.FINISH_PHASES?"#define RTR_OPTICAL_FINISH_PROFILE 1\n":"")
            +(OpticalFrameTimings.KERNEL_PHASES?"#define RTR_OPTICAL_PHASE_PROFILE 1\n":"")
            +CudaMediumTransportSource.source()+"\n"
            +CudaMediumSource.resource("api_emission.cu")+"\n"
            +CudaMediumSource.resource("api_density.cu")+"\n"
            +CudaMediumSource.resource("api_glass_helpers.cu")+"\n"
            +CudaMediumSource.resource("upstream-glass-functions.cl")+"\n"
            +"} // namespace rtr_glass\n"
            +CudaMediumSource.resource("api_roughglass_helpers.cu")+"\n"
            +CudaMediumSource.resource("upstream-evalstack-functions.cl")+"\n"
            +CudaMediumSource.resource("upstream-microfacet-functions.cl")+"\n"
            +CudaMediumSource.resource("upstream-roughglass-sample.cl")+"\n"
            +CudaMediumSource.resource("api_roughglass_sampling.cu")+"\n"
            +CudaMediumSource.resource("optical_transport.cu");}
}
