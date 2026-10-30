package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.EnumMap;
import java.util.List;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.SceneShaderProgram;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline.Program;

/** Owns built-in specializations or one explicit expert program until all frame slots retire. */
final class VulkanGpuScenePipelines implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final RendererConfig configuration;
    private final boolean linearHdr;
    private final EnumMap<Program, GpuSceneRayTracingPipeline> builtIns = new EnumMap<>(Program.class);
    private final GpuSceneRayTracingPipeline expert;
    private CudaCameraMediumManager nativeCameraMedium;
    private CudaBoundaryMediumManager nativeBoundaryMedium;
    private CudaCameraScatteringManager nativeCameraScattering;
    private CudaMultipleScatteringManager nativeMultipleScattering;
    private CudaDensityScatteringManager nativeDensityScattering;
    private CudaOpticalMediumManager nativeOpticalMedium;
    private CudaPbrMediumManager nativePbrMedium;
    private enum NativeMode { CAMERA, BOUNDARY, DIRECT, MULTIPLE, BOUNDARY_SCATTER, DENSITY, OPTICAL, PBR, PBR_SCATTER, PBR_DENSITY, PBR_MULTIPLE }

    VulkanGpuScenePipelines(VulkanDeviceRuntime device, RendererConfig configuration, boolean linearHdr) {
        this.device = device;
        this.configuration = configuration;
        this.linearHdr = linearHdr;
        if (configuration.sceneShaders().equals(SceneShaderProgram.builtIn())) {
            expert = null;
        } else {
            expert = GpuSceneRayTracingPipeline.open(device, configuration.maxFramesInFlight(),
                    linearHdr, configuration.sceneShaders());
        }
    }

    private GpuSceneRayTracingPipeline create(Program program) {
        return GpuSceneRayTracingPipeline.openBuiltIn(device, configuration.maxFramesInFlight(), linearHdr, program);
    }

    void validateShaderData(List<ResourceData> values) {
        if (expert != null) {
            expert.validateShaderData(values);
        } else if (!values.isEmpty()) {
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                    "built-in scene shaders declare no application data bindings");
        }
    }

    void validateShaderDataBeforeTransport(List<ResourceData> values) {
        if (expert != null || values.isEmpty()) validateShaderData(values);
    }

    boolean usesBuiltInSceneShaders() { return expert == null; }

    GpuSceneRayTracingPipeline photons() {
        if (expert != null) throw new FrameValidationException(FrameValidationException.Reason.INVALID_CAUSTICS,
                "built-in photon maps require built-in scene shaders; expert programs own their light transport");
        return builtIns.computeIfAbsent(Program.PHOTONS, this::create);
    }

    GpuSceneRayTracingPipeline forFrame(Program program) {
        if (expert != null && (program == Program.COMPACT_CAUSTICS || program == Program.GENERAL_CAUSTICS))
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_CAUSTICS,
                    "built-in caustics cannot replace an expert scene program");
        return expert != null ? expert : builtIns.computeIfAbsent(program, this::create);
    }

    /** Built-in queue stages have both ordinary and SER shader permutations. Expert programs own their transport. */
    boolean supportsPrimaryQueue() { return expert == null; }

    long requiredNativeCameraGrowth(int width, int height, int samples) {
        retireOtherNativeModes(NativeMode.CAMERA);
        return nativeCameraMedium == null ? CudaCameraMediumManager.requiredBytes(width, height, samples) : nativeCameraMedium.requiredGrowthBytes(width, height, samples);
    }
    CudaCameraMediumManager nativeCameraMedium() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.CAMERA);
        if(nativeCameraMedium==null)nativeCameraMedium=new CudaCameraMediumManager(device);
        return nativeCameraMedium;
    }
    long requiredNativeBoundaryGrowth(CudaBoundaryMediumManager.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.BOUNDARY);
        return nativeBoundaryMedium == null ? CudaBoundaryMediumManager.requiredBytes(prepared) : nativeBoundaryMedium.requiredGrowthBytes(prepared);
    }
    CudaBoundaryMediumManager nativeBoundaryMedium() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.BOUNDARY);
        if(nativeBoundaryMedium==null)nativeBoundaryMedium=new CudaBoundaryMediumManager(device);
        return nativeBoundaryMedium;
    }
    long requiredNativeScatteringGrowth(int width, int height, int samples, int steps) {
        retireOtherNativeModes(NativeMode.DIRECT);
        return nativeCameraScattering == null ? CudaCameraScatteringManager.requiredBytes(width, height, samples, steps) : nativeCameraScattering.requiredGrowthBytes(width, height, samples, steps);
    }
    long requiredNativeScatteringGrowthForPbr(int width,int height,int samples,int steps) {
        retireOtherNativeModes(NativeMode.PBR_SCATTER);
        return nativeCameraScattering==null?CudaCameraScatteringManager.requiredBytes(width,height,samples,steps)
                :nativeCameraScattering.requiredGrowthBytes(width,height,samples,steps);
    }
    CudaCameraScatteringManager nativeCameraScattering() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.DIRECT);
        if(nativeCameraScattering==null)nativeCameraScattering=new CudaCameraScatteringManager(device);
        return nativeCameraScattering;
    }
    CudaCameraScatteringManager nativeCameraScatteringForPbr() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.PBR_SCATTER);
        if(nativeCameraScattering==null)nativeCameraScattering=new CudaCameraScatteringManager(device);
        return nativeCameraScattering;
    }
    long requiredNativeMultipleScatteringGrowth(int width, int height, int samples, int steps) {
        retireOtherNativeModes(NativeMode.MULTIPLE);
        return nativeMultipleScattering == null ? CudaMultipleScatteringManager.requiredBytes(width, height, samples, steps) : nativeMultipleScattering.requiredGrowthBytes(width, height, samples, steps);
    }
    CudaMultipleScatteringManager nativeMultipleScattering() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.MULTIPLE);
        if(nativeMultipleScattering==null)nativeMultipleScattering=new CudaMultipleScatteringManager(device);
        return nativeMultipleScattering;
    }
    long requiredNativeMultipleScatteringGrowth(top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaHeterogeneousMultipleScatteringAbi.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.MULTIPLE);
        return nativeMultipleScattering == null ? CudaMultipleScatteringManager.requiredBytes(prepared)
                : nativeMultipleScattering.requiredGrowthBytes(prepared);
    }
    long requiredNativeBoundaryScatteringGrowth(
            top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryScatteringAbi.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.BOUNDARY_SCATTER);
        return nativeMultipleScattering == null ? CudaMultipleScatteringManager.requiredBytes(prepared)
                : nativeMultipleScattering.requiredGrowthBytes(prepared);
    }
    GpuSceneRayTracingPipeline nativeBoundaryScattering() {
        if (!supportsPrimaryQueue()) throw new FrameValidationException(
                FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.BOUNDARY_SCATTER);
        return builtIns.computeIfAbsent(Program.NATIVE_BOUNDARY_SCATTERING, this::create);
    }
    long requiredNativeDensityScatteringGrowth(top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaDensityScatteringAbi.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.DENSITY);
        return nativeDensityScattering == null ? CudaDensityScatteringManager.requiredBytes(prepared) : nativeDensityScattering.requiredGrowthBytes(prepared);
    }
    CudaDensityScatteringManager nativeDensityScattering() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.DENSITY);
        if(nativeDensityScattering==null)nativeDensityScattering=new CudaDensityScatteringManager(device);
        return nativeDensityScattering;
    }
    long requiredNativeOpticalGrowth(CudaOpticalMediumManager.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.OPTICAL);
        return nativeOpticalMedium == null ? CudaOpticalMediumManager.requiredBytes(prepared) : nativeOpticalMedium.requiredGrowthBytes(prepared);
    }
    CudaOpticalMediumManager nativeOpticalMedium() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.OPTICAL);
        if(nativeOpticalMedium==null)nativeOpticalMedium=new CudaOpticalMediumManager(device);
        return nativeOpticalMedium;
    }
    long requiredNativePbrGrowth(CudaPbrMediumManager.FrameScene prepared) {
        retireOtherNativeModes(NativeMode.PBR);
        return nativePbrMedium == null ? CudaPbrMediumManager.requiredBytes(prepared)
                : nativePbrMedium.requiredGrowthBytes(prepared);
    }
    long requiredNativePbrScatteringGrowth(CudaPbrMediumManager.FrameScene prepared,int width,int height,int samples,int steps) {
        retireOtherNativeModes(pbrScatteringMode(prepared));
        CudaPbrMediumManager.requireCombinedResidentBudget(prepared,width,height,samples,steps);
        long pbr=nativePbrMedium==null?CudaPbrMediumManager.requiredBytes(prepared):nativePbrMedium.requiredGrowthBytes(prepared);
        var plan=prepared.transportPlan();
        long scattering=switch(plan.kind()) {
            case DIRECT_UNIFORM -> nativeCameraScattering==null?plan.scatteringBytes():nativeCameraScattering.requiredGrowthBytes(plan.direct());
            case DIRECT_DENSITY -> nativeDensityScattering==null?plan.scatteringBytes():nativeDensityScattering.requiredGrowthBytes(plan.densityDirect());
            case MULTIPLE_UNIFORM -> nativeMultipleScattering==null?plan.scatteringBytes():nativeMultipleScattering.requiredGrowthBytes(plan.multiple());
            case MULTIPLE_DENSITY -> nativeMultipleScattering==null?plan.scatteringBytes():nativeMultipleScattering.requiredGrowthBytes(plan.densityMultiple());
        };
        return Math.addExact(pbr,scattering);
    }
    private static NativeMode pbrScatteringMode(CudaPbrMediumManager.FrameScene prepared) {
        return switch(prepared.transportPlan().kind()) {
            case DIRECT_UNIFORM -> NativeMode.PBR_SCATTER;
            case DIRECT_DENSITY -> NativeMode.PBR_DENSITY;
            case MULTIPLE_UNIFORM, MULTIPLE_DENSITY -> NativeMode.PBR_MULTIPLE;
        };
    }
    CudaDensityScatteringManager nativeDensityScatteringForPbr() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.PBR_DENSITY);
        if(nativeDensityScattering==null)nativeDensityScattering=new CudaDensityScatteringManager(device);
        return nativeDensityScattering;
    }
    CudaMultipleScatteringManager nativeMultipleScatteringForPbr() {
        if(!supportsPrimaryQueue())throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.PBR_MULTIPLE);
        if(nativeMultipleScattering==null)nativeMultipleScattering=new CudaMultipleScatteringManager(device);
        return nativeMultipleScattering;
    }
    CudaPbrMediumManager nativePbrMedium() {
        if (!supportsPrimaryQueue()) throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(NativeMode.PBR);
        if (nativePbrMedium == null) nativePbrMedium = new CudaPbrMediumManager(device);
        return nativePbrMedium;
    }
    CudaPbrMediumManager nativePbrMediumWithScattering(CudaPbrMediumManager.FrameScene prepared) {
        if (!supportsPrimaryQueue()) throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native medium transport requires built-in scene shaders");
        retireOtherNativeModes(pbrScatteringMode(prepared));
        if(nativePbrMedium==null)nativePbrMedium=new CudaPbrMediumManager(device);
        return nativePbrMedium;
    }
    /** A session has one shared native residency budget across every transport mode. */
    private void retireOtherNativeModes(NativeMode keep) {
        if(keep!=NativeMode.CAMERA)retireNativeCameraMedium();
        if(keep!=NativeMode.BOUNDARY)retireNativeBoundaryMedium();
        if(keep!=NativeMode.DIRECT&&keep!=NativeMode.PBR_SCATTER)retireNativeCameraScattering();
        if(keep!=NativeMode.MULTIPLE&&keep!=NativeMode.BOUNDARY_SCATTER&&keep!=NativeMode.PBR_MULTIPLE)retireNativeMultipleScattering();
        if(keep!=NativeMode.DENSITY&&keep!=NativeMode.PBR_DENSITY)retireNativeDensityScattering();
        if(keep!=NativeMode.OPTICAL)retireNativeOpticalMedium();
        if(keep!=NativeMode.PBR&&keep!=NativeMode.PBR_SCATTER&&keep!=NativeMode.PBR_DENSITY&&keep!=NativeMode.PBR_MULTIPLE)retireNativePbrMedium();
    }

    private void retireNativeCameraScattering() {
        if (nativeCameraScattering == null) return;
        try { nativeCameraScattering.close(); } finally { nativeCameraScattering = null; }
    }

    private void retireNativeCameraMedium() {
        if (nativeCameraMedium == null) return;
        try { nativeCameraMedium.close(); } finally { nativeCameraMedium = null; }
    }

    private void retireNativeBoundaryMedium() {
        if (nativeBoundaryMedium == null) return;
        try { nativeBoundaryMedium.close(); } finally { nativeBoundaryMedium = null; }
    }

    private void retireNativeMultipleScattering() {
        if(nativeMultipleScattering==null)return;
        try{nativeMultipleScattering.close();}finally{nativeMultipleScattering=null;}
    }

    private void retireNativeDensityScattering() {
        if(nativeDensityScattering==null)return;
        try{nativeDensityScattering.close();}finally{nativeDensityScattering=null;}
    }

    private void retireNativeOpticalMedium() {
        if(nativeOpticalMedium==null)return;
        try{nativeOpticalMedium.close();}finally{nativeOpticalMedium=null;}
    }

    private void retireNativePbrMedium() {
        if (nativePbrMedium == null) return;
        try { nativePbrMedium.close(); } finally { nativePbrMedium = null; }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for(Runnable retire:List.<Runnable>of(this::retireNativeMultipleScattering,this::retireNativeDensityScattering,
                this::retireNativeOpticalMedium,this::retireNativePbrMedium)) {
            try{retire.run();}catch(RuntimeException closeFailure){if(failure==null)failure=closeFailure;else failure.addSuppressed(closeFailure);}
        }
        if (nativeCameraScattering != null) {
            try { retireNativeCameraScattering(); }
            catch (RuntimeException closeFailure) { if(failure==null)failure=closeFailure;else failure.addSuppressed(closeFailure); }
        }
        if (nativeCameraMedium != null) {
            try { retireNativeCameraMedium(); }
            catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
        }
        if (nativeBoundaryMedium != null) {
            try { retireNativeBoundaryMedium(); }
            catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
        }
        var owned = expert != null ? List.of(expert) : builtIns.values();
        for (var pipeline : owned) {
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
