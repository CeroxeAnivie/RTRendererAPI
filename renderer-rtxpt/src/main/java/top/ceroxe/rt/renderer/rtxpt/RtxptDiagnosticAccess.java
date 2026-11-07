package top.ceroxe.rt.renderer.rtxpt;

/** Explicit owner-thread observations of the external adapter boundary. */
public interface RtxptDiagnosticAccess {
    void diagnosticLevel(RtxptDiagnostics.Level level);
    /** afterOrdinal is the last consumed event, initially -1. */
    RtxptDiagnostics.Snapshot diagnosticSnapshot(long afterOrdinal);
    /** Acquire on the owner, then poll from any thread, including while submit waits. */
    RtxptDiagnostics.Monitor diagnosticMonitor();
    /** Native adapter stages only; acquire after initialization. No upstream core probes. */
    RtxptDiagnostics.NativeMonitor nativeDiagnosticMonitor();
}
