package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Arrays;
import top.ceroxe.rt.renderer.api.MeshAsset;

/** Independent quadratic geometric oracle for the upload-time incidence index. */
public final class VulkanBoundaryTopologySelfTest {
    public static void main(String[] args) {
        String previous = System.getProperty(VulkanBoundaryTopology.PROPERTY);
        try {
            System.setProperty(VulkanBoundaryTopology.PROPERTY, "true");
            verify(MediumTransportSelfTest.cube(1, 1, 1));
            var seams = MeshAsset.triangles(2, new float[]{
                0,0,0, 1,0,0, 0,1,0, -0.0f,0,0, 0,0,1, 1,0,0,
                10,10,10, 11,10,10, 10,11,10
            }, new int[]{0,1,2, 3,4,5, 0,0,1, 6,7,8}, 1);
            verify(seams);
            float[] moved = new float[seams.positions().remaining()]; seams.positions().get(moved);
            int[] index = new int[seams.triangleIndices().remaining()]; seams.triangleIndices().get(index);
            moved[9] = 2; moved[15] = 3;
            verify(MeshAsset.triangles(2, moved, index, 1));
            System.setProperty(VulkanBoundaryTopology.PROPERTY, "false");
            int[] fallback = VulkanBoundaryTopology.pack(seams);
            require(fallback.length == seams.triangleCount()*3+1 && fallback[fallback.length-1] == 0,
                    "disabled topology must use the bounded scan fallback");
            System.setProperty(VulkanBoundaryTopology.PROPERTY, "true");
            int[] fan = new int[300];
            for (int i=0; i<fan.length; i+=3) {fan[i]=0;fan[i+1]=1;fan[i+2]=2;}
            var dense = MeshAsset.triangles(3, new float[]{0,0,0, 1,0,0, 0,1,0}, fan, 1);
            int[] capped = VulkanBoundaryTopology.pack(dense);
            require(capped.length == fan.length+1 && capped[fan.length] == 0,
                    "high-valence topology must fall back without quadratic resident storage");
        } finally {
            if (previous == null) System.clearProperty(VulkanBoundaryTopology.PROPERTY);
            else System.setProperty(VulkanBoundaryTopology.PROPERTY, previous);
        }
        System.out.println("VulkanBoundaryTopologySelfTest passed");
    }

    private static void verify(MeshAsset mesh) {
        int[] indices = new int[mesh.triangleIndices().remaining()]; mesh.triangleIndices().get(indices);
        float[] p = new float[mesh.positions().remaining()]; mesh.positions().get(p);
        int[] packed = VulkanBoundaryTopology.pack(mesh); int trailer = indices.length;
        require(Arrays.equals(indices, Arrays.copyOf(packed, indices.length)), "BLAS index prefix changed");
        require(packed[trailer] == VulkanBoundaryTopology.MAGIC, "small topology unexpectedly fell back");
        for (int triangle=0; triangle<mesh.triangleCount(); ++triangle) {
            int cursor=packed[trailer+1+triangle], end=packed[trailer+2+triangle];
            for (int other=0; other<mesh.triangleCount(); ++other) {
                boolean shared=false;
                for (int a=0; a<3; ++a) for (int b=0; b<3; ++b) {
                    int va=indices[triangle*3+a]*3,vb=indices[other*3+b]*3;
                    shared |= p[va]==p[vb] && p[va+1]==p[vb+1] && p[va+2]==p[vb+2];
                }
                if (shared) require(cursor<end && packed[trailer+cursor++]==other, "missing, unordered or duplicate neighbor");
            }
            require(cursor==end,"extra neighbor");
        }
    }

    private static void require(boolean value,String reason) { if (!value) throw new AssertionError(reason); }
}
