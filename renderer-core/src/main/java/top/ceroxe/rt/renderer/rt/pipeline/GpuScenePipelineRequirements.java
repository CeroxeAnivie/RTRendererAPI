package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Conservative binary reachability of the optional second miss record. */
final class GpuScenePipelineRequirements {
    private GpuScenePipelineRequirements() {}

    static boolean shadowServiceRequired(byte[] raygen, byte[] miss, byte[] closest, byte[] anyHit) {
        StageUse generation=inspect(raygen);
        // Nested/custom-stage traces retain the existing depth-two contract. Dynamic miss indices
        // also retain the service; only a proven single-level primary-miss pipeline can omit it.
        return !generation.primaryMissOnly || inspect(miss).traces
                || inspect(closest).traces || inspect(anyHit).traces;
    }

    private static StageUse inspect(byte[] spirv) {
        if (spirv.length<20 || (spirv.length&3)!=0) throw new IllegalArgumentException("invalid SPIR-V size");
        var words=ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (words.get(0)!=0x07230203) throw new IllegalArgumentException("invalid SPIR-V magic");
        Set<Integer> zeros=new HashSet<>();
        Set<Integer> integer32Types=new HashSet<>();
        for (int at=5; at<words.limit();) {
            int instruction=words.get(at), count=instruction>>>16, operation=instruction&0xffff;
            if (count==0 || count>words.limit()-at) throw new IllegalArgumentException("invalid SPIR-V instruction");
            if (operation==21 && count==4 && words.get(at+2)==32) integer32Types.add(words.get(at+1));
            if (operation==43 && count==4 && integer32Types.contains(words.get(at+1)) && words.get(at+3)==0)
                zeros.add(words.get(at+2));
            at+=count;
        }
        boolean traces=false, primaryOnly=true;
        for (int at=5; at<words.limit();) {
            int instruction=words.get(at), count=instruction>>>16, operation=instruction&0xffff;
            if (operation==4445) { // OpTraceRayKHR: operand six is the miss record index ID.
                traces=true;
                if (count!=12 || !zeros.contains(words.get(at+6))) primaryOnly=false;
            }
            at+=count;
        }
        return new StageUse(traces,primaryOnly);
    }

    private record StageUse(boolean traces, boolean primaryMissOnly) {}
}
