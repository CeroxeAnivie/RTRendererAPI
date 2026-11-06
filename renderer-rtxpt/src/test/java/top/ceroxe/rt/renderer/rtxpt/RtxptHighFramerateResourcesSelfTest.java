package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** New transport cases: resident references, direct packet reuse and generation-checked rectangles. */
public final class RtxptHighFramerateResourcesSelfTest {
    private static final RtxptMemoryResources.PacketPool PACKETS=new RtxptMemoryResources.PacketPool();
    static ByteBuffer packet(RtxptScene old,RtxptScene scene,Map<Long,TextureAsset> resident,RtxptMemoryResources.PacketPool pool){
        return RtxptMemoryResources.pack(old,scene,List.of(),List.of(new RtxptScene.Variant(1,null,1)),List.of(),List.of(),List.of(),resident,pool);
    }
    public static void main(String[] args){try{
        var old=new RtxptScene();byte[] pixels=new byte[16*16*4];Arrays.fill(pixels,(byte)255);
        var base=TextureAsset.color(1,16,16,pixels);
        old.retain(SceneTransaction.builder(1).upsert(base).upsert(MaterialAsset.builder(1).baseColorTextureId(1).normalTextureId(1).build()).build());
        var scene=new RtxptScene(old);scene.retain(SceneTransaction.builder(2).upsert(old.materials.get(1L).toBuilder().roughness(.71f).build()).build());
        var pool=PACKETS;var resident=new HashMap<Long,TextureAsset>();resident.put(1L,base);
        var first=packet(old,scene,resident,pool);require(first.getInt(4)==0,"Material-only packet transfers no resident texture payload");
        var second=packet(old,scene,resident,pool);require(first.equals(second),"Reused packet has an identical bounded payload");
        var rect=new TexturePatch(1,0,3,5,1,2,8,ByteBuffer.wrap(new byte[]{1,2,3,-1,0,0,0,0,4,5,6,-1}));
        var next=base.patched(rect);scene=new RtxptScene(old);scene.retain(SceneTransaction.builder(2).upsert(next).build());
        var patch=packet(old,scene,resident,pool);
        require(patch.getInt(4)==1&&patch.getLong(44)==next.generation()&&patch.getInt(52)==1,"Shared texture encoded once as a patch");
        require(patch.getLong(56)==base.generation()&&patch.getInt(88)==12,"Parent generation and row-padded rectangle retained");
        require(RtxptDelta.alphaEquivalent(base,next),"RGB rectangle scans only changed alpha bytes");
        var transparent=next.patched(new TexturePatch(1,0,3,5,1,1,4,ByteBuffer.wrap(new byte[]{1,2,3,0})));
        require(!RtxptDelta.alphaEquivalent(next,transparent),"Coverage delta detects current patch alpha");
        resident.clear();var full=packet(old,scene,resident,pool);require(full.getInt(52)==0&&full.getInt(56)==pixels.length,"Missing resident parent falls back to a complete generation");
        var data=new long[20];data[0]=7;data[2]=1;data[3]=16;data[4]=1;data[5]=2;data[9]=100;data[10]=19;
        var trace=RtxptDiagnostics.decodeProtocol(data);require(trace.events().size()==1&&trace.events().getFirst().sequence()==19,"Protocol fixed-width decoding");
        System.out.println("HIGH_FRAMERATE_RESOURCES_CPU PASS residentPayload=0 sharedDeduplicated=true patchParent=true packetReuse=true protocol=true");
    }finally{PACKETS.close();}}
}
