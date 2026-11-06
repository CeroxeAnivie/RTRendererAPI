package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.concurrent.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Native framework bootstrap/reset/file-load and owner-published cross-thread observations. */
public final class RtxptBootstrapNativeSelfTest {
    private static void observe(RtxptRenderer r,long revision,long sequence,Renderer.Status status)throws Exception {
        var task=CompletableFuture.runAsync(()->{
            for(int i=0;i<100;i++){
                var d=r.diagnostics();
                require(d.status()==status&&r.status()==status&&r.health().status()==status,"Cross-thread lifecycle agrees with immutable snapshot");
                require(d.latestAcceptedSceneRevision()==revision&&d.latestSubmittedFrameSequence()==sequence,"Owner publishes current revision and sequence");
                if(status==Renderer.Status.CLOSED)require(d.residentMeshes()==0&&d.residentInstances()==0,"Closed snapshot releases resident scene");
            }
        });
        task.get(10,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/framework-bootstrap"));
        Files.createDirectories(output);
        var core=RtxptNativeLightingNativeSelfTest.scene();
        var oracle=new RtxptScene();oracle.retain(core);
        var path=new RtxptSceneExport(oracle).export(output.resolve("native-oracle"),RtxptNativeLightingNativeSelfTest.frame(0,1),oracle.items(RtxptNativeLightingNativeSelfTest.frame(0,1)));
        var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),false);
        try{
            r.apply(core);r.submit(RtxptNativeLightingNativeSelfTest.frame(0,1));observe(r,1,0,Renderer.Status.READY);
            r.apply(SceneTransaction.builder(2).resetScene().build());r.submit(RtxptNativeLightingNativeSelfTest.frame(1,2));observe(r,2,1,Renderer.Status.READY);
            var repopulate=SceneTransaction.builder(3).resetScene();
            core.upserts().materials().forEach(repopulate::upsert);core.upserts().meshes().forEach(repopulate::upsert);core.upserts().instances().forEach(repopulate::upsert);
            r.apply(repopulate.build());r.submit(RtxptNativeLightingNativeSelfTest.frame(2,3));
            r.apply(SceneTransaction.builder(4).resetScene().build());r.submit(RtxptNativeLightingNativeSelfTest.frame(3,4));
            r.loadNativeScene(path);r.submit(RtxptNativeLightingNativeSelfTest.frame(4,4));observe(r,4,4,Renderer.Status.READY);
            var sameName=path.resolveSibling("__rtr_memory_scene__");Files.copy(path,sameName,StandardCopyOption.REPLACE_EXISTING);
            r.loadNativeScene(sameName);r.submit(RtxptNativeLightingNativeSelfTest.frame(5,4));
            var materials=output.resolve("same-name-materials");r.snapshotMaterials(materials);
            try(var files=Files.list(materials)){require(files.findAny().isPresent(),"An actual user file with the internal marker basename is imported normally");}
        }finally{r.close();}
        observe(r,4,5,Renderer.Status.CLOSED);
        System.out.println("RTXPT_FRAMEWORK_BOOTSTRAP PASS initial=true emptyReset=true populatedReset=true publicFileLoad=true crossThreadSnapshots=true closedSnapshot=true");
    }
}
