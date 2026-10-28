package top.ceroxe.rt.renderer.backend.vulkan;

import java.math.BigInteger;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Deterministic measurement math and request coverage; opens no device and submits no GPU work. */
public final class VulkanMediumBenchmarkStatisticsSelfTest {
    public static void main(String[] args) {
        var before = timing(3,10);
        var after = timing(5,13);
        var window = VulkanMediumTransportBenchmark.gpuWindow(before,after,2);
        require(window.estimateNanos().equals(BigInteger.valueOf(35)),"integer mean estimate");
        require(window.lowerNanos().equals(BigInteger.valueOf(33))
                && window.upperNanos().equals(BigInteger.valueOf(39)),"truncation uncertainty must survive subtraction");
        // Independently enumerate every exact cumulative sum consistent with the public means.
        for(long previous=30;previous<=32;previous++) for(long current=65;current<=69;current++) {
            var exact = BigInteger.valueOf(current-previous);
            require(exact.compareTo(window.lowerNanos())>=0 && exact.compareTo(window.upperNanos())<=0,
                    "window bounds must enclose every possible exact sample sum");
        }
        var empty = RendererDiagnostics.FrameGpuTiming.builder().enabled(true).build();
        var first = VulkanMediumTransportBenchmark.gpuWindow(empty,timing(2,7),2);
        require(first.lowerNanos().equals(BigInteger.valueOf(14))
                && first.upperNanos().equals(BigInteger.valueOf(15)),"empty baseline has no rounding residue");
        long beyondDoubleInteger = 9_007_199_254_740_993L;
        var large = VulkanMediumTransportBenchmark.gpuWindow(timing(beyondDoubleInteger,100),timing(beyondDoubleInteger+2,100),2);
        require(large.estimateNanos().equals(BigInteger.valueOf(200)),"large lifetime products must not cancel in double precision");
        expect(AssertionError.class,()->VulkanMediumTransportBenchmark.gpuWindow(before,after,3));
        expect(AssertionError.class,()->VulkanMediumTransportBenchmark.gpuWindow(after,before,2));
        expect(AssertionError.class,()->VulkanMediumTransportBenchmark.gpuWindow(empty,RendererDiagnostics.FrameGpuTiming.unavailable(),1));
        expect(AssertionError.class,()->VulkanMediumTransportBenchmark.gpuWindow(before,after.toBuilder().droppedSamples(1).build(),2));
        expect(AssertionError.class,()->VulkanMediumTransportBenchmark.gpuWindow(before,after.toBuilder().failedSamples(1).build(),2));

        var gpu = new VulkanMediumTransportBenchmark.GpuTimingWindow(100,BigInteger.valueOf(1_000),
                BigInteger.valueOf(1_000),BigInteger.valueOf(1_000));
        var fast = new VulkanMediumTransportBenchmark.BatchMeasurement(100,1_000_000_000L,10,20,0,1,gpu);
        var slow = new VulkanMediumTransportBenchmark.BatchMeasurement(100,3_000_000_000L,10,20,0,1,gpu);
        require(fast.completedBatchFps()==100,"native batch throughput counts completed frames over the whole interval");
        require(VulkanMediumTransportBenchmark.aggregateCompletedFps(List.of(fast,slow))==50,
                "combined throughput must be total frames / total wall time, not the arithmetic mean of FPS");
        expect(IllegalArgumentException.class,()->VulkanMediumTransportBenchmark.aggregateCompletedFps(List.of()));
        expect(IllegalArgumentException.class,()->new VulkanMediumTransportBenchmark.BatchMeasurement(100,0,10,20,0,1,gpu));
        expect(IllegalArgumentException.class,()->new VulkanMediumTransportBenchmark.BatchMeasurement(99,100,10,20,0,1,gpu));
        verifyScenarioWorkloads();
        verifyRequestCopy();
        System.out.println("VulkanMediumBenchmarkStatisticsSelfTest passed: rounding bounds, large counters, complete samples, weighted throughput, density/scattering scenes, full request copying");
    }

