package top.ceroxe.rt.renderer.api;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Closed oriented half-edge pairing and connected vertex stars, without per-link object graphs. */
final class MeshTopology {
    private MeshTopology() {}

    static boolean closed(FloatBuffer points, IntBuffer indices) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < points.limit(); i += 3) {
            minX = Math.min(minX, points.get(i)); maxX = Math.max(maxX, points.get(i));
            minY = Math.min(minY, points.get(i+1)); maxY = Math.max(maxY, points.get(i+1));
            minZ = Math.min(minZ, points.get(i+2)); maxZ = Math.max(maxZ, points.get(i+2));
        }
        double scale = Math.max((double) maxX-minX, Math.max((double) maxY-minY, (double) maxZ-minZ));
        if (!(scale > 0) || !Double.isFinite(scale)) return false;
        Map<Position, Integer> welded = new HashMap<>();
        int[] vertexIds = new int[points.limit()/3];
        Arrays.fill(vertexIds, -1);
        // A corner's two neighbours in its vertex link are supplied by its paired incident edges.
        // Edge closure gives degree two; joining these corners proves that each star is one cycle.
        int[] parents = new int[indices.limit()];
        Arrays.fill(parents, -1);
        EdgePairs edges = new EdgePairs(indices.limit()/2);
        double volume = 0;
        for (int corner = 0; corner < indices.limit(); corner += 3) {
            int a = indices.get(corner), b = indices.get(corner+1), c = indices.get(corner+2);
            int va = vertexId(points, a, vertexIds, welded);
            int vb = vertexId(points, b, vertexIds, welded);
            int vc = vertexId(points, c, vertexIds, welded);
            if (va==vb || vb==vc || vc==va) return false;
            if (!edges.add(va, vb, corner, indices, vertexIds, parents)
                    || !edges.add(vb, vc, corner+1, indices, vertexIds, parents)
                    || !edges.add(vc, va, corner+2, indices, vertexIds, parents)) return false;
            double ax=points.get(a*3), ay=points.get(a*3+1), az=points.get(a*3+2);
            double bx=points.get(b*3), by=points.get(b*3+1), bz=points.get(b*3+2);
            double cx=points.get(c*3), cy=points.get(c*3+1), cz=points.get(c*3+2);
            double abx=bx-ax, aby=by-ay, abz=bz-az, acx=cx-ax, acy=cy-ay, acz=cz-az;
            double nx=aby*acz-abz*acy, ny=abz*acx-abx*acz, nz=abx*acy-aby*acx;
            if (!(nx*nx+ny*ny+nz*nz > 0)) return false;
            ax-=minX; ay-=minY; az-=minZ; bx-=minX; by-=minY; bz-=minZ; cx-=minX; cy-=minY; cz-=minZ;
            volume+=ax*(by*cz-bz*cy)+ay*(bz*cx-bx*cz)+az*(bx*cy-by*cx);
        }
        if (!edges.closed()) return false;
        int[] stars = new int[welded.size()];
        Arrays.fill(stars, -1);
        for (int corner = 0; corner < indices.limit(); corner++) {
            int vertex = vertexIds[indices.get(corner)], root = root(parents, corner);
            if (stars[vertex]<0) stars[vertex]=root;
            else if (stars[vertex]!=root) return false;
        }
        return Math.abs(volume)>scale*scale*scale*1e-12;
    }

    private static int vertexId(FloatBuffer points, int vertex, int[] ids, Map<Position, Integer> welded) {
        if (ids[vertex]>=0) return ids[vertex];
        int offset=vertex*3;
        Position key=new Position(bits(points.get(offset)), bits(points.get(offset+1)), bits(points.get(offset+2)));
        Integer id=welded.get(key);
        if (id==null) { id=welded.size(); welded.put(key,id); }
        return ids[vertex]=id;
    }

    private static int bits(float value) { return Float.floatToIntBits(value==0 ? 0 : value); }
    private record Position(int x, int y, int z) {}

    private static int next(int corner) { return corner%3==2 ? corner-2 : corner+1; }
    private static int root(int[] parents, int corner) {
        int root=corner;
        while (parents[root]>=0) root=parents[root];
        while (corner!=root) { int next=parents[corner]; parents[corner]=root; corner=next; }
        return root;
    }

    private static void join(int[] parents, int a, int b) {
        a=root(parents,a); b=root(parents,b);
        if (a==b) return;
        if (parents[a]>parents[b]) { int swap=a; a=b; b=swap; }
        parents[a]+=parents[b]; parents[b]=a;
    }

    private static final class EdgePairs {
        private long[] keys;
        // Positive: first corner+1, negative: already paired, zero: empty.
        private int[] corners;
        private int size, unpaired;

        EdgePairs(int expected) {
            int capacity=16;
            while (capacity<(long) expected*3/2 && capacity<(1<<30)) capacity<<=1;
            keys=new long[capacity]; corners=new int[capacity];
        }

        boolean add(int from, int to, int corner, IntBuffer indices, int[] ids, int[] parents) {
            long key=((long) Math.min(from,to)<<32)|Math.max(from,to);
            int slot=find(key);
            if (keys[slot]==0) {
                if (size>=keys.length*2L/3) { grow(); slot=find(key); }
                keys[slot]=key; corners[slot]=corner+1; size++; unpaired++;
                return true;
            }
            int previous=corners[slot]-1;
            if (previous<0 || ids[indices.get(previous)]!=to || ids[indices.get(next(previous))]!=from)
                return false;
            join(parents,corner,next(previous)); join(parents,next(corner),previous);
            corners[slot]=-corners[slot]; unpaired--;
            return true;
        }

        boolean closed() { return unpaired==0; }

        private int find(long key) {
            long mixed=key;
            mixed=(mixed^(mixed>>>33))*0xff51afd7ed558ccdL;
            mixed=(mixed^(mixed>>>33))*0xc4ceb9fe1a85ec53L;
            int slot=(int) (mixed^(mixed>>>33))&(keys.length-1);
            while (keys[slot]!=0 && keys[slot]!=key) slot=(slot+1)&(keys.length-1);
            return slot;
        }

        private void grow() {
            if (keys.length==(1<<30)) throw new OutOfMemoryError("closed topology edge index is too large");
            long[] previousKeys=keys; int[] previousCorners=corners;
            keys=new long[keys.length*2]; corners=new int[keys.length];
            for (int i=0; i<previousKeys.length; i++) if (previousKeys[i]!=0) {
                int slot=find(previousKeys[i]); keys[slot]=previousKeys[i]; corners[slot]=previousCorners[i];
            }
        }
    }
}
