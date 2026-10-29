package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Production continuation glue around pinned LuxCore segment and medium-priority functions. */
public final class CudaBoundaryMediumSource {
    private CudaBoundaryMediumSource() { }
    public static String source() {
        return CudaMediumTransportSource.source()+"\n"
                +CudaMediumSource.resource("api_emission.cu")+"\n"
                +CudaMediumSource.resource("api_density.cu")+"\n"
                +CudaMediumSource.resource("boundary_transport.cu")+"\n"
                +CudaMediumSource.resource("boundary_packet.cu");
    }
}
