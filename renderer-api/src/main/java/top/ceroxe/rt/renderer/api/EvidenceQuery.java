package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * An immutable query result that distinguishes availability from missing history.
 *
 * <p>OUTSIDE_RETENTION_WINDOW means the provider can no longer answer that identity exactly;
 * it does not claim a skipped sequence or generation was ever submitted. Execution failure is
 * AVAILABLE evidence with its original typed failure reason, never UNKNOWN or UNSUPPORTED.</p>
 */
public record EvidenceQuery<T>(Status status, Optional<T> evidence) {
    public EvidenceQuery {
        status = Objects.requireNonNull(status, "status");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if ((status == Status.AVAILABLE) != evidence.isPresent()) {
            throw new IllegalArgumentException("only available queries carry evidence");
        }
    }

    public static <T> EvidenceQuery<T> available(T evidence) {
        return new EvidenceQuery<>(Status.AVAILABLE, Optional.of(evidence));
    }

    public static <T> EvidenceQuery<T> absent(Status status) {
        return new EvidenceQuery<>(status, Optional.empty());
    }

    public enum Status { AVAILABLE, UNKNOWN, OUTSIDE_RETENTION_WINDOW, UNSUPPORTED }
}
