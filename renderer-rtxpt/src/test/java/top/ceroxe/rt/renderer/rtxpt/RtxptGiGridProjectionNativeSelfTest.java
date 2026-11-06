package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptGiGridNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptGiGridNativeSelfTest.OUT;

/** Scaled actual RR over all depth conventions and explicit asymmetric jitter. */
public final class RtxptGiGridProjectionNativeSelfTest {
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);int w=385,h=257;long sequence=0;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(RtxptGiGridNativeSelfTest.plan(w,h));r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,1,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            int cases=0;long invalid=0;
            for(var depth:ExactProjectionState.DepthConvention.values())for(var jitter:new ExactProjectionState.JitterConvention[]{ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET,ExactProjectionState.JitterConvention.NDC_OFFSET}){
                double jx=jitter==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET?.35:.007,jy=jitter==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET?-.27:-.009;
                var projection=exact(w*2-1,h*2-1,depth,jitter,jx,jy,true,cases*.03,0,3);
                double[] direct=null;
                for(int divisor:new int[]{1,2}){
                    r.lightingReconstructionScale(divisor);
                    var frame=RenderFrameRequest.builder(sequence,w*2-1,h*2-1,CameraState.exactProjection(projection)).minimumSceneRevision(1).environment(EnvironmentState.neutral()).build();
                    RtxptNativeLightingReconstructionNativeSelfTest.submitLifecycle(r,frame);
                    try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==sequence,"Exact projection current identity");}
                    grid(r,divisor,w,h);var gain=image(r,"projection-"+cases+"-gain-"+divisor,0,w,h);
                    if(divisor==1)direct=gain;else require(Arrays.equals(direct,gain),"Projection/jitter/depth never changes direct with RR grid");sequence++;
                }
                var alpha=image(r,"projection-"+cases+"-alpha",16,w,h);var gi=image(r,"projection-"+cases+"-gi",1,w,h);
                require(Arrays.equals(alpha,image(r,"projection-"+cases+"-raw-alpha",15,w,h)),"Projection current alpha exact");
                for(int i=0;i<alpha.length;i+=3)if(alpha[i]==0){invalid++;require(gi[i]==0&&gi[i+1]==0&&gi[i+2]==0,"Disoccluded/invalid current receiver has exact zero GI");}
                if(cases==7)guides(r,"projection-guides",w,h);cases++;
            }
            require(invalid>0,"Fixture includes exposed invalid pixels");
            Files.writeString(OUT.resolve("projection-result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"depthConventions",4,"jitterConventions",2,"asymmetric",true,"frames",sequence,"invalidPixels",invalid,"actualRR",true,"directByteExact",true)));
            System.out.println("GI_GRID_PROJECTION PASS frames="+sequence+" depth=4 jitter=2 asymmetric=true invalidZero="+invalid+" currentGuides=true directByteExact=true");
        }
    }
}
