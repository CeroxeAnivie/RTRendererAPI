package top.ceroxe.rt.renderer.backend.vulkan;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/** Public-API-only, identical-work transport comparison; can run against an older runtime JAR. */
public final class VulkanMediumTransportBenchmark {
    private static final int WIDTH = 960, HEIGHT = 540;
    private static long sequence;
    private static final CameraState CAMERA = CameraState.lookAt(0, 0, 6, 0, 0, -2)
            .verticalFieldOfViewDegrees(35).aspectRatio(WIDTH / (double) HEIGHT).build();

    public static void main(String[] args) throws Exception {
        var config = RendererConfig.builder().maxFramesInFlight(4).validationEnabled(false)
                .gpuTimingsEnabled(true).cpuFrameReadbackEnabled(false)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build();
        try (var renderer = Renderer.open(config)) {
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            long revision = 0;
            for (String lane : new String[]{"opaque", "glass", "absorption", "emission", "nested-water-glass", "scattering"}) {
                var transaction = SceneTransaction.builder(++revision).resetScene()
                        .upsert(MaterialAsset.builder(1).roughness(.65f).doubleSided(true).build())
                        .upsert(MaterialAsset.builder(2).transmission(1).indexOfRefraction(1.5f).roughness(.04f).doubleSided(true).build())
                        .upsert(MeshAsset.triangles(1, new float[]{-100,-100,-4,100,-100,-4,100,100,-4,-100,100,-4},
                                new int[]{0,1,2,2,3,0}, 1))
                        .upsert(cube(2, 2)).upsert(SceneInstance.builder(1, 1).build())
                        .upsert(SceneLight.point(1, 0, 3, 3).intensity(30).range(20).build());
                if (!lane.equals("opaque")) {
                    var medium = switch (lane) {
                        case "absorption" -> EnvironmentState.Medium.builder().absorption(.2f,.1f,.03f).density(1).build();
                        case "emission" -> EnvironmentState.Medium.builder().absorption(.2f,.1f,.03f).emission(.04f,.02f,0).density(1).build();
                        case "scattering" -> EnvironmentState.Medium.builder().absorption(.02f,.02f,.02f)
                                .scattering(.1f,.1f,.1f).phaseAnisotropy(.2f).density(1).build();
                        default -> EnvironmentState.Medium.builder().indexOfRefraction(1.5f).build();
                    };
                    boolean surface = lane.equals("glass") || lane.equals("nested-water-glass");
                    transaction.upsert(SceneInstance.builder(2, 2).transform(scale(2))
                            .mediumBoundary(MediumBoundaryState.builder(medium).surfaceInteraction(surface).priority(1).build()).build());
                    if (lane.equals("nested-water-glass")) {
                        transaction.upsert(SceneInstance.builder(3, 2).transform(scale(.9f))
                                .mediumBoundary(MediumBoundaryState.builder(EnvironmentState.Medium.builder()
                                        .absorption(.02f,.01f,.005f).indexOfRefraction(1.333f).density(1).build())
                                        .priority(2).build()).build());
                    }
                }
                renderer.apply(transaction.build());
                range(renderer, interop, revision, 12);
                var before = renderer.diagnostics().frameGpuTiming();
                long started = System.nanoTime();
                range(renderer, interop, revision, 64);
                long elapsed = System.nanoTime() - started;
                var after = renderer.diagnostics().frameGpuTiming();
                long samples = after.completedSamples() - before.completedSamples();
                double gpuNanos = ((double) after.averageTotalNanos() * after.completedSamples()
                        - (double) before.averageTotalNanos() * before.completedSamples()) / samples;
                if (samples != 64 || after.failedSamples() != 0 || !Double.isFinite(gpuNanos))
                    throw new AssertionError("missing hardware timing evidence: " + after);
                System.out.printf(Locale.ROOT, "TransportBenchmark lane=%s extent=%dx%d spp=1 frames=%d gpuMs=%.4f nativeFps=%.2f%n",
                        lane, WIDTH, HEIGHT, samples, gpuNanos / 1e6, 64e9 / elapsed);
            }
            System.out.println("TransportBenchmark health=" + renderer.health());
        }
    }

    private static void range(Renderer renderer, VulkanFrameInterop interop, long revision, int count) {
        long end = sequence + count, last = end - 1;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (renderer.diagnostics().latestCompletedFrameSequence() < last) {
            while (sequence < end) {
                var frame = RenderFrameRequest.builder(sequence, WIDTH, HEIGHT, CAMERA)
                        .minimumSceneRevision(revision).environment(EnvironmentState.builder()
                                .skyRadiance(.1f,.12f,.15f).ambientIntensity(.2f)
                                .sunDirection(0,0,-1).sunIntensity(1).build())
                        .mediumTransport(new MediumTransportOptions(12, 1, 8, 512)).build();
                if (!(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)) break;
                sequence++;
            }
            if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available) available.lease().close();
            if (System.nanoTime() > deadline) throw new AssertionError("transport benchmark timed out");
            LockSupport.parkNanos(25_000);
        }
        if (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available) available.lease().close();
    }

    private static AffineTransform scale(float s) {
        return new AffineTransform(new float[]{s,0,0,0,0,s,0,0,0,0,s,0});
    }

    private static MeshAsset cube(long id, long material) {
        return MeshAsset.triangles(id, new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},
                new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5}, material);
    }
}
