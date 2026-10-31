package demo;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureFormat;

/** Captures the actual matrix workloads for frozen-core HDR comparison, outside all FPS windows. */
public final class MediumPerformanceHdrCapture {
    private MediumPerformanceHdrCapture() { }

    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("cases|all outputDirectory width height frames");
        var workloads=MediumPerformanceCases.all();
        if(!args[0].equals("all")) {
            var selected=Set.of(args[0].split(","));
            workloads=workloads.stream().filter(w->selected.contains(w.id())).toList();
            require(workloads.size()==selected.size(),"unknown or duplicate HDR workloads");
        }
        Path output=Path.of(args[1]).toAbsolutePath();
        int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]),frames=Integer.parseInt(args[4]);
        int lightMask=Integer.getInteger("top.ceroxe.rt.medium.hdrCaptureLightMask",-1);
        long unshadowedLight=Long.getLong("top.ceroxe.rt.medium.hdrCaptureUnshadowedLightId",-1L);
        require(lightMask>=-1,"invalid diagnostic light mask");
        require(unshadowedLight>=-1,"invalid diagnostic unshadowed light ID");
        require(width>0&&height>0&&frames>0,"positive HDR extent and frames required");
        Files.createDirectories(output);
        for(var workload:workloads) {
            try(var renderer=Renderer.open(workload.config().copyBuilder().cpuFrameReadbackEnabled(true).build())) {
                renderer.apply(maskedTransaction(workload.transaction(),lightMask,unshadowedLight));
                for(int frame=0;frame<frames;frame++) {
                    var request=workload.frame(frame,width,height);
                    // The all-on control must submit the exact original request and transaction.
                    if(lightMask>=0&&(lightMask&1)==0&&request.environment().sunIntensity()!=0)
                        request=request.toBuilder().environment(request.environment().toBuilder().sunIntensity(0).build()).build();
                    long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
                    while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                        require(System.nanoTime()<deadline,"HDR frame admission timed out: "+workload.id());
                        LockSupport.parkNanos(100_000);
                    }
                    var snapshot=renderer.awaitFrame(Duration.ofSeconds(120)).orElseThrow();
                    require(snapshot.format()==TextureFormat.RGBA16_FLOAT,"linear half-float HDR required");
                    var pixels=snapshot.pixels().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    byte[] packed=new byte[Math.multiplyExact(Math.multiplyExact(width,height),8)];
                    for(int y=0;y<height;y++) {
                        for(int x=0;x<width;x++)for(int c=0;c<4;c++) {
                            float value=Float.float16ToFloat(pixels.getShort(y*snapshot.rowBytes()+x*8+c*2));
                            require(Float.isFinite(value)&&value>=0,"invalid HDR pixel: "+workload.id());
                        }
                        pixels.position(y*snapshot.rowBytes());pixels.get(packed,y*width*8,width*8);
                    }
                    Files.write(output.resolve(workload.id()+"-"+frame+".rgba16f"),packed);
                }
                require(renderer.health().activeFailure().isEmpty(),"HDR session failed: "+workload.id());
            }
            System.out.println("MatrixHDR case="+workload.id()+" frames="+frames+" extent="+width+"x"+height);
        }
    }
    private static SceneTransaction maskedTransaction(SceneTransaction original,int mask,long unshadowedLight) {
        if(mask<0&&unshadowedLight<0)return original;
        boolean changed=false;
        boolean foundUnshadowed=unshadowedLight<0;
        for(var light:original.upserts().lights()) {
            require(light.id()>0&&light.id()<31,"light-mask diagnostic requires IDs 1..30");
            changed|=mask>=0&&(mask&(1<<light.id()))==0&&light.intensity()!=0;
            if(light.id()==unshadowedLight){foundUnshadowed=true;changed|=light.castsShadow();}
        }
        require(foundUnshadowed,"unknown diagnostic unshadowed light ID");
        if(!changed)return original;
        // Mask before the initial publish: no extra generation, revision or apply/admission race.
        var copy=SceneTransaction.builder(original.revision());
        if(original.reset())copy.resetScene();
        copy.upsertTextures(original.upserts().textures()).upsertMaterials(original.upserts().materials())
                .upsertMeshes(original.upserts().meshes()).upsertInstances(original.upserts().instances());
        for(var light:original.upserts().lights()) {
            boolean disabled=mask>=0&&(mask&(1<<light.id()))==0&&light.intensity()!=0;
            boolean unshadowed=light.id()==unshadowedLight&&light.castsShadow();
            if(!disabled&&!unshadowed)copy.upsert(light);
            else copy.upsert(light.toBuilder().intensity(disabled?0:light.intensity())
                    .castsShadow(light.castsShadow()&&!unshadowed).build());
        }
        for(var ids=original.removals().textureIds();ids.hasRemaining();)copy.removeTexture(ids.get());
        for(var ids=original.removals().materialIds();ids.hasRemaining();)copy.removeMaterial(ids.get());
        for(var ids=original.removals().meshIds();ids.hasRemaining();)copy.removeMesh(ids.get());
        for(var ids=original.removals().instanceIds();ids.hasRemaining();)copy.removeInstance(ids.get());
        for(var ids=original.removals().lightIds();ids.hasRemaining();)copy.removeLight(ids.get());
        return copy.build();
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
