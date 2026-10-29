package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** API estimator adapters only; compileBatchKernel prepends pinned segment support exactly once. */
public final class CudaMultipleScatteringSource {
    private CudaMultipleScatteringSource() { }
    public static String source(){return CudaMediumTransportSource.source()+"\n"
            +CudaMediumSource.resource("api_emission.cu")+"\n"+CudaMediumSource.resource("camera_scattering.cu")+"\n"
            +CudaMediumSource.resource("multiple_scattering.cu");}
}
