package top.ceroxe.rt.renderer.nvidia;

/** Supplies the bounded native warning history to integration-test failures. */
public final class StreamlineNativeTestDiagnostics {
    private StreamlineNativeTestDiagnostics() {
    }

    public static String warnings() {
        return NvidiaNativeBridge.nativeStreamlineDiagnostic();
    }
}
