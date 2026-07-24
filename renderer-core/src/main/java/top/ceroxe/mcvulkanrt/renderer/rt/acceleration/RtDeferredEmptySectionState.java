package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/**
 * Owns zero-triangle successors waiting for committed-presentation retention to end.
 *
 * <p>A compiled empty mesh is not always an authoritative removal: streaming can observe an empty
 * successor while the committed front still owns the prior BLAS. Interactive block mutation is
 * different and must remove immediately. Keeping the policy and deferred membership together
 * prevents a caller from inserting a key under one rule and releasing it under another.</p>
 */
final class RtDeferredEmptySectionState {
    private final Set<SectionKey> deferred = new LinkedHashSet<>();

    void defer(SectionKey key) {
        deferred.add(Objects.requireNonNull(key, "key"));
    }

    void resolve(SectionKey key) {
        deferred.remove(Objects.requireNonNull(key, "key"));
    }

    void clear() {
        deferred.clear();
    }

    int size() {
        return deferred.size();
    }

    List<SectionKey> releaseUnretained(Set<SectionKey> retainedPresentationKeys) {
        Objects.requireNonNull(retainedPresentationKeys, "retainedPresentationKeys");
        List<SectionKey> released = new ArrayList<>();
        Iterator<SectionKey> iterator = deferred.iterator();
        while (iterator.hasNext()) {
            SectionKey key = iterator.next();
            if (retainedPresentationKeys.contains(key)) {
                continue;
            }
            iterator.remove();
            released.add(key);
        }
        return List.copyOf(released);
    }

    static boolean shouldDefer(boolean activeBlasPresent, boolean retainedByPresentation, int sourceFlags) {
        return activeBlasPresent
                && retainedByPresentation
                && !interactiveTopologySource(sourceFlags);
    }

    static boolean interactiveTopologySource(int sourceFlags) {
        return (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0;
    }
}
