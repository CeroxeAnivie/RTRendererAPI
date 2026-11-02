package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** CPU generation and classification tests, independent of native loading. */
final class RtxptLightingSelfTest {
    static void run() {
        var scene=new RtxptScene();scene.retain(RtxptAdapterSelfTest.scene(1));
        var point=SceneLight.point(9,1,2,3).color(.2f,.3f,.4f).intensity(5).range(7).castsShadow(false).build();
        var spot=SceneLight.spot(2,4,5,6,0,-1,0).coneCosines(.9f,.7f).range(8).build();
        var directional=SceneLight.directional(18,1,-1,0).intensity(0).build();
        var tx=SceneTransaction.builder(2).upsert(point).upsert(spot).upsert(directional).build();
        require(RtxptRenderer.geometryUnchanged(tx,scene),"All light additions are independent of geometry");scene.retain(tx);
        var environment=RtxptAdapterSelfTest.frame(0).environment();
        var state=RtxptLighting.of(environment,scene.lights.values());var values=state.records();
        require(Arrays.equals(state.ids(),new long[]{-1,2,9,18}),"IDs are canonical and the sun cannot collide with application lights");
        require(values.length==64&&values[16+3]==2&&values[32+3]==1&&values[48+3]==0,"Every light type is packed");
        require(values[32]==1&&values[33]==2&&values[34]==3&&values[39]==7&&values[43]==5&&values[46]==0,"Point fields and no-shadow survive");
        require(values[28]==.9f&&values[29]==.7f&&values[23]==8,"Spot cone and range survive");
        require(values[11]==environment.sunIntensity()&&Arrays.equals(state.sky(),new float[]{.4f,.6f,.9f,1}),"Complete environment survives");
        var reverse=new ArrayList<>(scene.lights.values());Collections.reverse(reverse);
        require(state.equals(RtxptLighting.of(environment,reverse)),"Map insertion order does not cause redundant lighting uploads");
        var replacement=SceneTransaction.builder(3).upsert(SceneLight.spot(9,0,4,0,0,-1,0).build()).removeLight(2).build();
        require(RtxptRenderer.geometryUnchanged(replacement,scene),"Type replacement and removals do not reload geometry");scene.retain(replacement);
        require(!state.equals(RtxptLighting.of(environment,scene.lights.values())),"Actual replacements create a new generation");
        require(!RtxptRenderer.geometryUnchanged(SceneTransaction.builder(4).removeInstance(1).build(),scene),"Geometry removal still reloads");
        require(!RtxptRenderer.geometryUnchanged(SceneTransaction.builder(4).resetScene().build(),scene),"Reset preserves full-scene semantics");
        var moving=SceneTransaction.builder(4).upsert(SceneInstance.builder(1,1).mediumBoundary(scene.instances.get(1L).mediumBoundary().orElseThrow())
            .transform(new AffineTransform(new float[]{1,0,0,1,0,1,0,2,0,0,1,3})).build()).upsert(point).build();
        require(RtxptRenderer.geometryUnchanged(moving,scene),"Mixed light and transform batch stays incremental");
        values[32]=999;require(state.records()[32]==1,"Packing buffers cannot mutate a retained immutable snapshot");
    }
}
