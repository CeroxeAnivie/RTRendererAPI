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
        require(RtxptDelta.alphaEquivalent(base,TextureAsset.color(1,16,16,pixels.clone())),"Full identical generation preserves coverage");
        var alphaPixels=pixels.clone();alphaPixels[alphaPixels.length-1]=0;
        require(!RtxptDelta.alphaEquivalent(base,TextureAsset.color(1,16,16,alphaPixels)),"Full generation alpha change is not skipped");
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
        alphaPairs(base,next,transparent,pixels);
        resident.clear();var full=packet(old,scene,resident,pool);require(full.getInt(52)==0&&full.getInt(56)==pixels.length,"Missing resident parent falls back to a complete generation");
        inheritance(old,base,pixels,pool);
        System.out.println("HIGH_FRAMERATE_RESOURCES_CPU PASS residentPayload=0 sharedDeduplicated=true patchParent=true packetReuse=true imageInheritance=true samplerMetadata=true layoutFallback=true exactPixels=true identityPairMemo=true alphaMemoLocal=true");
    }finally{PACKETS.close();}}
    private static void alphaPairs(TextureAsset base,TextureAsset next,TextureAsset transparent,byte[] pixels){
        var memo=new RtxptDelta.AlphaPairs();
        for(int variant=0;variant<256;variant++)require(memo.equivalent(base,next),"Shared RGB patch changed alpha");
        require(memo.comparisons==1,"Shared material variants repeatedly scanned one exact texture pair");
        require(!memo.equivalent(next,transparent)&&!memo.equivalent(next,transparent)&&memo.comparisons==2,
                "Cached false must preserve a real alpha change without rescanning");
        var distinct=TextureAsset.color(1,16,16,pixels.clone());
        require(memo.equivalent(base,distinct)&&memo.comparisons==3,"Distinct immutable generations need their own exact comparison");
        require(memo.equivalent(distinct,base)&&memo.comparisons==4,"Pair identities and direction must remain distinct");
        require(memo.equivalent(base,base)&&memo.equivalent(null,null)&&!memo.equivalent(base,null)&&memo.comparisons==4,
                "Identity/null shortcuts must preserve coverage semantics");
        var fresh=new RtxptDelta.AlphaPairs();
        require(fresh.equivalent(base,next)&&fresh.comparisons==1,"Alpha results must not escape their prepare invocation");
    }
    private static void inheritance(RtxptScene old,TextureAsset base,byte[] pixels,RtxptMemoryResources.PacketPool pool){
        var resident=new HashMap<Long,TextureAsset>();resident.put(1L,base);
        var same=TextureAsset.color(1,16,16,pixels.clone());var scene=new RtxptScene(old);
        scene.retain(SceneTransaction.builder(2).upsert(same).build());
        var inherited=packet(old,scene,resident,pool);int compactBytes=inherited.remaining();
        require(inherited.getInt(4)==1&&inherited.getInt(52)==2&&inherited.getLong(56)==base.generation(),"Identical complete pixels inherit the exact resident generation");
        require(inherited.getLong(44)==same.generation(),"Inheritance advances the authored generation");
        int completeBytes=packet(old,scene,Map.of(),pool).remaining();
        require(completeBytes-compactBytes==pixels.length-4,"Inheritance omits exactly the complete pixel payload, retaining its base generation");
        var sampler=TextureAsset.builder(1,16,16).addressModes(TextureAsset.AddressMode.CLAMP_TO_EDGE,TextureAsset.AddressMode.REPEAT)
                .filter(TextureAsset.Filter.NEAREST).pixelsRgba8(pixels).build();
        scene=new RtxptScene(old);scene.retain(SceneTransaction.builder(2).upsert(sampler).build());
        var metadata=packet(old,scene,resident,pool);
        require(metadata.getInt(52)==2&&metadata.getLong(56)==base.generation()&&metadata.getInt(32)==1&&metadata.getInt(40)==0,"Sampler changes retain metadata while inheriting identical image pixels");
        var edited=same.patched(new TexturePatch(1,0,0,0,1,1,4,ByteBuffer.wrap(new byte[]{1,2,3,-1})));
        scene=new RtxptScene(old);scene.retain(SceneTransaction.builder(2).upsert(same).build());
        scene.retain(SceneTransaction.builder(3).upsert(edited).build());resident.put(1L,same);
        var patch=packet(old,scene,resident,pool);
        require(patch.getInt(52)==1&&patch.getLong(56)==same.generation(),"A later rectangle targets the inherited complete generation");
        resident.put(1L,base);
        require(packet(old,scene,resident,pool).getInt(52)==0,"A stale rectangle base cannot inherit the wrong resident image");
        var changed=pixels.clone();changed[changed.length-1]=0;
        var variants=List.of(TextureAsset.color(1,16,16,changed),
                TextureAsset.builder(1,16,16).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build(),
                TextureAsset.color(1,8,32,pixels));
        for(var texture:variants){scene=new RtxptScene(old);scene.retain(SceneTransaction.builder(2).upsert(texture).build());
            require(packet(old,scene,resident,pool).getInt(52)==0,"Pixel, color-space or image-layout changes require a complete image");}
    }
}
