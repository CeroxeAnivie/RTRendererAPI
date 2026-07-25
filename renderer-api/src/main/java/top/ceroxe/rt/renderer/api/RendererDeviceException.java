package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Stable device-failure envelope suitable for automated recovery policy.
 *
 * <p>The native result is retained as evidence, while {@link Reason} and
 * {@link RecoveryAction} keep callers independent of a backend's numeric error space.</p>
 */
public final class RendererDeviceException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Stable failure classification.
     */
    private final Reason reason;
    /**
     * Recommended caller recovery policy.
     */
    private final RecoveryAction recoveryAction;
    /**
     * Native operation that observed the failure.
     */
    private final String operation;
    /**
     * Native result retained as diagnostic evidence.
     */
    private final int nativeResult;

    /**
     * Creates a stable device-failure envelope.
     *
     * @param message        human-readable failure summary
     * @param reason         backend-independent failure classification
     * @param recoveryAction recommended caller recovery action
     * @param operation      non-blank operation that observed the failure
     * @param nativeResult   backend-native result retained as diagnostic evidence
     */
    public RendererDeviceException(
            String message,
            Reason reason,
            RecoveryAction recoveryAction,
            String operation,
            int nativeResult
    ) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.recoveryAction = Objects.requireNonNull(recoveryAction, "recoveryAction");
        this.operation = requireText(operation, "operation");
        this.nativeResult = nativeResult;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /**
     * Returns the failure classification.
     *
     * @return backend-independent failure classification
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the recovery policy.
     *
     * @return recommended caller recovery action
     */
    public RecoveryAction recoveryAction() {
        return recoveryAction;
    }

    /**
     * Returns the failed operation.
     *
     * @return non-blank operation that observed the failure
     */
    public String operation() {
        return operation;
    }

    /**
     * Returns the native result.
     *
     * @return backend-native result retained as diagnostic evidence
     */
    public int nativeResult() {
        return nativeResult;
    }

    /**
     * Backend-independent device failure classification.
     */
    public enum Reason {
        /**
         * The logical device became unusable.
         */
        DEVICE_LOST,
        /**
         * Device-local memory allocation failed.
         */
        DEVICE_OUT_OF_MEMORY,
        /**
         * Host memory allocation failed while serving the backend.
         */
        HOST_OUT_OF_MEMORY,
        /**
         * The driver reported an otherwise unclassified terminal failure.
         */
        DRIVER_FAILURE
    }

    /**
     * Recommended high-level recovery policy.
     */
    public enum RecoveryAction {
        /**
         * The renderer recreated its device; retry the rejected operation unchanged.
         */
        RETRY_OPERATION,
        /**
         * Close the failed renderer and create a new instance.
         */
        RECREATE_RENDERER,
        /**
         * Reduce workload or memory pressure before recreating the renderer.
         */
        REDUCE_MEMORY_AND_RECREATE,
        /**
         * Stop rendering because automated recovery is unsafe.
         */
        ABORT
    }
}
