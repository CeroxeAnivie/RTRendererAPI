package top.ceroxe.rt.renderer.api;

/** Portable index encodings accepted by triangle acceleration-structure geometry. */
public enum AccelerationStructureIndexFormat {
    UINT16(2),
    UINT32(4);

    private final int byteSize;

    AccelerationStructureIndexFormat(int byteSize) {
        this.byteSize = byteSize;
    }

    /** @return exact bytes occupied by one index */
    public int byteSize() { return byteSize; }
}
