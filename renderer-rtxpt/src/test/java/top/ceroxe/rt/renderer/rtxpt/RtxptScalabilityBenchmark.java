package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Uses only 7.0.1 public entry points so the same class runs against both versions. */
public final class RtxptScalabilityBenchmark {
    static MaterialAsset material(int value){return MaterialAsset.builder(1).baseColorRgba8(0xff777700|value).baseColorTextureId(7).roughness(.8f).build();}
    static TextureAsset texture(int value){return TextureAsset.color(7,1,1,new byte[]{(byte)value,(byte)160,(byte)80,(byte)255});}
    static MeshAsset cube(long id){
        var source=RtxptAcceptanceFixture.scene().upserts().meshes().getFirst();
        float[] p=new float[source.positions().remaining()];source.positions().get(p);
        int[] i=new int[source.triangleIndices().remaining()];source.triangleIndices().get(i);
        long[] m=new long[i.length/3];Arrays.fill(m,1);return MeshAsset.builder(id,p,i,m).build();
    }
    static RendererConfig config(){
        var base=RendererConfig.builder().cpuFrameReadbackEnabled(false).frameGeneration(FrameGenerationOptions.disabled()).build();
        var q=base.pathTracing().quality();q=q.withLighting(q.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
        return base.copyBuilder().pathTracing(new PathTracingOptions(1,1,0,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,q)).build();
    }
    static RenderFrameRequest frame(long seq,long rev,EnvironmentState e,int width,int height){return RenderFrameRequest.builder(seq,width,height,CameraState.lookAt(0,4,7,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio((double)width/height).build()).minimumSceneRevision(rev).environment(e).build();}
    static void result(String caseName,List<Double> samples,long exports){
        var sorted=new ArrayList<>(samples);Collections.sort(sorted);
        System.out.println("RTXPT_SCALE "+RtxptJson.encode(RtxptJson.object("case",caseName,"samplesMs",samples,"p50Ms",sorted.get(sorted.size()/2),"p95Ms",sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*.95)-1)),"sceneExports",exports)));
    }
    public static void main(String[] args)throws Exception {
        System.out.println("RTXPT_BENCH_ARTIFACT "+RtxptRenderer.class.getProtectionDomain().getCodeSource().getLocation());
        String test=args.length==0?"resources":args[0];int count=args.length>1?Integer.parseInt(args[1]):256;
        try(var r=Renderer.open("rtxpt",config())) {
            var host=r.extension(RtxptAccess.class).orElseThrow();long seq=0,rev=1;
            var tx=SceneTransaction.builder(rev).resetScene().upsert(texture(200)).upsert(material(80));
            var cameraEnv=EnvironmentState.builder().sunDirection(0,-1,0).sunIntensity(2).build();
            boolean resources=Set.of("resources","material","texture","mesh","instance").contains(test);
            if(resources) {
                for(int i=0;i<count;i++)tx.upsert(cube(i+1)).upsert(SceneInstance.builder(i+1,i+1).transform(new AffineTransform(new float[]{.1f,0,0,(i%16-8)*.22f,0,.1f,0,0,0,0,.1f,(i/16-8)*.22f})).build());
            }else{
                tx.upsert(cube(1)).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{8,0,0,0,0,.1f,0,0,0,0,8,0})).build());
                for(int i=0;i<count;i++)tx.upsert(SceneLight.point(i,(i%100-50)*.12,2,(i/100%100-50)*.12).intensity(40f/count).range(12).castsShadow(true).build());
            }
            r.apply(tx.build());r.submit(frame(seq++,rev,cameraEnv,160,90));
            for(int i=0;i<12;i++)host.renderFrame();host.awaitIdle();
            var samples=new ArrayList<Double>();
            for(int i=0;i<(resources?24:count>=100000?12:32);i++){
                long start=System.nanoTime();
                if(resources){
                    var edit=SceneTransaction.builder(++rev);
                    switch(test){
                        case "texture"->edit.upsert(texture(i%2==0?160:200));
                        case "mesh"->{var m=cube(1);float[] p=new float[m.positions().remaining()];m.positions().get(p);p[0]+=i%2==0?.1f:.2f;int[] indices=new int[m.triangleIndices().remaining()];m.triangleIndices().get(indices);long[] ids=new long[indices.length/3];Arrays.fill(ids,1);edit.upsert(MeshAsset.builder(1,p,indices,ids).build());}
                        case "instance"->edit.upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{.1f,0,0,(i%2)*.1f,0,.1f,0,0,0,0,.1f,0})).build());
                        default->edit.upsert(material(i%2==0?160:80));
                    }
                    r.apply(edit.build());
                }
                EnvironmentState env=test.equals("environment")?EnvironmentState.builder().sunDirection(0,-1,0).sunIntensity(2+i*.0001f).build():cameraEnv;
                r.submit(frame(seq++,rev,env,160,90));host.awaitIdle();
                samples.add((System.nanoTime()-start)/1e6);
            }
            result(test+"-"+count,samples,host.bridgeTimings().sceneExports());
        }
    }
}