    private static void verifyScenarioWorkloads() {
        require(VulkanMediumTransportBenchmark.SCENES.stream().map(VulkanMediumTransportBenchmark.Scene::name)
                        .distinct().count() == VulkanMediumTransportBenchmark.SCENES.size(),
                "all benchmark scenes must have unique selectable names");
        for (String name : List.of("heterogeneous-absorption", "heterogeneous-scattering", "scattering-bounces1",
                "scattering-bounces3", "heterogeneous-scattering-bounces1", "heterogeneous-scattering-bounces3")) {
            var scene = VulkanMediumTransportBenchmark.SCENES.stream().filter(candidate -> candidate.name().equals(name))
                    .findFirst().orElseThrow();
            int expectedBounces = name.endsWith("bounces3") ? 3 : name.endsWith("bounces1") ? 1 : 0;
            require(scene.scatteringBounces() == expectedBounces && scene.boundaries() == 1
                            && scene.steps() == 8 && scene.opticalSamples() == 1 && scene.lights() == 1,
                    "representative density/scattering scenes must isolate the advertised workload: " + name);
            var transaction = VulkanMediumTransportBenchmark.transaction(scene, 1);
            var instance = transaction.upserts().instances().stream().filter(value -> value.id() == 2).findFirst().orElseThrow();
            var boundary = instance.mediumBoundary().orElseThrow();
            var medium = boundary.medium();
            require(medium.participating() && !boundary.surfaceInteraction(),
                    "representative density/scattering scenes require a participating closed volume: " + name);
            if (scene.heterogeneous()) {
                require(transaction.upserts().textures().size() == 1, "each density scene must upload its atlas");
                var atlas = transaction.upserts().textures().get(0);
                var field = medium.densityField();
                require(!field.isUniform() && field.textureAssetId() == atlas.id() && field.slices() == 16
                                && atlas.colorSpace() == TextureAsset.ColorSpace.LINEAR
                                && atlas.width() == 32 && atlas.height() == 32 * field.slices()
                                && atlas.rgba8().remaining() == 32 * 32 * 16 * 4,
                        "density scenes must use the declared linear RGBA8 vertical slice atlas");
                for (int row = 0; row < 3; row++) {
                    var transform = field.transform();
                    float scale = transform.element(row * 4 + row), offset = transform.element(row * 4 + 3);
                    require(-scale + offset == 0 && scale + offset == 1,
                            "density coordinates must map the local box bounds [-1,1] into [0,1]");
                }
                int center = density(atlas, 16, 16, 8);
                require(center > density(atlas, 0, 16, 8) && center > density(atlas, 16, 0, 8)
                                && center > density(atlas, 16, 16, 0),
                        "density data must vary independently along all three grid axes");
                require(name.equals("heterogeneous-absorption") == medium.scattering().isZero(),
                        "absorption and scattering scene names must select the advertised coefficients");
            } else {
                require(medium.densityField().isUniform() && transaction.upserts().textures().isEmpty(),
                        "homogeneous bounce scenes must not allocate or sample density textures");
            }
        }
    }

    private static int density(TextureAsset atlas, int x, int y, int z) {
        return atlas.rgba8().get(((z * 32 + y) * atlas.width() + x) * 4) & 0xff;
    }

    private static void verifyRequestCopy() {
        var field = MediumDensityField.grid(12, 16, new AffineTransform(
                new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
        var template = RenderFrameRequest.builder(7,64,32,CameraState.lookAt(0,0,3,0,0,0).build())
                .minimumSceneRevision(6).clock(FrameClock.of(4,1))
                .environment(EnvironmentState.builder().cameraMedium(new Medium(new Rgb(.1f,.2f,.3f),
                        Rgb.ZERO,Rgb.ZERO,1,1,0,field)).build())
                .mediumTransport(MediumTransport.balanced().withLightSamples(2).withScatteringBounces(3))
                .antiAliasing(new AntiAliasingState(4))
                .primitiveBatch(FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(9).surfaceVisibility(.5f).build()).build())
                .resetTemporalHistory(HistoryResetReason.CAMERA_CUT).build();
        var copy = VulkanMediumTransportBenchmark.request(8,template);
        require(copy.sequence()==8 && copy.width()==template.width() && copy.height()==template.height()
                && copy.camera().equals(template.camera()) && copy.minimumSceneRevision()==template.minimumSceneRevision()
                && copy.clock().equals(template.clock()) && copy.environment().equals(template.environment())
                && copy.mediumTransport().equals(template.mediumTransport()) && copy.lightmap().equals(template.lightmap())
                && copy.fog().equals(template.fog()) && copy.textureSampling().equals(template.textureSampling())
                && copy.antiAliasing().equals(template.antiAliasing()) && copy.depthProjection().equals(template.depthProjection())
                && copy.primitiveBatch().equals(template.primitiveBatch()) && copy.shaderData().equals(template.shaderData())
                && copy.temporalHistoryResets().equals(template.temporalHistoryResets())
                && copy.environment().cameraMedium().densityField().equals(field)
                && copy.mediumTransport().scatteringBounces() == 3,
                "benchmark frames must preserve the complete declared workload when sequence advances");
    }

    private static RendererDiagnostics.FrameGpuTiming timing(long count,long mean) {
        return RendererDiagnostics.FrameGpuTiming.builder().enabled(true).completedSamples(count)
                .averageTraceNanos(mean).averageTotalNanos(mean).maxTotalNanos(mean+10).build();
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try { action.run(); } catch(Throwable failure) { if(type.isInstance(failure)) return;throw failure; }
        throw new AssertionError("expected "+type.getSimpleName());
    }
}
