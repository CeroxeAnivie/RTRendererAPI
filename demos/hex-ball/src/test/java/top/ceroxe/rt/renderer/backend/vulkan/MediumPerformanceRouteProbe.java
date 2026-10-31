package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only route evidence. Snapshot outside measured ranges; no production API is added. */
public final class MediumPerformanceRouteProbe {
    private MediumPerformanceRouteProbe() { }
    public static Map<String,Long> snapshot() {
        var values=new LinkedHashMap<String,Long>();
        values.put("camera",VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum());
        values.put("boundary",VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundarySubmissions.sum());
        values.put("directUniform",VulkanGpuSceneFrameSubmitter.diagnosticNativeScatteringSubmissions.sum());
        values.put("multiple",VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum());
        values.put("directDensity",VulkanGpuSceneFrameSubmitter.diagnosticNativeDensityScatteringSubmissions.sum());
        values.put("optical",VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum());
        values.put("pbr",VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum());
        values.put("boundaryFused",VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundaryFusedSubmissions.sum());
        values.put("boundaryScattering",VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundaryScatteringSubmissions.sum());
        values.put("photonMaps",CausticPhotonPlan.diagnosticPhotonDispatches.sum());
        return Map.copyOf(values);
    }
    public static Map<String,Long> delta(Map<String,Long> before) {
        var after=snapshot();var difference=new LinkedHashMap<String,Long>();
        after.forEach((key,value)->difference.put(key,Math.subtractExact(value,before.getOrDefault(key,0L))));
        return Map.copyOf(difference);
    }
}
