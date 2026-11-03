package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** One resident upstream renderer. Static resources are exported only on structural edits. */
final class RtxptRenderer implements Renderer,RtxptAccess,SceneLightingControl {
    private final Thread owner=Thread.currentThread();
    private final boolean externalPresentation;
    private final boolean gpuTimingsEnabled;
    private final int maxFramesInFlight;
    private final RendererResourceOptions resources;
    private RtxptScene scene=new RtxptScene();
    private RtxptScene residentScene;
    private Map<Long,Path> textureFiles=new HashMap<>();
    private boolean fullReset=true;
    private RetainedUpdateMode lastUpdateMode=RetainedUpdateMode.UNCHANGED;
    private long resourceBatches,resourceMeshes,resourceMaterials,resourceTextures;
    private RtxptOptions options;
    private RendererFeatureProfile requestedProfile;
    private final RtxptFeatureController features;
    private long handle,revision,submitted=-1,completed=-1,frames,frameNs,exports,exportNs,batches,transformNs;
    private boolean closed,dirty=true,windowClosed,scenePublished,nativeContractPending;
    private int width,height;
    private Path directory,nativeScene,fullDirectory;
    private List<RtxptScene.Item> previous=List.of();
    private CameraState camera;
    private RtxptLighting lighting;
    private final Set<Long> pendingLightIds=new HashSet<>();
    private SceneLightingControl.Continuity continuity=SceneLightingControl.Continuity.DISCONTINUOUS;
    private float[] visibleBackground;
    private boolean backgroundChanged,transparentBackground;
    private HistoryFrame lastHistoryFrame=new HistoryFrame(-1,false,Set.of(),0,0,0,0);
    @Override public HistoryFrame historyFrame(){ready();return lastHistoryFrame;}
    private long validationNs,translationNs,nativeResourceNs,lightingNs,retirementNs;
    @Override public void lightingContinuity(SceneLightingControl.Continuity value){ready();Objects.requireNonNull(value);if(value==SceneLightingControl.Continuity.CONTINUOUS&&!supportsContinuousLighting())throw new IllegalArgumentException("Continuous lighting requires retained SCENE_ANALYTIC without ReSTIR or radiance cache");continuity=value;}
    @Override public boolean supportsContinuousLighting(){ready();return nativeScene==null&&options.qualityInts()[0]==1&&!options.restirDi()&&!options.restirGi()&&!options.sharc();}
    @Override public void visibleBackground(float r,float g,float b){ready();for(float v:new float[]{r,g,b})if(!Float.isFinite(v)||v<0)throw new IllegalArgumentException("Background must be finite nonnegative linear radiance");float[] next={r,g,b};backgroundChanged|=transparentBackground||!Arrays.equals(visibleBackground,next);transparentBackground=false;visibleBackground=next;}
    @Override public void environmentBackground(){ready();backgroundChanged|=transparentBackground||visibleBackground!=null;transparentBackground=false;visibleBackground=null;}

    private static boolean supportsTransparent(RtxptOptions o){return !o.restirDi()&&!o.restirGi()&&!o.sharc()&&!o.frameGeneration().preference().requested()&&!o.quality().postProcessing().bloom();}
    @Override public boolean supportsTransparentBackground(){ready();return supportsTransparent(options);}
    @Override public void transparentBackground(){ready();if(!supportsTransparentBackground())throw new UnsupportedOperationException("Transparent output requires ReSTIR, radiance cache, frame generation and bloom disabled");backgroundChanged|=!transparentBackground;transparentBackground=true;visibleBackground=null;}

