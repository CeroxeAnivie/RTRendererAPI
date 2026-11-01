package top.ceroxe.rt.renderer.luxcore;

import java.nio.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;

/** One persistent upstream Scene + RenderSession, serialized at the Java ownership boundary. */
final class LuxCoreRenderer implements Renderer, LuxCoreAccess {
    private final RendererConfig configuration;
    private final LuxCoreScene scene=new LuxCoreScene();
    private long handle,revision,submitted=-1,completed=-1;
    private boolean started,closed,inRead,dirty=true;
    private RenderFrameRequest previous;
    private int width,height;
    private double deliveredSamples;
    private String extraConfig="",extraScene="";
    private long edits,editNs,submits,submitNs,reads,readNs;
    private ByteBuffer display;

    LuxCoreRenderer(RendererConfig config) {configuration=Objects.requireNonNull(config);LuxCoreNative.load();handle=LuxCoreNative.create();}
    private void ready() {if(closed)throw new IllegalStateException("LuxCore renderer closed");if(inRead)throw new IllegalStateException("Do not reenter renderer from readFrame");}
    @Override public synchronized Status status(){return closed?Status.CLOSED:Status.READY;}
    @Override public synchronized RendererHealth health(){return new RendererHealth(status(),Optional.empty(),RendererHealth.ResourceObligations.none());}
    @Override public synchronized RendererDiagnostics diagnostics(){return RendererDiagnostics.builder().status(status()).latestAcceptedSceneRevision(revision)
            .latestSubmittedFrameSequence(submitted).latestCompletedFrameSequence(completed).residentMeshes(scene.meshes.size()).residentInstances(scene.instances.size()).build();}
    @Override public synchronized SceneUpdateResult apply(SceneTransaction tx) {
        ready();Objects.requireNonNull(tx);if(tx.revision()<revision)throw new IllegalArgumentException("scene revision must be monotonic");
        // Admission validation happens before changing native ownership.
        var nextMeshes=new HashMap<>(scene.meshes);if(tx.reset())nextMeshes.clear();LuxCoreScene.remove(nextMeshes,tx.removals().meshIds());tx.upserts().meshes().forEach(m->nextMeshes.put(m.id(),m));
        for(var i:tx.upserts().instances())if(!nextMeshes.containsKey(i.meshAssetId()))throw new IllegalArgumentException("Missing mesh "+i.meshAssetId());
        long begin=System.nanoTime();if(started)LuxCoreNative.beginEdit(handle);
        try {
            // Remove live objects before a reset or asset replacement; native caches can then retire unused data.
            if(!scene.objects.isEmpty()){LuxCoreNative.deleteObjects(handle,LuxCoreScene.bytes(String.join("\n",scene.objects)));scene.objects.clear();}
            if(tx.reset()||tx.removals().lightIds().hasRemaining())LuxCoreNative.deleteLights(handle,LuxCoreScene.bytes(String.join("\n",scene.lights.keySet().stream().map(i->"l"+i).toList())));
            scene.retain(tx);scene.upload(handle,tx);revision=tx.revision();dirty=true;
            if(started&&previous!=null){LuxCoreNative.parse(handle,LuxCoreScene.bytes(scene.scene(previous)+extraScene));dirty=false;}
        } finally {if(started)LuxCoreNative.endEdit(handle);editNs+=System.nanoTime()-begin;edits++;}
        return new SceneUpdateResult(revision);
    }
    private static boolean sameSceneFrame(RenderFrameRequest a,RenderFrameRequest b) {
        return a!=null&&a.camera().equals(b.camera())&&a.environment().equals(b.environment())
                &&a.primitiveBatch().equals(b.primitiveBatch())&&a.mediumTransport().equals(b.mediumTransport());
    }
    @Override public synchronized FrameSubmissionResult submit(RenderFrameRequest request) {
        ready();Objects.requireNonNull(request);if(request.sequence()<=submitted)throw new IllegalArgumentException("Frame sequence must increase");
        if(request.minimumSceneRevision()>revision)throw new IllegalArgumentException("minimum scene revision is not resident");
        long begin=System.nanoTime();
        if(!started) {
            width=request.width();height=request.height();
            LuxCoreNative.parse(handle,LuxCoreScene.bytes(scene.scene(request)+extraScene));
            LuxCoreNative.start(handle,LuxCoreScene.bytes(config(request)+extraConfig));started=true;dirty=false;
        } else {
            if(width!=request.width()||height!=request.height()) {
                width=request.width();height=request.height();display=null;
                LuxCoreNative.resize(handle,LuxCoreScene.bytes("film.width = "+width+"\nfilm.height = "+height+"\n"));
                deliveredSamples=0;
            }
            if(dirty||!sameSceneFrame(previous,request)||!request.temporalHistoryResets().isEmpty()) {
                LuxCoreNative.beginEdit(handle);
                try {
                    if(!scene.objects.isEmpty())LuxCoreNative.deleteObjects(handle,LuxCoreScene.bytes(String.join("\n",scene.objects)));
                    LuxCoreNative.parse(handle,LuxCoreScene.bytes(scene.scene(request)+extraScene));
                }finally {LuxCoreNative.endEdit(handle);}
                dirty=false;deliveredSamples=0;
            }
        }
        previous=request;submitted=request.sequence();submitNs+=System.nanoTime()-begin;submits++;
        return FrameSubmissionResult.accepted(submitted,revision,Set.of());
    }
    static String config(RenderFrameRequest f) {
        return "renderengine.type = "+System.getProperty("top.ceroxe.rt.luxcore.engine","RTPATHOCL")+"\n"
            +"opencl.native.threads.count = 0\nopencl.cpu.use = 0\nopencl.gpu.use = 1\n"
            +"film.width = "+f.width()+"\nfilm.height = "+f.height()+"\n"
            +"rtpath.resolutionreduction = 1\nrtpath.resolutionreduction.preview = 1\nrtpath.resolutionreduction.preview.step = 1\n"
            +"tilepath.sampling.aa.size = 1\nfilm.filter.type = NONE\n"
            +"path.pathdepth.total = "+f.mediumTransport().maxEvents()+"\n"
            +"film.imagepipelines.0.0.type = TONEMAP_LINEAR\nfilm.imagepipelines.0.0.scale = 1\nfilm.imagepipelines.0.1.type = GAMMA_CORRECTION\n"
            +"film.outputs.0.type = RGBA\nfilm.outputs.0.filename = unused.exr\n"
            +"film.outputs.1.type = RGBA_IMAGEPIPELINE\nfilm.outputs.1.filename = unused.png\n";
    }
    @Override public synchronized FrameSubmissionAttempt trySubmit(RenderFrameRequest request){return new FrameSubmitted(submit(request));}
    @Override public synchronized boolean readFrame(FrameConsumer consumer) {
        ready();Objects.requireNonNull(consumer);if(!started)return false;
        double samples=LuxCoreNative.samples(handle);if(samples<=deliveredSamples||samples<=0)return false;
        if(display==null)display=LuxCoreScene.buffer(width*height*4);
        long begin=System.nanoTime();double readSamples=LuxCoreNative.readDisplay(handle,display);readNs+=System.nanoTime()-begin;reads++;
        completed=submitted;deliveredSamples=readSamples;
        inRead=true;try{consumer.accept(new View(completed,revision,width,height,display));}finally{inRead=false;}return true;
    }
    private record View(long frameSequence,long renderedSceneRevision,int width,int height,ByteBuffer storage) implements FrameView {
        @Override public Optional<RenderResourceId> outputResource(){return Optional.empty();}
        @Override public TextureFormat format(){return TextureFormat.RGBA8_UNORM;}
        @Override public FrameOrigin origin(){return FrameOrigin.TOP_LEFT;}
        @Override public int rowBytes(){return width*4;}
        @Override public int byteCount(){return width*height*4;}
        @Override public ByteBuffer pixels(){return storage.asReadOnlyBuffer();}
    }
    @Override public Optional<FrameSnapshot> pollFrame(){var result=new FrameSnapshot[1];readFrame(v->result[0]=FrameSnapshot.copyOf(v));return Optional.ofNullable(result[0]);}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);if(timeout.isNegative())throw new IllegalArgumentException("negative timeout");long end=System.nanoTime()+timeout.toNanos();
        do {if(Thread.interrupted())throw new InterruptedException();var result=pollFrame();if(result.isPresent())return result;LockSupport.parkNanos(500_000);}while(System.nanoTime()<end);return Optional.empty();
    }
    @Override public synchronized void sceneProperties(String properties) {
        ready();Objects.requireNonNull(properties);if(started)LuxCoreNative.beginEdit(handle);
        try {LuxCoreNative.parse(handle,LuxCoreScene.bytes(properties));extraScene+=properties+"\n";}
        finally {if(started)LuxCoreNative.endEdit(handle);}deliveredSamples=0;
    }
    @Override public synchronized void configurationProperties(String properties){ready();if(started)throw new IllegalStateException("Configure before first submit");extraConfig+=Objects.requireNonNull(properties)+"\n";}
    @Override public synchronized double readLinearRgba(ByteBuffer target){ready();if(!started)throw new IllegalStateException("Submit first");long begin=System.nanoTime();double samples=LuxCoreNative.readFilm(handle,target);readNs+=System.nanoTime()-begin;reads++;return samples;}
    @Override public synchronized double totalSamples(){ready();return started?LuxCoreNative.samples(handle):0;}
    @Override public synchronized String nativeStatistics(){ready();return started?LuxCoreNative.statistics(handle):"";}
    @Override public synchronized Timings bridgeTimings(){return new Timings(edits,editNs,submits,submitNs,reads,readNs,scene.uploads,scene.imageUploads);}
    @Override public synchronized void close(){if(closed)return;ready();try{LuxCoreNative.close(handle);}finally{handle=0;closed=true;display=null;}}
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx){return new ResourceTransactionEvidence(tx.revision(),ResourceTransactionEvidence.Outcome.REJECTED,List.of(),"Use retained LuxCore scenes; general commands belong to Vulkan");}
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key){return Optional.empty();}
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities(){return RenderingSemanticCapabilities.unsupported();}
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx){return new CommandExecutionEvidence(tx.sequence(),CommandExecutionEvidence.Outcome.REJECTED,CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,OptionalLong.empty(),Optional.empty(),0,"General command lane belongs to Vulkan");}
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long sequence){return Optional.empty();}
}
