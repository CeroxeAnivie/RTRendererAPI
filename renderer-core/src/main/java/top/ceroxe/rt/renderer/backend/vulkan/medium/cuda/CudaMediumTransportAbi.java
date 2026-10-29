package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.MediumTransport;

/** Shared state for a native path; volume indices identify boundaries, not coefficient values. */
public final class CudaMediumTransportAbi {
    public static final int MAX_BOUNDARIES = MediumTransport.MAX_NESTED_BOUNDARIES;
    public static final int NULL_VOLUME = -1;
    public static final int STACK_BYTES = 144;
    public static final int CURRENT_VOLUME_OFFSET = 0;
    public static final int VOLUME_LIST_OFFSET = 4;
    public static final int VOLUME_COUNT_OFFSET = 132;
    public static final int SCATTERED_START_OFFSET = 136;
    public static final int STATUS_OK = 0;
    public static final int STATUS_OVERFLOW = 1;
    public static final int STATUS_NOT_FOUND = 2;
    public static final int STATUS_INVALID_VOLUME = 3;
    public static final int STATUS_CORRUPT_STATE = 4;

    private CudaMediumTransportAbi() { }

    public static long stackBytes(int paths) {
        if (paths < 1) throw new IllegalArgumentException("positive native path count required");
        return Math.multiplyExact((long) paths, STACK_BYTES);
    }

    public static byte[] emptyStack() {
        return ByteBuffer.allocate(STACK_BYTES).order(ByteOrder.nativeOrder())
                .putInt(CURRENT_VOLUME_OFFSET, NULL_VOLUME).array();
    }
}
