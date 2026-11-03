package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Resource identity counters plus pixels compared with an explicit reset reference. */
public final class RtxptRetainedNativeSelfTest {
    static final Path OUTPUT=Path.of("build/reports/rtxpt-retained").toAbsolutePath();
    static TextureAsset texture(int red){return TextureAsset.color(7,1,1,new byte[]{(byte)red,(byte)(255-red),32,(byte)255});}
    static RenderFrameRequest frame(long sequence,long revision,EnvironmentState e){return RenderFrameRequest.builder(sequence,320,180,RtxptAcceptanceFixture.camera()).minimumSceneRevision(revision).environment(e).build();}
    static void settle(RtxptAccess host){RtxptLightingNativeSelfTest.settle(host,12);}
    static void referenceCapture(RtxptAccess host,Path path,String route)throws Exception {
        // ReSTIR histories differ after a delta and a fresh reset. Compare settled
        // 48-frame means with advancing noise, including on the other routes.
        RtxptLightingNativeSelfTest.settle(host,32);
        Path sample=path.resolveSibling(path.getFileName()+".sample.png");
        long[] sums=null;java.awt.image.BufferedImage image=null;
        for(int frame=0;frame<48;frame++){
            require(host.renderFrame(),"Reference average frame");host.capture(sample);
            image=javax.imageio.ImageIO.read(sample.toFile());
            if(sums==null)sums=new long[image.getWidth()*image.getHeight()*3];
            int offset=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
                int rgb=image.getRGB(x,y);for(int shift:new int[]{16,8,0})sums[offset++]+=(rgb>>>shift)&255;
            }
        }
        int offset=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int rgb=0xff000000;for(int shift:new int[]{16,8,0})rgb|=(int)((sums[offset++]+24)/48)<<shift;image.setRGB(x,y,rgb);
        }
        javax.imageio.ImageIO.write(image,"png",path.toFile());Files.delete(sample);
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUTPUT);
        for(String route:args.length==0?new String[]{"analytic","raw","rr","di-gi"}:args) {
            try(var renderer=Renderer.open("rtxpt",RtxptLightingNativeSelfTest.configuration(route))) {
                var r=(RtxptRenderer)renderer;var host=renderer.extension(RtxptAccess.class).orElseThrow();
                var e=RtxptLightingNativeSelfTest.environment();long rev=1,seq=0;
                r.apply(RtxptAcceptanceFixture.scene());r.submit(frame(seq++,rev,e));settle(host);
                var initial=host.lightingTimings();
                r.apply(SceneTransaction.builder(++rev).upsert(texture(255)).upsert(MaterialAsset.builder(1).baseColorTextureId(7).roughness(.8f).build()).build());
                r.submit(frame(seq++,rev,e));settle(host);
                var material=host.retainedUpdateTimings();
                require(material.exportedMeshVariants()==0&&material.builtBlases()==0&&material.tlasGenerations()==0,"Material and texture edit retains all geometry/AS");
                var a=OUTPUT.resolve(route+"-red.png");host.capture(a);
                for(int i=0;i<8;i++){r.apply(SceneTransaction.builder(++rev).upsert(texture(i%2==0?255:0)).build());r.submit(frame(seq++,rev,e));}
                settle(host);var b=OUTPUT.resolve(route+"-green.png");host.capture(b);
                require(RtxptLightingNativeSelfTest.difference(a,b)>.5,"Stable texture identity updates visible pixels");
                require(host.retainedUpdateTimings().exportedMeshVariants()==0&&host.retainedUpdateTimings().builtBlases()==0,"Texture animation has no geometry work");
                require(host.retainedUpdateTimings().reusedTextureAllocations()>=7,"Compatible stable textures retain GPU allocation");
                var mesh=r.sceneSnapshot().upserts().meshes().stream().filter(m->m.id()==3).findFirst().orElseThrow();
                float[] positions=new float[mesh.positions().remaining()];mesh.positions().get(positions);for(int i=0;i<positions.length;i+=3)positions[i]*=1.1f;
                int[] indices=new int[mesh.triangleIndices().remaining()];mesh.triangleIndices().get(indices);
                long[] ids=new long[mesh.triangleMaterialIds().remaining()];mesh.triangleMaterialIds().get(ids);
                r.apply(SceneTransaction.builder(++rev).upsert(MeshAsset.builder(3,positions,indices,ids).build()).build());r.submit(frame(seq++,rev,e));settle(host);
                var local=host.retainedUpdateTimings();require(local.exportedMeshVariants()==1&&local.builtBlases()==1,"One mesh replacement builds one BLAS");
                System.out.println("RTXPT_RETAINED mesh passed route="+route);
                r.apply(SceneTransaction.builder(++rev).upsert(SceneInstance.builder(99,3).transform(new AffineTransform(new float[]{1,0,0,2,0,1,0,1,0,0,1,-1})).build()).build());
                r.submit(frame(seq++,rev,e));settle(host);
                require(host.retainedUpdateTimings().builtBlases()==1,"New instance of a resident mesh shares its BLAS");
                r.apply(SceneTransaction.builder(++rev).removeInstance(99).build());r.submit(frame(seq++,rev,e));settle(host);
                var before=host.retainedUpdateTimings();var updated=OUTPUT.resolve(route+"-updated.png");referenceCapture(host,updated,route);
                require(host.bridgeTimings().sceneExports()==1&&host.lightingTimings().nativeSceneLoads()==initial.nativeSceneLoads(),"All sparse edits avoid scene reload/export");
                var snapshot=r.sceneSnapshot();r.apply(SceneTransaction.builder(++rev).resetScene().upsertTextures(snapshot.upserts().textures()).upsertMaterials(snapshot.upserts().materials()).upsertMeshes(snapshot.upserts().meshes()).upsertInstances(snapshot.upserts().instances()).upsertLights(snapshot.upserts().lights()).build());
                r.submit(frame(seq++,rev,e));settle(host);var reference=OUTPUT.resolve(route+"-reference.png");referenceCapture(host,reference,route);
                double block=RtxptLightingNativeSelfTest.blockDifference(updated,reference),mean=Math.abs(RtxptLightingNativeSelfTest.mean(updated)-RtxptLightingNativeSelfTest.mean(reference));
                require(block<3&&mean<1,"Full reset reference agrees: block="+block+" mean="+mean);
                require(host.bridgeTimings().sceneExports()==2,"Explicit reset uses the complete path");
                r.submit(RenderFrameRequest.builder(seq++,400,240,RtxptAcceptanceFixture.camera()).minimumSceneRevision(rev).environment(e).build());
                require(r.diagnostics().latestCompletedFrameSequence()==seq-1,"Resize completes with monotonic sequence");
                for(int i=0;i<3;i++){
                    r.windowState(1);require(host.renderFrame(),"Maximized window renders");
                    r.windowState(0);require(host.renderFrame(),"Restored window renders");
                    r.windowState(2);host.renderFrame();r.windowState(0);require(host.renderFrame(),"Minimized window resumes");
                }
                long goodRevision=r.sceneSnapshot().revision();
                RtxptAdapterSelfTest.rejected(()->r.apply(SceneTransaction.builder(goodRevision+1).removeMaterial(1).build()),"Invalid resource closure rejected atomically");
                require(r.sceneSnapshot().revision()==goodRevision,"Failed update preserves published revision");
                require(host.renderFrame(),"Previous scene remains usable after a rejected transaction");
                var populated=r.sceneSnapshot();var clear=SceneTransaction.builder(++rev);
                populated.upserts().instances().forEach(i->clear.removeInstance(i.id()));
                r.apply(clear.build());r.submit(frame(seq++,rev,e));settle(host);
                require(r.sceneSnapshot().upserts().instances().isEmpty(),"Removing all instances publishes an empty scene");
                r.windowState(1);require(host.renderFrame(),"Empty scene can maximize");r.windowState(0);
                r.apply(SceneTransaction.builder(++rev).upsertInstances(populated.upserts().instances()).build());
                r.submit(frame(seq++,rev,e));settle(host);
                require(host.bridgeTimings().sceneExports()==2,"Empty scene recovery uses retained updates");
                var telemetry=host.retainedUpdateTimings();
                require(telemetry.gpuIntervalSamples()>0&&telemetry.gpuIntervalNanos()>0,"Completed resource GPU queue intervals are measured");
                System.out.println("RTXPT_RETAINED PASS route="+route+" blockMae="+block+" meanError="+mean+" "+before);
            }
        }
    }
}
