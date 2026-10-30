package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.api.*;

/** Public provider -> retained scene -> final linear HDR, with an independent analytic reference. */
public final class CudaCameraMediumProductionNativeSelfTest {
    private static final Medium ABSORPTION = new Medium(new Rgb(.07f, .13f, .02f),
            Rgb.ZERO, Rgb.ZERO, 1.6f, 1, 0);
    private static final float HALF_PLANE = 1.4f;
    private static final double RECEIVER_DISTANCE = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final List<Comparison> COMPARISONS = new ArrayList<>();
    private static final Path OUTPUT = Path.of(System.getProperty("top.ceroxe.rt.medium.productionImageDirectory",
            "build/luxcore-production-native-20261028/images"));
    private static long sequence, revision;
    private static int accepted, nativeAccepted, rejected;
    private static Renderer renderer;

    private CudaCameraMediumProductionNativeSelfTest() { }

    public static void main(String[] arguments) throws Exception {
        if (List.of(arguments).contains("--benchmark")) {
            benchmark(List.of(arguments).contains("--control"));
            return;
        }
        String oldNative = System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        String oldQueue = System.getProperty("top.ceroxe.rt.medium.primaryQueue");
        Files.createDirectories(OUTPUT);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, "true");
        System.setProperty("top.ceroxe.rt.medium.primaryQueue", "false");
        long nativeBefore = VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum();
        long started = System.nanoTime();
        boolean pendingClose = false;
        try {
            var config = RendererConfig.builder().maxFramesInFlight(4).validationEnabled(true)
                    .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build();
            try (Renderer owned = Renderer.open("vulkan-rt", config)) {
                renderer = owned;
                initializeScene();

                comparePair("odd-hit-miss", 67, 49, 12);
                comparePair("multitile-resize", 319, 233, 12);
                comparePair("bounded-medium-distant-receiver", 67, 49, 4);
                FrameSnapshot vacuum = capture("resize-small-vacuum", request(37, 29, Medium.vacuum(), 12, 1));
                FrameSnapshot nativeFrame = capture("resize-small-native", request(37, 29, ABSORPTION, 12, 1));
                compare("resize-small", vacuum, nativeFrame, 12);

                compareMultisampled(2, 67, 49);
                compareMultisampled(8, 137, 101);
                var exactCamera = exactCamera(71, 53);
                var exactVacuum = capture("exact-jitter-vacuum", request(71, 53, Medium.vacuum(), 12, 1, exactCamera));
                var exactNative = capture("exact-jitter-native", request(71, 53, ABSORPTION, 12, 1, exactCamera));
                compare("exact-jitter-primary", exactVacuum, exactNative, 12, exactCamera, 1, true);

                apply(SceneTransaction.builder(++revision).removeInstance(1).build());
                var local = FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).build()).build();
                var localVacuum = capture("frame-local-vacuum", request(67, 49, Medium.vacuum(), 12, 1)
                        .toBuilder().primitiveBatch(local).build());
                var localNative = capture("frame-local-native", request(67, 49, ABSORPTION, 12, 1)
                        .toBuilder().primitiveBatch(local).build());
                compare("frame-local-ephemeral-TLAS", localVacuum, localNative, 12);
                apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(1, 1).build()).build());

                var refractiveAbsorber = new Medium(ABSORPTION.absorption(), Rgb.ZERO, Rgb.ZERO,
                        ABSORPTION.density(), 1.2f, 0);
                compare("IOR-without-interface", vacuum,
                        capture("IOR-without-interface", request(37, 29, refractiveAbsorber, 12, 1)), 12);
                apply(SceneTransaction.builder(++revision).upsert(MediumTransportSelfTest.cube(2, 1, 1))
                        .upsert(SceneInstance.builder(2, 2).transform(new AffineTransform(new float[]{
                                1,0,0,20, 0,1,0,0, 0,0,1,0}))
                                .mediumBoundary(MediumBoundary.volume(new Medium(ABSORPTION.absorption(),
                                        new Rgb(.1f, .1f, .1f), Rgb.ZERO, 1, 1.2f, 0))).build()).build());
                expectUnsupported(request(37, 29, ABSORPTION, 12, 1), "IOR-changing-invisible-boundary");
                apply(SceneTransaction.builder(++revision).removeInstance(2).build());
                apply(SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1).doubleSided(true)
                        .surfaceOverlay(SurfaceOverlayState.depthEqual(.001f)).build()).build());
                expectUnsupported(request(37, 29, ABSORPTION, 12, 1), "surface-overlay-continuation");
                apply(SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1)
                        .shadingModel(MaterialAsset.ShadingModel.UNLIT).doubleSided(true)
                        .emissive(0xff4080ff, .25f).build()).build());
                var afterReject = capture("after-rejection", request(37, 29, ABSORPTION, 12, 1));
                compare("rejection-recovery", vacuum, afterReject, 12);

                // Cancelling a queued CPU waiter does not cancel GPU production or change slot ownership.
                AtomicReference<Runnable> waiter = new AtomicReference<>();
                var wait = renderer.awaitFrameAsync(TIMEOUT, waiter::set);
                require(wait.cancel(false), "queued public frame waiter must be cancellable");
                waiter.get().run();
                require(wait.isCancelled(), "cancelled public waiter changed state");
                var afterCancel = capture("after-wait-cancellation", request(37, 29, ABSORPTION, 12, 1));
                compare("wait-cancellation-recovery", vacuum, afterCancel, 12);

                var otherThreadFrame = request(37, 29, ABSORPTION, 12, 1);
                AtomicReference<FrameSnapshot> otherThreadResult = new AtomicReference<>();
                runOnOtherThread(() -> otherThreadResult.set(capture("other-submission-thread", otherThreadFrame)));
                compare("other-submission-thread", vacuum, otherThreadResult.get(), 12);

                // Polling returns the latest frame. Completing the third submission drains prior queue work.
                RenderFrameRequest last = null;
                for (int index = 0; index < 3; index++) {
                    last = request(37, 29, ABSORPTION.withDensity(1.6f), 12, 1);
                    submit(last);
                }
                var drained = await(last.sequence());
                save("three-inflight-final", drained);
                compare("three-inflight-drained", vacuum, drained, 12);
                require(renderer.health().activeFailure().isEmpty(), "renderer has an active failure");

                // Leave a completed-or-pending consumer owned by the renderer; close must drain it.
                submit(request(41, 31, ABSORPTION, 12, 1));
                pendingClose = true;
                runOnOtherThread(renderer::close);
            }
            require(renderer.status() == Renderer.Status.CLOSED, "public close did not reach CLOSED");
            renderer.close();
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum() - nativeBefore == nativeAccepted,
                    "a supported medium frame bypassed the real native production route");
            writeSummary((System.nanoTime() - started) / 1e9, pendingClose);
            System.out.printf(Locale.ROOT,
                    "CudaCameraMediumProductionNativeSelfTest passed: comparisons=%d accepted=%d rejected=%d publicProvider=vulkan-rt pendingClose=%s; correctness only, not performance evidence%n",
                    COMPARISONS.size(), accepted, rejected, pendingClose);
        } finally {
            restore(CudaCameraMediumProfile.ENABLE_PROPERTY, oldNative);
            restore("top.ceroxe.rt.medium.primaryQueue", oldQueue);
        }
    }

    private static void initializeScene() throws Exception {
        apply(SceneTransaction.builder(++revision).resetScene()
                .upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT)
                        .doubleSided(true).emissive(0xff4080ff, .25f).build())
                .upsert(MeshAsset.triangles(1, new float[]{-HALF_PLANE, -HALF_PLANE, -4,
                        HALF_PLANE, -HALF_PLANE, -4, HALF_PLANE, HALF_PLANE, -4,
                        -HALF_PLANE, HALF_PLANE, -4}, new int[]{0, 1, 2, 2, 3, 0}, 1))
                .upsert(SceneInstance.builder(1, 1).build()).build());
    }

    private static void runOnOtherThread(CheckedAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { action.run(); } catch (Throwable thrown) { failure.set(thrown); }
        }, "native-production-public-caller");
        worker.start(); worker.join(TIMEOUT.toMillis());
        require(!worker.isAlive(), "public caller thread did not finish");
        if (failure.get() != null) throw new AssertionError("public caller thread failed", failure.get());
    }

    /** Independent old-core classpaths use the same compiled test and public API with --control. */
    private static void benchmark(boolean control) throws Exception {
        int width = Integer.getInteger("top.ceroxe.rt.medium.productionBenchmarkWidth", 640);
        int height = Integer.getInteger("top.ceroxe.rt.medium.productionBenchmarkHeight", 360);
        int warmup = Integer.getInteger("top.ceroxe.rt.medium.productionBenchmarkWarmup", 6);
        int samples = Integer.getInteger("top.ceroxe.rt.medium.productionBenchmarkSamples", 24);
        require(width > 0 && height > 0 && warmup >= 1 && samples >= 8, "invalid benchmark extent/count");
        String oldNative = System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        String oldQueue = System.getProperty("top.ceroxe.rt.medium.primaryQueue");
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, Boolean.toString(!control));
        System.setProperty("top.ceroxe.rt.medium.primaryQueue", "false");
        Files.createDirectories(OUTPUT);
        var config = RendererConfig.builder().maxFramesInFlight(4).validationEnabled(false)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).gpuTimingsEnabled(true)
                .temporalRendering(TemporalRenderingOptions.disabled()).build();
        try (Renderer owned = Renderer.open("vulkan-rt", config)) {
            renderer = owned;
            initializeScene();
            var vacuum = capture("benchmark-vacuum", request(width, height, Medium.vacuum(), 12, 1));
            long coldStarted = System.nanoTime();
            var cold = capture("benchmark-first-medium", request(width, height, ABSORPTION, 12, 1));
            long coldNanos = System.nanoTime() - coldStarted;
            compare(control ? "control-benchmark-reference" : "native-benchmark-reference", vacuum, cold, 12);
            for (int index = 0; index < warmup; index++) {
                var frame = request(width, height, ABSORPTION, 12, 1);
                submit(frame); await(frame.sequence());
            }
            var gpuBefore = renderer.diagnostics().frameGpuTiming();
            long[] nanos = new long[samples];
            long batchStarted = System.nanoTime();
            for (int index = 0; index < samples; index++) {
                long started = System.nanoTime();
                var frame = request(width, height, ABSORPTION, 12, 1);
                submit(frame); await(frame.sequence());
                nanos[index] = System.nanoTime() - started;
            }
            long batchNanos = System.nanoTime() - batchStarted;
            var gpuAfter = renderer.diagnostics().frameGpuTiming();
            var gpu = VulkanMediumTransportBenchmark.gpuWindow(gpuBefore, gpuAfter, samples);
            require(renderer.health().activeFailure().isEmpty(), "benchmark renderer unhealthy");
            long[] sorted = nanos.clone(); java.util.Arrays.sort(sorted);
            String label = control ? "control" : "native";
            StringBuilder json = new StringBuilder();
            json.append(String.format(Locale.ROOT,
                    "{\n  \"mode\":\"%s\",\n  \"width\":%d,\n  \"height\":%d,\n  \"samplesPerPixel\":1,\n  \"completedFrames\":%d,\n  \"warmupFrames\":%d,\n  \"coldFirstMediumMs\":%.6f,\n  \"batchWallMs\":%.6f,\n  \"completedFramesPerSecond\":%.6f,\n  \"medianEndToEndMs\":%.6f,\n  \"p95EndToEndMs\":%.6f,\n  \"gpuTimestampSamples\":%d,\n  \"gpuFinalSubmissionMeanMs\":%.9f,\n  \"gpuTimestampScope\":\"final Vulkan frame submission; native blocking prepass and CUDA are excluded from this GPU timestamp and included in end-to-end wall\",\n  \"frameNanos\":[",
                    label, width, height, samples, warmup, coldNanos / 1e6, batchNanos / 1e6,
                    samples * 1e9 / batchNanos, sorted[samples / 2] / 1e6,
                    sorted[Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1)] / 1e6,
                    gpu.samples(), gpu.meanNanos() / 1e6));
            for (int index = 0; index < nanos.length; index++) {
                if (index > 0) json.append(','); json.append(nanos[index]);
            }
            json.append("]\n}\n");
            Files.writeString(OUTPUT.resolveSibling(label + "-benchmark.json"), json);
            System.out.printf(Locale.ROOT,
                    "Production benchmark mode=%s completed=%d extent=%dx%d medianEndToEndMs=%.6f batchWallMs=%.6f completedFramesPerSecond=%.6f finalVulkanSubmissionMeanMs=%.9f%n",
                    label, samples, width, height, sorted[samples / 2] / 1e6, batchNanos / 1e6,
                    samples * 1e9 / batchNanos, gpu.meanNanos() / 1e6);
        } finally {
            restore(CudaCameraMediumProfile.ENABLE_PROPERTY, oldNative);
            restore("top.ceroxe.rt.medium.primaryQueue", oldQueue);
        }
    }

    private static void comparePair(String name, int width, int height, float distance) throws Exception {
        var vacuum = capture(name + "-vacuum", request(width, height, Medium.vacuum(), distance, 1));
        var candidate = capture(name + "-native", request(width, height, ABSORPTION, distance, 1));
        compare(name, vacuum, candidate, distance);
    }

    private static void compareMultisampled(int samples, int width, int height) throws Exception {
        // Every sample hits the same constant terminal material. The reference averages the
        // individual sample transmittances, rather than attenuating one representative hit.
        var camera = CameraState.lookAt(0, 0, 6, 0, 0, -4).verticalFieldOfViewDegrees(5)
                .aspectRatio((double) width / height).build();
        var vacuum = capture("spp" + samples + "-vacuum", request(width, height, Medium.vacuum(), 12, samples, camera));
        var candidate = capture("spp" + samples + "-native", request(width, height, ABSORPTION, 12, samples, camera));
        compare("sample-resolved-spp" + samples, vacuum, candidate, 12, camera, samples, false);
    }

    private static CameraState exactCamera(int width, int height) {
        double f = 1 / Math.tan(Math.toRadians(35) * .5), near = .1, far = 100;
        return CameraState.exactProjection(ExactProjectionState.builder(width, height)
                .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                .cameraToWorld(new double[]{1,0,0,0, 0,1,0,0, 0,0,1,6, 0,0,0,1})
                .clipFromView(new double[]{f * height / width,0,.13,0, 0,f,-.07,0,
                        0,0,far / (near - far),far * near / (near - far), 0,0,-1,0})
                .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
                .jitter(ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET, .25, -.125).build());
    }

    private static RenderFrameRequest request(int width, int height, Medium medium, float distance, int samples) {
        var camera = CameraState.lookAt(0, 0, 6, 0, 0, -4).verticalFieldOfViewDegrees(35)
                .aspectRatio((double) width / height).build();
        return request(width, height, medium, distance, samples, camera);
    }

    private static RenderFrameRequest request(int width, int height, Medium medium, float distance, int samples, CameraState camera) {
        return RenderFrameRequest.builder(++sequence, width, height, camera).minimumSceneRevision(revision)
                .clock(FrameClock.of(0, 0)).environment(EnvironmentState.builder().skyRadiance(.2f, .4f, .6f)
                        .cameraMedium(medium).build())
                .antiAliasing(new AntiAliasingState(samples))
                .mediumTransport(MediumTransport.balanced().withMaxDistance(distance)).build();
    }

    private static void apply(SceneTransaction transaction) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            try { renderer.apply(transaction); return; }
            catch (SubmissionRejectedException unavailable) {
                if (System.nanoTime() >= deadline) throw unavailable;
                Thread.sleep(1);
            }
        }
    }

    private static void submit(RenderFrameRequest frame) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("frame admission timeout: " + frame.sequence());
            Thread.sleep(1);
        }
        accepted++;
        if (!frame.environment().cameraMedium().isVacuum()) nativeAccepted++;
    }

    private static FrameSnapshot capture(String name, RenderFrameRequest frame) throws Exception {
        submit(frame);
        var completed = await(frame.sequence());
        save(name, completed);
        return completed;
    }

    private static FrameSnapshot await(long expectedSequence) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var ready = renderer.pollFrame();
            if (ready.isPresent()) {
                var frame = ready.orElseThrow();
                require(frame.frameSequence() <= expectedSequence, "published future frame");
                if (frame.frameSequence() == expectedSequence) {
                    require(frame.renderedSceneRevision() == revision, "rendered stale scene revision");
                    return frame;
                }
            }
            Thread.sleep(1);
        }
        throw new AssertionError("final HDR readback timeout: " + expectedSequence);
    }

    private static void expectUnsupported(RenderFrameRequest request, String name) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            try {
                if (renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)
                    throw new AssertionError("unsupported " + name + " was silently admitted");
            } catch (FrameValidationException expected) {
                require(renderer.status() == Renderer.Status.READY, "rejection poisoned renderer: " + name);
                rejected++;
                System.out.println("Native production explicitly rejected=" + name + " reason=" + expected.reason());
                return;
            }
            if (System.nanoTime() >= deadline) throw new AssertionError("unsupported-frame validation timeout: " + name);
            Thread.sleep(1);
        }
    }

    private static void compare(String name, FrameSnapshot vacuum, FrameSnapshot candidate, float maxDistance) {
        var camera = CameraState.lookAt(0, 0, 6, 0, 0, -4).verticalFieldOfViewDegrees(35)
                .aspectRatio((double) candidate.width() / candidate.height()).build();
        compare(name, vacuum, candidate, maxDistance, camera, 1, true);
    }

    private static void compare(String name, FrameSnapshot vacuum, FrameSnapshot candidate, float maxDistance,
                                CameraState camera, int samples, boolean requireHitAndMiss) {
        require(vacuum.width() == candidate.width() && vacuum.height() == candidate.height(), "extent mismatch");
        require(candidate.format() == TextureFormat.RGBA16_FLOAT, "final output is not linear RGBA16F");
        ByteBuffer a = vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = candidate.pixels().order(ByteOrder.LITTLE_ENDIAN);
        int width = candidate.width(), height = candidate.height();
        double[] extinction = {ABSORPTION.absorption().red() * ABSORPTION.density(),
                ABSORPTION.absorption().green() * ABSORPTION.density(),
                ABSORPTION.absorption().blue() * ABSORPTION.density()};
        int hits = 0, misses = 0;
        double maximum = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double[] averageTransmittance = new double[3];
            for (int sample = 0; sample < samples; sample++) {
                double[] offset = sampleOffset(sample, samples);
                double directionX, directionY, directionZ;
                if (camera.hasExactProjection()) {
                    var ray = camera.exactProjection().rayForPixel(x + offset[0] - .5, y + offset[1] - .5);
                    directionX = ray.directionX(); directionY = ray.directionY(); directionZ = ray.directionZ();
                } else {
                    double dx = ((x + offset[0]) / width * 2 - 1) * camera.tanHalfFovX();
                    double dy = -((y + offset[1]) / height * 2 - 1) * camera.tanHalfFovY();
                    double length = Math.sqrt(1 + dx * dx + dy * dy);
                    directionX = dx / length; directionY = dy / length; directionZ = -1 / length;
                }
                double hitDistance = RECEIVER_DISTANCE / -directionZ;
                boolean hit = Math.abs(directionX * hitDistance) <= HALF_PLANE
                        && Math.abs(directionY * hitDistance) <= HALF_PLANE;
                if (hit) hits++; else misses++;
                if (samples > 1) require(hit, "multisample reference requires constant full-screen terminal radiance");
                double distance = hit ? Math.min(hitDistance, maxDistance) : maxDistance;
                for (int channel = 0; channel < 3; channel++)
                    averageTransmittance[channel] += Math.exp(-extinction[channel] * distance) / samples;
            }
            int offset = (y * width + x) * 8;
            for (int channel = 0; channel < 3; channel++) {
                float base = Float.float16ToFloat(a.getShort(offset + channel * 2));
                float actual = Float.float16ToFloat(b.getShort(offset + channel * 2));
                double expected = base * averageTransmittance[channel];
                require(Float.isFinite(actual), name + " produced nonfinite HDR");
                double error = Math.abs(actual - expected);
                maximum = Math.max(maximum, error);
                // Both completed images are half-float; allow their two independent quantizations.
                double tolerance = .0015 * Math.max(1, Math.abs(expected));
                require(error <= tolerance, name + " pixel=" + x + "," + y + " channel=" + channel
                        + " actual=" + actual + " expected=" + expected + " error=" + error);
            }
            require(Float.float16ToFloat(b.getShort(offset + 6)) == 1, "native path corrupted final alpha");
        }
        require(hits > 0 && (!requireHitAndMiss || misses > 0), "scene did not exercise the required true hit/miss work");
        var comparison = new Comparison(name, width, height, hits, misses, maximum);
        COMPARISONS.add(comparison);
        System.out.printf(Locale.ROOT, "Native public HDR comparison=%s extent=%dx%d hits=%d misses=%d maxAbsolute=%.9g%n",
                name, width, height, hits, misses, maximum);
    }

    private static double[] sampleOffset(int sample, int samples) {
        if (samples == 1) return new double[]{.5, .5};
        if (samples == 2) return sample == 0 ? new double[]{.25, .25} : new double[]{.75, .75};
        double[][] offsets = {{.5,.3333}, {.25,.6667}, {.75,.1111}, {.125,.4444},
                {.625,.7778}, {.375,.2222}, {.875,.5556}, {.0625,.8889}};
        return offsets[sample];
    }

    private static void save(String name, FrameSnapshot frame) throws Exception {
        byte[] bytes = new byte[frame.byteCount()];
        frame.pixels().get(bytes);
        Files.write(OUTPUT.resolve(name + ".rgba16f"), bytes);
    }

    private static void writeSummary(double wallSeconds, boolean pendingClose) throws Exception {
        StringBuilder json = new StringBuilder("{\n  \"passed\": true,\n  \"scope\": \"public-vulkan-rt-final-HDR-homogeneous-camera-absorption-unlit\",\n");
        json.append("  \"acceptedFrames\": ").append(accepted).append(",\n  \"explicitRejections\": ").append(rejected)
                .append(",\n  \"actualNativeRouteFrames\": ").append(nativeAccepted)
                .append(",\n  \"queuedWaiterCancellation\": true,\n  \"otherSubmissionAndCloseThread\": true,\n  \"closeWithUnconsumedFrame\": ").append(pendingClose)
                .append(",\n  \"performanceClaim\": false,\n  \"wallSecondsIncludingColdWork\": ")
                .append(String.format(Locale.ROOT, "%.6f", wallSeconds)).append(",\n  \"comparisons\": [\n");
        for (int index = 0; index < COMPARISONS.size(); index++) {
            var c = COMPARISONS.get(index);
            if (index != 0) json.append(",\n");
            json.append(String.format(Locale.ROOT,
                    "    {\"name\":\"%s\",\"width\":%d,\"height\":%d,\"hits\":%d,\"misses\":%d,\"maxAbsoluteError\":%.12g}",
                    c.name(), c.width(), c.height(), c.hits(), c.misses(), c.maximum()));
        }
        json.append("\n  ]\n}\n");
        Files.writeString(OUTPUT.resolveSibling("result.json"), json);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private record Comparison(String name, int width, int height, int hits, int misses, double maximum) { }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
