package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;

/** Provider-free packing and public-emission contract checks. */
public final class CudaPrimaryEmissionSelfTest {
    public static void main(String[] arguments) {
        require(CudaPrimaryEmissionAbi.PRIMARY_BYTES == 208, "production primary ABI");
        require(CudaPrimaryEmissionAbi.CONSTANT_BYTES == 32, "emission constants ABI");
        require(CudaPrimaryEmissionAbi.outputBytes(Integer.MAX_VALUE) == 68719476704L, "wide output sizing");
        require(CudaPrimaryEmissionAbi.emissionOffset(257) == 4112, "separate float4 output planes");
        expectIllegal(() -> CudaPrimaryEmissionAbi.outputBytes(0));
        expectIllegal(() -> CudaPrimaryEmissionAbi.emissionOffset(-1));
        var medium = new Medium(new Rgb(0, .25f, .5f), Rgb.ZERO, new Rgb(.5f, .125f, 1), 2, 1, .9f);
        var frame = RenderFrameRequest.builder(0, 37, 29, CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withMaxDistance(17)).build();
        var constants = ByteBuffer.wrap(CudaPrimaryEmissionAbi.constants(frame)).order(ByteOrder.nativeOrder());
        require(constants.capacity() == 32 && constants.getFloat(0) == 0 && constants.getFloat(4) == .5f
                && constants.getFloat(8) == 1 && constants.getFloat(12) == 17, "density-scaled absorption and distance");
        require(constants.getFloat(16) == 1 && constants.getFloat(20) == .25f
                && constants.getFloat(24) == 2 && constants.getInt(28) == 0, "density-scaled emission and reserved lane");
        for (var unsupported : new Medium[]{
                new Medium(Rgb.ZERO,new Rgb(.1f,0,0),Rgb.ZERO,1,1,0),
                new Medium(Rgb.ZERO,Rgb.ZERO,Rgb.ZERO,1,1.5f,0)})
            expectIllegal(() -> CudaPrimaryEmissionAbi.constants(frame.toBuilder()
                    .environment(EnvironmentState.builder().cameraMedium(unsupported).build()).build()));
        require(CudaMediumSource.resource("primary_emission.cu").contains("HomogeneousVolume_SegmentScatter"),
                "transmittance retains the pinned native segment function");
        require(Math.abs(integral(0, .7, 19) - .7 * 19) < 1e-12, "zero-extinction analytic emission");
        require(Math.abs(integral(1e-12, .7, 19) - .7 * 19) < 1e-9, "small-extinction emission is stable");
        System.out.println("CudaPrimaryEmissionSelfTest passed: native packing, sizing, admission and analytic emission contract");
    }

    static double integral(double absorption, double emission, double distance) {
        return emission == 0 || distance == 0 ? 0 : emission * (absorption == 0
                ? distance : -Math.expm1(-absorption * distance) / absorption);
    }

    static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void expectIllegal(Runnable operation) {
        try { operation.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
