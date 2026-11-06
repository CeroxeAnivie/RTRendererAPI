package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;

/** Actual low-grid RR, unchanged direct, current guides, geometric edges and lifecycle. */
public final class RtxptGiGridNativeSelfTest {
    static final Path OUT=Path.of(System.getProperty("giGrid.output","build/reports/gi-grid")).toAbsolutePath();
    static final Set<NativeLightingPlan.Signal> SIGNALS=EnumSet.allOf(NativeLightingPlan.Signal.class);
    static NativeLightingPlan plan(int w,int h){return new NativeLightingPlan(w,h,SIGNALS,1,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);}
    static RenderFrameRequest request(long sequence,long revision,double x,int w,int h){
        return RenderFrameRequest.builder(sequence,w*2,h*2,CameraState.lookAt(x,0,3,x,0,0)
            .verticalFieldOfViewDegrees(52).aspectRatio((double)w/h).build()).minimumSceneRevision(revision)
            .environment(EnvironmentState.neutral()).clock(FrameClock.of(sequence*16_666_667,16_666_667)).build();
    }
    static void submit(RtxptRenderer r,long s,long revision,double x,int w,int h)throws Exception{
        RtxptNativeLightingReconstructionNativeSelfTest.submitLifecycle(r,request(s,revision,x,w,h));
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            var f=lease.frame();require(f.sequence()==s&&f.sceneRevision()==revision&&f.width()==w&&f.height()==h,"Current lighting identity and output extent");
        }
    }
    static double[] image(RtxptRenderer r,String name,int signal,int w,int h)throws Exception{
        var p=OUT.resolve(name+".pfm");r.captureNativeLighting(p,signal);var v=RtxptLightTreeNativeSelfTest.linear(p);
        require(v.length==w*h*3,"Exact captured grid "+name);for(double d:v)require(Double.isFinite(d)&&d>=-65504&&d<=65504,"Finite HDR "+name);return v;
    }
    static void grid(RtxptRenderer r,int divisor,int w,int h){
        var g=r.lightingReconstructionGrid();require(g.divisor()==divisor&&g.inputWidth()==(w+divisor-1)/divisor&&g.inputHeight()==(h+divisor-1)/divisor
            &&g.outputWidth()==w&&g.outputHeight()==h&&g.resourceBytes()>0,"Actual independent grid and logical resource bytes: "+g);
        require(r.lightingReconstruction().width()==w&&r.lightingReconstruction().height()==h,"Existing state retains output extent semantics");
        require(r.lightingReconstruction().actual()==NativeLightingReconstruction.DLSS_RR,"Real RR executed at reported grid");
    }
    static void guides(RtxptRenderer r,String name,int w,int h)throws Exception{
        int lw=(w+1)/2,lh=(h+1)/2;
        for(int[] pair:new int[][]{{9,10},{3,11},{5,12},{6,13},{7,14}}){
            var full=image(r,name+"-full-"+pair[0],pair[0],w,h);var low=image(r,name+"-low-"+pair[1],pair[1],lw,lh);
            for(int y=0;y<lh;y++)for(int x=0;x<lw;x++){
                int sx=Math.min((int)((x+.5)*w/lw),w-1),sy=h-1-Math.min((int)((lh-1-y+.5)*h/lh),h-1);
                for(int c=0;c<3;c++)require(low[(y*lw+x)*3+c]==full[(sy*w+sx)*3+c],"GI and every guide share the exact selected receiver");
            }
        }
        for(int signal:new int[]{17,18})for(double v:image(r,name+"-zero-"+signal,signal,lw,lh))require(v==0,"Reduced diffuse RR has zero specular guides");
        require(Arrays.equals(image(r,name+"-raw-alpha",15,w,h),image(r,name+"-output-alpha",16,w,h)),"Current-frame alpha/validity is exact");
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);long s=0;int w=384,h=256;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(w,h));require(r.lightingReconstructionGrid().divisor()==1,"API default divisor 1");
            for(int bad:new int[]{0,3,-1}){boolean rejected=false;try{r.lightingReconstructionScale(bad);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Invalid divisor rejected atomically");}
            r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int i=0;i<12;i++)submit(r,s++,1,0,w,h);grid(r,1,w,h);
            var gain=image(r,"scale1-gain",0,w,h);image(r,"scale1-gi",1,w,h);
            r.lightingReconstructionScale(2);for(int i=0;i<12;i++)submit(r,s++,1,0,w,h);grid(r,2,w,h);
            require(Arrays.equals(gain,image(r,"scale2-gain",0,w,h)),"RR grid never changes direct gain");
            var gi=image(r,"scale2-gi",1,w,h);var raw=image(r,"scale2-raw",9,w,h);int middle=((h/2)*w+w/2)*3;
            require(raw[middle]>.1,"Nonzero real one-bounce GI");near(gi[middle],raw[middle],raw[middle]*.4,"Low RR preserves GI energy");
            near(gi[middle+1],0,.02,"No invented green");near(gi[middle+2],0,.02,"No invented blue");guides(r,"static",w,h);
            var edge=r.nativeLightingGridProbe();require(edge[0]==128&&edge[1]==0&&edge[2]==0&&edge[3]==4&&edge[4]==0,"Actual GPU geometric/depth/normal/albedo boundary and alpha fixture: "+Arrays.toString(edge));
            submit(r,s++,1,.05,w,h);guides(r,"camera-motion",w,h);
            var mv=image(r,"moving-low-motion",13,w/2,h/2);require(Arrays.stream(mv).anyMatch(v->Math.abs(v)>.001),"Normalized motion reaches low-grid RR");
            r.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,.1f,0,1,0,0,0,0,1,0})).build()).build());
            submit(r,s++,2,.05,w,h);guides(r,"object-motion",w,h);image(r,"object-motion-gi",1,w,h);
            long resets=r.lightingReconstruction().historyResets();r.resetLightingReconstruction();submit(r,s++,2,.05,w,h);
            require(r.lightingReconstruction().historyResets()==resets+1,"Explicit scaled reset exactly once");
            // A live previous output lease survives odd resize and another grid.
            RtxptNativeLightingReconstructionNativeSelfTest.submitLifecycle(r,request(s++,2,.05,w,h));var previous=r.acquireLightingFrame().orElseThrow();
            try{w=385;h=257;r.nativeLightingPlan(plan(w,h));submit(r,s++,2,.05,w,h);grid(r,2,w,h);guides(r,"odd",w,h);
                require(previous.frame().width()==384&&previous.frame().height()==256,"Leased old HDR generation keeps its extent");
            }finally{previous.close();}
            long evaluations=r.lightingReconstruction().evaluatedFrames();r.lightingReconstruction(NativeLightingReconstruction.NONE);submit(r,s++,2,.05,w,h);
            require(r.lightingReconstruction().evaluatedFrames()==evaluations&&r.lightingReconstructionGrid().resourceBytes()==0,"Disable releases RR allocation and stops evaluations");
            r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);submit(r,s++,2,.05,w,h);grid(r,2,w,h);
            r.lightingReconstructionScale(1);submit(r,s++,2,.05,w,h);grid(r,1,w,h);
            r.awaitIdle();require(r.nativeLightingExecution().fullBeautyFrames()==0,"Independent lighting never invokes beauty");
            Files.writeString(OUT.resolve("result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"frames",s,"directByteExact",true,"actualRR",true,
                "edgeProbe",edge,"oddExtent",List.of(w,h),"oldLeasePreserved",true,"motionNormalized",true,"rawAlphaExact",true,"mode","DLAA","finalGrid",r.lightingReconstructionGrid().toString())));
            System.out.println("GI_GRID PASS frames="+s+" RR=real lowExtent=192x128 odd=193x129 gainByteExact=true currentGuides=true edgeProbe=128 alphaExact=true oldLease=true");
        }
    }
}
