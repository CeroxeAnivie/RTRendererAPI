package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Pinned LuxCore priority/HG functions plus explicit-boundary scattering and density integration. */
public final class CudaBoundaryScatteringSource {
    private CudaBoundaryScatteringSource() { }
    public static String source() {
        return CudaMediumTransportSource.source()+"\n"+CudaMediumSource.resource("api_emission.cu")+"\n"
                +CudaMediumSource.resource("api_density.cu")+"\n"+CudaMediumSource.resource("boundary_scattering.cu");
    }
}
