package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Worker-private primitive representation of section faces.
 *
 * <p>This is deliberately neither a scene snapshot nor a renderer publication. It exists only
 * between surface extraction and packed mesh construction, so the production worker does not
 * allocate one {@link SectionFace} object for every visible cube or fluid surface.</p>
 */
final class SectionFaceStaging {
    private static final FaceDirection[] DIRECTIONS = FaceDirection.values();
    static final int X = 0;
    static final int Y = 1;
    static final int Z = 2;
    static final int DIRECTION = 3;
    static final int BLOCK_STATE = 4;
    static final int FLUID_AMOUNT = 5;
    static final int MAP_COLOR = 6;
    static final int TINT_0 = 7;
    static final int TINT_1 = 8;
    static final int TINT_2 = 9;
    static final int TINT_3 = 10;
    static final int LIGHT_EMISSION = 11;
    static final int MATERIAL_FLAGS = 12;
    static final int FACE_VISIBLE = 13;
    static final int VERTEX_LIGHT_0 = 14;
    static final int VERTEX_LIGHT_1 = 15;
    static final int VERTEX_LIGHT_2 = 16;
    static final int VERTEX_LIGHT_3 = 17;
    static final int FLUID_HEIGHT_0 = 18;
    static final int FLUID_HEIGHT_1 = 19;
    static final int FLUID_HEIGHT_2 = 20;
    static final int FLUID_HEIGHT_3 = 21;
    static final int FLUID_FLOW_X = 22;
    static final int FLUID_FLOW_Z = 23;
    static final int FLUID_OVERLAY = 24;
    static final int FLUID_TYPE = 25;
    static final int INTS_PER_FACE = 26;

    private SectionKey key;
    private int[] values;
    private int faceCount;
    private SectionModelFacts modelFacts = SectionModelFacts.unavailable();

    SectionFaceStaging(SectionKey key, int expectedFaces) {
        reset(key, expectedFaces);
    }

    SectionFaceStaging() {
        values = new int[INTS_PER_FACE];
    }

    /**
     * Rebinds this thread-confined arena to one extraction. The mesh builder consumes it before
     * the worker accepts another section, so retaining the high-water primitive buffer removes
     * per-section humongous allocations without extending any published payload lifetime.
     */
    void reset(SectionKey key, int expectedFaces) {
        this.key = Objects.requireNonNull(key, "key");
        if (expectedFaces < 0) {
            throw new IllegalArgumentException("expectedFaces must not be negative");
        }
        int requiredValues = Math.multiplyExact(Math.max(1, expectedFaces), INTS_PER_FACE);
        if (values == null || values.length < requiredValues) {
            values = new int[requiredValues];
        }
        faceCount = 0;
        modelFacts = SectionModelFacts.unavailable();
    }

    static SectionFaceStaging fromSnapshot(SectionGeometrySnapshot geometry) {
        Objects.requireNonNull(geometry, "geometry");
        SectionFaceStaging staging = new SectionFaceStaging(geometry.key(), geometry.faceCount());
        for (SectionFace face : geometry.faces()) {
            staging.append(
                    face.x(), face.y(), face.z(), face.direction(), face.voxelTypeId(), face.mediumAmount(),
                    face.mapColor(), face.blockTintLayer0Color(), face.blockTintLayer1Color(),
                    face.blockTintLayer2Color(), face.blockTintLayer3Color(), face.lightEmission(),
                    face.materialFlags(), face.faceVisible(), face.vertexLighting0(), face.vertexLighting1(),
                    face.vertexLighting2(), face.vertexLighting3(), face.fluidHeight0(), face.fluidHeight1(),
                    face.fluidHeight2(), face.fluidHeight3(), face.fluidFlowX(), face.fluidFlowZ(),
                    face.fluidOverlay(), face.mediumTypeId()
            );
        }
        staging.modelFacts = geometry.modelFacts();
        return staging;
    }

    SectionKey key() {
        return key;
    }

    int faceCount() {
        return faceCount;
    }

    SectionModelFacts modelFacts() {
        return modelFacts;
    }

    void setModelFacts(SectionModelFacts modelFacts) {
        this.modelFacts = Objects.requireNonNull(modelFacts, "modelFacts");
    }

