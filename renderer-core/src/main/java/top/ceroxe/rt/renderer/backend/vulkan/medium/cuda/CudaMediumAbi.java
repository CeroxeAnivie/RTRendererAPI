package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** The batch protocol around the unchanged upstream segment function, not a public renderer ABI. */
public final class CudaMediumAbi {
    public static final int INPUT_FLOATS = 12;
    public static final int OUTPUT_FLOATS = 7;
    public static final int INPUT_BYTES = INPUT_FLOATS * Float.BYTES;
    public static final int OUTPUT_BYTES = OUTPUT_FLOATS * Float.BYTES;
    public static final String UPSTREAM_COMMIT = "b872186d2fe995a762f73708c606847eed55843e";
    private CudaMediumAbi() { }
    public static long inputBytes(int count) { return bytes(count, INPUT_BYTES); }
    public static long outputBytes(int count) { return bytes(count, OUTPUT_BYTES); }
    private static long bytes(int count, int stride) {
        if (count < 1) throw new IllegalArgumentException("segment count must be positive");
        return Math.multiplyExact((long) count, stride);
    }
}
