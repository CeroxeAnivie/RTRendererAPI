package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, collision-safe owner for detached packed section-material publications. */
class RtSectionMaterialReuseCache {
    private static final int MAX_ENTRIES = 8_192;
    private static final long MAX_RETAINED_BYTES = 16L * 1024L * 1024L;

    private final Long2ObjectOpenHashMap<List<RtSceneMaterialTable.SectionMaterial>> materials =
            new Long2ObjectOpenHashMap<>();
    private int materialCount;
    private long retainedBytes;

    Result materialFor(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        RtSceneMaterialTable.SectionMaterial published = mesh.packedMaterialPublication();
        if (published != null && !published.meshBackedPublication()) {
            return result(published, Outcome.GENERATION_HIT);
        }
        long fingerprint = materialFingerprint(mesh);
        List<RtSceneMaterialTable.SectionMaterial> candidates = materials.get(fingerprint);
        if (candidates != null) {
            for (RtSceneMaterialTable.SectionMaterial candidate : candidates) {
                if (candidate.matchesMesh(mesh)) {
                    if (published == null) {
                        mesh.publishPackedMaterial(candidate);
                    }
                    return result(
                            candidate,
                            published == null
                                    ? Outcome.HIT
                                    : Outcome.GENERATION_HIT
                    );
                }
            }
        }

        Outcome missCause = candidates == null
                ? materialCount == 0
                ? Outcome.EMPTY
                : Outcome.FINGERPRINT_MISS
                : Outcome.FINGERPRINT_COLLISION;
        RtSceneMaterialTable.SectionMaterial material = RtSceneMaterialTable.SectionMaterial
                .fromMesh(mesh)
                .detachedPublication();
        long materialBytes = material.estimatedBytes();
        if (materialCount >= MAX_ENTRIES || retainedBytes + materialBytes > MAX_RETAINED_BYTES) {
            clear();
            candidates = null;
            missCause = Outcome.CAPACITY_EVICTION;
        }
        if (candidates == null) {
            candidates = new ArrayList<>(1);
            materials.put(fingerprint, candidates);
        }
        candidates.add(material);
        materialCount++;
        retainedBytes += materialBytes;
        return result(material, missCause);
    }

    void clear() {
        materials.clear();
        materialCount = 0;
        retainedBytes = 0L;
    }

    private static Result result(
            RtSceneMaterialTable.SectionMaterial material,
            Outcome outcome
    ) {
        return new Result(material, outcome);
    }

    private static long materialFingerprint(SectionTriangleMesh mesh) {
        return ((long) RtSceneMaterialTable.SectionMaterial.meshRecordHash(mesh) << Integer.SIZE)
                ^ Integer.toUnsignedLong(mesh.faceCount());
    }

    enum Outcome {
        GENERATION_HIT,
        HIT,
        EMPTY,
        FINGERPRINT_MISS,
        FINGERPRINT_COLLISION,
        CAPACITY_EVICTION
    }

    record Result(RtSceneMaterialTable.SectionMaterial material, Outcome outcome) {
        Result {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
