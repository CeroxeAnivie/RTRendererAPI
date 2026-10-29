package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import top.ceroxe.rt.renderer.api.*;

/** Accepted-scene boundary index with an instance BVH and lazily shared triangle BVHs. */
final class MediumSceneIndex {
    static final String CONVEX_PACKET_PROPERTY="top.ceroxe.rt.medium.cuda.convexPacketBounds";
    private static final int[] EMPTY = new int[0];
    private static final Comparator<Volume> PRIORITY = Comparator.comparingInt(Volume::priority)
            .thenComparing(Volume::frameLocal).thenComparingLong(Volume::identity);
    private final Map<Long, MeshEntry> meshes = new HashMap<>();
    private final Map<Long, Boundary> boundaries = new HashMap<>();
    private final Map<Long, Integer> meshBoundaryReferences = new HashMap<>();
    private final Map<Long, DensityTexture> densityTextures = new HashMap<>();
    private long textureGeneration, validatedTextureGeneration = -1;
    private FramePrimitiveBatch validatedDensityBatch, classifiedBatch;
    private int densityBoundaries, scatteringBoundaries, surfaceBoundaries;
    private int batchDensityBoundaries, batchScatteringBoundaries, batchSurfaceBoundaries;
    private long generation, persistentGeneration;
    private long treeGeneration = -1, batchGeneration = -1, cachedGeneration = -1;
    private VolumeTree persistentTree = VolumeTree.EMPTY, frameTree = VolumeTree.EMPTY;
    private FramePrimitiveBatch treeBatch, cachedBatch;
    private double cameraX, cameraY, cameraZ;
    private int[] cached = EMPTY;

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        if (changes.reset() || changes.textures().statistics().writes() != 0 || changes.textures().statistics().removals() != 0) {
            if (changes.reset()) densityTextures.clear();
            for (long identity : changes.textures().removedIdentities()) densityTextures.remove(identity);
            for (var write : changes.textures().writes())
                densityTextures.put(write.id(), new DensityTexture(write.slot(), write.value()));
            textureGeneration++;
        }
        if (!changes.reset() && changes.meshes().statistics().writes() == 0
                && changes.meshes().statistics().removals() == 0 && changes.instances().statistics().writes() == 0
                && changes.instances().statistics().removals() == 0) return;
        boolean changed = changes.reset();
        if (changes.reset()) {
            meshes.clear(); boundaries.clear(); meshBoundaryReferences.clear(); densityBoundaries = scatteringBoundaries = surfaceBoundaries = 0;
        }
        for (long identity : changes.meshes().removedIdentities()) {
            meshes.remove(identity); changed |= meshBoundaryReferences.containsKey(identity);
        }
        for (var write : changes.meshes().writes()) {
            meshes.put(write.id(), new MeshEntry(write.value())); changed |= meshBoundaryReferences.containsKey(write.id());
        }
        for (long identity : changes.instances().removedIdentities()) changed |= removeBoundary(identity);
        for (var write : changes.instances().writes()) {
            SceneInstance instance = write.value();
            Boundary previous = boundaries.get(write.id());
            if (instance.mediumBoundary().isEmpty()) changed |= removeBoundary(write.id());
            else if (previous == null || previous.slot != write.slot() || !previous.instance.equals(instance)) {
                if (previous != null) classify(previous.instance.mediumBoundary().orElseThrow(), -1);
                classify(instance.mediumBoundary().orElseThrow(), 1);
                if (previous == null || previous.instance.meshAssetId() != instance.meshAssetId()) {
                    if (previous != null) removeMeshReference(previous.instance.meshAssetId());
                    meshBoundaryReferences.merge(instance.meshAssetId(), 1, Math::addExact);
                }
                boundaries.put(write.id(), new Boundary(write.slot(), instance)); changed = true;
            }
        }
        if (changed) persistentGeneration++;
        // Frame-local volumes can reference any resident mesh. An asset generation changes their tree.
        if (changed || changes.meshes().statistics().writes() != 0 || changes.meshes().statistics().removals() != 0)
            generation++;
    }

    void clear() {
        meshes.clear(); boundaries.clear(); meshBoundaryReferences.clear(); generation++; persistentGeneration++;
        densityTextures.clear(); textureGeneration++; validatedDensityBatch = classifiedBatch = null;
        densityBoundaries = scatteringBoundaries = surfaceBoundaries = 0;
        batchDensityBoundaries = batchScatteringBoundaries = batchSurfaceBoundaries = 0;
        persistentTree = frameTree = VolumeTree.EMPTY;
        cached = EMPTY; cachedBatch = treeBatch = null;
    }

    int boundaryCount() { return boundaries.size(); }

    private boolean removeBoundary(long identity) {
        Boundary previous = boundaries.remove(identity);
        if (previous == null) return false;
        removeMeshReference(previous.instance.meshAssetId());
        classify(previous.instance.mediumBoundary().orElseThrow(), -1);
        return true;
    }

    boolean hasDensityField(FramePrimitiveBatch batch) { classify(batch); return densityBoundaries + batchDensityBoundaries != 0; }
    boolean hasScattering(FramePrimitiveBatch batch) { classify(batch); return scatteringBoundaries + batchScatteringBoundaries != 0; }
    boolean hasSurfaceInteraction(FramePrimitiveBatch batch) { classify(batch); return surfaceBoundaries + batchSurfaceBoundaries != 0; }

    private void classify(MediumBoundary boundary, int delta) {
        var medium = boundary.medium();
        if (MediumDensitySupport.heterogeneous(medium)) densityBoundaries += delta;
        if (medium.density() > 0 && !medium.scattering().isZero()) scatteringBoundaries += delta;
        if (boundary.surfaceInteraction()) surfaceBoundaries += delta;
    }

    private void classify(FramePrimitiveBatch batch) {
        if (classifiedBatch == batch) return;
        batchDensityBoundaries = batchScatteringBoundaries = batchSurfaceBoundaries = 0;
        for (int index : batch.mediumBoundaryIndices()) {
            var boundary = batch.primitives().get(index).mediumBoundary().orElseThrow();
            var medium = boundary.medium();
            if (MediumDensitySupport.heterogeneous(medium)) batchDensityBoundaries++;
            if (medium.density() > 0 && !medium.scattering().isZero()) batchScatteringBoundaries++;
            if (boundary.surfaceInteraction()) batchSurfaceBoundaries++;
        }
        classifiedBatch = batch;
    }

    int densityTextureSlot(long identity) {
        var texture = densityTextures.get(identity);
        return texture == null ? -1 : texture.slot;
    }

    /** Immutable asset upload source; the native executor caches it by accepted scene state. */
    TextureAsset densityTexture(long identity) {
        var texture = densityTextures.get(identity);
        return texture == null ? null : texture.asset;
    }

    void validateDensityFields(RenderFrameRequest request) {
        validateDensity(request.environment().cameraMedium().densityField(), "environment");
        var batch = request.primitiveBatch();
        if (validatedDensityBatch == batch && validatedTextureGeneration == textureGeneration) return;
        for (int index : batch.mediumBoundaryIndices()) {
            var primitive = batch.primitives().get(index);
            var medium = primitive.mediumBoundary().orElseThrow().medium();
            validateDensity(medium.densityField(), "frame primitive " + index);
            if (MediumDensitySupport.heterogeneous(medium)) try {
                MediumDensitySupport.worldToDensity(medium.densityField(), primitive.transform());
            } catch (IllegalArgumentException invalid) {
                throw new FrameValidationException(FrameValidationException.Reason.INVALID_MEDIUM_DENSITY, invalid.getMessage());
            }
        }
        validatedDensityBatch = batch; validatedTextureGeneration = textureGeneration;
    }

    private void validateDensity(MediumDensityField field, String owner) {
        if (field.isUniform()) return;
        var texture = densityTextures.get(field.textureAssetId());
        String error = MediumDensitySupport.invalidTexture(field, texture == null ? null : texture.asset);
        if (error != null) throw new FrameValidationException(FrameValidationException.Reason.INVALID_MEDIUM_DENSITY, owner + ": " + error);
    }

    private void removeMeshReference(long identity) {
        meshBoundaryReferences.compute(identity, (ignored, count) -> count == 1 ? null : count - 1);
    }

    int frameBoundaryCount(FramePrimitiveBatch batch) {
        return Math.addExact(boundaries.size(), batch.mediumBoundaryIndices().size());
    }

    int[] containing(CameraState camera) { return containing(camera, FramePrimitiveBatch.empty()); }

    int[] containing(RenderFrameRequest request) { return containing(request.camera(), request.primitiveBatch()); }

    /** Immutable accepted-frame metadata; coefficients never travel through the host per ray. */
    NativeScene nativeScene(RenderFrameRequest request) {
        var batch = request.primitiveBatch();
        ensureTrees(batch);
        var entries = new ArrayList<NativeBoundary>(frameBoundaryCount(batch));
        long triangleCount=0, eventUpperBound=0;
        for (Boundary indexed : boundaries.values()) {
            var instance = indexed.instance;
            MeshEntry mesh=meshes.get(instance.meshAssetId());
            triangleCount=Math.addExact(triangleCount,mesh.mesh.triangleCount());
            eventUpperBound=Math.addExact(eventUpperBound,mesh.eventUpperBound());
            entries.add(new NativeBoundary(indexed.slot, instance.mediumBoundary().orElseThrow(), instance.transform()));
        }
        for (int index : batch.mediumBoundaryIndices()) {
            var primitive = batch.primitives().get(index);
            MeshEntry mesh=meshes.get(primitive.meshAssetId());
            triangleCount=Math.addExact(triangleCount,mesh.mesh.triangleCount());
            eventUpperBound=Math.addExact(eventUpperBound,mesh.eventUpperBound());
            entries.add(new NativeBoundary(VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT | index,
                    primitive.mediumBoundary().orElseThrow(), primitive.transform()));
        }
        entries.sort((left, right) -> Integer.compareUnsigned(left.slot(), right.slot()));
        return new NativeScene(entries, containing(request),triangleCount,eventUpperBound);
    }

    record NativeBoundary(int slot, MediumBoundary boundary, AffineTransform objectToWorld) { }

    record NativeScene(List<NativeBoundary> boundaries, int[] containing, long boundaryTriangleCount, long boundaryEventUpperBound) {
        NativeScene(List<NativeBoundary> boundaries,int[] containing) {this(boundaries,containing,Long.MAX_VALUE,Long.MAX_VALUE);}
        NativeScene(List<NativeBoundary> boundaries,int[] containing,long boundaryTriangleCount) {this(boundaries,containing,boundaryTriangleCount,boundaryTriangleCount);}
        /** A straight ray hits each instanced triangle at most once; unknown geometry retains 32 events. */
        int packetCapacity(int maxEvents) {
            return Math.max(1,(int)Math.min(Math.min(32,maxEvents),boundaryEventUpperBound));
        }
        NativeScene {
            if(boundaryTriangleCount<0||boundaryEventUpperBound<0)throw new IllegalArgumentException("negative medium event bound");
            boundaries = List.copyOf(boundaries);
            containing = containing.clone();
        }

        @Override public int[] containing() { return containing.clone(); }
    }

    private int[] containing(CameraState camera, FramePrimitiveBatch batch) {
        if (cachedGeneration == generation && cachedBatch == batch && cameraX == camera.x()
                && cameraY == camera.y() && cameraZ == camera.z()) return cached;
        ensureTrees(batch);
        var selected = new ArrayList<Volume>();
        persistentTree.collect(camera.x(), camera.y(), camera.z(), selected);
        frameTree.collect(camera.x(), camera.y(), camera.z(), selected);
        selected.sort(PRIORITY);
        cached = selected.isEmpty() ? EMPTY : selected.stream().mapToInt(Volume::slot).toArray();
        cachedBatch = batch; cachedGeneration = generation;
        cameraX = camera.x(); cameraY = camera.y(); cameraZ = camera.z();
        return cached;
    }

    int[] containingPoint(FramePrimitiveBatch batch, double x, double y, double z) {
        ensureTrees(batch);
        var selected = new ArrayList<Volume>();
        persistentTree.collect(x, y, z, selected); frameTree.collect(x, y, z, selected);
        selected.sort(PRIORITY);
        return selected.isEmpty() ? EMPTY : selected.stream().mapToInt(Volume::slot).toArray();
    }

    void validateCausticRegion(FramePrimitiveBatch batch, Caustics region) {
        ensureTrees(batch);
        for (var tree : List.of(persistentTree, frameTree)) for (var volume : tree.volumes) {
            var bounds = volume.bounds;
            double x = Math.max(Math.abs(bounds[0] - region.centerX()), Math.abs(bounds[3] - region.centerX()));
            double y = Math.max(Math.abs(bounds[1] - region.centerY()), Math.abs(bounds[4] - region.centerY()));
            double z = Math.max(Math.abs(bounds[2] - region.centerZ()), Math.abs(bounds[5] - region.centerZ()));
            if (Math.sqrt(x*x + y*y + z*z) >= region.radius()) throw new FrameValidationException(
                    FrameValidationException.Reason.INVALID_CAUSTICS,
                    "caustic sphere must strictly enclose all explicit medium boundary world bounds");
        }
    }

    private void ensureTrees(FramePrimitiveBatch batch) {
        if (treeGeneration != persistentGeneration) {
            var volumes = new ArrayList<Volume>(boundaries.size());
            for (Boundary boundary : boundaries.values()) {
                var instance = boundary.instance;
                volumes.add(volume(boundary.slot, instance.id(), false, instance.meshAssetId(), instance.transform(),
                        instance.mediumBoundary().orElseThrow()));
            }
            persistentTree = new VolumeTree(volumes);
            treeGeneration = persistentGeneration;
        }
        if (treeBatch != batch || batchGeneration != generation) {
            var volumes = new ArrayList<Volume>(batch.mediumBoundaryIndices().size());
            for (int index : batch.mediumBoundaryIndices()) {
                var primitive = batch.primitives().get(index);
                volumes.add(volume(VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT | index, index, true,
                        primitive.meshAssetId(), primitive.transform(), primitive.mediumBoundary().orElseThrow()));
            }
            frameTree = new VolumeTree(volumes);
            treeBatch = batch; batchGeneration = generation;
        }
    }

    private Volume volume(int slot, long identity, boolean frameLocal, long meshIdentity, AffineTransform transform,
            MediumBoundary boundary) {
        MeshEntry mesh = meshes.get(meshIdentity);
        if (mesh == null || !mesh.closed()) throw new FrameValidationException(
                FrameValidationException.Reason.INVALID_MEDIUM_BOUNDARY,
                "medium boundary requires a resident closed manifold mesh: " + meshIdentity);
        return new Volume(slot, identity, frameLocal, boundary.priority(), mesh, MediumMeshAcceleration.inverse(transform),
                transformedBounds(mesh.bounds(), transform));
    }

    static boolean contains(MeshAsset mesh, AffineTransform transform, double x, double y, double z) {
        return new MediumMeshAcceleration(mesh).contains(transform, x, y, z);
    }

    private static double[] transformedBounds(double[] local, AffineTransform transform) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            double x = local[(corner & 1) == 0 ? 0 : 3], y = local[(corner & 2) == 0 ? 1 : 4];
            double z = local[(corner & 4) == 0 ? 2 : 5];
            for (int axis = 0; axis < 3; axis++) {
                int row = axis * 4;
                double value = transform.element(row) * x + transform.element(row + 1) * y
                        + transform.element(row + 2) * z + transform.element(row + 3);
                box[axis] = Math.min(box[axis], value); box[axis + 3] = Math.max(box[axis + 3], value);
            }
        }
        return box;
    }

    private record Boundary(int slot, SceneInstance instance) {}
    private record DensityTexture(int slot, TextureAsset asset) {}
    private record Volume(int slot, long identity, boolean frameLocal, int priority, MeshEntry mesh,
            double[] inverse, double[] bounds) {}

    private static final class MeshEntry {
        private final MeshAsset mesh;
        private double[] bounds;
        private MediumMeshAcceleration acceleration;
        private Boolean closed;
        private Boolean convex;
        private MeshEntry(MeshAsset mesh) { this.mesh = mesh; }
        private boolean closed() { if (closed == null) closed = mesh.isClosedManifold(); return closed; }
        private int eventUpperBound() {
            if (convex == null) convex = MediumConvexMeshBound.convexClosed(mesh);
            return Boolean.parseBoolean(System.getProperty(CONVEX_PACKET_PROPERTY,"true")) && convex ? 2 : mesh.triangleCount();
        }
        private double[] bounds() { if (bounds == null) bounds = MediumMeshAcceleration.bounds(mesh); return bounds; }
        private boolean contains(double[] inverse, double x, double y, double z) {
            if (acceleration == null) acceleration = new MediumMeshAcceleration(mesh);
            return acceleration.containsTransformed(inverse, x, y, z);
        }
    }

    /** Point queries visit only world-space boxes containing the camera, including moving cameras. */
    private static final class VolumeTree {
        private static final VolumeTree EMPTY = new VolumeTree(List.of());
        private final Volume[] volumes;
        private final double[] bounds;
        private final int[] start, end, left, right;
        private int nodes;

        private VolumeTree(List<Volume> source) {
            volumes = source.toArray(Volume[]::new);
            int capacity = Math.max(1, Math.multiplyExact(volumes.length, 2));
            bounds = new double[Math.multiplyExact(capacity, 6)];
            start = new int[capacity]; end = new int[capacity]; left = new int[capacity]; right = new int[capacity];
            if (volumes.length != 0) build(0, volumes.length);
        }

        private int build(int begin, int finish) {
            int node = nodes++, base = node * 6;
            start[node] = begin; end[node] = finish; left[node] = right[node] = -1;
            bounds[base] = bounds[base + 1] = bounds[base + 2] = Double.POSITIVE_INFINITY;
            bounds[base + 3] = bounds[base + 4] = bounds[base + 5] = Double.NEGATIVE_INFINITY;
            for (int i = begin; i < finish; i++) for (int axis = 0; axis < 3; axis++) {
                bounds[base + axis] = Math.min(bounds[base + axis], volumes[i].bounds[axis]);
                bounds[base + 3 + axis] = Math.max(bounds[base + 3 + axis], volumes[i].bounds[3 + axis]);
            }
            if (finish - begin > 4) {
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
                double pivot = center(volumes[(low + high) >>> 1], axis);
                int a = low, b = high;
                while (a <= b) {
                    while (center(volumes[a], axis) < pivot) a++;
                    while (center(volumes[b], axis) > pivot) b--;
                    if (a <= b) { Volume value = volumes[a]; volumes[a++] = volumes[b]; volumes[b--] = value; }
                }
                if (middle <= b) high = b;
                else if (middle >= a) low = a;
                else return;
            }
        }

        private static double center(Volume volume, int axis) { return volume.bounds[axis] + volume.bounds[axis + 3]; }

        private void collect(double x, double y, double z, List<Volume> selected) {
            if (volumes.length != 0) collect(0, x, y, z, selected);
        }

        private void collect(int node, double x, double y, double z, List<Volume> selected) {
            if (!MediumMeshAcceleration.inside(bounds, node * 6, x, y, z)) return;
            if (left[node] >= 0) {
                collect(left[node], x, y, z, selected); collect(right[node], x, y, z, selected); return;
            }
            for (int i = start[node]; i < end[node]; i++) {
                Volume volume = volumes[i];
                if (MediumMeshAcceleration.inside(volume.bounds, 0, x, y, z)
                        && volume.mesh.contains(volume.inverse, x, y, z)) {
                    if (selected.size() == MediumTransport.MAX_NESTED_BOUNDARIES) throw new FrameValidationException(
                            FrameValidationException.Reason.MEDIUM_NESTING_EXCEEDS_LIMIT,
                            "camera medium nesting exceeds " + MediumTransport.MAX_NESTED_BOUNDARIES);
                    selected.add(volume);
                }
            }
        }
    }
}
