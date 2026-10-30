package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.LongFunction;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** Complete final HDR outputs and bounded GPU-owned density scattering continuations. */
public final class CudaHeterogeneousMultipleScatteringAbi {
    public static final int MAGIC = 0x52484d31, VERSION = 1, HEADER_BYTES = 224;
    public static final int STATE_BYTES = 144, CONNECTION_BYTES = 32, MAX_CONNECTIONS = 9;
    private CudaHeterogeneousMultipleScatteringAbi() { }

    public record Plan(int pixels, int samples, int steps, int tilePixels, int primaryOffset,
            int transmittanceOffset, int emissionOffset, int stateOffset, int connectionOffset,
            int connections, long bytes) {
        public int paths() { return Math.multiplyExact(pixels, samples); }
        public int secondaryPaths(int count) { return Math.multiplyExact(Math.multiplyExact(count, samples), steps); }
    }

    public record FrameScene(RenderFrameRequest frame, Plan plan, CudaMediumDensityAbi.Scene density,
                             int lightUpperBound) {
        public int chunks() {
            long lights = frame.mediumTransport().lightSamples() == 0 ? lightUpperBound
                    : Math.min(lightUpperBound, frame.mediumTransport().lightSamples());
            return Math.toIntExact((lights + 1 + plan.connections() - 1) / plan.connections());
        }
        public byte[] metadata(int first, int pixels, int phase, int chunk) { return pack(first, pixels, phase, chunk, true); }
        public byte[] header(int first, int pixels, int phase, int chunk) { return pack(first, pixels, phase, chunk, false); }
        private byte[] pack(int first, int pixels, int phase, int chunk, boolean includeDensity) {
            if (first < 0 || pixels < 1 || pixels > plan.tilePixels() || (long) first + pixels > plan.pixels()
                    || phase < 0 || phase > 1 || chunk < 0 || chunk >= chunks()) throw new IllegalArgumentException("invalid heterogeneous continuation tile");
            var medium = frame.environment().cameraMedium();
            var sigma = medium.extinction().multiply(medium.density());
            var scattering = medium.scattering().multiply(medium.density());
            var emission = medium.emission().multiply(medium.density());
            var budget = frame.mediumTransport();
            ByteBuffer out = ByteBuffer.allocate(includeDensity ? HEADER_BYTES + density.bytes() : HEADER_BYTES).order(ByteOrder.nativeOrder());
            out.putInt(MAGIC).putInt(VERSION).putInt(pixels * plan.samples()).putInt(plan.steps())
                    .putInt(first).putInt(pixels).putInt(plan.samples()).putInt(budget.scatteringBounces())
                    .putInt(plan.primaryOffset()).putInt(plan.transmittanceOffset()).putInt(plan.emissionOffset()).putInt(0)
                    .putFloat(sigma.red()).putFloat(sigma.green()).putFloat(sigma.blue()).putFloat(budget.maxDistance())
                    .putFloat(scattering.red()).putFloat(scattering.green()).putFloat(scattering.blue()).putFloat(medium.anisotropy())
                    .putFloat(emission.red()).putFloat(emission.green()).putFloat(emission.blue()).putFloat(budget.transmittanceCutoff());
            out.putInt(108, plan.stateOffset()).putInt(128, STATE_BYTES).putInt(132, (int) frame.sequence())
                    .putInt(136, budget.maxEvents()).putInt(140, plan.secondaryPaths(pixels))
                    .putInt(160, HEADER_BYTES + density.environmentOffset()).putInt(164, plan.stateOffset())
                    .putInt(168, plan.connectionOffset()).putInt(172, plan.connections())
                    .putInt(176, lightUpperBound).putInt(180, budget.lightSamples()).putInt(184, chunk * plan.connections()).putInt(188, phase);
            if (includeDensity) density.write(out, HEADER_BYTES);
            return out.array();
        }
    }

    public static FrameScene scene(RenderFrameRequest frame, int width, int height, int lightUpperBound,
            LongFunction<TextureAsset> textures, CudaMediumDensityAbi.Cache cache, long residentLimit) {
        var medium = frame.environment().cameraMedium();
        int samples = frame.antiAliasing().samplesPerPixel(), steps = frame.mediumTransport().scatteringSteps();
        if (width < 1 || height < 1 || samples < 1 || samples > 8 || steps < 1 || steps > 64
                || lightUpperBound < 0 || medium.densityField().isUniform()
                || medium.scattering().isZero() || frame.mediumTransport().scatteringBounces() < 1)
            throw new IllegalArgumentException("heterogeneous scattering and additional bounces required");
        int pixels = Math.toIntExact(Math.multiplyExact((long) width, height));
        long paths = Math.multiplyExact((long) pixels, samples);
        int connections = (int) Math.min(MAX_CONNECTIONS, 1L + (frame.mediumTransport().lightSamples() == 0
                ? lightUpperBound : Math.min(lightUpperBound, frame.mediumTransport().lightSamples())));
        long perPixel = Math.multiplyExact((long) samples * steps, STATE_BYTES + (long) connections * CONNECTION_BYTES);
        long densityBudget = Math.min(residentLimit, CudaMultipleScatteringAbi.RESIDENT_CAP)
                - CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE - HEADER_BYTES
                - Math.multiplyExact(paths, CudaMultipleScatteringAbi.PRIMARY_BYTES + 2L * CudaMultipleScatteringAbi.COLOR_BYTES) - perPixel;
        var descriptorMedium = medium.participating() ? medium : medium.withDensity(1);
        var density = cache == null ? CudaMediumDensityAbi.prepare(descriptorMedium, List.of(), textures, densityBudget)
                : cache.prepare(descriptorMedium, List.of(), textures, densityBudget);
        int primary = Math.addExact(HEADER_BYTES, density.bytes());
        int tr = Math.toIntExact(primary + paths * CudaMultipleScatteringAbi.PRIMARY_BYTES);
        int em = Math.toIntExact(tr + paths * CudaMultipleScatteringAbi.COLOR_BYTES);
        int state = Math.toIntExact(em + paths * CudaMultipleScatteringAbi.COLOR_BYTES);
        long available = Math.min(residentLimit, CudaMultipleScatteringAbi.RESIDENT_CAP)
                - CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE - state;
        int tile = Math.toIntExact(Math.min(pixels, Math.max(0, available) / perPixel));
        if (tile < 1) throw new IllegalArgumentException("complete heterogeneous multiple-scattering frame exceeds the CUDA cap");
        long secondary = Math.multiplyExact((long) tile * samples, steps);
        int connection = Math.toIntExact(state + secondary * STATE_BYTES);
        long bytes = Math.addExact(connection, secondary * connections * CONNECTION_BYTES);
        return new FrameScene(frame, new Plan(pixels, samples, steps, tile, primary, tr, em, state,
                connection, connections, bytes), density, lightUpperBound);
    }
}
