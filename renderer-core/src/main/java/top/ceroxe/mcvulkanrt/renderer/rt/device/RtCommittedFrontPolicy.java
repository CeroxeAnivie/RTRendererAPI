package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCommitPlan;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Decides whether an immutable, already-published world generation remains safe to render.
 *
 * <p>host extracts camera and entity render state every frame. UE similarly keeps rendering
 * the committed GPUScene generation while dirty resources for its successor are uploaded. A
 * successor request therefore does not invalidate the committed front by itself; only updates
 * that change the visible meaning or membership of that front must suspend continuity.</p>
 */
final class RtCommittedFrontPolicy {
    private RtCommittedFrontPolicy() {
    }

    static Decision classify(
            boolean committedFrontValid,
            boolean hasCommittedWorld,
            boolean successorConvergencePending
    ) {
        if (!committedFrontValid) {
            return Decision.TERRAIN_INVALIDATED;
        }
        if (!hasCommittedWorld) {
            return Decision.NO_COMMITTED_WORLD;
        }
        return successorConvergencePending ? Decision.ELIGIBLE : Decision.CONVERGED;
    }

    static boolean invalidatesCommittedFront(
            RendererFrameUpdate update,
            Set<SectionKey> committedFrontSections
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(committedFrontSections, "committedFrontSections");
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        if (!commitPlan.hasTerrainWork() || committedFrontSections.isEmpty()) {
            return false;
        }
        return invalidatesCommittedFront(
                commitPlan.fullResyncRequested(),
                commitPlan.removedSections(),
                commitPlan.unloadedChunks(),
                committedFrontSections
        );
    }

    static boolean invalidatesCommittedFront(
            boolean fullResync,
            Set<SectionKey> removedSections,
            Set<ChunkKey> unloadedChunks,
            Set<SectionKey> committedFrontSections
    ) {
        Objects.requireNonNull(removedSections, "removedSections");
        Objects.requireNonNull(unloadedChunks, "unloadedChunks");
        Objects.requireNonNull(committedFrontSections, "committedFrontSections");
        if (committedFrontSections.isEmpty()) {
            return false;
        }
        if (fullResync) {
            return true;
        }
        for (SectionKey removed : removedSections) {
            if (committedFrontSections.contains(removed)) {
                return true;
            }
        }
        if (unloadedChunks.isEmpty()) {
            return false;
        }
        for (SectionKey committed : committedFrontSections) {
            if (unloadedChunks.contains(committed.chunkKey())) {
                return true;
            }
        }
        return false;
    }

    static boolean generationIsCurrent(long invalidatedSectionRevision, long boundSectionRevision) {
        if (invalidatedSectionRevision < 0L || boundSectionRevision < 0L) {
            throw new IllegalArgumentException("committed-front section revisions must not be negative");
        }
        return boundSectionRevision >= invalidatedSectionRevision;
    }

    enum Decision {
        ELIGIBLE,
        CONVERGED,
        TERRAIN_INVALIDATED,
        NO_COMMITTED_WORLD,
        DISPATCH_REJECTED
    }
}
