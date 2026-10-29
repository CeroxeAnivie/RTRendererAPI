package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameSnapshot;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumTransport;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.Rgb;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;

/** Public HDR against the finite-order homogeneous transport solution, independent of kernel code. */
public final class CudaMultipleScatteringProductionNativeSelfTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static long sequence;

    private CudaMultipleScatteringProductionNativeSelfTest() { }

    public static void main(String[] arguments) throws Exception {
        String previous = System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, "true");
        long routes = VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum();
        long collisions = CudaMultipleScatteringManager.diagnosticCollisions.sum();
        int nativeFrames = 0;
        try (Renderer renderer = Renderer.open("vulkan-rt", RendererConfig.builder()
                .maxFramesInFlight(2).validationEnabled(true)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            Rgb sky = new Rgb(.8f, .5f, .25f);
            EnvironmentState environment = EnvironmentState.builder()
                    .skyRadiance(sky.red(), sky.green(), sky.blue())
                    .ambientIntensity(.7f).sunIntensity(0).build();
            Medium medium = new Medium(new Rgb(.2f, .2f, .2f),
                    new Rgb(.5f, .5f, .5f), Rgb.ZERO, 1, 1, 0);
            for (Medium tested : new Medium[]{medium, new Medium(new Rgb(.2f, .35f, .5f),
                    new Rgb(.5f, .35f, .2f), Rgb.ZERO, 1, 1, .6f)}) {
            for (int bounces : new int[]{1, 2, 8}) {
                int samples = bounces == 8 ? 8 : bounces;
                RenderFrameRequest vacuumRequest = request(environment, Medium.vacuum(), bounces, samples, 96, 64);
                double[] vacuum = mean(capture(renderer, vacuumRequest));
                FrameSnapshot frame = capture(renderer, request(environment, tested, bounces, samples, 96, 64));
                nativeFrames++;
                double[] actual = mean(frame);
                // Total optical path length is bounded by L, independently of sampled direction.
                // The linear sky has spherical-harmonic orders zero and one only. Each HG
                // scattering multiplies its directional gradient by g; the k-th collision
                // order has the finite-distance weight exp(-sigma L)*(s L)^k/k!.
                double[] sigma = {tested.extinction().red(), tested.extinction().green(), tested.extinction().blue()};
                double[] scatter = {tested.scattering().red(), tested.scattering().green(), tested.scattering().blue()};
                double[] colors = {sky.red(), sky.green(), sky.blue()};
                for (int channel = 0; channel < 3; channel++) {
                    double sphericalMean = colors[channel] * environment.ambientIntensity() * .675;
                    double term = 1, orders = 0;
                    for (int order = 1; order <= bounces + 1; order++) {
                        term *= scatter[channel] * tested.density() * 2 / order;
                        orders += term * (sphericalMean + (vacuum[channel] - sphericalMean)
                                * Math.pow(tested.anisotropy(), order));
                    }
                    double expected = Math.exp(-sigma[channel] * tested.density() * 2) * (vacuum[channel] + orders);
                    // This is a stochastic image-mean test. The tolerance includes sampling
                    // error and half rounding; deterministic per-pixel equality is inapplicable.
                    require(Math.abs(actual[channel] - expected) <= .004,
                            "finite scattering order " + (bounces + 1) + " channel=" + channel
                                    + " actual=" + actual[channel] + " expected=" + expected);
                }
                System.out.printf(Locale.ROOT,
                        "Multiple scattering HDR order=%d SPP=%d g=%.2f meanRGB=%.8f,%.8f,%.8f%n",
                        bounces + 1, samples, tested.anisotropy(), actual[0], actual[1], actual[2]);
            }
            }
            // Resize and switch SPP after the maximum-bounce allocation. With no incident
            // radiance or emission the exact solution is black at every scattering order.
            EnvironmentState black = environment.toBuilder().skyRadiance(0, 0, 0).build();
            double[] darkness = mean(capture(renderer, request(black, medium, 2, 2, 31, 19)));
            nativeFrames++;
            for (double channel : darkness) require(channel == 0, "black transport acquired radiance");
            require(renderer.health().activeFailure().isEmpty(), "multiple scattering session unhealthy");
        } finally {
            if (previous == null) System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, previous);
        }
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum() - routes == nativeFrames,
                "public HDR bypassed native multiple scattering");
        require(CudaMultipleScatteringManager.diagnosticCollisions.sum() > collisions,
                "no additional native scattering collisions were evaluated");
        System.out.println("CudaMultipleScatteringProductionNativeSelfTest passed: finite-order analytic HDR,"
                + " SPP 1/2/8, bounce budgets 1/2/8, actual native collisions, resize and close");
    }

    private static RenderFrameRequest request(EnvironmentState environment, Medium medium,
            int bounces, int samples, int width, int height) {
        return RenderFrameRequest.builder(++sequence, width, height,
                CameraState.lookAt(0, 0, 0, 0, 1, -1).aspectRatio((double) width / height).build())
                .environment(environment.toBuilder().cameraMedium(medium).build())
                .antiAliasing(samples == 1 ? AntiAliasingState.disabled() : AntiAliasingState.multisampled(samples))
                .mediumTransport(MediumTransport.balanced().withMaxDistance(2)
                        .withScatteringSteps(16).withScatteringBounces(bounces).withTransmittanceCutoff(0))
                .build();
    }

    private static FrameSnapshot capture(Renderer renderer, RenderFrameRequest request) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime() < deadline, "multiple scattering admission timeout");
            Thread.sleep(1);
        }
        while (System.nanoTime() < deadline) {
            var ready = renderer.pollFrame();
            if (ready.isPresent() && ready.orElseThrow().frameSequence() == request.sequence()) return ready.orElseThrow();
            Thread.sleep(1);
        }
        throw new AssertionError("multiple scattering final HDR timeout");
    }

    private static double[] mean(FrameSnapshot frame) {
        var pixels = frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double[] result = new double[3];
        int count = frame.width() * frame.height();
        for (int pixel = 0; pixel < count; pixel++) {
            for (int channel = 0; channel < 3; channel++) {
                float value = Float.float16ToFloat(pixels.getShort(pixel * 8 + channel * 2));
                require(Float.isFinite(value) && value >= 0, "nonfinite or negative multiple scattering HDR");
                result[channel] += value / count;
            }
            require(Float.float16ToFloat(pixels.getShort(pixel * 8 + 6)) == 1, "multiple scattering alpha");
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
