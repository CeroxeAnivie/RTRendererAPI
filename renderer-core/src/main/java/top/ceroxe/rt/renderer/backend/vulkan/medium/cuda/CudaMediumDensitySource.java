package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Density wrapper; CudaMediumRuntime supplies pinned CUDA compatibility and segment functions. */
public final class CudaMediumDensitySource {
    private CudaMediumDensitySource() { }
    public static String source() {
        return CudaMediumSource.resource("api_density.cu");
    }
}
