package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import java.nio.LongBuffer;
import java.nio.file.Files;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** New coverage only: textured tint, vertex-alpha cutout and altered DEEP scheduling. */
public final class RtxptLightingAttributeNativeSelfTest {
    static MeshAsset colored(int alpha){int c=(alpha<<24)|0xffffff;return plane(2,2,4,1,0,0,false).toBuilder().vertexColorsRgba8(new int[]{c,c,c,c}).build();}
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);var cases=args.length==0?List.of("alpha","texture","deep"):List.of(args);
        for(var value:cases)if(!List.of("alpha","texture","deep","timings").contains(value))throw new IllegalArgumentException(value);
        try(var session=new RtxptLightingTestSession()) {
            for(var value:cases)run(value,session);
        }
        System.out.println("RTXPT_ADDITIONAL PASS cases="+cases);
    }
    static void run(String value,RtxptLightingTestSession session)throws Exception {
        var test=session.begin("attributes/"+value);
        switch(value){case "alpha"->alpha(test);case "texture"->texture(test);case "deep"->deep(test);
            case "timings"->timings(test);default->throw new IllegalArgumentException(value);}
    }
    static void alpha(RtxptLightingTestSession.Case test)throws Exception{
        var r=test.renderer;
        r.nativeLightingPlan(directPlan(SUN_ON));
        r.apply(test.scene(1).resetScene().upsert(material(1,255,255,255))
            .upsert(MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
            .upsert(plane(1,1,2,0,0,1,false)).upsert(colored(127))
            .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
        test.publication(1,NativeLightingPublication.Instance.shadowOnly(2));r.submit(test.frame(0,1));
        near(capture(r,"alpha-below",0)[CENTER],1,.001,"Vertex alpha below cutout does not shadow");var before=r.retainedUpdateTimings();
        r.apply(test.scene(2).upsert(colored(128)).build());r.submit(test.frame(1,2));
        near(capture(r,"alpha-above",0)[CENTER],.45,.001,"Vertex alpha above cutout shadows");
        r.apply(test.scene(3).upsert(colored(0)).build());r.submit(test.frame(2,3));
        near(capture(r,"alpha-restored",0)[CENTER],1,.001,"Clearing vertex alpha restores visibility");
        require(r.retainedUpdateTimings().builtBlases()==before.builtBlases(),"Vertex alpha uses nonopaque query without BLAS rebuild");
        test.packet(2,3,FrameClock.of(0,0));System.out.println("RTXPT_VERTEX_ALPHA PASS threshold=127,128 restored=0 blasDelta=0");
    }
    static void texture(RtxptLightingTestSession.Case test)throws Exception{
        var r=test.renderer;
        r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
        var bounce=plane(2,2,4,1,0,0,false).toBuilder().vertexColorsRgba8(new int[]{rgba(255,0,0),rgba(255,0,0),rgba(255,0,0),rgba(255,0,0)}).build();
        r.apply(test.scene(1).resetScene().upsert(material(1,128,128,128))
            .upsert(TextureAsset.color(7,1,1,new byte[]{-1,0,-1,-1}))
            .upsert(MaterialAsset.builder(2).baseColorTextureId(7).baseColorRgba8(rgba(128,255,255)).build())
            .upsert(plane(1,1,2,0,0,0,false)).upsert(bounce)
            .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
        test.publication(1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));r.submit(test.frame(0,1));
        var value=capture(r,"texture-material-vertex",1);near(value[CENTER],giOracle(128/255.0)*(128/255.0),.003,"material times texture times vertex applied once");
        near(value[CENTER+1],0,1e-6,"vertex removes green");near(value[CENTER+2],0,1e-6,"vertex removes blue");
        test.packet(0,1,FrameClock.of(0,0));System.out.println("RTXPT_TEXTURED_TINT PASS");
    }
    static void deep(RtxptLightingTestSession.Case test)throws Exception{
        var r=test.renderer;
        r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
        r.apply(test.scene(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,255))
            .upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(plane(2,2,4,1,0,0,false))
            .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),List.of(source(1,0,0,.5,8,true)),LongBuffer.allocate(0));
        test.publication(1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));r.submit(test.frame(0,1));
        var fused=new ArrayList<double[]>();for(int signal=0;signal<3;signal++)fused.add(capture(r,"fused-"+signal,signal));test.packet(0,1,FrameClock.of(0,0));
        r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingDiagnosticDecompositionEnabled(true);r.submit(test.frame(1,1));
        for(int signal=0;signal<3;signal++){var split=capture(r,"deep-"+signal,signal);require(Arrays.equals(fused.get(signal),split),"Diagnostic scheduling is bit exact, signal "+signal);}
        test.packet(1,1,FrameClock.of(0,0));
        System.out.println("RTXPT_DEEP_EQUIVALENCE PASS signals=3 exact=true");
    }
    static void timings(RtxptLightingTestSession.Case test)throws Exception{
        var r=test.renderer;
        r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
        r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingDiagnosticDecompositionEnabled(true);
        r.apply(test.scene(1).resetScene().upsert(material(1,128,128,128)).upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(SceneInstance.builder(1,1).build()).build());
        r.submit(test.frame(0,1));test.packet(0,1,FrameClock.of(0,0));
        r.awaitIdle();var scopes=r.diagnosticGpuSnapshot();require(scopes.scopes().stream().anyMatch(s->s.name().equals("DiagnosticReceiverTrace")),"Actual receiver GPU scope");
        var cpu=r.diagnosticSnapshot();require(cpu.events().stream().anyMatch(e->e.threadCpuNanos()>=0),"v4 thread CPU timings present");
        require(cpu.events().stream().filter(e->e.threadCpuNanos()>=0).allMatch(e->e.threadCpuSelfNanos()>=0&&e.threadCpuSelfNanos()<=e.threadCpuNanos()),"CPU self bounded by inclusive CPU");
        System.out.println("RTXPT_DEEP_TIMINGS PASS cpuV4=true scopes="+scopes.scopes());
    }
}
