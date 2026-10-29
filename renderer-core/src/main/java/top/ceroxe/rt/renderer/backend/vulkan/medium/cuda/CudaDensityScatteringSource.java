package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Wrapper only; the runtime prepends the unchanged pinned CUDA/segment sources once. */
public final class CudaDensityScatteringSource {
    private CudaDensityScatteringSource() { }
    public static String source() {
        return CudaMediumSource.resource("api_emission.cu")+"\n"+CudaMediumSource.resource("api_density.cu")+"\n"
                +CudaMediumSource.resource("density_scattering.cu");
    }
}
