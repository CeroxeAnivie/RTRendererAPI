package demo;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;
import top.ceroxe.rt.renderer.backend.vulkan.MediumPerformanceRouteProbe;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline.Stage.*;

/** Native completed-frame benchmark: all closed medium features, or an explicitly selected focus case. */
public final class MediumGalleryPerformanceBenchmark {
    // Fixed arrays are allocated before the clock starts; split long rounds rather than grow in flight.
    private static final int WALL_EVENT_CAPACITY = 262_144;
    private static final String[] PROFILE_NAMES = {
            "sharedPrepare", "sharedExchange", "vulkanRelease", "cudaStageWall", "legacyResolve",
            "vulkanConsumerRecord", "vulkanConsumerFenceWait", "vulkanProducerGpu", "vulkanConsumerGpu",
            "snapshotCopy", "sharedExchangeCount", "releaseCommandRecord", "releaseQueueSubmit",
            "consumerCommandRecord", "consumerQueueSubmit", "cudaPhase0", "cudaPhase1", "cudaPhase2",
            "cudaPhase3", "cudaPhase4", "cudaPhase5", "cudaPhase6", "cudaPhase7",
            "cameraScatteringPrepare", "pbrMediumPrepare", "cameraScatteringKernelGpu",
            "pbrMediumKernelGpu", "cameraScatteringKernelCount", "pbrMediumKernelCount",
            "cameraScatteringTlasGpu", "cameraScatteringTlasCount", "cameraScatteringLightsGpu",
            "cameraScatteringLightsCount", "pbrPrimaryPacketsGpu", "pbrPrimaryPacketsCount",
            "pbrLightPacketsGpu", "pbrLightPacketsCount", "pbrContributionRestoreGpu",
            "pbrContributionRestoreCount", "cameraScatteringPrimaryGpu", "cameraScatteringPrimaryCount"
    };

    private MediumGalleryPerformanceBenchmark() { }

    public static void main(String[] args) throws Exception {
        var values = parse(args);
        int width = integer(values, "width", 1920, 1920, 1920);
        int height = integer(values, "height", 1080, 1080, 1080);
        int warmup = integer(values, "warmup", 90, 1, 100_000);
        int frames = integer(values, "frames", 120, 1, 100_000);
        int rounds = integer(values, "rounds", 3, 1, 100);
        boolean profile = bool(values, "profile", false);
        boolean shaderProfile=bool(values,"shaderProfile",false);
        boolean wallProfile=bool(values,"wallProfile",false);
        if(wallProfile) System.setProperty("top.ceroxe.rt.medium.opticalWallTimeline","true");
        else System.clearProperty("top.ceroxe.rt.medium.opticalWallTimeline");
        if (profile) System.setProperty("top.ceroxe.rt.medium.opticalFrameProfile", "true");
        else System.clearProperty("top.ceroxe.rt.medium.opticalFrameProfile");
        if(shaderProfile){System.setProperty("top.ceroxe.rt.medium.opticalDetailedProfile","true");
            System.setProperty("top.ceroxe.rt.medium.cameraScatteringShaderProfile","true");}
        else {System.clearProperty("top.ceroxe.rt.medium.opticalDetailedProfile");
            System.clearProperty("top.ceroxe.rt.medium.cameraScatteringShaderProfile");}

        String suite=values.getOrDefault("suite","all");
        if(!suite.equals("all")&&!suite.equals("focused"))throw new IllegalArgumentException("suite must be all or focused");
        List<MediumPerformanceCases.Workload> cases=suite.equals("all")?MediumPerformanceCases.all():List.of(MediumPerformanceCases.focused());
        if(values.containsKey("cases")) {
            Set<String> selected=Set.of(values.get("cases").split(","));
            var available=cases.stream().map(MediumPerformanceCases.Workload::id).collect(java.util.stream.Collectors.toSet());
            require(available.containsAll(selected),"unknown medium performance cases: "+selected);
            cases=cases.stream().filter(c->selected.contains(c.id())).toList();
        }
        if(shaderProfile)require(cases.size()==1&&cases.getFirst().id().equals("pbr-direct-uniform"),
                "camera shader-clock profile requires the focused pbr-direct-uniform case; use normal timings for the complete matrix");
        Path reportDirectory=Path.of(values.getOrDefault("output","build/reports/medium-performance")).toAbsolutePath();
        Files.createDirectories(reportDirectory);
        Path summary=reportDirectory.resolve(suite+"-"+(profile?"profiled":"unprofiled")+"-coverage.json");
        Files.writeString(summary,"{\"complete\":false,\"status\":\"running\"}\n",StandardCharsets.UTF_8);
        var completed=new ArrayList<String>();var covered=EnumSet.noneOf(MediumPerformanceCases.Feature.class);
        for(var scene:cases) {
            String evidence=runCase(scene,width,height,warmup,frames,rounds,profile,shaderProfile,wallProfile,reportDirectory);
            completed.add(evidence);covered.addAll(scene.features());
        }
        boolean full=suite.equals("all")&&!values.containsKey("cases");
        if(full)require(covered.containsAll(EnumSet.allOf(MediumPerformanceCases.Feature.class)),"completed matrix lost required features");
        Files.writeString(summary,"{\n\"scope\":\"all closed medium features and new cross-feature combinations\",\n\"complete\":"+full
                +",\n\"requiredFeatures\":"+strings(EnumSet.allOf(MediumPerformanceCases.Feature.class))
                +",\n\"executedFeatures\":"+strings(covered)+",\n\"cases\":[\n"+String.join(",\n",completed)+"\n]}\n",StandardCharsets.UTF_8);
        System.out.println("GalleryPerf coverageReport="+summary+" completedCases="+completed.size()+" complete="+full);
    }

