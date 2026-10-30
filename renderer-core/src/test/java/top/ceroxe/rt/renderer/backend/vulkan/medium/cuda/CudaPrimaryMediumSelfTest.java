package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.pipeline.GpuScenePrimaryQueue;

/** Provider-free contract checks for production primary-hit attenuation. */
public final class CudaPrimaryMediumSelfTest {
    public static void main(String[] args) {
        require(CudaPrimaryMediumAbi.PRIMARY_BYTES == GpuScenePrimaryQueue.ITEM_BYTES, "shared primary stride");
        require(GpuScenePrimaryQueue.PUSH_BYTES == 32, "primary/native-output push range");
        require(CudaPrimaryMediumAbi.primaryBytes(Integer.MAX_VALUE) == 446676598576L, "wide primary sizing");
        require(CudaPrimaryMediumAbi.outputBytes(Integer.MAX_VALUE) == 34359738352L, "wide RGBA sizing");
        expectIllegal(() -> CudaPrimaryMediumAbi.primaryBytes(0));
        expectIllegal(() -> CudaPrimaryMediumAbi.outputBytes(-1));
        var medium = new Medium(new Rgb(.125f, .25f, .5f), Rgb.ZERO, Rgb.ZERO, 2, 1, 0);
        var frame = RenderFrameRequest.builder(0, 67, 49, CameraState.lookAt(0, 0, -2, 0, 0, 0).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withMaxDistance(7)).build();
        var constants = ByteBuffer.wrap(CudaPrimaryMediumAbi.constants(frame)).order(ByteOrder.nativeOrder());
        require(constants.capacity() == 16 && constants.getFloat(0) == .25f && constants.getFloat(4) == .5f
                && constants.getFloat(8) == 1 && constants.getFloat(12) == 7, "constant lanes and density");
        for (var unsupported : new Medium[]{
                new Medium(Rgb.ZERO, new Rgb(.1f, 0, 0), Rgb.ZERO, 1, 1, 0),
                new Medium(Rgb.ZERO, Rgb.ZERO, new Rgb(.1f, 0, 0), 1, 1, 0)}) {
            expectIllegal(() -> CudaPrimaryMediumAbi.constants(frame.toBuilder()
                    .environment(EnvironmentState.builder().cameraMedium(unsupported).build()).build()));
        }
        var refractiveMedium = new Medium(medium.absorption(),Rgb.ZERO,Rgb.ZERO,medium.density(),1.5f,0);
        require(java.util.Arrays.equals(constants.array(), CudaPrimaryMediumAbi.constants(frame.toBuilder()
                .environment(EnvironmentState.builder().cameraMedium(refractiveMedium).build()).build())),
                "IOR does not alter segment absorption without an interface");
        require(CudaMediumSource.resource("primary_absorption.cu").contains("HomogeneousVolume_SegmentScatter"),
                "primary transmittance uses the pinned upstream segment function");
        System.out.println("CudaPrimaryMediumSelfTest passed: production stride, push ABI, constants, sizes and admission");
    }
    private static void expectIllegal(Runnable call) {
        try { call.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
