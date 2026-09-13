package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.UUID;

/** Exact session-owned native pipeline/SBT generation, issued in command execution evidence. */
public record RayTracingPipelineHandle(UUID sessionId, long generation, String identityDigest) {
    /** Validates the session, positive generation, and SHA-256 declaration identity. */
    public RayTracingPipelineHandle {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(identityDigest, "identityDigest");
        if (generation <= 0 || !identityDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("pipeline handle requires a positive generation and SHA-256 identity");
        }
    }
}
