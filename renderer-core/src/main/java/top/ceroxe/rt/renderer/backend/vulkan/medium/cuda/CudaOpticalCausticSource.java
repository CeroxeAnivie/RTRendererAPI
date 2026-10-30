package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Shares the pinned glass, volume priority, density integration and HG adapters with optical camera paths. */
public final class CudaOpticalCausticSource {
    private CudaOpticalCausticSource() { }
    public static String source(){return "#define RTR_OPTICAL_CAUSTIC_ONLY 1\n"+CudaOpticalMediumSource.source()
            +"\n"+CudaMediumSource.resource("optical_caustics.cu");}
}
