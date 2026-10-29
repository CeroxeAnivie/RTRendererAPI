package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import top.ceroxe.rt.renderer.api.MediumTransport;

/** Provider-free ABI/source provenance checks for the transport compatibility layer. */
public final class CudaMediumTransportSelfTest {
    public static void main(String[] arguments) throws Exception {
        require(CudaMediumTransportAbi.MAX_BOUNDARIES == MediumTransport.MAX_NESTED_BOUNDARIES
                && CudaMediumTransportAbi.MAX_BOUNDARIES == 32, "public nesting capacity");
        require(CudaMediumTransportAbi.stackBytes(2) == 288, "aligned path stride");
        require(CudaMediumTransportAbi.stackBytes(Integer.MAX_VALUE) == 309237645168L, "wide sizing");
        var empty = ByteBuffer.wrap(CudaMediumTransportAbi.emptyStack()).order(ByteOrder.nativeOrder());
        require(empty.capacity() == 144 && empty.getInt(0) == -1 && empty.getInt(132) == 0
                && empty.getInt(136) == 0, "empty stack wire layout");
        expectIllegal(() -> CudaMediumTransportAbi.stackBytes(0));
        String upstream = CudaMediumSource.resource("upstream-pathvolumeinfo-functions.cl");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(upstream.getBytes(StandardCharsets.UTF_8)));
        require(digest.equals("19dcb0a1de4e4ae058b6c4f1b593e856d7b9921175fc5b14c57ed0f03100097d"),
                "pinned priority function excerpt remains unchanged");
        var source = CudaMediumTransportSource.source();
        require(source.contains("PathVolumeInfo_AddVolume") && source.contains("PathVolumeInfo_RemoveVolume")
                && source.contains("hgEvaluate") && source.contains("hgSample"), "transport source assembly");
        for (double g : new double[]{-.9, -.5, -.001, 0, .001, .5, .9}) {
            double mean = 0;
            int count = 131072;
            for (int i = 0; i < count; i++) mean += inverse((i + .5) / count, g);
            require(Math.abs(mean / count - g) < 2e-8, "HG inverse-CDF mean equals asymmetry g=" + g);
        }
        System.out.println("CudaMediumTransportSelfTest passed: pinned provenance, 32-boundary ABI and HG analytic mean");
    }

    static double inverse(double u, double g) {
        if (g == 0) return 2 * u - 1;
        double ratio = (1 - g * g) / (1 - g + 2 * g * u);
        return Math.max(-1, Math.min(1, (1 + g * g - ratio * ratio) / (2 * g)));
    }

    static double phase(double cosine, double g) {
        double denominator = 1 + g * g - 2 * g * cosine;
        return (1 - g * g) / (4 * Math.PI * denominator * Math.sqrt(denominator));
    }

    private static void expectIllegal(Runnable call) {
        try { call.run(); throw new AssertionError("expected invalid dimensions"); }
        catch (IllegalArgumentException expected) { }
    }

    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
