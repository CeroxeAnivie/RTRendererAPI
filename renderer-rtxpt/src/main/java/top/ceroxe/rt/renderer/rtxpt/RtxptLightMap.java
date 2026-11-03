package top.ceroxe.rt.renderer.rtxpt;
import top.ceroxe.rt.renderer.api.SceneLight;
/** Persistent light index; snapshots share immutable nodes. */
final class RtxptLightMap extends RtxptPersistentMap<SceneLight> {
    RtxptLightMap() { }
    RtxptLightMap(RtxptLightMap source) { super(source); }
}
