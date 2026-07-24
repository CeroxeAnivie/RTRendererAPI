package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable section-local neighborhood used while building renderer-owned RT
 * geometry.
 *
 * <p>sourceEngine block and fluid renderers sample world neighbors, not only the
 * six face-adjacent sections. Fluid corner heights and AO/light repair can ask
 * for positions such as {@code x + 1, z + 1} while the source voxel is on a
 * section edge. Keeping the neighborhood keyed by {@link SectionKey} lets the
 * mesher resolve those diagonal samples without holding host world
 * objects on worker threads.</p>
 */
public final class SectionNeighborhood {
    private static final int SECTION_SIZE = SectionVoxelSnapshot.SECTION_SIZE;

    private final SectionKey centerKey;
    private final Map<SectionKey, SectionVoxelSnapshot> snapshots;
    private final SectionBoundarySnapshot capturedBoundary;

    private SectionNeighborhood(
            SectionKey centerKey,
            Map<SectionKey, SectionVoxelSnapshot> snapshots,
            SectionBoundarySnapshot capturedBoundary
    ) {
        this.centerKey = Objects.requireNonNull(centerKey, "centerKey");
        this.snapshots = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        this.capturedBoundary = capturedBoundary;
    }

    public static SectionNeighborhood empty(SectionKey centerKey) {
        return new SectionNeighborhood(centerKey, Map.of(), null);
    }

    public static SectionNeighborhood fromFaceNeighbors(
            SectionKey centerKey,
            Map<FaceDirection, SectionVoxelSnapshot> faceNeighbors
    ) {
        Objects.requireNonNull(centerKey, "centerKey");
        Objects.requireNonNull(faceNeighbors, "faceNeighbors");
        if (faceNeighbors.isEmpty()) {
            return empty(centerKey);
        }

        Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<FaceDirection, SectionVoxelSnapshot> entry : faceNeighbors.entrySet()) {
            FaceDirection direction = Objects.requireNonNull(entry.getKey(), "neighbor direction");
            SectionVoxelSnapshot snapshot = Objects.requireNonNull(entry.getValue(), "neighbor snapshot");
            SectionKey expectedKey = new SectionKey(
                    centerKey.x() + direction.stepX(),
                    centerKey.y() + direction.stepY(),
                    centerKey.z() + direction.stepZ()
            );
            if (!snapshot.key().equals(expectedKey)) {
                throw new IllegalArgumentException(
                        "neighbor snapshot key mismatch for "
                                + direction
                                + ": expected "
                                + expectedKey
                                + ", got "
                                + snapshot.key()
                );
            }
            snapshots.put(expectedKey, snapshot);
        }
        return new SectionNeighborhood(centerKey, snapshots, null);
    }

    public static SectionNeighborhood fromSnapshots(
            SectionKey centerKey,
            Map<SectionKey, SectionVoxelSnapshot> neighborSnapshots
    ) {
        return fromSnapshots(centerKey, neighborSnapshots, null);
    }

    public static SectionNeighborhood fromSnapshots(
            SectionKey centerKey,
            Map<SectionKey, SectionVoxelSnapshot> neighborSnapshots,
            SectionBoundarySnapshot capturedBoundary
    ) {
        Objects.requireNonNull(centerKey, "centerKey");
        Objects.requireNonNull(neighborSnapshots, "neighborSnapshots");
        if (neighborSnapshots.isEmpty()) {
            return new SectionNeighborhood(centerKey, Map.of(), capturedBoundary);
        }

        Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, SectionVoxelSnapshot> entry : neighborSnapshots.entrySet()) {
            SectionKey key = Objects.requireNonNull(entry.getKey(), "neighbor key");
            SectionVoxelSnapshot snapshot = Objects.requireNonNull(entry.getValue(), "neighbor snapshot");
            if (!key.equals(snapshot.key())) {
                throw new IllegalArgumentException("neighbor map key must match snapshot key");
            }
            if (key.equals(centerKey)) {
                continue;
            }
            snapshots.put(key, snapshot);
        }
        return new SectionNeighborhood(centerKey, snapshots, capturedBoundary);
    }

    public SectionKey centerKey() {
        return centerKey;
    }

    public SectionVoxelSnapshot snapshotAtLocalCoordinate(int x, int y, int z) {
        int sectionOffsetX = Math.floorDiv(x, SECTION_SIZE);
        int sectionOffsetY = Math.floorDiv(y, SECTION_SIZE);
        int sectionOffsetZ = Math.floorDiv(z, SECTION_SIZE);
        if (sectionOffsetX == 0 && sectionOffsetY == 0 && sectionOffsetZ == 0) {
            return null;
        }
        return snapshots.get(new SectionKey(
                centerKey.x() + sectionOffsetX,
                centerKey.y() + sectionOffsetY,
                centerKey.z() + sectionOffsetZ
        ));
    }

    public boolean hasGeometrySampleAtLocalCoordinate(int x, int y, int z) {
        return capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)
                || snapshotAtLocalCoordinate(x, y, z) != null;
    }

    public int voxelTypeIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.voxelTypeIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.voxelTypeIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int mediumStateIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumStateIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumStateIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int mediumTypeIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumTypeIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumTypeIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int mediumAmountAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumAmountAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumAmountAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int geometryMaterialFlagsAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.geometryMaterialFlagsAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.materialFlagsAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public boolean hasLightSampleAtLocalCoordinate(int x, int y, int z) {
        return capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)
                || snapshotAtLocalCoordinate(x, y, z) != null;
    }

    public int packedMapColorAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.packedMapColorAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mapColorAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int lightEmissionAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.lightEmissionAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.lightEmissionAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int lightMaterialFlagsAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.lightMaterialFlagsAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.materialFlagsAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    public int shadeBrightnessAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.shadeBrightnessAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.shadeBrightnessAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    private SectionVoxelSnapshot requiredSnapshotAtLocalCoordinate(int x, int y, int z) {
        SectionVoxelSnapshot snapshot = snapshotAtLocalCoordinate(x, y, z);
        if (snapshot == null) {
            throw new IllegalStateException("section neighborhood sample is unavailable at local coordinate ("
                    + x + ", " + y + ", " + z + ')');
        }
        return snapshot;
    }

    public int wrapLocalX(int x) {
        return Math.floorMod(x, SECTION_SIZE);
    }

    public int wrapLocalY(int y) {
        return Math.floorMod(y, SECTION_SIZE);
    }

    public int wrapLocalZ(int z) {
        return Math.floorMod(z, SECTION_SIZE);
    }

    public Map<SectionKey, SectionVoxelSnapshot> snapshots() {
        return snapshots;
    }
}
