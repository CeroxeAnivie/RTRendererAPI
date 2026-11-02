package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Short, deterministic material/motion/resize fixture for native memory-fix A/B captures. */
public final class RtxptMemoryParitySelfTest {
    private RtxptMemoryParitySelfTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Output directory required");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        boolean legacy = java.util.Arrays.asList(args).contains("--legacy");
        boolean benchmark = java.util.Arrays.asList(args).contains("--benchmark");
        var config = DemoRendererProfile.rtxptInteractive();
        if (benchmark) config = config.copyBuilder().frameGeneration(FrameGenerationOptions.disabled()).build();
        var physics = new HexPhysics();
        var scene = new DemoScene(DemoScene.BallMaterial.GLASS);
        var previous = FramePrimitiveBatch.empty();
        long sequence = 0;
        long revision = 0;
        try (var renderer = Renderer.open("rtxpt", config)) {
            var host = renderer.extension(RtxptAccess.class).orElseThrow();
            renderer.apply(scene.initialTransaction());
            host.setExposureCompensation(DemoScene.GLASS_EXPOSURE_COMPENSATION);
            for (var material : new DemoScene.BallMaterial[]{DemoScene.BallMaterial.GLASS,
                    DemoScene.BallMaterial.OPAQUE_REFLECTIVE, DemoScene.BallMaterial.ORIGINAL_METAL}) {
                if (sequence > 0) {
                    scene = new DemoScene(material);
                    renderer.apply(scene.ballMaterialTransaction(++revision));
                }
                for (int f = 0; f < 48; f++) {
                    if (f >= 32) for (int step = 0; step < 4; step++) physics.stepForTesting();
                    var batch = scene.primitiveBatch(physics, previous);
                    long before = host.bridgeTimings().frames();
                    renderer.submit(DemoRendererProfile.frame(sequence, 1920, 1080,
                            DemoRendererProfile.camera(1920, 1080, 15.4))
                        .clock(FrameClock.of(sequence * 16_666_667L, 16_666_667L))
                        .minimumSceneRevision(revision).primitiveBatch(batch)
                        .environment(DemoRendererProfile.environment()).build());
                    awaitFrame(host, before);
                    previous = batch;
                    sequence++;
                    if (f == 31 || f == 47) {
                        String name = material + (f == 31 ? "-static" : "-moving");
                        host.capture(output.resolve(name + ".png"));
                        System.out.println("MEMORY_PARITY_CAPTURE " + name + " nativeFrames=" + host.bridgeTimings().frames());
                        if (material == DemoScene.BallMaterial.GLASS && f == 31
                                && "1".equals(System.getenv("RTR_RTXPT_MEMORY_DIAGNOSTICS")))
                            Thread.sleep(1500); // Bounded external VMMap/PDH observation; never part of timing.
                    }
                }
            }
            var status = host.runtimeStatus();
            require(status.sceneLighting() && !status.sharc() && !status.restirDi() && !status.restirGi(),
                "Default analytic transport remains active: " + status);
            require(status.reconstruction() == RtxptAccess.Reconstruction.DLSS && status.dlssMode() == 1,
                "Default SR Performance remains active: " + status);
            require(status.renderWidth() == 960 && status.renderHeight() == 540, "Default internal extent: " + status);
            require(status.activeExactOpticalInterfaces() == 4, "Four optical interfaces remain active");
            if (!legacy) require(host.pathQualityStatus().allocatedCacheEntries() == 0,
                "Disabled SHaRC must own no cache storage");
            require(status.frameGeneration() == !benchmark, "Requested default FG state remains active");
            Files.writeString(output.resolve("runtime.txt"), status + "\n" + host.pathQualityStatus() + "\n");
            if (benchmark) {
                host.awaitIdle();
                long start = System.nanoTime();
                long before = host.bridgeTimings().frames();
                for (int f = 0; f < 480; f++) require(host.renderFrame(), "Benchmark window stays open");
                host.awaitIdle();
                long frames = host.bridgeTimings().frames() - before;
                require(frames == 480, "Every benchmark iteration completes a native frame");
                String timing = String.format(Locale.ROOT, "MEMORY_PARITY_TIMING frames=%d msPerFrame=%.9f%n",
                    frames, (System.nanoTime() - start) / 1_000_000.0 / frames);
                Files.writeString(output.resolve("timing.txt"), timing);
                System.out.print(timing);
            }
            for (int f = 0; f < 16; f++) {
                long before = host.bridgeTimings().frames();
                renderer.submit(DemoRendererProfile.frame(sequence++, 1280, 720, DemoRendererProfile.camera(1280, 720, 15.4))
                    .clock(FrameClock.of(sequence * 16_666_667L, 16_666_667L))
                    .minimumSceneRevision(revision).primitiveBatch(previous)
                    .environment(DemoRendererProfile.environment()).build());
                awaitFrame(host, before);
            }
            host.capture(output.resolve("resized.png"));
            require(host.presentationStatus().outputWidth() == 1280, "Output resized");
            require(host.runtimeStatus().frameGeneration() == !benchmark, "FG restored after resize");
            require(renderer.health().activeFailure().isEmpty(), "Renderer remains healthy");
            System.out.println("MEMORY_PARITY PASS " + host.bridgeTimings());
        }
        System.out.println("MEMORY_PARITY CLOSED");
    }

    private static void awaitFrame(RtxptAccess host, long before) {
        long deadline = System.nanoTime() + 90_000_000_000L;
        while (host.bridgeTimings().frames() == before && System.nanoTime() < deadline)
            require(host.renderFrame(), "Window stays open");
        require(host.bridgeTimings().frames() == before + 1, "Exactly one native frame per pose");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
