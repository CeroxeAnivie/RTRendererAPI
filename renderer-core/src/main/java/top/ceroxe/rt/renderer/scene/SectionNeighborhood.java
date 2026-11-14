package top.ceroxe.rt.renderer.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable section-local neighborhood used while building renderer-owned RT
 * geometry.
 *
 * <p>体素与流体网格生成器需要采样空间邻域，而不只是六个面相邻区段。源体素位于区段边缘时，
 * 流体角点高度和 AO/光照修复可能查询 {@code x + 1, z + 1} 等位置。使用
 * {@link SectionKey} 索引邻域，使网格生成器无需在工作线程持有宿主场景对象，
 * 也能解析这些对角采样。</p>
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

    /**
     * Creates an empty neighborhood for a center section.
     *
     * @param centerKey center section identity
     * @return empty neighborhood
     */
    public static SectionNeighborhood empty(SectionKey centerKey) {
        return new SectionNeighborhood(centerKey, Map.of(), null);
    }

    /**
     * Creates a neighborhood from validated cardinal face neighbors.
     *
     * @param centerKey     center section identity
     * @param faceNeighbors snapshots keyed by cardinal face direction
     * @return immutable neighborhood
     */
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

    /**
     * Creates a neighborhood from arbitrary neighboring section snapshots.
     *
     * @param centerKey         center section identity
     * @param neighborSnapshots snapshots keyed by section identity
     * @return immutable neighborhood
     */
    public static SectionNeighborhood fromSnapshots(
            SectionKey centerKey,
            Map<SectionKey, SectionVoxelSnapshot> neighborSnapshots
    ) {
        return fromSnapshots(centerKey, neighborSnapshots, null);
    }

    /**
     * Creates a neighborhood with arbitrary snapshots and a coherent captured boundary.
     *
     * @param centerKey         center section identity
     * @param neighborSnapshots snapshots keyed by section identity
     * @param capturedBoundary  coherent boundary snapshot, or {@code null}
     * @return immutable neighborhood
     */
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

    /**
     * Returns the center section identity.
     *
     * @return center section identity
     */
    public SectionKey centerKey() {
        return centerKey;
    }

    /**
     * Resolves the neighboring snapshot containing an out-of-center local coordinate.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return neighboring snapshot, or {@code null} when unavailable
     */
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

    /**
     * Tests whether a geometry sample is available at a local coordinate.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return {@code true} when the geometry sample is available
     */
    public boolean hasGeometrySampleAtLocalCoordinate(int x, int y, int z) {
        return capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)
                || snapshotAtLocalCoordinate(x, y, z) != null;
    }

    /**
     * Reads a neighboring voxel type.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return voxel type identifier
     */
    public int voxelTypeIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.voxelTypeIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.voxelTypeIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads a neighboring medium state.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return medium state identifier
     */
    public int mediumStateIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumStateIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumStateIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads a neighboring medium type.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return medium type identifier
     */
    public int mediumTypeIdAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumTypeIdAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumTypeIdAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads a neighboring unsigned medium amount.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return unsigned medium amount
     */
    public int mediumAmountAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.mediumAmountAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mediumAmountAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads neighboring geometry material flags.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return unsigned geometry material flags
     */
    public int geometryMaterialFlagsAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) {
            return capturedBoundary.geometryMaterialFlagsAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.materialFlagsAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Tests whether a light sample is available at a local coordinate.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return {@code true} when the light sample is available
     */
    public boolean hasLightSampleAtLocalCoordinate(int x, int y, int z) {
        return capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)
                || snapshotAtLocalCoordinate(x, y, z) != null;
    }

    /**
     * Reads neighboring packed map color and light.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return packed map color and light
     */
    public int packedMapColorAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.packedMapColorAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.mapColorAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads neighboring unsigned light emission.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return unsigned light emission
     */
    public int lightEmissionAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.lightEmissionAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.lightEmissionAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads neighboring light material flags.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return unsigned light material flags
     */
    public int lightMaterialFlagsAtLocalCoordinate(int x, int y, int z) {
        if (capturedBoundary != null && SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) {
            return capturedBoundary.lightMaterialFlagsAt(x, y, z);
        }
        SectionVoxelSnapshot snapshot = requiredSnapshotAtLocalCoordinate(x, y, z);
        return snapshot.materialFlagsAt(wrapLocalX(x), wrapLocalY(y), wrapLocalZ(z));
    }

    /**
     * Reads neighboring unsigned shade brightness.
     *
     * @param x coordinate relative to the center section
     * @param y coordinate relative to the center section
     * @param z coordinate relative to the center section
     * @return unsigned shade brightness
     */
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

    /**
     * Wraps a local x coordinate into the center-section domain.
     *
     * @param x unbounded local x coordinate
     * @return wrapped x coordinate
     */
    public int wrapLocalX(int x) {
        return Math.floorMod(x, SECTION_SIZE);
    }

    /**
     * Wraps a local y coordinate into the center-section domain.
     *
     * @param y unbounded local y coordinate
     * @return wrapped y coordinate
     */
    public int wrapLocalY(int y) {
        return Math.floorMod(y, SECTION_SIZE);
    }

    /**
     * Wraps a local z coordinate into the center-section domain.
     *
     * @param z unbounded local z coordinate
     * @return wrapped z coordinate
     */
    public int wrapLocalZ(int z) {
        return Math.floorMod(z, SECTION_SIZE);
    }

    /**
     * Returns immutable neighboring snapshots keyed by section identity.
     *
     * @return immutable neighboring snapshot map
     */
    public Map<SectionKey, SectionVoxelSnapshot> snapshots() {
        return snapshots;
    }
}
