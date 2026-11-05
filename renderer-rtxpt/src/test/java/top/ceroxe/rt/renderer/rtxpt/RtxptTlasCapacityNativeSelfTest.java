package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.GlLightingPacketConsumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Exercises the actual removal-only TLAS path, capacity reuse and independent retirement. */
public final class RtxptTlasCapacityNativeSelfTest {
    static final class HiddenGl implements AutoCloseable {
        final long window;
        HiddenGl(){
            require(glfwInit(),"Initialize hidden lease-content verification");glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);
            glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);window=glfwCreateWindow(16,16,"Hidden retained-output verification",0,0);
            require(window!=0,"Create hidden lease-content context");glfwMakeContextCurrent(window);GL.createCapabilities();
        }
        public void close(){GL.setCapabilities(null);glfwDestroyWindow(window);glfwTerminate();}
    }
    static List<byte[]> pixels(GlLightingPacketConsumer.Packet packet){
        var values=new ArrayList<byte[]>();
        for(var plane:packet.frame().planes()){
            int bytes=packet.frame().width()*packet.frame().height()*(plane.format()==LightingFrameLease.Format.RGBA16_FLOAT?8:16);
            var data=MemoryUtil.memAlloc(bytes);
            try{glGetTextureImage(packet.texture(plane.signal()),0,
                plane.format()==LightingFrameLease.Format.RGBA32_UINT?GL_RGBA_INTEGER:GL_RGBA,
                plane.format()==LightingFrameLease.Format.RGBA16_FLOAT?GL_HALF_FLOAT:plane.format()==LightingFrameLease.Format.RGBA32_UINT?GL_UNSIGNED_INT:GL_FLOAT,data);
                var value=new byte[bytes];data.get(value);values.add(value);
            }finally{MemoryUtil.memFree(data);}
        }
        require(glGetError()==GL_NO_ERROR,"Read exact borrowed output pixels");return values;
    }
    static long sequence,revision=1;
    static LightingFrameLease submit(RtxptRenderer r)throws Exception {
        return submit(r,frame(sequence,revision).camera());
    }
    static LightingFrameLease submit(RtxptRenderer r,CameraState camera)throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        var request=frame(sequence,revision,camera);
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Immutable request eventually admitted");Thread.sleep(1);
        }
        var lease=r.acquireLightingFrame().orElseThrow();
        require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Exact accepted scene identity");
        sequence++;return lease;
    }
    static void step(RtxptRenderer r)throws Exception {try(var lease=submit(r)){} }
    static void drain(RtxptRenderer r)throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        do{r.awaitIdle();step(r);require(System.nanoTime()<deadline,"Bounded retirement drains");}
        while(r.resourcePressure().retiredGenerations()!=0||r.resourcePressure().pendingReleases()!=0);
    }
    static void add(SceneTransaction.Builder tx,long id){
        tx.upsert(RtxptTopologyPreparationNativeSelfTest.multi(id,2));
        tx.upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(id,id));
    }
    static List<RtxptDiagnostics.CpuEvent> eventsSince(RtxptRenderer r,long firstOrdinal){
        var snapshot=r.diagnosticSnapshot();
        require(snapshot.firstEventOrdinal()<=firstOrdinal&&snapshot.droppedActiveScopes()==0,"Diagnostic window covers the complete operation");
        return snapshot.events().stream().filter(e->e.ordinal()>=firstOrdinal&&e.completed()).toList();
    }
    static long allocations(RtxptRenderer r,long firstOrdinal){
        return eventsSince(r,firstOrdinal).stream().filter(e->
            e.stage()==RtxptDiagnostics.Stage.TLAS_ALLOCATE||e.stage()==RtxptDiagnostics.Stage.BUFFER_ALLOCATE).count();
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);
        try(var gl=new HiddenGl();var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);var consumer=new GlLightingPacketConsumer()){
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            var tx=SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,2,0,.2f,.7f,false)).upsert(SceneInstance.builder(1,1).build());
            for(long id=1000;id<1512;id++)add(tx,id);
            r.apply(tx.build());step(r);drain(r);var reference=capture(r,"tlas-reference",0);var referenceNormal=capture(r,"tlas-reference-normal",2);
            // Warm both capacity classes before checking allocation-free shrink/oscillation.
            r.apply(SceneTransaction.builder(++revision).removeInstance(1511).removeMesh(1511).build());step(r);drain(r);
            tx=SceneTransaction.builder(++revision);add(tx,1511);r.apply(tx.build());step(r);drain(r);
            long removalOrdinal=r.diagnosticSnapshot().nextEventOrdinal();
            tx=SceneTransaction.builder(++revision);for(long id=1000;id<1146;id++)tx.removeInstance(id).removeMesh(id);
            r.apply(tx.build());var held=submit(r);long heldSequence=held.frame().sequence();
            consumer.consume(held,packet->{
                var before=pixels(packet);
                try{
                    r.awaitIdle();var away=CameraState.lookAt(0,0,3,0,0,10).verticalFieldOfViewDegrees(52).aspectRatio(1.5).build();
                    var removal=SceneTransaction.builder(++revision);for(long id=1146;id<1292;id++)removal.removeInstance(id).removeMesh(id);
                    r.apply(removal.build());try(var ignored=submit(r,away)){}r.awaitIdle();try(var ignored=submit(r,away)){}
                    require(!Arrays.equals(referenceNormal,capture(r,"tlas-new-output-differs",2)),"New frame content changes while old output remains leased");
                    var after=pixels(packet);
                    for(int i=0;i<before.size();i++)require(Arrays.equals(before.get(i),after.get(i)),"Every plane of the held imported output remains byte exact");
                    require(packet.frame().sequence()==heldSequence,"Old output lease identity survives scene updates");
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));step(r);
            var pressure=r.resourcePressure();
            require(pressure.retiredGenerations()>=2,"Both >8-mesh generations still have retirement cursor work");
            require(pressure.pooledTlasBytes()>0,"Later fence-ready TLAS is pooled despite older mesh cursor backlog");
            require(allocations(r,removalOrdinal)==0,"Deleting 146 twice does not allocate TLAS or auxiliary tables");
            var uploads=eventsSince(r,removalOrdinal).stream().filter(e->e.stage()==RtxptDiagnostics.Stage.BUFFER_UPLOAD).toList();
            long uploadRows=uploads.stream().mapToLong(RtxptDiagnostics.CpuEvent::items).sum();
            long uploadBytes=uploads.stream().mapToLong(RtxptDiagnostics.CpuEvent::bytes).sum();
            require(!uploads.isEmpty()&&uploadRows>uploads.size()&&uploadBytes>0,"Dense attribute rows use fewer contiguous writeBuffer calls");
            require(Arrays.equals(reference,capture(r,"tlas-delete146",0)),"Removal-only updates preserve unrelated visible lighting");
            drain(r);
            long oscillationOrdinal=r.diagnosticSnapshot().nextEventOrdinal();
            for(int round=0;round<4;round++){
                tx=SceneTransaction.builder(++revision);for(long id=2000;id<2010;id++)add(tx,id);
                r.apply(tx.build());step(r);r.awaitIdle();step(r);
                tx=SceneTransaction.builder(++revision);for(long id=2000;id<2010;id++)tx.removeInstance(id).removeMesh(id);
                r.apply(tx.build());step(r);r.awaitIdle();step(r);
            }
            require(allocations(r,oscillationOrdinal)==0,"Same-capacity growth/shrink repeats without TLAS or auxiliary-table allocation");
            require(Arrays.equals(reference,capture(r,"tlas-oscillation",0)),"Capacity reuse preserves visible output");
            drain(r);
            // Two users keep one shared mesh alive. A mesh-only transaction changes
            // its geometry groups, then deleting one user must retain the survivor.
            r.apply(SceneTransaction.builder(++revision).upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(3000,1400)).build());step(r);drain(r);
            long poseOrdinal=r.diagnosticSnapshot().nextEventOrdinal();
            r.apply(SceneTransaction.builder(++revision).upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(3000,1400).toBuilder()
                .transform(new AffineTransform(new float[]{1,0,0,5000,0,1,0,1,0,0,1,0})).build()).build());step(r);
            require(allocations(r,poseOrdinal)==0,"Pure transform edits do not reserve unused TLAS or auxiliary tables");
            tx=SceneTransaction.builder(++revision);tx.upsert(RtxptTopologyPreparationNativeSelfTest.multi(1400,2,2,2));
            r.apply(tx.build());step(r);
            require(Arrays.equals(reference,capture(r,"tlas-mesh-only-layout",0)),"Mesh-only geometry-count changes are prepared before publication");
            r.apply(SceneTransaction.builder(++revision).removeInstance(3000).build());step(r);
            require(Arrays.equals(reference,capture(r,"tlas-shared-survivor",0)),"Deleting one shared-mesh user preserves its survivor");
            Files.writeString(OUT.resolve("tlas-capacity-result.json"),RtxptJson.encode(RtxptJson.object("passed",true,
                "deleteOnlyBatches",2,"removedPerBatch",146,"retiredGenerationsDuringCheck",pressure.retiredGenerations(),
                "pooledTlasBytesDuringBacklog",pressure.pooledTlasBytes(),"sameCapacityOscillations",4,"sharedMeshOnlyLayoutChange",true,
                "heldOutputAllPlaneBytesExact",true,"newOutputWasDifferent",true,"transformOnlyAllocations",0,"attributeUploadCalls",uploads.size(),"attributeUploadRows",uploadRows,"attributeUploadBytes",uploadBytes)));
            System.out.println("TLAS_CAPACITY PASS deleteOnly146=2 completedGenerationBacklog=true noCapacityChurn=true heldOutput=true sharedMeshOnlyLayout=true transformOnly=true uploadCalls="+uploads.size()+" rows="+uploadRows+" bytes="+uploadBytes);
        }
    }
}
