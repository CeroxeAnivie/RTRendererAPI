package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.List;
import java.util.Objects;

/** No installed backend can satisfy the requested immutable renderer configuration. */
public final class RendererUnavailableException extends RendererException {
    private final List<BackendAttempt> attempts;

    public RendererUnavailableException(String message, List<BackendAttempt> attempts) {
        super(message);
        this.attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
    }

    public List<BackendAttempt> attempts() {
        return attempts;
    }

    public record BackendAttempt(String providerId, String compatibility, String reason) {
        public BackendAttempt {
            providerId = requireText(providerId, "providerId");
            compatibility = requireText(compatibility, "compatibility");
            reason = requireText(reason, "reason");
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
