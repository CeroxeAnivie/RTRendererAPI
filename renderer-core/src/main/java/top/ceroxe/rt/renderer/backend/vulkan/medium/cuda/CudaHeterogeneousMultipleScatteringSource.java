package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Pinned segment/HG support plus density-aware API continuation and light-connection adapters. */
public final class CudaHeterogeneousMultipleScatteringSource {
    private CudaHeterogeneousMultipleScatteringSource() { }
    public static String source() {
        return CudaMediumTransportSource.source() + "\n" + CudaMediumSource.resource("api_emission.cu") + "\n"
                + CudaMediumSource.resource("api_density.cu") + "\n"
                + CudaMediumSource.resource("heterogeneous_multiple_scattering.cu");
    }
}
