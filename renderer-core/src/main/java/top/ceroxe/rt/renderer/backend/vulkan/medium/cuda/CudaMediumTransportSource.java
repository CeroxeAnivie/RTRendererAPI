package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** API-semantic adapters assembled after the pinned CUDA compatibility and segment functions. */
public final class CudaMediumTransportSource {
    private CudaMediumTransportSource() { }

    public static String source() {
        return CudaMediumSource.resource("medium_transport_types.cu") + "\n"
                + CudaMediumSource.resource("upstream-pathvolumeinfo-functions.cl") + "\n"
                + CudaMediumSource.resource("medium_transport.cu");
    }
}
