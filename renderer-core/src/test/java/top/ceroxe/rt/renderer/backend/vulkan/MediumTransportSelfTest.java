package top.ceroxe.rt.renderer.backend.vulkan;
import top.ceroxe.rt.renderer.api.MeshAsset;
/** Shared immutable mesh fixture, with no retired transport implementation. */
final class MediumTransportSelfTest {
    static MeshAsset cube(long id,long material,float radius) {
        return MeshAsset.triangles(id,new float[]{-radius,-radius,-radius,radius,-radius,-radius,radius,radius,-radius,-radius,radius,-radius,
            -radius,-radius,radius,radius,-radius,radius,radius,radius,radius,-radius,radius,radius},
            new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5},material);
    }
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
