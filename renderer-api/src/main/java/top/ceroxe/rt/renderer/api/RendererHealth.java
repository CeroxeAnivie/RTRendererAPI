package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable operational health and resource-debt snapshot for one renderer instance.
 *
 * <p>Unlike exception messages, this value is suitable for automated retry, shutdown and
 * telemetry policy. It never retains a {@link Throwable}, native resource or unbounded history.</p>
 *
 * @param status        renderer lifecycle state at snapshot time
 * @param activeFailure typed active failure, when the renderer is degraded
 * @param obligations   bounded externally visible resource and recovery debt
 */
public record RendererHealth(
        RayTracingRenderer.Status status,
        Optional<Failure> activeFailure,
        ResourceObligations obligations
) {
    /**
     * Validates and creates an operational health snapshot.
     *
     * @param status        renderer lifecycle state at snapshot time
     * @param activeFailure typed active failure, when present
     * @param obligations   immutable resource and recovery debt
     */
    public RendererHealth {
        status = Objects.requireNonNull(status, "status");
        activeFailure = Objects.requireNonNull(activeFailure, "activeFailure");
        obligations = Objects.requireNonNull(obligations, "obligations");
        if ((status == RayTracingRenderer.Status.FAILED || status == RayTracingRenderer.Status.RECOVERING)
                && activeFailure.isEmpty()) {
            throw new IllegalArgumentException("failed or recovering renderer health requires an active failure");
        }
        if (status == RayTracingRenderer.Status.READY && activeFailure.isPresent()) {
            throw new IllegalArgumentException("ready renderer health must not report an active failure");
        }
        if (status == RayTracingRenderer.Status.RECOVERING && !obligations.deviceRecoveryPending()) {
            throw new IllegalArgumentException("recovering renderer health requires pending device recovery");
        }
    }

    /**
     * Stable renderer failure categories for policy and telemetry.
     */
    public enum Kind {
        /**
         * Provider did not expose a more precise category.
         */
        UNCLASSIFIED,
        /**
         * Vulkan logical device loss.
         */
        DEVICE_LOST,
        /**
         * Device-local memory exhaustion.
         */
        DEVICE_OUT_OF_MEMORY,
        /**
         * Host memory exhaustion while serving the backend.
         */
        HOST_OUT_OF_MEMORY,
        /**
         * Driver failure outside the more specific categories.
         */
        DRIVER_FAILURE,
        /**
         * Backend state or admission result violated the renderer contract.
         */
        BACKEND_FAILURE,
        /**
         * Renderer-owned native resources could not yet be closed.
         */
        RESOURCE_CLEANUP_FAILURE,
        /**
         * Automatic device recreation failed.
         */
        RECOVERY_FAILURE
    }

    /**
     * Typed, bounded failure evidence without an exception object or backend-specific stack trace.
     *
     * @param kind           stable failure category
     * @param recoveryAction recommended caller policy
     * @param operation      non-blank operation that observed the failure
     * @param nativeResult   backend-native result when one exists
     */
    public record Failure(
            Kind kind,
            RendererDeviceException.RecoveryAction recoveryAction,
            String operation,
            OptionalInt nativeResult
    ) {
        /**
         * Validates and creates bounded failure evidence.
         *
         * @param kind           stable failure category
         * @param recoveryAction recommended caller policy
         * @param operation      non-blank operation that observed the failure
         * @param nativeResult   backend-native result when one exists
         */
        public Failure {
            kind = Objects.requireNonNull(kind, "kind");
            recoveryAction = Objects.requireNonNull(recoveryAction, "recoveryAction");
            operation = Objects.requireNonNull(operation, "operation");
            nativeResult = Objects.requireNonNull(nativeResult, "nativeResult");
            if (operation.isBlank()) {
                throw new IllegalArgumentException("failure operation must not be blank");
            }
        }
    }

    /**
     * Bounded ownership obligations that may delay shutdown or device recreation.
     *
     * @param outstandingGpuFrameLeases consumer-owned GPU frame leases not yet retired
     * @param nativeCleanupPending      whether renderer-owned native teardown remains incomplete
     * @param deviceRecoveryPending     whether recreation is waiting for obligations to retire
     */
    public record ResourceObligations(
            int outstandingGpuFrameLeases,
            boolean nativeCleanupPending,
            boolean deviceRecoveryPending
    ) {
        /**
         * Validates and creates a resource-obligation snapshot.
         *
         * @param outstandingGpuFrameLeases non-negative external frame-lease count
         * @param nativeCleanupPending      whether native teardown remains incomplete
         * @param deviceRecoveryPending     whether device recreation remains pending
         */
        public ResourceObligations {
            if (outstandingGpuFrameLeases < 0) {
                throw new IllegalArgumentException("outstanding GPU frame leases must not be negative");
            }
            if (deviceRecoveryPending && !nativeCleanupPending) {
                throw new IllegalArgumentException("device recovery debt requires pending native cleanup");
            }
        }

        /**
         * Returns the canonical snapshot with no resource or recovery debt.
         *
         * @return immutable zero-debt snapshot
         */
        public static ResourceObligations none() {
            return new ResourceObligations(0, false, false);
        }
    }
}
