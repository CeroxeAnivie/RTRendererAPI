package top.ceroxe.rt.renderer.rtxpt;

/** Owner-thread diagnostic view, also available when a composed renderer owns frame delivery. */
public interface RtxptDiagnosticAccess {
    void diagnosticLevel(RtxptDiagnostics.Level level);
    RtxptDiagnostics.Snapshot diagnosticSnapshot();
    RtxptDiagnostics.GpuSnapshot diagnosticGpuSnapshot();
    RtxptAccess.SubmitStages submitStages();
    RtxptAccess.PresentationStatus presentationStatus();
    RtxptAccess.RuntimeStatus runtimeStatus();
}
