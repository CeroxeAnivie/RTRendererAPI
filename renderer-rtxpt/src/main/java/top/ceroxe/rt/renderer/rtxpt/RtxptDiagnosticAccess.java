package top.ceroxe.rt.renderer.rtxpt;

/** Explicit owner-thread observations of the external adapter boundary. */
public interface RtxptDiagnosticAccess {
    /**
     * Sets the detail level for adapter diagnostics.
     * @param level diagnostic detail to record
     */
    void diagnosticLevel(RtxptDiagnostics.Level level);
    /**
     * Reads events after the supplied cursor. The cursor is the last consumed
     * event ordinal and is {@code -1} for the initial read.
     * @param afterOrdinal last consumed event ordinal, or {@code -1} initially
     * @return an immutable snapshot and the cursor for the next read
     */
    RtxptDiagnostics.Snapshot diagnosticSnapshot(long afterOrdinal);
    /**
     * Acquires a non-blocking diagnostic reader on the renderer owner thread.
     * The returned reader may be polled from any thread, including while submit waits.
     * @return a reader with an independent event cursor
     */
    RtxptDiagnostics.Monitor diagnosticMonitor();
    /**
     * Acquires a reader for native adapter stages after renderer initialization.
     * It does not expose probes inside the upstream RTXPT core.
     * @return a reader for native adapter boundary events
     */
    RtxptDiagnostics.NativeMonitor nativeDiagnosticMonitor();
}
