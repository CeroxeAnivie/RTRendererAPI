package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Pinned native transmittance plus the API's analytic homogeneous-emission adapter. */
public final class CudaPrimaryEmissionSource {
    private CudaPrimaryEmissionSource() { }

    public static String source() {
        return CudaMediumSource.resource("api_emission.cu") + "\n"
                + CudaMediumSource.resource("primary_emission.cu");
    }
}
