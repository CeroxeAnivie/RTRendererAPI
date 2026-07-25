package top.ceroxe.rt.renderer.scene;

import java.lang.ref.WeakReference;

/**
 * Immutable entered/exited facts for one publication edge. Keeping this contract separate from
 * membership storage makes transition proofs auditable without exposing or duplicating the full
 * section set.
 */
final class PackedSectionTransition {
    private static final long[] EMPTY = new long[0];
    private final WeakReference<PackedSectionMembership> predecessor;
    private final long[] enteredSections;
    private final long[] exitedSections;

    private PackedSectionTransition(
            PackedSectionMembership predecessor,
            long[] enteredSections,
            long[] exitedSections
    ) {
        // The predecessor identifies the publication edge; it does not own the predecessor.
        // Keeping a strong reference here would turn independently immutable publications into
        // an unbounded history chain. Callers that can use the fast transition proof already own
        // both adjacent publications. If the predecessor has otherwise expired, returning null
        // deliberately falls back to the complete membership proof.
        this.predecessor = predecessor == null ? null : new WeakReference<>(predecessor);
        this.enteredSections = enteredSections == null ? EMPTY : enteredSections;
        this.exitedSections = exitedSections == null ? EMPTY : exitedSections;
    }

    static PackedSectionTransition of(
            PackedSectionMembership predecessor,
            long[] enteredSections,
            long[] exitedSections
    ) {
        return new PackedSectionTransition(
                enteredSections == null || exitedSections == null ? null : predecessor,
                enteredSections,
                exitedSections
        );
    }

    PackedSectionMembership predecessor() {
        return predecessor == null ? null : predecessor.get();
    }

    long[] enteredSections() {
        return enteredSections;
    }

    long[] exitedSections() {
        return exitedSections;
    }
}
