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
    private RtxptOptions options=RtxptOptions.recommended();
    private long handle,revision,submitted=-1,completed=-1,frames,frameNs,exports,exportNs,batches,transformNs;
    private boolean closed,dirty=true,windowClosed;
    private int width,height;
    private Path directory,nativeScene;
    private List<RtxptScene.Item> previous=List.of();
    private CameraState camera;
    private EnvironmentState environment;
    RtxptRenderer(RendererConfig config){Objects.requireNonNull(config);RtxptNative.load();}
    private void ready(){if(closed)throw new IllegalStateException("RTXPT renderer closed");if(Thread.currentThread()!=owner)throw new IllegalStateException("Call RTXPT on its creating thread");}
    @Override public Status status(){return closed?Status.CLOSED:Status.READY;}
    @Override public RendererHealth health(){return new RendererHealth(status(),Optional.empty(),RendererHealth.ResourceObligations.none());}
    @Override public RendererDiagnostics diagnostics(){return RendererDiagnostics.builder().status(status()).latestAcceptedSceneRevision(revision)
        .latestSubmittedFrameSequence(submitted).latestCompletedFrameSequence(completed).residentMeshes(scene.meshes.size()).residentInstances(scene.instances.size()).build();}
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
    @Override public void configure(RtxptOptions value){ready();if(handle!=0)throw new IllegalStateException("Configure before first submit");options=Objects.requireNonNull(value);}
    @Override public void loadNativeScene(Path value){
        ready();if(!scene.meshes.isEmpty())throw new IllegalStateException("Cannot replace retained API geometry with an unrelated native scene");
        var path=Objects.requireNonNull(value).toAbsolutePath().normalize();if(!Files.isRegularFile(path))throw new IllegalArgumentException("Missing native scene "+path);
        if(handle!=0)RtxptNative.loadScene(handle,path.toString());nativeScene=path;
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
            RtxptNative.settings(handle,options.maxBounces(),options.diffuseBounces(),options.reflex());
            if(nativeScene==null)scene.uploadInteriors(handle);
        }else if(rebuild){RtxptNative.loadScene(handle,path.toString());scene.uploadInteriors(handle);}
        if(width!=f.width()||height!=f.height()){RtxptNative.resize(handle,f.width(),f.height());width=f.width();height=f.height();}
        if(!Objects.equals(camera,f.camera())){
            var c=f.camera();RtxptNative.camera(handle,c.x()+","+c.y()+","+c.z()+","+c.forwardX()+","+c.forwardY()+","+c.forwardZ()+","+c.upX()+","+c.upY()+","+c.upZ(),(float)(2*Math.atan(c.tanHalfFovY())));camera=c;
        }
        if(!rebuild)updateTransforms(items);
        previous=items;environment=f.environment();dirty=false;
        if(!renderFrame())throw new IllegalStateException("RTXPT window closed");
        submitted=f.sequence();completed=submitted;
        return FrameSubmissionResult.accepted(submitted,revision,Set.of());
    }
    private void updateTransforms(List<RtxptScene.Item> items){
        var changed=new ArrayList<RtxptScene.Item>();for(int i=0;i<items.size();i++)if(!items.get(i).transform().equals(previous.get(i).transform()))changed.add(items.get(i));
        if(changed.isEmpty())return;
        long begin=System.nanoTime();var data=RtxptScene.floats(changed.size()*12);for(var i:changed)data.put(i.transform().elements());
        RtxptNative.transforms(handle,changed.stream().map(RtxptScene.Item::name).toArray(String[]::new),data.flip());batches++;transformNs+=System.nanoTime()-begin;
    }
    @Override public boolean renderFrame(){ready();if(handle==0)throw new IllegalStateException("Submit a scene first");if(windowClosed)return false;
        long begin=System.nanoTime();boolean rendered=RtxptNative.frame(handle);frameNs+=System.nanoTime()-begin;if(rendered)frames++;else windowClosed=true;return rendered;}
    @Override public long windowHandle(){ready();if(handle==0)throw new IllegalStateException("Submit first");return RtxptNative.window(handle);}
    @Override public void capture(Path png){ready();if(handle==0)throw new IllegalStateException("Submit first");RtxptNative.capture(handle,Objects.requireNonNull(png).toAbsolutePath().toString());}
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
