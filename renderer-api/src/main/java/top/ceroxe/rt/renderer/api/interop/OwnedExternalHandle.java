package top.ceroxe.rt.renderer.api.interop;

/**
 * Linearizable owner of one exported operating-system handle.
 *
 * <p>The importer calls {@link #markImported()} only after native import succeeds. Failed import
 * leaves the handle in {@link ExternalHandleState#EXPORTED}, so {@link #close()} releases it. For
 * an import-consuming type, successful import transfers native ownership and closing the Java
 * owner must not close the consumed handle. Implementations must make every state transition
 * thread-safe and exactly-once.</p>
 *
 * @param <T> memory or synchronization handle-type domain
 */
public interface OwnedExternalHandle<T> extends AutoCloseable {
    /** @return strongly typed external-handle contract */
    T handleType();

    /** @return ownership rule applied by successful import */
    ExternalHandleOwnership ownership();

    /** @return authoritative current ownership state */
    ExternalHandleState state();

    /**
     * Returns the native handle representation while this owner is exported.
     *
     * <p>The value is intentionally not required to be non-zero: descriptor zero is valid for
     * transports based on POSIX file descriptors. Callers must interpret it only according to
     * {@link #handleType()}.</p>
     *
     * @return native integral value or pointer bits
     * @throws IllegalStateException when the state is not {@link ExternalHandleState#EXPORTED}
     */
    long nativeValue();

    /**
     * Records successful native import exactly once.
     *
     * @return {@code true} only for the successful EXPORTED-to-IMPORTED transition
     */
    boolean markImported();

    /** Releases exporter-owned native state without guessing whether import succeeded. */
    @Override
    void close();
}
