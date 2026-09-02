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

    /**
     * Returns whether one vertex storage format can feed this interface.
     *
     * <p>Normalized integer attributes are converted to floating point by the graphics API before
     * they reach the shader, so their storage width is intentionally independent from the shader
     * component width (within the declared portable widths).</p>
     */
    public boolean accepts(VertexFormat format) {
        java.util.Objects.requireNonNull(format, "format");
        if (format.componentCount() != componentCount) return false;
        if (format.normalized()) {
            return numericType == NumericType.FLOATING_POINT
                    && (format.numericType() == VertexFormat.NumericType.SIGNED_INTEGER
                    || format.numericType() == VertexFormat.NumericType.UNSIGNED_INTEGER)
                    && format.componentBitWidth() > 0
                    && format.componentBitWidth() <= componentBitWidth;
        }
        NumericType storage = switch (format.numericType()) {
            case FLOATING_POINT -> NumericType.FLOATING_POINT;
            case SIGNED_INTEGER -> NumericType.SIGNED_INTEGER;
            case UNSIGNED_INTEGER -> NumericType.UNSIGNED_INTEGER;
        };
        return storage == numericType
                && format.componentBitWidth() == componentBitWidth;
    }
}