    private static String runCase(MediumPerformanceCases.Workload scene,int width,int height,int warmup,int frames,int rounds,
            boolean profile,boolean shaderProfile,boolean wallProfile,Path reportDirectory)throws Exception {
        var frameConfig=scene.config();String stem=scene.id()+"-1080p";
        Path report = reportDirectory.resolve(stem+"-"
                + (profile ? "profiled" : shaderProfile?"shader-sampled":wallProfile?"wall-timeline":"unprofiled") + ".csv").toAbsolutePath();
        Files.createDirectories(report.getParent());
        // Capture actual request budgets before warmup, outside every measured window.
        // In particular maxEvents and scatteringSteps are different API fields.
        var workload = scene.frame(0, width, height);
        var budget = workload.mediumTransport();
        Path workloadReport = report.resolveSibling(report.getFileName().toString().replace(".csv", "-workload.json"));
        Files.writeString(workloadReport, String.format(Locale.ROOT,
                "{\"scene\":\"%s\",\"width\":%d,\"height\":%d,\"spp\":%d,\"maxEvents\":%d,"
                        + "\"opticalSamples\":%d,\"scatteringSteps\":%d,\"lightSamples\":%d,"
                        + "\"maxDistance\":%s,\"transmittanceCutoff\":%s,\"scatteringBounces\":%d,"
                        + "\"firstInterfaceSampling\":\"%s\"}%n",
                scene.id(), width, height, workload.antiAliasing().samplesPerPixel(), budget.maxEvents(),
                budget.opticalSamples(), budget.scatteringSteps(), budget.lightSamples(), budget.maxDistance(),
                budget.transmittanceCutoff(), budget.scatteringBounces(), budget.firstInterfaceSampling()), StandardCharsets.UTF_8);
        System.out.println("GalleryPerf workloadReport=" + workloadReport + " budgets=" + budget);
        System.out.printf(Locale.ROOT,
                "GalleryPerf device=RTX5080-Laptop targetScene=%s extent=%dx%d spp=%d warmup=%d frames=%d rounds=%d "
                        + "profile=%s wallProfile=%s frameGeneration=false reconstruction=false temporal=false denoising=false cpuReadback=false%n",
                scene.id(), width, height,workload.antiAliasing().samplesPerPixel(), warmup, frames, rounds, profile, wallProfile);

        var routesBefore=MediumPerformanceRouteProbe.snapshot();
        try (var renderer = Renderer.open(frameConfig);
             var output = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
            renderer.apply(scene.transaction());
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            var sequence = new long[]{0};
            Result warmupResult = range(renderer, interop, scene, width, height, sequence, warmup);
            requireHealthy(renderer, "warmup");
            long eventsPerFrame = warmupResult.timeline()==null ? 0
                    : Math.max(2048L, Math.ceilDiv(warmupResult.timeline().attemptedEvents(),warmup)*2L);
            int windowFrames = wallWindowFrames(frames,eventsPerFrame);
            System.out.println("GalleryPerf wallWindowFrames="+windowFrames+" warmupEventBudgetPerFrame="+eventsPerFrame);
            long[] profileBefore = OpticalFrameTimings.snapshot();
            var detailedBefore=top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.snapshot();
            var stagesBefore=OpticalFrameTimings.stageSnapshot();
            output.println("round,frames,wallMs,completedFps,submitCpuMs,requestBuildCpuMs,pollCpuMs,deferrals,"
                    + "gpuFrameMeanMs,gpuFrameLowerMs,gpuFrameUpperMs,gpuSamples,profileData,stageData");
            int totalFrames = Math.multiplyExact(frames,rounds), window = 0;
            for (int offset = 0; offset < totalFrames;) {
                int round = window++;
                int count = Math.min(windowFrames,Math.min(frames-offset%frames,totalFrames-offset));
                Result result = range(renderer, interop, scene, width, height, sequence, count);
                offset += count;
                requireHealthy(renderer, "round " + (round + 1));
                long[] profileAfter = OpticalFrameTimings.snapshot();
                long[] delta = subtract(profileAfter, profileBefore);
                profileBefore = profileAfter;
                String stageJson=OpticalFrameTimings.stageDeltaJson(stagesBefore,result.frames());
                stagesBefore=OpticalFrameTimings.stageSnapshot();
                Files.writeString(report.resolveSibling(stem+"-gpu-stages-round-"+(round+1)+".json"),stageJson,StandardCharsets.UTF_8);
                if(shaderProfile){
                    String shaderSummary=top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile
                            .cameraScatteringSummaryJson(detailedBefore,result.frames());
                    detailedBefore=top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.snapshot();
                    Path shaderReport=reportDirectory.resolve(stem+"-shader-profile-round-"
                            +(round+1)+".json").toAbsolutePath();
                    Files.writeString(shaderReport,shaderSummary,StandardCharsets.UTF_8);
                    System.out.println("GalleryPerf shaderStageReport="+shaderReport+" stages="+shaderSummary.replace('\n',' '));
                }
                if (result.timeline() != null) {
                    Path timelineReport = report.resolveSibling(stem+"-wall-timeline-round-" + (round + 1) + ".json");
                    Files.writeString(timelineReport, result.timeline().json(result.frames()), StandardCharsets.UTF_8);
                    require(result.timeline().droppedEvents()==0,"wall timeline lost events for "+scene.id()
                            +" attempted="+result.timeline().attemptedEvents()+" capacity="+WALL_EVENT_CAPACITY
                            +"; failed evidence="+timelineReport);
                    System.out.println("GalleryPerf wallTimelineReport=" + timelineReport);
                }
                RendererDiagnostics.FrameGpuTiming gpu = result.gpu().after();
                double gpuMeanMs = result.gpu().estimateNanos().doubleValue() / result.gpu().samples() / 1e6;
                double gpuLowMs = result.gpu().lowerNanos().doubleValue() / result.gpu().samples() / 1e6;
                double gpuHighMs = result.gpu().upperNanos().doubleValue() / result.gpu().samples() / 1e6;
                String profileJson = profileJson(delta, result.frames());
                System.out.printf(Locale.ROOT,
                        "GalleryPerf round=%d frames=%d wallMs=%.3f completedFps=%.2f submitCpuMs=%.3f "
                                + "requestBuildCpuMs=%.3f pollCpuMs=%.3f deferrals=%d gpuFrameMeanMs=%.4f "
                        + "gpuBoundsMs=[%.4f,%.4f] profile=%s shaderProfile=%s%n",
                        round + 1, result.frames(), result.wallNanos() / 1e6, result.frames() * 1e9 / result.wallNanos(),
                        result.submitNanos() / 1e6, result.requestBuildNanos() / 1e6, result.pollNanos() / 1e6,
                        result.deferrals(), gpuMeanMs, gpuLowMs, gpuHighMs, profileJson,shaderProfile);
                output.printf(Locale.ROOT,
                        "%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.9f,%.9f,%.9f,%d,\"%s\",\"%s\"%n",
                        round + 1, result.frames(), result.wallNanos() / 1e6,
                        result.frames() * 1e9 / result.wallNanos(), result.submitNanos() / 1e6,
                        result.requestBuildNanos() / 1e6, result.pollNanos() / 1e6, result.deferrals(),
                        gpuMeanMs, gpuLowMs, gpuHighMs, result.gpu().samples(), profileJson.replace("\"", "\"\""),stageJson.replace('\n',' ').replace("\"","\"\""));
                output.flush();
                if (output.checkError()) throw new IllegalStateException("failed to write " + report);
            }
            require(renderer.diagnostics().frameGenerationEvidence().generatedFramesActuallyPresented() == 0,
                    "native benchmark must not count generated frames");
            System.out.println("GalleryPerf warmupFrames=" + warmupResult.frames() + " report=" + report);
        }
        var routes=MediumPerformanceRouteProbe.delta(routesBefore);
        boolean zeroDensity=scene.features().contains(MediumPerformanceCases.Feature.ZERO_DENSITY);
        require(zeroDensity||routes.entrySet().stream().filter(e->!e.getKey().equals("photonMaps")).mapToLong(e->e.getValue()).sum()>=warmup+(long)frames*rounds,
                "medium performance case bypassed native execution: "+scene.id()+" "+routes);
        require(!workload.caustics().enabled()||routes.getOrDefault("photonMaps",0L)>0,
                "caustics performance case never produced a photon map: "+scene.id());
        return "{\"case\":\""+scene.id()+"\",\"features\":"+strings(scene.features())+",\"nativeRoutes\":"+longMap(routes)
                +",\"measuredFrames\":"+((long)frames*rounds)+",\"csv\":\""+report.getFileName()+"\"}";
    }

