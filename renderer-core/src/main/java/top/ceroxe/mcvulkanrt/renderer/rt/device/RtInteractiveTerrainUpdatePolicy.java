package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;

import java.util.Objects;

/**
 * Pure admission policy for player-visible terrain mutations.
 *
 * <p>The policy distinguishes an exact, nearby block edit from background chunk streaming.
 * Device code owns the persistent target fence and executes the resulting work, while this class
 * makes the urgency decision independently testable and prevents batching heuristics from being
 * interleaved with Vulkan resource lifetime.</p>
 */
final class RtInteractiveTerrainUpdatePolicy {
    private RtInteractiveTerrainUpdatePolicy() {
    }

    static boolean shouldSubmitUrgentWorldSceneBind(
            boolean forceCurrentWorldTlas,
            boolean pendingInteractiveWorldSceneBindUrgency,
            boolean pendingWorldSceneBindPresent
    ) {
        return (forceCurrentWorldTlas || pendingInteractiveWorldSceneBindUrgency) && !pendingWorldSceneBindPresent;
    }

    static boolean shouldPreserveCurrentWorldTlasForAcceptedTerrainUpdate(
            RendererFrameUpdate update,
            long interactiveMutationRadiusSections,
            long maxInteractiveMutationSections
    ) {
        Objects.requireNonNull(update, "update");
        if (!update.commitPlan().hasTerrainWork()) {
            return false;
        }
        SceneUpdateBatch batch = update.batch();
        boolean blockMutationSource = batch.hasBatchBlockMutationSource();
        boolean chunkStreamingSource = batch.hasBatchChunkStreamingSource();
        boolean removalSource = batch.hasBatchSectionRemovalSource() || !batch.removedSections().isEmpty();
        boolean terrainTopologyChanged = blockMutationSource || removalSource;
        if (!terrainTopologyChanged) {
            return false;
        }
        boolean interactiveBlockMutation = isInteractiveBlockMutationBatch(
                batch,
                update.frameState(),
                interactiveMutationRadiusSections,
                maxInteractiveMutationSections
        );
        return shouldForceWorldTlasForTerrainMutation(
                true,
                blockMutationSource,
                chunkStreamingSource,
                interactiveBlockMutation,
                update.backlogSnapshot()
        );
    }

    static boolean shouldForceWorldTlasForTerrainMutation(
            boolean terrainTopologyChanged,
            boolean blockMutationSource,
            boolean chunkStreamingSource,
            boolean interactiveBlockMutation,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog
    ) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        if (!terrainTopologyChanged) {
            return false;
        }
        if (blockMutationSource) {
            /* A target fence, not unrelated streaming backlog, governs a nearby player edit. */
            return interactiveBlockMutation;
        }
        return !rendererBacklog.hasPendingRendererWork() && !chunkStreamingSource;
    }

    static boolean isInteractiveBlockMutationBatch(
            SceneUpdateBatch batch,
            RendererFrameState frameState,
            long radiusSections,
            long maxMutationSections
    ) {
        Objects.requireNonNull(batch, "batch");
        RendererFrameState effectiveFrameState =
                frameState == null ? RendererFrameState.unavailable() : frameState;
        if (!batch.hasBatchBlockMutationSource() || !effectiveFrameState.valid()) {
            return false;
        }
        if (radiusSections < 0L || maxMutationSections <= 0L) {
            throw new IllegalArgumentException("interactive mutation limits must be non-negative and non-zero");
        }

        int blockMutationSections = 0;
        boolean hasNearbyMutation = false;
        for (SectionKey key : batch.sectionSourceFlags().keySet()) {
            if ((batch.sourceFlagsForSection(key) & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) == 0) {
                continue;
            }
            blockMutationSections++;
            if (blockMutationSections > maxMutationSections) {
                return false;
            }
            if (sectionWithinCameraRadius(key, effectiveFrameState, radiusSections)) {
                hasNearbyMutation = true;
            }
        }
        return hasNearbyMutation;
    }

    private static boolean sectionWithinCameraRadius(
            SectionKey key,
            RendererFrameState frameState,
            long radiusSections
    ) {
        int cameraSectionX = blockToSection(frameState.cameraX());
        int cameraSectionY = blockToSection(frameState.cameraY());
        int cameraSectionZ = blockToSection(frameState.cameraZ());
        long dx = Math.abs((long) key.x() - cameraSectionX);
        long dy = Math.abs((long) key.y() - cameraSectionY);
        long dz = Math.abs((long) key.z() - cameraSectionZ);
        return dx <= radiusSections && dy <= radiusSections && dz <= radiusSections;
    }

    private static int blockToSection(double blockCoordinate) {
        return Math.floorDiv((int) Math.floor(blockCoordinate), SectionVoxelSnapshot.SECTION_SIZE);
    }
}
