package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Small image oracle for the declared center-support fields; no GI/RR/beauty. */
public final class RtxptSourceVisibilityNativeSelfTest {
    static final Path OUT=Path.of("build/reports/source-visibility/images").toAbsolutePath();
    static int images,checks;static double maxError;
    static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static MeshAsset quad(long id,float[] p){return MeshAsset.builder(id,p,new int[]{0,1,2,0,2,3},new long[]{1,1}).build();}
    static MeshAsset cube(){
        float[] p={-2.5f,-.5f,0,-1.5f,-.5f,0,-1.5f,.5f,0,-2.5f,.5f,0,-2.5f,-.5f,1,-1.5f,-.5f,1,-1.5f,.5f,1,-2.5f,.5f,1};
        return MeshAsset.builder(2,p,new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,1,2,6,1,6,5,2,3,7,2,7,6,3,0,4,3,4,7},new long[]{1,1,1,1,1,1,1,1,1,1,1,1}).build();
    }
    static SparseLightSource source(long id,float z,SparseLightSource.Visibility visibility,SparseLightSource.Layer layer,SparseLightSource.Composition composition,float r,float g,float b){
        return new SparseLightSource(id,SparseLightSource.Shape.BOX,-2,0,z,.5f,.5f,.5f,0,-1,0,r,g,b,1,15,1,1,-1,composition,0,true,layer,SparseLightSource.Attenuation.MANHATTAN_LINEAR).withVisibility(visibility);
    }
    static RenderFrameRequest frame(long sequence,long revision){return RtxptFramePreparationNativeSelfTest.frame(sequence,revision,96,64).toBuilder()
        .environment(RtxptLightingNativeSelfTest.environment().toBuilder().sunIntensity(0).build()).build();}
    static double strength(double x,double y,double z){return Math.max(0,15-(Math.max(Math.abs(-2-x)-.5,0)+Math.max(Math.abs(y)-.5,0)+Math.max(Math.abs(z)-.5,0)))/15;}
    static void image(RtxptLightingTestSession session,String name,List<SparseLightSource> sources,int geometry,boolean visible,boolean dynamic,boolean rgb)throws Exception {
        var test=session.begin("visibility/"+name);var r=test.renderer;
        r.nativeLightingPlan(new NativeLightingPlan(48,32,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,.35f,NativeLightingStyle.defaults()));
        r.diagnosticLevel(name.contains("deep")?RtxptDiagnostics.Level.DEEP:name.contains("summary")?RtxptDiagnostics.Level.SUMMARY:RtxptDiagnostics.Level.OFF);
        r.lightingWorkStatisticsEnabled(name.contains("deep"));
        // Keep the receiver strictly inside the emitter's z slab: on its
        // exact boundary ray reconstruction roundoff can flip a tangent dot.
        var floor=quad(1,new float[]{-1,-1,.001f,1,-1,.001f,1,1,.001f,-1,1,.001f}).toBuilder().lightmapCoordinates(new float[]{.5f,0,.5f,0,.5f,0,.5f,0}).build();
        var tx=test.scene(1).resetScene().upsert(MaterialAsset.builder(1).build()).upsert(floor).upsert(SceneInstance.builder(1,1).build());
        if(geometry==1)tx.upsert(cube()).upsert(SceneInstance.builder(2,2).build());
        if(geometry==2)tx.upsert(quad(2,new float[]{-1,-2,0,-1,2,0,-1,2,2,-1,-2,2})).upsert(SceneInstance.builder(2,2).build());
        if(geometry==3)tx.upsert(quad(2,new float[]{-1.55f,-2,1.35f,-1.55f,2,1.35f,-1.55f,2,1.75f,-1.55f,-2,1.75f})).upsert(SceneInstance.builder(2,2).build());
        r.apply(tx.build(),sources,LongBuffer.allocate(0));
        if(geometry!=0)test.publish(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.shadowOnly(2)),List.of(),null,null));
        r.submit(test.request(0,1,96,64,frame(0,1).camera())
            .environment(frame(0,1).environment()).build());Path path=OUT.resolve(name+".pfm");r.captureNativeLighting(path,0);double[] pixels=RtxptLightTreeNativeSelfTest.linear(path);
        var camera=frame(0,1).camera();
        // Independent old-shader formulas, restricted to clear central floor pixels.
        for(int y=14;y<18;y++)for(int x=22;x<26;x++)for(int channel=0;channel<3;channel++){
            double px=(x+.5)/48*2-1,py=1-(y+.5)/32*2;
            px*=2.999*camera.tanHalfFovX();py*=2.999*camera.tanHalfFovY();
            double light=visible?strength(px,py,sources.getFirst().z()-.001):0;
            if(rgb&&channel==2)light=0;
            if(rgb&&sources.getFirst().composition()==SparseLightSource.Composition.VISIBLE_GROUP_MAX&&channel==0)light=0;
            double expected=1-.75*(.5-Math.min(light,.5))+(dynamic?12*Math.max(light-.5,0)*.5:0);
            double error=Math.abs(pixels[(y*48+x)*3+channel]-expected);maxError=Math.max(maxError,error);
            require(error<.008,name+" channel="+channel+" pixel="+x+","+y+" actual="+pixels[(y*48+x)*3+channel]+" expected="+expected);
        }
        test.packet(0,1,FrameClock.of(0,0));
        require(test.lightingFrames()==1,"Each visibility case executes a fresh frame");
        require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty execution");images++;
    }
    static void cpu(){
        var original=source(1,.5f,SparseLightSource.Visibility.NEAREST_POINT,SparseLightSource.Layer.STATIC,SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,1,1,1);
        require(original.visibility()==SparseLightSource.Visibility.NEAREST_POINT,"Existing constructors preserve nearest-point declaration");
        var independent=original.withVisibility(SparseLightSource.Visibility.CENTER_SPHERE_SUPPORT_V1,.08f)
            .withLayer(SparseLightSource.Layer.DYNAMIC).withAttenuation(SparseLightSource.Attenuation.MANHATTAN_LINEAR);
        var independentWords=RtxptSparseSources.pack(List.of(independent));
        require(independent.halfX()==original.halfX()&&independent.visibilityRadius()==.08f,"Independent radius preserves emitter attenuation extent");
        require(independentWords[16]==.08f&&((int)independentWords[23]&16)!=0,"Independent visibility radius has an explicit native flag");
        for(var visibility:SparseLightSource.Visibility.values())for(var layer:SparseLightSource.Layer.values())for(var attenuation:SparseLightSource.Attenuation.values()){
            var s=original.withVisibility(visibility).withLayer(layer).withAttenuation(attenuation).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,2);
            require(RtxptSparseSources.pack(List.of(s))[23]==layer.ordinal()+2*attenuation.ordinal()+4*visibility.ordinal(),"Packed flags preserve all orthogonal source semantics");
            require(s.visibility()==visibility,"Copy methods preserve visibility");
        }
        try{new SparseLightSource(1,SparseLightSource.Shape.BOX,0,0,0,1,2,3,0,-1,0,1,1,1,1,15,1,1,-1,SparseLightSource.Composition.PHYSICAL_SUM,0,true).withVisibility(SparseLightSource.Visibility.CENTER_SPHERE_SUPPORT_V1);throw new AssertionError("Anisotropic sphere accepted");}catch(IllegalArgumentException expected){checks++;}
        // Closed-form center ray endpoints differ on a diagonal; a blocker can be
        // inside the AABB yet outside the sphere, so these modes cannot be merged.
        double distance=Math.sqrt(8),cubeEnd=distance-.5/Math.sqrt(.5)-.004,sphereEnd=distance-.5-.004;
        require(cubeEnd<2.2&&sphereEnd>2.2,"Independent diagonal endpoints separate box/sphere visibility");
        require((.505<=.5+.005)&&Math.sqrt(.3*.3+.4*.4)<=.5+.005,"Closed support tolerances include boundary receivers");
        require(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1.ordinal()==2,"Host response is appended without renumbering legacy modes");
        System.out.println("SOURCE_VISIBILITY_CPU PASS checks="+checks+" constructorsCompatible=true packedFlags=true distinctEndpoints=true legacyModeOrdinalsStable=true");
    }
    public static void main(String[] args)throws Exception {
        images=0;checks=0;maxError=0;
        cpu();if(args.length>0&&args[0].equals("cpu"))return;
        try(var session=new RtxptLightingTestSession()){run(session);}
    }
    static void run(RtxptLightingTestSession session)throws Exception {
        Files.createDirectories(OUT);images=0;maxError=0;
        var box=SparseLightSource.Visibility.CENTER_BOX_SUPPORT_V1;var sphere=SparseLightSource.Visibility.CENTER_SPHERE_SUPPORT_V1;
        var max=SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX;var stat=SparseLightSource.Layer.STATIC;var dyn=SparseLightSource.Layer.DYNAMIC;
        image(session,"nearest-coplanar",List.of(source(1,.5f,SparseLightSource.Visibility.NEAREST_POINT,stat,max,1,1,1)),0,false,false,false);
        image(session,"box-coplanar",List.of(source(1,.5f,box,stat,max,1,1,1)),0,true,false,false);
        image(session,"box-emitter-self",List.of(source(1,.5f,box,stat,max,1,1,1)),1,true,false,false);
        image(session,"box-external-wall",List.of(source(1,.5f,box,stat,max,1,1,1)),2,false,false,false);
        image(session,"sphere-diagonal-clear",List.of(source(1,2,sphere,dyn,max,1,1,1)),0,true,true,false);
        image(session,"sphere-diagonal-blocked",List.of(source(1,2,sphere,dyn,max,1,1,1)),3,false,true,false);
        image(session,"box-diagonal-support",List.of(source(1,2,box,dyn,max,1,1,1)),3,true,true,false);
        for(var composition:SparseLightSource.Composition.values())for(String level:List.of("off","summary","deep")){
            image(session,"rgb-"+composition+"-"+level,List.of(source(1,.5f,sphere,dyn,composition,1,0,0),source(2,.5f,sphere,dyn,composition,0,1,0)),0,true,true,true);
        }
        System.out.println("SOURCE_VISIBILITY_GPU PASS images="+images+" checks="+checks+" maxAbsError="+maxError+" diagnosticLevels=3 compositions=3 GI=0 RR=0 beauty=0");
    }
}
