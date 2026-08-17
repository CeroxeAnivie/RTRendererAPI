package top.ceroxe.rt.renderer.api.interop;

/**
 * Byte region containing an externally shared image within one exported allocation.
 *
 * @param allocationSizeBytes positive complete allocation size
 * @param imageOffsetBytes non-negative image binding offset
 * @param imageAccessibleBytes positive byte range needed by the imported image
 * @param dedicated whether the allocation contains only this image
 */
public record ExternalMemoryRegion(
        long allocationSizeBytes,
        long imageOffsetBytes,
        long imageAccessibleBytes,
        boolean dedicated
) {
    public ExternalMemoryRegion {
        if (allocationSizeBytes <= 0L || imageOffsetBytes < 0L || imageAccessibleBytes <= 0L) {
            throw new IllegalArgumentException("external-memory sizes must be positive and offset non-negative");
        }
        long end = checkedEnd(imageOffsetBytes, imageAccessibleBytes);
        if (end > allocationSizeBytes) {
            throw new IllegalArgumentException("external-memory image region exceeds its allocation");
        }
        if (dedicated && (imageOffsetBytes != 0L || imageAccessibleBytes != allocationSizeBytes)) {
            throw new IllegalArgumentException("dedicated external memory must expose the complete allocation");
        }
    }

    private static long checkedEnd(long offset, long length) {
        try {
            return Math.addExact(offset, length);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "external-memory region overflows signed 64-bit range", overflow
            );
        }
    }
}
