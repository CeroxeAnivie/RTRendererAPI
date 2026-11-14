package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded CPU publication from which any later FarField cell can be assembled.
 *
 * <p>Base BLAS upload needs the complete section mesh only until native build completion. FarField
 * coverage has a longer lifetime: a section may leave the camera-local Base window long after that
 * upload. Retaining the complete mesh for that possibility caused heap growth proportional to the
 * full host view authority. This publication keeps at most one directional depth winner in
 * each 4x4 section-local bucket (96 faces total), including the exact packed material record of the
 * winner. Its cost is therefore bounded independently of source triangle count.</p>
 */
public final class RtFarFieldSectionSource {
    private static final FaceDirection[] FACE_DIRECTIONS = FaceDirection.values();
    private static final int BUCKETS_PER_AXIS = 4;
    private static final int BUCKETS_PER_DIRECTION = BUCKETS_PER_AXIS * BUCKETS_PER_AXIS;
    private static final int MAX_CANDIDATES = FACE_DIRECTIONS.length * BUCKETS_PER_DIRECTION;
    private static final int VERTICES_PER_FACE = 4;
    private static final int COMPONENTS_PER_VERTEX = 3;

    private final SectionKey key;
    private final short[] centerPositions;
    private final byte[] directions;
    private final byte[] alphaCutoutFlags;
    private final int[] sourceFaceIndices;
    private final int[] faceRecords;
    private final short minimumY;
    private final short maximumY;

    private RtFarFieldSectionSource(
            SectionKey key,
            short[] centerPositions,
            byte[] directions,
            byte[] alphaCutoutFlags,
            int[] sourceFaceIndices,
            int[] faceRecords,
            short minimumY,
            short maximumY
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.centerPositions = Arrays.copyOf(centerPositions, centerPositions.length);
        this.directions = Arrays.copyOf(directions, directions.length);
        this.alphaCutoutFlags = Arrays.copyOf(alphaCutoutFlags, alphaCutoutFlags.length);
        this.sourceFaceIndices = Arrays.copyOf(sourceFaceIndices, sourceFaceIndices.length);
        this.faceRecords = Arrays.copyOf(faceRecords, faceRecords.length);
        if (directions.length == 0 || directions.length > MAX_CANDIDATES
                || centerPositions.length != directions.length * COMPONENTS_PER_VERTEX
                || alphaCutoutFlags.length != directions.length
                || sourceFaceIndices.length != directions.length
                || faceRecords.length != directions.length * RtSceneMaterialTable.SectionMaterial.intsPerFaceRecord()) {
            throw new IllegalArgumentException("invalid bounded FarField section source");
        }
        if (maximumY < minimumY) {
            throw new IllegalArgumentException("FarField source vertical bounds are inverted");
        }
        this.minimumY = minimumY;
        this.maximumY = maximumY;
    }

