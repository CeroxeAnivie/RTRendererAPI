package top.ceroxe.rt.renderer;

import java.util.List;
import java.util.Objects;

/**
 * Immutable bounded lookup result for one submission trace identity.
 *
 * @param traceId                   queried trace identifier, or {@code -1} when unavailable
 * @param eventCount                total event count, including events outside the retained window
 * @param lastStage                 last observed numeric stage
 * @param lastPublicationGeneration last observed publication generation, or {@code -1}
 * @param retainedStages            immutable evidence retained by the bounded diagnostic window
 * @param eventsOutsideWindow       number of events omitted from the bounded window
 * @param available                 whether the requested trace was available
 * @param reason                    diagnostic reason, or an empty string
 */
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
    /**
     * Validates the bounded-window accounting and freezes retained evidence.
     */
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

    /**
     * Creates an unavailable trace result without retained events.
     *
     * @param traceId queried trace identifier, or {@code -1}
     * @param reason  diagnostic reason
     * @return unavailable trace result
     */
    public static RendererCausalityTrace unavailable(long traceId, String reason) {
        return new RendererCausalityTrace(traceId, 0L, -1, -1L, List.of(), 0L, false, reason);
    }

    /**
     * One retained edge from the preallocated per-trace diagnostic window.
     *
     * @param ordinal               positive per-trace event ordinal
     * @param stage                 non-negative numeric lifecycle stage
     * @param stageName             stable diagnostic stage name
     * @param publicationGeneration associated publication generation, or {@code -1}
     */
    public record StageEvidence(
            long ordinal,
            int stage,
            String stageName,
            long publicationGeneration
    ) {
        /**
         * Validates ordinal, stage and publication-generation bounds.
         */
        public StageEvidence {
            if (ordinal <= 0L || stage < 0 || publicationGeneration < -1L) {
                throw new IllegalArgumentException("causality stage evidence is invalid");
            }
            stageName = Objects.requireNonNull(stageName, "stageName");
        }
    }
}
