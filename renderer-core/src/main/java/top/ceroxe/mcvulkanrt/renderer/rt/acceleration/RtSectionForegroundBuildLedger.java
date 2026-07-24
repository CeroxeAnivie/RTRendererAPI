package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Owns durable foreground build requirements and their bounded fair recovery order.
 *
 * <p>The membership set is authoritative; the deque is only a round-robin cursor.  Keeping both
 * inside one owner prevents stale queue entries, duplicate recovery candidates, and accidental
 * full-successor rescans when the pending-build queue has only a few free slots.</p>
 */
final class RtSectionForegroundBuildLedger {
    private final Set<SectionKey> required = new LinkedHashSet<>();
    private final ArrayDeque<SectionKey> recovery = new ArrayDeque<>();

    void reconcile(PackedSectionMembership authoritative, Predicate<SectionKey> requiresBuild) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(requiresBuild, "requiresBuild");
        required.retainAll(authoritative);
        recovery.removeIf(key -> !required.contains(key));
        for (SectionKey key : authoritative) {
            if (!requiresBuild.test(key)) {
                required.remove(key);
                continue;
            }
            /*
             * Set.add returning false means this key is still an outstanding member, not that its
             * work completed. The previous nested else removed every surviving key on its second
             * reconciliation and then pruned the recovery cursor, leaving source-owned foreground
             * sections in no queued/recording/GPU stage. Only the requiresBuild predicate may
             * discharge the durable requirement.
             */
            if (required.add(key)) {
                recovery.addLast(key);
            }
        }
        recovery.removeIf(key -> !required.contains(key));
    }

    SectionKey pollRecoveryCandidate() {
        while (!recovery.isEmpty()) {
            SectionKey key = recovery.removeFirst();
            if (required.contains(key)) {
                return key;
            }
        }
        return null;
    }

    void complete(SectionKey key) {
        required.remove(Objects.requireNonNull(key, "key"));
    }

    void defer(SectionKey key) {
        Objects.requireNonNull(key, "key");
        if (!required.contains(key)) {
            throw new IllegalStateException("cannot defer non-required foreground section " + key);
        }
        recovery.addLast(key);
    }

    int inspectionBudget(int maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("foreground recovery inspection limit must not be negative");
        }
        return Math.min(maximum, recovery.size());
    }

    boolean hasRecoveryWork() {
        return !required.isEmpty() && !recovery.isEmpty();
    }

    int size() {
        return required.size();
    }

    void clear() {
        required.clear();
        recovery.clear();
    }
}
