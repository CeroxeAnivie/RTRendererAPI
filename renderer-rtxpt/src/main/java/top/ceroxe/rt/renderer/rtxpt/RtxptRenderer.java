package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** One resident upstream renderer. Static resources are exported only on structural edits. */
final class RtxptRenderer implements Renderer,RtxptAccess,SceneLightingControl,SparseLightingControl,NativeLightingControl,HostSurfaceControl {
    private final Thread owner=Thread.currentThread();
    private final boolean externalPresentation;
    private final boolean gpuTimingsEnabled;
    private final int maxFramesInFlight;
    private final RendererResourceOptions resources;
    private RtxptScene scene=new RtxptScene();
    private RtxptScene residentScene;
    private Map<Long,Path> textureFiles=new RtxptPersistentMap<>();
    private final Map<Long,TextureAsset> memoryTextures=new HashMap<>();
    private final RtxptMemoryResources.PacketPool resourcePackets=new RtxptMemoryResources.PacketPool();
    private boolean fullReset=true;
    private RetainedUpdateMode lastUpdateMode=RetainedUpdateMode.UNCHANGED;
    private long resourceBatches,resourceMeshes,resourceMaterials,resourceTextures;
    private RtxptOptions options;
    private RendererFeatureProfile requestedProfile;
    private final RtxptFeatureController features;
    private long handle,revision,submitted=-1,completed=-1,frames,frameNs,exports,exportNs,batches,transformNs;
    private RtxptDiagnostics.Level diagnosticLevel=RtxptDiagnostics.Level.SUMMARY;
    private boolean closed,dirty=true,windowClosed,scenePublished,nativeContractPending;
    private int width,height;
    private Path directory,nativeScene,fullDirectory;
    private List<RtxptScene.Item> previous=List.of();
    private CameraState camera;
    private RtxptLighting lighting;
    private final Set<Long> pendingLightIds=new HashSet<>();
    private final Set<Long> pendingSparseIds=new HashSet<>();
    private NativeLightingPlan nativeLightingPlan;
    private HostSurfacePlan hostSurfacePlan;
    private HostSurfaceAugmentation hostSurfaceAugmentation;
    @Override public HostSurfaceAugmentation hostSurfaceAugmentation(){ready();hostSurfacePlan();if(hostSurfaceAugmentation==null)throw new IllegalStateException("Select host-lit augmentation first");return hostSurfaceAugmentation;}
    @Override public void hostSurfaceAugmentation(HostSurfaceAugmentation value){
        ready();Objects.requireNonNull(value).validateFor(hostSurfacePlan());
        if(value.lighting().encoding()!=NativeLightingPlan.Encoding.HDR_FLOAT16)throw new IllegalArgumentException("Host augmentation publishes its declared floating working-domain signals");
        if((hostSurfacePlan.outputSignals()&HostSurfacePlan.COMBINED)!=0&&(value.lighting().width()!=hostSurfacePlan.width()||value.lighting().height()!=hostSurfacePlan.height()))
            throw new IllegalArgumentException("Combined host color requires native input extent; use independent modulation/GI for lower-resolution lighting");
        hostSurfaceAugmentation=value;
    }
    private boolean preparingHostSurface,hostSurfaceWorkEnabled,diagnosticPrepared;
    @Override public void hostSurfaceWorkStatisticsEnabled(boolean enabled){ready();hostSurfacePlan();hostSurfaceWorkEnabled=enabled;if(handle!=0)RtxptNative.hostSurfaceWorkEnabled(handle,enabled);}
    @Override public HostSurfaceControl.WorkStatistics hostSurfaceWorkStatistics(){ready();hostSurfacePlan();var n=handle==0?new long[9]:RtxptNative.hostSurfaceWork(handle);return new HostSurfaceControl.WorkStatistics(n[0]!=0,n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8]);}
    private long nextHostViewIdentity;
    private CameraState lastHostCamera;
    private record HostRequest(RenderFrameRequest frame,long revision,long resourceGeneration,long viewIdentity) { }
    private final Map<Long,HostRequest> hostRequests=new HashMap<>();
    private final EnumSet<HistoryCause> preparedHostHistory=EnumSet.noneOf(HistoryCause.class);
    private RetainedUpdateMode preparedHostUpdateMode=RetainedUpdateMode.UNCHANGED;
    private final EnumSet<HistoryInvalidationReason> preparedHostReasons=EnumSet.noneOf(HistoryInvalidationReason.class);
    @Override public Optional<HostSurfaceFrameLease> acquireHostSurfaceFrame(){ready();hostSurfacePlan();if(handle==0)return Optional.empty();var packet=RtxptNative.hostSurfaceFrame(handle);return packet.length==0?Optional.empty():Optional.of(new RtxptHostSurfaceFrameLease(handle,packet));}
    @Override public HostSurfacePlan hostSurfacePlan(){ready();if(hostSurfacePlan==null)throw new IllegalStateException("Select host primary-surface mode first");return hostSurfacePlan;}
    @Override public void hostSurfacePlan(HostSurfacePlan plan){
        ready();Objects.requireNonNull(plan);
        if(!externalPresentation||nativeLightingPlan!=null||nativeScene!=null||options.restirDi()||options.restirGi()||options.sharc())
            throw new IllegalArgumentException("Host primary surfaces require an offscreen retained scene without ReSTIR or radiance caches");
        var nextAugmentation=hostSurfaceAugmentation;
        if(plan.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION){
            if(options.reconstruction()!=Reconstruction.NONE||options.standaloneDenoiser())throw new IllegalArgumentException("Deterministic host gain/GI has no full-image RR/SR inputs; choose unreconstructed finite signals or split transport with RR");
            var parameters=hostSurfaceAugmentation==null?HostSurfaceAugmentation.defaults(plan.width(),plan.height(),HostSurfaceAugmentation.ColorDomain.SCENE_LINEAR):hostSurfaceAugmentation;
            parameters.validateFor(plan);nextAugmentation=parameters;
        }
        if(!supportsTransparent(options))throw new IllegalArgumentException("Host surface HDR output requires bloom, ReSTIR, radiance caches and frame generation disabled");
        if(handle!=0)configureHostSurface(plan);hostSurfaceAugmentation=nextAugmentation;hostSurfacePlan=plan;transparentBackground=true;backgroundChanged=true;
    }
    private void configureHostSurface(HostSurfacePlan plan){RtxptNative.hostSurfacePlan(handle,plan.width(),plan.height(),plan.composition().ordinal(),plan.preservedHostComponents(),plan.motionIncludesJitter(),plan.maximumResidentBytes());RtxptNative.hostSurfaceOutputs(handle,plan.outputSignals());}
    @Override public Optional<HostSurfaceInputLease> acquireHostSurfaceInput(RenderFrameRequest frame,long resourceGeneration){
        ready();hostSurfacePlan();Objects.requireNonNull(frame);
        if(resourceGeneration<0||hostRequests.containsKey(frame.sequence()))throw new IllegalArgumentException("Invalid resource generation or duplicate input sequence");
        RtxptHostSurfaceView.pack(frame.camera(),frame.camera(),frame.width(),frame.height());
        preparingHostSurface=true;try{submit(frame);}finally{preparingHostSurface=false;}
        configureHostSurface(hostSurfacePlan);
        long viewIdentity=Math.incrementExact(nextHostViewIdentity);nextHostViewIdentity=viewIdentity;
        var packet=RtxptNative.hostSurfaceAcquire(handle,frame.sequence(),revision,resourceGeneration,viewIdentity,frame.clock().timeNanos(),frame.clock().deltaNanos());
        if(packet.length==0)return Optional.empty();
        hostRequests.put(frame.sequence(),new HostRequest(frame,revision,resourceGeneration,viewIdentity));
        return Optional.of(new RtxptHostSurfaceInputLease(handle,packet,()->hostRequests.remove(frame.sequence())));
    }
    @Override public void discardHostSurfaceInput(long generation,long sequence){ready();hostSurfacePlan();if(handle==0)throw new IllegalStateException("No host input allocation");RtxptNative.hostSurfaceDiscard(handle,generation,sequence);hostRequests.remove(sequence);}
    @Override public HostSurfaceControl.Status hostSurfaceStatus(){ready();hostSurfacePlan();var n=handle==0?new long[9]:RtxptNative.hostSurfaceStatus(handle);return new HostSurfaceControl.Status(n[0],(int)n[1],(int)n[2],(int)n[3],(int)n[4],n[5],n[6],n[7],n[8]);}
    private void prepareHostSurface(RenderFrameRequest frame,List<RtxptScene.Item> items,boolean geometryChanged){
        if(hostSurfacePlan==null)return;
        if(geometryChanged){
            var uploaded=new HashSet<String>();
            for(var item:items){String name=RtxptScene.meshName(item);if(!uploaded.add(name))continue;
                var mesh=scene.meshes.get(item.mesh());var materialIds=mesh.triangleMaterialIds();
                var groups=new LinkedHashMap<Long,Integer>();var counts=new HashMap<Long,Integer>();int[] mapping=new int[mesh.triangleCount()*2];
                for(int triangle=0;triangle<mesh.triangleCount();triangle++){long material=materialIds.get(triangle);
                    int geometry=groups.computeIfAbsent(material,ignored->groups.size());int primitive=counts.getOrDefault(material,0);counts.put(material,primitive+1);
                    mapping[triangle*2]=geometry;mapping[triangle*2+1]=primitive;}
                RtxptNative.hostSurfaceTriangles(handle,name,mapping);
            }
            RtxptNative.hostSurfaceMappingDirty(handle);
        }
        RtxptNative.hostSurfaceWorkEnabled(handle,hostSurfaceWorkEnabled);
        RtxptNative.hostSurfaceView(handle,RtxptHostSurfaceView.pack(frame.camera(),lastHostCamera==null?frame.camera():lastHostCamera,frame.width(),frame.height()));
        if(hostSurfacePlan.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION){
            var augmentation=hostSurfaceAugmentation();var plan=augmentation.lighting();
            RtxptNative.hostSurfaceAugmentation(handle,RtxptNativeLightingParameters.pack(plan,frame),plan.width(),plan.height(),hostSurfacePlan.outputSignals(),augmentation.colorDomain().ordinal());
        }
    }
    private long lightingAttributeGeneration;
    private long appliedLightingAttributeGeneration=-1;
    private boolean lightingAttributeReset=true;
    private final Map<Long,NativeLightingPublication.Instance> lightingInstances=new HashMap<>();
    private final Map<Long,float[]> lightingBaselines=new HashMap<>();
    private final Set<Long> pendingLightingInstances=new HashSet<>(),pendingLightingBaselines=new HashSet<>();
    private final Set<Long> pendingLightingColors=new HashSet<>();
    private record AttributeGroups(MeshAsset mesh,List<RtxptAttributeBatch.Group> groups) { }
    private final Map<Long,AttributeGroups> attributeGroups=new HashMap<>();
    private List<RtxptScene.Item> pendingGeometryVariants=List.of();
    private long baselineCornersConverted,colorCornersConverted,attributeMeshesConverted;
    private long preparationSubmissions,preparationItemReads,preparationDependencies,preparationMapNodes,preparationModels,
        preparationTransforms,preparationAttributeInstances,preparationAllocatedBytes;
    private static final com.sun.management.ThreadMXBean allocationBean=
        java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean?bean:null;
    private long allocatedBytes(){return diagnosticLevel==RtxptDiagnostics.Level.OFF||allocationBean==null
        ||!allocationBean.isThreadAllocatedMemorySupported()||!allocationBean.isThreadAllocatedMemoryEnabled()?-1:allocationBean.getThreadAllocatedBytes(owner.threadId());}
    @Override public PreparationStatistics preparationStatistics(){ready();return new PreparationStatistics(diagnosticLevel!=RtxptDiagnostics.Level.OFF,
        preparationSubmissions,preparationItemReads,preparationDependencies,preparationMapNodes,preparationModels,
        preparationTransforms,preparationAttributeInstances,allocationBean==null||!allocationBean.isThreadAllocatedMemorySupported()
            ||!allocationBean.isThreadAllocatedMemoryEnabled()?-1:preparationAllocatedBytes);}
    private float[] pendingBaselineLookup;
    private byte[] pendingApplicationData;
    private final ArrayDeque<NativeLightingApplicationPatch> pendingApplicationPatches=new ArrayDeque<>();
    private int lightingApplicationBytes,lightingExtensionSignals=15,lightingExtensionMinimumBytes;
    private Path lightingExtensionManifest;
    private String lightingExtensionHash;
    private boolean lightingExtensionPending;
    private record LightingExtensionState(Path manifest,String hash,int signals,int minimumBytes) { }
    private LightingExtensionState selectedLightingExtension=new LightingExtensionState(null,null,15,0);
    private LightingExtensionState activeLightingExtension=selectedLightingExtension;
    private boolean lightingWorkStatisticsEnabled;
    private boolean lightingDiagnosticDecomposition;
    private NativeLightingReconstruction lightingReconstruction=NativeLightingReconstruction.NONE;
    @Override public void lightingDiagnosticDecompositionEnabled(boolean enabled){ready();nativeLightingPlan();if(enabled&&(lightingExtensionManifest!=null||lightingReconstruction!=NativeLightingReconstruction.NONE))throw new IllegalStateException("Diagnostic decomposition requires the raw default evaluator");if(handle!=0)RtxptNative.nativeLightingDecomposition(handle,enabled);lightingDiagnosticDecomposition=enabled;}
    @Override public void lightingReconstruction(NativeLightingReconstruction value){
        ready();Objects.requireNonNull(value);var plan=nativeLightingPlan();
        if(value!=NativeLightingReconstruction.NONE&&value!=NativeLightingReconstruction.DLSS_RR)throw new IllegalArgumentException("Native GI supports NONE or DLSS_RR");
        if(value!=NativeLightingReconstruction.NONE&&(!plan.signals().contains(NativeLightingPlan.Signal.INDIRECT_DIFFUSE)||lightingDiagnosticDecomposition||lightingExtensionManifest!=null))
            throw new IllegalStateException("GI RR requires INDIRECT_DIFFUSE and the default fused evaluator");
        if(handle!=0)RtxptNative.nativeLightingReconstruction(handle,value==NativeLightingReconstruction.DLSS_RR,false);lightingReconstruction=value;
    }
    @Override public void resetLightingReconstruction(){ready();nativeLightingPlan();if(handle!=0)RtxptNative.nativeLightingReconstruction(handle,lightingReconstruction==NativeLightingReconstruction.DLSS_RR,true);}
    @Override public NativeLightingControl.ReconstructionState lightingReconstruction(){
        ready();nativeLightingPlan();var n=handle==0?new long[7]:RtxptNative.nativeLightingReconstructionState(handle);
        return new NativeLightingControl.ReconstructionState(lightingReconstruction,n[0]!=0?NativeLightingReconstruction.DLSS_RR:NativeLightingReconstruction.NONE,(int)n[1],(int)n[2],n[3],n[4],n[5]!=0);
    }
    private boolean hostAugmentationActive(){return hostSurfacePlan!=null&&hostSurfacePlan.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION;}
    private NativeLightingPlan lightingPublicationPlan(){
        ready();if(nativeLightingPlan!=null)return nativeLightingPlan;
        if(hostAugmentationActive())return hostSurfaceAugmentation().lighting();
        throw new IllegalStateException("Select independent native lighting or host-lit augmentation first");
    }
    @Override public void lightingWorkStatisticsEnabled(boolean enabled){ready();lightingPublicationPlan();lightingWorkStatisticsEnabled=enabled;if(handle!=0)RtxptNative.nativeLightingWorkEnabled(handle,enabled);}
    @Override public NativeLightingControl.WorkStatistics lightingWorkStatistics(){ready();lightingPublicationPlan();var n=handle==0?new long[9]:RtxptNative.nativeLightingWork(handle);return new NativeLightingControl.WorkStatistics(n[0]!=0,n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8]);}
    @Override public NativeLightingControl.StatisticsCollection lightingStatisticsCollection(){ready();lightingPublicationPlan();var n=handle==0?new long[11]:RtxptNative.nativeLightingUpdateStatistics(handle);return new NativeLightingControl.StatisticsCollection(lightingWorkStatisticsEnabled,n[6]!=0,n[7],n[8],n[9],n[10]);}
    @Override public NativeLightingControl.AttributeUpdateStatistics lightingAttributeUpdateStatistics(){ready();lightingPublicationPlan();var n=handle==0?new long[11]:RtxptNative.nativeLightingUpdateStatistics(handle);return new NativeLightingControl.AttributeUpdateStatistics(baselineCornersConverted,colorCornersConverted,attributeMeshesConverted,n[0],n[1],n[2],n[3],n[4]);}
    @Override public void activateLightingExtension(Path manifest){
        ready();
        if(lightingDiagnosticDecomposition)throw new IllegalStateException("Disable diagnostic decomposition before activating an extension evaluator");
        if(lightingReconstruction!=NativeLightingReconstruction.NONE)throw new IllegalStateException("Extension output semantics must be raw; disable GI RR before activation");
        if(hostAugmentationActive())throw new UnsupportedOperationException("Host-lit augmentation does not execute offline native-lighting extensions");
        nativeLightingPlan();var path=Objects.requireNonNull(manifest).toAbsolutePath().normalize();
        var validated=RtxptNative.nativeLightingExtensionValidate(path.toString(),nativeLightingPlan.signalMask(),lightingApplicationBytes);
        var next=new LightingExtensionState(path,validated[0],Integer.parseInt(validated[1]),Integer.parseInt(validated[2]));
        // A live device can reject the PSO here without invalidating the renderer
        // or replacing its accepted Java capability/data contract.
        if(handle!=0)RtxptNative.nativeLightingActivateValidatedExtension(handle,path.toString(),next.hash(),nativeLightingPlan.signalMask(),lightingApplicationBytes);
        selectLightingExtension(next);lightingExtensionPending=handle==0;if(handle!=0)activeLightingExtension=next;
    }
    private void selectLightingExtension(LightingExtensionState value){
        selectedLightingExtension=value;lightingExtensionManifest=value.manifest();lightingExtensionHash=value.hash();
        lightingExtensionSignals=value.signals();lightingExtensionMinimumBytes=value.minimumBytes();
    }
    @Override public void resetLightingExtension(){
        ready();
        if(hostAugmentationActive())throw new UnsupportedOperationException("Host-lit augmentation does not execute offline native-lighting extensions");
        nativeLightingPlan();var next=new LightingExtensionState(null,null,15,0);
        if(handle!=0)RtxptNative.nativeLightingExtension(handle,null,null);
        selectLightingExtension(next);lightingExtensionPending=handle==0;if(handle!=0)activeLightingExtension=next;
    }
    @Override public void publishLightingAttributes(NativeLightingPublication publication){
        ready();Objects.requireNonNull(publication);lightingPublicationPlan();
        if(publication.generation()<=lightingAttributeGeneration)throw new IllegalArgumentException("Lighting attribute generations must increase");
        var instanceIds=new HashSet<Long>();var meshIds=new HashSet<Long>();
        for(var value:publication.instances())if(!instanceIds.add(value.id())||!scene.instances.containsKey(value.id()))throw new IllegalArgumentException("Unknown or duplicate lighting instance: "+value.id());
        for(var value:publication.baselines())if(!meshIds.add(value.meshId())||!scene.meshes.containsKey(value.meshId())||value.coordinates().length!=scene.meshes.get(value.meshId()).vertexCount()*2)
            throw new IllegalArgumentException("Unknown, duplicate or mismatched baseline mesh: "+value.meshId());
        if(publication.applicationData()!=null&&publication.applicationData().remaining()<lightingExtensionMinimumBytes)throw new IllegalArgumentException("Application publication is smaller than the active extension contract");
        for(var value:publication.instances())lightingInstances.put(value.id(),value);
        for(var value:publication.baselines())lightingBaselines.put(value.meshId(),value.coordinates());
        pendingLightingInstances.addAll(instanceIds);pendingLightingBaselines.addAll(meshIds);
        if(publication.baselineLookup()!=null)pendingBaselineLookup=publication.baselineLookup();
        if(publication.applicationData()!=null){var bytes=publication.applicationData();pendingApplicationData=new byte[bytes.remaining()];bytes.get(pendingApplicationData);lightingApplicationBytes=pendingApplicationData.length;pendingApplicationPatches.clear();}
        lightingAttributeGeneration=publication.generation();
    }
    @Override public void patchLightingApplicationData(NativeLightingApplicationPatch patch){
        ready();lightingPublicationPlan();Objects.requireNonNull(patch);
        var source=patch.data();int bytes=source.remaining();
        if(patch.generation()<=lightingAttributeGeneration)throw new IllegalArgumentException("Application patch generations must increase with attribute publications");
        if(lightingApplicationBytes<lightingExtensionMinimumBytes||patch.offset()>lightingApplicationBytes-bytes)
            throw new IllegalArgumentException("Application patch must remain within the existing publication and extension capacity");
        if(pendingApplicationData!=null){
            // No GPU copy of this full publication exists yet. Fold the delta into
            // that necessary first upload instead of uploading the same bytes twice.
            var values=new byte[bytes];source.get(values);System.arraycopy(values,0,pendingApplicationData,patch.offset(),bytes);
        }else pendingApplicationPatches.addLast(patch);
        lightingAttributeGeneration=patch.generation();
    }
    long[] nativeLightingApplicationTransfers(){ready();return handle==0?new long[4]:RtxptNative.nativeLightingApplicationTelemetry(handle);}
    double[] nativeLightingCompactProbe(){ready();if(handle==0)throw new IllegalStateException("Initialize the GPU before the encoder diagnostic");return RtxptNative.nativeLightingCompactProbe(handle);}
    @Override public NativeLightingPlan nativeLightingPlan(){ready();if(nativeLightingPlan==null)throw new IllegalStateException("Select the independent native lighting mode first");return nativeLightingPlan;}
    @Override public void nativeLightingPlan(NativeLightingPlan value){
        ready();Objects.requireNonNull(value);
        if((value.signalMask()&~lightingExtensionSignals)!=0)throw new IllegalArgumentException("Requested signals are not supported by the selected extension");
        if(lightingReconstruction!=NativeLightingReconstruction.NONE&&!value.signals().contains(NativeLightingPlan.Signal.INDIRECT_DIFFUSE))throw new IllegalArgumentException("Disable GI RR before removing INDIRECT_DIFFUSE");
        if(!externalPresentation||!sparseLightingSupported()||options.reconstruction()!=Reconstruction.NONE
                ||options.standaloneDenoiser()||options.frameGeneration().preference().requested())
            throw new IllegalArgumentException("The independent deterministic lighting plan requires offscreen SCENE_ANALYTIC without full-image reconstruction, ReSTIR or a radiance cache");
        if(hostSurfacePlan!=null)throw new IllegalStateException("Host primary surfaces and independent native primary tracing are separate modes");
        nativeLightingPlan=value;
    }
    long[] nativeLightingTelemetry(){ready();return handle==0?new long[8]:RtxptNative.nativeLightingTelemetry(handle);}
    @Override public NativeLightingControl.Execution nativeLightingExecution(){var n=nativeLightingTelemetry();return new NativeLightingControl.Execution(n[0]!=0,(int)n[1],(int)n[2],(int)n[3],n[4],n[5],n[6],n[7]);}
    @Override public Optional<LightingFrameLease> acquireLightingFrame(){ready();if(nativeLightingPlan==null)throw new IllegalStateException("Select native lighting first");
        if(handle==0)return Optional.empty();var packet=RtxptNative.nativeLightingFrame(handle);return packet.length==0?Optional.empty():Optional.of(new RtxptLightingFrameLease(handle,packet));}
    void captureNativeLighting(Path path,int signal){ready();if(handle==0||nativeLightingPlan==null)throw new IllegalStateException("Submit a native-lighting frame first");RtxptNative.nativeLightingCapture(handle,path.toAbsolutePath().toString(),signal);}
    private void prepareNativeLighting(RenderFrameRequest frame,List<RtxptScene.Item> items,boolean geometryChanged){
        if(nativeLightingPlan==null&&!hostAugmentationActive())return;
        if(nativeLightingPlan!=null){
            RtxptNative.nativeLighting(handle,RtxptNativeLightingParameters.pack(nativeLightingPlan,frame));
            RtxptNative.nativeLightingEncoding(handle,nativeLightingPlan.encoding().ordinal());
            RtxptNative.nativeLightingReconstruction(handle,lightingReconstruction==NativeLightingReconstruction.DLSS_RR,!frame.temporalHistoryResets().isEmpty());
        }
        RtxptNative.nativeLightingClock(handle,frame.clock().timeNanos(),frame.clock().deltaNanos());
        RtxptNative.nativeLightingWorkEnabled(handle,lightingWorkStatisticsEnabled);
        RtxptNative.nativeLightingDecomposition(handle,lightingDiagnosticDecomposition);
        RtxptNative.diagnosticAttributesBegin(handle);try{
        boolean reset=geometryChanged||lightingAttributeReset;
        if(reset)RtxptNative.nativeLightingClearAttributes(handle);
        var variants=new LinkedHashMap<String,RtxptScene.Item>();
        if(reset)for(var item:items)variants.putIfAbsent(RtxptScene.meshName(item),item);
        else {
            for(var item:pendingGeometryVariants)variants.putIfAbsent(RtxptScene.meshName(item),item);
            var meshIds=new HashSet<>(pendingLightingBaselines);meshIds.addAll(pendingLightingColors);
            for(long mesh:meshIds)for(long id:scene.instancesUsingMesh(mesh)){
                var item=scene.item(id);variants.putIfAbsent(RtxptScene.meshName(item),item);
                if(diagnosticLevel!=RtxptDiagnostics.Level.OFF)preparationAttributeInstances++;
            }
            if(!meshIds.isEmpty())for(int index=scene.instances.size();index<items.size();index++){
                var item=items.get(index);if(meshIds.contains(item.mesh()))variants.putIfAbsent(RtxptScene.meshName(item),item);
                if(diagnosticLevel!=RtxptDiagnostics.Level.OFF)preparationItemReads++;
            }
        }
        var geometryNames=new HashSet<String>();for(var item:pendingGeometryVariants)geometryNames.add(RtxptScene.meshName(item));
        var batch=new ArrayList<RtxptAttributeBatch.Entry>();
        for(var item:variants.values()){
            String name=RtxptScene.meshName(item);
            boolean baseline=reset||geometryNames.contains(name)||pendingLightingBaselines.contains(item.mesh());
            boolean color=reset||geometryNames.contains(name)||pendingLightingColors.contains(item.mesh());
            var mesh=scene.meshes.get(item.mesh());var cached=attributeGroups.get(item.mesh());
            if(cached==null||!cached.mesh().triangleMaterialIds().equals(mesh.triangleMaterialIds())){
                cached=new AttributeGroups(mesh,scene.attributeGroups(mesh));attributeGroups.put(item.mesh(),cached);
            }
            int geometry=0;for(var group:cached.groups()){
                var entry=RtxptAttributeBatch.convert(name+":"+geometry++,mesh,group,lightingBaselines.get(item.mesh()),baseline,color);batch.add(entry);
                if(baseline)baselineCornersConverted+=group.corners().length;if(color)colorCornersConverted+=group.corners().length;
            }
            attributeMeshesConverted++;
        }
        RtxptAttributeBatch.submit(handle,revision,batch);
        pendingGeometryVariants=List.of();
        while(!pendingApplicationPatches.isEmpty()){
            var patch=pendingApplicationPatches.getFirst();var data=patch.data();var bytes=new byte[data.remaining()];data.get(bytes);
            RtxptNative.nativeLightingApplicationPatch(handle,patch.generation(),patch.offset(),bytes);pendingApplicationPatches.removeFirst();
        }
        if(reset||appliedLightingAttributeGeneration!=lightingAttributeGeneration){
            var instanceIds=reset?lightingInstances.keySet():pendingLightingInstances;
            var names=new ArrayList<String>();float[] packed=new float[Math.multiplyExact(instanceIds.size(),8)];int offset=0;
            for(long id:instanceIds){if(!scene.instances.containsKey(id))continue;var value=lightingInstances.get(id);names.add("i"+id);
                packed[offset++]=value.roles();packed[offset++]=value.visibility();packed[offset++]=value.fog();packed[offset++]=0;
                packed[offset++]=value.localScale();packed[offset++]=value.skyScale();packed[offset++]=value.localBias();packed[offset++]=value.skyBias();}
            RtxptNative.nativeLightingAttributes(handle,lightingAttributeGeneration,names.toArray(String[]::new),offset==packed.length?packed:Arrays.copyOf(packed,offset),pendingBaselineLookup,pendingApplicationData);
            appliedLightingAttributeGeneration=lightingAttributeGeneration;
        }

        if(lightingExtensionPending&&nativeLightingPlan!=null){
            try{
                RtxptNative.nativeLightingExtension(handle,lightingExtensionManifest==null?null:lightingExtensionManifest.toString(),lightingExtensionHash);
                activeLightingExtension=selectedLightingExtension;lightingExtensionPending=false;
            }catch(RuntimeException|Error failure){selectLightingExtension(activeLightingExtension);lightingExtensionPending=false;throw failure;}
        }
        pendingLightingInstances.clear();pendingLightingBaselines.clear();pendingLightingColors.clear();pendingBaselineLookup=null;pendingApplicationData=null;
        lightingAttributeReset=false;
        }finally{RtxptNative.diagnosticAttributesEnd();}
    }
    private boolean sparseLightingSupported(){return nativeScene==null&&options.qualityInts()[0]==1&&!options.restirDi()&&!options.restirGi()&&!options.sharc();}
    @Override public SparseLightingControl.Capacity sparseLightingCapacity(){ready();if(!sparseLightingSupported())throw new IllegalStateException("Sparse sources require retained SCENE_ANALYTIC without ReSTIR or radiance cache");return new SparseLightingControl.Capacity(262144,32,96);}
    @Override public SparseLightingControl.UpdateStatus sparseLightingStatus(){ready();long[] n=handle==0?new long[8]:RtxptNative.sparseLightingTelemetry(handle);return new SparseLightingControl.UpdateStatus(n[0],n[0]==0?0:n[0]*2-1,n[1],n[2],n[3],n[4],n[5],n[6],n[7]);}
    @Override public SceneUpdateResult apply(SceneTransaction tx,List<SparseLightSource> upserts,java.nio.LongBuffer removals){
        ready();Objects.requireNonNull(upserts);Objects.requireNonNull(removals);sparseLightingCapacity();
        var prepared=prepareScene(tx,true);var next=prepared.value().sparseSources;var touched=new HashSet<Long>();
        var removed=removals.asReadOnlyBuffer();
        if(tx.reset()&&removed.hasRemaining())throw new IllegalArgumentException("A reset cannot remove sparse sources");
        while(removed.hasRemaining()){long id=removed.get();if(id<0||!touched.add(id)||next.remove(id)==null)throw new IllegalArgumentException("Unknown or duplicate sparse source removal: "+id);}
        for(var source:upserts){Objects.requireNonNull(source);if(!touched.add(source.id()))throw new IllegalArgumentException("Duplicate sparse source ID: "+source.id());next.put(source.id(),source);}
        if(next.size()>262144)throw new IllegalArgumentException("Sparse source capacity exceeded: "+next.size());
        var result=commitScene(prepared);pendingSparseIds.addAll(touched);return result;
    }
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
        var next=RtxptOptions.resolve(profile);
        if(hostSurfacePlan!=null&&(next.restirDi()||next.restirGi()||next.sharc()))throw new IllegalArgumentException("Host primary surfaces do not execute ReSTIR or radiance caches");
        if(hostAugmentationActive()&&(next.reconstruction()!=Reconstruction.NONE||next.standaloneDenoiser()))
            throw new IllegalArgumentException("Host-lit augmentation does not execute full-image RR/SR or standalone denoising");
        if(nativeLightingPlan!=null&&(next.reconstruction()!=Reconstruction.NONE||next.standaloneDenoiser()||next.restirDi()||next.restirGi()||next.sharc()))
            throw new IllegalArgumentException("Independent deterministic lighting does not execute full-image reconstruction or stochastic caches");
        if(!scene.sparseSources.isEmpty()&&(next.qualityInts()[0]!=1||next.restirDi()||next.restirGi()||next.sharc()))
            throw new IllegalArgumentException("Resident sparse sources require SCENE_ANALYTIC without ReSTIR or radiance cache");
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
    @Override public Renderer.Status status(){return closed?Renderer.Status.CLOSED:Renderer.Status.READY;}
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
        next.validateAssetBudget(resources);
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
        for(var mesh:tx.upserts().meshes()){
            var old=scene.meshes.get(mesh.id());
            if(old==null||!old.lightmapCoordinates().equals(mesh.lightmapCoordinates()))pendingLightingBaselines.add(mesh.id());
            if(old==null||!old.vertexColorsRgba8().equals(mesh.vertexColorsRgba8()))pendingLightingColors.add(mesh.id());
        }
        if(tx.reset()){attributeGroups.clear();pendingSparseIds.clear();lightingInstances.clear();lightingBaselines.clear();pendingLightingInstances.clear();pendingLightingBaselines.clear();lightingAttributeReset=true;}
        var removedInstances=tx.removals().instanceIds();while(removedInstances.hasRemaining()){long id=removedInstances.get();lightingInstances.remove(id);pendingLightingInstances.remove(id);}
        var removedMeshes=tx.removals().meshIds();while(removedMeshes.hasRemaining()){long id=removedMeshes.get();lightingBaselines.remove(id);pendingLightingBaselines.remove(id);attributeGroups.remove(id);}
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
    private long diagnosticClock(){return diagnosticLevel==RtxptDiagnostics.Level.OFF?0:System.nanoTime();}
    private long diagnosticElapsed(long start){return start==0?0:System.nanoTime()-start;}
    @Override public RtxptDiagnostics.ProtocolMonitor protocolMonitor(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptDiagnostics.protocolMonitor(RtxptNative.protocolMonitorToken(handle));}
    @Override public ResourceMaintenance resourceMaintenance(){ready();long[] n=handle==0?new long[7]:RtxptNative.resourceMaintenance(handle);return new ResourceMaintenance(n[0],n[1],n[2],n[3],n[4],n[5],n[6]);}
    @Override public ResourcePressure resourcePressure(){ready();long[] n=handle==0?new long[11]:RtxptNative.resourcePressure(handle);
        return new ResourcePressure(n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],n[10]);}
    @Override public FrameSubmissionResult submit(RenderFrameRequest f){return submitFrame(f,false,diagnosticClock());}
    private FrameSubmissionResult submitFrame(RenderFrameRequest f,boolean admitted,long callBegin){
        ready();Objects.requireNonNull(f);
        if(hostSurfacePlan!=null&&!preparingHostSurface&&handle!=0&&!RtxptNative.hostSurfaceCanPublish(handle))throw new IllegalStateException("Host HDR output ring is full; return GPU output leases before submitting");
        if(nativeLightingPlan!=null&&handle!=0&&!RtxptNative.nativeLightingCanPublish(handle))throw new IllegalStateException("Lighting output ring is full; return GPU leases or use trySubmit");
        if(hostSurfacePlan!=null&&!preparingHostSurface){var host=hostRequests.get(f.sequence());
            if(host==null||host.frame()!=f||host.revision()!=revision)throw new IllegalArgumentException("Submit the exact immutable request used to acquire a published input, at the same resident scene revision");}
        long allocationStart=allocatedBytes();
        long submitBegin=preparingHostSurface?0:callBegin;boolean submitTracked=!preparingHostSurface&&handle!=0,submitAccepted=false;
        if(submitTracked&&!admitted)RtxptNative.diagnosticSubmitBegin(handle,f.sequence(),revision,diagnosticElapsed(callBegin));
        try {
        if(!admitted&&handle!=0&&!RtxptNative.resourcesCanUpdate(handle))throw new IllegalStateException("Resource generation ring is full; retry with trySubmit after GPU retirement");
        long validationBegin=submitBegin;if(f.width()>16384||f.height()>16384)throw new IllegalArgumentException("RTXPT output dimensions exceed D3D12 limit 16384");if(f.sequence()<=submitted)throw new IllegalArgumentException("Frame sequence must increase");
        var historyBefore=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);
        var historyCauses=EnumSet.copyOf(preparedHostHistory);
        if(handle==0)historyCauses.add(HistoryCause.FIRST_FRAME);
        if(!Objects.equals(camera,f.camera()))historyCauses.add(HistoryCause.CAMERA);
        boolean extentChanged=width!=f.width()||height!=f.height();
        if(extentChanged)historyCauses.add(HistoryCause.OUTPUT_EXTENT);
        if(backgroundChanged)historyCauses.add(HistoryCause.BACKGROUND);
        if(!f.temporalHistoryResets().isEmpty())historyCauses.add(HistoryCause.EXPLICIT);
        RtxptInputContract.validateFrame(f,nativeLightingPlan!=null||hostSurfacePlan!=null);
        if(nativeLightingPlan!=null)RtxptNativeLightingParameters.pack(nativeLightingPlan,f);
        else if(hostAugmentationActive())RtxptNativeLightingParameters.pack(hostSurfaceAugmentation().lighting(),f);
        if(nativeContractPending&&nativeScene==null){scene.validate();nativeContractPending=false;}
        if(f.minimumSceneRevision()>revision)throw new IllegalArgumentException("Scene revision is not resident");
        var items=nativeScene==null?scene.items(f):List.<RtxptScene.Item>of();
        var nextLighting=nativeScene==null?RtxptLighting.of(f.environment(),List.of()):null;
        // Validate the complete batch before scene export or any native mutation.
        boolean sparseLights=lighting!=null&&!fullReset;
        var changedLights=new ArrayList<SceneLight>();
        var removedLights=new ArrayList<Long>();
        if(sparseLights)for(long id:pendingLightIds){var next=scene.lights.get(id);var old=residentScene.lights.get(id);
            if(!Objects.equals(next,old)){if(next==null)removedLights.add(id);else changedLights.add(next);}
        }
        boolean sameLights=sparseLights&&changedLights.isEmpty()&&removedLights.isEmpty();
        boolean sparseSourcesChanged=fullReset?!scene.sparseSources.isEmpty():false;
        if(!sparseSourcesChanged&&residentScene!=null)for(long id:pendingSparseIds)
            if(!Objects.equals(scene.sparseSources.get(id),residentScene.sparseSources.get(id))){sparseSourcesChanged=true;break;}
        if(sparseSourcesChanged)historyCauses.add(HistoryCause.LIGHT);
        if(lighting!=null&&nextLighting!=null){
            if(!lighting.environment.equals(nextLighting.environment))historyCauses.add(HistoryCause.ENVIRONMENT);
            if(!sameLights)historyCauses.add(HistoryCause.LIGHT);
        }
        var completeLighting=nextLighting!=null&&!sparseLights?RtxptLighting.sorted(f.environment(),scene.sortedLights()):null;
        float[] lightRecords=completeLighting!=null?completeLighting.records():null;
        var lightPatch=sparseLights?RtxptLighting.of(f.environment(),changedLights):null;
        float[] patchRecords=lightPatch!=null?Arrays.copyOfRange(lightPatch.records(),16,(changedLights.size()+1)*16):null;
        long validationDuration=diagnosticElapsed(validationBegin);validationNs+=validationDuration;
        long translationBegin=diagnosticClock();
        boolean rebuild=nativeScene==null && (handle==0 || fullReset);
        boolean delta=nativeScene==null&&!rebuild&&(dirty||!RtxptScene.sameStructure(items,previous));
        var updateMode=nativeScene!=null?RetainedUpdateMode.NATIVE_SCENE:rebuild
            ?(handle==0?RetainedUpdateMode.INITIAL_PUBLICATION:RetainedUpdateMode.EXPLICIT_RESET)
            :delta?RetainedUpdateMode.RESOURCE_DELTA:!RtxptScene.sameItems(items,previous)?RetainedUpdateMode.TRANSFORMS
            :sparseSourcesChanged||!sameLights||!Objects.equals(nextLighting,lighting)?RetainedUpdateMode.LIGHTING:RetainedUpdateMode.UNCHANGED;
        if(delta||rebuild)historyCauses.add(HistoryCause.RESOURCE);
        RtxptDelta.Prepared preparedDelta=null;
        Path path=nativeScene;
        if(rebuild){long begin=diagnosticClock();try{
            if(directory==null)directory=Files.createTempDirectory("rtrenderer-rtxpt-");
            var generation=Files.createTempDirectory(directory,"full-");var bootstrap=new RtxptScene();path=bootstrap.export(generation,f,List.of());fullDirectory=generation;
            textureFiles=new RtxptPersistentMap<>();
            memoryTextures.clear();preparedDelta=RtxptDelta.prepare(directory,bootstrap,scene,List.of(),items,textureFiles,memoryTextures,resourcePackets);
            if(diagnosticLevel!=RtxptDiagnostics.Level.OFF){preparationModels++;preparationItemReads+=items.size();}
        }catch(IOException e){throw new IllegalStateException("RTXPT asset translation failed",e);}exportNs+=diagnosticElapsed(begin);exports++;}
        if(delta)try{preparedDelta=RtxptDelta.prepare(directory,residentScene,scene,previous,items,textureFiles,memoryTextures,resourcePackets);
            if(diagnosticLevel!=RtxptDiagnostics.Level.OFF){var work=preparedDelta.work();preparationItemReads+=work.itemReads();
                preparationDependencies+=work.dependencyVisits();preparationMapNodes+=work.inspectedMapNodes();preparationModels+=work.exportedModels();}}
        catch(IOException e){throw new IllegalStateException("RTXPT delta translation failed",e);}
        long translationDuration=diagnosticElapsed(translationBegin);translationNs+=translationDuration;
        try {
        if(handle==0){
            if(path==null)throw new IllegalStateException("Submit a populated scene");
            handle=RtxptNative.create(RtxptNative.runtime().toString(),path.toString(),f.width(),f.height(),externalPresentation,gpuTimingsEnabled,
                maxFramesInFlight,resources.workerThreads(),resources.shaderCompilerThreads(),options.arguments(),options.qualityInts(),options.qualityFloats(),diagnosticLevel.ordinal());
            RtxptNative.diagnosticLevel(handle,diagnosticLevel.ordinal());
            if(compositor!=null)RtxptNative.compositor(handle,compositor);
            if(title!=null)RtxptNative.title(handle,title);
            applyNativeSettings();
            if(nativeScene==null)scene.uploadInteriors(handle);
        }else if(rebuild){RtxptNative.loadScene(handle,path.toString());applyNativeSettings();scene.uploadInteriors(handle);}
        RtxptNative.diagnosticContext(handle,f.sequence(),revision);
        // Select native AS semantics before the first memory transaction builds
        // geometry; otherwise alpha meshes inherit beauty-only build flags.
        if(rebuild&&nativeLightingPlan!=null)RtxptNative.nativeLighting(handle,RtxptNativeLightingParameters.pack(nativeLightingPlan,f));
        if(!preparingHostSurface&&!submitTracked){RtxptNative.diagnosticSubmitBegin(handle,f.sequence(),revision,diagnosticElapsed(submitBegin));submitTracked=true;}
        if(submitBegin!=0){
            RtxptNative.diagnosticSubmitStage(RtxptDiagnostics.Stage.SUBMIT_VALIDATION.ordinal(),0,validationDuration,scene.lights.size(),pendingLightIds.size());
            RtxptNative.diagnosticSubmitStage(RtxptDiagnostics.Stage.TRANSLATION.ordinal(),translationBegin-submitBegin,translationDuration,items.size(),0);
        }
        if(preparedDelta!=null&&preparedDelta.hasWork()){
            long begin=diagnosticClock();RtxptNative.resourcesMemory(handle,revision,preparedDelta.memory());nativeResourceNs+=diagnosticElapsed(begin);preparedDelta.translated().uploadInteriors(handle);
            for(var entry:preparedDelta.translated().textures.entrySet())memoryTextures.put(entry.getKey(),entry.getValue());
            scene.textures.forEachChanged(residentScene==null?new RtxptPersistentMap<>():residentScene.textures,(id,value)->{if(value==null)memoryTextures.remove(id);});
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
        if(nextLighting!=null&&(rebuild||!sameLights||!nextLighting.equals(lighting))) {
            long begin=diagnosticClock();
            try {
                if(!rebuild&&sparseLights){
                    RtxptNative.lightingPatch(handle,lightPatch.lights.stream().mapToLong(SceneLight::id).toArray(),patchRecords,
                        removedLights.stream().mapToLong(Long::longValue).toArray(),nextLighting.sun(),nextLighting.sky());
                }else if(!rebuild&&sameLights)RtxptNative.environment(handle,nextLighting.sun(),nextLighting.sky());
                else RtxptNative.lighting(handle,completeLighting.ids(),lightRecords,nextLighting.sky());
            }
            catch(RuntimeException|Error failure) {
                // A failed native commit must never present a partially modified generation.
                try {close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
            lighting=nextLighting;lightingNs+=diagnosticElapsed(begin);
        }
        if(!delta)updateTransforms(items,rebuild);
        if(rebuild||!pendingSparseIds.isEmpty()){
            var sources=new ArrayList<SparseLightSource>();var removed=new ArrayList<Long>();
            if(rebuild)sources.addAll(scene.sparseSources.values());
            else for(long id:pendingSparseIds){var value=scene.sparseSources.get(id);var old=residentScene.sparseSources.get(id);
                if(!Objects.equals(value,old)){if(value==null)removed.add(id);else sources.add(value);}}
            if(rebuild||!sources.isEmpty()||!removed.isEmpty())RtxptNative.sparseLighting(handle,sources.stream().mapToLong(SparseLightSource::id).toArray(),RtxptSparseSources.pack(sources),removed.stream().mapToLong(Long::longValue).toArray(),rebuild);
        }
        boolean mappingsChanged=rebuild||(preparedDelta!=null?preparedDelta.mappingsChanged():!RtxptScene.sameStructure(items,previous));
        pendingGeometryVariants=preparedDelta==null?List.of():preparedDelta.changedVariants();
        prepareNativeLighting(f,items,rebuild);
        prepareHostSurface(f,items,mappingsChanged);
        previous=items;dirty=false;fullReset=false;residentScene=scene;pendingLightIds.clear();pendingSparseIds.clear();
        if(preparingHostSurface){
            preparedHostHistory.addAll(historyCauses);
            if(updateMode!=RetainedUpdateMode.UNCHANGED)preparedHostUpdateMode=updateMode;
            if(rebuild)preparedHostReasons.add(handle==0||frames==0?HistoryInvalidationReason.FIRST_FRAME:HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED);
            else if(delta)preparedHostReasons.add(HistoryInvalidationReason.SCENE_CONTENT_CHANGED);
            if(extentChanged)preparedHostReasons.add(HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED);
            if(rebuild)pruneTranslations();else if(preparedDelta!=null)retireDelta(preparedDelta);return null;
        }
        if(hostSurfacePlan!=null){var host=hostRequests.get(f.sequence());RtxptNative.hostSurfaceRead(handle,f.sequence(),revision,host.resourceGeneration(),host.viewIdentity(),f.clock().timeNanos(),f.clock().deltaNanos());}
        long previousSubmitted=submitted,hostConsumedBefore=hostSurfacePlan==null?0:RtxptNative.hostSurfaceStatus(handle)[7];submitted=f.sequence();
        try{if(!renderFrame())throw new IllegalStateException("RTXPT window closed");}
        catch(RuntimeException|Error failure){if(hostSurfacePlan!=null){submitted=previousSubmitted;
            if(RtxptNative.hostSurfaceStatus(handle)[7]>hostConsumedBefore)hostRequests.remove(f.sequence());}throw failure;}
        scenePublished=true;
        if(hostSurfacePlan!=null){hostRequests.remove(f.sequence());lastHostCamera=f.camera();preparedHostHistory.clear();
            if(preparedHostUpdateMode!=RetainedUpdateMode.UNCHANGED)updateMode=preparedHostUpdateMode;
            preparedHostUpdateMode=RetainedUpdateMode.UNCHANGED;}
        verifyRequiredFeatures();
        lastUpdateMode=updateMode;
        var historyAfter=RtxptNative.executionTelemetry(handle);
        if(historyCauses.isEmpty()&&(historyAfter[8]!=historyBefore[8]||historyAfter[9]!=historyBefore[9]))historyCauses.add(HistoryCause.CONFIGURATION);
        lastHistoryFrame=new HistoryFrame(submitted,completed==submitted,historyCauses,historyAfter[7]-historyBefore[7],historyAfter[8]-historyBefore[8],historyAfter[9]-historyBefore[9],historyAfter[10]-historyBefore[10]);
        backgroundChanged=false;
        if(rebuild||preparedDelta!=null){long begin=diagnosticClock();if(rebuild)pruneTranslations();else retireDelta(preparedDelta);retirementNs+=diagnosticElapsed(begin);}
        var reasons=EnumSet.noneOf(HistoryInvalidationReason.class);
        if(hostSurfacePlan!=null){reasons.addAll(preparedHostReasons);preparedHostReasons.clear();}
        if(rebuild)reasons.add(frames==1?HistoryInvalidationReason.FIRST_FRAME:HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED);
        else if(delta)reasons.add(HistoryInvalidationReason.SCENE_CONTENT_CHANGED);
        if(extentChanged)reasons.add(HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED);
        if(historyCauses.contains(HistoryCause.ENVIRONMENT)||historyCauses.contains(HistoryCause.LIGHT)||historyCauses.contains(HistoryCause.BACKGROUND))
            if(lastHistoryFrame.pathAccumulationResets()>0||lastHistoryFrame.reconstructionResets()>0||lastHistoryFrame.denoiserResets()>0)reasons.add(HistoryInvalidationReason.FRAME_SHADING_CHANGED);
        for(var reason:f.temporalHistoryResets())reasons.add(HistoryInvalidationReason.valueOf(reason.name()));
        submitAccepted=true;return FrameSubmissionResult.accepted(submitted,revision,reasons);
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }finally{if(submitTracked)RtxptNative.diagnosticSubmitEnd(!submitAccepted);
            if(diagnosticLevel!=RtxptDiagnostics.Level.OFF){preparationSubmissions++;if(allocationStart>=0){long end=allocatedBytes();if(end>=allocationStart)preparationAllocatedBytes+=end-allocationStart;}}}
    }
    long[] benchmarkGpuSnapshot(){ready();return RtxptNative.benchmarkGpuSnapshot(handle);}
    long[] nativeLightingInputSnapshot(){ready();return RtxptNative.nativeLightingInputSnapshot(handle);}
    long[] sceneTableUploadStatistics(){ready();return RtxptNative.sceneTableUploadStatistics(handle);}
    void beginBenchmarkGpuRequest(RenderFrameRequest frame){ready();RtxptNative.benchmarkGpuBegin(handle,frame.sequence(),revision);}
    void resetWindowPollInterval(){ready();RtxptNative.resetWindowPollInterval(handle);}
    @Override public SubmitStages submitStages(){ready();var n=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);return new SubmitStages(validationNs,translationNs,nativeResourceNs,lightingNs,retirementNs,n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[12],n[13],n[14],n[15]);}
    @Override public HistoryActions historyActions(){ready();var n=handle==0?new long[16]:RtxptNative.executionTelemetry(handle);return new HistoryActions(n[7],n[8],n[9],n[10],n[11]);}
    @Override public NativePreparationStatistics nativePreparationStatistics(){ready();var t=handle==0?new long[14]:RtxptNative.resourceTelemetry(handle);
        return new NativePreparationStatistics(diagnosticLevel!=RtxptDiagnostics.Level.OFF,t[11],t[12],t[13]);}
    @Override public RetainedUpdateTimings retainedUpdateTimings(){ready();var t=handle==0?new long[14]:RtxptNative.resourceTelemetry(handle);
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
    private void retireDelta(RtxptDelta.Prepared delta){
        // Imported model files are no longer needed; cached PNG paths remain live until replaced.
        for(var path:delta.retiredTextures()){try{Files.deleteIfExists(path);if(!path.getParent().equals(fullDirectory))Files.deleteIfExists(path.getParent());}catch(IOException ignored){ }}
        if(delta.directory()!=null){for(String name:List.of("delta.json","content.gltf","content.bin"))try{Files.deleteIfExists(delta.directory().resolve(name));}catch(IOException ignored){ }
            try{Files.deleteIfExists(delta.directory());}catch(IOException ignored){ }}
    }
    void captureLinear(Path path){ready();RtxptNative.captureLinear(handle,path.toAbsolutePath().toString());}
    void captureHostSurface(int signal,Path path){ready();RtxptNative.hostSurfaceCapture(handle,signal,path.toAbsolutePath().toString());}
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
    private void updateTransforms(List<RtxptScene.Item> items,boolean initial){
        if(RtxptScene.sameItems(items,previous))return;
        List<RtxptScene.Item> changed;
        if(initial)changed=items;
        else{var edits=RtxptScene.changedItems(residentScene,scene,previous,items);changed=edits.upserts();
            if(diagnosticLevel!=RtxptDiagnostics.Level.OFF){preparationMapNodes+=edits.inspectedNodes();preparationItemReads+=edits.itemReads();}}
        if(diagnosticLevel!=RtxptDiagnostics.Level.OFF)preparationTransforms+=changed.size();
        if(changed.isEmpty())return;
        long begin=diagnosticClock();var data=RtxptScene.floats(changed.size()*12);for(var i:changed)data.put(i.transform().elements());
        RtxptNative.transforms(handle,changed.stream().map(RtxptScene.Item::name).toArray(String[]::new),data.flip());batches++;transformNs+=diagnosticElapsed(begin);
    }
    @Override public boolean renderFrame(){ready();if(handle==0)throw new IllegalStateException("Submit a scene first");if(windowClosed)return false;
        long begin=diagnosticClock();int result=RtxptNative.frame(handle);frameNs+=diagnosticElapsed(begin);if(result==2){frames++;completed=submitted;}else if(result==0)windowClosed=true;return result!=0;}
    @Override public void diagnosticLevel(RtxptDiagnostics.Level level){ready();diagnosticLevel=Objects.requireNonNull(level);if(handle!=0)RtxptNative.diagnosticLevel(handle,level.ordinal());else if(diagnosticPrepared)RtxptNative.prepareDiagnostics(level.ordinal());}
    @Override public RtxptDiagnostics.LightUpdates lightUpdates(){ready();return RtxptDiagnostics.decodeLights(handle==0?new long[16]:RtxptNative.lightUpdateTelemetry(handle));}
    @Override public RtxptDiagnostics.Snapshot diagnosticSnapshot(){ready();return handle==0?RtxptDiagnostics.decode(new long[]{2,12,0,0,0,0,0}):RtxptDiagnostics.decode(RtxptNative.diagnosticSnapshot(handle));}
    @Override public RtxptDiagnostics.Monitor diagnosticMonitor(){ready();
        if(handle==0&&!diagnosticPrepared){RtxptNative.prepareDiagnostics(diagnosticLevel.ordinal());diagnosticPrepared=true;}
        long token=RtxptNative.diagnosticMonitorToken(handle);return RtxptDiagnostics.monitor(token);}
    @Override public RtxptDiagnostics.GpuSnapshot diagnosticGpuSnapshot(){ready();return RtxptDiagnostics.decodeGpu(handle==0?new String[]{"0,0,0,0,0"}:RtxptNative.diagnosticGpuSnapshot(handle));}
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
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest f){long callBegin=diagnosticClock();ready();Objects.requireNonNull(f);
        boolean tracked=handle!=0&&!preparingHostSurface,delegated=false;
        if(tracked)RtxptNative.diagnosticSubmitBegin(handle,f.sequence(),revision,0);
        try {
        if(handle!=0){int admission=RtxptNative.resourcesAdmission(handle);if(admission!=0)return new FrameSubmissionDeferred(SubmissionDeferralReason.SCENE_UPDATE_BACKLOG,
            switch(admission){case 1->"Scene generations await graphics/compute completion";case 2->"Completed-object release queue is full";
                case 3->"Lighting allocations await GPU completion";default->throw new IllegalStateException("Unknown native admission result");});}
        if(hostSurfacePlan!=null&&handle!=0&&!RtxptNative.hostSurfaceCanPublish(handle))return new FrameSubmissionDeferred(SubmissionDeferralReason.FRAME_RING_FULL,"All host-surface HDR slots await consumer completion");
        if(nativeLightingPlan!=null&&handle!=0&&!RtxptNative.nativeLightingCanPublish(handle))return new FrameSubmissionDeferred(SubmissionDeferralReason.FRAME_RING_FULL,"All HDR lighting slots await consumer completion");
        delegated=true;return new FrameSubmitted(submitFrame(f,true,callBegin));
        }catch(RuntimeException|Error failure){if(tracked)RtxptNative.diagnosticSubmitEnd(true);delegated=true;throw failure;
        }finally{if(tracked&&!delegated)RtxptNative.diagnosticSubmitDeferred();}}
    @Override public DynamicResourceStatistics dynamicResourceStatistics(){ready();var n=handle==0?new long[19]:RtxptNative.dynamicResourceStatistics(handle);
        return new DynamicResourceStatistics(diagnosticLevel!=RtxptDiagnostics.Level.OFF,n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],n[14],n[16],n[17],n[15]);}
    @Override public boolean readFrame(FrameConsumer consumer){ready();Objects.requireNonNull(consumer);throw new UnsupportedOperationException("RTXPT presents GPU frames directly; CPU readback is not exposed");}
    @Override public Optional<FrameSnapshot> pollFrame(){ready();throw new UnsupportedOperationException("RTXPT GPU-native presentation");}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout){Objects.requireNonNull(timeout);return pollFrame();}
    @Override public void close(){
        if(closed)return;ready();Throwable failure=null;
        boolean removed=handle!=0&&RtxptNative.deviceRemoved(handle);
        if(!removed&&nativeLightingPlan!=null&&handle!=0&&!RtxptNative.nativeLightingConsumersRetired(handle))throw new IllegalStateException("Return imported lighting leases and finish their consumer GPU work before closing");
        if(!removed&&hostSurfacePlan!=null&&handle!=0){RtxptNative.hostSurfaceRequireRetired(handle);
            if(!RtxptNative.hostSurfaceConsumersRetired(handle))throw new IllegalStateException("Complete imported host-surface HDR output consumers before closing");}
        try{if(handle!=0)RtxptNative.close(handle);}
        catch(RuntimeException|Error e){failure=e;throw e;}
        finally {
            handle=0;closed=true;
            // A caller may retain a closed renderer; do not keep its entire scene or
            // a compositor closure (and its owner's Vulkan resources) alive with it.
            scene=new RtxptScene();residentScene=null;textureFiles.clear();memoryTextures.clear();resourcePackets.clear();pendingLightIds.clear();visibleBackground=null;previous=List.of();camera=null;lighting=null;compositor=null;nativeScene=null;
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
