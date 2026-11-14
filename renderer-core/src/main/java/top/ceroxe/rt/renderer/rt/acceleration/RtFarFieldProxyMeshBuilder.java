package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.*;

/**
 * Builds a bounded directional-depth proxy for one far-field admission cell.
 *
 * <p>Each source face votes into an orthographic bucket for its source-engine face
 * direction. The outermost face wins and is expanded to the bucket footprint.
 * This preserves terrain and building silhouettes from all six directions while
 * keeping proxy complexity independent of source triangle count. Exact source-face
 * identities remain attached so material reconstruction never reaches into BLAS state.</p>
 */
public final class RtFarFieldProxyMeshBuilder {
    private static final int HORIZONTAL_BUCKETS = 8;
    private static final int VERTICAL_BUCKETS = 8;
    private static final int VERTICES_PER_FACE = 4;
    private static final int COMPONENTS_PER_VERTEX = 3;
    private static final int INDICES_PER_FACE = 6;
    private static final double PLANE_EPSILON = 1.0E-6D;

    /**
     * Creates a stateless deterministic proxy builder.
     */
    public RtFarFieldProxyMeshBuilder() {
    }

    private static void collectCandidates(
            SourceMesh source,
            double cellWidth,
            double minimumY,
            double verticalHeight,
            Map<BucketKey, CandidateFace> selectedFaces
    ) {
        for (int faceIndex = 0; faceIndex < source.faceCount(); faceIndex++) {
            FaceDirection direction = source.faceDirection(faceIndex);
            FaceCenter center = source.faceCenter(faceIndex);
            BucketKey bucket = bucketFor(direction, center, cellWidth, minimumY, verticalHeight);
            CandidateFace candidate = new CandidateFace(
                    normalCoordinate(direction, center),
                    new SourceFaceReference(source.key(), source.sourceFaceIndex(faceIndex)),
                    source.alphaCutout(faceIndex)
            );
            selectedFaces.merge(
                    bucket,
                    candidate,
                    (current, replacement) -> betterCandidate(direction, current, replacement)
                            ? replacement
                            : current
            );
        }
    }

    private static boolean betterCandidate(
            FaceDirection direction,
            CandidateFace current,
            CandidateFace replacement
    ) {
        double delta = replacement.normalPlane() - current.normalPlane();
        if (Math.abs(delta) > PLANE_EPSILON) {
            return direction.stepX() + direction.stepY() + direction.stepZ() > 0
                    ? delta > 0.0D
                    : delta < 0.0D;
        }
        return sourceFaceOrder().compare(replacement.sourceFace(), current.sourceFace()) < 0;
    }

    private static BucketKey bucketFor(
            FaceDirection direction,
            FaceCenter center,
            double cellWidth,
            double minimumY,
            double verticalHeight
    ) {
        return switch (direction) {
            case NEGATIVE_Y, POSITIVE_Y -> new BucketKey(
                    direction,
                    bucketIndex(center.x(), 0.0D, cellWidth, HORIZONTAL_BUCKETS),
                    bucketIndex(center.z(), 0.0D, cellWidth, HORIZONTAL_BUCKETS)
            );
            case NEGATIVE_X, POSITIVE_X -> new BucketKey(
                    direction,
                    bucketIndex(center.z(), 0.0D, cellWidth, HORIZONTAL_BUCKETS),
                    bucketIndex(center.y(), minimumY, verticalHeight, VERTICAL_BUCKETS)
            );
            case NEGATIVE_Z, POSITIVE_Z -> new BucketKey(
                    direction,
                    bucketIndex(center.x(), 0.0D, cellWidth, HORIZONTAL_BUCKETS),
                    bucketIndex(center.y(), minimumY, verticalHeight, VERTICAL_BUCKETS)
            );
        };
    }

    private static int bucketIndex(double coordinate, double minimum, double extent, int bucketCount) {
        if (!Double.isFinite(coordinate) || !Double.isFinite(minimum) || !Double.isFinite(extent)) {
            throw new IllegalArgumentException("proxy bucket coordinates must be finite");
        }
        if (extent <= 0.0D || bucketCount <= 0) {
            throw new IllegalArgumentException("proxy bucket extent and count must be positive");
        }
        double normalized = (coordinate - minimum) / extent;
        int index = (int) Math.floor(normalized * bucketCount);
        return Math.max(0, Math.min(bucketCount - 1, index));
    }

