package top.ceroxe.rt.renderer.api;

/**
 * Portable scalar and vector formats accepted by generic vertex inputs.
 *
 * <p>The format determines byte interpretation only. Shader-interface compatibility and backend
 * format support remain explicit pipeline-admission checks.</p>
 */
public enum VertexFormat {
    UINT8(1, 1, NumericType.UNSIGNED_INTEGER, false),
    UINT8X2(2, 2, NumericType.UNSIGNED_INTEGER, false),
    UINT8X3(3, 3, NumericType.UNSIGNED_INTEGER, false),
    UINT8X4(4, 4, NumericType.UNSIGNED_INTEGER, false),
    SINT8(1, 1, NumericType.SIGNED_INTEGER, false),
    SINT8X2(2, 2, NumericType.SIGNED_INTEGER, false),
    SINT8X3(3, 3, NumericType.SIGNED_INTEGER, false),
    SINT8X4(4, 4, NumericType.SIGNED_INTEGER, false),
    UNORM8(1, 1, NumericType.UNSIGNED_INTEGER, true),
    UNORM8X2(2, 2, NumericType.UNSIGNED_INTEGER, true),
    UNORM8X3(3, 3, NumericType.UNSIGNED_INTEGER, true),
    UNORM8X4(4, 4, NumericType.UNSIGNED_INTEGER, true),
    SNORM8(1, 1, NumericType.SIGNED_INTEGER, true),
    SNORM8X2(2, 2, NumericType.SIGNED_INTEGER, true),
    SNORM8X3(3, 3, NumericType.SIGNED_INTEGER, true),
    SNORM8X4(4, 4, NumericType.SIGNED_INTEGER, true),
    UINT16(2, 1, NumericType.UNSIGNED_INTEGER, false),
    UINT16X2(4, 2, NumericType.UNSIGNED_INTEGER, false),
    UINT16X3(6, 3, NumericType.UNSIGNED_INTEGER, false),
    UINT16X4(8, 4, NumericType.UNSIGNED_INTEGER, false),
    SINT16(2, 1, NumericType.SIGNED_INTEGER, false),
    SINT16X2(4, 2, NumericType.SIGNED_INTEGER, false),
    SINT16X3(6, 3, NumericType.SIGNED_INTEGER, false),
    SINT16X4(8, 4, NumericType.SIGNED_INTEGER, false),
    UNORM16(2, 1, NumericType.UNSIGNED_INTEGER, true),
    UNORM16X2(4, 2, NumericType.UNSIGNED_INTEGER, true),
    UNORM16X3(6, 3, NumericType.UNSIGNED_INTEGER, true),
    UNORM16X4(8, 4, NumericType.UNSIGNED_INTEGER, true),
    SNORM16(2, 1, NumericType.SIGNED_INTEGER, true),
    SNORM16X2(4, 2, NumericType.SIGNED_INTEGER, true),
    SNORM16X3(6, 3, NumericType.SIGNED_INTEGER, true),
    SNORM16X4(8, 4, NumericType.SIGNED_INTEGER, true),
    FLOAT16(2, 1, NumericType.FLOATING_POINT, false),
    FLOAT16X2(4, 2, NumericType.FLOATING_POINT, false),
    FLOAT16X3(6, 3, NumericType.FLOATING_POINT, false),
    FLOAT16X4(8, 4, NumericType.FLOATING_POINT, false),
    UINT32(4, 1, NumericType.UNSIGNED_INTEGER, false),
    UINT32X2(8, 2, NumericType.UNSIGNED_INTEGER, false),
    UINT32X3(12, 3, NumericType.UNSIGNED_INTEGER, false),
    UINT32X4(16, 4, NumericType.UNSIGNED_INTEGER, false),
    SINT32(4, 1, NumericType.SIGNED_INTEGER, false),
    SINT32X2(8, 2, NumericType.SIGNED_INTEGER, false),
    SINT32X3(12, 3, NumericType.SIGNED_INTEGER, false),
    SINT32X4(16, 4, NumericType.SIGNED_INTEGER, false),
    FLOAT32(4, 1, NumericType.FLOATING_POINT, false),
    FLOAT32X2(8, 2, NumericType.FLOATING_POINT, false),
    FLOAT32X3(12, 3, NumericType.FLOATING_POINT, false),
    FLOAT32X4(16, 4, NumericType.FLOATING_POINT, false),
    FLOAT64(8, 1, NumericType.FLOATING_POINT, false),
    FLOAT64X2(16, 2, NumericType.FLOATING_POINT, false),
    FLOAT64X3(24, 3, NumericType.FLOATING_POINT, false),
    FLOAT64X4(32, 4, NumericType.FLOATING_POINT, false),
    UINT10_10_10_2(4, 4, NumericType.UNSIGNED_INTEGER, false),
    SINT10_10_10_2(4, 4, NumericType.SIGNED_INTEGER, false),
    UNORM10_10_10_2(4, 4, NumericType.UNSIGNED_INTEGER, true),
    SNORM10_10_10_2(4, 4, NumericType.SIGNED_INTEGER, true);

    /** Numeric domain presented to the shader before optional normalization. */
    public enum NumericType {
        UNSIGNED_INTEGER,
        SIGNED_INTEGER,
        FLOATING_POINT
    }

    private final int byteSize;
    private final int componentCount;
    private final NumericType numericType;
    private final boolean normalized;

    VertexFormat(int byteSize, int componentCount, NumericType numericType, boolean normalized) {
        this.byteSize = byteSize;
        this.componentCount = componentCount;
        this.numericType = numericType;
        this.normalized = normalized;
    }

    /** @return exact number of bytes consumed by one attribute */
    public int byteSize() { return byteSize; }

    /** @return scalar component count */
    public int componentCount() { return componentCount; }

    /** @return numeric domain before normalization */
    public NumericType numericType() { return numericType; }

    /** @return whether integer storage is normalized to a floating-point shader value */
    public boolean normalized() { return normalized; }
}
