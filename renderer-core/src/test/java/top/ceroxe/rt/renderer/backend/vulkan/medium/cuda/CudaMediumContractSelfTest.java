package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Provider-free source/ABI checks; does not initialize CUDA or NVRTC. */
public final class CudaMediumContractSelfTest {
    public static void main(String[] args) {
        require(CudaMediumAbi.inputBytes(2) == 96, "input ABI");
        require(CudaMediumAbi.outputBytes(2) == 56, "output ABI");
        require(CudaMediumAbi.inputBytes(Integer.MAX_VALUE) == 103079215056L, "wide sizing");
        expectIllegal(() -> CudaMediumAbi.inputBytes(0));
        expectIllegal(() -> CudaMediumAbi.outputBytes(-1));
        expectIllegal(() -> CudaMediumSource.resource("../escape"));
        String source = CudaMediumSource.source();
        require(source.contains("HomogeneousVolume_SegmentScatter"), "upstream function packaged");
        require(source.contains("rtr_luxcore_segment"), "batch entry packaged");
        require(source.contains("__device__"), "CUDA compatibility packaged");
        require(CudaMediumSource.resource("camera_absorption.cu").contains("camera_medium_resolve"), "camera wrapper packaged");
        require(CudaMediumSource.resource("primary_absorption.cu").contains("primary_medium_resolve"), "primary wrapper packaged");
        require(CudaMediumSource.resource("NOTICE.txt").contains(CudaMediumAbi.UPSTREAM_COMMIT), "source identity");
        require(CudaMediumSource.resource("LICENSE.txt").contains("Apache License"), "license packaged");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    CudaMediumSource.resource("upstream-segment-function.cl").getBytes(StandardCharsets.UTF_8)));
            require(digest.equals("40593200c7db1913e33aa4b416ffcbec00e4b68bdfa6c2e3e98868e24d09570a"),
                    "upstream function must remain byte-identical");
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        System.out.println("CudaMediumContractSelfTest passed");
    }
    private static void require(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    private static void expectIllegal(Runnable call) {
        try { call.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
