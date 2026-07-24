package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

/**
 * Owns the immutable CPU-to-GPU identity of one claimed section-BLAS batch.
 *
 * <p>A mesh and its content revision, causality, source flags, and material are one publication
 * fact. Keeping four independently assembled maps allowed a future caller to publish different
 * key sets or reorder claimed work without failing until native completion. This owner resolves
 * claimed mesh identities once, validates unique section keys, and carries the same immutable
 * section entries through CPU recording and GPU submission.</p>
 */
final class RtSectionBlasBuildBatch {
    private final List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> workItems;
    private final List<Section> sections;
    private final List<SectionTriangleMesh> meshes;
    private final long retainedEstimatedBytes;

    private RtSectionBlasBuildBatch(
            List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> workItems,
            List<Section> sections,
            long retainedEstimatedBytes
    ) {
        this.workItems = List.copyOf(workItems);
        this.sections = List.copyOf(sections);
        this.meshes = this.sections.stream().map(Section::mesh).toList();
        this.retainedEstimatedBytes = retainedEstimatedBytes;
    }

    static RtSectionBlasBuildBatch capture(
            List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> candidates,
            List<SectionTriangleMesh> claimedMeshes
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(claimedMeshes, "claimedMeshes");
        if (claimedMeshes.isEmpty()) {
            throw new IllegalArgumentException("claimed section-BLAS batch must not be empty");
        }

        IdentityHashMap<SectionTriangleMesh, RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>>
                workByMesh = new IdentityHashMap<>();
        for (RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            SectionTriangleMesh mesh = Objects.requireNonNull(candidate.mesh(), "candidate mesh");
            Objects.requireNonNull(candidate.payload(), "candidate metadata");
            if (workByMesh.put(mesh, candidate) != null) {
                throw new IllegalArgumentException("duplicate candidate mesh identity for " + mesh.key());
            }
        }

        List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> claimedWork =
                new ArrayList<>(claimedMeshes.size());
        List<Section> claimedSections = new ArrayList<>(claimedMeshes.size());
        Set<SectionTriangleMesh> claimedIdentities = java.util.Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Set<SectionKey> claimedKeys = new HashSet<>();
        long retainedBytes = 0L;
        for (SectionTriangleMesh claimedMesh : claimedMeshes) {
            Objects.requireNonNull(claimedMesh, "claimed mesh");
            if (!claimedIdentities.add(claimedMesh)) {
                throw new IllegalArgumentException("duplicate claimed mesh identity for " + claimedMesh.key());
            }
            if (!claimedKeys.add(claimedMesh.key())) {
                throw new IllegalArgumentException("duplicate claimed section " + claimedMesh.key());
            }
            RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work = workByMesh.get(claimedMesh);
            if (work == null) {
                throw new IllegalArgumentException(
                        "claimed section mesh is not an identity member of the candidate batch: "
                                + claimedMesh.key()
                );
            }
            claimedWork.add(work);
            claimedSections.add(new Section(claimedMesh, work.payload()));
            retainedBytes = Math.addExact(retainedBytes, claimedMesh.estimatedBytes());
        }
        return new RtSectionBlasBuildBatch(claimedWork, claimedSections, retainedBytes);
    }

    List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> workItems() {
        return workItems;
    }

    List<Section> sections() {
        return sections;
    }

    List<SectionTriangleMesh> meshes() {
        return meshes;
    }

    long retainedEstimatedBytes() {
        return retainedEstimatedBytes;
    }

    record Section(SectionTriangleMesh mesh, RtSectionBlasBuildMetadata metadata) {
        Section {
            mesh = Objects.requireNonNull(mesh, "mesh");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }
}
