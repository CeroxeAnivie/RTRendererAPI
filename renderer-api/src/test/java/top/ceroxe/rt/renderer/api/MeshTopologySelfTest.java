package top.ceroxe.rt.renderer.api;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Geometry assertions plus an opt-in, public-API-only allocation comparison. */
public final class MeshTopologySelfTest {
    private static final float[] CUBE = {-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,
            -1,-1,1,1,-1,1,1,1,1,-1,1,1};
    private static final int[] TRIANGLES = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,
            3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};

    public static void main(String[] args) {
        require(mesh(CUBE, TRIANGLES).isClosedManifold(), "closed cube");
        require(denseCube(8).isClosedManifold(), "welded texture seams");
        int[] reversed = TRIANGLES.clone();
        for (int i = 0; i < reversed.length; i += 3) {
            int swap = reversed[i]; reversed[i] = reversed[i+1]; reversed[i+1] = swap;
        }
        require(mesh(CUBE, reversed).isClosedManifold(), "inward winding");
        int swap = reversed[0]; reversed[0] = reversed[1]; reversed[1] = swap;
        require(!mesh(CUBE, reversed).isClosedManifold(), "one inconsistent face");
        require(!mesh(CUBE, java.util.Arrays.copyOf(TRIANGLES, TRIANGLES.length-3)).isClosedManifold(), "open border");
        int[] duplicate = java.util.Arrays.copyOf(TRIANGLES, TRIANGLES.length+3);
        System.arraycopy(TRIANGLES, 0, duplicate, TRIANGLES.length, 3);
        require(!mesh(CUBE, duplicate).isClosedManifold(), "three faces on an edge");
        // Two closed components sharing a single welded vertex have paired edges but a pinched star.
        float[] two = java.util.Arrays.copyOf(CUBE, CUBE.length*2);
        for (int i = 0; i < CUBE.length; i++) two[CUBE.length+i] = CUBE[i]+2;
        int[] both = java.util.Arrays.copyOf(TRIANGLES, TRIANGLES.length*2);
        for (int i = 0; i < TRIANGLES.length; i++) both[TRIANGLES.length+i] = TRIANGLES[i]+8;
        require(!mesh(two, both).isClosedManifold(), "pinched vertex");
        for (int i = 0; i < CUBE.length; i += 3) two[CUBE.length+i] += 1;
        require(mesh(two, both).isClosedManifold(), "disconnected closed components");
        if (args.length > 0 && args[0].equals("--profile")) profile();
        System.out.println("MeshTopologySelfTest passed: closed, seams, inward, open, winding, nonmanifold edge/star");
    }

    private static void profile() {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 8; i++) require(denseCube(8).isClosedManifold(), "warmup");
        for (int subdivisions : new int[]{8,32,64}) {
            long bytes = 0, nanos = 0;
            for (int repeat = 0; repeat < 3; repeat++) {
                var mesh = denseCube(subdivisions);
                long before = bean.getCurrentThreadAllocatedBytes(), started = System.nanoTime();
                require(mesh.isClosedManifold(), "dense topology");
                nanos += System.nanoTime()-started; bytes += bean.getCurrentThreadAllocatedBytes()-before;
                long cached = bean.getCurrentThreadAllocatedBytes();
                boolean closed = mesh.isClosedManifold();
                long cachedBytes = bean.getCurrentThreadAllocatedBytes()-cached;
                require(closed && cachedBytes==0, "cached admission must succeed without allocation");
            }
            System.out.printf(Locale.ROOT, "TopologyAllocation triangles=%d averageBytes=%d averageMs=%.3f%n",
                    12*subdivisions*subdivisions, bytes/3, nanos/3e6);
        }
    }

    private static MeshAsset denseCube(int n) {
        int count = 12*n*n, next = 0;
        float[] points = new float[count*9]; int[] indices = new int[count*3];
        for (int t = 0; t < TRIANGLES.length; t += 3) {
            int a = TRIANGLES[t]*3, b = TRIANGLES[t+1]*3, c = TRIANGLES[t+2]*3;
            for (int u = 0; u < n; u++) for (int v = 0; v < n-u; v++) {
                int[] us = {u,u+1,u,u+1,u+1,u}, vs = {v,v,v+1,v,v+1,v+1};
                for (int vertex = 0, total = u+v+1<n?6:3; vertex < total; vertex++) {
                    indices[next/3] = next/3;
                    for (int axis = 0; axis < 3; axis++) points[next++] = CUBE[a+axis]
                            +(CUBE[b+axis]-CUBE[a+axis])*us[vertex]/n
                            +(CUBE[c+axis]-CUBE[a+axis])*vs[vertex]/n;
                }
            }
        }
        return mesh(points, indices);
    }

    private static MeshAsset mesh(float[] positions, int[] indices) {
        return MeshAsset.triangles(1, positions, indices, 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
