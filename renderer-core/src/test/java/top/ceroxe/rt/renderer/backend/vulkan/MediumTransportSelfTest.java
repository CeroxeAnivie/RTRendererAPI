package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.util.Arrays;
import top.ceroxe.rt.renderer.api.*;

public final class MediumTransportSelfTest {
    static MeshAsset cube(long id,long material,float radius) {
        return MeshAsset.triangles(id,new float[]{-radius,-radius,-radius,radius,-radius,-radius,radius,radius,-radius,-radius,radius,-radius,
            -radius,-radius,radius,radius,-radius,radius,radius,radius,radius,-radius,radius,radius},
            new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5},material);
    }
    public static void main(String[] args) {
        var medium=EnvironmentState.Medium.builder().absorption(.1f,.2f,.3f).scattering(.03f,.04f,.05f)
            .density(.8f).indexOfRefraction(1.333f).phaseAnisotropy(.6f).emission(.02f,.01f,0).build();
        require(Math.abs(medium.extinctionRed()-.13f)<1e-6,"absorption and scattering must compose");
        require(medium.equals(medium.toBuilder().build()),"medium round trip");
        expect(IllegalArgumentException.class,()->EnvironmentState.Medium.builder().phaseAnisotropy(1).build());
        expect(IllegalArgumentException.class,()->EnvironmentState.Medium.builder().emission(-1,0,0).build());
        expect(IllegalArgumentException.class,()->MediumBoundaryState.builder(EnvironmentState.Medium.builder().scattering(1,0,0).build()).build());
        expect(IllegalArgumentException.class,()->new MediumTransportOptions(33,1,8,512));
        expect(IllegalArgumentException.class,()->MediumTransportOptions.balanced().withMaximumDistance(Float.NaN));
        var boundary=MediumBoundaryState.builder(medium).priority(Integer.MIN_VALUE).surfaceInteraction(false).build();
        require(boundary.equals(boundary.toBuilder().build()),"boundary round trip");
        var instance=SceneInstance.builder(1,1).mediumBoundary(boundary).build();
        require(instance.equals(instance.toBuilder().build()),"instance boundary round trip");
        require(instance.toBuilder().clearMediumBoundary().build().mediumBoundary().isEmpty(),"boundary clear");
        var primitive=PrimitiveInstance.from(instance);
        require(primitive.mediumBoundary().orElseThrow().equals(boundary),"scene-to-frame boundary conversion");
        require(primitive.equals(primitive.toBuilder().build()),"frame boundary round trip");
        require(primitive.toBuilder().clearMediumBoundary().build().mediumBoundary().isEmpty(),"frame boundary clear");
        var mesh=cube(1,1,1);
        require(mesh.isClosedManifold(),"cube must be closed");
        var open=MeshAsset.triangles(2,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1);
        require(!open.isClosedManifold(),"open sheet must be rejected");
        int[] badIndices=new int[mesh.triangleIndices().remaining()];mesh.triangleIndices().get(badIndices);
        int swap=badIndices[0];badIndices[0]=badIndices[1];badIndices[1]=swap;
        float[] points=new float[mesh.positions().remaining()];mesh.positions().get(points);
        require(!MeshAsset.triangles(3,points,badIndices,1).isClosedManifold(),"inconsistent winding must be rejected");
        require(VulkanMediumBoundaryIndex.contains(mesh,AffineTransform.identity(),0,0,0),"camera inside");
        require(!VulkanMediumBoundaryIndex.contains(mesh,AffineTransform.identity(),3,0,0),"camera outside");
        var mirror=new AffineTransform(new float[]{-2,0,0,5,0,1,0,0,0,0,1,0});
        require(VulkanMediumBoundaryIndex.contains(mesh,mirror,5,0,0),"mirrored/scaled boundary containment");
        int[] packed=VulkanGpuSceneAbi.packInstance(instance,id->0);
        require(packed[VulkanGpuSceneAbi.INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD]==3,"volume-only flag");
        require(packed[VulkanGpuSceneAbi.INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD]==Integer.MIN_VALUE,"signed priority");
        require(Float.intBitsToFloat(packed[VulkanGpuSceneAbi.INSTANCE_MEDIUM_PHASE_ANISOTROPY_WORD])==.6f,"phase ABI");
        require(Float.intBitsToFloat(packed[VulkanGpuSceneAbi.INSTANCE_MEDIUM_EMISSION_WORD])==.02f,"emission ABI");
        var registry=new PersistentSceneRegistry();
        registry.commit(registry.prepare(SceneTransaction.builder(1).upsert(MaterialAsset.opaque(1,-1)).upsert(mesh).upsert(instance).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(2)
            .upsert(SceneInstance.builder(2,2).mediumBoundary(boundary).build()).upsert(open).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(2)
            .upsert(MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1)).build()));
        require(registry.state().revision()==1,"failed volume replacement must stay atomic");
        var residency=new VulkanSceneResidency();var index=new VulkanMediumBoundaryIndex();
        var transaction=SceneTransaction.builder(1).upsert(MaterialAsset.opaque(1,-1)).upsert(mesh).upsert(instance).build();
        var prepared=residency.prepare(transaction);residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.containing(CameraState.lookAt(0,0,0,0,0,-1).build()).length==1,"initial containment index");
        var second=SceneInstance.builder(2,1).mediumBoundary(boundary.toBuilder().priority(5).build()).build();
        prepared=residency.prepare(SceneTransaction.builder(2).upsert(second).build());residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(Arrays.equals(new int[]{0,1},index.containing(CameraState.lookAt(0,0,0,0,0,-1).build())),"priority order");
        prepared=residency.prepare(SceneTransaction.builder(3).removeInstances(1,2).build());residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.containing(CameraState.lookAt(0,0,0,0,0,-1).build()).length==0,"removed volumes retire immediately");
        var insideCamera=CameraState.lookAt(0,0,0,0,0,-1).build();
        var localRequest=RenderFrameRequest.builder(1,32,32,insideCamera)
            .primitiveBatch(FramePrimitiveBatch.builder().add(primitive).build()).build();
        require(Arrays.equals(index.containing(localRequest),new int[]{VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT}),"frame-local containment slot");
        require(index.frameBoundaryCount(localRequest.primitiveBatch())==1,"frame boundary count");
        require(index.containing(localRequest.toBuilder().primitiveBatch(FramePrimitiveBatch.empty()).build()).length==0,"frame volume retirement");
        var missing=localRequest.toBuilder().primitiveBatch(FramePrimitiveBatch.builder()
            .add(PrimitiveInstance.builder(999).mediumBoundary(boundary).build()).build()).build();
        expect(FrameValidationException.class,()->index.containing(missing));
        var tooMany=FramePrimitiveBatch.builder();
        for(int i=0;i<=MediumTransportOptions.MAX_NESTED_BOUNDARIES;i++) tooMany.add(primitive);
        expect(FrameValidationException.class,()->index.containing(localRequest.toBuilder().primitiveBatch(tooMany.build()).build()));
        prepared=residency.prepare(SceneTransaction.builder(4).resetScene().build());residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.boundaryCount()==0 && index.containing(insideCamera).length==0,"reset clears medium lifecycle");
        expect(FrameValidationException.class,()->index.containing(localRequest));
        var request=RenderFrameRequest.builder(1,32,32,CameraState.lookAt(0,0,4,0,0,0).build())
            .environment(EnvironmentState.builder().cameraMedium(medium).build())
            .mediumTransport(new MediumTransportOptions(18,4,16,1024)).build();
        require(request.equals(request.toBuilder().build()),"frame option round trip");
        var tracker=new TemporalHistoryTracker(TemporalRenderingOptions.disabled());
        var bytes=VulkanFrameUniformPacker.pack(request,0,0,tracker.prepare(request,0),TemporalRenderingOptions.disabled(),false);
        var words=java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(words.getInt(VulkanGpuSceneAbi.FRAME_OPTICAL_MAX_EVENTS_WORD*4)==18,"runtime optical depth");
        require(words.getInt(VulkanGpuSceneAbi.FRAME_SCATTERING_STEPS_WORD*4)==16,"runtime scattering steps");
        require(words.getFloat(VulkanGpuSceneAbi.FRAME_MEDIUM_EMISSION_WORD*4)==.02f,"global emission packed");
        verifyMediumHistoryInvalidation(instance,primitive,mesh);
        verifyAcceleratedContainment();
        System.out.println("MediumTransportSelfTest passed: values, topology, containment, packing, mutation rollback, retirement");
    }
    private static void verifyAcceleratedContainment() {
        var cube=cube(1,1,1);var source=cube.positions();var triangles=cube.triangleIndices();
        int subdivisions=16,triangleCount=cube.triangleCount()*subdivisions*subdivisions;
        float[] points=new float[triangleCount*9];int[] indices=new int[triangleCount*3];int next=0;
        while(triangles.hasRemaining()) {
            int a=triangles.get()*3,b=triangles.get()*3,c=triangles.get()*3;
            for(int u=0;u<subdivisions;u++)for(int v=0;v<subdivisions-u;v++) {
                int[][] uv={{u,v},{u+1,v},{u,v+1},{u+1,v},{u+1,v+1},{u,v+1}};
                int vertices=u+v+1<subdivisions?6:3;
                for(int vertex=0;vertex<vertices;vertex++) {
                    indices[next/3]=next/3;
                    for(int axis=0;axis<3;axis++) points[next++]=source.get(a+axis)
                        +(source.get(b+axis)-source.get(a+axis))*uv[vertex][0]/subdivisions
                        +(source.get(c+axis)-source.get(a+axis))*uv[vertex][1]/subdivisions;
                }
            }
        }
        var dense=MeshAsset.triangles(9,points,indices,1);
        require(dense.isClosedManifold(),"subdivided seam-welded cube topology");
        var geometry=new VulkanMediumGeometry(dense);var random=new java.util.Random(520);
        double[][] queries=new double[512][3];
        for(var query:queries) for(int axis=0;axis<3;axis++) query[axis]=random.nextDouble()*2.6-1.3;
        for(var query:queries) {
            boolean expected=Math.abs(query[0])<1 && Math.abs(query[1])<1 && Math.abs(query[2])<1;
            require(geometry.containsLocal(query[0],query[1],query[2])==expected,"BVH containment against analytic cube");
            require(geometry.windingContains(query[0],query[1],query[2])==expected,"winding reference containment");
        }
        int accelerated=0,reference=0;long started=System.nanoTime();
        for(var query:queries) if(geometry.containsLocal(query[0],query[1],query[2]))accelerated++;
        long bvhNanos=System.nanoTime()-started;started=System.nanoTime();
        for(var query:queries) if(geometry.windingContains(query[0],query[1],query[2]))reference++;
        long referenceNanos=System.nanoTime()-started;
        require(accelerated==reference,"accelerated/generalized-winding agreement");
        require(geometry.containsLocal(0,0,0),"shared diagonal/vertex directions must classify interior");
        System.out.printf(java.util.Locale.ROOT,"Medium containment: triangles=%d queries=%d BVH=%.2f ms winding=%.2f ms (informational, exact results required)%n",
            triangleCount,queries.length,bvhNanos/1e6,referenceNanos/1e6);
    }
    private static void verifyMediumHistoryInvalidation(SceneInstance instance,PrimitiveInstance primitive,MeshAsset mesh) {
        var residency=new VulkanSceneResidency();
        var tracker=new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
        var camera=CameraState.lookAt(0,0,4,0,0,0).build();
        var dynamic=instance.toBuilder().mobility(SceneInstance.Mobility.DYNAMIC).build();
        var initial=residency.prepare(SceneTransaction.builder(1).upsert(MaterialAsset.opaque(1,-1)).upsert(mesh).upsert(dynamic).build());
        residency.commit(initial);tracker.sceneApplied(initial.changeSet());
        tracker.commit(tracker.prepare(RenderFrameRequest.builder(1,32,32,camera).build(),1));
        var cleared=residency.prepare(SceneTransaction.builder(2).upsert(dynamic.toBuilder().clearMediumBoundary().build()).build());
        residency.commit(cleared);tracker.sceneApplied(cleared.changeSet());
        var frame=tracker.prepare(RenderFrameRequest.builder(2,32,32,camera).build(),2);
        require(frame.invalidations().contains(HistoryInvalidationReason.SCENE_CONTENT_CHANGED),"dynamic medium role removal invalidates indirect history");tracker.commit(frame);
        var restored=residency.prepare(SceneTransaction.builder(3).upsert(dynamic).build());
        residency.commit(restored);tracker.sceneApplied(restored.changeSet());
        tracker.commit(tracker.prepare(RenderFrameRequest.builder(3,32,32,camera).build(),3));
        var removed=residency.prepare(SceneTransaction.builder(4).removeInstance(dynamic.id()).build());
        residency.commit(removed);tracker.sceneApplied(removed.changeSet());
        frame=tracker.prepare(RenderFrameRequest.builder(4,32,32,camera).build(),4);
        require(frame.invalidations().contains(HistoryInvalidationReason.SCENE_CONTENT_CHANGED),"dynamic medium deletion invalidates indirect history");tracker.commit(frame);
        var local=RenderFrameRequest.builder(5,32,32,camera).primitiveBatch(FramePrimitiveBatch.builder().add(primitive).build()).build();
        frame=tracker.prepare(local,4);
        require(frame.invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),"frame-local medium addition invalidates indirect history");tracker.commit(frame);
        frame=tracker.prepare(RenderFrameRequest.builder(6,32,32,camera).build(),4);
        require(frame.invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),"frame-local medium deletion invalidates indirect history");tracker.commit(frame);
        frame=tracker.prepare(RenderFrameRequest.builder(7,32,32,camera).mediumTransport(MediumTransportOptions.balanced().withOpticalSamples(2)).build(),4);
        require(frame.invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),"transport quality invalidates history");
    }
    static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static void expect(Class<? extends Throwable> type,Runnable action) {
        try {action.run();} catch(Throwable failure) {if(type.isInstance(failure)) return;throw failure;}
        throw new AssertionError("expected "+type.getSimpleName());
    }
}
