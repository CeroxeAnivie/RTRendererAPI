package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MeshAsset;

/** Immutable triangle BVH. Instances share geometry; transforms are handled outside the tree. */
final class MediumMeshAcceleration {
    private static final int LEAF_SIZE = 8;
    private static final int AMBIGUOUS = Integer.MIN_VALUE;
    private static final int ON_BOUNDARY = Integer.MAX_VALUE;
    private static final double[][] RAYS = {
            {1, .3713906763541037, .6947465906068658},
            {.537190783, 1, .219377619}, {.129831703, .66137191, 1}};
    private final FloatBuffer positions;
    private final IntBuffer indices;
    private final int[] triangles, start, end, left, right;
    private final double[] bounds;
    private final double extent;
    private int nodeCount;

    MediumMeshAcceleration(MeshAsset mesh) {
        positions = mesh.positions();
        indices = mesh.triangleIndices();
        triangles = new int[mesh.triangleCount()];
        int capacity = Math.max(1, Math.multiplyExact(triangles.length, 2));
        start = new int[capacity]; end = new int[capacity];
        left = new int[capacity]; right = new int[capacity];
        bounds = new double[Math.multiplyExact(capacity, 6)];
        for (int i = 0; i < triangles.length; i++) triangles[i] = i;
        build(0, triangles.length);
        extent = Math.max(bounds[3] - bounds[0], Math.max(bounds[4] - bounds[1], bounds[5] - bounds[2]));
    }

    boolean contains(AffineTransform transform, double x, double y, double z) {
        double[] inverse = inverse(transform);
        return containsTransformed(inverse, x, y, z);
    }

    boolean containsTransformed(double[] inverse, double x, double y, double z) {
        return containsLocal(inverse[0] * x + inverse[1] * y + inverse[2] * z + inverse[3],
                inverse[4] * x + inverse[5] * y + inverse[6] * z + inverse[7],
                inverse[8] * x + inverse[9] * y + inverse[10] * z + inverse[11]);
    }

    boolean containsLocal(double x, double y, double z) {
        if (!inside(bounds, 0, x, y, z)) return false;
        for (double[] ray : RAYS) {
            int crossings = crossings(0, x, y, z, ray[0], ray[1], ray[2]);
            if (crossings == ON_BOUNDARY) return true;
            if (crossings != AMBIGUOUS) return crossings != 0;
        }
        return windingContains(x, y, z);
    }

    private int build(int begin, int finish) {
        int node = nodeCount++, base = node * 6;
        start[node] = begin; end[node] = finish;
        left[node] = right[node] = -1;
        bounds[base] = bounds[base + 1] = bounds[base + 2] = Double.POSITIVE_INFINITY;
        bounds[base + 3] = bounds[base + 4] = bounds[base + 5] = Double.NEGATIVE_INFINITY;
        for (int item = begin; item < finish; item++) for (int vertex = 0; vertex < 3; vertex++) {
            int p = indices.get(triangles[item] * 3 + vertex) * 3;
            for (int axis = 0; axis < 3; axis++) {
                double value = positions.get(p + axis);
                bounds[base + axis] = Math.min(bounds[base + axis], value);
                bounds[base + 3 + axis] = Math.max(bounds[base + 3 + axis], value);
            }
        }
        if (finish - begin > LEAF_SIZE) {
            double dx = bounds[base + 3] - bounds[base], dy = bounds[base + 4] - bounds[base + 1];
            double dz = bounds[base + 5] - bounds[base + 2];
            int axis = dx >= dy && dx >= dz ? 0 : dy >= dz ? 1 : 2;
            int middle = (begin + finish) >>> 1;
            select(begin, finish - 1, middle, axis);
            left[node] = build(begin, middle); right[node] = build(middle, finish);
        }
        return node;
    }

    private void select(int low, int high, int middle, int axis) {
        while (low < high) {
            double pivot = centroid(triangles[(low + high) >>> 1], axis);
            int a = low, b = high;
            while (a <= b) {
                while (centroid(triangles[a], axis) < pivot) a++;
                while (centroid(triangles[b], axis) > pivot) b--;
                if (a <= b) { int value = triangles[a]; triangles[a++] = triangles[b]; triangles[b--] = value; }
            }
            if (middle <= b) high = b;
            else if (middle >= a) low = a;
            else return;
        }
    }

    private double centroid(int triangle, int axis) {
        int base = triangle * 3;
        return (double) positions.get(indices.get(base) * 3 + axis)
                + positions.get(indices.get(base + 1) * 3 + axis)
                + positions.get(indices.get(base + 2) * 3 + axis);
    }

