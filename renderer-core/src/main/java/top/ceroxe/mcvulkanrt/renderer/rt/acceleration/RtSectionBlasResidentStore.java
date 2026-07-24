package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns live exact BLAS resources and their triangle, byte, and material metadata atomically.
 *
 * <p>The access-ordered BLAS map remains the eviction authority.  Parallel metadata never escapes
 * this owner, so install/remove cannot update a resource without updating both budget totals and
 * its material publication in the same operation.</p>
 */
final class RtSectionBlasResidentStore {
    private final Map<SectionKey, RtAccelerationStructure> blases = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<SectionKey, Integer> triangleCounts = new LinkedHashMap<>();
    private final Map<SectionKey, Long> storageBytes = new LinkedHashMap<>();
    private final Map<SectionKey, RtSceneMaterialTable.SectionMaterial> materials = new LinkedHashMap<>();
    private final Map<SectionKey, RtAccelerationStructure> blasesView = Collections.unmodifiableMap(blases);
    private long cachedTriangles;
    private long cachedBytes;

    Install prepareInstall(
            SectionKey key,
            RtAccelerationStructure blas,
            int triangles,
            RtSceneMaterialTable.SectionMaterial material
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(blas, "blas");
        Objects.requireNonNull(material, "material");
        if (triangles < 0 || blas.storageBytes() < 0L) {
            throw new IllegalArgumentException("resident BLAS metrics must not be negative");
        }
        RtAccelerationStructure previous = blases.get(key);
        Integer previousTriangles = triangleCounts.get(key);
        Long previousBytes = storageBytes.get(key);
        RtSceneMaterialTable.SectionMaterial previousMaterial = materials.get(key);
        assertPreviousShape(key, previous, previousTriangles, previousBytes, previousMaterial);
        if (previous == blas) {
            throw new IllegalArgumentException("resident BLAS replacement must publish a new resource for " + key);
        }
        long nextTriangles = Math.addExact(cachedTriangles, triangles);
        long nextBytes = Math.addExact(cachedBytes, blas.storageBytes());
        if (previous != null) {
            nextTriangles = Math.subtractExact(nextTriangles, previousTriangles);
            nextBytes = Math.subtractExact(nextBytes, previousBytes);
        }
        return new Install(
                key,
                blas,
                triangles,
                blas.storageBytes(),
                material,
                previous,
                previousTriangles,
                previousBytes,
                previousMaterial,
                nextTriangles,
                nextBytes
        );
    }

    void publish(Install install) {
        Objects.requireNonNull(install, "install");
        SectionKey key = install.key();
        if (blases.get(key) != install.previousBlas()
                || !Objects.equals(triangleCounts.get(key), install.previousTriangles())
                || !Objects.equals(storageBytes.get(key), install.previousStorageBytes())
                || !Objects.equals(materials.get(key), install.previousMaterial())) {
            throw new IllegalStateException("resident BLAS install no longer matches prepared state for " + key);
        }
        blases.put(key, install.blas());
        triangleCounts.put(key, install.triangles());
        storageBytes.put(key, install.storageBytes());
        materials.put(key, install.material());
        cachedTriangles = install.nextCachedTriangles();
        cachedBytes = install.nextCachedBytes();
    }