    private static Result range(Renderer renderer, VulkanFrameInterop interop, MediumPerformanceCases.Workload scene,
            int width, int height, long[] sequence, int frames) throws InterruptedException {
        RendererDiagnostics beforeDiagnostics = renderer.diagnostics();
        var beforeGpu = beforeDiagnostics.frameGpuTiming();
        long firstSequence = sequence[0], last = Math.addExact(firstSequence, frames) - 1;
        var timeline = OpticalWallTimeline.ENABLED ? OpticalWallTimeline.allocate(WALL_EVENT_CAPACITY) : null;
        long started = timeline == null ? System.nanoTime() : timeline.begin();
        long requestBuild = 0, submit = 0, poll = 0, deferrals = 0;
        long deadline = started + Duration.ofMinutes(5).toNanos();
        RendererDiagnostics after = beforeDiagnostics;
        RenderFrameRequest pending = null;
        while (after.latestCompletedFrameSequence() < last) {
            if (Thread.interrupted()) throw new InterruptedException("gallery benchmark interrupted");
            while (sequence[0] <= last) {
                if (pending == null) {
                    long buildStarted = System.nanoTime();
                    int timing = OpticalWallTimeline.enter(REQUEST_BUILD);
                    try { pending = scene.frame(sequence[0], width, height); }
                    finally { OpticalWallTimeline.leave(timing); }
                    requestBuild += System.nanoTime() - buildStarted;
                }
                long submitStarted = System.nanoTime();
                Renderer.FrameSubmissionAttempt attempt;
                int timing = OpticalWallTimeline.enter(TRY_SUBMIT);
                try { attempt = renderer.trySubmit(pending); }
                finally { OpticalWallTimeline.leave(timing); }
                submit += System.nanoTime() - submitStarted;
                if (!(attempt instanceof Renderer.FrameSubmitted)) { deferrals++; break; }
                sequence[0]++;
                pending = null;
            }
            long pollStarted = System.nanoTime();
            int timing = OpticalWallTimeline.enter(FRAME_POLL_AND_LEASE);
            try {
                if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
                    available.lease().close();
            } finally { OpticalWallTimeline.leave(timing); }
            poll += System.nanoTime() - pollStarted;
            timing = OpticalWallTimeline.enter(DIAGNOSTICS);
            try { after = renderer.diagnostics(); }
            finally { OpticalWallTimeline.leave(timing); }
            if (after.status() != Renderer.Status.READY) throw new AssertionError("renderer left READY: " + after);
            if (System.nanoTime() > deadline) throw new AssertionError("gallery benchmark timed out at sequence " + last);
            if (after.latestCompletedFrameSequence() < last) {
                timing = OpticalWallTimeline.enter(PARK);
                try { LockSupport.parkNanos(100_000L); }
                finally { OpticalWallTimeline.leave(timing); }
            }
        }
        if (sequence[0] != last + 1 || after.latestSubmittedFrameSequence() != last)
            throw new AssertionError("benchmark batch did not submit its complete sequence");
        int timing = OpticalWallTimeline.enter(FRAME_POLL_AND_LEASE);
        try {
            if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
                available.lease().close();
        } finally { OpticalWallTimeline.leave(timing); }
        timing = OpticalWallTimeline.enter(DIAGNOSTICS);
        RendererDiagnostics.FrameGpuTiming afterGpu;
        try { afterGpu = renderer.diagnostics().frameGpuTiming(); }
        finally { OpticalWallTimeline.leave(timing); }
        long elapsed = timeline == null ? System.nanoTime() - started : timeline.finish();
        var window = gpuWindow(beforeGpu, afterGpu, frames);
        return new Result(frames, elapsed, submit, requestBuild, poll, deferrals, window, timeline);
    }

