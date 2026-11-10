package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Retained input conversion and ownership at the original RTXPT call boundary. */
final class RtxptRenderer implements Renderer, RtxptAccess,
        SparseLightingControl, NativeLightingControl {
    private final Thread owner=Thread.currentThread();
    private final boolean externalPresentation;
    private final RendererResourceOptions resources;
    private final RtxptOptions options;
    private float exposureOffset;
    private String windowTitle;
    private String overlayText="";
    private List<String> overlayChoices=List.of();
    private int overlaySelected=-1;
    private final RtxptDiagnostics.Recorder recorder=new RtxptDiagnostics.Recorder();
    private final RtxptMemoryResources.PacketPool resourcePackets=new RtxptMemoryResources.PacketPool();
    private final Map<Long,TextureAsset> memoryTextures=new HashMap<>();
    // Only semantic variant declarations, not GPU ownership or a second scene.
    // A temporary lack of geometry users does not delete a MaterialAsset.
    private final Map<Long,Map<String,RtxptScene.Variant>> memoryMaterialVariants=new HashMap<>();
    private final Map<Long,Map<String,RtxptScene.Item>> memoryMeshVariants=new HashMap<>();
    private RtxptScene scene=new RtxptScene(),residentScene=new RtxptScene();
    private List<RtxptScene.Item> previous=List.of();
    private long handle,revision,submitted=-1,completed=-1;
    private boolean closed,scenePublished,fullReset=true,windowClosed;
    private boolean sessionRequiresStreamline;
    private int width,height;
    private Path nativeScene;
    private CameraState camera;
    private EnvironmentState environment;
    private NativeLightingPlan nativeLightingPlan;
    private long lightingAttributeGeneration,appliedLightingAttributeGeneration=-1;
    private boolean lightingAttributeReset=true;
    private final Map<Long,NativeLightingPublication.Instance> lightingInstances=new HashMap<>();
    private final Map<Long,float[]> lightingBaselines=new HashMap<>();
    private final Set<Long> pendingLightingInstances=new HashSet<>(),pendingLightingBaselines=new HashSet<>(),pendingLightingColors=new HashSet<>();
    private List<RtxptScene.Item> pendingGeometryVariants=List.of();
    private float[] pendingBaselineLookup,lightingBaselineLookup;
    private NativeLightingHostResponse lightingHostResponse;
    private final RtxptApplicationData applicationData=new RtxptApplicationData();
    private int lightingApplicationBytes,lightingReconstructionDivisor=1;
    private NativeLightingReconstruction lightingReconstruction=NativeLightingReconstruction.NONE;
    private NativeLightingPlan appliedLightingPlan;
    private FramePrimitiveBatch appliedLightingPrimitives=FramePrimitiveBatch.empty();
    private NativeLightingReconstruction appliedReconstruction;
    private int appliedReconstructionDivisor;

    RtxptRenderer(RendererConfig config){this(config,false);}
    RtxptRenderer(RendererConfig config,boolean externalPresentation){
        this.externalPresentation=externalPresentation;
        resources=config.resources();
        options=RtxptOptions.resolve(RtxptOptions.profile(config));
        exposureOffset=options.quality().postProcessing().exposureOffset();
        RtxptNative.load();
    }
    private void ready(){
        if(closed)throw new IllegalStateException("RTXPT renderer closed");
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the renderer on its creating thread");
    }
    @Override public Status status(){return closed?Status.CLOSED:windowClosed?Status.FAILED:Status.READY;}
    @Override public RendererDiagnostics diagnostics(){
        ready();return RendererDiagnostics.builder().status(status()).latestAcceptedSceneRevision(revision)
            .latestSubmittedFrameSequence(submitted).latestCompletedFrameSequence(completed)
            .residentMeshes(scene.meshes.size()).residentInstances(scene.instances.size()).build();
    }
    @Override public void diagnosticLevel(RtxptDiagnostics.Level level){ready();recorder.level(level);resourcePackets.diagnostics(level!=RtxptDiagnostics.Level.OFF);if(handle!=0)RtxptNative.diagnosticLevel(handle,level.ordinal());}
    @Override public RtxptDiagnostics.Snapshot diagnosticSnapshot(long afterOrdinal){ready();return recorder.snapshot(afterOrdinal);}
    @Override public RtxptDiagnostics.Monitor diagnosticMonitor(){ready();return recorder.monitor();}
    @Override public RtxptDiagnostics.NativeMonitor nativeDiagnosticMonitor(){ready();if(handle==0)throw new IllegalStateException("Submit before acquiring native diagnostics");return RtxptDiagnostics.nativeMonitor(handle);}
    @Override public <T> Optional<T> extension(Class<T> type){Objects.requireNonNull(type);return type.isInstance(this)?Optional.of(type.cast(this)):Optional.empty();}
    @Override public SceneUpdateResult apply(SceneTransaction tx){return apply(tx,List.of(),LongBuffer.allocate(0));}
    @Override public SceneUpdateResult apply(SceneTransaction tx,List<SparseLightSource> upserts,LongBuffer removals){
        ready();Objects.requireNonNull(tx);Objects.requireNonNull(upserts);Objects.requireNonNull(removals);
        if(nativeScene!=null)throw new IllegalStateException("A native file scene does not accept retained transactions");
        RtxptInputContract.requireAdvancingRevision(tx.revision(),revision,scenePublished);
        var next=new RtxptScene(scene);next.retain(tx);
        if(tx.reset())next.sparseSources.clear();
        var changed=new HashSet<Long>();
        for(var source:upserts){Objects.requireNonNull(source);if(!changed.add(source.id()))throw new IllegalArgumentException("Duplicate source identity");next.sparseSources.put(source.id(),source);}
        var ids=removals.duplicate();while(ids.hasRemaining()){long id=ids.get();if(!changed.add(id))throw new IllegalArgumentException("Duplicate source identity");next.sparseSources.remove(id);}
        if(next.sparseSources.size()>262144)throw new IllegalArgumentException("Sparse source capacity exceeded");
        next.validateAssetBudget(resources);
        for(var mesh:tx.upserts().meshes()){
            var old=scene.meshes.get(mesh.id());
            if(old==null||!old.lightmapCoordinates().equals(mesh.lightmapCoordinates()))pendingLightingBaselines.add(mesh.id());
            if(old==null||!old.vertexColorsRgba8().equals(mesh.vertexColorsRgba8()))pendingLightingColors.add(mesh.id());
        }
        if(tx.reset()){lightingInstances.clear();lightingBaselines.clear();pendingLightingInstances.clear();pendingLightingBaselines.clear();pendingLightingColors.clear();lightingAttributeReset=true;}
        for(var instance:tx.upserts().instances())pendingLightingInstances.add(instance.id());
        var removedInstances=tx.removals().instanceIds();while(removedInstances.hasRemaining()){long id=removedInstances.get();lightingInstances.remove(id);pendingLightingInstances.remove(id);}
        var removedMeshes=tx.removals().meshIds();while(removedMeshes.hasRemaining()){long id=removedMeshes.get();lightingBaselines.remove(id);pendingLightingBaselines.remove(id);pendingLightingColors.remove(id);}

        scene=next;revision=tx.revision();scenePublished=true;fullReset|=tx.reset();
        return new SceneUpdateResult(revision);
    }
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest frame){
        ready();Objects.requireNonNull(frame);
        if(handle!=0&&nativeLightingPlan!=null&&!RtxptNative.nativeLightingCanPublish(handle))
            return new FrameSubmissionDeferred(SubmissionDeferralReason.FRAME_RING_FULL,"Return lighting leases before submitting another frame");
        return new FrameSubmitted(submit(frame,true));
    }
    @Override public FrameSubmissionResult submit(RenderFrameRequest frame){
        return submit(frame,false);
    }
    private FrameSubmissionResult submit(RenderFrameRequest frame,boolean admitted){
        ready();Objects.requireNonNull(frame);long begin=recorder.clock();
        RtxptInputContract.validateFrame(frame,nativeLightingPlan!=null);
        if(frame.sequence()<=submitted||frame.minimumSceneRevision()>revision)throw new IllegalArgumentException("Frame sequence or resident revision mismatch");
        if(frame.width()>16384||frame.height()>16384)throw new IllegalArgumentException("Output extent exceeds the D3D12 limit");
        if(nativeLightingPlan!=null&&nativeLightingPlan.style().additionMode().ordinal()>=NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1.ordinal()&&lightingHostResponse==null)
            throw new IllegalStateException("Publish the host response before HOST_RESPONSE_RATIO_V1");
        // trySubmit already checked this renderer-thread admission. No other
        // frame can be submitted between that check and this call; lease
        // returns can only make additional slots available.
        if(!admitted&&handle!=0&&nativeLightingPlan!=null&&!RtxptNative.nativeLightingCanPublish(handle))throw new IllegalStateException("Lighting output ring is full");
        recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.VALIDATION,begin,0,0,false);
        // Independent lighting owns its requested grid and exact projection.
        // The sample's unused presentation targets need that grid, not another
        // full-size copy of the consumer's color/depth surface. Beauty keeps the
        // ordinary frame extent; never alter frame identity or camera matrices.
        int nativeWidth=nativeLightingPlan==null?frame.width():nativeLightingPlan.width();
        int nativeHeight=nativeLightingPlan==null?frame.height():nativeLightingPlan.height();
        boolean reset=fullReset,first=handle==0,extentChanged=width!=nativeWidth||height!=nativeHeight;
        try{
            if(first){
                sessionRequiresStreamline=nativeLightingPlan==null||lightingReconstruction==NativeLightingReconstruction.DLSS_RR;
                handle=RtxptNative.createSession(RtxptNative.runtime().toString(),nativeScene==null?null:nativeScene.toString(),nativeWidth,nativeHeight,externalPresentation,sessionRequiresStreamline,options.arguments());
                RtxptNative.diagnosticLevel(handle,recorder.level().ordinal());
                RtxptNative.presentation(handle,options.maxBounces(),options.diffuseBounces(),options.reflex(),options.generatedFrames());
                options.applyQuality(handle);
                RtxptNative.denoiser(handle,options.relaxDenoiser());
                RtxptNative.exposure(handle,exposureOffset);
                if(windowTitle!=null)RtxptNative.title(handle,windowTitle);
                if(!overlayText.isEmpty())RtxptNative.overlayText(handle,overlayText);
                if(!overlayChoices.isEmpty())RtxptNative.overlayChoices(handle,overlayChoices.toArray(String[]::new),overlaySelected);
                width=nativeWidth;height=nativeHeight;
            }
            else if(reset&&nativeScene==null)RtxptNative.loadScene(handle,null);
            RtxptNative.frameContext(handle,frame.sequence(),revision);
            if(extentChanged&&!first){RtxptNative.resize(handle,nativeWidth,nativeHeight);width=nativeWidth;height=nativeHeight;}
            // Select the external direct pass before importing materials with explicit sampler policies.
            if(nativeLightingPlan!=null)RtxptNative.nativeLighting(handle,RtxptNativeLightingParameters.pack(nativeLightingPlan,frame));
            var before=reset?new RtxptScene():residentScene;
            RtxptDelta.Prepared pendingMeshImport=null;
            var items=nativeScene==null?scene.items(frame):List.<RtxptScene.Item>of();
            boolean resourcesChanged=nativeScene==null&&(reset||!scene.meshes.sameSnapshot(before.meshes)||!scene.materials.sameSnapshot(before.materials)||!scene.textures.sameSnapshot(before.textures)||!RtxptScene.sameItems(items,previous));
            if(resourcesChanged){
                begin=recorder.clock();
                if(reset){memoryTextures.clear();memoryMaterialVariants.clear();memoryMeshVariants.clear();}
                var delta=RtxptDelta.prepare(before,scene,reset?List.of():previous,items,memoryTextures,memoryMaterialVariants,memoryMeshVariants,resourcePackets);
                recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.TRANSLATION,begin,delta.work().itemReads(),delta.memory()==null?0:delta.memory().remaining(),false);
                pendingGeometryVariants=delta.changedVariants();
                if(delta.hasWork()){
                    begin=recorder.clock();boolean failed=true;int bytes=delta.memory().remaining();
                    try(var packet=resourcePackets.borrow(delta.memory())){RtxptNative.resourcesMemory(handle,revision,packet.buffer());failed=false;}
                    finally{recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.RESOURCES,begin,delta.meshes(),bytes,failed);}
                    for(var entry:delta.translated().textures.entrySet())memoryTextures.put(entry.getKey(),entry.getValue());
                    scene.textures.forEachChanged(before.textures,(id,value)->{if(value==null)memoryTextures.remove(id);});
                    scene.materials.forEachChanged(before.materials,(id,value)->{if(value==null)memoryMaterialVariants.remove(id);});
                    for(var variant:delta.materialVariants())memoryMaterialVariants.computeIfAbsent(variant.material(),id->new LinkedHashMap<>()).put(variant.name(),variant);
                    pendingMeshImport=delta;
                }
                if(reset)resourcePackets.clear();
            }
            if(!Objects.equals(camera,frame.camera())){var c=frame.camera();RtxptNative.camera(handle,c.x()+","+c.y()+","+c.z()+","+c.forwardX()+","+c.forwardY()+","+c.forwardZ()+","+c.upX()+","+c.upY()+","+c.upZ(),(float)(2*Math.atan(c.tanHalfFovY())));camera=c;}
            if(nativeScene==null){
                begin=recorder.clock();boolean failed=true;
                try{publishLights(before,frame.environment(),reset);failed=false;}
                finally{recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.LIGHTING,begin,scene.sparseSources.size(),0,failed);}
            }
            begin=recorder.clock();boolean failed=true;
            long attributeItems=lightingAttributeReset?lightingInstances.size()+scene.meshes.size():pendingLightingInstances.size()+pendingLightingBaselines.size()+pendingLightingColors.size()+pendingGeometryVariants.size();
            try{prepareNativeLighting(frame,items);failed=false;}
            finally{recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.ATTRIBUTES,begin,attributeItems,0,failed);}
            if(!frame.temporalHistoryResets().isEmpty())RtxptNative.resetHistory(handle);
            begin=recorder.clock();failed=true;
            try{if(RtxptNative.frame(handle)==0){windowClosed=true;throw new IllegalStateException("RTXPT window closed before frame completion");}failed=false;}
            finally{recorder.add(frame.sequence(),revision,RtxptDiagnostics.Stage.RTXPT_FRAME,begin,1,0,failed);}
            if(pendingMeshImport!=null)RtxptDelta.commitMeshVariants(memoryMeshVariants,pendingMeshImport);
            submitted=completed=frame.sequence();residentScene=scene;previous=items;fullReset=false;
            var reasons=EnumSet.noneOf(HistoryInvalidationReason.class);
            if(first)reasons.add(HistoryInvalidationReason.FIRST_FRAME);
            else if(reset)reasons.add(HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED);
            else if(resourcesChanged)reasons.add(HistoryInvalidationReason.SCENE_CONTENT_CHANGED);
            if(extentChanged)reasons.add(HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED);
            for(var reason:frame.temporalHistoryResets())reasons.add(HistoryInvalidationReason.valueOf(reason.name()));
            return FrameSubmissionResult.accepted(submitted,revision,reasons);
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private void publishLights(RtxptScene before,EnvironmentState nextEnvironment,boolean reset){
        boolean environmentChanged=reset||!Objects.equals(environment,nextEnvironment);
        if(!reset&&scene.lights.sameSnapshot(before.lights)&&scene.sparseSources.sameSnapshot(before.sparseSources)&&!environmentChanged)return;
        var lights=new ArrayList<SceneLight>();var removedLights=new ArrayList<Long>();
        scene.lights.forEachChanged(before.lights,(id,value)->{if(value==null)removedLights.add(id);else lights.add(value);});
        if(reset||!lights.isEmpty()||!removedLights.isEmpty()||environmentChanged){
            var packet=RtxptLighting.of(nextEnvironment,lights);var all=packet.records();
            RtxptNative.lightingPatch(handle,lights.stream().mapToLong(SceneLight::id).toArray(),Arrays.copyOfRange(all,16,all.length),removedLights.stream().mapToLong(Long::longValue).toArray(),packet.sun(),environmentChanged?packet.sky():new float[0]);
        }
        var sources=new ArrayList<SparseLightSource>();var removed=new ArrayList<Long>();
        scene.sparseSources.forEachChanged(before.sparseSources,(id,value)->{if(value==null)removed.add(id);else sources.add(value);});
        if(reset||!sources.isEmpty()||!removed.isEmpty())RtxptNative.sparseLighting(handle,sources.stream().mapToLong(SparseLightSource::id).toArray(),RtxptSparseSources.pack(sources),removed.stream().mapToLong(Long::longValue).toArray(),reset);
        environment=nextEnvironment;
    }
    private NativeLightingPlan lightingPublicationPlan(){return nativeLightingPlan();}
    @Override public NativeLightingPlan nativeLightingPlan(){ready();if(nativeLightingPlan==null)throw new IllegalStateException("Select native lighting first");return nativeLightingPlan;}
    @Override public void nativeLightingPlan(NativeLightingPlan plan){
        ready();Objects.requireNonNull(plan);
        if(options.quality().beautyTransport()!=PathTracingQualityOptions.BeautyTransport.NATIVE)
            throw new IllegalArgumentException("Analytic beauty and independent lighting signals are separate output modes");
        if(!externalPresentation||nativeScene!=null||options.reconstruction()!=Reconstruction.NONE||options.standaloneDenoiser()||options.restirDi()||options.restirGi()||options.sharc())throw new IllegalArgumentException("Native lighting requires an external finite-signal configuration");
        nativeLightingPlan=plan;
        scene.nativeLighting=true;residentScene.nativeLighting=true;
    }
    @Override public void publishLightingAttributes(NativeLightingPublication publication){
        ready();Objects.requireNonNull(publication);lightingPublicationPlan();
        if(publication.generation()<=lightingAttributeGeneration)throw new IllegalArgumentException("Lighting attribute generations must increase");
        var instanceIds=new HashSet<Long>();var meshIds=new HashSet<Long>();
        for(var value:publication.instances())if(!instanceIds.add(value.id())||!scene.instances.containsKey(value.id()))throw new IllegalArgumentException("Unknown or duplicate lighting instance: "+value.id());
        for(var value:publication.baselines())if(!meshIds.add(value.meshId())||!scene.meshes.containsKey(value.meshId())||value.coordinates().length!=scene.meshes.get(value.meshId()).vertexCount()*2)
            throw new IllegalArgumentException("Unknown, duplicate or mismatched baseline mesh: "+value.meshId());
        for(var value:publication.instances())lightingInstances.put(value.id(),value);
        for(var value:publication.baselines())lightingBaselines.put(value.meshId(),value.coordinates());
        pendingLightingInstances.addAll(instanceIds);pendingLightingBaselines.addAll(meshIds);
        var lookup=publication.baselineLookup();
        if(lookup!=null)lightingBaselineLookup=lookup;
        if(publication.hostResponse()!=null)lightingHostResponse=publication.hostResponse();
        if(lookup!=null||publication.hostResponse()!=null){
            if(lightingBaselineLookup==null){
                lightingBaselineLookup=new float[512];
                for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++){
                    int index=(sky*16+local)*2;lightingBaselineLookup[index]=local/15f;lightingBaselineLookup[index+1]=sky/15f;
                }
            }
            pendingBaselineLookup=Arrays.copyOf(lightingBaselineLookup,lightingHostResponse==null?512:1280);
            if(lightingHostResponse!=null)System.arraycopy(lightingHostResponse.values(),0,pendingBaselineLookup,512,NativeLightingHostResponse.SAMPLES);
        }
        if(publication.applicationData()!=null){applicationData.publish(publication.applicationData());lightingApplicationBytes=applicationData.capacity();}
        lightingAttributeGeneration=publication.generation();
    }
    @Override public void patchLightingApplicationData(NativeLightingApplicationPatch patch){
        ready();lightingPublicationPlan();Objects.requireNonNull(patch);
        var source=patch.data();int bytes=source.remaining();
        if(patch.generation()<=lightingAttributeGeneration)throw new IllegalArgumentException("Application patch generations must increase with attribute publications");
        if(patch.offset()>lightingApplicationBytes-bytes)
            throw new IllegalArgumentException("Application patch must remain within the existing publication and extension capacity");
        applicationData.patch(patch.offset(),source);
        lightingAttributeGeneration=patch.generation();
    }

    private void prepareNativeLighting(RenderFrameRequest frame,List<RtxptScene.Item> items){
        if(nativeLightingPlan==null)return;
        if(appliedLightingPlan==null||appliedLightingPlan.encoding()!=nativeLightingPlan.encoding()){
            RtxptNative.nativeLightingEncoding(handle,nativeLightingPlan.encoding().ordinal());
        }
        if(appliedReconstructionDivisor!=lightingReconstructionDivisor){
            RtxptNative.nativeLightingReconstructionScale(handle,lightingReconstructionDivisor);
            appliedReconstructionDivisor=lightingReconstructionDivisor;
        }
        if(appliedReconstruction!=lightingReconstruction||!frame.temporalHistoryResets().isEmpty()){
            RtxptNative.nativeLightingReconstruction(handle,lightingReconstruction==NativeLightingReconstruction.DLSS_RR,!frame.temporalHistoryResets().isEmpty());
            appliedReconstruction=lightingReconstruction;
        }
        appliedLightingPlan=nativeLightingPlan;
        RtxptNative.nativeLightingClock(handle,frame.clock().timeNanos(),frame.clock().deltaNanos());
        boolean reset=lightingAttributeReset;
        if(reset)RtxptNative.nativeLightingClearAttributes(handle);
        var variants=new LinkedHashMap<String,RtxptScene.Item>();
        if(reset)for(var item:items)variants.putIfAbsent(RtxptScene.meshName(item),item);
        else {
            for(var item:pendingGeometryVariants)variants.putIfAbsent(RtxptScene.meshName(item),item);
            var meshIds=new HashSet<>(pendingLightingBaselines);meshIds.addAll(pendingLightingColors);
            for(long mesh:meshIds)for(long id:scene.instancesUsingMesh(mesh)){
                var item=scene.item(id);variants.putIfAbsent(RtxptScene.meshName(item),item);
            }
            if(!meshIds.isEmpty())for(int index=scene.instances.size();index<items.size();index++){
                var item=items.get(index);if(meshIds.contains(item.mesh()))variants.putIfAbsent(RtxptScene.meshName(item),item);
            }
        }
        var geometryNames=new HashSet<String>();for(var item:pendingGeometryVariants)geometryNames.add(RtxptScene.meshName(item));
        var batch=new ArrayList<RtxptAttributeBatch.Entry>();
        for(var item:variants.values()){
            String name=RtxptScene.meshName(item);
            boolean baseline=reset||geometryNames.contains(name)||pendingLightingBaselines.contains(item.mesh());
            boolean color=reset||geometryNames.contains(name)||pendingLightingColors.contains(item.mesh());
            var mesh=scene.meshes.get(item.mesh());
            batch.add(RtxptAttributeBatch.canonical(name+":0",mesh,lightingBaselines.get(item.mesh()),baseline,color));
        }
        RtxptAttributeBatch.submit(handle,revision,batch);
        pendingGeometryVariants=List.of();
        applicationData.flushPatches(lightingAttributeGeneration,(generation,offset,bytes)->RtxptNative.nativeLightingApplicationPatch(handle,generation,offset,bytes));
        var primitiveBatch=frame.primitiveBatch();
        for(long mesh:pendingLightingBaselines)pendingLightingInstances.addAll(scene.instancesUsingMesh(mesh));
        var primitiveRoleChanges=new ArrayList<Integer>();
        if(reset||primitiveBatch!=appliedLightingPrimitives||!pendingLightingBaselines.isEmpty())for(int i=0;i<primitiveBatch.size();i++){
            if(reset||i>=appliedLightingPrimitives.size()||pendingLightingBaselines.contains(primitiveBatch.primitives().get(i).meshAssetId())
                    ||!RtxptLightingRoles.same(primitiveBatch.primitives().get(i),appliedLightingPrimitives.primitives().get(i)))
                primitiveRoleChanges.add(i);
        }
        if(reset||appliedLightingAttributeGeneration!=lightingAttributeGeneration||!pendingLightingInstances.isEmpty()
                ||!primitiveRoleChanges.isEmpty()){
            var instanceIds=reset?scene.instances.keySet():pendingLightingInstances;
            var names=new ArrayList<String>();float[] packed=new float[Math.multiplyExact(instanceIds.size()+primitiveRoleChanges.size(),20)];int offset=0;
            for(long id:instanceIds){var instance=scene.instances.get(id);if(instance==null)continue;
                var value=lightingInstances.get(id);if(value==null)value=RtxptLightingRoles.defaults(id,instance.packedLight(),instance.surfaceVisibility(),
                    scene.meshes.get(instance.meshAssetId()),lightingBaselines.containsKey(instance.meshAssetId()));
                names.add("i"+id);RtxptLightingRoles.pack(packed,offset,value,instance.visibilityMask(),instance.castsShadow());offset+=20;}
            for(int i:primitiveRoleChanges){
                var primitive=primitiveBatch.primitives().get(i);
                names.add("p"+i);var value=RtxptLightingRoles.defaults(i,primitive.packedLight(),primitive.surfaceVisibility(),
                    scene.meshes.get(primitive.meshAssetId()),lightingBaselines.containsKey(primitive.meshAssetId()));
                RtxptLightingRoles.pack(packed,offset,value,primitive.visibilityMask(),primitive.castsShadow());
                RtxptLightingRoles.packHistory(packed,offset,primitive);offset+=20;
            }
            RtxptNative.nativeLightingAttributes(handle,lightingAttributeGeneration,names.toArray(String[]::new),offset==packed.length?packed:Arrays.copyOf(packed,offset),pendingBaselineLookup,applicationData.fullPublication());
            appliedLightingAttributeGeneration=lightingAttributeGeneration;
            applicationData.publicationApplied();
        }
        appliedLightingPrimitives=primitiveBatch;

        pendingLightingInstances.clear();pendingLightingBaselines.clear();pendingLightingColors.clear();pendingBaselineLookup=null;
        lightingAttributeReset=false;

    }

    @Override public Execution nativeLightingExecution(){ready();var n=handle==0?new long[8]:RtxptNative.nativeLightingTelemetry(handle);return new Execution(n[0]!=0,(int)n[1],(int)n[2],(int)n[3],n[4],n[5],n[6],n[7]);}
    @Override public Optional<LightingFrameLease> acquireLightingFrame(){ready();nativeLightingPlan();if(handle==0)return Optional.empty();var packet=RtxptNative.nativeLightingFrame(handle);return packet.length==0?Optional.empty():Optional.of(new RtxptLightingFrameLease(handle,packet));}
    @Override public void lightingReconstruction(NativeLightingReconstruction value){
        ready();Objects.requireNonNull(value);
        if(handle!=0&&!sessionRequiresStreamline&&value==NativeLightingReconstruction.DLSS_RR)
            throw new IllegalStateException("Select DLSS_RR before the first submit, or reopen the renderer to enable Streamline");
        lightingReconstruction=value;
    }
    @Override public void resetLightingReconstruction(){ready();nativeLightingPlan();if(handle!=0)RtxptNative.nativeLightingReconstruction(handle,lightingReconstruction==NativeLightingReconstruction.DLSS_RR,true);}
    @Override public void lightingReconstructionScale(int divisor){ready();if(divisor!=1&&divisor!=2&&divisor!=4)throw new IllegalArgumentException("Reconstruction divisor must be 1, 2 or 4");lightingReconstructionDivisor=divisor;}
    @Override public ReconstructionState lightingReconstruction(){ready();var n=handle==0?new long[7]:RtxptNative.nativeLightingReconstructionState(handle);return new ReconstructionState(NativeLightingReconstruction.values()[Math.toIntExact(n[0])],NativeLightingReconstruction.values()[Math.toIntExact(n[1])],Math.toIntExact(n[2]),Math.toIntExact(n[3]),n[4],n[5],n[6]!=0);}
    void captureNativeLighting(Path path,int signal){ready();if(handle==0)throw new IllegalStateException("Submit before capturing");RtxptNative.nativeLightingCapture(handle,path.toAbsolutePath().toString(),signal);}
    @Override public Capacity sparseLightingCapacity(){ready();return new Capacity(262144,32,96);}
    @Override public UpdateStatus sparseLightingStatus(){ready();var n=handle==0?new long[8]:RtxptNative.sparseLightingTelemetry(handle);return new UpdateStatus(scene.sparseSources.size(),n[0]==0?0:n[0]*2-1,n[1],n[2],n[3],n[4],n[5],n[6],n[7]);}
    @Override public void loadNativeScene(Path path){ready();if(options.quality().beautyTransport()!=PathTracingQualityOptions.BeautyTransport.NATIVE)throw new IllegalStateException("Analytic beauty requires API scene/light publications; native files use NATIVE transport");if(nativeLightingPlan!=null)throw new IllegalStateException("Retained lighting and native file scenes are separate inputs");nativeScene=Objects.requireNonNull(path).toAbsolutePath().normalize();if(handle!=0)RtxptNative.loadScene(handle,nativeScene.toString());}
    @Override public long windowHandle(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptNative.window(handle);}
    @Override public void setWindowTitle(String title){ready();Objects.requireNonNull(title);if(Objects.equals(windowTitle,title))return;windowTitle=title;if(handle!=0)RtxptNative.title(handle,title);}
    @Override public void setExposureOffset(float offset){
        ready();
        if(!Float.isFinite(offset)||offset < -12f||offset > 12f)throw new IllegalArgumentException("Exposure offset must be finite and in [-12,12]");
        if(Float.compare(exposureOffset,offset)==0)return;
        exposureOffset=offset;
        if(handle!=0)RtxptNative.exposure(handle,offset);
    }
    @Override public PresentationState presentationState(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        var n=RtxptNative.presentationState(handle);
        if(n.length!=10)throw new IllegalStateException("Invalid native presentation state");
        return new PresentationState(Math.toIntExact(n[0]),Math.toIntExact(n[1]),Math.toIntExact(n[2]),Math.toIntExact(n[3]),Math.toIntExact(n[4]),Math.toIntExact(n[5]),Math.toIntExact(n[6]),Math.toIntExact(n[7]),n[8]!=0,n[9]!=0);
    }
    @Override public ExposureState exposureState(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        var n=RtxptNative.exposureState(handle);
        if(n.length!=3)throw new IllegalStateException("Invalid native exposure state");
        return new ExposureState(n[0]!=0,n[1],n[2]);
    }
    @Override public WindowState windowState(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        var n=RtxptNative.windowState(handle);
        if(n.length!=12)throw new IllegalStateException("Invalid native window state");
        return new WindowState(Math.toIntExact(n[0]),Math.toIntExact(n[1]),Math.toIntExact(n[2]),Math.toIntExact(n[3]),Math.toIntExact(n[4]),Math.toIntExact(n[5]),Math.toIntExact(n[6]),Math.toIntExact(n[7]),Math.toIntExact(n[8]),Math.toIntExact(n[9]),n[10]!=0,n[11]!=0);
    }
    @Override public Denoiser denoiserState(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        int n=RtxptNative.denoiserState(handle);
        if(n<0||n>=Denoiser.values().length)throw new IllegalStateException("Invalid native denoiser state");
        return Denoiser.values()[n];
    }
    @Override public String frameStatistics(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        return RtxptNative.frameStatistics(handle);
    }
    @Override public RenderingState renderingState(){
        ready();if(handle==0)throw new IllegalStateException("Submit first");
        var n=RtxptNative.renderingState(handle);
        if(n.length!=17||n[1]<0||n[1]>=Reconstruction.values().length||n[5]<0||n[5]>=Denoiser.values().length)throw new IllegalStateException("Invalid native rendering state");
        return new RenderingState(n[0]!=0,Reconstruction.values()[Math.toIntExact(n[1])],Math.toIntExact(n[2]),Math.toIntExact(n[3]),
                n[4]!=0,Denoiser.values()[Math.toIntExact(n[5])],Math.toIntExact(n[6]),Math.toIntExact(n[7]),
                n[8]!=0,n[9]!=0,n[10]!=0,n[11]!=0,n[12]!=0,n[13]!=0,Math.toIntExact(n[14]),n[15]!=0,n[16]!=0);
    }
    @Override public void setOverlayText(String text){
        ready();Objects.requireNonNull(text);
        if(overlayText.equals(text))return;
        overlayText=text;if(handle!=0)RtxptNative.overlayText(handle,text);
    }
    @Override public void setOverlayChoices(List<String> choices,int selected){
        ready();var copy=List.copyOf(Objects.requireNonNull(choices));
        if(copy.size()>64||selected< -1||selected>=copy.size()||(!copy.isEmpty()&&selected<0))throw new IllegalArgumentException("Invalid overlay choices or selection");
        overlayChoices=copy;overlaySelected=selected;
        if(handle!=0)RtxptNative.overlayChoices(handle,copy.toArray(String[]::new),selected);
    }
    @Override public int overlaySelection(){ready();return handle==0?overlaySelected:RtxptNative.overlaySelection(handle);}
    @Override public void awaitIdle(){ready();if(handle!=0)RtxptNative.awaitIdle(handle);}
    @Override public void capture(Path png){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.capture(handle,png.toAbsolutePath().toString());}
    @Override public void close(){
        if(closed)return;ready();
        if(handle!=0&&nativeLightingPlan!=null&&!RtxptNative.nativeLightingConsumersRetired(handle))throw new IllegalStateException("Complete imported lighting consumers before closing");
        if(handle!=0)RtxptNative.close(handle);
        handle=0;closed=true;recorder.close();scene=new RtxptScene();residentScene=new RtxptScene();previous=List.of();memoryTextures.clear();memoryMaterialVariants.clear();memoryMeshVariants.clear();resourcePackets.close();
        lightingInstances.clear();lightingBaselines.clear();pendingLightingInstances.clear();pendingLightingBaselines.clear();pendingLightingColors.clear();pendingGeometryVariants=List.of();
        pendingBaselineLookup=null;lightingBaselineLookup=null;lightingHostResponse=null;applicationData.clear();
    }
}
