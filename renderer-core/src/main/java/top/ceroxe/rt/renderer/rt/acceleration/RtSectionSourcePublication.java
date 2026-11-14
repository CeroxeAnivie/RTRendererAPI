package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.Objects;

/**
 * One coherent CPU-source generation consumed by Base BLAS and FarField proxy stages.
 *
 * <p>The mesh, its packed source material and both proxy generation tokens must cross the
 * renderer ownership boundary together. Keeping these facts in separate maps allowed Base-slot
 * eviction to delete a material generation while leaving the same section resident for FarField
 * admission. An immutable value makes that partially-published state unrepresentable.</p>
 *
 * @param mesh               immutable base geometry payload, or {@code null} after payload release
 * @param material           immutable packed material payload paired with {@code mesh}, or {@code null}
 * @param farFieldSource     immutable far-field proxy source
 * @param geometryGeneration non-negative geometry generation
 * @param materialGeneration non-negative material generation
 * @param contentRevision    non-negative coherent content revision
 * @param causality          immutable frame-causality identity
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
    /**
     * Creates a base payload and derives its immutable far-field source.
     *
     * @param mesh               base geometry
     * @param material           paired material
     * @param geometryGeneration geometry generation
     * @param materialGeneration material generation
     */
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

    /**
     * Creates a coherent base and far-field generation with untraced causality.
     *
     * @param mesh               base geometry
     * @param material           paired material
     * @param farFieldSource     compact proxy source
     * @param geometryGeneration geometry generation
     * @param materialGeneration material generation
     */
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

    /**
     * Validates atomic base-payload publication and matching section identities.
     */
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

    /**
     * Reports whether heavyweight base geometry and material remain attached.
     *
     * @return base payload presence
     */
    public boolean hasPayload() {
        return mesh != null;
    }

    /**
     * Returns the base mesh or fails after payload release.
     *
     * @return retained base mesh
     */
    public SectionTriangleMesh requireMesh() {
        return Objects.requireNonNull(mesh, "section source mesh payload");
    }

    /**
     * Returns the paired base material or fails after payload release.
     *
     * @return retained base material
     */
    public RtSceneMaterialTable.SectionMaterial requireMaterial() {
        return Objects.requireNonNull(material, "section source material payload");
    }

    /**
     * Accounts the complete heavyweight Base-build publication retained on the Java heap.
     * Geometry and its detached twelve-int face records share one lifetime and must therefore
     * consume one budget; counting only the mesh understated streaming retention by almost 2x.
     *
     * @return retained base payload bytes, or zero after payload release
     */
    public long basePayloadEstimatedBytes() {
        if (!hasPayload()) {
            return 0L;
        }
        return Math.addExact(requireMesh().estimatedBytes(), requireMaterial().estimatedBytes());
    }

    /**
     * Reports whether the immutable proxy source is retained.
     *
     * @return far-field payload presence
     */
    public boolean hasFarFieldPayload() {
        return farFieldSource != null;
    }

    /**
     * Returns the proxy source paired with this generation.
     *
     * @return non-null far-field source
     */
    public RtFarFieldSectionSource requireFarFieldSource() {
        return Objects.requireNonNull(farFieldSource, "FarField section source payload");
    }

    /**
     * Detaches heavyweight base payload while preserving proxy and generation identity.
     *
     * @return reduced publication
     */
    public RtSectionSourcePublication withoutPayload() {
        return hasPayload()
                ? new RtSectionSourcePublication(
                null, null, farFieldSource, geometryGeneration, materialGeneration,
                contentRevision, causality
        )
                : this;
    }
}
