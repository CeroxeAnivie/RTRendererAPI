package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.util.concurrent.atomic.LongAdder;

/** Opt-in wall-time attribution. GPU durations are overlapping evidence, never additive wall buckets. */
public final class OpticalFrameTimings {
    public static final boolean ENABLED=Boolean.getBoolean("top.ceroxe.rt.medium.opticalFrameProfile");
    public static final boolean ENTRY_PHASES=ENABLED&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalEntryPhaseProfile");
    public static final boolean FINISH_PHASES=ENABLED&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalFinishPhaseProfile");
    public static final boolean KERNEL_PHASES=ENABLED&&(ENTRY_PHASES||FINISH_PHASES||Boolean.getBoolean("top.ceroxe.rt.medium.opticalKernelPhaseProfile"));
    public static final LongAdder prepare=new LongAdder(),exchange=new LongAdder(),release=new LongAdder();
    public static final LongAdder cudaStage=new LongAdder(),resolve=new LongAdder(),consumerRecord=new LongAdder(),consumerWait=new LongAdder();
    public static final LongAdder producerGpu=new LongAdder(),consumerGpu=new LongAdder(),snapshotCopy=new LongAdder(),rounds=new LongAdder();
    public static final LongAdder releaseCommands=new LongAdder(),releaseSubmit=new LongAdder();
    public static final LongAdder consumerCommands=new LongAdder(),consumerSubmit=new LongAdder();
    public static final LongAdder cameraScatteringPrepare=new LongAdder(),pbrMediumPrepare=new LongAdder();
    public static final LongAdder cameraScatteringKernelGpu=new LongAdder(),pbrMediumKernelGpu=new LongAdder();
    public static final LongAdder cameraScatteringKernelCount=new LongAdder(),pbrMediumKernelCount=new LongAdder();
    public static final LongAdder cameraScatteringTlasGpu=new LongAdder(),cameraScatteringTlasCount=new LongAdder();
    public static final LongAdder cameraScatteringLightsGpu=new LongAdder(),cameraScatteringLightsCount=new LongAdder();
    /** Nested in cameraScatteringLightsGpu; never add it again to the enclosing producer. */
    public static final LongAdder cameraScatteringPrimaryGpu=new LongAdder(),cameraScatteringPrimaryCount=new LongAdder();
    public static final LongAdder pbrPrimaryPacketsGpu=new LongAdder(),pbrPrimaryPacketsCount=new LongAdder();
    public static final LongAdder pbrLightPacketsGpu=new LongAdder(),pbrLightPacketsCount=new LongAdder();
    public static final LongAdder pbrContributionRestoreGpu=new LongAdder(),pbrContributionRestoreCount=new LongAdder();
    public static final LongAdder[] kernelPhases=java.util.stream.IntStream.range(0,8).mapToObj(i->new LongAdder()).toArray(LongAdder[]::new);
    private static final java.util.Map<String,LongAdder[]> roundDetails=new java.util.concurrent.ConcurrentHashMap<>();
    private OpticalFrameTimings() { }
    public static void recordCudaKernel(String stage,long nanos) {
        if(!ENABLED)return;
        switch(stage) {
            case "cameraScattering.resolve" -> {cameraScatteringKernelGpu.add(nanos);cameraScatteringKernelCount.increment();}
            case "pbrMedium.resolve" -> {pbrMediumKernelGpu.add(nanos);pbrMediumKernelCount.increment();}
            default -> throw new IllegalArgumentException("unknown profiled CUDA kernel: "+stage);
        }
    }
    public static void recordGpuStage(String stage,long nanos) {
        if(!ENABLED)return;
        if(nanos<0)throw new IllegalArgumentException("GPU stage duration must not be negative");
        switch(stage) {
            case "cameraScattering.tlas" -> {cameraScatteringTlasGpu.add(nanos);cameraScatteringTlasCount.increment();}
            case "cameraScattering.lights" -> {cameraScatteringLightsGpu.add(nanos);cameraScatteringLightsCount.increment();}
            case "cameraScattering.primary" -> {cameraScatteringPrimaryGpu.add(nanos);cameraScatteringPrimaryCount.increment();}
            case "pbrMedium.primaryPackets" -> {pbrPrimaryPacketsGpu.add(nanos);pbrPrimaryPacketsCount.increment();}
            case "pbrMedium.lightPackets" -> {pbrLightPacketsGpu.add(nanos);pbrLightPacketsCount.increment();}
            case "pbrMedium.contributionRestore" -> {pbrContributionRestoreGpu.add(nanos);pbrContributionRestoreCount.increment();}
            default -> throw new IllegalArgumentException("unknown profiled Vulkan stage: "+stage);
        }
    }
    public static void recordRound(int opticalSample,int branch,int round,int queries,int[] statistics,long[] phases) {
        if(!KERNEL_PHASES)return;
        String key="sample"+opticalSample+"-branch"+branch+"-round"+round;
        var values=roundDetails.computeIfAbsent(key,k->java.util.stream.IntStream.range(0,14)
                .mapToObj(i->new LongAdder()).toArray(LongAdder[]::new));
        values[0].increment();values[1].add(queries);
        for(int i=0;i<4;i++)values[2+i].add(statistics[i]);
        for(int i=0;i<8;i++)values[6+i].add(phases[i]);
    }
    public static java.util.Map<String,long[]> roundSnapshot() {
        var result=new java.util.TreeMap<String,long[]>();
        roundDetails.forEach((key,values)->result.put(key,java.util.Arrays.stream(values).mapToLong(LongAdder::sum).toArray()));
        return result;
    }
    public static String roundDeltaJson(java.util.Map<String,long[]> before) {
        StringBuilder json=new StringBuilder("{\n\"sampling\":\"")
                .append(ENTRY_PHASES?"entry stages only":FINISH_PHASES?"finish stages only":"whole path stages")
                .append("; sampled clock64 latency, not full-frame time\",\n\"columns\":[\"calls\",\"inputQueries\",\"continued\",\"errors\",\"interfaces\",\"completed\",\"phase0\",\"phase1\",\"phase2\",\"phase3\",\"phase4\",\"phase5\",\"phase6\",\"samples\"],\n\"rounds\":{");
        boolean first=true;
        for(var entry:roundSnapshot().entrySet()){
            if(!first)json.append(',');first=false;
            long[] values=entry.getValue(),old=before.get(entry.getKey());
            json.append("\n\"").append(entry.getKey()).append("\":[");
            for(int i=0;i<values.length;i++){if(i>0)json.append(',');json.append(values[i]-(old==null?0:old[i]));}
            json.append(']');
        }
        return json.append("\n}}\n").toString();
    }
    public static long[] snapshot() {
        return new long[]{prepare.sum(),exchange.sum(),release.sum(),cudaStage.sum(),resolve.sum(),consumerRecord.sum(),
                consumerWait.sum(),producerGpu.sum(),consumerGpu.sum(),snapshotCopy.sum(),rounds.sum(),
                releaseCommands.sum(),releaseSubmit.sum(),consumerCommands.sum(),consumerSubmit.sum(),
                kernelPhases[0].sum(),kernelPhases[1].sum(),kernelPhases[2].sum(),kernelPhases[3].sum(),
                kernelPhases[4].sum(),kernelPhases[5].sum(),kernelPhases[6].sum(),kernelPhases[7].sum(),
                cameraScatteringPrepare.sum(),pbrMediumPrepare.sum(),cameraScatteringKernelGpu.sum(),
                pbrMediumKernelGpu.sum(),cameraScatteringKernelCount.sum(),pbrMediumKernelCount.sum(),
                cameraScatteringTlasGpu.sum(),cameraScatteringTlasCount.sum(),cameraScatteringLightsGpu.sum(),
                cameraScatteringLightsCount.sum(),pbrPrimaryPacketsGpu.sum(),pbrPrimaryPacketsCount.sum(),
                pbrLightPacketsGpu.sum(),pbrLightPacketsCount.sum(),pbrContributionRestoreGpu.sum(),
                pbrContributionRestoreCount.sum(),cameraScatteringPrimaryGpu.sum(),cameraScatteringPrimaryCount.sum()};
    }
}
