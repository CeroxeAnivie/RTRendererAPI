package demo;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;

/** Focused steady-state benchmark for the actual default PBR uniform-scattering gallery scene. */
public final class MediumGalleryPerformanceBenchmark {
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
            "pbrContributionRestoreCount"
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
        if (profile) System.setProperty("top.ceroxe.rt.medium.opticalFrameProfile", "true");
        else System.clearProperty("top.ceroxe.rt.medium.opticalFrameProfile");

        var options = MediumGalleryDemo.Options.parse(new String[]{
                "--width=" + width, "--height=" + height, "--spp=1", "--scene=pbr-direct-uniform",
                "--fg=false", "--caustics=false", "--animate=false", "--controls=false"
        });
        var scene = new MediumGalleryScene(options);
        var frameConfig = MediumGalleryDemo.configuration(options);
        Path report = Path.of("build/reports/medium-performance/pbr-direct-uniform-1080p-"
                + (profile ? "profiled" : "unprofiled") + ".csv").toAbsolutePath();
        Files.createDirectories(report.getParent());
        System.out.printf(Locale.ROOT,
                "GalleryPerf device=RTX5080-Laptop targetScene=%s extent=%dx%d spp=1 warmup=%d frames=%d rounds=%d "
                        + "profile=%s frameGeneration=false reconstruction=false temporal=false denoising=false cpuReadback=false%n",
                scene.preset().id, width, height, warmup, frames, rounds, profile);

        try (var renderer = Renderer.open(frameConfig);
             var output = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
            renderer.apply(scene.transaction());
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            var sequence = new long[]{0};
            Result warmupResult = range(renderer, interop, scene, width, height, sequence, warmup);
            requireHealthy(renderer, "warmup");
            long[] profileBefore = OpticalFrameTimings.snapshot();
            output.println("round,frames,wallMs,completedFps,submitCpuMs,requestBuildCpuMs,pollCpuMs,deferrals,"
                    + "gpuFrameMeanMs,gpuFrameLowerMs,gpuFrameUpperMs,gpuSamples,profileData");
            for (int round = 0; round < rounds; round++) {
                Result result = range(renderer, interop, scene, width, height, sequence, frames);
                requireHealthy(renderer, "round " + (round + 1));
                long[] profileAfter = OpticalFrameTimings.snapshot();
                long[] delta = subtract(profileAfter, profileBefore);
                profileBefore = profileAfter;
                RendererDiagnostics.FrameGpuTiming gpu = result.gpu().after();
                double gpuMeanMs = result.gpu().estimateNanos().doubleValue() / result.gpu().samples() / 1e6;
                double gpuLowMs = result.gpu().lowerNanos().doubleValue() / result.gpu().samples() / 1e6;
                double gpuHighMs = result.gpu().upperNanos().doubleValue() / result.gpu().samples() / 1e6;
                String profileJson = profileJson(delta, result.frames());
                System.out.printf(Locale.ROOT,
                        "GalleryPerf round=%d frames=%d wallMs=%.3f completedFps=%.2f submitCpuMs=%.3f "
                                + "requestBuildCpuMs=%.3f pollCpuMs=%.3f deferrals=%d gpuFrameMeanMs=%.4f "
                                + "gpuBoundsMs=[%.4f,%.4f] profile=%s%n",
                        round + 1, result.frames(), result.wallNanos() / 1e6, result.frames() * 1e9 / result.wallNanos(),
                        result.submitNanos() / 1e6, result.requestBuildNanos() / 1e6, result.pollNanos() / 1e6,
                        result.deferrals(), gpuMeanMs, gpuLowMs, gpuHighMs, profileJson);
                output.printf(Locale.ROOT,
                        "%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.9f,%.9f,%.9f,%d,\"%s\"%n",
                        round + 1, result.frames(), result.wallNanos() / 1e6,
                        result.frames() * 1e9 / result.wallNanos(), result.submitNanos() / 1e6,
                        result.requestBuildNanos() / 1e6, result.pollNanos() / 1e6, result.deferrals(),
                        gpuMeanMs, gpuLowMs, gpuHighMs, result.gpu().samples(), profileJson.replace("\"", "\"\""));
                output.flush();
                if (output.checkError()) throw new IllegalStateException("failed to write " + report);
            }
            require(renderer.diagnostics().frameGenerationEvidence().generatedFramesActuallyPresented() == 0,
                    "native benchmark must not count generated frames");
            System.out.println("GalleryPerf warmupFrames=" + warmupResult.frames() + " report=" + report);
        }
    }

    private static Result range(Renderer renderer, VulkanFrameInterop interop, MediumGalleryScene scene,
            int width, int height, long[] sequence, int frames) throws InterruptedException {
        RendererDiagnostics beforeDiagnostics = renderer.diagnostics();
        var beforeGpu = beforeDiagnostics.frameGpuTiming();
        long firstSequence = sequence[0], last = Math.addExact(firstSequence, frames) - 1;
        long started = System.nanoTime(), requestBuild = 0, submit = 0, poll = 0, deferrals = 0;
        long deadline = started + Duration.ofMinutes(5).toNanos();
        RendererDiagnostics after = beforeDiagnostics;
        RenderFrameRequest pending = null;
        while (after.latestCompletedFrameSequence() < last) {
            if (Thread.interrupted()) throw new InterruptedException("gallery benchmark interrupted");
            while (sequence[0] <= last) {
                if (pending == null) {
                    long buildStarted = System.nanoTime();
                    pending = scene.frame(sequence[0], width, height, 0, 0);
                    requestBuild += System.nanoTime() - buildStarted;
                }
                long submitStarted = System.nanoTime();
                Renderer.FrameSubmissionAttempt attempt = renderer.trySubmit(pending);
                submit += System.nanoTime() - submitStarted;
                if (!(attempt instanceof Renderer.FrameSubmitted)) { deferrals++; break; }
                sequence[0]++;
                pending = null;
            }
            long pollStarted = System.nanoTime();
            if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
                available.lease().close();
            poll += System.nanoTime() - pollStarted;
            after = renderer.diagnostics();
            if (after.status() != Renderer.Status.READY) throw new AssertionError("renderer left READY: " + after);
            if (System.nanoTime() > deadline) throw new AssertionError("gallery benchmark timed out at sequence " + last);
            if (after.latestCompletedFrameSequence() < last) LockSupport.parkNanos(100_000L);
        }
        if (sequence[0] != last + 1 || after.latestSubmittedFrameSequence() != last)
            throw new AssertionError("benchmark batch did not submit its complete sequence");
        if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
            available.lease().close();
        long elapsed = System.nanoTime() - started;
        var afterGpu = renderer.diagnostics().frameGpuTiming();
        var window = gpuWindow(beforeGpu, afterGpu, frames);
        return new Result(frames, elapsed, submit, requestBuild, poll, deferrals, window);
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
                    || i == 36 || i == 38;
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

    private record GpuWindow(long samples, BigInteger estimateNanos, BigInteger lowerNanos,
            BigInteger upperNanos, RendererDiagnostics.FrameGpuTiming after) { }
    private record Result(int frames, long wallNanos, long submitNanos, long requestBuildNanos,
            long pollNanos, long deferrals, GpuWindow gpu) { }
}
