package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.util.concurrent.atomic.LongAdder;

/** Opt-in wall-time attribution. GPU durations are overlapping evidence, never additive wall buckets. */
public final class OpticalFrameTimings {
    public static final boolean ENABLED=Boolean.getBoolean("top.ceroxe.rt.medium.opticalFrameProfile");
    public static final boolean KERNEL_PHASES=ENABLED&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalKernelPhaseProfile");
    public static final LongAdder prepare=new LongAdder(),exchange=new LongAdder(),release=new LongAdder();
    public static final LongAdder cudaStage=new LongAdder(),resolve=new LongAdder(),consumerRecord=new LongAdder(),consumerWait=new LongAdder();
    public static final LongAdder producerGpu=new LongAdder(),consumerGpu=new LongAdder(),snapshotCopy=new LongAdder(),rounds=new LongAdder();
    public static final LongAdder releaseCommands=new LongAdder(),releaseSubmit=new LongAdder();
    public static final LongAdder consumerCommands=new LongAdder(),consumerSubmit=new LongAdder();
    public static final LongAdder[] kernelPhases=java.util.stream.IntStream.range(0,8).mapToObj(i->new LongAdder()).toArray(LongAdder[]::new);
    private OpticalFrameTimings() { }
    public static long[] snapshot() {
        return new long[]{prepare.sum(),exchange.sum(),release.sum(),cudaStage.sum(),resolve.sum(),consumerRecord.sum(),
                consumerWait.sum(),producerGpu.sum(),consumerGpu.sum(),snapshotCopy.sum(),rounds.sum(),
                releaseCommands.sum(),releaseSubmit.sum(),consumerCommands.sum(),consumerSubmit.sum(),
                kernelPhases[0].sum(),kernelPhases[1].sum(),kernelPhases[2].sum(),kernelPhases[3].sum(),
                kernelPhases[4].sum(),kernelPhases[5].sum(),kernelPhases[6].sum(),kernelPhases[7].sum()};
    }
}
