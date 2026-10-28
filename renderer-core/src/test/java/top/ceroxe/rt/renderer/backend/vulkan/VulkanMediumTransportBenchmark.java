package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/**
 * Headless, public-API medium benchmark. Each measured batch starts and ends with no outstanding
 * submissions. Native completed batch throughput includes request construction, admission,
 * backpressure, polling, initial queue fill and final drain; it is not steady-state or display FPS.
 * GPUScene timestamps cover the frame command submission, not separately measured trace/post work.
 * The public lifetime integer means yield bounded estimates, not exact per-frame GPU samples.
 * No machine-dependent performance threshold is part of the ordinary correctness gate.
 */
public final class VulkanMediumTransportBenchmark {
    private enum Kind { OPAQUE, UNUSED_GLASS, OFFSCREEN, ABSORPTION, EMISSION, GLASS, NESTED,
        SCATTERING, HETEROGENEOUS_ABSORPTION, HETEROGENEOUS_SCATTERING, CAMERA, MANY }
    record Scene(String name, Kind kind, int lights, int steps, int opticalSamples, int boundaries,
            float density, int scatteringBounces) {
        int events() { return kind == Kind.MANY ? 32 : 12; }
        boolean heterogeneous() {
            return kind == Kind.HETEROGENEOUS_ABSORPTION || kind == Kind.HETEROGENEOUS_SCATTERING;
        }
    }
    static final List<Scene> SCENES = List.of(
            new Scene("opaque-clean", Kind.OPAQUE, 1, 8, 1, 0, 1, 0),
            new Scene("opaque-unused-glass", Kind.UNUSED_GLASS, 1, 8, 1, 0, 1, 0),
            new Scene("opaque-offscreen-boundary", Kind.OFFSCREEN, 1, 8, 1, 1, 1, 0),
            new Scene("absorption", Kind.ABSORPTION, 1, 8, 1, 1, 1, 0),
            new Scene("emission", Kind.EMISSION, 1, 8, 1, 1, 1, 0),
            new Scene("glass", Kind.GLASS, 1, 8, 1, 1, 1, 0),
            new Scene("nested-water-glass", Kind.NESTED, 1, 8, 1, 2, 1, 0),
            new Scene("scattering", Kind.SCATTERING, 1, 8, 1, 1, 1, 0),
            new Scene("scattering-steps1", Kind.SCATTERING, 1, 1, 1, 1, 1, 0),
            new Scene("scattering-steps32", Kind.SCATTERING, 1, 32, 1, 1, 1, 0),
            new Scene("scattering-lights8", Kind.SCATTERING, 8, 8, 1, 1, 1, 0),
            new Scene("scattering-dense", Kind.SCATTERING, 1, 8, 1, 1, 20, 0),
            new Scene("camera-scattering", Kind.CAMERA, 1, 8, 1, 0, 1, 0),
            new Scene("many-boundaries", Kind.MANY, 1, 8, 1, 8, 1, 0),
            new Scene("glass-samples4", Kind.GLASS, 1, 8, 4, 1, 1, 0),
            new Scene("heterogeneous-absorption", Kind.HETEROGENEOUS_ABSORPTION, 1, 8, 1, 1, 1, 0),
            new Scene("heterogeneous-scattering", Kind.HETEROGENEOUS_SCATTERING, 1, 8, 1, 1, 1, 0),
            new Scene("scattering-bounces1", Kind.SCATTERING, 1, 8, 1, 1, 1, 1),
            new Scene("scattering-bounces3", Kind.SCATTERING, 1, 8, 1, 1, 1, 3),
            new Scene("heterogeneous-scattering-bounces1", Kind.HETEROGENEOUS_SCATTERING, 1, 8, 1, 1, 1, 1),
            new Scene("heterogeneous-scattering-bounces3", Kind.HETEROGENEOUS_SCATTERING, 1, 8, 1, 1, 1, 3));
    private static final long DENSITY_TEXTURE_ID = 1;
    private static final int DENSITY_WIDTH = 32, DENSITY_HEIGHT = 32, DENSITY_SLICES = 16;
    private static long sequence;

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("Medium benchmark: --width=1920 --height=1080 --spp=1 --warmup=240 "
                    + "--frames=240 --rounds=3 --poll-nanos=0 --scenes=all --reverse=false --output=path.csv; --list");
            System.out.println("Optional density/scattering group: --scenes=heterogeneous-absorption,heterogeneous-scattering,"
                    + "scattering-bounces1,scattering-bounces3,heterogeneous-scattering-bounces1,heterogeneous-scattering-bounces3");
            System.out.println("--scenes=all includes every listed scene. Heterogeneous scenes use a 32x32x16 LINEAR RGBA8 "
                    + "red-channel density atlas in one closed volume; bounces1/bounces3 add 1/3 volume scattering events. "
                    + "Compare sampling budgets and density distributions alongside throughput.");
            return;
        }
        if (Arrays.asList(args).contains("--list")) {
            SCENES.forEach(scene -> System.out.println(scene.name));
            return;
        }
        Map<String, String> options = options(args);
        int width = positive(options, "width", 1920), height = positive(options, "height", 1080);
        int spp = positive(options, "spp", 1), warmup = positive(options, "warmup", 240);
        int frames = positive(options, "frames", 240), rounds = positive(options, "rounds", 3);
        long pollNanos = Long.parseLong(options.getOrDefault("poll-nanos", "0"));
        if (pollNanos < 0 || pollNanos > 1_000_000_000L)
            throw new IllegalArgumentException("poll-nanos must be within [0, 1000000000]");
        var antiAliasing = new AntiAliasingState(spp);
        String selection = options.getOrDefault("scenes", "all");
        var scenes = new ArrayList<Scene>();
        if (selection.equals("all")) scenes.addAll(SCENES);
        else for (String name : selection.split(",", -1)) {
            Scene selected = SCENES.stream().filter(scene -> scene.name.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown scene: " + name));
            if (scenes.contains(selected)) throw new IllegalArgumentException("duplicate scene: " + name);
            scenes.add(selected);
        }
        String reverse = options.getOrDefault("reverse", "false");
        if (!reverse.equals("true") && !reverse.equals("false")) throw new IllegalArgumentException("reverse must be true or false");
        if (Boolean.parseBoolean(reverse)) Collections.reverse(scenes);
        Path output = Path.of(options.getOrDefault("output",
                "build/reports/medium-performance/medium-" + System.currentTimeMillis() + ".csv")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var camera = CameraState.lookAt(0, 0, 6, 0, 0, -2).verticalFieldOfViewDegrees(35)
                .aspectRatio(width / (double) height).build();
        var config = RendererConfig.builder().maxFramesInFlight(4).validationEnabled(false)
                .gpuTimingsEnabled(true).cpuFrameReadbackEnabled(false)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .denoising(DenoisingOptions.disabled()).frameGeneration(FrameGenerationOptions.disabled())
                .lowLatency(LowLatencyOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        sequence = 0;
        System.out.printf(Locale.ROOT, "MediumBenchmark extent=%dx%d spp=%d warmup=%d frames=%d rounds=%d "
                + "slots=4 HDR=true validation=false readback=false temporal=false reconstruction=false denoising=false "
                + "frameGeneration=false lowLatency=false SER=false pollNanos=%d java=%s os=%s%n",
                width, height, spp, warmup, frames, rounds, pollNanos,
                System.getProperty("java.runtime.version"), System.getProperty("os.name"));
        System.out.println("MediumBenchmark method=drained-native-batches; throughput includes queue fill/drain and host work; "
                + "GPU interval=whole-frame-command-submission; no presentation, generated frames or per-frame latency percentiles are measured.");
        long openStarted = System.nanoTime();
        try (var renderer = Renderer.open(config);
                var csv = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            System.out.printf(Locale.ROOT, "MediumBenchmark rendererOpenMs=%.3f%n", (System.nanoTime() - openStarted) / 1e6);
            csv.println("scene,width,height,spp,lights,boundaries,scatteringSteps,opticalSamples,maxEvents,scatteringBounces,densityGridSlices,density,round,submittedNativeFrames,completedNativeFrames,wallMs,nativeCompletedBatchFps,gpuTimestampSamples,gpuSubmissionMeanEstimateMs,gpuSubmissionMeanLowerMs,gpuSubmissionMeanUpperMs,firstCompletionObservedMs,completionWaitAfterLastAdmissionMs,admissionDeferrals,leasesReleased,pollNanos,sceneApplyMs,coldFirstBatchMs");
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            long revision = 0;
            for (Scene scene : scenes) {
                long applyStarted = System.nanoTime();
                renderer.apply(transaction(scene, ++revision));
                double applyMs = (System.nanoTime() - applyStarted) / 1e6;
                var request = RenderFrameRequest.builder(0, width, height, camera).minimumSceneRevision(revision)
                        .clock(FrameClock.of(0, 0)).environment(environment(scene)).antiAliasing(antiAliasing)
                        .mediumTransport(new MediumTransport(scene.events(), scene.opticalSamples, scene.steps,
                                4, 512, 1e-4f, scene.scatteringBounces)).build();
                var first = range(renderer, interop, request, 1, pollNanos);
                double firstMs = first.elapsedNanos() / 1e6;
                range(renderer, interop, request, warmup, pollNanos);
                double[] gpuTimes = new double[rounds], completedRates = new double[rounds];
                var batches = new ArrayList<BatchMeasurement>(rounds);
                for (int round = 0; round < rounds; round++) {
                    var batch = range(renderer, interop, request, frames, pollNanos);
                    batches.add(batch);
                    var gpu = batch.gpu();
                    gpuTimes[round] = gpu.meanNanos() / 1e6; completedRates[round] = batch.completedBatchFps();
                    System.out.printf(Locale.ROOT, "MediumBenchmark scene=%s round=%d submitted=%d completed=%d "
                            + "wallMs=%.4f nativeCompletedBatchFps=%.2f gpuSubmissionMeanEstimateMs=%.6f "
                            + "gpuMeanBoundsMs=[%.6f,%.6f] completionWaitAfterLastAdmissionMs=%.4f admissionDeferrals=%d%n",
                            scene.name, round + 1, batch.frames(), batch.frames(), batch.elapsedNanos() / 1e6,
                            batch.completedBatchFps(), gpu.meanNanos() / 1e6, gpu.lowerMeanNanos() / 1e6,
                            gpu.upperMeanNanos() / 1e6, batch.completionWaitNanos() / 1e6, batch.admissionDeferrals());
                    csv.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%.6f,%.6f,%d,%.9f,%.9f,%.9f,%.6f,%.6f,%d,%d,%d,%.6f,%.6f%n",
                            scene.name, width, height, spp, scene.lights, scene.boundaries, scene.steps, scene.opticalSamples,
                            scene.events(), scene.scatteringBounces, scene.heterogeneous() ? DENSITY_SLICES : 0,
                            scene.density, round + 1, batch.frames(), batch.frames(), batch.elapsedNanos() / 1e6,
                            batch.completedBatchFps(), gpu.samples(), gpu.meanNanos() / 1e6, gpu.lowerMeanNanos() / 1e6,
                            gpu.upperMeanNanos() / 1e6, batch.firstCompletionNanos() / 1e6, batch.completionWaitNanos() / 1e6,
                            batch.admissionDeferrals(), batch.leasesReleased(), pollNanos, applyMs, firstMs);
                }
                Arrays.sort(gpuTimes); Arrays.sort(completedRates);
                System.out.printf(Locale.ROOT, "MediumBenchmark summary scene=%s nativeCompletedBatchFps=%.2f "
                        + "medianBatchNativeFps=%.2f medianBatchGpuMeanEstimateMs=%.6f batchGpuMeanEstimateMinMs=%.6f "
                        + "batchGpuMeanEstimateMaxMs=%.6f sceneApplyMs=%.3f coldFirstBatchMs=%.3f%n",
                        scene.name, aggregateCompletedFps(batches), median(completedRates), median(gpuTimes),
                        gpuTimes[0], gpuTimes[rounds - 1], applyMs, firstMs);
                csv.flush();
                if (csv.checkError()) throw new IllegalStateException("failed to write " + output);
            }
            var health = renderer.health();
            if (health.activeFailure().isPresent() || health.obligations().outstandingGpuFrameLeases() != 0)
                throw new AssertionError("unhealthy benchmark completion: " + health);
            System.out.println("MediumBenchmark health=" + health);
            System.out.println("MediumBenchmark CSV=" + output);
        }
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    static SceneTransaction transaction(Scene scene, long revision) {
        var transaction = SceneTransaction.builder(revision).resetScene()
                .upsert(MaterialAsset.builder(1).roughness(.65f).doubleSided(true).build())
                .upsert(MeshAsset.triangles(1, new float[]{-100,-100,-4,100,-100,-4,100,100,-4,-100,100,-4},
                        new int[]{0,1,2,2,3,0}, 1)).upsert(SceneInstance.builder(1, 1).build());
        boolean glass = scene.kind == Kind.GLASS || scene.kind == Kind.NESTED || scene.kind == Kind.UNUSED_GLASS;
        if (glass) transaction.upsert(MaterialAsset.builder(2).transmission(1).indexOfRefraction(1.5f)
                .roughness(.04f).doubleSided(true).build());
        if (scene.boundaries > 0 || scene.kind == Kind.UNUSED_GLASS) transaction.upsert(cube(2, glass ? 2 : 1));
        var medium = switch (scene.kind) {
            case GLASS, NESTED -> new Medium(Rgb.ZERO, Rgb.ZERO, Rgb.ZERO, 0, 1.5f, 0);
            case SCATTERING, HETEROGENEOUS_SCATTERING -> scattering(scene.density);
            case EMISSION -> new Medium(new Rgb(.2f,.1f,.03f), Rgb.ZERO, new Rgb(.04f,.02f,0), 1, 1, 0);
            default -> new Medium(new Rgb(.2f,.1f,.03f), Rgb.ZERO, Rgb.ZERO, 1, 1, 0);
        };
        if (scene.heterogeneous()) {
            transaction.upsert(densityAtlas());
            medium = medium.withDensityField(MediumDensityField.grid(DENSITY_TEXTURE_ID, DENSITY_SLICES,
                    new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f})));
        }
        if (scene.boundaries > 0) transaction.upsert(SceneInstance.builder(2, 2)
                .transform(transform(2, scene.kind == Kind.OFFSCREEN ? 1000 : 0))
                .mediumBoundary(new MediumBoundary(medium, 1, MediumBoundary.Orientation.OUTWARD, glass)).build());
        for (int boundary = 1; boundary < scene.boundaries; boundary++) {
            float radius = scene.kind == Kind.NESTED ? .9f : 2f - boundary * .2f;
            var inner = new Medium(new Rgb(.02f,.01f,.005f), Rgb.ZERO, Rgb.ZERO, 1, scene.kind == Kind.NESTED ? 1.333f : 1, 0);
            transaction.upsert(SceneInstance.builder(2 + boundary, 2).transform(transform(radius, 0))
                    .mediumBoundary(new MediumBoundary(inner, 1 + boundary, MediumBoundary.Orientation.OUTWARD, scene.kind == Kind.NESTED)).build());
        }
        for (int light = 0; light < scene.lights; light++) {
            double angle = 2 * Math.PI * light / scene.lights;
            transaction.upsert(SceneLight.point(1 + light, scene.lights == 1 ? 0 : Math.cos(angle) * 3,
                    scene.lights == 1 ? 3 : Math.sin(angle) * 3, 3).intensity(30f / scene.lights).range(20).build());
        }
        return transaction.build();
    }

    private static TextureAsset densityAtlas() {
        byte[] pixels = new byte[DENSITY_WIDTH * DENSITY_HEIGHT * DENSITY_SLICES * 4];
        for (int z = 0; z < DENSITY_SLICES; z++) {
            double nz = 2 * (z + .5) / DENSITY_SLICES - 1;
            for (int y = 0; y < DENSITY_HEIGHT; y++) {
                double ny = 2 * (y + .5) / DENSITY_HEIGHT - 1;
                for (int x = 0; x < DENSITY_WIDTH; x++) {
                    double nx = 2 * (x + .5) / DENSITY_WIDTH - 1;
                    int offset = ((z * DENSITY_HEIGHT + y) * DENSITY_WIDTH + x) * 4;
                    pixels[offset] = (byte) Math.round(255 * (.1 + .9 * Math.exp(-4 * (nx * nx + ny * ny + nz * nz))));
                    pixels[offset + 3] = (byte) 255;
                }
            }
        }
        return TextureAsset.builder(DENSITY_TEXTURE_ID, DENSITY_WIDTH, DENSITY_HEIGHT * DENSITY_SLICES)
                .colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();
    }

    private static EnvironmentState environment(Scene scene) {
        var environment = EnvironmentState.builder().skyRadiance(.1f,.12f,.15f).ambientIntensity(.2f)
                .sunDirection(0,0,-1).sunIntensity(1);
        if (scene.kind == Kind.CAMERA) environment.cameraMedium(scattering(1));
        return environment.build();
    }

    private static Medium scattering(float density) {
        return new Medium(new Rgb(.02f,.02f,.02f), new Rgb(.1f,.1f,.1f), Rgb.ZERO, density, 1, .2f);
    }

    private static BatchMeasurement range(Renderer renderer, VulkanFrameInterop interop,
            RenderFrameRequest template, int count, long pollNanos) throws InterruptedException {
        var before = renderer.diagnostics();
        require(before.latestSubmittedFrameSequence() == sequence - 1
                        && before.latestCompletedFrameSequence() == sequence - 1,
                "batch must start drained at sequence " + (sequence - 1) + ": " + before);
        long end = Math.addExact(sequence, count), last = end - 1;
        long started = System.nanoTime(), lastAdmission = started, firstCompletion = 0;
        long deadline = started + Duration.ofMinutes(5).toNanos();
        long deferrals = 0, leases = 0;
        var after = before;
        RenderFrameRequest pending = null;
        while (after.latestCompletedFrameSequence() < last) {
            if (Thread.interrupted()) throw new InterruptedException("medium benchmark interrupted");
            while (sequence < end) {
                if (pending == null) pending = request(sequence, template);
                if (!(renderer.trySubmit(pending) instanceof Renderer.FrameSubmitted)) { deferrals++; break; }
                sequence++; pending = null; lastAdmission = System.nanoTime();
            }
            if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available) {
                available.lease().close(); leases++;
            }
            after = renderer.diagnostics();
            long observedAt = System.nanoTime();
            require(after.status() == Renderer.Status.READY, "renderer left READY during benchmark: " + after);
            if (firstCompletion == 0 && after.latestCompletedFrameSequence() > before.latestCompletedFrameSequence())
                firstCompletion = observedAt - started;
            if (observedAt > deadline) throw new AssertionError("medium benchmark timed out: submitted="
                    + (sequence - 1) + ", completed=" + after.latestCompletedFrameSequence() + ", final=" + last);
            if (after.latestCompletedFrameSequence() == last) {
                long elapsed = observedAt - started;
                require(sequence == end && after.latestSubmittedFrameSequence() == last, "batch completion escaped admission range");
                require(before.deviceRecovery().equals(after.deviceRecovery()), "device recovery invalidates a benchmark window");
                require(after.frameGenerationEvidence().generatedFramesActuallyPresented() == 0,
                        "headless native benchmark must not include generated presentation frames");
                // Latest-frame leases can intentionally skip intermediate outputs. They are not the frame counter.
                if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available) {
                    available.lease().close(); leases++;
                }
                return new BatchMeasurement(count, elapsed, firstCompletion, observedAt - lastAdmission, deferrals, leases,
                        gpuWindow(before.frameGpuTiming(), after.frameGpuTiming(), count));
            }
            require(after.latestCompletedFrameSequence() < last, "completion sequence exceeded the benchmark batch");
            if (pollNanos == 0) Thread.onSpinWait(); else LockSupport.parkNanos(pollNanos);
        }
        throw new AssertionError("nonempty batch completed without entering its measurement loop");
    }

    record BatchMeasurement(int frames, long elapsedNanos, long firstCompletionNanos, long completionWaitNanos,
            long admissionDeferrals, long leasesReleased, GpuTimingWindow gpu) {
        BatchMeasurement {
            if (frames < 1 || elapsedNanos <= 0 || firstCompletionNanos <= 0 || firstCompletionNanos > elapsedNanos
                    || completionWaitNanos < 0 || completionWaitNanos > elapsedNanos || admissionDeferrals < 0
                    || leasesReleased < 0 || gpu.samples() != frames)
                throw new IllegalArgumentException("inconsistent native batch measurement");
        }
        double completedBatchFps() { return frames * 1e9 / elapsedNanos; }
    }

    /** Exact bounds on the unknown timestamp sum implied by truncated lifetime means. */
    record GpuTimingWindow(long samples, BigInteger estimateNanos, BigInteger lowerNanos, BigInteger upperNanos) {
        double meanNanos() { return estimateNanos.doubleValue() / samples; }
        double lowerMeanNanos() { return lowerNanos.doubleValue() / samples; }
        double upperMeanNanos() { return upperNanos.doubleValue() / samples; }
    }

    static GpuTimingWindow gpuWindow(RendererDiagnostics.FrameGpuTiming before,
            RendererDiagnostics.FrameGpuTiming after, long expectedSamples) {
        require(expectedSamples > 0 && before.enabled() && after.enabled()
                        && after.completedSamples() - before.completedSamples() == expectedSamples
                        && after.failedSamples() == before.failedSamples() && after.droppedSamples() == before.droppedSamples(),
                "incomplete hardware timestamp window: " + before + " -> " + after);
        var beforeCount = BigInteger.valueOf(before.completedSamples());
        var afterCount = BigInteger.valueOf(after.completedSamples());
        var estimate = BigInteger.valueOf(after.averageTotalNanos()).multiply(afterCount)
                .subtract(BigInteger.valueOf(before.averageTotalNanos()).multiply(beforeCount));
        var lower = estimate.subtract(beforeCount.subtract(BigInteger.ONE).max(BigInteger.ZERO)).max(BigInteger.ZERO);
        var upper = estimate.add(afterCount.subtract(BigInteger.ONE));
        require(estimate.signum() > 0 && upper.compareTo(lower) >= 0,
                "timestamp precision/counter continuity is insufficient for this batch");
        return new GpuTimingWindow(expectedSamples, estimate, lower, upper);
    }

    static double aggregateCompletedFps(List<BatchMeasurement> batches) {
        long frames = 0, nanos = 0;
        for (var batch : batches) {
            frames = Math.addExact(frames, batch.frames()); nanos = Math.addExact(nanos, batch.elapsedNanos());
        }
        if (frames == 0) throw new IllegalArgumentException("at least one completed batch is required");
        return frames * 1e9 / nanos;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static RenderFrameRequest request(long sequence, RenderFrameRequest template) {
        var builder = RenderFrameRequest.builder(sequence, template.width(), template.height(), template.camera())
                .minimumSceneRevision(template.minimumSceneRevision()).clock(template.clock())
                .environment(template.environment()).mediumTransport(template.mediumTransport()).caustics(template.caustics())
                .lightmap(template.lightmap()).fog(template.fog()).textureSampling(template.textureSampling())
                .antiAliasing(template.antiAliasing()).depthProjection(template.depthProjection())
                .primitiveBatch(template.primitiveBatch()).shaderData(template.shaderData());
        for (var reset : template.temporalHistoryResets()) builder.resetTemporalHistory(reset);
        return builder.build();
    }

    private static Map<String, String> options(String[] args) {
        var options = new LinkedHashMap<String, String>();
        var supported = List.of("width", "height", "spp", "warmup", "frames", "rounds", "poll-nanos", "scenes", "reverse", "output");
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 3) throw new IllegalArgumentException("expected --name=value: " + arg);
            String name = arg.substring(2, equals), value = arg.substring(equals + 1);
            if (!supported.contains(name) || value.isEmpty() || options.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("unknown, empty or duplicate option: " + arg);
        }
        return options;
    }

    private static int positive(Map<String, String> options, String name, int fallback) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static AffineTransform transform(float scale, float x) {
        return new AffineTransform(new float[]{scale,0,0,x,0,scale,0,0,0,0,scale,0});
    }

    private static MeshAsset cube(long id, long material) {
        return MeshAsset.triangles(id, new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},
                new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5}, material);
    }
}
