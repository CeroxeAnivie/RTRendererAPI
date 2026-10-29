package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.util.concurrent.atomic.LongAdder;

/** Opt-in wall-time attribution. GPU durations are overlapping evidence, never additive wall buckets. */
public final class OpticalFrameTimings {
    public static final boolean ENABLED=Boolean.getBoolean("top.ceroxe.rt.medium.opticalFrameProfile");
    public static final LongAdder prepare=new LongAdder(),exchange=new LongAdder(),release=new LongAdder();
    public static final LongAdder cudaStage=new LongAdder(),resolve=new LongAdder(),consumerRecord=new LongAdder(),consumerWait=new LongAdder();
    public static final LongAdder producerGpu=new LongAdder(),consumerGpu=new LongAdder(),snapshotCopy=new LongAdder(),rounds=new LongAdder();
    private OpticalFrameTimings() { }
    public static long[] snapshot() {
        return new long[]{prepare.sum(),exchange.sum(),release.sum(),cudaStage.sum(),resolve.sum(),consumerRecord.sum(),
                consumerWait.sum(),producerGpu.sum(),consumerGpu.sum(),snapshotCopy.sum(),rounds.sum()};
    }
}
