package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.Path;

/** Paths needed to launch a consumer beside the unmodified upstream runtime. */
public final class RtxptRuntime {
    private RtxptRuntime() { }

    /**
     * Returns the verified native runtime bin directory. A packaged runtime is
     * extracted once; developers can explicitly set {@code top.ceroxe.rt.rtxpt.runtime}.
     * This method does not initialize a device or start a renderer.
     * @return absolute runtime bin directory, with official Assets beside it
     */
    public static Path binDirectory() {
        String override = System.getProperty("top.ceroxe.rt.rtxpt.runtime");
        Path bin = override != null ? Path.of(override) : RtxptRuntimeBundle.packaged()
            ? RtxptRuntimeBundle.extract()
            : Path.of("build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin");
        bin = bin.toAbsolutePath().normalize();
        try {
            RtxptRuntimeBundle.verifyBin(bin);
            if (!java.nio.file.Files.isDirectory(bin.resolve("../Assets").normalize()))
                throw new IOException("Missing official RTXPT Assets beside runtime bin");
            if (!java.nio.file.Files.isDirectory(bin.resolve("../Assets/EnvironmentMaps").normalize()))
                throw new IOException("Missing RTXPT EnvironmentMaps directory (it may be empty)");
        } catch (IOException failure) {
            throw new IllegalStateException("RTXPT runtime is incomplete: " + bin, failure);
        }
        return bin;
    }

    /**
     * Returns the JNI Invocation host executable used as the consumer's process.
     * @return absolute executable path
     */
    public static Path jvmHostExecutable() { return binDirectory().resolve("rtrenderer_jvm_host.exe"); }
}
