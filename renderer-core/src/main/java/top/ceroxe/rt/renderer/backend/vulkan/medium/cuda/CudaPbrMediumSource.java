package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Direct surface connections use the pinned LuxCore priority and segment implementations. */
public final class CudaPbrMediumSource {
    private CudaPbrMediumSource() { }
    public static String source(){return CudaMediumTransportSource.source()+"\n"
            +CudaMediumSource.resource("api_emission.cu")+"\n"+CudaMediumSource.resource("api_density.cu")+"\n"
            +CudaMediumSource.resource("boundary_transport.cu")+"\n"+CudaMediumSource.resource("pbr_transport.cu");}
}