    private RtxptNative.Compositor compositor;
    private String title;
    private Float exposureCompensation;
    RtxptRenderer(RendererConfig config){this(config,false);}
    RtxptRenderer(RendererConfig config,boolean externalPresentation){
        this.externalPresentation=externalPresentation;
        this.gpuTimingsEnabled=config.gpuTimingsEnabled();
        this.maxFramesInFlight=config.maxFramesInFlight();
        this.resources=config.resources();
        RtxptInputContract.validateConfiguration(config);requestedProfile=RtxptOptions.profile(config);options=RtxptOptions.resolve(requestedProfile);
        validatePresentationProfile(requestedProfile);
        features=new RtxptFeatureController(new RtxptFeatureController.Host(){
            public boolean initialized(){return handle!=0;}
            public void ready(){RtxptRenderer.this.ready();}
            public void apply(RendererFeatureProfile profile){validatePresentationProfile(profile);requestedProfile=profile;options=RtxptOptions.resolve(profile);if(handle!=0)applyNativeSettings();}
            public RendererFeatureProfile effective(RendererFeatureProfile profile){return effectiveProfile(profile);}
            public RendererFeatureProfile normalize(RendererFeatureProfile profile){return RtxptOptions.normalize(profile);}
        },RtxptOptions.normalize(RtxptOptions.profile(config)));
        RtxptNative.load();
        RtxptNative.prepare(RtxptNative.runtime().toString());
    }
    private void validatePresentationProfile(RendererFeatureProfile profile) {
        if(transparentBackground&&!supportsTransparent(RtxptOptions.resolve(profile)))throw new IllegalArgumentException("Requested features do not preserve transparent output; select an opaque background first");
        if(externalPresentation&&profile.frameGeneration().preference().requested())
            throw new IllegalArgumentException("Vulkan composition accepts native RTXPT frames; DXGI FG/MFG is only supported with native RTXPT presentation");
        if(externalPresentation&&profile.lowLatency().preference().requested())
            throw new IllegalArgumentException("RTXPT Reflex markers do not manage the final Vulkan swapchain; disable lowLatency in composed mode");
    }
    SceneTransaction sceneSnapshot() {
        ready();var tx=SceneTransaction.builder(revision).resetScene();
        scene.textures.values().forEach(tx::upsert);scene.materials.values().forEach(tx::upsert);
        scene.meshes.values().forEach(tx::upsert);scene.instances.values().forEach(tx::upsert);scene.lights.values().forEach(tx::upsert);
        return tx.build();
    }
    top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame sharedFrame(long sequence) {
        ready();if(handle==0||!externalPresentation)throw new IllegalStateException("No external RTXPT frame");
        var f=RtxptNative.sharedFrame(handle,sequence);
        return new top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame(f[0],f[1],f[2],(int)f[3],(int)f[4],f[5],f[6],f[7],f[8],f[9],top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame.AlphaMode.values()[(int)f[10]]);
    }
    void retireSharedFrames(){ready();if(handle!=0)RtxptNative.retireSharedFrames(handle);}
    void resetHistory(){ready();if(handle!=0)RtxptNative.resetHistory(handle);}
    void compositor(RtxptNative.Compositor callback){
        ready();if(externalPresentation)throw new IllegalStateException("Only native RTXPT presentation accepts a returning UI compositor");
        if(handle!=0)RtxptNative.compositor(handle,callback);compositor=callback;
    }
    void viewportTitle(String value){
        ready();Objects.requireNonNull(value,"title");if(value.isBlank())throw new IllegalArgumentException("Window title must not be blank");
        if(handle!=0)RtxptNative.title(handle,value);title=value;
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
        if(handle==0||closed||externalPresentation)return FrameGenerationEvidence.unavailable();
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
        if(!gpuTimingsEnabled||handle==0||closed)return RendererDiagnostics.FrameGpuTiming.unavailable();
        var t=RtxptNative.gpuTiming(handle);
        return t[0]==0?RendererDiagnostics.FrameGpuTiming.unavailable():RendererDiagnostics.FrameGpuTiming.builder()
            .enabled(true).completedSamples(t[0]).averagePostTraceNanos(t[1]).averageTotalNanos(t[1]).maxTotalNanos(t[2]).build();
    }
    @Override public SceneUpdateResult apply(SceneTransaction tx){
        return applyRetained(tx,true);
    }
    // The composed viewport retains Vulkan-only attributes unchanged and checks the native
    // contract only when that whole scene is actually selected for RTXPT rendering.
    SceneUpdateResult applyComposed(SceneTransaction tx){return applyRetained(tx,false);}
    private SceneUpdateResult applyRetained(SceneTransaction tx,boolean nativeContract){
        return commitScene(prepareScene(tx,nativeContract));
    }
    record PreparedScene(SceneTransaction transaction,RtxptScene value,boolean geometryUnchanged,boolean nativeContract) { }
    PreparedScene prepareScene(SceneTransaction tx,boolean nativeContract){
        ready();Objects.requireNonNull(tx);if(nativeScene!=null)throw new IllegalStateException("Choose retained API scenes or a native scene explicitly");
        RtxptInputContract.requireAdvancingRevision(tx.revision(),revision,scenePublished);
        var next=new RtxptScene(scene);next.retain(tx,nativeContract);
        if(tx.reset() || !tx.upserts().meshes().isEmpty() || !tx.upserts().textures().isEmpty()
                || tx.removals().meshIds().hasRemaining() || tx.removals().textureIds().hasRemaining())
            resources.validateAssets(next.meshes.values(),next.textures.values());
        boolean geometryUnchanged=geometryUnchanged(tx,scene);
        return new PreparedScene(tx,next,geometryUnchanged,nativeContract);
    }
    static boolean geometryUnchanged(SceneTransaction tx,RtxptScene scene) {
        if(tx.reset() || !tx.upserts().textures().isEmpty() || !tx.upserts().materials().isEmpty() || !tx.upserts().meshes().isEmpty()
            || tx.removals().textureIds().hasRemaining() || tx.removals().materialIds().hasRemaining() || tx.removals().meshIds().hasRemaining() || tx.removals().instanceIds().hasRemaining())return false;
        for(var i:tx.upserts().instances()) {
            var old=scene.instances.get(i.id());if(old==null || old.meshAssetId()!=i.meshAssetId() || !old.mediumBoundary().equals(i.mediumBoundary()) || old.surfaceVisibility()!=i.surfaceVisibility())return false;
        }
        return true;
    }
    SceneUpdateResult commitScene(PreparedScene prepared){
        var tx=prepared.transaction();
        tx.upserts().lights().forEach(l->pendingLightIds.add(l.id()));var removedLights=tx.removals().lightIds();while(removedLights.hasRemaining())pendingLightIds.add(removedLights.get());
        scene=prepared.value();dirty|=tx.hasChanges()&&!prepared.geometryUnchanged();fullReset|=tx.reset();
        nativeContractPending=!prepared.nativeContract();
        revision=tx.revision();scenePublished=true;return new SceneUpdateResult(revision);
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
        long validationBegin=System.nanoTime();ready();Objects.requireNonNull(f);if(f.sequence()<=submitted)throw new IllegalArgumentException("Frame sequence must increase");
        var historyBefore=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);
        var historyCauses=EnumSet.noneOf(HistoryCause.class);
        if(handle==0)historyCauses.add(HistoryCause.FIRST_FRAME);
        if(!Objects.equals(camera,f.camera()))historyCauses.add(HistoryCause.CAMERA);
        boolean extentChanged=width!=f.width()||height!=f.height();
        if(extentChanged)historyCauses.add(HistoryCause.OUTPUT_EXTENT);
        if(backgroundChanged)historyCauses.add(HistoryCause.BACKGROUND);
        if(!f.temporalHistoryResets().isEmpty())historyCauses.add(HistoryCause.EXPLICIT);
        RtxptInputContract.validateFrame(f);
        if(nativeContractPending&&nativeScene==null){scene.validate();nativeContractPending=false;}
        if(f.minimumSceneRevision()>revision)throw new IllegalArgumentException("Scene revision is not resident");
        var items=nativeScene==null?scene.items(f):List.<RtxptScene.Item>of();
        var nextLighting=nativeScene==null?RtxptLighting.sorted(f.environment(),scene.sortedLights()):null;
        // Validate the complete batch before scene export or any native mutation.
        boolean sameLights=lighting!=null&&nextLighting!=null&&nextLighting.lights.equals(lighting.lights);
        boolean sparseLights=lighting!=null&&!fullReset&&!sameLights;
        var changedLights=new ArrayList<SceneLight>();
        if(sparseLights)for(long id:pendingLightIds){var next=scene.lights.get(id);var old=residentScene.lights.get(id);
            if(next==null||old==null||next.type()!=old.type()){sparseLights=false;break;}
            if(!next.equals(old))changedLights.add(next);
        }
        if(lighting!=null&&nextLighting!=null){
            if(!lighting.environment.equals(nextLighting.environment))historyCauses.add(HistoryCause.ENVIRONMENT);
            if(!sameLights)historyCauses.add(HistoryCause.LIGHT);
        }
        float[] lightRecords=nextLighting!=null&&(!sameLights||fullReset)&&!sparseLights?nextLighting.records():null;
        validationNs+=System.nanoTime()-validationBegin;
        long translationBegin=System.nanoTime();
        boolean rebuild=nativeScene==null && (handle==0 || fullReset);
        boolean delta=nativeScene==null&&!rebuild&&(dirty||!RtxptScene.sameStructure(items,previous));
        var updateMode=nativeScene!=null?RetainedUpdateMode.NATIVE_SCENE:rebuild
            ?(handle==0?RetainedUpdateMode.INITIAL_PUBLICATION:RetainedUpdateMode.EXPLICIT_RESET)
            :delta?RetainedUpdateMode.RESOURCE_DELTA:!items.equals(previous)?RetainedUpdateMode.TRANSFORMS
            :!Objects.equals(nextLighting,lighting)?RetainedUpdateMode.LIGHTING:RetainedUpdateMode.UNCHANGED;
        if(delta||rebuild)historyCauses.add(HistoryCause.RESOURCE);
        RtxptDelta.Prepared preparedDelta=null;
        Path path=nativeScene;
        if(rebuild){long begin=System.nanoTime();try{
            if(directory==null)directory=Files.createTempDirectory("rtrenderer-rtxpt-");
            var generation=Files.createTempDirectory(directory,"full-");path=scene.export(generation,f,items);fullDirectory=generation;
            textureFiles=new HashMap<>();for(long id:scene.textures.keySet())textureFiles.put(id,generation.resolve("t"+id+".png"));
        }catch(IOException e){throw new IllegalStateException("RTXPT asset translation failed",e);}exportNs+=System.nanoTime()-begin;exports++;}
        if(delta)try{preparedDelta=RtxptDelta.prepare(Files.createTempDirectory(directory,"delta-"),residentScene,scene,previous,items,textureFiles);}
        catch(IOException e){throw new IllegalStateException("RTXPT delta translation failed",e);}
        translationNs+=System.nanoTime()-translationBegin;
        try {
        if(handle==0){
            if(path==null)throw new IllegalStateException("Submit a populated scene");
            handle=RtxptNative.create(RtxptNative.runtime().toString(),path.toString(),f.width(),f.height(),externalPresentation,gpuTimingsEnabled,
                maxFramesInFlight,resources.workerThreads(),resources.shaderCompilerThreads(),options.arguments(),options.qualityInts(),options.qualityFloats());
            if(compositor!=null)RtxptNative.compositor(handle,compositor);
            if(title!=null)RtxptNative.title(handle,title);
            applyNativeSettings();
            if(nativeScene==null)scene.uploadInteriors(handle);
        }else if(rebuild){RtxptNative.loadScene(handle,path.toString());applyNativeSettings();scene.uploadInteriors(handle);}
        if(preparedDelta!=null&&preparedDelta.hasWork()){
            long begin=System.nanoTime();RtxptNative.resources(handle,preparedDelta.manifest().toString());nativeResourceNs+=System.nanoTime()-begin;preparedDelta.translated().uploadInteriors(handle);
            textureFiles=preparedDelta.textureFiles();resourceBatches++;resourceMeshes+=preparedDelta.meshes();
            resourceMaterials+=preparedDelta.materials();resourceTextures+=preparedDelta.textures();
        }
        if(preparedDelta!=null)textureFiles=preparedDelta.textureFiles();
        if(width!=f.width()||height!=f.height()){RtxptNative.resize(handle,f.width(),f.height());width=f.width();height=f.height();}
        if(rebuild || !Objects.equals(camera,f.camera())){
            var c=f.camera();RtxptNative.camera(handle,c.x()+","+c.y()+","+c.z()+","+c.forwardX()+","+c.forwardY()+","+c.forwardZ()+","+c.upX()+","+c.upY()+","+c.upZ(),(float)(2*Math.atan(c.tanHalfFovY())));camera=c;
        }
        RtxptNative.lightingPolicy(handle,continuity.ordinal());
        RtxptNative.background(handle,visibleBackground,transparentBackground);
        if(!f.temporalHistoryResets().isEmpty())RtxptNative.resetHistory(handle);
        if(nextLighting!=null&&(rebuild||!nextLighting.equals(lighting))) {
            long begin=System.nanoTime();
            try {
                if(!rebuild&&sparseLights){
                    var batch=RtxptLighting.of(nextLighting.environment,changedLights);
                    RtxptNative.lightingDelta(handle,changedLights.stream().sorted(Comparator.comparingLong(SceneLight::id)).mapToLong(SceneLight::id).toArray(),Arrays.copyOfRange(batch.records(),16,(changedLights.size()+1)*16));
                    if(!nextLighting.environment.equals(lighting.environment))RtxptNative.environment(handle,nextLighting.sun(),nextLighting.sky());
                }else if(!rebuild&&sameLights)RtxptNative.environment(handle,nextLighting.sun(),nextLighting.sky());
                else RtxptNative.lighting(handle,nextLighting.ids(),lightRecords!=null?lightRecords:nextLighting.records(),nextLighting.sky());
            }
            catch(RuntimeException|Error failure) {
                // A failed native commit must never present a partially modified generation.
                try {close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
            lighting=nextLighting;lightingNs+=System.nanoTime()-begin;
        }
        if(!rebuild&&!delta)updateTransforms(items);
        previous=items;dirty=false;fullReset=false;residentScene=scene;pendingLightIds.clear();
        submitted=f.sequence();
        if(!renderFrame())throw new IllegalStateException("RTXPT window closed");
        scenePublished=true;
        verifyRequiredFeatures();
        lastUpdateMode=updateMode;
        var historyAfter=RtxptNative.executionTelemetry(handle);
        if(historyCauses.isEmpty()&&(historyAfter[8]!=historyBefore[8]||historyAfter[9]!=historyBefore[9]))historyCauses.add(HistoryCause.CONFIGURATION);
        lastHistoryFrame=new HistoryFrame(submitted,completed==submitted,historyCauses,historyAfter[7]-historyBefore[7],historyAfter[8]-historyBefore[8],historyAfter[9]-historyBefore[9],historyAfter[10]-historyBefore[10]);
        backgroundChanged=false;
        if(rebuild||delta){long begin=System.nanoTime();pruneTranslations();retirementNs+=System.nanoTime()-begin;}
        var reasons=EnumSet.noneOf(HistoryInvalidationReason.class);
        if(rebuild)reasons.add(frames==1?HistoryInvalidationReason.FIRST_FRAME:HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED);
        else if(delta)reasons.add(HistoryInvalidationReason.SCENE_CONTENT_CHANGED);
        if(extentChanged)reasons.add(HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED);
        if(historyCauses.contains(HistoryCause.ENVIRONMENT)||historyCauses.contains(HistoryCause.LIGHT)||historyCauses.contains(HistoryCause.BACKGROUND))
            if(lastHistoryFrame.pathAccumulationResets()>0||lastHistoryFrame.reconstructionResets()>0||lastHistoryFrame.denoiserResets()>0)reasons.add(HistoryInvalidationReason.FRAME_SHADING_CHANGED);
        for(var reason:f.temporalHistoryResets())reasons.add(HistoryInvalidationReason.valueOf(reason.name()));
        return FrameSubmissionResult.accepted(submitted,revision,reasons);
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    void resetWindowPollInterval(){ready();RtxptNative.resetWindowPollInterval(handle);}
    @Override public SubmitStages submitStages(){ready();var n=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);return new SubmitStages(validationNs,translationNs,nativeResourceNs,lightingNs,retirementNs,n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[12],n[13],n[14],n[15]);}
    @Override public HistoryActions historyActions(){ready();var n=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);return new HistoryActions(n[7],n[8],n[9],n[10],n[11]);}
    @Override public RetainedUpdateTimings retainedUpdateTimings(){ready();var t=handle==0?new long[11]:RtxptNative.resourceTelemetry(handle);
        return new RetainedUpdateTimings(resourceBatches,resourceMeshes,resourceMaterials,resourceTextures,t[0],t[1],t[2],t[3],t[4],t[5],t[6],t[7],t[8],t[9],t[10]);}
    long[] lightingTelemetry(){ready();return handle==0?new long[5]:RtxptNative.lightingTelemetry(handle);}
    @Override public LightingTimings lightingTimings(){var t=lightingTelemetry();return new LightingTimings(t[0],t[1],t[2],t[3],t[4]);}
    @Override public LightSamplingStatus lightSamplingStatus(){ready();var t=handle==0?new long[8]:RtxptNative.lightSamplingTelemetry(handle);
        return new LightSamplingStatus(LightSamplingMode.values()[(int)t[0]],t[1],t[2],t[3]!=0,t[4],t[5],t[6],t[7]);}
    @Override public RetainedUpdateMode retainedUpdateMode(){ready();return lastUpdateMode;}
    private void pruneTranslations(){
        if(directory==null)return;
        // Native imports are complete. Keep only the current full scene and PNGs
        // that a later material delta may reference; retry locked files next time.
        var liveTextures=new HashSet<>(textureFiles.values());
        try(var files=Files.walk(directory)){
            for(var path:files.sorted(Comparator.reverseOrder()).toList()) {
                if(path.equals(directory)||path.startsWith(fullDirectory)||liveTextures.contains(path))continue;
                try{Files.deleteIfExists(path);}catch(IOException ignored){ }
            }
        }catch(IOException ignored){ }
    }
    void captureLinear(Path path){ready();RtxptNative.captureLinear(handle,path.toAbsolutePath().toString());}
    void windowState(int state){ready();RtxptNative.windowState(handle,state);}
    private void applyNativeSettings(){RtxptNative.settings(handle,options.maxBounces(),options.diffuseBounces(),options.reflex(),options.frameGenerationMode(),options.generatedFrames(),
        options.samplesPerPixel(),options.reconstruction().ordinal(),options.dlssQuality().nativeValue,options.standaloneDenoiser(),options.relaxDenoiser(),options.restirDi(),options.restirGi(),options.sharc(),options.exactOpticalInterfaces());
        RtxptNative.pathQuality(handle,options.qualityInts(),options.qualityFloats());
        if(exposureCompensation!=null)RtxptNative.exposureCompensation(handle,exposureCompensation);}
    @Override public void setExposureCompensation(float stops){
        ready();if(!Float.isFinite(stops))throw new IllegalArgumentException("Exposure compensation must be finite");
        exposureCompensation=stops;if(handle!=0)RtxptNative.exposureCompensation(handle,stops);
    }
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
            s.restirGi()?path.indirectLightResampling():RendererFeaturePreference.DISABLED,s.exactOpticalInterfaces(),path.quality()));
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
    @Override public void setOverlayChoices(List<String> labels,int selectedIndex){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        labels=List.copyOf(Objects.requireNonNull(labels,"labels"));
        if(labels.size()>64 || (labels.isEmpty()?selectedIndex!=-1:selectedIndex<0||selectedIndex>=labels.size()))
            throw new IllegalArgumentException("Select an existing overlay choice; use -1 for an empty list (maximum 64)");
        for(String label:labels)if(label.isBlank())throw new IllegalArgumentException("Overlay labels must not be blank");
        RtxptNative.overlayChoices(handle,labels.toArray(String[]::new),selectedIndex);
    }
    @Override public int overlaySelection(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptNative.overlaySelection(handle);}
    @Override public PresentationStatus presentationStatus(){ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.presentationStatus(handle);
        return new PresentationStatus((int)s[0],(int)s[1],(float)s[2],(float)s[3],s[4]!=0,s[5]!=0,(float)s[6],(float)s[7]);}
    @Override public CameraState cameraState(){ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.cameraState(handle);
        return CameraState.lookAt(s[0],s[1],s[2],s[0]+s[3],s[1]+s[4],s[2]+s[5])
            .worldUp(s[6],s[7],s[8]).verticalFieldOfViewDegrees(Math.toDegrees(s[9])).aspectRatio(s[10]/s[11]).build();}
    @Override public RuntimeStatus runtimeStatus(){ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.runtimeStatus(handle);
        return new RuntimeStatus(s[0],s[1],Reconstruction.values()[s[2]],s[3]!=0,s[4]!=0,s[5]!=0,s[6]!=0,s[7]!=0,s[8]!=0,s[9]!=0,s[10]!=0,
            s[11],s[12],s[13],s[14]!=0,s[15]!=0,s[16]!=0,s[17]!=0,s[18]!=0,s[19]!=0,s[20],s[21]!=0);}
    @Override public PathQualityStatus pathQualityStatus(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");var s=RtxptNative.pathQualityStatus(handle);
        var light=new PathTracingQualityOptions.LightSampling(PathTracingQualityOptions.LightingInput.values()[(int)s[0]],
            PathTracingQualityOptions.LightSampler.values()[(int)s[1]],(int)s[2],(int)s[3],(float)s[19],(int)s[4],(int)s[5],s[6]!=0);
        var cache=new PathTracingQualityOptions.RadianceCache((int)s[7],(int)s[8],(int)s[9],(int)s[10],(int)s[11],
            (float)s[20],(float)s[22],(float)s[23],(int)s[17],(int)s[18],(float)s[24]);
        var actual=new PathTracingQualityOptions(light,cache,(int)s[12],(int)s[13],(int)s[14],
            PathTracingQualityOptions.SubSampling.values()[(int)s[15]],(float)s[21],s[16]!=0,
            new PathTracingQualityOptions.PostProcessing(s[32]!=0,(float)s[34],(float)s[35],s[33]!=0));
        return new PathQualityStatus(actual,(int)s[25],(int)s[26],(int)s[27],(int)s[28],(int)s[29],s[30]!=0,s[31]!=0);
    }
    @Override public long windowHandle(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptNative.window(handle);}
    @Override public void setWindowTitle(String title){viewportTitle(title);}
    @Override public void capture(Path png){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.capture(handle,Objects.requireNonNull(png).toAbsolutePath().toString());}
    void captureSource(Path png){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.captureSource(handle,Objects.requireNonNull(png).toAbsolutePath().toString());}
    void snapshotMaterials(Path directory){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.snapshotMaterials(handle,directory.toAbsolutePath().toString());}
    @Override public BridgeTimings bridgeTimings(){return new BridgeTimings(frames,frameNs,exports,exportNs,batches,transformNs);}
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest f){return new FrameSubmitted(submit(f));}
    @Override public boolean readFrame(FrameConsumer consumer){ready();Objects.requireNonNull(consumer);throw new UnsupportedOperationException("RTXPT presents GPU frames directly; CPU readback is not exposed");}
    @Override public Optional<FrameSnapshot> pollFrame(){ready();throw new UnsupportedOperationException("RTXPT GPU-native presentation");}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout){Objects.requireNonNull(timeout);return pollFrame();}
    @Override public void close(){
        if(closed)return;ready();Throwable failure=null;
        try{if(handle!=0)RtxptNative.close(handle);}
        catch(RuntimeException|Error e){failure=e;throw e;}
        finally {
            handle=0;closed=true;
            // A caller may retain a closed renderer; do not keep its entire scene or
            // a compositor closure (and its owner's Vulkan resources) alive with it.
            scene=new RtxptScene();residentScene=null;textureFiles.clear();pendingLightIds.clear();visibleBackground=null;previous=List.of();camera=null;lighting=null;compositor=null;nativeScene=null;
            if(directory!=null)try(var files=Files.walk(directory)){
                for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
            }catch(IOException e){
                var cleanup=new IllegalStateException("Failed to remove temporary scene assets",e);
                if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;
            }
        }
    }
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx){ready();Objects.requireNonNull(tx,"transaction");return new ResourceTransactionEvidence(tx.revision(),ResourceTransactionEvidence.Outcome.REJECTED,List.of(),"General GPU resource commands belong to Vulkan");}
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key){ready();Objects.requireNonNull(key,"key");return Optional.empty();}
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities(){return RenderingSemanticCapabilities.unsupported();}
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx){ready();Objects.requireNonNull(tx,"transaction");return new CommandExecutionEvidence(tx.sequence(),CommandExecutionEvidence.Outcome.REJECTED,CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,OptionalLong.empty(),Optional.empty(),0,"General command lane belongs to Vulkan");}
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long sequence){ready();if(sequence<0)throw new IllegalArgumentException("sequence must be nonnegative");return Optional.empty();}
}
