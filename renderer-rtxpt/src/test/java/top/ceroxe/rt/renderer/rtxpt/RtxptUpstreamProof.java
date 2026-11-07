package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** A bounded real upstream initialization/capture proof; not a renderer capability claim. */
public final class RtxptUpstreamProof {
    private static native long[] runNative(String[] options, String captureFile, int frames);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException(
                "Usage: <runtime-bin> <capture.png> <rendered-frames> [original RTXPT options...]");
        Path runtime = Path.of(args[0]).toAbsolutePath().normalize();
        Path capture = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(capture.getParent());
        for (String dependency : new String[]{"sl.interposer.dll", "NRD.dll", "omm-lib.dll"})
            System.load(runtime.resolve(dependency).toString());
        System.load(runtime.resolve("rtrenderer_rtxpt.dll").toString());
        long[] result = runNative(Arrays.copyOfRange(args, 3, args.length), capture.toString(),
                Integer.parseInt(args[2]));
        if (result == null || result.length != 4 || result[3] != 1 || !Files.isRegularFile(capture))
            throw new IllegalStateException("Upstream proof did not produce a captured frame");
        System.out.println("Original RTXPT capture: frames="+result[0]+" size="+result[1]+"x"+result[2]
                +" file="+capture);
    }
}
