package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Memory ownership, rectangle rows, generation rejection and file-free resource planning. */
public final class RtxptDynamicResourcesSelfTest {
    private static final RtxptMemoryResources.PacketPool PACKETS=new RtxptMemoryResources.PacketPool();
    static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException|ArithmeticException expected){return;}throw new AssertionError("Expected invalid input rejection");}
    static void patches(){
        byte[] pixels=new byte[(4*4+2*2+1)*4];Arrays.fill(pixels,(byte)31);
        var base=TextureAsset.builder(7,4,4).mipChainRgba8(3,pixels).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .addressModes(TextureAsset.AddressMode.CLAMP_TO_EDGE,TextureAsset.AddressMode.REPEAT).filter(TextureAsset.Filter.NEAREST).build();
        var input=ByteBuffer.allocate(20);input.position(3);input.put(new byte[]{1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12});input.limit(19).position(3);
        var patch=new TexturePatch(7,1,0,0,1,2,12,input);input.put(3,(byte)100);
        var next=base.patched(patch);next.validateReplacementOf(base);
        require(base.rgba8().get(64)==31&&next.rgba8().get(64)==1&&next.rgba8().get(72)==9,"Patch copies input and preserves old generation, using mip row stride");
        require(next.rgba8().get(68)==31&&next.rgba8().get(80)==31,"Untouched texels and mips survive");
        require(next.filter()==base.filter()&&next.colorSpace()==base.colorSpace()&&next.addressU()==base.addressU(),"Sampling metadata survives");
        reject(()->next.validateReplacementOf(null));reject(()->next.validateReplacementOf(next));
        reject(()->base.patched(new TexturePatch(7,1,2,0,1,1,4,ByteBuffer.allocate(4))));
        reject(()->new TexturePatch(7,0,0,0,2,1,4,ByteBuffer.allocate(8)));
        reject(()->new TexturePatch(7,0,0,0,2,2,8,ByteBuffer.allocate(15)));
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1).upsert(base).build());
        var updated=new RtxptScene(scene);updated.retain(SceneTransaction.builder(2).upsert(next).build());
        reject(()->new RtxptScene(updated).retain(SceneTransaction.builder(3).upsert(next).build()));
        var finalTexture=next.patched(new TexturePatch(7,2,0,0,1,1,4,ByteBuffer.wrap(new byte[]{4,3,2,1})));
        new RtxptScene(updated).retain(SceneTransaction.builder(3).upsert(finalTexture).build());
        require(scene.textures.get(7L)==base&&updated.textures.get(7L)==next,"Rejected updates preserve snapshots");
    }
    static void resources()throws Exception{
        var old=new RtxptScene();var scene=new RtxptScene();scene.retain(RtxptDynamicResourcesNativeSelfTest.initial(4));
        var items=scene.items(RenderFrameRequest.builder(0,64,64,CameraState.lookAt(0,0,4,0,0,0).build()).build());
        Path path=Files.createTempDirectory("resource-plan-");
        try{
            var result=RtxptDelta.prepare(path,old,scene,List.of(),items,new RtxptPersistentMap<>(),Map.of(),PACKETS);
            require(result.memory().isDirect()&&result.memory().getInt(0)==0x52445254,"Direct little-endian resource packet");
            require(result.work().exportedModels()==0&&result.directory()==null&&result.manifest()==null,"No file importer path");
            try(var files=Files.list(path)){require(files.findAny().isEmpty(),"Resource planning creates no files");}
            var mesh=scene.meshes.get(1L);var groups=RtxptAttributeBatch.groups(mesh);
            require(groups.size()==2&&groups.stream().mapToInt(g->g.corners().length).sum()==mesh.triangleCount()*3,"Multi-material primitive groups preserve every corner");
            boolean[] seen=new boolean[mesh.triangleCount()*3];
            for(var group:groups){var entry=RtxptAttributeBatch.convert("mesh",mesh,group,null,true,true);
                for(int i=0;i<group.corners().length;i++){int corner=group.corners()[i];require(!seen[corner],"Unique corner mapping");seen[corner]=true;
                    require(mesh.triangleMaterialIds().get(corner/3)==group.material()&&entry.colors()[i]==0xffa0b0c0,"Group and authored color preserved");}}
        }finally{Files.delete(path);}
    }
    public static void main(String[] args)throws Exception{try{patches();resources();System.out.println("DYNAMIC_RESOURCES_CPU PASS rows=true mips=true staleRejected=true snapshotIsolation=true noFiles=true primitiveGroups=true");}finally{PACKETS.close();}}
}
