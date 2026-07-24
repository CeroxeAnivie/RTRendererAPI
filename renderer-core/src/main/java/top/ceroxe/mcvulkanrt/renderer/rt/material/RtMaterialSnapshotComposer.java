package top.ceroxe.mcvulkanrt.renderer.rt.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Builds split material namespaces without mutating or retaining superseded snapshot ownership. */
final class RtMaterialSnapshotComposer {
    private RtMaterialSnapshotComposer() {
    }

    static RtSceneMaterialTable.Snapshot composeIncremental(
            RtSceneMaterialTable.Snapshot previousComposite,
            RtSceneMaterialTable.Snapshot previousBase,
            RtSceneMaterialTable.Snapshot nextBase,
            RtSceneMaterialTable.Snapshot previousAppended,
            RtSceneMaterialTable.Snapshot nextAppended,
            long revision,
            int instanceLayoutHash
    ) {
        Objects.requireNonNull(previousComposite, "previousComposite");
        Objects.requireNonNull(previousBase, "previousBase");
        Objects.requireNonNull(nextBase, "nextBase");
        Objects.requireNonNull(previousAppended, "previousAppended");
        Objects.requireNonNull(nextAppended, "nextAppended");

        int previousSectionCount = RtSceneMaterialTable.checkedIntAdd(
                previousBase.sectionCount(), previousAppended.sectionCount()
        );
        int nextSectionCount = RtSceneMaterialTable.checkedIntAdd(
                nextBase.sectionCount(), nextAppended.sectionCount()
        );
        int previousFaceCount = RtSceneMaterialTable.checkedIntAdd(
                previousBase.faceCount(), previousAppended.faceCount()
        );
        int nextFaceCount = RtSceneMaterialTable.checkedIntAdd(nextBase.faceCount(), nextAppended.faceCount());
        boolean baseUnchanged = nextBase == previousBase || nextBase.isIncrementalSuccessorOf(previousBase);
        boolean appendedUnchanged = nextAppended == previousAppended
                || nextAppended.isIncrementalSuccessorOf(previousAppended);
        if (!baseUnchanged
                || !appendedUnchanged
                || previousComposite.sectionCount() != previousSectionCount
                || previousComposite.faceCount() != previousFaceCount
                || previousSectionCount != nextSectionCount
                || previousFaceCount != nextFaceCount) {
            return compose(nextBase, nextAppended, revision, instanceLayoutHash);
        }

        int[] baseDirtySlots = nextBase.dirtySectionSlotsUnsafe();
        int[] appendedDirtySlots = nextAppended.dirtySectionSlotsUnsafe();
        int[] dirtySlots = new int[RtSceneMaterialTable.checkedIntAdd(
                baseDirtySlots.length, appendedDirtySlots.length
        )];
        System.arraycopy(baseDirtySlots, 0, dirtySlots, 0, baseDirtySlots.length);
        for (int index = 0; index < appendedDirtySlots.length; index++) {
            dirtySlots[baseDirtySlots.length + index] = RtSceneMaterialTable.checkedIntAdd(
                    nextBase.sectionCount(), appendedDirtySlots[index]
            );
        }

        List<RtSceneMaterialTable.SectionMaterial> previousMaterials = previousComposite.sectionMaterialsUnsafe();
        int[] previousFirstFaces = previousComposite.sectionFirstFacesUnsafe();
        List<RtSceneMaterialTable.SectionMaterial> nextMaterials = previousMaterials;
        int[] nextFirstFaces = previousFirstFaces;
        int fallbackColorFaces = previousComposite.signature().fallbackColorFaceCount();
        int fluidFaces = previousComposite.signature().fluidFaceCount();
        int emissiveFaces = previousComposite.signature().emissiveFaceCount();
        int sectionRecordHash = previousComposite.signature().sectionRecordHash();
        int faceRecordHash = previousComposite.signature().faceRecordHash();

        for (int compositeSlot : dirtySlots) {
            if (compositeSlot < 0 || compositeSlot >= nextSectionCount) {
                return compose(nextBase, nextAppended, revision, instanceLayoutHash);
            }
            RtSceneMaterialTable.SectionMaterial nextMaterial;
            int nextFirstFace;
            if (compositeSlot < nextBase.sectionCount()) {
                nextMaterial = nextBase.sectionMaterialsUnsafe().get(compositeSlot);
                nextFirstFace = nextBase.sectionFirstFacesUnsafe()[compositeSlot];
            } else {
                int appendedSlot = compositeSlot - nextBase.sectionCount();
                nextMaterial = nextAppended.sectionMaterialsUnsafe().get(appendedSlot);
                nextFirstFace = RtSceneMaterialTable.checkedIntAdd(
                        nextBase.faceCount(), nextAppended.sectionFirstFacesUnsafe()[appendedSlot]
                );
            }
            RtSceneMaterialTable.SectionMaterial previousMaterial = previousMaterials.get(compositeSlot);
            int previousFirstFace = previousFirstFaces[compositeSlot];
            if (previousMaterial.equals(nextMaterial) && previousFirstFace == nextFirstFace) {
                continue;
            }
            if (nextMaterials == previousMaterials) {
                nextMaterials = new ArrayList<>(previousMaterials);
            }
            nextMaterials.set(compositeSlot, nextMaterial);
            if (nextFirstFaces == previousFirstFaces) {
                nextFirstFaces = Arrays.copyOf(previousFirstFaces, previousFirstFaces.length);
            }
            nextFirstFaces[compositeSlot] = nextFirstFace;

            fallbackColorFaces = RtSceneMaterialTable.checkedIntAdd(
                    fallbackColorFaces - previousMaterial.fallbackColorFaceCount(),
                    nextMaterial.fallbackColorFaceCount()
            );
            fluidFaces = RtSceneMaterialTable.checkedIntAdd(
                    fluidFaces - previousMaterial.fluidFaceCount(), nextMaterial.fluidFaceCount()
            );
            emissiveFaces = RtSceneMaterialTable.checkedIntAdd(
                    emissiveFaces - previousMaterial.emissiveFaceCount(), nextMaterial.emissiveFaceCount()
            );
            int sectionExponent = RtSceneMaterialTable.checkedIntMultiply(
                    previousComposite.sectionCount() - compositeSlot - 1,
                    RtSceneMaterialTable.INTS_PER_SECTION_RECORD
            );
            sectionRecordHash += (sectionRecordBlockHash(nextFirstFace, nextMaterial)
                    - sectionRecordBlockHash(previousFirstFace, previousMaterial)) * powerOf31(sectionExponent);
            int faceExponent = RtSceneMaterialTable.checkedIntMultiply(
                    previousComposite.sectionCount() - compositeSlot - 1, 2
            );
            faceRecordHash += (faceRecordBlockHash(nextFirstFace, nextMaterial)
                    - faceRecordBlockHash(previousFirstFace, previousMaterial)) * powerOf31(faceExponent);
        }

        return new RtSceneMaterialTable.Snapshot(
                previousComposite,
                nextMaterials,
                nextFirstFaces,
                nextFaceCount,
                RtTextureCatalog.snapshot(),
                revision,
                instanceLayoutHash,
                dirtySlots,
                fallbackColorFaces,
                fluidFaces,
                emissiveFaces,
                sectionRecordHash,
                faceRecordHash
        );
    }

