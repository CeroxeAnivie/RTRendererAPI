package top.ceroxe.rt.renderer.rtxpt;

/** Explicit owner-thread observations of the external adapter boundary. */
public interface RtxptDiagnosticAccess {
    void diagnosticLevel(RtxptDiagnostics.Level level);
    /** afterOrdinal is the last consumed event, initially -1. */
    RtxptDiagnostics.Snapshot diagnosticSnapshot(long afterOrdinal);
}
