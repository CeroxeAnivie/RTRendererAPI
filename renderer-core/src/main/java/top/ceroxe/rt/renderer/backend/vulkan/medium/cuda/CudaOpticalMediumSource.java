package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Pinned LuxCore glass/priority/segment code with separate public transport-budget glue. */
public final class CudaOpticalMediumSource {
    public static final String ACTIVE_VOLUME_COPIES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalActiveVolumeCopies";
    private CudaOpticalMediumSource() { }
    public static String source(){return (Boolean.parseBoolean(System.getProperty(ACTIVE_VOLUME_COPIES_PROPERTY,"true"))
            ?"#define RTR_OPTICAL_ACTIVE_VOLUME_COPIES 1\n":"")
            +(System.getProperty("top.ceroxe.rt.medium.opticalTracePath")!=null
            ?"#define RTR_OPTICAL_TRACE_PATH "+Integer.parseUnsignedInt(System.getProperty("top.ceroxe.rt.medium.opticalTracePath"))+"u\n":"")
            +(OpticalFrameTimings.ENTRY_PHASES?"#define RTR_OPTICAL_ENTRY_PROFILE 1\n":"")
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
