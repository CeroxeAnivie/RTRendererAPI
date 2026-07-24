package top.ceroxe.mcvulkanrt.renderer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/** Immutable publication of pending terrain revisions and source flags. */
final class PendingTerrainMetadata {
    private static final PendingTerrainMetadata EMPTY = new PendingTerrainMetadata(Map.of(), Map.of());

    private final Set<SectionKey> sectionKeys;
    private final Map<SectionKey, Long> revisions;
    private final Map<SectionKey, Integer> sourceFlags;

    private PendingTerrainMetadata(
            Map<SectionKey, Long> ownedRevisions,
            Map<SectionKey, Integer> ownedSourceFlags
    ) {
        if (!ownedRevisions.keySet().equals(ownedSourceFlags.keySet())) {
            throw new IllegalArgumentException(
                    "pending terrain revisions and source flags must cover the same immutable membership"
            );
        }
        revisions = Collections.unmodifiableMap(ownedRevisions);
        sourceFlags = Collections.unmodifiableMap(ownedSourceFlags);
        sectionKeys = Collections.unmodifiableSet(ownedSourceFlags.keySet());
    }

    static PendingTerrainMetadata freezeOwned(
            Map<SectionKey, Long> ownedRevisions,
            Map<SectionKey, Integer> ownedSourceFlags
    ) {
        Objects.requireNonNull(ownedRevisions, "ownedRevisions");
        Objects.requireNonNull(ownedSourceFlags, "ownedSourceFlags");
        if (ownedRevisions.isEmpty() && ownedSourceFlags.isEmpty()) {
            return EMPTY;
        }
        return new PendingTerrainMetadata(ownedRevisions, ownedSourceFlags);
    }

    Set<SectionKey> sectionKeys() {
        return sectionKeys;
    }

    Map<SectionKey, Long> revisions() {
        return revisions;
    }

    Map<SectionKey, Integer> sourceFlags() {
        return sourceFlags;
    }

    static PendingTerrainMetadata empty() {
        return EMPTY;
    }
}
