package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

import java.util.Objects;

/**
 * One coherent CPU-source generation consumed by Base BLAS and FarField proxy stages.
 *
 * <p>The mesh, its packed source material and both proxy generation tokens must cross the
 * renderer ownership boundary together. Keeping these facts in separate maps allowed Base-slot
 * eviction to delete a material generation while leaving the same section resident for FarField
 * admission. An immutable value makes that partially-published state unrepresentable.</p>
 */
public record RtSectionSourcePublication(
        SectionTriangleMesh mesh,
        RtSceneMaterialTable.SectionMaterial material,
        RtFarFieldSectionSource farFieldSource,
        long geometryGeneration,
        long materialGeneration,
        long contentRevision,
        RendererFrameCausality causality
) {
    public RtSectionSourcePublication(
            SectionTriangleMesh mesh,
            RtSceneMaterialTable.SectionMaterial material,
            long geometryGeneration,
            long materialGeneration
    ) {
        this(
                mesh,
                material,
                RtFarFieldSectionSource.fromMesh(Objects.requireNonNull(mesh, "section source mesh payload")),
                geometryGeneration,
                materialGeneration,
                0L,
                RendererFrameCausality.untraced(0L)
        );
    }

    public RtSectionSourcePublication(
            SectionTriangleMesh mesh,
            RtSceneMaterialTable.SectionMaterial material,
            RtFarFieldSectionSource farFieldSource,
            long geometryGeneration,
            long materialGeneration
    ) {
        this(mesh, material, farFieldSource, geometryGeneration, materialGeneration,
                0L, RendererFrameCausality.untraced(0L));
    }

    public RtSectionSourcePublication {
        if ((mesh == null) != (material == null)) {
            throw new IllegalArgumentException("section source mesh and material payload must publish together");
        }
        farFieldSource = Objects.requireNonNull(farFieldSource, "FarField section source payload");
        if (mesh != null && !mesh.key().equals(farFieldSource.key())) {
            throw new IllegalArgumentException("Base and FarField source payloads belong to different sections");
        }
        if (geometryGeneration < 0L || materialGeneration < 0L || contentRevision < 0L) {
            throw new IllegalArgumentException("section source generations must not be negative");
        }
        causality = Objects.requireNonNull(causality, "section source causality");
    }

    public boolean hasPayload() {
        return mesh != null;
    }

    public SectionTriangleMesh requireMesh() {
        return Objects.requireNonNull(mesh, "section source mesh payload");
    }

    public RtSceneMaterialTable.SectionMaterial requireMaterial() {
        return Objects.requireNonNull(material, "section source material payload");
    }

    /**
     * Accounts the complete heavyweight Base-build publication retained on the Java heap.
     * Geometry and its detached twelve-int face records share one lifetime and must therefore
     * consume one budget; counting only the mesh understated streaming retention by almost 2x.
     */
    public long basePayloadEstimatedBytes() {
        if (!hasPayload()) {
            return 0L;
        }
        return Math.addExact(requireMesh().estimatedBytes(), requireMaterial().estimatedBytes());
    }

    public boolean hasFarFieldPayload() {
        return farFieldSource != null;
    }

    public RtFarFieldSectionSource requireFarFieldSource() {
        return Objects.requireNonNull(farFieldSource, "FarField section source payload");
    }

    public RtSectionSourcePublication withoutPayload() {
        return hasPayload()
                ? new RtSectionSourcePublication(
                        null, null, farFieldSource, geometryGeneration, materialGeneration,
                        contentRevision, causality
                )
                : this;
    }
}
