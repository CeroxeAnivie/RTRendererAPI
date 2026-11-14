package top.ceroxe.rt.renderer.rt.material;

import java.nio.IntBuffer;
import java.util.Objects;

/**
 * Converts immutable material snapshot provenance into sparse GPU upload chunks.
 */
final class RtMaterialSnapshotUploadPlanner {
    private RtMaterialSnapshotUploadPlanner() {
    }

    static RtSceneMaterialTable.MaterialUploadDiff diff(
            RtSceneMaterialTable.Snapshot snapshot,
            RtSceneMaterialTable.Snapshot previousSnapshot,
            boolean forceFullUpload
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        RtMaterialDirtyUploadPlan sectionPlan = planSections(snapshot, previousSnapshot, forceFullUpload);
        RtMaterialDirtyUploadPlan facePlan = planFaces(snapshot, previousSnapshot, forceFullUpload);
        RtMaterialDirtyUploadPlan textureRecordPlan;
        RtMaterialDirtyUploadPlan texturePixelPlan;
        if (!forceFullUpload
                && snapshot.textureSnapshot().revision() == previousSnapshot.textureSnapshot().revision()) {
            textureRecordPlan = RtMaterialDirtyUploadPlan.empty();
            texturePixelPlan = RtMaterialDirtyUploadPlan.empty();
        } else {
            IntBuffer textureRecords = snapshot.textureSnapshot().textureRecordBuffer();
            textureRecordPlan = RtTextureUploadPlanner.planRecords(
                    textureRecords,
                    previousSnapshot.textureSnapshot().textureRecordBuffer(),
                    RtTextureCatalog.INTS_PER_TEXTURE_RECORD,
                    forceFullUpload
            );
            texturePixelPlan = RtTextureUploadPlanner.planPixels(
                    snapshot.textureSnapshot(), previousSnapshot.textureSnapshot(), forceFullUpload
            );
        }
        return new RtSceneMaterialTable.MaterialUploadDiff(
                sectionPlan.recordCount(),
                facePlan.recordCount(),
                textureRecordPlan.recordCount(),
                texturePixelPlan.recordCount(),
                sectionPlan.byteCount(),
                facePlan.byteCount(),
                textureRecordPlan.byteCount(),
                texturePixelPlan.byteCount(),
                sectionPlan.byteCount()
                        + facePlan.byteCount()
                        + textureRecordPlan.byteCount()
                        + texturePixelPlan.byteCount(),
                forceFullUpload
        );
    }

    static RtMaterialDirtyUploadPlan planSections(
            RtSceneMaterialTable.Snapshot snapshot,
            RtSceneMaterialTable.Snapshot previousSnapshot,
            boolean forceFullUpload
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        RtMaterialDirtyUploadPlan.Builder builder = RtMaterialDirtyUploadPlan.builder();
        if (!forceFullUpload && snapshot.isIncrementalSuccessorOf(previousSnapshot)) {
            for (int section : snapshot.dirtySectionSlotsUnsafe()) {
                if (section < snapshot.sectionCount() && !snapshot.sectionRecordEquals(previousSnapshot, section)) {
                    builder.add(
                            snapshot.sectionRecord(section),
                            RtSceneMaterialTable.checkedMultiply(
                                    RtSceneMaterialTable.checkedMultiply(
                                            section, RtSceneMaterialTable.INTS_PER_SECTION_RECORD
                                    ),
                                    Integer.BYTES
                            ),
                            1L
                    );
                }
            }
            return builder.build();
        }
        for (int section = 0; section < snapshot.sectionCount(); section++) {
            if (forceFullUpload || section >= previousSnapshot.sectionCount()
                    || !snapshot.sectionRecordEquals(previousSnapshot, section)) {
                builder.add(
                        snapshot.sectionRecord(section),
                        RtSceneMaterialTable.checkedMultiply(
                                RtSceneMaterialTable.checkedMultiply(
                                        section, RtSceneMaterialTable.INTS_PER_SECTION_RECORD
                                ),
                                Integer.BYTES
                        ),
                        1L
                );
            }
        }
        return builder.build();
    }

    static RtMaterialDirtyUploadPlan planFaces(
            RtSceneMaterialTable.Snapshot snapshot,
            RtSceneMaterialTable.Snapshot previousSnapshot,
            boolean forceFullUpload
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        RtMaterialDirtyUploadPlan.Builder builder = RtMaterialDirtyUploadPlan.builder();
        if (!forceFullUpload && snapshot.isIncrementalSuccessorOf(previousSnapshot)) {
            int[] dirtyRuns = snapshot.dirtyFaceRunsUnsafe();
            for (int offset = 0; offset < dirtyRuns.length; offset += 3) {
                addDirtyFaceRun(
                        builder,
                        snapshot.sectionMaterial(dirtyRuns[offset]),
                        snapshot.firstFace(dirtyRuns[offset]),
                        dirtyRuns[offset + 1],
                        dirtyRuns[offset + 2]
                );
            }
            return builder.build();
        }
        for (int section = 0; section < snapshot.sectionCount(); section++) {
            addFaceUpload(builder, snapshot, previousSnapshot, section, forceFullUpload);
        }
        return builder.build();
    }

    private static void addFaceUpload(
            RtMaterialDirtyUploadPlan.Builder builder,
            RtSceneMaterialTable.Snapshot snapshot,
            RtSceneMaterialTable.Snapshot previousSnapshot,
            int section,
            boolean forceFullUpload
    ) {
        RtSceneMaterialTable.SectionMaterial material = snapshot.sectionMaterial(section);
        int firstFace = snapshot.firstFace(section);
        long sectionByteOffset = RtSceneMaterialTable.checkedMultiply(
                RtSceneMaterialTable.checkedMultiply(firstFace, RtSceneMaterialTable.INTS_PER_FACE_RECORD),
                Integer.BYTES
        );
        if (forceFullUpload || section >= previousSnapshot.sectionCount()
                || firstFace != previousSnapshot.firstFace(section)) {
            builder.add(material, sectionByteOffset, material.faceCount());
            return;
        }
        RtSceneMaterialTable.SectionMaterial previousMaterial = previousSnapshot.sectionMaterial(section);
        if (material.faceCount() != previousMaterial.faceCount() || material != previousMaterial) {
            builder.add(material, sectionByteOffset, material.faceCount());
        }
    }

    private static void addDirtyFaceRun(
            RtMaterialDirtyUploadPlan.Builder builder,
            RtSceneMaterialTable.SectionMaterial material,
            int sectionFirstFace,
            int firstDirtyFace,
            int dirtyFaceCount
    ) {
        long targetOffsetBytes = RtSceneMaterialTable.checkedMultiply(
                RtSceneMaterialTable.checkedMultiply(
                        RtSceneMaterialTable.checkedIntAdd(sectionFirstFace, firstDirtyFace),
                        RtSceneMaterialTable.INTS_PER_FACE_RECORD
                ),
                Integer.BYTES
        );
        builder.add(material.faceRange(firstDirtyFace, dirtyFaceCount), targetOffsetBytes, dirtyFaceCount);
    }
}
