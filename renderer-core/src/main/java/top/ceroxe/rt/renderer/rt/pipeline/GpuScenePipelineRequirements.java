package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Nested traces, rather than miss-record numbers, determine the required recursion depth. */
final class GpuScenePipelineRequirements {
    private GpuScenePipelineRequirements() {}
    static int recursionDepth(byte[] miss, byte[] closest, byte[] anyHit) {
        return traces(miss) || traces(closest) || traces(anyHit) ? 2 : 1;
    }
    private static boolean traces(byte[] spirv) {
        if (spirv.length < 20 || (spirv.length & 3) != 0) throw new IllegalArgumentException("invalid SPIR-V size");
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (words.get(0) != 0x07230203) throw new IllegalArgumentException("invalid SPIR-V magic");
        for (int at = 5; at < words.limit();) {
            int instruction = words.get(at), count = instruction >>> 16;
            if (count == 0 || count > words.limit() - at) throw new IllegalArgumentException("invalid SPIR-V instruction");
            if ((instruction & 0xffff) == 4445) return true;
            at += count;
        }
        return false;
    }
}