    MaterialUpdate prepareMaterialUpdate(SectionKey key, RtSceneMaterialTable.SectionMaterial material) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(material, "material");
        RtAccelerationStructure resident = blases.get(key);
        Integer triangles = triangleCounts.get(key);
        Long bytes = storageBytes.get(key);
        RtSceneMaterialTable.SectionMaterial previousMaterial = materials.get(key);
        assertPreviousShape(key, resident, triangles, bytes, previousMaterial);
        if (resident == null) {
            throw new IllegalStateException("cannot update material for non-resident BLAS " + key);
        }
        return new MaterialUpdate(key, previousMaterial, material);
    }

    void publishMaterialUpdate(MaterialUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!blases.containsKey(update.key())
                || !Objects.equals(materials.get(update.key()), update.previousMaterial())) {
            throw new IllegalStateException(
                    "resident BLAS material update no longer matches prepared state for " + update.key()
            );
        }
        materials.put(update.key(), update.material());
    }

    Removal remove(SectionKey key) {
        Objects.requireNonNull(key, "key");
        RtAccelerationStructure removed = blases.get(key);
        Integer triangles = triangleCounts.get(key);
        Long bytes = storageBytes.get(key);
        RtSceneMaterialTable.SectionMaterial material = materials.get(key);
        assertPreviousShape(key, removed, triangles, bytes, material);
        if (removed == null) {
            return Removal.absent(key);
        }
        long nextTriangles = Math.subtractExact(cachedTriangles, triangles);
        long nextBytes = Math.subtractExact(cachedBytes, bytes);
        blases.remove(key);
        triangleCounts.remove(key);
        storageBytes.remove(key);
        materials.remove(key);
        cachedTriangles = nextTriangles;
        cachedBytes = nextBytes;
        return new Removal(key, removed, triangles, bytes, material);
    }

    SectionKey firstOutside(Set<SectionKey> retainedKeys) {
        Objects.requireNonNull(retainedKeys, "retainedKeys");
        for (SectionKey key : blases.keySet()) {
            if (!retainedKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    RtAccelerationStructure get(SectionKey key) {
        return blases.get(Objects.requireNonNull(key, "key"));
    }

    boolean contains(SectionKey key) {
        return blases.containsKey(Objects.requireNonNull(key, "key"));
    }

    RtSceneMaterialTable.SectionMaterial material(SectionKey key) {
        return materials.get(Objects.requireNonNull(key, "key"));
    }

    Map<SectionKey, RtAccelerationStructure> blases() {
        return blasesView;
    }

    Set<SectionKey> keys() {
        return blasesView.keySet();
    }

    int size() {
        return blases.size();
    }

    boolean isEmpty() {
        return blases.isEmpty();
    }

    long cachedTriangles() {
        return cachedTriangles;
    }

    long cachedBytes() {
        return cachedBytes;
    }

    /** Clears metadata only after the caller has retired or closed every resource in {@link #blases()}. */
    void clearAfterExternalRelease() {
        blases.clear();
        triangleCounts.clear();
        storageBytes.clear();
        materials.clear();
        cachedTriangles = 0L;
        cachedBytes = 0L;
    }

    private static void assertPreviousShape(
            SectionKey key,
            RtAccelerationStructure blas,
            Integer triangles,
            Long bytes,
            RtSceneMaterialTable.SectionMaterial material
    ) {
        boolean present = blas != null;
        if (present != (triangles != null) || present != (bytes != null) || present != (material != null)) {
            throw new IllegalStateException("resident BLAS metadata ownership diverged for " + key);
        }
    }

    record Install(
            SectionKey key,
            RtAccelerationStructure blas,
            int triangles,
            long storageBytes,
            RtSceneMaterialTable.SectionMaterial material,
            RtAccelerationStructure previousBlas,
            Integer previousTriangles,
            Long previousStorageBytes,
            RtSceneMaterialTable.SectionMaterial previousMaterial,
            long nextCachedTriangles,
            long nextCachedBytes
    ) {
        Install {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(blas, "blas");
            Objects.requireNonNull(material, "material");
            if (triangles < 0 || storageBytes < 0L || nextCachedTriangles < 0L || nextCachedBytes < 0L) {
                throw new IllegalArgumentException("resident BLAS install metrics must not be negative");
            }
            assertPreviousShape(
                    key,
                    previousBlas,
                    previousTriangles,
                    previousStorageBytes,
                    previousMaterial
            );
        }
    }

    record Removal(
            SectionKey key,
            RtAccelerationStructure blas,
            int triangles,
            long storageBytes,
            RtSceneMaterialTable.SectionMaterial material
    ) {
        Removal {
            if (blas == null) {
                if (triangles != 0 || storageBytes != 0L || material != null) {
                    throw new IllegalArgumentException("absent BLAS removal must not retain metadata");
                }
            } else {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(material, "material");
                if (triangles < 0 || storageBytes < 0L) {
                    throw new IllegalArgumentException("removed BLAS metrics must not be negative");
                }
            }
        }

        static Removal absent(SectionKey key) {
            return new Removal(key, null, 0, 0L, null);
        }

        boolean present() {
            return blas != null;
        }
    }

    record MaterialUpdate(
            SectionKey key,
            RtSceneMaterialTable.SectionMaterial previousMaterial,
            RtSceneMaterialTable.SectionMaterial material
    ) {
        MaterialUpdate {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(previousMaterial, "previousMaterial");
            Objects.requireNonNull(material, "material");
        }
    }
}