    private static double normalCoordinate(FaceDirection direction, FaceCenter center) {
        return switch (direction) {
            case NEGATIVE_X, POSITIVE_X -> center.x();
            case NEGATIVE_Y, POSITIVE_Y -> center.y();
            case NEGATIVE_Z, POSITIVE_Z -> center.z();
        };
    }

    private static void writeProxyFace(
            float[] positions,
            int offset,
            BucketKey bucket,
            double normalPlane,
            double cellWidth,
            double minimumY,
            double verticalHeight
    ) {
        double horizontalStep = cellWidth / HORIZONTAL_BUCKETS;
        double verticalStep = verticalHeight / VERTICAL_BUCKETS;
        double firstHorizontalMin = bucket.firstBucket() * horizontalStep;
        double firstHorizontalMax = firstHorizontalMin + horizontalStep;
        double secondHorizontalMin = bucket.secondBucket() * horizontalStep;
        double secondHorizontalMax = secondHorizontalMin + horizontalStep;
        double verticalMin = minimumY + bucket.secondBucket() * verticalStep;
        double verticalMax = verticalMin + verticalStep;

        switch (bucket.direction()) {
            case NEGATIVE_Y, POSITIVE_Y -> {
                writeVertex(positions, offset, firstHorizontalMin, normalPlane, secondHorizontalMin);
                writeVertex(positions, offset + 3, firstHorizontalMax, normalPlane, secondHorizontalMin);
                writeVertex(positions, offset + 6, firstHorizontalMax, normalPlane, secondHorizontalMax);
                writeVertex(positions, offset + 9, firstHorizontalMin, normalPlane, secondHorizontalMax);
            }
            case NEGATIVE_X, POSITIVE_X -> {
                writeVertex(positions, offset, normalPlane, verticalMin, firstHorizontalMin);
                writeVertex(positions, offset + 3, normalPlane, verticalMin, firstHorizontalMax);
                writeVertex(positions, offset + 6, normalPlane, verticalMax, firstHorizontalMax);
                writeVertex(positions, offset + 9, normalPlane, verticalMax, firstHorizontalMin);
            }
            case NEGATIVE_Z, POSITIVE_Z -> {
                writeVertex(positions, offset, firstHorizontalMin, verticalMin, normalPlane);
                writeVertex(positions, offset + 3, firstHorizontalMax, verticalMin, normalPlane);
                writeVertex(positions, offset + 6, firstHorizontalMax, verticalMax, normalPlane);
                writeVertex(positions, offset + 9, firstHorizontalMin, verticalMax, normalPlane);
            }
        }
    }

    private static void writeVertex(float[] positions, int offset, double x, double y, double z) {
        positions[offset] = checkedFloat(x, "proxyVertexX");
        positions[offset + 1] = checkedFloat(y, "proxyVertexY");
        positions[offset + 2] = checkedFloat(z, "proxyVertexZ");
    }

    private static void writeFaceIndices(int[] indices, int faceIndex) {
        int vertexBase = faceIndex * VERTICES_PER_FACE;
        int indexBase = faceIndex * INDICES_PER_FACE;
        indices[indexBase] = vertexBase;
        indices[indexBase + 1] = vertexBase + 1;
        indices[indexBase + 2] = vertexBase + 2;
        indices[indexBase + 3] = vertexBase;
        indices[indexBase + 4] = vertexBase + 2;
        indices[indexBase + 5] = vertexBase + 3;
    }

