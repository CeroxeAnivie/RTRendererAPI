package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;

/** Only the new host-response, independent-radius and wide-compact contracts. */
public final class RtxptHostResponseNativeSelfTest {
    private static SparseLightSource source(SparseLightSource.Layer layer,float radius,float intensity,boolean shadow){
        return new SparseLightSource(9,SparseLightSource.Shape.BOX,0,0,1,2,2,2,0,-1,0,.8f,.5f,.2f,
            intensity,15,1,1,-1,SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0,shadow,layer,
            SparseLightSource.Attenuation.MANHATTAN_LINEAR).withVisibility(SparseLightSource.Visibility.CENTER_SPHERE_SUPPORT_V1,radius);
    }
    private static void release(RtxptRenderer r){try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().planes().size()==1,"Direct-only output remains one plane");}}
    public static void main(String[] arguments)throws Exception{
        Files.createDirectories(OUT);float[] values=new float[768];
        for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++)for(int c=0;c<3;c++)values[(sky*16+local)*3+c]=1/255f+.9f*local/15f+.05f*sky/15f;
        var response=new NativeLightingHostResponse(values);
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,.75f,12,.55f,.18f,.8f,.9f,1,0,255,1);
        var plan=new NativeLightingPlan(W,H,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,style,SUN_OFF,null,true);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan);
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,255,255,255)).upsert(plane(1,1,2,0,.2f,.4f,false))
                .upsert(SceneInstance.builder(1,1).build()).build(),List.of(source(SparseLightSource.Layer.STATIC,.08f,1,false)),LongBuffer.allocate(0));
            try{r.submit(frame(0,1));throw new AssertionError("Response mode accepted an unpublished table");}catch(IllegalStateException expected){require(expected.getMessage().contains("NativeLightingHostResponse"),"Missing table has a precise error");}
            r.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(),null,null,response));
            r.submit(frame(0,1));release(r);var placed=capture(r,"host-response-static",0);
            for(int c=0;c<3;c++)near(placed[CENTER+c],response.directGain(style,c,.2f,.4f,1,0,new float[]{.8f,.5f,.2f}[c],0,true,0,1),.003,"Static host response follows the supplied RGB table");
            r.apply(SceneTransaction.builder(2).build(),List.of(source(SparseLightSource.Layer.DYNAMIC,.08f,1,false)),LongBuffer.allocate(0));
            r.submit(frame(1,2));release(r);var held=capture(r,"host-response-held",0);
            for(int c=0;c<3;c++)near(held[CENTER+c],placed[CENTER+c],.003,"Static and dynamic use the same response");
            r.apply(SceneTransaction.builder(3).build(),List.of(source(SparseLightSource.Layer.DYNAMIC,.16f,1,false)),LongBuffer.allocate(0));
            r.submit(frame(2,3));release(r);var dropped=capture(r,"host-response-dropped",0);
            for(int c=0;c<3;c++)near(dropped[CENTER+c],held[CENTER+c],.003,"Visibility radius does not change attenuation");
            r.apply(SceneTransaction.builder(4).build(),List.of(source(SparseLightSource.Layer.DYNAMIC,.08f,.1f,false)),LongBuffer.allocate(0));
            r.submit(frame(3,4));release(r);var low=capture(r,"host-response-native-preserved",0);
            for(int c=0;c<3;c++)near(low[CENTER+c],1,.001,"Uncaptured static baseline is preserved");
            r.apply(SceneTransaction.builder(5).upsert(plane(2,1,2,.5f,0,0,false)).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(SparseLightSource.Layer.STATIC,.08f,1,true)),LongBuffer.allocate(0));
            r.publishLightingAttributes(new NativeLightingPublication(2,List.of(NativeLightingPublication.Instance.shadowOnly(2)),List.of(),null,null));
            r.submit(frame(4,5));release(r);var hidden=capture(r,"host-response-shadow",0);
            for(int c=0;c<3;c++)near(hidden[CENTER+c],response.directGain(style,c,.2f,.4f,1,0,0,0,true,0,1),.003,"Occluded static candidate removes its baked contribution");
            long blases=r.retainedUpdateTimings().builtBlases();
            r.publishLightingAttributes(new NativeLightingPublication(3,List.of(),List.of(),null,null,response));
            r.submit(frame(5,5));release(r);require(r.retainedUpdateTimings().builtBlases()==blases,"Response-only update does not rebuild geometry");
            r.nativeLightingPlan(plan.withEncoding(NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8));r.submit(frame(6,5));
            try(var lease=r.acquireLightingFrame().orElseThrow()){
                require(lease.frame().encoding()==NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8&&lease.frame().planes().getFirst().format()==LightingFrameLease.Format.RGBA8_UNORM,"Wide response encoding reaches actual packet metadata");
            }
            var error=r.nativeLightingCompactProbe();require(error[0]<.003908&&error[1]<.023&&error[2]<.004&&error[3]==0,"Wide GPU encoder quantization and neutral bounds");
            System.out.println("HOST_RESPONSE_GPU PASS frames=7 rgb=true staticDynamic=true radiusIndependent=true coveredShadow=true responseOnlyNoBlas=true compactMax=255 relativeError="+error[1]);
        }
    }
}
