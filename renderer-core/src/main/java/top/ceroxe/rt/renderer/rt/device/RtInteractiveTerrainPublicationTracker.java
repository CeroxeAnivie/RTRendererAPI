package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameCommitPlan;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.SectionLifecycleFlightRecorder;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasCache;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.*;

/**
 * Owns the exact section revisions which an interactive terrain edit must make descriptor-visible.
 *
 * <p>This state outlives a frame and therefore cannot belong to a local scheduling decision. It is
 * cleared only by full resynchronization or by observing the required content in a committed scene
 * publication. Ready-event identity is retained separately so a busy descriptor generation does
 * not flood diagnostics while the same target waits.</p>
 */
final class RtInteractiveTerrainPublicationTracker {
    private final Map<SectionKey, Long> presentTargets = new HashMap<>();
    private final Set<SectionKey> absentTargets = new HashSet<>();
    private final Map<SectionKey, Long> recordedReadyPresentTargets = new HashMap<>();
    private final Set<SectionKey> recordedReadyAbsentTargets = new HashSet<>();
    private boolean urgent;
    private String urgencySource = "none";

    void clear() {
        presentTargets.clear();
        absentTargets.clear();
        recordedReadyPresentTargets.clear();
        recordedReadyAbsentTargets.clear();
    }

    void capture(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        SceneUpdateBatch batch = update.batch();
        RendererFrameCommitPlan plan = update.commitPlan();
        for (Map.Entry<SectionKey, Integer> source : plan.sectionSourceFlags().entrySet()) {
            if ((source.getValue() & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) == 0) {
                continue;
            }
            SectionKey key = source.getKey();
            recordedReadyPresentTargets.remove(key);
            recordedReadyAbsentTargets.remove(key);
            SectionTriangleMesh mesh = plan.sectionMeshes().get(key);
            boolean removed = plan.removedSections().contains(key);
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_INTERACTIVE_TARGET_CAPTURED,
                    SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION,
                    mesh != null || removed
                            ? SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED
                            : SectionLifecycleFlightRecorder.OUTCOME_RETRY_REQUIRED,
                    key,
                    0,
                    batch.totalBlockMutationMarks(),
                    plan.sectionContentRevisions().getOrDefault(key, -1L),
                    -1L,
                    source.getValue(),
                    pendingTargetCount(),
                    mesh == null ? -1L : mesh.triangleCount()
            );
            if (mesh == null) {
                continue;
            }
            if (mesh.triangleCount() == 0) {
                presentTargets.remove(key);
                absentTargets.add(key);
            } else {
                Long contentRevision = plan.sectionContentRevisions().get(key);
                if (contentRevision == null) {
                    throw new IllegalStateException("interactive terrain mesh has no content revision: " + key);
                }
                absentTargets.remove(key);
                presentTargets.put(key, contentRevision);
            }
        }
        for (SectionKey key : plan.removedSections()) {
            if ((batch.sourceFlagsForSection(key) & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0) {
                presentTargets.remove(key);
                absentTargets.add(key);
            }
        }
    }

    boolean hasPendingTargets() {
        return !presentTargets.isEmpty() || !absentTargets.isEmpty();
    }

    boolean promoteIfReady(RtSectionBlasCache sectionBlasCache, RtScenePublication publication) {
        if (!hasPendingTargets()) {
            return false;
        }
        boolean ready = sectionBlasCache.matchesActiveContentTargets(presentTargets, absentTargets);
        if (!ready) {
            return false;
        }
        int pendingTargets = pendingTargetCount();
        for (Map.Entry<SectionKey, Long> entry : presentTargets.entrySet()) {
            Long recordedRevision = recordedReadyPresentTargets.get(entry.getKey());
            if (recordedRevision != null && recordedRevision.longValue() == entry.getValue()) {
                continue;
            }
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_INTERACTIVE_ACTIVE_READY,
                    SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    entry.getKey(), 0, -1L, entry.getValue(), publication.generation(),
                    SceneUpdateBatch.SOURCE_BLOCK_MUTATION, pendingTargets, 1L
            );
            recordedReadyPresentTargets.put(entry.getKey(), entry.getValue());
        }
        for (SectionKey key : absentTargets) {
            if (!recordedReadyAbsentTargets.add(key)) {
                continue;
            }
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_INTERACTIVE_ACTIVE_READY,
                    SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    key, 0, -1L, -1L, publication.generation(),
                    SceneUpdateBatch.SOURCE_BLOCK_MUTATION, pendingTargets, 0L
            );
        }
        markUrgent("interactiveTerrainTargetsReady");
        return true;
    }

    void settle(RtScenePublication publication) {
        Map<SectionKey, Long> boundContent = publication.worldSectionContentRevisions();
        int pendingTargets = pendingTargetCount();
        var presentIterator = presentTargets.entrySet().iterator();
        while (presentIterator.hasNext()) {
            Map.Entry<SectionKey, Long> entry = presentIterator.next();
            if (boundContent.getOrDefault(entry.getKey(), -1L) < entry.getValue()) {
                continue;
            }
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_INTERACTIVE_PRESENTED,
                    SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    entry.getKey(), 0, publication.generation(), entry.getValue(), publication.worldViewRevision(),
                    SceneUpdateBatch.SOURCE_BLOCK_MUTATION, pendingTargets, publication.worldSectionKeys().size()
            );
            recordedReadyPresentTargets.remove(entry.getKey());
            presentIterator.remove();
        }
        var absentIterator = absentTargets.iterator();
        while (absentIterator.hasNext()) {
            SectionKey key = absentIterator.next();
            if (publication.worldSectionKeys().contains(key)) {
                continue;
            }
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_INTERACTIVE_PRESENTED,
                    SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    key, 0, publication.generation(), -1L, publication.worldViewRevision(),
                    SceneUpdateBatch.SOURCE_BLOCK_MUTATION, pendingTargets, publication.worldSectionKeys().size()
            );
            recordedReadyAbsentTargets.remove(key);
            absentIterator.remove();
        }
        if (!hasPendingTargets()) {
            clearUrgency();
        }
    }

    void markUrgent(String source) {
        urgent = true;
        urgencySource = Objects.requireNonNull(source, "source");
    }

    void clearUrgency() {
        urgent = false;
    }

    boolean urgent() {
        return urgent;
    }

    String urgencySource() {
        return urgencySource;
    }

    int presentTargetCount() {
        return presentTargets.size();
    }

    int absentTargetCount() {
        return absentTargets.size();
    }

    private int pendingTargetCount() {
        return Math.addExact(presentTargets.size(), absentTargets.size());
    }
}
