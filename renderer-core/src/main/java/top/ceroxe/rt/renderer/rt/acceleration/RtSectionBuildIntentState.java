package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the desired revision, causality, and source flags for each section build generation.
 *
 * <p>These values describe one request and must never be updated in three independent maps.  An
 * immutable intent is replaced atomically, then copied into queue/recording/GPU ownership so a
 * stale result can always be attributed to the exact request which produced it.</p>
 */
final class RtSectionBuildIntentState {
    private final Map<SectionKey, Intent> intents = new HashMap<>();

    Intent publish(
            SectionKey key,
            Long requestedRevision,
            RendererFrameCausality causality,
            int sourceFlags
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(causality, "causality");
        Intent previous = intents.get(key);
        long revision = requestedRevision != null
                ? requestedRevision
                : previous == null ? 0L : previous.contentRevision();
        Intent next = new Intent(revision, causality, sourceFlags);
        intents.put(key, next);
        return next;
    }

    Intent intent(SectionKey key) {
        return intents.get(Objects.requireNonNull(key, "key"));
    }

    Intent require(SectionKey key) {
        Intent intent = intent(key);
        if (intent == null) {
            throw new IllegalStateException("section build intent is missing for " + key);
        }
        return intent;
    }

    Long revision(SectionKey key) {
        Intent intent = intent(key);
        return intent == null ? null : intent.contentRevision();
    }

    long revisionOrDefault(SectionKey key, long fallback) {
        Intent intent = intent(key);
        return intent == null ? fallback : intent.contentRevision();
    }

    RendererFrameCausality causality(SectionKey key) {
        Intent intent = intent(key);
        return intent == null ? null : intent.causality();
    }

    int sourceFlagsOrDefault(SectionKey key, int fallback) {
        Intent intent = intent(key);
        return intent == null ? fallback : intent.sourceFlags();
    }

    void remove(SectionKey key) {
        intents.remove(Objects.requireNonNull(key, "key"));
    }

    void clear() {
        intents.clear();
    }

    record Intent(
            long contentRevision,
            RendererFrameCausality causality,
            int sourceFlags
    ) {
        Intent {
            if (contentRevision < 0L) {
                throw new IllegalArgumentException("section build content revision must not be negative");
            }
            Objects.requireNonNull(causality, "causality");
        }
    }
}