    private static float checkedFloat(double value, String label) {
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalArgumentException(label + " exceeds finite float range: " + value);
        }
        return converted;
    }

    private static Comparator<BucketKey> bucketOrder() {
        return Comparator.comparingInt((BucketKey bucket) -> bucket.direction().ordinal())
                .thenComparingInt(BucketKey::firstBucket)
                .thenComparingInt(BucketKey::secondBucket);
    }

    private static Comparator<SourceFaceReference> sourceFaceOrder() {
        return Comparator.comparingInt((SourceFaceReference reference) -> reference.sectionKey().x())
                .thenComparingInt(reference -> reference.sectionKey().y())
                .thenComparingInt(reference -> reference.sectionKey().z())
                .thenComparingInt(SourceFaceReference::faceIndex);
    }

    /**
     * Reduces authoritative source publications into one bounded proxy mesh.
     *
     * @param cell               admission cell defining the proxy footprint and source identities
     * @param sourcePublications authoritative publication lookup for every cell source
     * @return validated proxy geometry with exact source-face mappings
     * @throws IllegalArgumentException if the cell has no sources or geometry is not representable
     * @throws IllegalStateException    if an authoritative source publication or usable face is missing
     */
    public ProxyMesh build(
            RtSectionInstanceAdmission.FarFieldCell cell,
            Map<SectionKey, RtSectionSourcePublication> sourcePublications
    ) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(sourcePublications, "sourcePublications");
        if (cell.sourceSections().isEmpty()) {
            throw new IllegalArgumentException("far-field cell must contain source sections");
        }

        int originSectionY = cell.sourceSections().stream()
                .mapToInt(SectionKey::y)
                .min()
                .orElseThrow();
        float originBlockX = checkedFloat((long) cell.key().originSectionX() * 16L, "originBlockX");
        float originBlockY = checkedFloat((long) originSectionY * 16L, "originBlockY");
        float originBlockZ = checkedFloat((long) cell.key().originSectionZ() * 16L, "originBlockZ");
        double cellWidth = (double) cell.key().spanSections() * 16.0D;

        List<SourceMesh> sources = new ArrayList<>(cell.sourceSections().size());
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (SectionKey sourceKey : cell.sourceSections()) {
            RtSectionSourcePublication publication = sourcePublications.get(sourceKey);
            if (publication == null) {
                throw new IllegalStateException("missing source publication for far-field section " + sourceKey);
            }
            SourceMesh source = SourceMesh.from(
                    publication.requireFarFieldSource(), originBlockX, originBlockY, originBlockZ
            );
            sources.add(source);
            minimumY = Math.min(minimumY, source.minimumY());
            maximumY = Math.max(maximumY, source.maximumY());
        }
        if (!Double.isFinite(minimumY) || !Double.isFinite(maximumY)) {
            throw new IllegalStateException("far-field source meshes did not provide finite vertical bounds");
        }
        double verticalHeight = Math.max(1.0D, maximumY - minimumY);

        Map<BucketKey, CandidateFace> selectedFaces = new HashMap<>();
        for (SourceMesh source : sources) {
            collectCandidates(
                    source,
                    cellWidth,
                    minimumY,
                    verticalHeight,
                    selectedFaces
            );
        }
        if (selectedFaces.isEmpty()) {
            throw new IllegalStateException("far-field source meshes did not contain any proxy faces");
        }

        List<Map.Entry<BucketKey, CandidateFace>> orderedFaces = new ArrayList<>(selectedFaces.entrySet());
        orderedFaces.sort(Comparator
                .comparing((Map.Entry<BucketKey, CandidateFace> entry) -> entry.getValue().alphaCutout())
                .thenComparing(Map.Entry::getKey, bucketOrder()));
        int opaqueFaceCount = 0;
        for (Map.Entry<BucketKey, CandidateFace> selectedFace : orderedFaces) {
            if (!selectedFace.getValue().alphaCutout()) {
                opaqueFaceCount++;
            }
        }
        int alphaCutoutFaceCount = orderedFaces.size() - opaqueFaceCount;
        int secondGeometryFaceOffset = opaqueFaceCount > 0 && alphaCutoutFaceCount > 0
                ? opaqueFaceCount
                : 0;
        float[] positions = new float[Math.multiplyExact(
                orderedFaces.size(),
                VERTICES_PER_FACE * COMPONENTS_PER_VERTEX
        )];
        int[] indices = new int[Math.multiplyExact(orderedFaces.size(), INDICES_PER_FACE)];
        List<SourceFaceReference> sourceFaceReferences = new ArrayList<>(orderedFaces.size());
        for (int faceIndex = 0; faceIndex < orderedFaces.size(); faceIndex++) {
            Map.Entry<BucketKey, CandidateFace> selected = orderedFaces.get(faceIndex);
            writeProxyFace(
                    positions,
                    faceIndex * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX,
                    selected.getKey(),
                    selected.getValue().normalPlane(),
                    cellWidth,
                    minimumY,
                    verticalHeight
            );
            writeFaceIndices(indices, faceIndex);
            sourceFaceReferences.add(selected.getValue().sourceFace());
        }
        return new ProxyMesh(
                cell.key(),
                originBlockX,
                originBlockY,
                originBlockZ,
                positions,
                indices,
                sourceFaceReferences,
                secondGeometryFaceOffset,
                alphaCutoutFaceCount > 0,
                cell.sourceSections().size()
        );
    }

    /**
     * Immutable coarse mesh and source-material identity mapping for one far-field cell.
     *
     * @param cellKey                  spatial admission-cell identity
     * @param originBlockX             world-space X origin used to localize vertices
     * @param originBlockY             world-space Y origin used to localize vertices
     * @param originBlockZ             world-space Z origin used to localize vertices
     * @param vertexPositions          tightly packed local {@code vec3} positions
     * @param indices                  unsigned triangle indices represented as Java integers
     * @param sourceFaces              one authoritative source identity per generated quad
     * @param secondGeometryFaceOffset first cutout face, or zero when no split is required
     * @param hasAlphaCutoutFaces      whether any generated face requires any-hit alpha evaluation
     * @param sourceSectionCount       number of authoritative sections reduced into the proxy
     */
    public record ProxyMesh(
            RtSectionInstanceAdmission.FarFieldCellKey cellKey,
            float originBlockX,
            float originBlockY,
            float originBlockZ,
            float[] vertexPositions,
            int[] indices,
            List<SourceFaceReference> sourceFaces,
            int secondGeometryFaceOffset,
            boolean hasAlphaCutoutFaces,
            int sourceSectionCount
    ) {
        /**
         * Defensively freezes arrays and validates geometry-to-material cardinality.
         */
        public ProxyMesh {
            cellKey = Objects.requireNonNull(cellKey, "cellKey");
            if (!Float.isFinite(originBlockX) || !Float.isFinite(originBlockY) || !Float.isFinite(originBlockZ)) {
                throw new IllegalArgumentException("proxy origin must be finite");
            }
            vertexPositions = Arrays.copyOf(Objects.requireNonNull(vertexPositions, "vertexPositions"), vertexPositions.length);
            indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
            sourceFaces = List.copyOf(sourceFaces);
            if (sourceSectionCount <= 0) {
                throw new IllegalArgumentException("sourceSectionCount must be positive");
            }
            if (vertexPositions.length % (VERTICES_PER_FACE * COMPONENTS_PER_VERTEX) != 0) {
                throw new IllegalArgumentException("proxy vertices must contain independent quad faces");
            }
            if (indices.length % INDICES_PER_FACE != 0) {
                throw new IllegalArgumentException("proxy indices must contain quad triangle pairs");
            }
            if (sourceFaces.size() != indices.length / INDICES_PER_FACE) {
                throw new IllegalArgumentException("proxy source-face references must match proxy faces");
            }
            if (secondGeometryFaceOffset < 0 || secondGeometryFaceOffset > sourceFaces.size()) {
                throw new IllegalArgumentException("proxy second geometry offset lies outside its face range");
            }
            if (secondGeometryFaceOffset > 0
                    && (!hasAlphaCutoutFaces || secondGeometryFaceOffset == sourceFaces.size())) {
                throw new IllegalArgumentException("proxy geometry split requires opaque and cutout faces");
            }
            int vertexCount = vertexPositions.length / COMPONENTS_PER_VERTEX;
            for (int index : indices) {
                if (index < 0 || index >= vertexCount) {
                    throw new IllegalArgumentException("proxy index outside vertex buffer");
                }
            }
        }

        /**
         * Returns the generated quad count.
         *
         * @return proxy face count
         */
        public int faceCount() {
            return sourceFaces.size();
        }

        /**
         * Returns the generated indexed-triangle count.
         *
         * @return proxy triangle count
         */
        public int triangleCount() {
            return indices.length / 3;
        }

        /**
         * Splits the proxy into opaque and alpha-cutout BLAS geometries when required.
         *
         * @return one or two non-empty, defensively copied geometry parts
         */
        public List<GeometryPart> geometryParts() {
            if (secondGeometryFaceOffset == 0) {
                return List.of(new GeometryPart(vertexPositions, indices, !hasAlphaCutoutFaces));
            }
            return List.of(
                    geometryPart(0, secondGeometryFaceOffset, true),
                    geometryPart(secondGeometryFaceOffset, faceCount(), false)
            );
        }

        private GeometryPart geometryPart(int firstFace, int endFace, boolean opaque) {
            int partFaceCount = endFace - firstFace;
            if (partFaceCount <= 0) {
                throw new IllegalArgumentException("proxy geometry part must contain at least one face");
            }
            int positionOffset = firstFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            float[] partPositions = Arrays.copyOfRange(
                    vertexPositions,
                    positionOffset,
                    positionOffset + partFaceCount * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX
            );
            int[] partIndices = new int[partFaceCount * INDICES_PER_FACE];
            for (int face = 0; face < partFaceCount; face++) {
                writeFaceIndices(partIndices, face);
            }
            return new GeometryPart(partPositions, partIndices, opaque);
        }

        /**
         * Returns a defensive copy so callers cannot mutate the frozen proxy.
         *
         * @return copied positions
         */
        @Override
        public float[] vertexPositions() {
            return Arrays.copyOf(vertexPositions, vertexPositions.length);
        }

        /**
         * Returns a defensive copy so callers cannot mutate the frozen proxy.
         *
         * @return copied indices
         */
        @Override
        public int[] indices() {
            return Arrays.copyOf(indices, indices.length);
        }
    }

    /**
     * One homogeneous Vulkan BLAS geometry range.
     *
     * @param vertexPositions tightly packed independent-quad positions
     * @param indices         triangle indices local to this part
     * @param opaque          whether Vulkan may omit any-hit evaluation
     */
    public record GeometryPart(float[] vertexPositions, int[] indices, boolean opaque) {
        /**
         * Defensively freezes and validates a non-empty geometry range.
         */
        public GeometryPart {
            vertexPositions = Arrays.copyOf(
                    Objects.requireNonNull(vertexPositions, "vertexPositions"),
                    vertexPositions.length
            );
            indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
            if (vertexPositions.length == 0
                    || vertexPositions.length % (VERTICES_PER_FACE * COMPONENTS_PER_VERTEX) != 0) {
                throw new IllegalArgumentException("proxy geometry vertices must contain complete quad faces");
            }
            if (indices.length == 0 || indices.length % INDICES_PER_FACE != 0) {
                throw new IllegalArgumentException("proxy geometry indices must contain complete quad faces");
            }
        }

        /**
         * Returns a defensive copy so callers cannot mutate this geometry part.
         *
         * @return copied positions
         */
        @Override
        public float[] vertexPositions() {
            return Arrays.copyOf(vertexPositions, vertexPositions.length);
        }

        /**
         * Returns a defensive copy so callers cannot mutate this geometry part.
         *
         * @return copied indices
         */
        @Override
        public int[] indices() {
            return Arrays.copyOf(indices, indices.length);
        }
    }

    /**
     * Stable link from a generated proxy face to its authoritative source face.
     *
     * @param sectionKey source section identity
     * @param faceIndex  non-negative face index within the source publication
     */
    public record SourceFaceReference(SectionKey sectionKey, int faceIndex) {
        /**
         * Validates the source identity before it enters the proxy material mapping.
         */
        public SourceFaceReference {
            sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
            if (faceIndex < 0) {
                throw new IllegalArgumentException("faceIndex must not be negative");
            }
        }
    }

    private record BucketKey(FaceDirection direction, int firstBucket, int secondBucket) {
        private BucketKey {
            direction = Objects.requireNonNull(direction, "direction");
            if (firstBucket < 0 || firstBucket >= HORIZONTAL_BUCKETS) {
                throw new IllegalArgumentException("first proxy bucket is out of range");
            }
            int secondBucketLimit = direction.stepY() == 0 ? VERTICAL_BUCKETS : HORIZONTAL_BUCKETS;
            if (secondBucket < 0 || secondBucket >= secondBucketLimit) {
                throw new IllegalArgumentException("second proxy bucket is out of range");
            }
        }
    }

    private record CandidateFace(double normalPlane, SourceFaceReference sourceFace, boolean alphaCutout) {
        private CandidateFace {
            if (!Double.isFinite(normalPlane)) {
                throw new IllegalArgumentException("proxy normal plane must be finite");
            }
            sourceFace = Objects.requireNonNull(sourceFace, "sourceFace");
        }
    }

    private record FaceCenter(double x, double y, double z) {
        private FaceCenter {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("proxy face center must be finite");
            }
        }
    }

    private record SourceMesh(
            SectionKey key,
            float[] faceCenters,
            byte[] faceDirections,
            byte[] faceAlphaCutoutFlags,
            int[] sourceFaceIndices,
            double minimumY,
            double maximumY
    ) {
        private SourceMesh {
            key = Objects.requireNonNull(key, "key");
            faceCenters = Arrays.copyOf(faceCenters, faceCenters.length);
            faceDirections = Arrays.copyOf(faceDirections, faceDirections.length);
            faceAlphaCutoutFlags = Arrays.copyOf(faceAlphaCutoutFlags, faceAlphaCutoutFlags.length);
            sourceFaceIndices = Arrays.copyOf(sourceFaceIndices, sourceFaceIndices.length);
            if (faceCenters.length != faceDirections.length * COMPONENTS_PER_VERTEX) {
                throw new IllegalArgumentException("source proxy centers must match source faces");
            }
            if (faceAlphaCutoutFlags.length != faceDirections.length) {
                throw new IllegalArgumentException("source proxy cutout flags must match source faces");
            }
            if (sourceFaceIndices.length != faceDirections.length) {
                throw new IllegalArgumentException("source proxy identities must match source faces");
            }
            if (!Double.isFinite(minimumY) || !Double.isFinite(maximumY) || maximumY < minimumY) {
                throw new IllegalArgumentException("invalid source proxy vertical bounds");
            }
        }

        private static SourceMesh from(
                RtFarFieldSectionSource source,
                float originBlockX,
                float originBlockY,
                float originBlockZ
        ) {
            float[] centers = new float[source.faceCount() * COMPONENTS_PER_VERTEX];
            byte[] directions = new byte[source.faceCount()];
            byte[] cutouts = new byte[source.faceCount()];
            int[] sourceFaces = new int[source.faceCount()];
            double sectionBlockX = (long) source.key().x() * 16L - (double) originBlockX;
            double sectionBlockY = (long) source.key().y() * 16L - (double) originBlockY;
            double sectionBlockZ = (long) source.key().z() * 16L - (double) originBlockZ;
            for (int face = 0; face < source.faceCount(); face++) {
                int offset = face * COMPONENTS_PER_VERTEX;
                centers[offset] = checkedFloat(sectionBlockX + source.centerBlock(face, 0), "sourceProxyX");
                centers[offset + 1] = checkedFloat(sectionBlockY + source.centerBlock(face, 1), "sourceProxyY");
                centers[offset + 2] = checkedFloat(sectionBlockZ + source.centerBlock(face, 2), "sourceProxyZ");
                directions[face] = (byte) source.direction(face).ordinal();
                cutouts[face] = source.alphaCutout(face) ? (byte) 1 : 0;
                sourceFaces[face] = source.sourceFaceIndex(face);
            }
            return new SourceMesh(
                    source.key(),
                    centers,
                    directions,
                    cutouts,
                    sourceFaces,
                    sectionBlockY + source.minimumBlockY(),
                    sectionBlockY + source.maximumBlockY()
            );
        }

        private int faceCount() {
            return faceDirections.length;
        }

        private FaceDirection faceDirection(int faceIndex) {
            int ordinal = Byte.toUnsignedInt(faceDirections[faceIndex]);
            FaceDirection[] directions = FaceDirection.values();
            if (ordinal >= directions.length) {
                throw new IllegalArgumentException("source face direction is out of range: " + ordinal);
            }
            return directions[ordinal];
        }

        private boolean alphaCutout(int faceIndex) {
            if (faceIndex < 0 || faceIndex >= faceAlphaCutoutFlags.length) {
                throw new IllegalArgumentException("source proxy face index is out of range");
            }
            return faceAlphaCutoutFlags[faceIndex] != 0;
        }

        private int sourceFaceIndex(int faceIndex) {
            if (faceIndex < 0 || faceIndex >= sourceFaceIndices.length) {
                throw new IllegalArgumentException("source proxy face identity is out of range");
            }
            return sourceFaceIndices[faceIndex];
        }

        private FaceCenter faceCenter(int faceIndex) {
            int offset = faceIndex * COMPONENTS_PER_VERTEX;
            return new FaceCenter(faceCenters[offset], faceCenters[offset + 1], faceCenters[offset + 2]);
        }
    }
}