    private int crossings(int node, double x, double y, double z, double dx, double dy, double dz) {
        int base = node * 6;
        double near = Math.max(0, Math.max(Math.min((bounds[base] - x) / dx, (bounds[base + 3] - x) / dx),
                Math.max(Math.min((bounds[base + 1] - y) / dy, (bounds[base + 4] - y) / dy),
                        Math.min((bounds[base + 2] - z) / dz, (bounds[base + 5] - z) / dz))));
        double far = Math.min(Math.max((bounds[base] - x) / dx, (bounds[base + 3] - x) / dx),
                Math.min(Math.max((bounds[base + 1] - y) / dy, (bounds[base + 4] - y) / dy),
                        Math.max((bounds[base + 2] - z) / dz, (bounds[base + 5] - z) / dz)));
        if (far < near) return 0;
        if (left[node] >= 0) {
            int a = crossings(left[node], x, y, z, dx, dy, dz);
            if (a == AMBIGUOUS || a == ON_BOUNDARY) return a;
            int b = crossings(right[node], x, y, z, dx, dy, dz);
            return b == AMBIGUOUS || b == ON_BOUNDARY ? b : a + b;
        }
        int count = 0;
        for (int i = start[node]; i < end[node]; i++) {
            int triangle = triangles[i] * 3;
            int a = indices.get(triangle) * 3, b = indices.get(triangle + 1) * 3, c = indices.get(triangle + 2) * 3;
            double ax = positions.get(a), ay = positions.get(a + 1), az = positions.get(a + 2);
            double ex = positions.get(b) - ax, ey = positions.get(b + 1) - ay, ez = positions.get(b + 2) - az;
            double fx = positions.get(c) - ax, fy = positions.get(c + 1) - ay, fz = positions.get(c + 2) - az;
            double hx = dy * fz - dz * fy, hy = dz * fx - dx * fz, hz = dx * fy - dy * fx;
            double det = ex * hx + ey * hy + ez * hz;
            double determinantScale = Math.sqrt((hx * hx + hy * hy + hz * hz) * (ex * ex + ey * ey + ez * ez));
            if (Math.abs(det) <= determinantScale * 1e-13) continue;
            double tx = x - ax, ty = y - ay, tz = z - az;
            double u = (tx * hx + ty * hy + tz * hz) / det;
            if (u < -1e-10 || u > 1 + 1e-10) continue;
            double qx = ty * ez - tz * ey, qy = tz * ex - tx * ez, qz = tx * ey - ty * ex;
            double v = (dx * qx + dy * qy + dz * qz) / det;
            if (v < -1e-10 || u + v > 1 + 1e-10) continue;
            double distance = (fx * qx + fy * qy + fz * qz) / det;
            double tolerance = extent * 1e-12;
            if (distance < -tolerance) continue;
            if (distance <= tolerance) return ON_BOUNDARY;
            if (u <= 1e-10 || v <= 1e-10 || 1 - u - v <= 1e-10) return AMBIGUOUS;
            count += det < 0 ? 1 : -1;
        }
        return count;
    }

    /** Rare fallback for rays that repeatedly land on mesh edges or vertices. */
    private boolean windingContains(double x, double y, double z) {
        double angle = 0;
        for (int triangle = 0; triangle < indices.limit(); triangle += 3) {
            int a = indices.get(triangle) * 3, b = indices.get(triangle + 1) * 3, c = indices.get(triangle + 2) * 3;
            double ax = positions.get(a) - x, ay = positions.get(a + 1) - y, az = positions.get(a + 2) - z;
            double bx = positions.get(b) - x, by = positions.get(b + 1) - y, bz = positions.get(b + 2) - z;
            double cx = positions.get(c) - x, cy = positions.get(c + 1) - y, cz = positions.get(c + 2) - z;
            double al = Math.sqrt(ax * ax + ay * ay + az * az), bl = Math.sqrt(bx * bx + by * by + bz * bz);
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (al == 0 || bl == 0 || cl == 0) return true;
            double numerator = ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx);
            double denominator = al * bl * cl + (ax * bx + ay * by + az * bz) * cl
                    + (bx * cx + by * cy + bz * cz) * al + (cx * ax + cy * ay + cz * az) * bl;
            angle += 2 * Math.atan2(numerator, denominator);
        }
        return Math.abs(angle) >= 2 * Math.PI * (1 - 1e-10);
    }

    static boolean inside(double[] box, int offset, double x, double y, double z) {
        return x >= box[offset] && y >= box[offset + 1] && z >= box[offset + 2]
                && x <= box[offset + 3] && y <= box[offset + 4] && z <= box[offset + 5];
    }

    static double[] bounds(MeshAsset mesh) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        var vertices = mesh.positions(); var indices = mesh.triangleIndices();
        while (indices.hasRemaining()) {
            int vertex = indices.get() * 3;
            for (int axis = 0; axis < 3; axis++) {
                double value = vertices.get(vertex + axis);
                box[axis] = Math.min(box[axis], value); box[axis + 3] = Math.max(box[axis + 3], value);
            }
        }
        return box;
    }

    static double[] inverse(AffineTransform t) {
        double a = t.element(0), b = t.element(1), c = t.element(2), d = t.element(4), e = t.element(5), f = t.element(6);
        double g = t.element(8), h = t.element(9), i = t.element(10);
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        double[] inverse = {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, 0,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det, 0,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det, 0};
        for (int row = 0; row < 3; row++) inverse[row * 4 + 3] = -inverse[row * 4] * t.element(3)
                - inverse[row * 4 + 1] * t.element(7) - inverse[row * 4 + 2] * t.element(11);
        return inverse;
    }
}
