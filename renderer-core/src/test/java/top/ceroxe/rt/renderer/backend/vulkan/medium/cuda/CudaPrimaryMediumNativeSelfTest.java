package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import static java.lang.foreign.ValueLayout.*;
import top.ceroxe.rt.renderer.api.*;

/** Native kernel ABI and memory-boundary checks, separate from the public-renderer image test. */
public final class CudaPrimaryMediumNativeSelfTest {
    public static void main(String[] args) {
        final int count = 257;
        final byte guard = 0x5a;
        var frame = RenderFrameRequest.builder(0, count, 1, CameraState.lookAt(0, 0, -2, 0, 0, 0).build())
                .environment(EnvironmentState.builder().cameraMedium(new Medium(
                        new Rgb(.1f, .2f, .3f), Rgb.ZERO, Rgb.ZERO, 2, 1, 0)).build())
                .mediumTransport(MediumTransport.balanced().withMaxDistance(7)).build();
        long primaryBytes = CudaPrimaryMediumAbi.primaryBytes(count);
        long outputBytes = CudaPrimaryMediumAbi.outputBytes(count);
        try (var runtime = CudaMediumRuntime.open(0);
             var kernel = runtime.compileBatchKernel(CudaMediumSource.resource("primary_absorption.cu"), "primary_medium_resolve");
             var primary = runtime.allocate(primaryBytes + 32);
             var constants = runtime.allocate(32);
             var output = runtime.allocate(outputBytes + 32);
             var arena = Arena.ofConfined()) {
            var records = primary.slice(16, primaryBytes);
            var packed = constants.slice(0, 16);
            var result = output.slice(16, outputBytes);
            var hostPrimary = arena.allocate(primaryBytes, 16);
            for (int i = 0; i < count; i++) {
                long at = (long) i * CudaPrimaryMediumAbi.PRIMARY_BYTES;
                hostPrimary.set(JAVA_FLOAT, at + 12, i % 7 == 0 ? -1 : i * .07f);
                hostPrimary.set(JAVA_INT, at + 112, i % 3 == 0 ? 0 : 1);
                // A conflicting value in every neighboring lane exposes an incorrect stride/offset.
                hostPrimary.set(JAVA_FLOAT, at + 8, 1000);
                hostPrimary.set(JAVA_INT, at + 116, i % 3 == 0 ? 1 : 0);
            }
            runtime.upload(records, hostPrimary);
            byte[] constantBytes = CudaPrimaryMediumAbi.constants(frame);
            runtime.upload(packed, arena.allocateFrom(JAVA_BYTE, constantBytes));
            byte[] initial = new byte[(int) output.byteSize()]; Arrays.fill(initial, guard);
            runtime.upload(output.slice(0, output.byteSize()), arena.allocateFrom(JAVA_BYTE, initial));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, 0, packed, result));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records.slice(0, primaryBytes - 1), count, packed, result));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, packed.slice(0, 15), result));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, packed, result.slice(0, outputBytes - 1)));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, primary.slice(17, primaryBytes), count, packed, result));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, constants.slice(1, 16), result));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, packed, output.slice(17, outputBytes)));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, packed, records.slice(0, outputBytes)));
            expectIllegal(() -> runtime.launchPrimaryBlocking(kernel, records, count, result.slice(0, 16), result));
            runtime.launchPrimaryBlocking(kernel, records, count, packed, result);
            var hostOutput = arena.allocate(output.byteSize(), 16);
            runtime.download(output.slice(0, output.byteSize()), hostOutput);
            for (int i = 0; i < 16; i++) require(hostOutput.get(JAVA_BYTE, i) == guard
                    && hostOutput.get(JAVA_BYTE, output.byteSize() - 16 + i) == guard, "output guards");
            long channels = 0;
            for (int i = 0; i < count; i++) {
                float sourceDistance = hostPrimary.get(JAVA_FLOAT, (long) i * 208 + 12);
                double distance = i % 3 == 0 ? 7 : Math.min(Math.max(sourceDistance, 0), 7);
                for (int c = 0; c < 4; c++) {
                    float actual = hostOutput.get(JAVA_FLOAT, 16 + (long) i * 16 + c * 4);
                    float coefficient = c == 3 ? 0 : MemorySegment.ofArray(constantBytes).get(JAVA_FLOAT_UNALIGNED, c * 4L);
                    double expected = c == 3 ? 1 : Math.exp(-coefficient * distance);
                    require(Float.isFinite(actual) && Math.abs(actual - expected) <= 4e-5,
                            "primary=" + i + " channel=" + c + " expected=" + expected + " actual=" + actual);
                    channels++;
                }
            }
            var closed = runtime.allocate(primaryBytes);
            var stale = closed.slice(0, primaryBytes); closed.close();
            try { runtime.launchPrimaryBlocking(kernel, stale, count, packed, result); throw new AssertionError("closed primary accepted"); }
            catch (IllegalStateException expected) { }
            System.out.println("CudaPrimaryMediumNativeSelfTest passed: hits/misses, distance clamp, native channels="
                    + channels + ", guards and slice/alignment/alias/lifetime rejection");
        }
    }
    private static void expectIllegal(Runnable call) {
        try { call.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
