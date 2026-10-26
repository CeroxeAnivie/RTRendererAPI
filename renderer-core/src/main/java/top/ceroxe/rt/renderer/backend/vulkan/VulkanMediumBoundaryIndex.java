package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import top.ceroxe.rt.renderer.api.*;

/** Accepted-scene-only camera containment; geometry and transforms never come from pending writes. */
final class VulkanMediumBoundaryIndex {
    private static final int[] EMPTY_SLOTS = new int[0];
    private final Map<Long, MeshAsset> meshes = new HashMap<>();
    private final Map<Long, VulkanMediumGeometry> meshGeometry = new HashMap<>();
    private final Map<Long, Boundary> boundaries = new HashMap<>();
    private final java.util.Set<Long> transmissiveMaterials = new java.util.HashSet<>();
    private long generation;
    private long queriedGeneration = -1;
    private double cameraX, cameraY, cameraZ;
    private int[] cached = EMPTY_SLOTS;
    private FramePrimitiveBatch cachedFrameBatch;
    private long frameGeneration=-1;
    private double frameX,frameY,frameZ;
    private int[] cachedFrame=EMPTY_SLOTS;

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        if (changes.reset()) { meshes.clear(); meshGeometry.clear(); boundaries.clear(); transmissiveMaterials.clear();
            cachedFrameBatch=null;cachedFrame=EMPTY_SLOTS;cached=EMPTY_SLOTS; }
        for(long id:changes.materials().removedIdentities()) transmissiveMaterials.remove(id);
        for(var write:changes.materials().writes()) {
            if(write.value().transmission()>0) transmissiveMaterials.add(write.id()); else transmissiveMaterials.remove(write.id());
        }
        for (long id : changes.meshes().removedIdentities()) { meshes.remove(id); meshGeometry.remove(id); }
        for (var write : changes.meshes().writes()) { meshes.put(write.id(), write.value()); meshGeometry.remove(write.id()); }
        boolean boundaryChanged=false;
        for (long id : changes.instances().removedIdentities()) boundaryChanged|=boundaries.remove(id)!=null;
        for (var write : changes.instances().writes()) {
            if (write.value().mediumBoundary().isEmpty()) boundaryChanged|=boundaries.remove(write.id())!=null;
            else {boundaries.put(write.id(), new Boundary(write.slot(), write.value()));boundaryChanged=true;}
        }
        if(changes.reset() || changes.meshes().statistics().writes()!=0 || changes.meshes().statistics().removals()!=0
                || boundaryChanged) generation++;
    }

    void clear() { meshes.clear(); meshGeometry.clear(); boundaries.clear(); transmissiveMaterials.clear();
        cached=EMPTY_SLOTS;cachedFrame=EMPTY_SLOTS;cachedFrameBatch=null;generation++; }

    int transmissiveCount() { return transmissiveMaterials.size(); }

    int boundaryCount() { return boundaries.size(); }

    int frameBoundaryCount(FramePrimitiveBatch batch) {
        return boundaries.size()+batch.mediumBoundaryIndices().size();
    }

    int[] containing(RenderFrameRequest request) {
        int[] persistent=containing(request.camera());
        if(request.primitiveBatch().mediumBoundaryIndices().isEmpty()) {
            cachedFrameBatch=null;cachedFrame=EMPTY_SLOTS;return persistent;
        }
        var camera=request.camera();
        if(cachedFrameBatch==request.primitiveBatch() && frameGeneration==generation
                && frameX==camera.x() && frameY==camera.y() && frameZ==camera.z()) return cachedFrame;
        var selected=new ArrayList<Integer>();
        for(int slot:persistent) selected.add(slot);
        var primitives=request.primitiveBatch().primitives();
        for(int i:request.primitiveBatch().mediumBoundaryIndices()) {
            var primitive=primitives.get(i);
            MeshAsset mesh=meshes.get(primitive.meshAssetId());
            if(mesh==null || !mesh.isClosedManifold())
                throw new FrameValidationException(FrameValidationException.Reason.INVALID_MEDIUM_BOUNDARY,
                        "frame medium boundary " + i + " requires a resident closed manifold mesh");
            if(meshGeometry.computeIfAbsent(mesh.id(),ignored->new VulkanMediumGeometry(mesh)).contains(primitive.transform(),camera.x(),camera.y(),camera.z()))
                selected.add(VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT | i);
        }
        if(selected.size()>MediumTransportOptions.MAX_NESTED_BOUNDARIES)
            throw new FrameValidationException(FrameValidationException.Reason.MEDIUM_NESTING_EXCEEDS_LIMIT,
                    "combined persistent/frame medium containment exceeds " + MediumTransportOptions.MAX_NESTED_BOUNDARIES);
        cachedFrame=selected.stream().mapToInt(Integer::intValue).toArray();
        cachedFrameBatch=request.primitiveBatch();frameGeneration=generation;
        frameX=camera.x();frameY=camera.y();frameZ=camera.z();
        return cachedFrame;
    }

    int[] containing(CameraState camera) {
        if (boundaries.isEmpty()) return EMPTY_SLOTS;
        if (generation == queriedGeneration && camera.x() == cameraX && camera.y() == cameraY && camera.z() == cameraZ)
            return cached;
        var containing = new ArrayList<Boundary>();
        for (var boundary : boundaries.values()) {
            MeshAsset mesh = meshes.get(boundary.instance.meshAssetId());
            if (mesh != null && meshGeometry.computeIfAbsent(mesh.id(),ignored->new VulkanMediumGeometry(mesh)).contains(boundary.instance.transform(),camera.x(),camera.y(),camera.z()))
                containing.add(boundary);
        }
        if (containing.size() > MediumTransportOptions.MAX_NESTED_BOUNDARIES)
            throw new FrameValidationException(FrameValidationException.Reason.MEDIUM_NESTING_EXCEEDS_LIMIT,
                    "camera is inside " + containing.size() + " medium boundaries; limit is "
                            + MediumTransportOptions.MAX_NESTED_BOUNDARIES);
        containing.sort(Comparator.comparingInt((Boundary b) -> b.instance.mediumBoundary().orElseThrow().priority())
                .thenComparingLong(b -> b.instance.id()));
        cached = containing.stream().mapToInt(Boundary::slot).toArray();
        cameraX = camera.x(); cameraY = camera.y(); cameraZ = camera.z(); queriedGeneration = generation;
        return cached;
    }

    static boolean contains(MeshAsset mesh, AffineTransform transform, double x, double y, double z) {
        return new VulkanMediumGeometry(mesh).contains(transform,x,y,z);
    }

    private record Boundary(int slot, SceneInstance instance) {}
}
