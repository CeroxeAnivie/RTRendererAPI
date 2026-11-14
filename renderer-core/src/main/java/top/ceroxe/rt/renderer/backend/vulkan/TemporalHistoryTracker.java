package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.HistoryResetReason;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;

import java.util.*;

/**
 * Transactional temporal source state; rejected submissions never advance it.
 */
final class TemporalHistoryTracker {
    private final boolean enabled;
    private final EnumSet<HistoryInvalidationReason> pendingSceneInvalidations =
            EnumSet.noneOf(HistoryInvalidationReason.class);
    private final Map<Long, SceneInstance.Mobility> instanceMobility = new HashMap<>();

    private RenderFrameRequest sourceFrame;
    private long sourceSceneRevision = -1L;
    private long generation = -1L;

    TemporalHistoryTracker(TemporalRenderingOptions options) {
        this(options, false);
    }

    TemporalHistoryTracker(TemporalRenderingOptions options, boolean featureProvenanceRequired) {
        enabled = Objects.requireNonNull(options, "options").enabled() || featureProvenanceRequired;
    }

    private static boolean changed(VulkanSceneResidency.DomainChange<?> change) {
        VulkanSceneResidency.DomainUpdateStatistics statistics = change.statistics();
        return statistics.writes() != 0 || statistics.removals() != 0 || statistics.clears() != 0;
    }

    private static boolean sameProjection(CameraState first, CameraState second) {
        if (first.projectionPath() != second.projectionPath()) return false;
        if (first.hasExactProjection()) return first.exactProjection().equals(second.exactProjection());
        return Float.floatToIntBits(first.tanHalfFovX()) == Float.floatToIntBits(second.tanHalfFovX())
                && Float.floatToIntBits(first.tanHalfFovY()) == Float.floatToIntBits(second.tanHalfFovY());
    }

    private static boolean sameShadingState(RenderFrameRequest first, RenderFrameRequest second) {
        return first.environment().equals(second.environment())
                && first.lightmap().revision() == second.lightmap().revision()
                && first.fog().equals(second.fog())
                && first.textureSampling().equals(second.textureSampling())
                && first.antiAliasing().equals(second.antiAliasing());
    }

    private static Set<HistoryInvalidationReason> immutableCopy(
            EnumSet<HistoryInvalidationReason> reasons
    ) {
        return reasons.isEmpty() ? Set.of() : Set.copyOf(reasons);
    }

    PreparedFrame prepare(RenderFrameRequest request, long sceneRevision) {
        RenderFrameRequest checked = Objects.requireNonNull(request, "request");
        if (sceneRevision < checked.minimumSceneRevision()) {
            throw new IllegalArgumentException("scene revision does not satisfy frame minimum");
        }
        EnumSet<HistoryInvalidationReason> invalidations =
                EnumSet.noneOf(HistoryInvalidationReason.class);
        if (!enabled) {
            return new PreparedFrame(
                    sourceFrame, sourceSceneRevision, checked, sceneRevision,
                    false, false, -1L, Set.of()
            );
        }

        if (sourceFrame == null) {
            invalidations.add(HistoryInvalidationReason.FIRST_FRAME);
        } else {
            if (checked.sequence() != Math.addExact(sourceFrame.sequence(), 1L)) {
                invalidations.add(HistoryInvalidationReason.FRAME_SEQUENCE_DISCONTINUITY);
            }
            if (checked.width() != sourceFrame.width() || checked.height() != sourceFrame.height()) {
                invalidations.add(HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED);
            }
            if (!sameProjection(checked.camera(), sourceFrame.camera())
                    || !checked.depthProjection().equals(sourceFrame.depthProjection())) {
                invalidations.add(HistoryInvalidationReason.CAMERA_PROJECTION_CHANGED);
            }
            if (!sameShadingState(checked, sourceFrame)) {
                invalidations.add(HistoryInvalidationReason.FRAME_SHADING_CHANGED);
            }
        }
        invalidations.addAll(pendingSceneInvalidations);
        for (HistoryResetReason reason : checked.temporalHistoryResets()) {
            invalidations.add(switch (reason) {
                case CAMERA_CUT -> HistoryInvalidationReason.CAMERA_CUT;
                case SCENE_DISCONTINUITY -> HistoryInvalidationReason.SCENE_DISCONTINUITY;
                case EXPLICIT_RESET -> HistoryInvalidationReason.EXPLICIT_RESET;
            });
        }
        long preparedGeneration = invalidations.isEmpty()
                ? generation
                : Math.incrementExact(generation);
        return new PreparedFrame(
                sourceFrame,
                sourceSceneRevision,
                checked,
                sceneRevision,
                true,
                invalidations.isEmpty(),
                preparedGeneration,
                immutableCopy(invalidations)
        );
    }

