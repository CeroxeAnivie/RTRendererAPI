package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.Path;

/** Run inside the real native JVM host: the OS must reject child creation. */
public final class RtxptPrecompiledProcessSelfTest {
    public static void main(String[] args) throws Exception {
        var executable = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        try {
            var child = new ProcessBuilder(executable.toString(), "/c", "exit", "0").start();
            child.waitFor();
            throw new AssertionError("Native renderer process permits runtime compiler children");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("367")) throw expected;
            System.out.println("RTXPT_PRECOMPILED_PROCESS_SELFTEST PASS win32=367 childCreationBlocked=true");
        }
    }
}