    SectionGeometrySnapshot toSnapshot(int sourcePaletteSize, int sourceRunCount) {
        List<SectionFace> faces = new ArrayList<>(faceCount);
        for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
            faces.add(new SectionFace(
                    x(faceIndex), y(faceIndex), z(faceIndex), direction(faceIndex), voxelTypeId(faceIndex),
                    mediumAmount(faceIndex), mapColor(faceIndex), tint(faceIndex, 0), tint(faceIndex, 1),
                    tint(faceIndex, 2), tint(faceIndex, 3), lightEmission(faceIndex), materialFlags(faceIndex),
                    faceVisible(faceIndex), vertexLight(faceIndex, 0), vertexLight(faceIndex, 1),
                    vertexLight(faceIndex, 2), vertexLight(faceIndex, 3), fluidHeight(faceIndex, 0),
                    fluidHeight(faceIndex, 1), fluidHeight(faceIndex, 2), fluidHeight(faceIndex, 3),
                    fluidFlowX(faceIndex), fluidFlowZ(faceIndex), fluidOverlay(faceIndex), mediumTypeId(faceIndex)
            ));
        }
        return new SectionGeometrySnapshot(key, faces, sourcePaletteSize, sourceRunCount, modelFacts);
    }

    int valueAt(int faceIndex, int field) {
        if (faceIndex < 0 || faceIndex >= faceCount || field < 0 || field >= INTS_PER_FACE) {
            throw new IllegalArgumentException("face staging index outside range");
        }
        return values[faceIndex * INTS_PER_FACE + field];
    }

    int x(int faceIndex) { return valueAt(faceIndex, X); }
    int y(int faceIndex) { return valueAt(faceIndex, Y); }
    int z(int faceIndex) { return valueAt(faceIndex, Z); }
    FaceDirection direction(int faceIndex) { return DIRECTIONS[valueAt(faceIndex, DIRECTION)]; }
    int voxelTypeId(int faceIndex) { return valueAt(faceIndex, BLOCK_STATE); }
    int mediumAmount(int faceIndex) { return valueAt(faceIndex, FLUID_AMOUNT); }
    int mapColor(int faceIndex) { return valueAt(faceIndex, MAP_COLOR); }
    int tint(int faceIndex, int layer) {
        if (layer < 0 || layer > 3) {
            throw new IllegalArgumentException("tint layer outside range");
        }
        return valueAt(faceIndex, TINT_0 + layer);
    }
    int lightEmission(int faceIndex) { return valueAt(faceIndex, LIGHT_EMISSION); }
    int materialFlags(int faceIndex) { return valueAt(faceIndex, MATERIAL_FLAGS); }
    boolean faceVisible(int faceIndex) { return valueAt(faceIndex, FACE_VISIBLE) != 0; }
    int vertexLight(int faceIndex, int vertex) {
        if (vertex < 0 || vertex > 3) {
            throw new IllegalArgumentException("vertex outside range");
        }
        return valueAt(faceIndex, VERTEX_LIGHT_0 + vertex);
    }
    int fluidHeight(int faceIndex, int corner) {
        if (corner < 0 || corner > 3) {
            throw new IllegalArgumentException("fluid corner outside range");
        }
        return valueAt(faceIndex, FLUID_HEIGHT_0 + corner);
    }
    int fluidFlowX(int faceIndex) { return valueAt(faceIndex, FLUID_FLOW_X); }
    int fluidFlowZ(int faceIndex) { return valueAt(faceIndex, FLUID_FLOW_Z); }
    boolean fluidOverlay(int faceIndex) { return valueAt(faceIndex, FLUID_OVERLAY) != 0; }
    int mediumTypeId(int faceIndex) { return valueAt(faceIndex, FLUID_TYPE); }

    void append(
            int x, int y, int z, FaceDirection direction, int voxelTypeId, int mediumAmount,
            int mapColor, int tint0, int tint1, int tint2, int tint3, int lightEmission,
            int materialFlags, boolean faceVisible, int vertexLight0, int vertexLight1,
            int vertexLight2, int vertexLight3, int fluidHeight0, int fluidHeight1,
            int fluidHeight2, int fluidHeight3, int fluidFlowX, int fluidFlowZ,
            boolean fluidOverlay, int mediumTypeId
    ) {
        int offset = reserveFace();
        values[offset + X] = x;
        values[offset + Y] = y;
        values[offset + Z] = z;
        values[offset + DIRECTION] = direction.ordinal();
        values[offset + BLOCK_STATE] = voxelTypeId;
        values[offset + FLUID_AMOUNT] = mediumAmount;
        values[offset + MAP_COLOR] = mapColor;
        values[offset + TINT_0] = tint0;
        values[offset + TINT_1] = tint1;
        values[offset + TINT_2] = tint2;
        values[offset + TINT_3] = tint3;
        values[offset + LIGHT_EMISSION] = lightEmission;
        values[offset + MATERIAL_FLAGS] = materialFlags;
        values[offset + FACE_VISIBLE] = faceVisible ? 1 : 0;
        values[offset + VERTEX_LIGHT_0] = vertexLight0;
        values[offset + VERTEX_LIGHT_1] = vertexLight1;
        values[offset + VERTEX_LIGHT_2] = vertexLight2;
        values[offset + VERTEX_LIGHT_3] = vertexLight3;
        values[offset + FLUID_HEIGHT_0] = fluidHeight0;
        values[offset + FLUID_HEIGHT_1] = fluidHeight1;
        values[offset + FLUID_HEIGHT_2] = fluidHeight2;
        values[offset + FLUID_HEIGHT_3] = fluidHeight3;
        values[offset + FLUID_FLOW_X] = fluidFlowX;
        values[offset + FLUID_FLOW_Z] = fluidFlowZ;
        values[offset + FLUID_OVERLAY] = fluidOverlay ? 1 : 0;
        values[offset + FLUID_TYPE] = mediumTypeId;
    }

    private int reserveFace() {
        int offset = Math.multiplyExact(faceCount, INTS_PER_FACE);
        if (offset == values.length) {
            values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
        }
        faceCount++;
        return offset;
    }
}
