package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Native memory generations: pitched patches, mips, alpha/RGB, resize and rejected stale bases. */
public final class RtxptTexturePatchNativeSelfTest {
    static TextureAsset texture(int width,int mips,int alpha){
        byte[] pixels=new byte[(int)TextureAsset.requiredByteCount(width,width,mips)];Arrays.fill(pixels,(byte)255);
        for(int i=3;i<pixels.length;i+=4)pixels[i]=(byte)alpha;
        return TextureAsset.builder(7,width,width).mipChainRgba8(mips,pixels).addressMode(TextureAsset.AddressMode.CLAMP_TO_EDGE).filter(TextureAsset.Filter.NEAREST).build();
    }
    static TextureAsset patch(TextureAsset base,int mip,int alpha,int red){
        int width=base.mipWidth(mip),height=base.mipHeight(mip),pitch=width*4+12;var data=ByteBuffer.allocate(pitch*height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)data.put(y*pitch+x*4,(byte)red).put(y*pitch+x*4+1,(byte)255).put(y*pitch+x*4+2,(byte)255).put(y*pitch+x*4+3,(byte)alpha);
        return base.patched(new TexturePatch(7,mip,0,0,width,height,pitch,data));
    }
    static void frameCheck(RtxptRenderer r,long revision,String name,double expected)throws Exception{
        r.submit(frame(revision-1,revision));near(capture(r,"patch-"+name,0)[CENTER],expected,.002,name);packet(r,revision-1,revision,FrameClock.of(0,0));
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);var current=texture(8,4,255);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);r.nativeLightingPlan(directPlan(SUN_ON));
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(current)
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
            publication(r,1,NativeLightingPublication.Instance.shadowOnly(2));frameCheck(r,1,"initial",.45);
            var stale=current;current=patch(current,0,0,255);r.apply(SceneTransaction.builder(2).upsert(current).build());frameCheck(r,2,"pitched-alpha-open",1);
            long blases=r.retainedUpdateTimings().builtBlases();current=patch(current,0,0,127);r.apply(SceneTransaction.builder(3).upsert(current).build());frameCheck(r,3,"rgb-only",1);
            require(r.retainedUpdateTimings().builtBlases()==blases,"RGB-only patches preserve coverage BLAS");
            try{r.apply(SceneTransaction.builder(4).upsert(patch(stale,0,255,1)).build());throw new AssertionError("Stale base accepted");}catch(IllegalArgumentException expected){}
            current=patch(current,0,255,255);r.apply(SceneTransaction.builder(4).upsert(current).build());frameCheck(r,4,"failure-recovery-alpha-restore",.45);
            current=patch(current,2,255,100);r.apply(SceneTransaction.builder(5).upsert(current).build());frameCheck(r,5,"mip-only",.45);
            long bytes=r.dynamicResourceStatistics().textureUploadBytes();
            current=patch(current,2,255,100);r.apply(SceneTransaction.builder(6).upsert(current).build());frameCheck(r,6,"identical-no-upload",.45);
            require(r.dynamicResourceStatistics().textureUploadBytes()==bytes,"Identical pixels do not upload");
            current=texture(4,2,0);r.apply(SceneTransaction.builder(7).upsert(current).build());frameCheck(r,7,"extent-mip-change",1);
            r.apply(SceneTransaction.builder(8).removeInstance(2).removeMesh(2).removeMaterial(2).removeTexture(7).build());frameCheck(r,8,"remove",1);
            current=texture(8,4,255);r.apply(SceneTransaction.builder(9).upsert(current)
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(2,2,4,1,0,0,false)).upsert(SceneInstance.builder(2,2).build()).build());
            publication(r,2,NativeLightingPublication.Instance.shadowOnly(2));frameCheck(r,9,"restore-new-generation",.45);
            require(r.dynamicResourceStatistics().deviceDrains()==0,"No resource device drains");
            require(r.dynamicResourceStatistics().attributeClears()==1,"Only initial attributes cleared");
        }
        System.out.println("TEXTURE_PATCH_NATIVE PASS cases=9 pitchedRows=true mips=true rgbPreservesBlas=true alphaRestores=true staleRecovery=true resize=true removeRestore=true");
    }
}