    /**
     * Creates an immutable compact proxy source from authoritative section geometry.
     *
     * @param mesh source mesh
     * @return proxy source
     */
    public static RtFarFieldSectionSource fromMesh(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.faceCount() <= 0) {
            throw new IllegalArgumentException("FarField source mesh must contain faces");
        }
        int[] winners = new int[MAX_CANDIDATES];
        int[] winnerNormalCoordinates = new int[MAX_CANDIDATES];
        Arrays.fill(winners, -1);

        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int y = mesh.vertexPositionComponent(vertex, 1);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
        }
        for (int face = 0; face < mesh.faceCount(); face++) {
            int centerX = faceCenterComponent(mesh, face, 0);
            int centerY = faceCenterComponent(mesh, face, 1);
            int centerZ = faceCenterComponent(mesh, face, 2);
            FaceDirection direction = directionFromOrdinal(mesh.faceDirectionOrdinal(face));
            int firstCoordinate;
            int secondCoordinate;
            int normalCoordinate;
            switch (direction) {
                case NEGATIVE_Y, POSITIVE_Y -> {
                    firstCoordinate = centerX;
                    secondCoordinate = centerZ;
                    normalCoordinate = centerY;
                }
                case NEGATIVE_X, POSITIVE_X -> {
                    firstCoordinate = centerZ;
                    secondCoordinate = centerY;
                    normalCoordinate = centerX;
                }
                case NEGATIVE_Z, POSITIVE_Z -> {
                    firstCoordinate = centerX;
                    secondCoordinate = centerY;
                    normalCoordinate = centerZ;
                }
                default -> throw new AssertionError("unreachable face direction");
            }
            int bucket = direction.ordinal() * BUCKETS_PER_DIRECTION
                    + bucketIndex(firstCoordinate) * BUCKETS_PER_AXIS
                    + bucketIndex(secondCoordinate);
            int current = winners[bucket];
            boolean positive = direction.stepX() + direction.stepY() + direction.stepZ() > 0;
            if (current < 0
                    || (positive ? normalCoordinate > winnerNormalCoordinates[bucket]
                    : normalCoordinate < winnerNormalCoordinates[bucket])
                    || (normalCoordinate == winnerNormalCoordinates[bucket] && face < current)) {
                winners[bucket] = face;
                winnerNormalCoordinates[bucket] = normalCoordinate;
            }
        }

        int candidateCount = 0;
        for (int winner : winners) {
            if (winner >= 0) {
                candidateCount++;
            }
        }
        short[] compactCenters = new short[candidateCount * COMPONENTS_PER_VERTEX];
        byte[] compactDirections = new byte[candidateCount];
        byte[] compactCutouts = new byte[candidateCount];
        int[] compactSourceFaces = new int[candidateCount];
        int recordInts = RtSceneMaterialTable.SectionMaterial.intsPerFaceRecord();
        int[] compactRecords = new int[Math.multiplyExact(candidateCount, recordInts)];
        int cursor = 0;
        cursor = appendWinners(mesh, winners, false,
                compactCenters, compactDirections, compactCutouts, compactSourceFaces, compactRecords, cursor);
        cursor = appendWinners(mesh, winners, true,
                compactCenters, compactDirections, compactCutouts, compactSourceFaces, compactRecords, cursor);
        if (cursor != candidateCount) {
            throw new IllegalStateException("FarField source candidate accounting mismatch");
        }
        return new RtFarFieldSectionSource(
                mesh.key(), compactCenters, compactDirections, compactCutouts, compactSourceFaces, compactRecords,
                checkedShort(minimumY, "minimumY"), checkedShort(maximumY, "maximumY")
        );
    }

    private static int appendWinners(
            SectionTriangleMesh mesh,
            int[] winners,
            boolean alphaCutout,
            short[] targetCenters,
            byte[] targetDirections,
            byte[] targetCutouts,
            int[] targetSourceFaces,
            int[] targetRecords,
            int cursor
    ) {
        for (int winner : winners) {
            if (winner < 0 || mesh.faceAlphaCutout(winner) != alphaCutout) {
                continue;
            }
            int targetOffset = cursor * COMPONENTS_PER_VERTEX;
            targetCenters[targetOffset] = checkedShort(faceCenterComponent(mesh, winner, 0), "centerX");
            targetCenters[targetOffset + 1] = checkedShort(faceCenterComponent(mesh, winner, 1), "centerY");
            targetCenters[targetOffset + 2] = checkedShort(faceCenterComponent(mesh, winner, 2), "centerZ");
            targetDirections[cursor] = (byte) mesh.faceDirectionOrdinal(winner);
            targetCutouts[cursor] = (byte) (mesh.faceAlphaCutout(winner) ? 1 : 0);
            targetSourceFaces[cursor] = winner;
            RtSceneMaterialTable.SectionMaterial.copyMeshFaceRecord(mesh, winner, targetRecords, cursor);
            cursor++;
        }
        return cursor;
    }

    private static int faceCenterComponent(SectionTriangleMesh mesh, int face, int component) {
        int firstVertex = face * VERTICES_PER_FACE;
        int sum = 0;
        for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
            sum += mesh.vertexPositionComponent(firstVertex + vertex, component);
        }
        return Math.round(sum / (float) VERTICES_PER_FACE);
    }

    private static int bucketIndex(int packedCoordinate) {
        long sectionExtent = 16L * SectionTriangleMesh.POSITION_SCALE;
        long normalized = Math.max(0L, Math.min(sectionExtent - 1L, packedCoordinate));
        return (int) Math.min(BUCKETS_PER_AXIS - 1L, normalized * BUCKETS_PER_AXIS / sectionExtent);
    }

    private static FaceDirection directionFromOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= FACE_DIRECTIONS.length) {
            throw new IllegalArgumentException("FarField source direction is out of range: " + ordinal);
        }
        return FACE_DIRECTIONS[ordinal];
    }

    private static short checkedShort(int value, String label) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(label + " exceeds packed section coordinate range: " + value);
        }
        return (short) value;
    }

    /**
     * Refreshes only packed face records while preserving compact geometry identity.
     *
     * @param mesh matching section mesh
     * @return refreshed source
     */
    public RtFarFieldSectionSource refreshMaterials(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        if (!key.equals(mesh.key())) {
            throw new IllegalArgumentException("FarField material refresh belongs to another section");
        }
        int recordInts = RtSceneMaterialTable.SectionMaterial.intsPerFaceRecord();
        int[] refreshed = new int[faceRecords.length];
        for (int candidate = 0; candidate < sourceFaceIndices.length; candidate++) {
            int sourceFace = sourceFaceIndices[candidate];
            if (sourceFace >= mesh.faceCount()) {
                throw new IllegalArgumentException("FarField source face is absent from compatible mesh generation");
            }
            RtSceneMaterialTable.SectionMaterial.copyMeshFaceRecord(
                    mesh, sourceFace, refreshed, candidate
            );
        }
        if (refreshed.length != sourceFaceIndices.length * recordInts) {
            throw new IllegalStateException("FarField material refresh record accounting mismatch");
        }
        return Arrays.equals(faceRecords, refreshed)
                ? this
                : new RtFarFieldSectionSource(
                key, centerPositions, directions, alphaCutoutFlags, sourceFaceIndices,
                refreshed, minimumY, maximumY
        );
    }

    /**
     * Returns the authoritative section identity.
     *
     * @return section key
     */
    public SectionKey key() {
        return key;
    }

    /**
     * Returns retained proxy-source face count.
     *
     * @return face count
     */
    public int faceCount() {
        return directions.length;
    }

    /**
     * Returns one face-center component in block units.
     *
     * @param face      compact face index
     * @param component XYZ component
     * @return block coordinate
     */
    public double centerBlock(int face, int component) {
        if (face < 0 || face >= faceCount() || component < 0 || component >= COMPONENTS_PER_VERTEX) {
            throw new IllegalArgumentException("FarField source center coordinate is out of range");
        }
        return centerPositions[face * COMPONENTS_PER_VERTEX + component]
                / (double) SectionTriangleMesh.POSITION_SCALE;
    }

    /**
     * Returns the source face normal direction.
     *
     * @param face compact face index
     * @return direction
     */
    public FaceDirection direction(int face) {
        if (face < 0 || face >= faceCount()) {
            throw new IllegalArgumentException("FarField source face is out of range");
        }
        return directionFromOrdinal(Byte.toUnsignedInt(directions[face]));
    }

    /**
     * Reports whether the source face needs alpha testing.
     *
     * @param face compact face index
     * @return cutout state
     */
    public boolean alphaCutout(int face) {
        if (face < 0 || face >= faceCount()) {
            throw new IllegalArgumentException("FarField source face is out of range");
        }
        return alphaCutoutFlags[face] != 0;
    }

    /**
     * Maps a compact face to its authoritative material-record index.
     *
     * @param face compact face index
     * @return source face index
     */
    public int sourceFaceIndex(int face) {
        if (face < 0 || face >= faceCount()) {
            throw new IllegalArgumentException("FarField source face is out of range");
        }
        return sourceFaceIndices[face];
    }

    /**
     * Returns the retained vertical minimum in block units.
     *
     * @return minimum Y
     */
    public double minimumBlockY() {
        return minimumY / (double) SectionTriangleMesh.POSITION_SCALE;
    }

    /**
     * Returns the retained vertical maximum in block units.
     *
     * @return maximum Y
     */
    public double maximumBlockY() {
        return maximumY / (double) SectionTriangleMesh.POSITION_SCALE;
    }

    /**
     * Copies one packed material record into a caller-owned table.
     *
     * @param originalSourceFace authoritative face index
     * @param target             destination records
     * @param targetFace         destination face
     */
    public void copySourceFaceRecordTo(int originalSourceFace, int[] target, int targetFace) {
        Objects.requireNonNull(target, "target");
        int compactFace = -1;
        for (int candidate = 0; candidate < sourceFaceIndices.length; candidate++) {
            if (sourceFaceIndices[candidate] == originalSourceFace) {
                compactFace = candidate;
                break;
            }
        }
        if (compactFace < 0) {
            throw new IllegalArgumentException("FarField source material face is not retained");
        }
        int recordInts = RtSceneMaterialTable.SectionMaterial.intsPerFaceRecord();
        int targetOffset = Math.multiplyExact(targetFace, recordInts);
        if (targetFace < 0 || targetOffset + recordInts > target.length) {
            throw new IllegalArgumentException("FarField target material face is out of range");
        }
        System.arraycopy(faceRecords, compactFace * recordInts, target, targetOffset, recordInts);
    }

    /**
     * Returns retained primitive-array storage, excluding object headers.
     *
     * @return estimated bytes
     */
    public long estimatedBytes() {
        return (long) centerPositions.length * Short.BYTES
                + directions.length
                + alphaCutoutFlags.length
                + (long) sourceFaceIndices.length * Integer.BYTES
                + (long) faceRecords.length * Integer.BYTES;
    }
}
