package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** One resident upstream renderer. Static resources are exported only on structural edits. */
final class RtxptRenderer implements Renderer,RtxptAccess {
    private final Thread owner=Thread.currentThread();
    private RtxptScene scene=new RtxptScene();
    private RtxptOptions options;
    private RendererFeatureProfile requestedProfile;
    private final RtxptFeatureController features;
    private long handle,revision,submitted=-1,completed=-1,frames,frameNs,exports,exportNs,batches,transformNs;
    private boolean closed,dirty=true,windowClosed;
    private int width,height;
    private Path directory,nativeScene;
    private List<RtxptScene.Item> previous=List.of();
    private CameraState camera;
    private EnvironmentState environment;
    RtxptRenderer(RendererConfig config){
        Objects.requireNonNull(config);requestedProfile=RtxptOptions.profile(config);options=RtxptOptions.resolve(requestedProfile);
        features=new RtxptFeatureController(new RtxptFeatureController.Host(){
            public boolean initialized(){return handle!=0;}
            public void ready(){RtxptRenderer.this.ready();}
            public void apply(RendererFeatureProfile profile){requestedProfile=profile;options=RtxptOptions.resolve(profile);if(handle!=0)applyNativeSettings();}
            public RendererFeatureProfile effective(RendererFeatureProfile profile){return effectiveProfile(profile);}
            public RendererFeatureProfile normalize(RendererFeatureProfile profile){return RtxptOptions.normalize(profile);}
        },RtxptOptions.normalize(RtxptOptions.profile(config)));
        RtxptNative.load();
    }
    @Override public <T> Optional<T> extension(Class<T> type){
        Objects.requireNonNull(type);
        if(type.isInstance(features))return Optional.of(type.cast(features));
        if(type==RenderingFeatureCapabilities.class && handle!=0)return Optional.of(type.cast(featureCapabilities()));
        return Renderer.super.extension(type);
    }
    private void ready(){if(closed)throw new IllegalStateException("RTXPT renderer closed");if(Thread.currentThread()!=owner)throw new IllegalStateException("Call RTXPT on its creating thread");}
    @Override public Status status(){return closed?Status.CLOSED:Status.READY;}
    @Override public RendererHealth health(){return new RendererHealth(status(),Optional.empty(),RendererHealth.ResourceObligations.none());}
    @Override public RendererDiagnostics diagnostics(){return RendererDiagnostics.builder().status(status()).latestAcceptedSceneRevision(revision)
        .latestSubmittedFrameSequence(submitted).latestCompletedFrameSequence(completed).residentMeshes(scene.meshes.size()).residentInstances(scene.instances.size())
        .frameGpuTiming(gpuTiming()).frameGenerationEvidence(generationEvidence()).build();}
    private FrameGenerationEvidence generationEvidence(){
        if(handle==0||closed)return FrameGenerationEvidence.unavailable();
        var t=RtxptNative.generationTelemetry(handle);var s=runtimeStatus();
        int requested=options.frameGeneration().preference().requested()?options.generatedFrames():0;
        long generated=Math.max(0,t[1]-t[0]);
        var b=FrameGenerationEvidence.builder().reported(true)
            .requestedGeneratedFramesPerNativeFrame(requested).lastSubmittedGeneratedFramesPerNativeFrame(s.frameGeneration()?requested:0)
            .configuredGeneratedFramesPerNativeFrame(s.frameGeneration()?requested:0).proxyPresentCalls(t[0])
            .stateQueryCalls(t[2]).stateSamples(t[3]).stateQueryFailures(t[4])
            .totalFramesActuallyPresented(t[1]).generatedFramesActuallyPresented(generated).lastFramesActuallyPresented((int)t[5])
            .latestQuerySucceeded(t[7]!=0).latestNativeStatus(t[7]!=0?OptionalInt.of((int)t[6]):OptionalInt.empty())
            .maximumSupportedGeneratedFramesPerNativeFrame((int)t[8]);
        if(t[0]>0)b.proxyPresentSequenceRange(0,t[0]-1);
        if(generated>0)b.lastGeneratedObservationSequence(t[0]-1);
        return b.build();
    }
    private RendererDiagnostics.FrameGpuTiming gpuTiming(){
        if(handle==0||closed)return RendererDiagnostics.FrameGpuTiming.unavailable();
        var t=RtxptNative.gpuTiming(handle);
        return t[0]==0?RendererDiagnostics.FrameGpuTiming.unavailable():RendererDiagnostics.FrameGpuTiming.builder()
            .enabled(true).completedSamples(t[0]).averagePostTraceNanos(t[1]).averageTotalNanos(t[1]).maxTotalNanos(t[2]).build();
    }
    @Override public SceneUpdateResult apply(SceneTransaction tx){
        ready();Objects.requireNonNull(tx);if(nativeScene!=null)throw new IllegalStateException("Choose retained API scenes or a native scene explicitly");
        if(tx.revision()<revision)throw new IllegalArgumentException("Scene revision must be monotonic");
        var next=new RtxptScene(scene);next.retain(tx);
        boolean transformsOnly=!tx.reset() && tx.upserts().textures().isEmpty() && tx.upserts().materials().isEmpty() && tx.upserts().meshes().isEmpty() && tx.upserts().lights().isEmpty()
            && !tx.removals().textureIds().hasRemaining() && !tx.removals().materialIds().hasRemaining() && !tx.removals().meshIds().hasRemaining() && !tx.removals().lightIds().hasRemaining() && !tx.removals().instanceIds().hasRemaining();
        if(transformsOnly)for(var i:tx.upserts().instances()) {
            var old=scene.instances.get(i.id());if(old==null || old.meshAssetId()!=i.meshAssetId() || !old.mediumBoundary().equals(i.mediumBoundary()) || old.surfaceVisibility()!=i.surfaceVisibility()) {transformsOnly=false;break;}
        }
        scene=next;dirty|=tx.hasChanges()&&!transformsOnly;revision=tx.revision();return new SceneUpdateResult(revision);
    }
    @Override public void loadNativeScene(Path value){
        ready();if(!scene.meshes.isEmpty())throw new IllegalStateException("Cannot replace retained API geometry with an unrelated native scene");
        var path=Objects.requireNonNull(value).toAbsolutePath().normalize();if(!Files.isRegularFile(path))throw new IllegalArgumentException("Missing native scene "+path);
        if(handle!=0){RtxptNative.loadScene(handle,path.toString());applyNativeSettings();}
        nativeScene=path;
        // Scene loading selects the scene's camera. Reapply the API camera on the next submit,
        // even if its value equals the camera used before the scene switch.
        camera=null;
    }
    @Override public FrameSubmissionResult submit(RenderFrameRequest f){
        ready();Objects.requireNonNull(f);if(f.sequence()<=submitted)throw new IllegalArgumentException("Frame sequence must increase");
        if(f.minimumSceneRevision()>revision)throw new IllegalArgumentException("Scene revision is not resident");
        if(f.camera().hasExactProjection() || !f.shaderData().isEmpty() || f.fog().opacity()>0 || f.caustics().enabled())
            throw new IllegalArgumentException("Exact Vulkan projections, custom shaders, screen fog and photon-map budgets are not native RTXPT controls");
        var items=nativeScene==null?scene.items(f):List.<RtxptScene.Item>of();
        boolean rebuild=nativeScene==null && (dirty || !RtxptScene.sameStructure(items,previous) || !Objects.equals(environment,f.environment()));
        Path path=nativeScene;
        if(rebuild){long begin=System.nanoTime();try{
            if(directory==null)directory=Files.createTempDirectory("rtrenderer-rtxpt-");
            path=scene.export(directory,f,items);
        }catch(IOException e){throw new IllegalStateException("RTXPT asset translation failed",e);}exportNs+=System.nanoTime()-begin;exports++;}
        if(handle==0){
            if(path==null)throw new IllegalStateException("Submit a populated scene");
            handle=RtxptNative.create(RtxptNative.runtime().toString(),path.toString(),f.width(),f.height(),options.arguments());
            applyNativeSettings();
            if(nativeScene==null)scene.uploadInteriors(handle);
        }else if(rebuild){RtxptNative.loadScene(handle,path.toString());applyNativeSettings();scene.uploadInteriors(handle);}
        if(width!=f.width()||height!=f.height()){RtxptNative.resize(handle,f.width(),f.height());width=f.width();height=f.height();}
        if(rebuild || !Objects.equals(camera,f.camera())){
            var c=f.camera();RtxptNative.camera(handle,c.x()+","+c.y()+","+c.z()+","+c.forwardX()+","+c.forwardY()+","+c.forwardZ()+","+c.upX()+","+c.upY()+","+c.upZ(),(float)(2*Math.atan(c.tanHalfFovY())));camera=c;
        }
        if(!rebuild)updateTransforms(items);
        previous=items;environment=f.environment();dirty=false;
        submitted=f.sequence();
        if(!renderFrame())throw new IllegalStateException("RTXPT window closed");
        verifyRequiredFeatures();
        return FrameSubmissionResult.accepted(submitted,revision,Set.of());
    }
    private void applyNativeSettings(){RtxptNative.settings(handle,options.maxBounces(),options.diffuseBounces(),options.reflex(),options.frameGenerationMode(),options.generatedFrames(),
        options.samplesPerPixel(),options.reconstruction().ordinal(),options.dlssQuality().nativeValue,options.standaloneDenoiser(),options.relaxDenoiser(),options.restirDi(),options.restirGi(),options.sharc());}
    private RendererFeatureProfile effectiveProfile(RendererFeatureProfile requested){
        if(handle==0)return RtxptOptions.normalize(requested);
        var s=runtimeStatus();var p=RtxptOptions.normalize(requested);
        var builder=p.toBuilder();
        if(s.reconstruction()==Reconstruction.NONE || !requested.frameReconstruction().preference().requested())
            builder.frameReconstruction(FrameReconstructionOptions.disabled());
        else {
            var actualMode=s.dlssMode()==RtxptOptions.DlssQuality.DLAA.nativeValue
                ?FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING
                :s.reconstruction()==Reconstruction.TAA?FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING
                :FrameReconstructionOptions.Mode.SUPER_RESOLUTION;
            var actualQuality=s.dlssMode()==1?FrameReconstructionOptions.Quality.PERFORMANCE
                :s.dlssMode()==2?FrameReconstructionOptions.Quality.BALANCED
                :s.dlssMode()==3?FrameReconstructionOptions.Quality.QUALITY
                :s.dlssMode()==4?FrameReconstructionOptions.Quality.ULTRA_PERFORMANCE
                :s.dlssMode()==5?FrameReconstructionOptions.Quality.ULTRA_QUALITY:FrameReconstructionOptions.Quality.AUTO;
            builder.frameReconstruction(FrameReconstructionOptions.builder().preference(requested.frameReconstruction().preference())
                .mode(actualMode).quality(actualQuality).build());
        }
        if(!s.frameGeneration())builder.frameGeneration(FrameGenerationOptions.disabled());
        else if(requested.frameGeneration().preference().requested()) {
            int generated=Math.max(1,Math.min(3,s.generatedFramesPerNativeFrame()));
            var multiplier=FrameGenerationOptions.Multiplier.values()[generated-1];
            var mode=generated==1?FrameGenerationOptions.Mode.FRAME_GENERATION:FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION;
            builder.frameGeneration(FrameGenerationOptions.builder().preference(requested.frameGeneration().preference())
                .mode(mode).multiplier(multiplier).fallback(requested.frameGeneration().fallback()).build());
        }
        if(!s.lowLatency())builder.lowLatency(LowLatencyOptions.disabled());
        if(!s.denoising())builder.denoising(DenoisingOptions.disabled());
        var path=p.pathTracing();
        builder.pathTracing(new PathTracingOptions(path.samplesPerPixel(),path.maxBounces(),path.diffuseBounces(),
            s.sharc()?path.radianceCache():RendererFeaturePreference.DISABLED,
            s.restirDi()?path.directLightResampling():RendererFeaturePreference.DISABLED,
            s.restirGi()?path.indirectLightResampling():RendererFeaturePreference.DISABLED));
        var ray=p.rayTracingOptimizations();
        builder.rayTracingOptimizations(ray.toBuilder().shaderExecutionReordering(s.shaderExecutionReordering()
            ?ray.shaderExecutionReordering():RendererFeaturePreference.DISABLED).memoryOptimization(RendererFeaturePreference.DISABLED).build());
        return builder.build();
    }
    private RenderingFeatureCapabilities featureCapabilities(){
        return featureCapabilities(requestedProfile,runtimeStatus());
    }
    static RenderingFeatureCapabilities featureCapabilities(RendererFeatureProfile p,RuntimeStatus s){
        var b=RenderingFeatureCapabilities.builder();
        var reconstruction=p.frameReconstruction();
        var generation=p.frameGeneration();
        boolean fixedGeneration=generation.mode()!=FrameGenerationOptions.Mode.ADAPTIVE;
        boolean reconstructionSupported=reconstruction.mode()!=FrameReconstructionOptions.Mode.SPATIAL_UPSCALING&&s.dlssSupported();
        var reconstructionImpl=s.reconstruction()==Reconstruction.DLSS_RR?"nvidia.streamline.dlss_rr":"nvidia.streamline.dlss";
        var reconstructionEntry=capability(reconstruction.preference().requested(),reconstructionSupported,
            requestedReconstructionActive(reconstruction,s),reconstructionImpl,null,"RTXPT reconstruction negotiation");
        if(reconstruction.preference().requested()&&s.reconstruction()==Reconstruction.TAA
            &&reconstruction.fallback()==FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL)
            reconstructionEntry=RenderingFeatureCapabilities.Entry.of(RenderingFeatureCapabilities.Status.FALLBACK,
                "nvidia.rtxpt.taa","Requested DLSS unavailable; upstream temporal anti-aliasing fallback is active");
        b.feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION,reconstructionEntry);
        String generationFallback=generation.fallback()==FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES
            ?"nvidia.rtxpt.native-presentation":null;
        b.feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION,capability(generation.preference().requested(),
            fixedGeneration&&s.frameGenerationSupported(),s.frameGeneration(),"nvidia.streamline.dlss_g",generationFallback,"RTXPT fixed-rate DLSS-FG negotiation"));
        b.feature(RenderingFeatureCapabilities.Feature.LOW_LATENCY,capability(p.lowLatency().preference().requested(),
            s.reflexSupported(),s.lowLatency(),"nvidia.reflex",null,"RTXPT Reflex state"));
        b.feature(RenderingFeatureCapabilities.Feature.DENOISING,capability(p.denoising().preference().requested(),
            true,s.denoising(),s.standaloneDenoiser()?"nvidia.nrd":"nvidia.dlss_rr",null,"RTXPT denoising state"));
        b.feature(RenderingFeatureCapabilities.Feature.SHADER_EXECUTION_REORDERING,capability(
            p.rayTracingOptimizations().shaderExecutionReordering().requested(),s.shaderExecutionReorderingSupported(),
            s.shaderExecutionReordering(),"nvidia.rtxpt.ser",null,"RTXPT SER state"));
        b.feature(RenderingFeatureCapabilities.Feature.MEMORY_OPTIMIZATION,capability(
            p.rayTracingOptimizations().memoryOptimization().requested(),false,false,"none",null,
            "RTXMU is not part of the RTXPT D3D12 path"));
        b.technology(RenderingFeatureCapabilities.Technology.TEMPORAL_SUPER_RESOLUTION,capability(
            reconstruction.preference().requested()&&reconstruction.mode()==FrameReconstructionOptions.Mode.SUPER_RESOLUTION,
            s.dlssSupported(),requestedReconstructionActive(reconstruction,s),
            reconstructionImpl,null,"Streamline DLSS super resolution"));
        b.technology(RenderingFeatureCapabilities.Technology.NATIVE_TEMPORAL_ANTI_ALIASING,capability(
            reconstruction.preference().requested()&&reconstruction.mode()==FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING,
            s.dlssSupported(),(s.reconstruction()==Reconstruction.DLSS||s.reconstruction()==Reconstruction.DLSS_RR)
                &&s.dlssMode()==RtxptOptions.DlssQuality.DLAA.nativeValue,
            "nvidia.streamline.dlaa",null,"Streamline DLAA"));
        b.technology(RenderingFeatureCapabilities.Technology.SPATIAL_UPSCALING,capability(
            reconstruction.mode()==FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                || reconstruction.fallback()==FrameReconstructionOptions.Fallback.SPATIAL,
            false,false,"none",null,"RTXPT does not expose the Streamline NIS path"));
        boolean multiFrame=s.frameGeneration()&&s.generatedFramesPerNativeFrame()>1;
        b.technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION,capability(
            generation.preference().requested()&&generation.multiplier()==FrameGenerationOptions.Multiplier.TWO_X,
            s.frameGenerationSupported(),s.frameGeneration()&&!multiFrame,"nvidia.streamline.dlss_g",generationFallback,"2x DLSS-FG"));
        b.technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION,capability(
            generation.preference().requested()&&(generation.mode()==FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION),
            s.frameGenerationSupported()&&s.maxGeneratedFrames()>1,multiFrame,"nvidia.streamline.dlss_g_mfg",generationFallback,"3x/4x DLSS-MFG"));
        b.technology(RenderingFeatureCapabilities.Technology.LOW_LATENCY_MARKERS,capability(
            p.lowLatency().preference().requested(),s.reflexSupported(),s.lowLatency(),"nvidia.reflex",null,"Streamline Reflex markers"));
        b.technology(RenderingFeatureCapabilities.Technology.RAY_TRACING_DENOISING,capability(
            p.denoising().preference().requested(),true,s.denoising(),s.standaloneDenoiser()?"nvidia.nrd":"nvidia.dlss_rr",null,"RTXPT native denoising"));
        b.technology(RenderingFeatureCapabilities.Technology.SHADER_EXECUTION_REORDERING,capability(
            p.rayTracingOptimizations().shaderExecutionReordering().requested(),s.shaderExecutionReorderingSupported(),
            s.shaderExecutionReordering(),"nvidia.rtxpt.ser",null,"RTXPT hit-object path"));
        b.technology(RenderingFeatureCapabilities.Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,capability(
            p.rayTracingOptimizations().memoryOptimization().requested(),false,false,"none",null,"RTXMU belongs to the Vulkan backend"));
        return b.build();
    }
    static boolean requestedReconstructionActive(FrameReconstructionOptions requested,RuntimeStatus actual){
        if(actual.reconstruction()!=Reconstruction.DLSS&&actual.reconstruction()!=Reconstruction.DLSS_RR)return false;
        return switch(requested.mode()) {
            case SUPER_RESOLUTION -> actual.dlssMode()!=RtxptOptions.DlssQuality.DLAA.nativeValue;
            case NATIVE_ANTI_ALIASING -> actual.dlssMode()==RtxptOptions.DlssQuality.DLAA.nativeValue;
            case SPATIAL_UPSCALING -> false;
        };
    }
    private static RenderingFeatureCapabilities.Entry capability(boolean requested,boolean supported,boolean active,
            String implementation,String fallbackImplementation,String reason){
        if(!requested)return RenderingFeatureCapabilities.Entry.disabled();
        if(active)return RenderingFeatureCapabilities.Entry.of(RenderingFeatureCapabilities.Status.ACTIVE,implementation,reason+"; active");
        if(supported)return RenderingFeatureCapabilities.Entry.of(RenderingFeatureCapabilities.Status.AVAILABLE,implementation,reason+"; available but not active");
        if(fallbackImplementation!=null)return RenderingFeatureCapabilities.Entry.of(RenderingFeatureCapabilities.Status.FALLBACK,fallbackImplementation,reason+"; native presentation retained");
        return RenderingFeatureCapabilities.Entry.of(RenderingFeatureCapabilities.Status.NOT_SUPPORTED,"none",reason+"; not supported by this backend");
    }
    private void verifyRequiredFeatures(){
        var p=requestedProfile;var s=runtimeStatus();
        var reconstruction=p.frameReconstruction();
        if(reconstruction.preference()==RendererFeaturePreference.REQUIRED) {
            if(!requestedReconstructionActive(reconstruction,s))throw new IllegalStateException("RTXPT did not activate required frame reconstruction: "+s);
        }
        var generation=p.frameGeneration();
        if(generation.preference()==RendererFeaturePreference.REQUIRED
            && (!s.frameGeneration() || s.generatedFramesPerNativeFrame()<generation.multiplier().presentedFramesPerNativeFrame()-1))
            throw new IllegalStateException("RTXPT did not activate the required DLSS frame-generation cadence: "+s);
        if(p.lowLatency().preference()==RendererFeaturePreference.REQUIRED&&!s.lowLatency())
            throw new IllegalStateException("RTXPT did not activate required Reflex low latency: "+s);
        if(p.denoising().preference()==RendererFeaturePreference.REQUIRED&&!s.denoising())
            throw new IllegalStateException("RTXPT did not activate required denoising: "+s);
        var path=p.pathTracing();
        if(path.radianceCache()==RendererFeaturePreference.REQUIRED&&!s.sharc())
            throw new IllegalStateException("RTXPT did not activate required SHaRC radiance cache: "+s);
        if(path.directLightResampling()==RendererFeaturePreference.REQUIRED&&!s.restirDi())
            throw new IllegalStateException("RTXPT did not activate required ReSTIR DI: "+s);
        if(path.indirectLightResampling()==RendererFeaturePreference.REQUIRED&&!s.restirGi())
            throw new IllegalStateException("RTXPT did not activate required ReSTIR GI: "+s);
        if(p.rayTracingOptimizations().shaderExecutionReordering()==RendererFeaturePreference.REQUIRED
            && !s.shaderExecutionReordering())
            throw new IllegalStateException("RTXPT did not activate required SER: "+s);
    }
    private void updateTransforms(List<RtxptScene.Item> items){
        var changed=new ArrayList<RtxptScene.Item>();for(int i=0;i<items.size();i++)if(!items.get(i).transform().equals(previous.get(i).transform()))changed.add(items.get(i));
        if(changed.isEmpty())return;
        long begin=System.nanoTime();var data=RtxptScene.floats(changed.size()*12);for(var i:changed)data.put(i.transform().elements());
        RtxptNative.transforms(handle,changed.stream().map(RtxptScene.Item::name).toArray(String[]::new),data.flip());batches++;transformNs+=System.nanoTime()-begin;
    }
    @Override public boolean renderFrame(){ready();if(handle==0)throw new IllegalStateException("Submit a scene first");if(windowClosed)return false;
        long begin=System.nanoTime();int result=RtxptNative.frame(handle);frameNs+=System.nanoTime()-begin;if(result==2){frames++;completed=submitted;}else if(result==0)windowClosed=true;return result!=0;}
    @Override public void awaitIdle(){ready();if(handle!=0)RtxptNative.awaitIdle(handle);}
    @Override public void setOverlayText(String text){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.overlay(handle,Objects.requireNonNull(text));}
    @Override public PresentationStatus presentationStatus(){ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.presentationStatus(handle);
        return new PresentationStatus((int)s[0],(int)s[1],(float)s[2],(float)s[3],s[4]!=0);}
    @Override public RuntimeStatus runtimeStatus(){ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.runtimeStatus(handle);
        return new RuntimeStatus(s[0],s[1],Reconstruction.values()[s[2]],s[3]!=0,s[4]!=0,s[5]!=0,s[6]!=0,s[7]!=0,s[8]!=0,s[9]!=0,s[10]!=0,
            s[11],s[12],s[13],s[14]!=0,s[15]!=0,s[16]!=0,s[17]!=0,s[18]!=0,s[19]!=0);}
    @Override public long windowHandle(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptNative.window(handle);}
    @Override public void capture(Path png){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.capture(handle,Objects.requireNonNull(png).toAbsolutePath().toString());}
    void snapshotMaterials(Path directory){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.snapshotMaterials(handle,directory.toAbsolutePath().toString());}
    @Override public BridgeTimings bridgeTimings(){return new BridgeTimings(frames,frameNs,exports,exportNs,batches,transformNs);}
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest f){return new FrameSubmitted(submit(f));}
    @Override public boolean readFrame(FrameConsumer consumer){ready();Objects.requireNonNull(consumer);throw new UnsupportedOperationException("RTXPT presents GPU frames directly; CPU readback is not exposed");}
    @Override public Optional<FrameSnapshot> pollFrame(){ready();throw new UnsupportedOperationException("RTXPT GPU-native presentation");}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout){Objects.requireNonNull(timeout);return pollFrame();}
    @Override public void close(){
        if(closed)return;ready();try{if(handle!=0)RtxptNative.close(handle);}finally{handle=0;closed=true;}
        if(directory!=null)try(var files=Files.walk(directory)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}catch(IOException e){throw new IllegalStateException("Failed to remove temporary scene assets",e);}
    }
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx){return new ResourceTransactionEvidence(tx.revision(),ResourceTransactionEvidence.Outcome.REJECTED,List.of(),"General GPU resource commands belong to Vulkan");}
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key){return Optional.empty();}
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities(){return RenderingSemanticCapabilities.unsupported();}
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx){return new CommandExecutionEvidence(tx.sequence(),CommandExecutionEvidence.Outcome.REJECTED,CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,OptionalLong.empty(),Optional.empty(),0,"General command lane belongs to Vulkan");}
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long sequence){return Optional.empty();}
}
