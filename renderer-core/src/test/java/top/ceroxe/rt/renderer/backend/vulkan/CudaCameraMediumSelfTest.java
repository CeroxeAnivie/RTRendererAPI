package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraMediumAbi;

/** Provider-free admission and exact constant packing for the first native camera segment. */
public final class CudaCameraMediumSelfTest {
    public static void main(String[] args) {
        String enable = System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        try {
            System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            require(CudaCameraMediumProfile.requested(), "built-in LuxCore medium route must be enabled by default");
            System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, "false");
            require(!CudaCameraMediumProfile.requested(), "explicit rollback switch must disable the native route");
        } finally {
            if (enable == null) System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY, enable);
        }
        var medium = new Medium(new Rgb(.1f, .2f, .3f), Rgb.ZERO, Rgb.ZERO, 2, 1, 0);
        var frame = RenderFrameRequest.builder(0, 33554433, 1, CameraState.lookAt(0, 0, -2, 0, 0, 0).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).build();
        CudaCameraMediumProfile.requireSupported(frame, 0, true);
        var constants = ByteBuffer.wrap(CudaCameraMediumAbi.constants(frame, 16777217, 3, .25f, -.125f, .001f))
                .order(ByteOrder.nativeOrder());
        require(constants.capacity() == 96, "constants byte count");
        require(constants.getInt(28) == 33554433 && constants.getInt(44) == 1
                && constants.getInt(60) == 16777217, "uint pixel lanes must not round through float");
        require(constants.getFloat(8) == -2 && constants.getFloat(80) == .2f
                && constants.getFloat(84) == .4f && constants.getFloat(88) == .6f, "origin and density-scaled absorption");
        require(constants.getFloat(72) == .25f && constants.getFloat(76) == -.125f
                && constants.getFloat(92) == .001f, "jitter and epsilon");
        require(CudaCameraMediumAbi.outputBytes(Integer.MAX_VALUE) == 34359738352L, "wide output sizing");
        expectIllegal(() -> CudaCameraMediumAbi.outputBytes(0));
        expectIllegal(() -> CudaCameraMediumAbi.constants(frame, -1, 3, 0, 0, .001f));
        expectIllegal(() -> CudaCameraMediumAbi.constants(frame, 33554432, 2, 0, 0, .001f));
        expectIllegal(() -> CudaCameraMediumAbi.constants(frame, 0, 1, Float.NaN, 0, .001f));
        expectIllegal(() -> CudaCameraMediumAbi.constants(frame, 0, 1, 0, 0, frame.mediumTransport().maxDistance()));
        expectUnsupported(() -> CudaCameraMediumProfile.requireSupported(frame, 1, true));
        expectUnsupported(() -> CudaCameraMediumProfile.requireSupported(frame, 0, false));
        CudaCameraMediumProfile.requireSupported(frame.toBuilder()
                .antiAliasing(AntiAliasingState.multisampled(8)).build(), 0, true);
        require(CudaCameraMediumManager.paths(17, 13, 8) == 1768,
                "native primary path count retains all spatial samples");
        require(CudaCameraMediumManager.requiredBytes(17, 13, 8) == 1768L * 240 + 32 + 65536,
                "native shared frame budget contains every sample and inline frame constants");
        CudaCameraMediumProfile.requireSupported(frame.toBuilder().environment(EnvironmentState.builder()
                .cameraMedium(new Medium(Rgb.ZERO, Rgb.ZERO, new Rgb(.1f, 0, 0), 1, 1, 0))
                .build()).build(), 0, true);
        CudaCameraMediumProfile.requireSupported(frame.toBuilder().environment(EnvironmentState.builder()
                .cameraMedium(new Medium(new Rgb(.1f,0,0),Rgb.ZERO,Rgb.ZERO,1,1.5f,0)).build()).build(),0,true);
        for (var unsupported : new Medium[]{Medium.vacuum(),
                new Medium(Rgb.ZERO, new Rgb(.1f, 0, 0), Rgb.ZERO, 1, 1, 0)}) {
            var rejected = frame.toBuilder().environment(EnvironmentState.builder().cameraMedium(unsupported).build()).build();
            expectUnsupported(() -> CudaCameraMediumProfile.requireSupported(rejected, 0, true));
        }
        System.out.println("CudaCameraMediumSelfTest passed: admission, exact uint constants, coefficient density, tile bounds");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void expectIllegal(Runnable operation) {
        try { operation.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void expectUnsupported(Runnable operation) {
        try { operation.run(); throw new AssertionError("expected unsupported medium frame"); }
        catch (FrameValidationException expected) {
            require(expected.reason() == FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                    "unsupported medium transport must retain its recoverable input category");
        }
    }
}
