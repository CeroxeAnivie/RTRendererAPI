package top.ceroxe.rt.renderer.api;

import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider;

import java.util.List;
import java.util.Objects;

/**
 * No installed backend can satisfy the requested immutable renderer configuration.
 */
public final class RendererUnavailableException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Immutable provider attempts in deterministic selection order.
     */
    private final java.util.ArrayList<BackendAttempt> attempts;

    /**
     * Creates an aggregate provider-selection failure.
     *
     * @param message  human-readable failure summary
     * @param attempts provider attempts in deterministic selection order
     */
    public RendererUnavailableException(String message, List<BackendAttempt> attempts) {
        super(message);
        this.attempts = new java.util.ArrayList<>(List.copyOf(Objects.requireNonNull(attempts, "attempts")));
    }

    /**
     * Returns immutable provider attempts in selection order.
     *
     * @return immutable attempt list
     */
    public List<BackendAttempt> attempts() {
        return List.copyOf(attempts);
    }

    /**
     * Diagnostic result from one provider probe.
     */
    public static final class BackendAttempt implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * Stable provider identifier.
         */
        private final String providerId;
        /**
         * Strongly typed probe compatibility classification.
         */
        private final RayTracingBackendProvider.Compatibility compatibility;
        /**
         * Bounded human-readable probe diagnostic.
         */
        private final String reason;

        private BackendAttempt(
                String providerId,
                RayTracingBackendProvider.Compatibility compatibility,
                String reason
        ) {
            this.providerId = requireText(providerId, "providerId");
            this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
            this.reason = requireText(reason, "reason");
        }

        /**
         * Creates strongly typed diagnostic evidence from one provider probe.
         *
         * @param providerId    non-blank provider identifier
         * @param compatibility non-null compatibility classification
         * @param reason        non-blank diagnostic reason
         * @return immutable typed provider-attempt evidence
         */
        public static BackendAttempt of(
                String providerId,
                RayTracingBackendProvider.Compatibility compatibility,
                String reason
        ) {
            return new BackendAttempt(providerId, compatibility, reason);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        /**
         * Returns the stable provider identifier.
         *
         * @return non-blank provider identifier
         */
        public String providerId() {
            return providerId;
        }

        /**
         * Returns typed compatibility classification.
         *
         * @return non-null compatibility classification
         */
        public RayTracingBackendProvider.Compatibility compatibility() {
            return compatibility;
        }

        /**
         * Returns bounded diagnostic reason.
         *
         * @return non-blank diagnostic reason
         */
        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BackendAttempt attempt)) return false;
            return providerId.equals(attempt.providerId)
                    && compatibility == attempt.compatibility
                    && reason.equals(attempt.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(providerId, compatibility, reason);
        }

        @Override
        public String toString() {
            return "BackendAttempt[providerId=" + providerId + ", compatibility=" + compatibility
                    + ", reason=" + reason + ']';
        }
    }
}
