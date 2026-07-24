package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

/**
 * Owns one submitted section-BLAS batch until every result is applied or released.
 *
 * <p>The cache decides scheduling and publication; this owner retains only the native
 * submission, immutable section metadata, invalidation state, and unconsumed results.
 * Keeping those responsibilities together makes failure cleanup deterministic without
 * exposing native result ownership through the cache's much larger state machine.</p>
 */
final class RtPendingSectionBlasBuild implements AutoCloseable {
    private final RtAccelerationStructure.SectionBlasBuildSubmission submission;
    private final boolean backgroundAdmission;
    private final long sequence;
    private boolean foregroundSubmission;
    private final Map<SectionKey, PendingSection> sections = new LinkedHashMap<>();
    private long retainedEstimatedBytes;
    private long activeTriangleCount;
    private long activeEstimatedBytes;
    private List<RtAccelerationStructure.SectionBlasBuildResult> completedResults = List.of();
    private int nextCompletedResultIndex;
    private boolean closed;

    RtPendingSectionBlasBuild(
            RtAccelerationStructure.SectionBlasBuildSubmission submission,
            RtSectionBlasBuildBatch batch,
            boolean backgroundAdmission,
            boolean foregroundSubmission,
            long sequence
    ) {
        this.submission = Objects.requireNonNull(submission, "submission");
        this.backgroundAdmission = backgroundAdmission;
        this.foregroundSubmission = foregroundSubmission;
        if (sequence < 0L) {
            throw new IllegalArgumentException("pending BLAS sequence must not be negative");
        }
        this.sequence = sequence;
        Objects.requireNonNull(batch, "batch");
        for (RtSectionBlasBuildBatch.Section section : batch.sections()) {
            SectionTriangleMesh mesh = section.mesh();
            RtSectionBlasBuildMetadata metadata = section.metadata();
            PendingSection previous = sections.put(
                    mesh.key(),
                    new PendingSection(
                            mesh,
                            true,
                            metadata.contentRevision(),
                            metadata.sourceFlags(),
                            metadata.material(),
                            metadata.causality()
                    )
            );
            if (previous != null) {
                throw new IllegalStateException("duplicate section in validated BLAS batch: " + mesh.key());
            }
            activeTriangleCount = Math.addExact(activeTriangleCount, mesh.triangleCount());
            activeEstimatedBytes = Math.addExact(activeEstimatedBytes, mesh.estimatedBytes());
            retainedEstimatedBytes = Math.addExact(retainedEstimatedBytes, mesh.estimatedBytes());
        }
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("pending async BLAS build must contain sections");
        }
    }

    RtAccelerationStructure.SectionBlasBuildSubmission submission() {
        requireOpen();
        return submission;
    }

    boolean foregroundSubmission() {
        return foregroundSubmission;
    }

    boolean backgroundAdmission() {
        return backgroundAdmission;
    }

    void boostPriority() {
        requireOpen();
        foregroundSubmission = true;
    }

    void complete(RtAccelerationStructure.CompletedSectionBlasBuild completed) {
        requireOpen();
        Objects.requireNonNull(completed, "completed");
        if (!completedResults.isEmpty() || nextCompletedResultIndex != 0) {
            throw new IllegalStateException("completed async BLAS results were already attached");
        }
        completedResults = completed.results();
        if (completedResults.isEmpty()) {
            throw new IllegalStateException("completed async BLAS build did not contain results");
        }
    }

    boolean hasCompletedResults() {
        return nextCompletedResultIndex < completedResults.size();
    }

    RtAccelerationStructure.SectionBlasBuildResult nextCompletedResult() {
        requireOpen();
        if (!hasCompletedResults()) {
            throw new IllegalStateException("no completed async BLAS result is available");
        }
        return completedResults.get(nextCompletedResultIndex);
    }

    void markCompletedResultApplied(SectionKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        if (!hasCompletedResults()) {
            throw new IllegalStateException("cannot consume an unavailable async BLAS result");
        }
        RtAccelerationStructure.SectionBlasBuildResult current = completedResults.get(nextCompletedResultIndex);
        if (!current.mesh().key().equals(key)) {
            throw new IllegalStateException("completed BLAS results must be consumed in submission order");
        }
        PendingSection section = sections.remove(key);
        if (section != null) {
            retainedEstimatedBytes -= section.estimatedBytes();
            if (section.active()) {
                activeTriangleCount -= section.triangleCount();
                activeEstimatedBytes -= section.estimatedBytes();
            }
        }
        nextCompletedResultIndex++;
    }

    boolean invalidate(SectionKey key) {
        requireOpen();
        PendingSection section = sections.get(Objects.requireNonNull(key, "key"));
        if (section == null || !section.active()) {
            return false;
        }
        sections.put(key, section.invalidated());
        activeTriangleCount -= section.triangleCount();
        activeEstimatedBytes -= section.estimatedBytes();
        return true;
    }

    boolean invalidateAll() {
        requireOpen();
        if (activeTriangleCount == 0L) {
            return false;
        }
        for (Map.Entry<SectionKey, PendingSection> entry : sections.entrySet()) {
            PendingSection section = entry.getValue();
            if (section.active()) {
                entry.setValue(section.invalidated());
            }
        }
        activeTriangleCount = 0L;
        activeEstimatedBytes = 0L;
        return true;
    }

    boolean resultWasInvalidated(SectionKey key) {
        PendingSection section = sections.get(Objects.requireNonNull(key, "key"));
        return section == null || !section.active();
    }

    long sequence() {
        return sequence;
    }

    boolean containsSection(SectionKey key) {
        return sections.containsKey(Objects.requireNonNull(key, "key"));
    }

    long contentRevision(SectionKey key) {
        return requireSection(key).contentRevision();
    }

    RtSceneMaterialTable.SectionMaterial material(SectionKey key) {
        return requireSection(key).material();
    }

    RendererFrameCausality causality(SectionKey key) {
        return requireSection(key).causality();
    }

    int retainedSectionCount() {
        return sections.size();
    }

    long retainedEstimatedBytes() {
        return retainedEstimatedBytes;
    }

    int activeSectionCount() {
        int count = 0;
        for (PendingSection section : sections.values()) {
            if (section.active()) {
                count++;
            }
        }
        return count;
    }

    int activePreferredSectionCount(Set<SectionKey> preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        int count = 0;
        for (Map.Entry<SectionKey, PendingSection> entry : sections.entrySet()) {
            if (entry.getValue().active() && preferredSectionKeys.contains(entry.getKey())) {
                count++;
            }
        }
        return count;
    }

    void addActiveKeysTo(Set<SectionKey> target) {
        Objects.requireNonNull(target, "target");
        for (Map.Entry<SectionKey, PendingSection> entry : sections.entrySet()) {
            if (entry.getValue().active()) {
                target.add(entry.getKey());
            }
        }
    }

    void addActiveKeysTo(PackedSectionMembership.Builder target) {
        Objects.requireNonNull(target, "target");
        for (Map.Entry<SectionKey, PendingSection> entry : sections.entrySet()) {
            if (entry.getValue().active()) {
                SectionKey key = entry.getKey();
                target.addPacked(key.packed());
            }
        }
    }

    long activeTriangleCount() {
        return activeTriangleCount;
    }

    long activeEstimatedBytes() {
        return activeEstimatedBytes;
    }

    List<SectionTriangleMesh> meshes() {
        List<SectionTriangleMesh> meshes = new ArrayList<>(sections.size());
        for (PendingSection section : sections.values()) {
            meshes.add(section.mesh());
        }
        return meshes;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            submission.close();
        } catch (RuntimeException ex) {
            failure = ex;
        }
        for (int index = completedResults.size() - 1; index >= nextCompletedResultIndex; index--) {
            try {
                completedResults.get(index).blas().close();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        completedResults = List.of();
        nextCompletedResultIndex = 0;
        sections.clear();
        retainedEstimatedBytes = 0L;
        activeTriangleCount = 0L;
        activeEstimatedBytes = 0L;
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pending section BLAS build is closed");
        }
    }

    private PendingSection requireSection(SectionKey key) {
        PendingSection section = sections.get(Objects.requireNonNull(key, "key"));
        if (section == null) {
            throw new IllegalStateException("missing pending BLAS metadata for " + key);
        }
        return section;
    }

    private record PendingSection(
            SectionTriangleMesh mesh,
            boolean active,
            long contentRevision,
            int sourceFlags,
            RtSceneMaterialTable.SectionMaterial material,
            RendererFrameCausality causality
    ) {
        private PendingSection {
            mesh = Objects.requireNonNull(mesh, "mesh");
            material = Objects.requireNonNull(material, "material");
            causality = Objects.requireNonNull(causality, "causality");
            if (contentRevision < 0L) {
                throw new IllegalArgumentException("pending BLAS content revision must be non-negative");
            }
            if (mesh.triangleCount() <= 0L) {
                throw new IllegalArgumentException("pending async BLAS section triangle count must be positive");
            }
            if (mesh.estimatedBytes() <= 0L) {
                throw new IllegalArgumentException("pending async BLAS section byte count must be positive");
            }
        }

        long triangleCount() {
            return mesh.triangleCount();
        }

        long estimatedBytes() {
            return mesh.estimatedBytes();
        }

        PendingSection invalidated() {
            return active
                    ? new PendingSection(mesh, false, contentRevision, sourceFlags, material, causality)
                    : this;
        }
    }
}
