package top.ceroxe.rt.renderer.rtxpt;

/** Owner-thread diagnostic view, also available when a composed renderer owns frame delivery. */
public interface RtxptDiagnosticAccess {
    void diagnosticLevel(RtxptDiagnostics.Level level);
    RtxptDiagnostics.Snapshot diagnosticSnapshot();
    /** Acquire on the owner thread after native initialization, then query from
     * a watchdog thread even while submit is busy. The monitor never calls renderer code. */
    default RtxptDiagnostics.Monitor diagnosticMonitor(){throw new UnsupportedOperationException("Concurrent diagnostic monitor unavailable");}
    RtxptDiagnostics.GpuSnapshot diagnosticGpuSnapshot();
    RtxptAccess.SubmitStages submitStages();
    RtxptAccess.PresentationStatus presentationStatus();
    RtxptAccess.RuntimeStatus runtimeStatus();
    /** Cumulative work counters; compare snapshots around the same submission. */
    default RtxptDiagnostics.LightUpdates lightUpdates(){throw new UnsupportedOperationException("Light work counters unavailable");}
    /** Upload resource capacity counters, read on the renderer owner thread without waiting for the GPU. */
    default RtxptDiagnostics.UploadMemory uploadMemory(){throw new UnsupportedOperationException("Upload memory counters unavailable");}
}