    private static GpuWindow gpuWindow(RendererDiagnostics.FrameGpuTiming before,
            RendererDiagnostics.FrameGpuTiming after, long samples) {
        require(before.enabled() && after.enabled() && after.completedSamples() - before.completedSamples() == samples
                        && after.failedSamples() == before.failedSamples()
                        && after.droppedSamples() == before.droppedSamples(),
                "incomplete GPU timestamps: " + before.completedSamples() + " -> " + after.completedSamples());
        BigInteger beforeCount = BigInteger.valueOf(before.completedSamples());
        BigInteger afterCount = BigInteger.valueOf(after.completedSamples());
        BigInteger estimate = BigInteger.valueOf(after.averageTotalNanos()).multiply(afterCount)
                .subtract(BigInteger.valueOf(before.averageTotalNanos()).multiply(beforeCount));
        BigInteger lower = estimate.subtract(beforeCount.subtract(BigInteger.ONE).max(BigInteger.ZERO)).max(BigInteger.ZERO);
        BigInteger upper = estimate.add(afterCount.subtract(BigInteger.ONE));
        require(estimate.signum() > 0 && upper.compareTo(lower) >= 0, "GPU timestamp window is not precise enough");
        return new GpuWindow(samples, estimate, lower, upper, after);
    }

    private static String profileJson(long[] values, int frames) {
        if (!OpticalFrameTimings.ENABLED) return "{}";
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) json.append(',');
            boolean count = i == 10 || i == 27 || i == 28 || i == 30 || i == 32 || i == 34
                    || i == 36 || i == 38 || i == 40;
            double perFrame = values[i] / (double) frames;
            String unit = count ? "PerFrame" : "MsPerFrame";
            json.append('"').append(PROFILE_NAMES[i]).append(unit).append("\":")
                    .append(String.format(Locale.ROOT, count ? "%.3f" : "%.6f", count ? perFrame : perFrame / 1e6));
        }
        return json.append('}').toString();
    }

    private static long[] subtract(long[] after, long[] before) {
        if (after.length != before.length) throw new IllegalStateException("profile ABI changed during benchmark");
        long[] delta = new long[after.length];
        for (int i = 0; i < delta.length; i++) delta[i] = after[i] - before[i];
        return delta;
    }

    private static void requireHealthy(Renderer renderer, String phase) {
        var health = renderer.health();
        if (health.activeFailure().isPresent() || health.obligations().outstandingGpuFrameLeases() != 0)
            throw new AssertionError("renderer unhealthy after " + phase + ": " + health);
    }

    private static java.util.Map<String, String> parse(String[] args) {
        var values = new java.util.HashMap<String, String>();
        for (String arg : args) {
            int at = arg.indexOf('=');
            if (!arg.startsWith("--") || at < 3) throw new IllegalArgumentException("expected --name=value: " + arg);
            if(!Set.of("width","height","warmup","frames","rounds","profile","shaderProfile","wallProfile","suite","cases","output")
                    .contains(arg.substring(2,at)))throw new IllegalArgumentException("unknown benchmark option: "+arg);
            if (values.put(arg.substring(2, at), arg.substring(at + 1)) != null)
                throw new IllegalArgumentException("duplicate option: " + arg);
        }
        return values;
    }
    private static int integer(java.util.Map<String, String> values, String key, int fallback, int low, int high) {
        int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        if (value < low || value > high) throw new IllegalArgumentException(key + " must be within [" + low + ", " + high + "]");
        return value;
    }
    private static boolean bool(java.util.Map<String, String> values, String key, boolean fallback) {
        String value = values.getOrDefault(key, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String strings(java.util.Collection<?> values){return values.stream().map(Object::toString).sorted()
            .map(v->"\""+v+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));}
    private static String longMap(Map<String,Long> values){return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e->"\""+e.getKey()+"\":"+e.getValue()).collect(java.util.stream.Collectors.joining(",","{","}"));}

    static int wallWindowFrames(int requestedFrames,long eventsPerFrame) {
        if(eventsPerFrame==0)return requestedFrames;
        require(eventsPerFrame<WALL_EVENT_CAPACITY,"single-frame wall event budget exceeds fixed capacity: "+eventsPerFrame);
        return (int)Math.min(requestedFrames,(WALL_EVENT_CAPACITY-1L)/eventsPerFrame);
    }

    private record GpuWindow(long samples, BigInteger estimateNanos, BigInteger lowerNanos,
            BigInteger upperNanos, RendererDiagnostics.FrameGpuTiming after) { }
    private record Result(int frames, long wallNanos, long submitNanos, long requestBuildNanos,
            long pollNanos, long deferrals, GpuWindow gpu, OpticalWallTimeline.Recording timeline) { }
}
