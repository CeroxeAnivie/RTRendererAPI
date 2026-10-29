package top.ceroxe.rt.renderer.backend.vulkan;

import java.math.BigDecimal;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.MeshAsset;

/**
 * Conservative event bound for small closed meshes. A proven convex closed 2-manifold can be
 * crossed at most twice by a line. Every other mesh deliberately falls back to its triangle
 * count; this class never reduces a public event budget on an uncertain proof.
 */
final class MediumConvexMeshBound {
    private static final int MAX_VERTICES = 256;
    private static final int MAX_TRIANGLES = 512;

    private MediumConvexMeshBound() { }

    static boolean convexClosed(MeshAsset mesh) {
        int vertices = mesh.vertexCount(), triangles = mesh.triangleCount();
        if (vertices > MAX_VERTICES || triangles > MAX_TRIANGLES) return false;
        FloatBuffer points = mesh.positions();
        IntBuffer indices = mesh.triangleIndices();
        Map<Position, Integer> welded = new HashMap<>();
        List<Position> weldedPoints = new ArrayList<>();
        int[] ids = new int[vertices];
        for (int vertex = 0; vertex < vertices; vertex++) {
            int base = vertex * 3;
            Position key = new Position(bits(points.get(base)), bits(points.get(base + 1)), bits(points.get(base + 2)));
            Integer weldedId = welded.get(key);
            if (weldedId == null) { weldedId = welded.size(); welded.put(key, weldedId); weldedPoints.add(key); }
            ids[vertex] = weldedId;
        }
        if (welded.size() < 4) return false;
        List<Set<Integer>> incident = new ArrayList<>(welded.size());
        for (int i = 0; i < welded.size(); i++) {
            incident.add(new HashSet<>());
        }
        List<Set<Integer>> faceNeighbors = new ArrayList<>(triangles);
        for (int i = 0; i < triangles; i++) faceNeighbors.add(new HashSet<>());
        Map<Edge, OrientedEdge> edges = new HashMap<>();
        BigDecimal[][] exact = new BigDecimal[vertices][3];
        for (int vertex = 0; vertex < vertices; vertex++) for (int axis = 0; axis < 3; axis++)
            exact[vertex][axis] = new BigDecimal((double) points.get(vertex * 3 + axis));
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < welded.size(); i++) {
            Position p = weldedPoints.get(i);
            minX = Math.min(minX, Float.intBitsToFloat(p.x)); maxX = Math.max(maxX, Float.intBitsToFloat(p.x));
            minY = Math.min(minY, Float.intBitsToFloat(p.y)); maxY = Math.max(maxY, Float.intBitsToFloat(p.y));
            minZ = Math.min(minZ, Float.intBitsToFloat(p.z)); maxZ = Math.max(maxZ, Float.intBitsToFloat(p.z));
        }
        double scale = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (!(scale > 0) || !Double.isFinite(scale)) return false;
        double volume = 0;
        for (int face = 0; face < triangles; face++) {
            int offset = face * 3;
            int a = ids[indices.get(offset)], b = ids[indices.get(offset + 1)], c = ids[indices.get(offset + 2)];
            if (a == b || b == c || c == a) return false;
            incident.get(a).add(face); incident.get(b).add(face); incident.get(c).add(face);
            double ax = points.get(indices.get(offset) * 3), ay = points.get(indices.get(offset) * 3 + 1), az = points.get(indices.get(offset) * 3 + 2);
            double bx = points.get(indices.get(offset + 1) * 3), by = points.get(indices.get(offset + 1) * 3 + 1), bz = points.get(indices.get(offset + 1) * 3 + 2);
            double cx = points.get(indices.get(offset + 2) * 3), cy = points.get(indices.get(offset + 2) * 3 + 1), cz = points.get(indices.get(offset + 2) * 3 + 2);
            double abx = bx - ax, aby = by - ay, abz = bz - az, acx = cx - ax, acy = cy - ay, acz = cz - az;
            double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
            double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(normalLength > Math.max(scale * scale * 1e-24, 1e-30))) return false;
            BigDecimal[] exactNormal = exactNormal(exact[indices.get(offset)], exact[indices.get(offset + 1)], exact[indices.get(offset + 2)]);
            if (exactNormal[0].signum() == 0 && exactNormal[1].signum() == 0 && exactNormal[2].signum() == 0) return false;
            boolean negativeSide = false, positiveSide = false;
            BigDecimal[] origin = exact[indices.get(offset)];
            for (BigDecimal[] point : exact) {
                int side = exactSide(exactNormal, point, origin).signum();
                negativeSide |= side < 0; positiveSide |= side > 0;
                if (negativeSide && positiveSide) return false;
            }
            for (int edge = 0; edge < 3; edge++) {
                int from = edge == 0 ? a : edge == 1 ? b : c, to = edge == 0 ? b : edge == 1 ? c : a;
                Edge key = new Edge(Math.min(from, to), Math.max(from, to));
                OrientedEdge previous = edges.putIfAbsent(key, new OrientedEdge(from, to, face));
                if (previous != null) {
                    if (previous.from == from && previous.to == to || previous.paired) return false;
                    previous.paired = true; faceNeighbors.get(face).add(previous.face); faceNeighbors.get(previous.face).add(face);
                }
            }
            ax -= minX; ay -= minY; az -= minZ; bx -= minX; by -= minY; bz -= minZ; cx -= minX; cy -= minY; cz -= minZ;
            volume += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx);
        }
        if (edges.values().stream().anyMatch(edge -> !edge.paired) || Math.abs(volume) <= scale * scale * scale * 1e-12) return false;
        if (!connected(faceNeighbors, triangles)) return false;
        for (int vertex = 0; vertex < incident.size(); vertex++) {
            if (incident.get(vertex).size() < 3 || !connectedStar(incident.get(vertex), faceNeighbors)) return false;
        }
        return true;
    }

    private static boolean connected(List<Set<Integer>> graph, int count) {
        if (count == 0) return false;
        Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(0); seen.add(0);
        while (!queue.isEmpty()) for (int next : graph.get(queue.remove())) if (seen.add(next)) queue.add(next);
        return seen.size() == count;
    }

    private static boolean connectedStar(Set<Integer> faces, List<Set<Integer>> graph) {
        int start = faces.iterator().next(); Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) for (int next : graph.get(queue.remove())) if (faces.contains(next) && seen.add(next)) queue.add(next);
        return seen.containsAll(faces);
    }

    private static BigDecimal[] exactNormal(BigDecimal[] a, BigDecimal[] b, BigDecimal[] c) {
        BigDecimal abx = b[0].subtract(a[0]), aby = b[1].subtract(a[1]), abz = b[2].subtract(a[2]);
        BigDecimal acx = c[0].subtract(a[0]), acy = c[1].subtract(a[1]), acz = c[2].subtract(a[2]);
        return new BigDecimal[]{aby.multiply(acz).subtract(abz.multiply(acy)),
                abz.multiply(acx).subtract(abx.multiply(acz)), abx.multiply(acy).subtract(aby.multiply(acx))};
    }

    private static BigDecimal exactSide(BigDecimal[] normal, BigDecimal[] point, BigDecimal[] origin) {
        return normal[0].multiply(point[0].subtract(origin[0]))
                .add(normal[1].multiply(point[1].subtract(origin[1])))
                .add(normal[2].multiply(point[2].subtract(origin[2])));
    }

    private record Position(int x, int y, int z) { }
    private static int bits(float value) { return value == 0f ? 0 : Float.floatToIntBits(value); }
    private record Edge(int from, int to) { }
    private static final class OrientedEdge {
        final int from, to, face; boolean paired;
        OrientedEdge(int from, int to, int face) { this.from = from; this.to = to; this.face = face; }
    }
}