    void commit(PreparedFrame prepared) {
        PreparedFrame checked = Objects.requireNonNull(prepared, "prepared");
        if (checked.expectedSource() != sourceFrame) {
            throw new IllegalStateException("temporal frame was prepared against a stale source");
        }
        if (checked.expectedSourceSceneRevision() != sourceSceneRevision) {
            throw new IllegalStateException("temporal frame was prepared against a stale scene source");
        }
        sourceFrame = checked.request();
        sourceSceneRevision = checked.sceneRevision();
        generation = checked.generation();
        pendingSceneInvalidations.clear();
    }

    /**
     * Invalidates the next prepared frame without advancing the committed source frame.
     * A discarded feature recording must not be treated as a valid temporal sample on retry.
     */
    void invalidate(HistoryInvalidationReason reason) {
        pendingSceneInvalidations.add(Objects.requireNonNull(reason, "reason"));
    }

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        VulkanSceneResidency.SceneChangeSet checked = Objects.requireNonNull(changes, "changes");
        if (!enabled) return;
        boolean staticInstanceHistoryChanged = updateInstanceMobility(checked);
        if (checked.reset() || changed(checked.meshes()) || staticInstanceHistoryChanged) {
            pendingSceneInvalidations.add(HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED);
        }
        if (changed(checked.textures())
                || changed(checked.materials())
                || changed(checked.lights())) {
            pendingSceneInvalidations.add(HistoryInvalidationReason.SCENE_CONTENT_CHANGED);
        }
    }

    private boolean updateInstanceMobility(VulkanSceneResidency.SceneChangeSet changes) {
        boolean staticHistoryChanged = false;
        if (changes.reset()) {
            staticHistoryChanged = !instanceMobility.isEmpty()
                    || changes.instances().statistics().writes() != 0;
            instanceMobility.clear();
        } else {
            for (long removedId : changes.instances().removedIdentities()) {
                SceneInstance.Mobility removed = instanceMobility.remove(removedId);
                if (removed != SceneInstance.Mobility.DYNAMIC) staticHistoryChanged = true;
            }
            if (changes.instances().statistics().clears()
                    > changes.instances().statistics().removals()) {
                staticHistoryChanged = true;
            }
        }
        for (StableIdentitySlots.SlotWrite<SceneInstance> write : changes.instances().writes()) {
            SceneInstance.Mobility previous = instanceMobility.put(write.id(), write.value().mobility());
            if (write.value().mobility() == SceneInstance.Mobility.STATIC
                    || previous == SceneInstance.Mobility.STATIC) {
                staticHistoryChanged = true;
            }
        }
        return staticHistoryChanged;
    }

    record PreparedFrame(
            RenderFrameRequest expectedSource,
            long expectedSourceSceneRevision,
            RenderFrameRequest request,
            long sceneRevision,
            boolean provenanceTracked,
            boolean historyValid,
            long generation,
            Set<HistoryInvalidationReason> invalidations
    ) {
        PreparedFrame {
            request = Objects.requireNonNull(request, "request");
            invalidations = Set.copyOf(Objects.requireNonNull(invalidations, "invalidations"));
            if (expectedSourceSceneRevision < -1L || sceneRevision < 0L || generation < -1L) {
                throw new IllegalArgumentException("temporal generation metadata is out of range");
            }
            if (historyValid && !invalidations.isEmpty()) {
                throw new IllegalArgumentException("valid history cannot carry invalidation reasons");
            }
            if (historyValid && !provenanceTracked) {
                throw new IllegalArgumentException("valid history requires tracked temporal provenance");
            }
        }

        CameraState previousCamera() {
            return expectedSource == null ? request.camera() : expectedSource.camera();
        }

        long previousSequence() {
            return expectedSource == null ? request.sequence() : expectedSource.sequence();
        }

        top.ceroxe.rt.renderer.api.DepthProjectionState previousDepthProjection() {
            return expectedSource == null ? request.depthProjection() : expectedSource.depthProjection();
        }

        long previousSceneRevision() {
            return expectedSource == null ? sceneRevision : expectedSourceSceneRevision;
        }
    }
}
