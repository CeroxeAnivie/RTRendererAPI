package top.ceroxe.rt.renderer.api;

/** Portable scalar/vector shader interface type used for stage-linkage validation. */
public record ShaderInterfaceType(NumericType numericType, int componentBitWidth, int componentCount) {
    /** Scalar numeric domain visible to the shader stage. */
    public enum NumericType { FLOATING_POINT, SIGNED_INTEGER, UNSIGNED_INTEGER }

    /** Validates a portable scalar or vector interface type. */
    public ShaderInterfaceType {
        java.util.Objects.requireNonNull(numericType, "numericType");
        if (componentBitWidth != 8 && componentBitWidth != 16
                && componentBitWidth != 32 && componentBitWidth != 64) {
            throw new IllegalArgumentException("shader interface component width must be 8, 16, 32, or 64 bits");
        }
        if (componentCount < 1 || componentCount > 4) {
            throw new IllegalArgumentException("shader interface component count must be in [1, 4]");
        }
    }

    /** Returns whether one vertex storage format can feed this interface without numeric-domain coercion. */
    public boolean accepts(VertexFormat format) {
        java.util.Objects.requireNonNull(format, "format");
        NumericType storage = switch (format.numericType()) {
            case FLOATING_POINT -> NumericType.FLOATING_POINT;
            case SIGNED_INTEGER -> format.normalized() ? NumericType.FLOATING_POINT : NumericType.SIGNED_INTEGER;
            case UNSIGNED_INTEGER -> format.normalized() ? NumericType.FLOATING_POINT : NumericType.UNSIGNED_INTEGER;
        };
        return storage == numericType
                && format.componentCount() == componentCount
                && format.byteSize() * Byte.SIZE / componentCount == componentBitWidth;
    }
}