    static RtSceneMaterialTable.Snapshot compose(
            RtSceneMaterialTable.Snapshot base,
            List<RtSceneMaterialTable.SectionMaterial> appendedMaterials,
            long revision,
            int instanceLayoutHash
    ) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(appendedMaterials, "appendedMaterials");
        if (appendedMaterials.isEmpty()) {
            return RtSceneMaterialTable.Snapshot.fromMaterialSlots(
                    base.sectionMaterials(),
                    base.sectionFirstFaces(),
                    base.faceCount(),
                    revision,
                    instanceLayoutHash
            );
        }
        List<RtSceneMaterialTable.SectionMaterial> materials =
                new ArrayList<>(base.sectionCount() + appendedMaterials.size());
        materials.addAll(base.sectionMaterials());
        int[] firstFaces = Arrays.copyOf(base.sectionFirstFaces(), materials.size() + appendedMaterials.size());
        int faceCursor = base.faceCount();
        for (RtSceneMaterialTable.SectionMaterial material : appendedMaterials) {
            RtSceneMaterialTable.SectionMaterial checked = Objects.requireNonNull(material, "appended material");
            firstFaces[materials.size()] = faceCursor;
            materials.add(checked);
            faceCursor = RtSceneMaterialTable.checkedIntAdd(faceCursor, checked.faceCount());
        }
        return new RtSceneMaterialTable.Snapshot(
                materials,
                firstFaces,
                faceCursor,
                RtTextureCatalog.snapshot(),
                revision,
                instanceLayoutHash
        );
    }

    static RtSceneMaterialTable.Snapshot compose(
            RtSceneMaterialTable.Snapshot base,
            RtSceneMaterialTable.Snapshot appended,
            long revision,
            int instanceLayoutHash
    ) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(appended, "appended");
        if (appended.sectionCount() == 0) {
            return compose(base, List.of(), revision, instanceLayoutHash);
        }
        if (base.sectionCount() == 0) {
            return RtSceneMaterialTable.Snapshot.fromMaterialSlots(
                    appended.sectionMaterials(),
                    appended.sectionFirstFaces(),
                    appended.faceCount(),
                    revision,
                    instanceLayoutHash
            );
        }

        List<RtSceneMaterialTable.SectionMaterial> materials =
                new ArrayList<>(base.sectionCount() + appended.sectionCount());
        materials.addAll(base.sectionMaterials());
        materials.addAll(appended.sectionMaterials());
        int[] firstFaces = Arrays.copyOf(base.sectionFirstFaces(), materials.size());
        int[] appendedFirstFaces = appended.sectionFirstFaces();
        for (int index = 0; index < appendedFirstFaces.length; index++) {
            firstFaces[base.sectionCount() + index] =
                    RtSceneMaterialTable.checkedIntAdd(base.faceCount(), appendedFirstFaces[index]);
        }
        return new RtSceneMaterialTable.Snapshot(
                materials,
                firstFaces,
                RtSceneMaterialTable.checkedIntAdd(base.faceCount(), appended.faceCount()),
                RtTextureCatalog.snapshot(),
                revision,
                instanceLayoutHash
        );
    }

    static RtSceneMaterialTable.Snapshot prefix(
            RtSceneMaterialTable.Snapshot snapshot,
            int prefixSections,
            long revision,
            int instanceLayoutHash
    ) {
        if (prefixSections < 0 || prefixSections > snapshot.sectionCount()) {
            throw new IllegalArgumentException("material prefix must fit the snapshot section count");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("material prefix revision must not be negative");
        }
        if (prefixSections == 0) {
            return RtSceneMaterialTable.Snapshot.empty();
        }
        List<RtSceneMaterialTable.SectionMaterial> materials =
                snapshot.sectionMaterials().subList(0, prefixSections);
        int[] firstFaces = Arrays.copyOf(snapshot.sectionFirstFacesUnsafe(), prefixSections);
        int prefixFaceCount = 0;
        for (int index = 0; index < prefixSections; index++) {
            prefixFaceCount = Math.max(
                    prefixFaceCount,
                    RtSceneMaterialTable.checkedIntAdd(firstFaces[index], materials.get(index).faceCount())
            );
        }
        return RtSceneMaterialTable.Snapshot.fromMaterialSlots(
                materials, firstFaces, prefixFaceCount, revision, instanceLayoutHash
        );
    }

    static RtSceneMaterialTable.Snapshot suffix(
            RtSceneMaterialTable.Snapshot snapshot,
            int prefixSections,
            long revision,
            int instanceLayoutHash
    ) {
        if (prefixSections < 0 || prefixSections > snapshot.sectionCount()) {
            throw new IllegalArgumentException("material suffix prefix must fit the snapshot section count");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("material suffix revision must not be negative");
        }
        if (prefixSections == snapshot.sectionCount()) {
            return RtSceneMaterialTable.Snapshot.empty();
        }
        List<RtSceneMaterialTable.SectionMaterial> materials =
                snapshot.sectionMaterials().subList(prefixSections, snapshot.sectionCount());
        int[] sourceFirstFaces = snapshot.sectionFirstFacesUnsafe();
        int sourceFirstFace = sourceFirstFaces[prefixSections];
        int[] firstFaces = new int[materials.size()];
        int suffixFaceCount = 0;
        for (int index = 0; index < materials.size(); index++) {
            int firstFace = sourceFirstFaces[prefixSections + index] - sourceFirstFace;
            if (firstFace < 0) {
                throw new IllegalStateException("material suffix face ranges are not monotonic");
            }
            firstFaces[index] = firstFace;
            suffixFaceCount = Math.max(
                    suffixFaceCount,
                    RtSceneMaterialTable.checkedIntAdd(firstFace, materials.get(index).faceCount())
            );
        }
        return RtSceneMaterialTable.Snapshot.fromMaterialSlots(
                materials, firstFaces, suffixFaceCount, revision, instanceLayoutHash
        );
    }

    private static int sectionRecordBlockHash(int firstFace, RtSceneMaterialTable.SectionMaterial material) {
        int result = firstFace;
        result = 31 * result + material.faceCount();
        result = 31 * result + material.secondGeometryFaceOffset();
        return 31 * result;
    }

    private static int faceRecordBlockHash(int firstFace, RtSceneMaterialTable.SectionMaterial material) {
        return 31 * firstFace + material.faceRecordHash();
    }

    private static int powerOf31(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("power exponent must not be negative");
        }
        int result = 1;
        int factor = 31;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result *= factor;
            }
            factor *= factor;
            remaining >>>= 1;
        }
        return result;
    }
}
