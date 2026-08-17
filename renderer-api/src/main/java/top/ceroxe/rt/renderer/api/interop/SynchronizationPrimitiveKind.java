package top.ceroxe.rt.renderer.api.interop;

/** Portable signal behavior of an externally shared synchronization primitive. */
public enum SynchronizationPrimitiveKind {
    /** Single transition carrying no counter value. */
    BINARY,
    /** Monotonically increasing unsigned counter represented in the non-negative Java range. */
    TIMELINE
}
