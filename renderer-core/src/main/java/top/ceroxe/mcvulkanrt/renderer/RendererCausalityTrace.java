package top.ceroxe.mcvulkanrt.renderer;

import java.util.List;
import java.util.Objects;

/** Immutable bounded lookup result for one submission trace identity. */
public record RendererCausalityTrace(
        long traceId,
        long eventCount,
        int lastStage,
        long lastPublicationGeneration,
        List<StageEvidence> retainedStages,
        long eventsOutsideWindow,
        boolean available,
        String reason
) {
    public RendererCausalityTrace {
        if (traceId < -1L || eventCount < 0L || lastPublicationGeneration < -1L
                || eventsOutsideWindow < 0L || eventsOutsideWindow > eventCount) {
            throw new IllegalArgumentException("causality trace values are invalid");
        }
        retainedStages = List.copyOf(Objects.requireNonNull(retainedStages, "retainedStages"));
        if (retainedStages.size() + eventsOutsideWindow != eventCount) {
            throw new IllegalArgumentException("retained and omitted causality events must equal the event count");
        }
        reason = reason == null ? "" : reason;
        if (!available && (eventCount != 0L || !retainedStages.isEmpty() || eventsOutsideWindow != 0L)) {
            throw new IllegalArgumentException("unavailable causality trace must not claim events");
        }
    }

    public static RendererCausalityTrace unavailable(long traceId, String reason) {
        return new RendererCausalityTrace(traceId, 0L, -1, -1L, List.of(), 0L, false, reason);
    }

    /** One retained edge from the preallocated per-trace diagnostic window. */
    public record StageEvidence(
            long ordinal,
            int stage,
            String stageName,
            long publicationGeneration
    ) {
        public StageEvidence {
            if (ordinal <= 0L || stage < 0 || publicationGeneration < -1L) {
                throw new IllegalArgumentException("causality stage evidence is invalid");
            }
            stageName = Objects.requireNonNull(stageName, "stageName");
        }
    }
}
