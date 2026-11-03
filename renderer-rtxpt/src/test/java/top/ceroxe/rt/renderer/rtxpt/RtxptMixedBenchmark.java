package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Same public workload and bytecode for both artifacts; no readback in timed frames. */
public final class RtxptMixedBenchmark {
    static TextureAsset texture(int n){byte[] p=new byte[32*32*4];for(int i=0;i<p.length;i+=4){p[i]=(byte)(100+n%80);p[i+1]=(byte)160;p[i+2]=80;p[i+3]=(byte)255;}return TextureAsset.color(7,32,32,p);}
    static MeshAsset mesh(long id,int n){
        var source=RtxptAcceptanceFixture.scene().upserts().meshes().getFirst();
        float[] p=new float[source.positions().remaining()];source.positions().get(p);p[0]+=(n%2)*.03f;
        int[] indices=new int[source.triangleIndices().remaining()];source.triangleIndices().get(indices);
        long[] m=new long[indices.length/3];Arrays.fill(m,id);return MeshAsset.builder(id,p,indices,m).build();
    }
    static SceneInstance instance(long id){return SceneInstance.builder(id,id).transform(new AffineTransform(new float[]{.1f,0,0,(id%16-8)*.23f,0,.1f,0,0,0,0,.1f,(id/16-8)*.23f})).build();}
    static SceneLight moving(int n){return SceneLight.point(0,Math.sin(n*.05),2,0).intensity(8).range(12).build();}
    public static void main(String[] args)throws Exception {
        int count=args.length>0?Integer.parseInt(args[0]):100000;String label=args.length>1?args[1]:"current";
        Path output=Path.of("build/reports/rtxpt-mixed/"+label+"-"+count+".csv");Files.createDirectories(output.getParent());
        var config=RtxptScalabilityBenchmark.config();
        try(var csv=Files.newBufferedWriter(output);var r=Renderer.open("rtxpt",config)) {
            var h=r.extension(RtxptAccess.class).orElseThrow();long rev=1,seq=0;
            csv.write("phase,index,applyMs,submitMs,completedSequence\n");
            var tx=SceneTransaction.builder(rev).resetScene().upsert(texture(0));
            for(int i=1;i<=256;i++)tx.upsert(MaterialAsset.builder(i).baseColorTextureId(7).roughness(.8f).build()).upsert(mesh(i,0)).upsert(instance(i));
            for(int i=1;i<count;i++)tx.upsert(SceneLight.point(i,(i%100-50)*.2,2,(i/100%100-50)*.2).intensity(20f/count).range(16).build());
            tx.upsert(moving(0));long start=System.nanoTime();r.apply(tx.build());double apply=(System.nanoTime()-start)/1e6;
            var env=EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.4f).sunDirection(0,-1,0).sunIntensity(2).build();
            start=System.nanoTime();r.submit(RtxptScalabilityBenchmark.frame(seq++,rev,env,640,360));
            csv.write("initial,0,"+apply+","+(System.nanoTime()-start)/1e6+","+(seq-1)+"\n");csv.flush();
            for(int i=0;i<16;i++)h.renderFrame();h.awaitIdle();
            try{h.getClass().getDeclaredMethod("resetWindowPollInterval").invoke(h);}catch(NoSuchMethodException ignored){}
            System.out.println("MIXED_CONFIG "+h.runtimeStatus()+" "+h.pathQualityStatus());
            for(String phase:List.of("stable","texture","mesh","instance","light","environment","mixed")) {
                var times=new ArrayList<Double>();var before=h.retainedUpdateTimings();
                for(int i=0;i<16;i++) {
                    var edit=SceneTransaction.builder(rev+1);boolean change=!phase.equals("stable")&&!phase.equals("environment");
                    if(phase.equals("texture")||phase.equals("mixed"))edit.upsert(texture(i+1));
                    if(phase.equals("mesh")||phase.equals("mixed"))edit.upsert(mesh(1,i));
                    if(phase.equals("instance")){if(i%2==0)edit.removeInstance(256);else edit.upsert(instance(256));}
                    if(phase.equals("light")||phase.equals("mixed"))edit.upsert(moving(i+1));
                    start=System.nanoTime();if(change){r.apply(edit.build());rev++;}apply=(System.nanoTime()-start)/1e6;
                    var e=phase.equals("environment")||phase.equals("mixed")?EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.4f).sunDirection(0,-1,0).sunIntensity(2+i*.001f).build():env;
                    start=System.nanoTime();r.submit(RtxptScalabilityBenchmark.frame(seq++,rev,e,640,360));double elapsed=(System.nanoTime()-start)/1e6;
                    times.add(elapsed);csv.write(phase+","+i+","+apply+","+elapsed+","+r.diagnostics().latestCompletedFrameSequence()+"\n");csv.flush();
                }
                var sorted=new ArrayList<>(times);Collections.sort(sorted);
                System.out.println("MIXED_RESULT "+phase+" p50="+sorted.get(7)+" p95="+sorted.get(15)+" max="+Collections.max(sorted)+" before="+before+" after="+h.retainedUpdateTimings());
                try{System.out.println("MIXED_STAGES "+phase+" "+h.getClass().getMethod("submitStages").invoke(h));}catch(NoSuchMethodException ignored){}
            }
        }
    }
}
