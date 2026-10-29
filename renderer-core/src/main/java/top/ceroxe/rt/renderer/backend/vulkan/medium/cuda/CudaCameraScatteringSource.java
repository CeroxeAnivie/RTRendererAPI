package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Direct-light and analytic-source wrappers; the runtime prepends pinned LuxCore support once. */
public final class CudaCameraScatteringSource {
    private CudaCameraScatteringSource() { }
    public static String source() {
        return CudaMediumSource.resource("api_emission.cu")+"\n"
                +CudaMediumSource.resource("camera_scattering.cu");
    }
}
