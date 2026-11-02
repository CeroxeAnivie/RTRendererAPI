package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Real native scene/material hot switching through the same selector used by the demo. */
public final class RtxptBallMaterialNativeSelfTest {
    private RtxptBallMaterialNativeSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path output=Path.of("build/reports/ball-material-acceptance").toAbsolutePath();
        Files.createDirectories(output);
        var scene=new DemoScene(true);
        var physics=new HexPhysics();
        var previous=FramePrimitiveBatch.empty();
        long sequence=0,revision=0;
        try(var renderer=Renderer.open("rtxpt",DemoRendererProfile.rtxptInteractive())) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            renderer.apply(scene.initialTransaction());
            var camera=DemoRendererProfile.camera(1280,720,15.4);
            for(var material:new DemoScene.BallMaterial[]{DemoScene.BallMaterial.GLASS,
                    DemoScene.BallMaterial.OPAQUE_REFLECTIVE,DemoScene.BallMaterial.ORIGINAL_METAL,DemoScene.BallMaterial.GLASS}) {
                if(sequence>0) {
                    host.setOverlayChoices(Arrays.stream(DemoScene.BallMaterial.values()).map(m->m.label).toList(),material.ordinal());
                    scene=new DemoScene(DemoScene.BallMaterial.values()[host.overlaySelection()]);
                    renderer.apply(scene.ballMaterialTransaction(++revision));
                }
                long exportsBefore=host.bridgeTimings().sceneExports();
                for(int frame=0;frame<30;frame++) {
                    physics.advanceTo(System.nanoTime());
                    var batch=scene.primitiveBatch(physics,previous);
                    renderer.submit(DemoRendererProfile.frame(sequence++,1280,720,camera)
                        .minimumSceneRevision(revision).primitiveBatch(batch)
                        .environment(DemoRendererProfile.environment()).build());
                    previous=batch;
                    if(frame==0) {
                        if(sequence==1)host.setOverlayChoices(Arrays.stream(DemoScene.BallMaterial.values()).map(m->m.label).toList(),material.ordinal());
                        require(host.overlaySelection()==material.ordinal(),"Native material selector did not retain its choice");
                    }
                }
                require(host.bridgeTimings().sceneExports()==exportsBefore+1,"Material/structure change must export once, not each frame");
                require(previous.primitives().stream().allMatch(p->p.mediumBoundary().isPresent()==(material==DemoScene.BallMaterial.GLASS)),
                    "Native submission used the wrong interior structure");
                host.capture(output.resolve(sequence+"-"+material+".png"));
                System.out.println("BALL_MATERIAL_NATIVE_PASS "+material+" selected="+host.overlaySelection()
                    +" exports="+host.bridgeTimings().sceneExports()+" actual="+host.runtimeStatus());
            }
        }
    }

    private static void require(boolean passed,String message) {
        if(!passed)throw new AssertionError(message);
    }
}
