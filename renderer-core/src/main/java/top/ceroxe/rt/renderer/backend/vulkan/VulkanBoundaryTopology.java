package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeSet;
import top.ceroxe.rt.renderer.api.MeshAsset;

/** Immutable, position-based triangle incidence, prepared with its index generation. */
final class VulkanBoundaryTopology {
    static final int MAGIC = 0x42545032;
    static final String PROPERTY = "top.ceroxe.rt.medium.preparedBoundaryTopology";
    private static final int MAX_NEIGHBORS = 1_048_576;

    private VulkanBoundaryTopology() {}

    private record Point(int x, int y, int z) {
        static Point at(FloatBuffer positions, int vertex) {
            int at = positions.position() + vertex * 3;
            return new Point(bits(positions.get(at)), bits(positions.get(at + 1)), bits(positions.get(at + 2)));
        }
        // GLSL equal() treats both signed zeros as the same position.
        private static int bits(float value) { return value == 0 ? 0 : Float.floatToRawIntBits(value); }
    }

    static int[] pack(MeshAsset mesh) {
        IntBuffer indices = mesh.triangleIndices();
        int count = mesh.triangleCount(), indexWords = indices.remaining();
        int[] fallback = new int[Math.addExact(indexWords, 1)];
        indices.get(fallback, 0, indexWords);
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) return fallback;

        // Match geometric positions, including UV seams and duplicated vertices.
        FloatBuffer positions = mesh.positions();
        Point[] vertices = new Point[mesh.vertexCount()];
        for (int i = 0; i < vertices.length; ++i) vertices[i] = Point.at(positions, i);
        var incident = new HashMap<Point, ArrayList<Integer>>();
        for (int triangle = 0; triangle < count; ++triangle) {
            Point last0 = null, last1 = null;
            for (int axis = 0; axis < 3; ++axis) {
                Point point = vertices[fallback[triangle * 3 + axis]];
                if (!point.equals(last0) && !point.equals(last1))
                    incident.computeIfAbsent(point, ignored -> new ArrayList<>()).add(triangle);
                if (axis == 0) last0 = point; else last1 = point;
            }
        }
        long limit = Math.min(MAX_NEIGHBORS, Math.max(24L, count * 24L));
        int[] offsets = new int[Math.addExact(count, 1)];
        var neighbors = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int triangle = 0; triangle < count; ++triangle) {
            var sorted = new TreeSet<Integer>();
            for (int axis = 0; axis < 3; ++axis) {
                var list = incident.get(vertices[fallback[triangle * 3 + axis]]);
                if (list.size() > limit - neighbors.size()) return fallback;
                sorted.addAll(list);
            }
            if (sorted.size() > limit - neighbors.size()) return fallback;
            offsets[triangle] = 1 + offsets.length + neighbors.size();
            for (int other : sorted) neighbors.add(other);
        }
        offsets[count] = 1 + offsets.length + neighbors.size();
        int[] packed = java.util.Arrays.copyOf(fallback, Math.addExact(indexWords, Math.addExact(offsets[count], Math.multiplyExact(count, 2))));
        packed[indexWords] = MAGIC;
        System.arraycopy(offsets, 0, packed, indexWords + 1, offsets.length);
        neighbors.getElements(0, packed, indexWords + 1 + offsets.length, neighbors.size());
        // A certified object-space coordinate plane belongs to this same immutable
        // position generation. The per-instance shader accepts it only when an
        // exact zero pattern proves one independent world coordinate as well.
        int planeBase = indexWords + offsets[count];
        for (int triangle = 0; triangle < count; ++triangle) {
            int a = fallback[triangle * 3] * 3, b = fallback[triangle * 3 + 1] * 3,
                    c = fallback[triangle * 3 + 2] * 3;
            for (int axis = 0; axis < 3; ++axis) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3, start = positions.position();
                float plane = positions.get(start + a + axis);
                double area = ((double) positions.get(start + b + u) - positions.get(start + a + u))
                        * ((double) positions.get(start + c + v) - positions.get(start + a + v))
                        - ((double) positions.get(start + b + v) - positions.get(start + a + v))
                        * ((double) positions.get(start + c + u) - positions.get(start + a + u));
                if (plane == positions.get(start + b + axis) && plane == positions.get(start + c + axis)
                        && Float.isFinite(plane) && Double.isFinite(area) && area != 0) {
                    packed[planeBase + triangle * 2] = axis + 1;
                    packed[planeBase + triangle * 2 + 1] = Float.floatToRawIntBits(plane);
                    break;
                }
            }
        }
        return packed;
    }
}
