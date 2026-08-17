package top.ceroxe.rt.renderer.api;

/** Unsigned integer formats accepted by indexed draw commands. */
public enum IndexFormat {
    UINT8(1, 0xFFL),
    UINT16(2, 0xFFFFL),
    UINT32(4, 0xFFFF_FFFFL);

    private final int byteSize;
    private final long primitiveRestartValue;

    IndexFormat(int byteSize, long primitiveRestartValue) {
        this.byteSize = byteSize;
        this.primitiveRestartValue = primitiveRestartValue;
    }

    /** @return exact bytes consumed by one index */
    public int byteSize() { return byteSize; }

    /** @return unsigned all-ones restart index represented as a positive {@code long} */
    public long primitiveRestartValue() { return primitiveRestartValue; }
}
