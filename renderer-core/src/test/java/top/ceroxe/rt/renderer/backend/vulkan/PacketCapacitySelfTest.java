package top.ceroxe.rt.renderer.backend.vulkan;
import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;
public final class PacketCapacitySelfTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        var empty=CudaMediumDensityAbi.Scene.empty();long limit=512L*1024*1024-65536;
        var compact=CudaBoundaryMediumAbi.planPacket(1920,1080,1,2,empty,limit,24);
        check(compact.packet()&&compact.packetCapacity()==24&&compact.packetPathBytes()==224,"24-event layout");
        check(compact.tilePixels()==compact.pixels()&&compact.bytes()<=limit,"full HD fits one tile");
        var legacy=CudaBoundaryMediumAbi.planPacket(1920,1080,1,2,empty,limit);
        check(legacy.packetCapacity()==32&&legacy.packetPathBytes()==288&&legacy.tilePixels()<legacy.pixels(),"32-event fallback");
        long testStorage=64L*1024*1024;
        var boundaryWide=CudaBoundaryMediumAbi.planPacket(19,17,8,2,empty,testStorage,32);
        var boundaryConvex=CudaBoundaryMediumAbi.planPacket(19,17,8,2,empty,testStorage,2);
        long boundaryPaths=(long)boundaryWide.pixels()*boundaryWide.samples();
        check(boundaryWide.tilePixels()==boundaryWide.pixels()&&boundaryConvex.tilePixels()==boundaryConvex.pixels()
                &&boundaryWide.packetPathBytes()==288&&boundaryConvex.packetPathBytes()==48
                &&boundaryWide.bytes()-boundaryConvex.bytes()==boundaryPaths*240L
                &&CudaBoundaryMediumAbi.minimumPacketBytes(19,17,8,2,32)
                    -CudaBoundaryMediumAbi.minimumPacketBytes(19,17,8,2,2)==8L*240L,
                "boundary packet capacity reduces only bounded per-query scratch");
        var pbrWide=CudaPbrMediumAbi.plan(19,17,8,2,7,32,empty,testStorage);
        var pbrConvex=CudaPbrMediumAbi.plan(19,17,8,2,7,2,empty,testStorage);
        long pbrQueries=boundaryPaths*(pbrWide.connections()+1L);
        check(pbrWide.tilePixels()==pbrWide.pixels()&&pbrConvex.tilePixels()==pbrConvex.pixels()
                &&pbrWide.packetBytes()==288&&pbrConvex.packetBytes()==48
                &&pbrWide.bytes()-pbrConvex.bytes()==pbrQueries*240L
                &&CudaPbrMediumAbi.minimumBytes(19,17,8,2,7,32)
                    -CudaPbrMediumAbi.minimumBytes(19,17,8,2,7,2)==8L*(7+2)*240L,
                "PBR capacity reduces camera and light packet scratch while retaining ray records");
        for(int capacity=1;capacity<=32;capacity++){
            long minimum=CudaBoundaryMediumAbi.minimumPacketBytes(19,17,8,2,capacity);
            var p=CudaBoundaryMediumAbi.planPacket(19,17,8,2,empty,minimum,capacity);
            check(p.tilePixels()==1&&p.bytes()==minimum,"minimum/stride capacity "+capacity);
            check((p.transmittanceOffset()-p.stateOffset())==p.tilePaths()*p.packetPathBytes(),"no scratch overlap");
            var header=ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
            for(int i=0;i<32;i++)header.putInt(128+i*4,1000+i);
            CudaBoundaryMediumAbi.updateTile(header,p,0,1);
            check(header.getInt(108)==capacity,"capacity header");
            for(int i=0;i<32;i++)check(header.getInt(128+i*4)==1000+i,"full initial stack preserved");
        }
        var known=new MediumSceneIndex.NativeScene(List.of(),new int[0],24);
        check(known.packetCapacity(32)==24&&known.packetCapacity(2)==2,"triangle proof independent of maxEvents");
        check(new MediumSceneIndex.NativeScene(List.of(),new int[0]).packetCapacity(32)==32,"unknown geometry fallback");
        check(new MediumSceneIndex.NativeScene(List.of(),new int[0],0).packetCapacity(32)==1,"empty scene safe capacity");
        var cube=MeshAsset.triangles(1,new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},
                new int[]{0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,1,5,6,1,6,2,2,6,7,2,7,3,4,0,3,4,3,7},1);
        check(MediumConvexMeshBound.convexClosed(cube),"cube convex closed proof");
        var cubePositions=new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        var cubeIndices=new int[]{0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,1,5,6,1,6,2,2,6,7,2,7,3,4,0,3,4,3,7};
        var repeatedPositions=Arrays.copyOf(cubePositions,27);repeatedPositions[24]=1;repeatedPositions[25]=-1;repeatedPositions[26]=-1;
        var repeatedIndices=cubeIndices.clone();repeatedIndices[1]=8;
        check(MediumConvexMeshBound.convexClosed(MeshAsset.triangles(6,repeatedPositions,repeatedIndices,1)),"duplicate seam positions are welded exactly");
        var tetra=MeshAsset.triangles(2,new float[]{0,0,0,1,0,0,0,1,0,0,0,1},new int[]{0,2,1,0,1,3,1,2,3,2,0,3},1);
        check(MediumConvexMeshBound.convexClosed(tetra),"tetrahedron convex closed proof");
        var open=MeshAsset.triangles(3,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1);
        check(!MediumConvexMeshBound.convexClosed(open),"open mesh fallback");
        var degenerate=MeshAsset.triangles(4,new float[]{0,0,0,1,0,0,2,0,0},new int[]{0,1,2},1);
        check(!MediumConvexMeshBound.convexClosed(degenerate),"degenerate mesh fallback");
        var concavePositions=cubePositions.clone();concavePositions[18]=-1.5f;
        var concave=MeshAsset.triangles(5,concavePositions,cubeIndices,1);
        check(!MediumConvexMeshBound.convexClosed(concave),"concave mesh fallback");
        verifySceneBounds(cube,concave);
        System.out.println("PacketCapacitySelfTest passed: all 32 capacities, alignment, minimum residency, initial stack and proof fallback");
    }
    private static void verifySceneBounds(MeshAsset cube,MeshAsset concave) {
        String previous=System.getProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY);
        try {
            System.setProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY,"true");
            var residency=new VulkanSceneResidency();var index=new MediumSceneIndex();
            var boundary=MediumBoundary.volume(Medium.vacuum());var camera=CameraState.lookAt(4,0,0,4,0,-1).build();
            var mirrored=new AffineTransform(new float[]{-1,0,0,2,0,1,0,0,0,0,1,0});
            var initial=residency.prepare(SceneTransaction.builder(1).upsert(MaterialAsset.opaque(1,-1)).upsert(cube)
                    .upsert(SceneInstance.builder(10,1).transform(mirrored).mediumBoundary(boundary).build()).build());
            residency.commit(initial);index.sceneApplied(initial.changeSet());
            var request=RenderFrameRequest.builder(1,16,16,camera).minimumSceneRevision(1).build();
            var convex=index.nativeScene(request);
            check(convex.boundaryTriangleCount()==12&&convex.boundaryEventUpperBound()==2&&convex.packetCapacity(32)==2
                    &&convex.packetCapacity(1)==1,
                    "cached convex bound survives translated mirrored instances");
            int persistentSlot=convex.boundaries().get(0).slot();
            check(Arrays.equals(index.containingPoint(FramePrimitiveBatch.empty(),2,0,0),new int[]{persistentSlot})
                    &&index.containingPoint(FramePrimitiveBatch.empty(),0,0,0).length==0,
                    "translated mirrored persistent bounds select only their world-space interior");
            System.setProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY,"false");
            var legacy=index.nativeScene(request);
            check(legacy.boundaryEventUpperBound()==12&&legacy.packetCapacity(32)==12,"old triangle event bound remains available for same-artifact A/B");
            System.setProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY,"true");
            var movedTransform=new AffineTransform(new float[]{-1,0,0,12,0,1,0,0,0,0,1,0});
            var moved=residency.prepare(SceneTransaction.builder(2).upsert(SceneInstance.builder(10,1).transform(movedTransform)
                    .mediumBoundary(boundary).build()).build());
            residency.commit(moved);index.sceneApplied(moved.changeSet());
            check(index.containingPoint(FramePrimitiveBatch.empty(),2,0,0).length==0
                    &&Arrays.equals(index.containingPoint(FramePrimitiveBatch.empty(),12,0,0),new int[]{persistentSlot}),
                    "persistent transform updates invalidate world-space boundary bounds");
            var frameBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).mediumBoundary(boundary).build())
                    .add(PrimitiveInstance.builder(1).transform(mirrored).mediumBoundary(boundary).build()).build();
            var frameCamera=CameraState.lookAt(2,0,0,2,0,-1).build();
            var frameRequest=RenderFrameRequest.builder(3,16,16,frameCamera).minimumSceneRevision(2).primitiveBatch(frameBatch).build();
            var frameScene=index.nativeScene(frameRequest);
            check(frameScene.boundaryTriangleCount()==36&&frameScene.boundaryEventUpperBound()==6&&frameScene.packetCapacity(32)==6,
                    "scene bound includes the persistent volume and both overlapping frame-local convex volumes");
            check(Arrays.equals(frameScene.containing(),new int[]{VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT|1}),
                    "frame-local transformed bounds return the containing transient instance slot");
            var translatedFrame=new AffineTransform(new float[]{1,0,0,20,0,1,0,0,0,0,1,0});
            var mirroredFrame=new AffineTransform(new float[]{-1,0,0,22,0,1,0,0,0,0,1,0});
            var movedFrameBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).transform(translatedFrame)
                    .mediumBoundary(boundary).build()).add(PrimitiveInstance.builder(1).transform(mirroredFrame)
                    .mediumBoundary(boundary).build()).build();
            var movedFrameCamera=CameraState.lookAt(22,0,0,22,0,-1).build();
            var movedFrame=index.nativeScene(RenderFrameRequest.builder(4,16,16,movedFrameCamera)
                    .minimumSceneRevision(2).primitiveBatch(movedFrameBatch).build());
            check(movedFrame.boundaryEventUpperBound()==6
                    &&Arrays.equals(movedFrame.containing(),new int[]{VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT|1})
                    &&index.containingPoint(movedFrameBatch,2,0,0).length==0,
                    "next frame replaces transformed transient bounds and does not reuse the previous tree");
            float[] changedPositions=new float[concave.vertexCount()*3];concave.positions().get(changedPositions);
            int[] changedIndices=new int[concave.triangleCount()*3];concave.triangleIndices().get(changedIndices);
            var changedAsset=MeshAsset.triangles(1,changedPositions,changedIndices,1);
            var update=residency.prepare(SceneTransaction.builder(3).upsert(changedAsset).build());
            residency.commit(update);index.sceneApplied(update.changeSet());
            var changed=index.nativeScene(RenderFrameRequest.builder(5,16,16,camera).minimumSceneRevision(3).build());
            check(changed.boundaryTriangleCount()==12&&changed.boundaryEventUpperBound()==12&&changed.packetCapacity(32)==12,
                    "mesh asset replacement invalidates cached convex proof");
        } finally {
            if(previous==null)System.clearProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY);
            else System.setProperty(MediumSceneIndex.CONVEX_PACKET_PROPERTY,previous);
        }
    }
}
