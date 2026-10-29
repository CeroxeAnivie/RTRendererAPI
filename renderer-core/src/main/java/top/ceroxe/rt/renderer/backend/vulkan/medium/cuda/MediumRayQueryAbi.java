package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

/** Internal std430/CUDA ray exchange protocol. Identifiers are uint bits, never float conversions. */
public final class MediumRayQueryAbi {
    public static final int RECORD_BYTES = 128;
    public static final int ORIGIN_TMIN = 0;
    public static final int DIRECTION_TMAX = 16;
    /** uint active, query kind, path identifier, request epoch. */
    public static final int CONTROL = 32;
    /** float hit distance (-1 for miss), barycentric u/v, front-facing flag. */
    public static final int HIT = 48;
    /** uint primitive, instance custom index, geometry index, echoed request epoch. */
    public static final int HIT_IDS = 64;
    /** The last three vec4 lanes are owned by the native path continuation. */
    public static final int PATH_STATE = 80;
    public static final int CLOSEST = 0;
    public static final int OCCLUSION = 1;
    public static final int MISS_ID = -1;
    private MediumRayQueryAbi() { }
    public static long bytes(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("ray capacity must be positive");
        return Math.multiplyExact((long) capacity, RECORD_BYTES);
    }
    public static void requireCount(int count, int capacity) {
        if (count < 1 || count > capacity) throw new IllegalArgumentException("ray count exceeds slot capacity");
    }
}
