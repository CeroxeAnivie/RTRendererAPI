package top.ceroxe.rt.renderer.rt.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.Snapshot;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.PackedVoxelLighting;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtMaterialStateSelfTest {
   private static final int TEST_INTS_PER_SECTION_RECORD = 4;
   private static final int TEST_INTS_PER_FACE_RECORD = 12;

   private RtMaterialStateSelfTest() {
   }

   public static void main(String[] args) {
      tracksMaterialSnapshotSignatureWithoutRetainingUploadedPayload();
      uploadsAnimatedTextureRevisionWithoutTerrainMaterialChurn();
      separatesMaterialUploadSignatureFromTlasInstanceLayout();
      exposesStreamingSectionMaterialsWithoutPackedRecordRoundTrip();
      keepsStableMaterialSlotsIncrementalAcrossSparseUpdates();
      reusesUnchangedStreamingMaterialPublicationIdentity();
      keepsComposedMaterialSlotsIncrementalAcrossBaseAndFarFieldUpdates();
      rebasesDeferredWorldMaterialAgainstDescriptorVisibleDynamicSuffix();
      keepsDynamicMaterialSnapshotsIncrementalAcrossTransformOnlyFrames();
      dirtiesSameSlotFirstMaskedFaceTextureReplacement();
      usesPublicationIdentityWhenAsyncUploadSkipsSuccessors();
      materializesSparseFaceRecordsAtTheirAllocatedOffsets();
      growsMaterialSlotsWithSlackForMcDynamicFaceChurn();
      meetsThousandFpsDynamicMaterialSlotChurnStressGate();
      meetsThousandFpsMaterialStreamingStressGate();
      System.out.println("RtMaterialStateSelfTest passed");
   }

   private static void rebasesDeferredWorldMaterialAgainstDescriptorVisibleDynamicSuffix() {
      RtSceneMaterialTable.SectionMaterial candidateTerrainA = stressSectionMaterial(601, 2);
      RtSceneMaterialTable.SectionMaterial candidateTerrainB = stressSectionMaterial(602, 3);
      RtSceneMaterialTable.SectionMaterial staleDynamic = stressSectionMaterial(603, 4);
      RtSceneMaterialTable.SectionMaterial publishedTerrain = stressSectionMaterial(604, 5);
      RtSceneMaterialTable.SectionMaterial currentDynamicA = stressSectionMaterial(605, 6);
      RtSceneMaterialTable.SectionMaterial currentDynamicB = stressSectionMaterial(606, 7);
      RtSceneMaterialTable.Snapshot candidate = Snapshot.fromSectionMaterials(List.of(candidateTerrainA, candidateTerrainB, staleDynamic), 31L, 51729);
      RtSceneMaterialTable.Snapshot published = Snapshot.fromSectionMaterials(List.of(publishedTerrain, currentDynamicA, currentDynamicB), 41L, RtWorldTlasCache.combinedSplitLayoutHash(1, 53664));
      RtSceneMaterialTable.Snapshot rebased = RtWorldTlasCache.rebaseSplitWorldMaterialSnapshot(candidate, 2, 30L, 300L, published, 1, 400L);
      require(rebased.sectionCount() == 4, "rebased world material table must contain candidate terrain and current dynamic slots");
      require(rebased.sectionMaterials().get(0) == candidateTerrainA && rebased.sectionMaterials().get(1) == candidateTerrainB, "deferred world completion must preserve its exact terrain material prefix");
      require(rebased.sectionMaterials().get(2) == currentDynamicA && rebased.sectionMaterials().get(3) == currentDynamicB, "deferred world completion must use the descriptor-visible dynamic suffix");
      require(!rebased.sectionMaterials().contains(staleDynamic), "deferred world completion must not resurrect its captured dynamic suffix");
      require(rebased.instanceLayoutHash() == RtWorldTlasCache.combinedSplitLayoutHash(2, 53664), "rebased world material table must retain the current dynamic layout identity");
      int[] sectionRecords = rebased.sectionRecords();
      require(sectionRecords[8] == 5, "first rebased dynamic slot must start after the complete candidate terrain face prefix");
      require(sectionRecords[12] == 11, "rebased dynamic face offsets must remain contiguous after terrain prefix composition");
      RtSceneMaterialTable.Snapshot unchangedDynamic = RtWorldTlasCache.rebaseSplitWorldMaterialSnapshot(candidate, 2, 30L, 300L, published, 1, 300L);
      require(unchangedDynamic == candidate, "matching dynamic generations must retain the candidate snapshot identity");
      RtSceneMaterialTable.Snapshot bootstrap = RtWorldTlasCache.rebaseSplitWorldMaterialSnapshot(candidate, 2, 30L, 300L, Snapshot.empty(), 0, -1L);
      require(bootstrap == candidate, "bootstrap publication has no committed dynamic suffix and must retain the candidate identity");
   }

   private static void tracksMaterialSnapshotSignatureWithoutRetainingUploadedPayload() {
      RtSceneMaterialTable.SectionMaterial material = new RtSceneMaterialTable.SectionMaterial(singlePackedFaceRecord(77, 17039880, 2245734, 0, RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)));
      RtSceneMaterialTable.Snapshot first = Snapshot.fromSectionMaterials(List.of(material), 41L);
      RtSceneMaterialTable.Snapshot sameContentNewRevision = Snapshot.fromSectionMaterials(List.of(material), 42L);
      RtSceneMaterialTable.SectionMaterial changedMaterial = new RtSceneMaterialTable.SectionMaterial(singlePackedFaceRecord(78, 17039880, 2245734, 0, RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)));
      RtSceneMaterialTable.SnapshotSignature firstSignature = first.signature();
      RtSceneMaterialTable.SnapshotSignature duplicateSignature = Snapshot.fromSectionMaterials(List.of(material), 41L).signature();
      RtSceneMaterialTable.SnapshotSignature sameContentNewRevisionSignature = sameContentNewRevision.signature();
      RtSceneMaterialTable.SnapshotSignature changedContentSignature = Snapshot.fromSectionMaterials(List.of(changedMaterial), 42L).signature();
      require(first.revision() == 41L, "material snapshot should carry the source BLAS revision");
      require(firstSignature.equals(duplicateSignature), "same revision and content should deduplicate material upload");
      require(firstSignature.equals(sameContentNewRevisionSignature), "unchanged material contents must not reupload only because TLAS revision advanced");
      require(!firstSignature.equals(changedContentSignature), "changed material contents must still trigger a fresh material upload");
      require(firstSignature.sectionCount() == 1, "material signature section count mismatch");
      require(firstSignature.faceCount() == 1, "material signature face count mismatch");
      require(firstSignature.fallbackColorFaceCount() == 1, "fallback-color face count mismatch");
      require(firstSignature.fluidFaceCount() == 1, "fluid face count mismatch");
      require(firstSignature.emissiveFaceCount() == 1, "emissive face count mismatch");
      require(firstSignature.textureRecords() >= 1, "material signature should include texture catalog records");
      require(firstSignature.texturePixels() >= 1, "material signature should include texture catalog pixels");
      require(firstSignature.textureRevision() == first.textureSnapshot().revision(), "material signature should include the texture catalog revision");
   }

   private static void uploadsAnimatedTextureRevisionWithoutTerrainMaterialChurn() {
      RtTextureCatalog.TestTextureScope textureScope = RtTextureCatalog.installAnimatedTestTexturesForSelfTest(List.of(new RtTextureCatalog.TestAnimatedTexture("rtrenderer:selftest/material_only_water", 1, 1, List.of(new int[]{rgba8(16, 64, 180, 192)}, new int[]{rgba8(32, 96, 220, 192)}), 1)));

      try {
         require(textureScope.textureId("rtrenderer:selftest/material_only_water") > 0, "animated material-only texture must be installed inside its owning scope");
         RtSceneMaterialTable.SectionMaterial material = new RtSceneMaterialTable.SectionMaterial(singlePackedFaceRecord(77, 16777992, 3368652, 0, RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)));
         RtSceneMaterialTable.Snapshot before = Snapshot.fromSectionMaterials(List.of(material), 210L, 51966);
         RtTextureCatalog.AnimationUpdate update = RtTextureCatalog.advanceAnimations();
         RtSceneMaterialTable.Snapshot after = before.withCurrentTextureSnapshot();
         RtSceneMaterialTable.MaterialUploadDiff diff = after.uploadDiffFrom(before, false);
         require(update.changedTextures() == 1, "animated material-only self-test must advance exactly one texture frame");
         require(after.textureSnapshot().revision() > before.textureSnapshot().revision(), "material-only texture refresh must carry the new catalog revision");
         require(diff.dirtySectionRecords() == 0L, "animated texture refresh must not dirty section material records");
         require(diff.dirtyFaceRecords() == 0L, "animated texture refresh must not dirty face material records");
         require(diff.dirtyTexturePixels() > 0L, "animated texture refresh must dirty texture pixels");
         require(!diff.fullUpload(), "animated texture refresh should stay on the incremental upload path");
         require(after.sectionCount() == before.sectionCount() && after.faceCount() == before.faceCount() && after.instanceLayoutHash() == before.instanceLayoutHash(), "animated texture refresh must preserve stable material slots and TLAS instance layout");
      } catch (Throwable value7) {
         if (textureScope != null) {
            try {
               textureScope.close();
            } catch (Throwable value6) {
               value7.addSuppressed(value6);
            }
         }

         throw value7;
      }

      if (textureScope != null) {
         textureScope.close();
      }

   }

   private static void separatesMaterialUploadSignatureFromTlasInstanceLayout() {
      RtSceneMaterialTable.SectionMaterial material = new RtSceneMaterialTable.SectionMaterial(singlePackedFaceRecord(77, 17039880, 2245734, 0, RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)));
      List<RtSceneMaterialTable.SectionMaterial> identicalMaterials = List.of(material, material);
      RtSceneMaterialTable.Snapshot stableLayoutFirstRevision = Snapshot.fromSectionMaterials(identicalMaterials, 100L, 4951);
      RtSceneMaterialTable.Snapshot stableLayoutNextRevision = Snapshot.fromSectionMaterials(identicalMaterials, 101L, 4951);
      RtSceneMaterialTable.Snapshot reorderedInstanceLayout = Snapshot.fromSectionMaterials(identicalMaterials, 102L, 9320);
      require(stableLayoutFirstRevision.signature().equals(stableLayoutNextRevision.signature()), "unchanged instance layout must not reupload material data only because TLAS revision advanced");
      require(stableLayoutFirstRevision.signature().equals(reorderedInstanceLayout.signature()), "TLAS instance identity/order must not reupload unchanged material buffers");
      require(stableLayoutFirstRevision.instanceLayoutHash() == 4951, "material snapshot should retain TLAS layout identity outside the upload signature");
      require(reorderedInstanceLayout.instanceLayoutHash() == 9320, "material snapshot should retain the updated TLAS layout identity outside the upload signature");
      require(stableLayoutFirstRevision.equals(reorderedInstanceLayout), "material snapshots with identical GPU buffer contents must compare equal across TLAS-only layout changes");
   }

   private static void exposesStreamingSectionMaterialsWithoutPackedRecordRoundTrip() {
      RtSceneMaterialTable.SectionMaterial material = new RtSceneMaterialTable.SectionMaterial(singlePackedFaceRecord(77, 17039880, 2245734, 0, RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)));
      RtSceneMaterialTable.Snapshot streamingSnapshot = Snapshot.fromSectionMaterials(List.of(material), 200L, 4951);
      List<RtSceneMaterialTable.SectionMaterial> sectionMaterials = streamingSnapshot.sectionMaterials();
      require(sectionMaterials.size() == 1, "streaming material snapshot should expose one section material");
      require(sectionMaterials.get(0) == material, "streaming TLAS composition must reuse section materials without packed array round-trip");
      RuntimeException mutationFailure = expectFailure(() -> sectionMaterials.add(material));
      require(mutationFailure instanceof UnsupportedOperationException, "exposed streaming section materials must be immutable");
   }

   private static void keepsStableMaterialSlotsIncrementalAcrossSparseUpdates() {
      RtSceneMaterialTable.SectionMaterial stable = stressSectionMaterial(10, 4);
      RtSceneMaterialTable.SectionMaterial changed = stressSectionMaterial(20, 4);
      RtSceneMaterialTable.SectionMaterial changedNext = stressSectionMaterial(21, 4);
      RtSceneMaterialTable.Snapshot previous = Snapshot.fromMaterialSlots(List.of(stable, changed), new int[]{0, 32}, 36, 1L, 256);
      RtSceneMaterialTable.Snapshot next = Snapshot.fromMaterialSlots(List.of(stable, changedNext), new int[]{0, 32}, 36, 2L, 512);
      require(next.sectionRecords()[0] == 0, "stable material slot 0 must keep its first-face range");
      require(next.sectionRecords()[4] == 32, "stable material slot 1 must keep its sparse first-face range");
      RtSceneMaterialTable.MaterialUploadDiff diff = next.uploadDiffFrom(previous, false);
      require(diff.dirtySectionRecords() == 0L, "same face ranges must not rewrite section records when only face material changed");
      require(diff.dirtyFaceRecords() == 4L, "incremental material diff must stage only the changed sparse slot faces");
      require(diff.stagedBytes() == 192L, "incremental material diff staged byte count mismatch");
      require(next.instanceLayoutHash() == 512, "stable material snapshot must still carry TLAS layout hash outside upload signature");
   }

   private static void keepsComposedMaterialSlotsIncrementalAcrossBaseAndFarFieldUpdates() {
      MaterialSlotAllocator<SectionKey> baseSlots = new MaterialSlotAllocator<>();
      MaterialSlotAllocator<SectionKey> farFieldSlots = new MaterialSlotAllocator<>();
      SectionKey baseKey = new SectionKey(1, 2, 3);
      SectionKey farFieldKey = new SectionKey(96, 2, 3);
      baseSlots.update(baseKey, stressSectionMaterial(100, 4));
      farFieldSlots.update(farFieldKey, stressSectionMaterial(200, 4));
      RtSceneMaterialTable.Snapshot previousBase = Snapshot.fromMaterialSlots(baseSlots.sectionMaterials(), baseSlots.firstFacesArray(), baseSlots.faceCapacity(), 1L, 256);
      RtSceneMaterialTable.Snapshot previousFarField = Snapshot.fromMaterialSlots(farFieldSlots.sectionMaterials(), farFieldSlots.firstFacesArray(), farFieldSlots.faceCapacity(), 1L, 0);
      baseSlots.consumeDirtySlots();
      farFieldSlots.consumeDirtySlots();
      RtSceneMaterialTable.Snapshot previousComposite = Snapshot.compose(previousBase, previousFarField, 1L, 256);
      baseSlots.update(baseKey, stressSectionMaterial(101, 4));
      farFieldSlots.update(farFieldKey, stressSectionMaterial(201, 4));
      RtSceneMaterialTable.Snapshot nextBase = Snapshot.fromMaterialSlotsIncremental(previousBase, baseSlots, 2L, 512);
      RtSceneMaterialTable.Snapshot nextFarField = Snapshot.fromMaterialSlotsIncremental(previousFarField, farFieldSlots, 2L, 0);
      RtSceneMaterialTable.Snapshot incrementalComposite = Snapshot.composeIncremental(previousComposite, previousBase, nextBase, previousFarField, nextFarField, 2L, 512);
      RtSceneMaterialTable.Snapshot fullComposite = Snapshot.compose(nextBase, nextFarField, 2L, 512);
      require(incrementalComposite.signature().equals(fullComposite.signature()), "incremental base and far-field composition must preserve the full upload signature");
      require(incrementalComposite.equals(fullComposite), "incremental base and far-field composition must preserve material buffer contents");
      RtSceneMaterialTable.MaterialUploadDiff diff = incrementalComposite.uploadDiffFrom(previousComposite, false);
      require(diff.dirtySectionRecords() == 0L, "same-capacity base and far-field material updates must not rewrite section records");
      require(diff.dirtyFaceRecords() == 8L, "composed material updates must stage exactly the changed base and far-field face runs");
      require(incrementalComposite.instanceLayoutHash() == 512, "composed material updates must retain the current TLAS instance layout outside material upload state");
      RtSceneMaterialTable.Snapshot unchangedBaseComposite = Snapshot.composeIncremental(previousComposite, previousBase, previousBase, previousFarField, nextFarField, 2L, 512);
      RtSceneMaterialTable.MaterialUploadDiff unchangedBaseDiff = unchangedBaseComposite.uploadDiffFrom(previousComposite, false);
      require(unchangedBaseDiff.dirtySectionRecords() == 0L, "identity-reused terrain base must preserve dynamic-only section record reuse");
      require(unchangedBaseDiff.dirtyFaceRecords() == 4L, "identity-reused terrain base must upload only the changed dynamic face run");
   }

   private static void keepsDynamicMaterialSnapshotsIncrementalAcrossTransformOnlyFrames() {
      RtSceneMaterialTable.SectionMaterial stable = stressSectionMaterial(300, 4);
      RtSceneMaterialTable.SectionMaterial changed = stressSectionMaterial(400, 4);
      RtSceneMaterialTable.SectionMaterial changedNext = stressSectionMaterial(401, 4);
      RtSceneMaterialTable.Snapshot previous = Snapshot.fromSectionMaterials(List.of(stable, changed), 10L, 4096);
      RtSceneMaterialTable.Snapshot next = Snapshot.fromSectionMaterialsIncremental(previous, List.of(stable, changedNext), new int[]{1}, 11L, 8192);
      RtSceneMaterialTable.Snapshot fullSnapshot = Snapshot.fromSectionMaterials(List.of(stable, changedNext), 11L, 8192);
      require(next.signature().equals(fullSnapshot.signature()), "dynamic incremental snapshots must preserve the full material upload signature");
      require(next.equals(fullSnapshot), "dynamic incremental snapshots must preserve full material buffer contents");
      RtSceneMaterialTable.MaterialUploadDiff diff = next.uploadDiffFrom(previous, false);
      require(diff.dirtySectionRecords() == 0L, "dynamic material-only updates must preserve sequential section records");
      require(diff.dirtyFaceRecords() == (long)changedNext.faceCount(), "dynamic material-only updates must stage only the changed mesh material faces");
      require(next.instanceLayoutHash() == 8192, "dynamic transform-only TLAS layout changes must remain outside material upload state");
      RtSceneMaterialTable.SectionMaterial tombstone = RtSceneMaterialTable.tombstoneSectionMaterial(changedNext.faceCount());
      RtSceneMaterialTable.Snapshot removed = Snapshot.fromSectionMaterialsIncremental(next, List.of(stable, tombstone), new int[]{1}, 12L, 8192);
      RtSceneMaterialTable.MaterialUploadDiff removalDiff = removed.uploadDiffFrom(next, false);
      require(removalDiff.dirtySectionRecords() == 0L, "same-capacity dynamic tombstones must preserve later material offsets");
      require(removalDiff.dirtyFaceRecords() == (long)tombstone.faceCount(), "dynamic slot removal must upload only the removed slot face range");
   }

   private static void dirtiesSameSlotFirstMaskedFaceTextureReplacement() {
      RtSceneMaterialTable.SectionMaterial initial = maskedSlotMaterial(41, 7, 11, 13);
      RtSceneMaterialTable.SectionMaterial replacement = maskedSlotMaterial(41, 17, 11, 13);
      MaterialSlotAllocator<SectionKey> materialSlots = new MaterialSlotAllocator<>();
      SectionKey key = new SectionKey(12, 5, 9);
      int slot = materialSlots.update(key, initial);
      RtSceneMaterialTable.Snapshot previous = Snapshot.fromMaterialSlots(materialSlots.sectionMaterials(), materialSlots.firstFacesArray(), materialSlots.faceCapacity(), 1L, 48879);
      materialSlots.consumeDirtySlots();
      int replacementSlot = materialSlots.update(key, replacement);
      RtSceneMaterialTable.Snapshot next = Snapshot.fromMaterialSlotsIncremental(previous, materialSlots, 2L, 48879);
      RtSceneMaterialTable.Snapshot fullSnapshot = Snapshot.fromMaterialSlots(materialSlots.sectionMaterials(), materialSlots.firstFacesArray(), materialSlots.faceCapacity(), 2L, 48879);
      require(replacementSlot == slot, "same section must preserve its TLAS material slot across masked texture churn");
      require(next.signature().equals(fullSnapshot.signature()), "incremental material snapshots must preserve the full snapshot upload signature");
      require(next.sectionRecords()[slot * 4] == previous.sectionRecords()[slot * 4], "same-slot masked replacement must keep the sparse face range stable");
      int sectionRecord = slot * 4;
      int firstMaskedFaceRecord = (next.sectionRecords()[sectionRecord] + next.sectionRecords()[sectionRecord + 2]) * 12;
      require((next.faceRecords()[firstMaskedFaceRecord + 3] & 1073741823) == 17, "first alpha-cutout face texture id must be replaced in the sparse material table");
      RtSceneMaterialTable.MaterialUploadDiff diff = next.uploadDiffFrom(previous, false);
      require(diff.dirtySectionRecords() == 0L, "same-slot masked texture replacement must not churn section records");
      require(diff.dirtyFaceRecords() == 1L, "same-slot masked texture replacement must upload only the changed face run");
      require(diff.stagedBytes() == 48L, "same-slot masked texture replacement staged byte count mismatch");
   }

   private static void reusesUnchangedStreamingMaterialPublicationIdentity() {
      MaterialSlotAllocator<SectionKey> materialSlots = new MaterialSlotAllocator<>();
      SectionKey key = new SectionKey(4, 5, 6);
      materialSlots.update(key, maskedSlotMaterial(31, 7, 11, 13));
      RtSceneMaterialTable.Snapshot previous = Snapshot.fromMaterialSlots(materialSlots.sectionMaterials(), materialSlots.firstFacesArray(), materialSlots.faceCapacity(), 1L, 51966);
      materialSlots.consumeDirtySlots();
      RtSceneMaterialTable.Snapshot unchanged = Snapshot.fromMaterialSlotsIncremental(previous, materialSlots, 2L, 51966);
      require(unchanged.sectionMaterials() == previous.sectionMaterials(), "an unchanged material generation must retain the immutable slot publication by identity");
      RtSceneMaterialTable.MaterialUploadDiff diff = unchanged.uploadDiffFrom(previous, false);
      require(diff.dirtySectionRecords() == 0L && diff.dirtyFaceRecords() == 0L, "an unchanged material generation must not manufacture dirty upload ranges");
   }

   private static void usesPublicationIdentityWhenAsyncUploadSkipsSuccessors() {
      RtSceneMaterialTable.SectionMaterial initial = maskedSlotMaterial(51, 7, 11, 13);
      RtSceneMaterialTable.SectionMaterial middle = maskedSlotMaterial(51, 17, 11, 13);
      RtSceneMaterialTable.SectionMaterial latest = maskedSlotMaterial(51, 17, 19, 13);
      RtSceneMaterialTable.Snapshot bound = Snapshot.fromSectionMaterials(List.of(initial), 1L, 42241);
      RtSceneMaterialTable.Snapshot skipped = Snapshot.fromSectionMaterialsIncremental(bound, List.of(middle), new int[]{0}, 2L, 42241);
      RtSceneMaterialTable.Snapshot pending = Snapshot.fromSectionMaterialsIncremental(skipped, List.of(latest), new int[]{0}, 3L, 42241);
      RtSceneMaterialTable.MaterialUploadDiff skippedDiff = pending.uploadDiffFrom(bound, false);
      require(skippedDiff.dirtyFaceRecords() == (long)latest.faceCount(), "a pending generation whose immediate predecessor is not bound must conservatively scatter its section");
      RtSceneMaterialTable.Snapshot reverted = Snapshot.fromSectionMaterialsIncremental(skipped, List.of(initial), new int[]{0}, 4L, 42241);
      RtSceneMaterialTable.MaterialUploadDiff revertedDiff = reverted.uploadDiffFrom(bound, false);
      require(revertedDiff.dirtyFaceRecords() == 0L, "returning to the exact bound publication must not upload an intermediate material generation");
   }

   private static void materializesSparseFaceRecordsAtTheirAllocatedOffsets() {
      RtSceneMaterialTable.SectionMaterial first = maskedSlotMaterial(101, 7, 8, 9);
      RtSceneMaterialTable.SectionMaterial second = maskedSlotMaterial(202, 17, 18, 19);
      RtSceneMaterialTable.Snapshot snapshot = Snapshot.fromMaterialSlots(List.of(first, second), new int[]{0, 32}, 64, 3L, 64206);
      int[] faceRecords = snapshot.faceRecords();
      int secondFaceOffset = 384;
      require(faceRecords.length == 768, "sparse material snapshot must materialize the complete face table capacity");
      require(faceRecords[3] == first.faceRecords()[3], "first sparse material must be written at its allocated face offset");
      require(faceRecords[secondFaceOffset + 3] == second.faceRecords()[3], "second sparse material must be written at its allocated face offset");
      require(faceRecords[first.faceCount() * 12] == 0, "unused gap between sparse material slots must stay zeroed instead of receiving compacted face data");
      require(faceRecords[15] != second.faceRecords()[3], "sparse face materialization must not compact later slots into the previous slot's tail");
   }

   private static void growsMaterialSlotsWithSlackForMcDynamicFaceChurn() {
      require(MaterialSlotAllocator.grownFaceCapacity(128, 64) == 128, "material slot growth must preserve existing slack while the new face range fits");
      require(MaterialSlotAllocator.grownFaceCapacity(128, 129) == 192, "material slot growth should use 1.5x slack instead of exact-fit reallocations");
      require(MaterialSlotAllocator.grownFaceCapacity(192, 193) == 320, "material slot growth should remain aligned for scatter-friendly face tables");
      int largeGrowth = MaterialSlotAllocator.grownFaceCapacity(320, 1025);
      require(largeGrowth >= 1025 && largeGrowth % 64 == 0, "large material slot growth must cover the requested faces and stay aligned");
      RuntimeException zeroCurrent = expectFailure(() -> MaterialSlotAllocator.grownFaceCapacity(0, 1));
      require(zeroCurrent instanceof IllegalArgumentException, "zero material slot capacity should be rejected at the source");
      RuntimeException zeroRequired = expectFailure(() -> MaterialSlotAllocator.grownFaceCapacity(128, 0));
      require(zeroRequired instanceof IllegalArgumentException, "zero required material faces should be rejected at the source");
      RuntimeException overflow = expectFailure(() -> MaterialSlotAllocator.grownFaceCapacity(2147483646, 2147483647));
      require(overflow instanceof IllegalArgumentException, "material slot growth overflow must fail before corrupting face offsets");
      MaterialSlotAllocator.FaceRangeAllocator faceRanges = new MaterialSlotAllocator.FaceRangeAllocator();
      MaterialSlotAllocator.FaceRange first = faceRanges.allocate(128);
      MaterialSlotAllocator.FaceRange second = faceRanges.allocate(128);
      require(first.firstFace() == 0 && second.firstFace() == 128, "material face allocator must append stable ranges in first-use order");
      faceRanges.release(first);
      MaterialSlotAllocator.FaceRange reusedPrefix = faceRanges.allocate(64);
      require(reusedPrefix.firstFace() == 0 && reusedPrefix.capacity() == 64, "material face allocator must reuse free ranges instead of appending");
      MaterialSlotAllocator.FaceRange grownInPlace = faceRanges.grow(reusedPrefix, 128);
      require(grownInPlace.firstFace() == 0 && grownInPlace.capacity() == 128, "material face allocator must grow in-place into an adjacent free range");
      faceRanges.release(grownInPlace);
      faceRanges.release(second);
      require(faceRanges.freeRangeCount() == 1 && faceRanges.freeFaceCapacity() == 256, "released material face ranges must coalesce for later high-churn reuse");
      MaterialSlotAllocator.FaceRange bestFit = faceRanges.allocate(192);
      require(bestFit.firstFace() == 0 && bestFit.capacity() == 192, "material face allocator must best-fit a coalesced free range before tail growth");
      MaterialSlotAllocator<SectionKey> materialSlots = new MaterialSlotAllocator<>();
      SectionKey firstKey = new SectionKey(0, 0, 0);
      SectionKey secondKey = new SectionKey(1, 0, 0);
      SectionKey recycledKey = new SectionKey(2, 0, 0);
      int firstSlot = materialSlots.update(firstKey, stressSectionMaterial(10, 64));
      int secondSlot = materialSlots.update(secondKey, stressSectionMaterial(11, 64));
      require(firstSlot == 0 && secondSlot == 1, "material slots must allocate stable TLAS custom indices");
      materialSlots.release(firstKey);
      require(materialSlots.freeSlotCount() == 1 && materialSlots.slotCount() == 2, "non-tail section removal must leave a reusable tombstoned material slot");
      int recycledSlot = materialSlots.update(recycledKey, stressSectionMaterial(12, 96));
      require(recycledSlot == firstSlot, "new sections must reuse tombstoned material slots instead of growing the section table");
      require(materialSlots.reusedSlotAllocations() == 1L, "material slot reuse telemetry must catch free-list regressions");
      materialSlots.release(secondKey);
      require(materialSlots.slotCount() == 1 && materialSlots.freeSlotCount() == 0, "tail free slots must be trimmed without shifting active material slots");
   }

   private static void meetsThousandFpsDynamicMaterialSlotChurnStressGate() {
      int sectionSlots = 4096;
      int minFacesPerSection = 40;
      int maxFacesPerSection = 192;
      int frames = 1000;
      int warmupFrames = 256;
      int mutationsPerFrame = 64;
      int recycledSectionsPerFrame = 16;
      long minimumFps = 500L;
      MaterialSlotAllocator<SectionKey> materialSlots = new MaterialSlotAllocator<>();
      SectionKey[] activeKeys = new SectionKey[4096];

      for(int index = 0; index < 4096; ++index) {
         SectionKey key = stressSectionKey(index);
         activeKeys[index] = key;
         materialSlots.update(key, stressSectionMaterial(index, 192));
      }

      SectionKey warmupReplacement = stressSectionKey(1000000);
      materialSlots.release(activeKeys[0]);
      activeKeys[0] = warmupReplacement;
      materialSlots.update(warmupReplacement, stressSectionMaterial(1000000, 192));
      RtSceneMaterialTable.Snapshot previous = Snapshot.fromMaterialSlots(materialSlots.sectionMaterials(), materialSlots.firstFacesArray(), materialSlots.faceCapacity(), 1L, stressMaterialSlotLayoutHash(materialSlots, activeKeys));
      materialSlots.consumeDirtySlots();
      RtSceneMaterialTable.MaterialBufferUploadPlan initialUploadPlan = RtSceneMaterialTable.materialBufferUploadPlan(Snapshot.empty(), previous, 0L, 0L, 0L, 0L);
      long currentSectionBufferBytes = initialUploadPlan.requiredSectionBytes();
      long currentFaceBufferBytes = initialUploadPlan.requiredFaceBytes();
      long currentTextureRecordBufferBytes = initialUploadPlan.requiredTextureRecordBytes();
      long currentTexturePixelBufferBytes = initialUploadPlan.requiredTexturePixelBytes();
      int[] touchedFrames = new int[materialSlots.slotCount() + 16 + 8];
      int[] touchedFaces = new int[touchedFrames.length];
      Arrays.fill(touchedFrames, -1);
      long totalDirtySectionRecords = 0L;
      long totalDirtyFaces = 0L;
      long expectedDirtyFaces = 0L;
      long totalStagedBytes = 0L;
      long maxFrameStagedBytes = 0L;
      long materialBufferChanges = 0L;
      long copiedPreviousBytes = 0L;
      long unexpectedFullUploads = 0L;
      long inPlaceUploads = 0L;
      int nextSectionId = 1000001;
      long start = 0L;

      for(int iteration = 0; iteration < 1256; ++iteration) {
         int frame = iteration;
         boolean measured = iteration >= 256;

         for(int mutation = 0; mutation < 64; ++mutation) {
            int activeIndex = Math.floorMod(frame * 97 + mutation * 53, 4096);
            int faces = stressMcDynamicFaceCount(frame, activeIndex, mutation, 40, 192);
            int slot = materialSlots.update(activeKeys[activeIndex], stressSectionMaterial(200000 + frame * 64 + mutation, faces));
            long dirtyFaces = markTouchedMaterialSlot(frame, slot, faces, touchedFrames, touchedFaces);
            if (measured) {
               expectedDirtyFaces += dirtyFaces;
            }
         }

         for(int recycle = 0; recycle < 16; ++recycle) {
            int activeIndex = Math.floorMod(frame * 211 + recycle * 67 + 2048, 4096);
            materialSlots.release(activeKeys[activeIndex]);
            SectionKey replacement = stressSectionKey(nextSectionId++);
            activeKeys[activeIndex] = replacement;
            int faces = stressMcDynamicFaceCount(frame, activeIndex, recycle + 10000, 40, 192);
            int slot = materialSlots.update(replacement, stressSectionMaterial(400000 + frame * 16 + recycle, faces));
            if (slot >= touchedFrames.length) {
               throw new AssertionError("material slot table grew past the stress gate guard: slot=" + slot + ", slots=" + materialSlots.slotCount());
            }

            long dirtyFaces = markTouchedMaterialSlot(frame, slot, faces, touchedFrames, touchedFaces);
            if (measured) {
               expectedDirtyFaces += dirtyFaces;
            }
         }

         RtSceneMaterialTable.Snapshot next = Snapshot.fromMaterialSlotsIncremental(previous, materialSlots, (long)frame + 2L, stressMaterialSlotLayoutHash(materialSlots, activeKeys));
         RtSceneMaterialTable.MaterialUploadDiff diff = next.uploadDiffFrom(previous, false);
         RtSceneMaterialTable.MaterialBufferUploadPlan uploadPlan = RtSceneMaterialTable.materialBufferUploadPlan(previous, next, currentSectionBufferBytes, currentFaceBufferBytes, currentTextureRecordBufferBytes, currentTexturePixelBufferBytes);
         if (measured) {
            totalDirtySectionRecords += diff.dirtySectionRecords();
            totalDirtyFaces += diff.dirtyFaceRecords();
            totalStagedBytes += diff.stagedBytes();
            maxFrameStagedBytes = Math.max(maxFrameStagedBytes, diff.stagedBytes());
            copiedPreviousBytes += uploadPlan.copiedPreviousBytes();
            if (uploadPlan.materialBuffersChanged()) {
               ++materialBufferChanges;
            }

            if (uploadPlan.fullUpload()) {
               ++unexpectedFullUploads;
            }

            if (uploadPlan.inPlace()) {
               ++inPlaceUploads;
            }
         }

         if (uploadPlan.materialBuffersChanged()) {
            currentSectionBufferBytes = uploadPlan.requiredSectionBytes();
            currentFaceBufferBytes = uploadPlan.requiredFaceBytes();
            currentTextureRecordBufferBytes = uploadPlan.requiredTextureRecordBytes();
            currentTexturePixelBufferBytes = uploadPlan.requiredTexturePixelBytes();
         }

         previous = next;
         if (iteration == 255) {
            start = System.nanoTime();
         }
      }

      long elapsedNanos = Math.max(System.nanoTime() - start, 1L);
      long simulatedFps = 1000000000000L / elapsedNanos;
      long finalFullFrameBytes = checkedStressBytes(materialSlots.slotCount() * 4) + checkedStressBytes(materialSlots.faceCapacity() * 12);
      long fullRewriteBytes = finalFullFrameBytes * 1000L;
      long changedSlotsPerFrameUpperBound = 80L;
      long maxAllowedFrameBytes = changedSlotsPerFrameUpperBound * 2308L * 4L;
      System.out.println("DynamicMaterialSlotChurnStressGate fps=" + simulatedFps + ", frames=1000, sectionSlots=4096, materialSlots=" + materialSlots.slotCount() + ", freeMaterialSlots=" + materialSlots.freeSlotCount() + ", reusedMaterialSlots=" + materialSlots.reusedSlotAllocations() + ", minFacesPerSection=40, maxFacesPerSection=192, mutationsPerFrame=64, recycledSectionsPerFrame=16, faceCapacity=" + materialSlots.faceCapacity() + ", freeFaceRanges=" + materialSlots.freeFaceRangeCount() + ", freeFaceCapacity=" + materialSlots.freeFaceCapacity() + ", reusedFaceRanges=" + materialSlots.reusedFaceRangeAllocations() + ", movedFaceRanges=" + materialSlots.movedFaceRangeAllocations() + ", tailExtendedFaceRanges=" + materialSlots.tailExtendedFaceRangeAllocations() + ", materialBufferChanges=" + materialBufferChanges + ", copiedPreviousBytes=" + copiedPreviousBytes + ", inPlaceUploads=" + inPlaceUploads + ", totalDirtySectionRecords=" + totalDirtySectionRecords + ", totalDirtyFaces=" + totalDirtyFaces + ", totalStagedBytes=" + totalStagedBytes + ", maxFrameStagedBytes=" + maxFrameStagedBytes + ", finalFullFrameBytes=" + finalFullFrameBytes + ", fullRewriteBytes=" + fullRewriteBytes);
      require(totalDirtyFaces == expectedDirtyFaces, "dynamic material slot churn dirty face count drifted: expected=" + expectedDirtyFaces + ", actual=" + totalDirtyFaces);
      require(totalDirtySectionRecords <= 1000L * changedSlotsPerFrameUpperBound, "dynamic material slot churn dirtied too many section records: dirtySectionRecords=" + totalDirtySectionRecords);
      require(maxFrameStagedBytes <= maxAllowedFrameBytes, "dynamic material slot churn staged a broad material update: maxFrameStagedBytes=" + maxFrameStagedBytes + ", maxAllowedFrameBytes=" + maxAllowedFrameBytes);
      require(totalStagedBytes * 50L < fullRewriteBytes, "dynamic material slot churn regressed toward full material rewrites: totalStagedBytes=" + totalStagedBytes + ", fullRewriteBytes=" + fullRewriteBytes);
      boolean condition72 = materialSlots.slotCount() == 4096;
      int value10001 = materialSlots.slotCount();
      require(condition72, "dynamic material slot churn must reuse slots instead of growing the section table: materialSlots=" + value10001);
      condition72 = materialSlots.activeSlotCount() == 4096;
      value10001 = materialSlots.activeSlotCount();
      require(condition72, "dynamic material slot churn lost active section slots: activeSlots=" + value10001);
      condition72 = materialSlots.reusedSlotAllocations() * 100L >= 1520000L;
      long value77 = materialSlots.reusedSlotAllocations();
      require(condition72, "dynamic material slot churn did not exercise slot reuse enough: reusedSlots=" + value77);
      require(materialSlots.reusedFaceRangeAllocations() > 0L, "dynamic material slot churn must reuse material face ranges");
      condition72 = materialSlots.faceCapacity() <= 786433;
      int value78 = materialSlots.faceCapacity();
      require(condition72, "dynamic material slot churn grew face capacity beyond warmed warmed workload bounds: faceCapacity=" + value78);
      require(unexpectedFullUploads == 0L, "dynamic material slot churn should never fall back to full material upload");
      require(materialBufferChanges == 0L, "dynamic material slot churn must stay in-place after warmup: materialBufferChanges=" + materialBufferChanges);
      require(inPlaceUploads == 1000L, "dynamic material slot churn must keep every measured upload in-place: inPlaceUploads=" + inPlaceUploads + ", frames=1000");
      require(copiedPreviousBytes == 0L, "dynamic material slot churn must not copy previous material buffers after warmup: copiedPreviousBytes=" + copiedPreviousBytes);
      require(simulatedFps >= 500L, "dynamic material slot churn stress gate below 500fps: fps=" + simulatedFps + ", frames=1000, totalStagedBytes=" + totalStagedBytes + ", maxFrameStagedBytes=" + maxFrameStagedBytes + ", materialSlots=" + materialSlots.slotCount() + ", faceCapacity=" + materialSlots.faceCapacity() + ", reusedSlots=" + materialSlots.reusedSlotAllocations() + ", reusedFaceRanges=" + materialSlots.reusedFaceRangeAllocations() + ", materialBufferChanges=" + materialBufferChanges);
   }

   private static void meetsThousandFpsMaterialStreamingStressGate() {
      int sectionSlots = 4096;
      int facesPerSection = 64;
      int frames = 1500;
      int mutationsPerFrame = 16;
      int dynamicTextureCount = 128;
      int dynamicTextureSize = 16;
      int textureMutationsPerFrame = 8;
      long minimumFps = 500L;
      boolean profileStages = Boolean.getBoolean("top.ceroxe.rt.rt.materialStreaming.profile");
      List<RtTextureCatalog.TestTexture> textures = new ArrayList<>(128);

      for(int texture = 0; texture < 128; ++texture) {
         textures.add(new RtTextureCatalog.TestTexture("rtrenderer:selftest/material_stress_" + texture, 16, 16, stressTexturePixels(texture, 0, 16)));
      }

      RtTextureCatalog.TestTextureScope textureScope = RtTextureCatalog.installTestTexturesForSelfTest(textures);

      try {
         int[] textureIds = new int[128];

         for(int texture = 0; texture < 128; ++texture) {
            textureIds[texture] = textureScope.textureId("rtrenderer:selftest/material_stress_" + texture);
         }

         List<RtSceneMaterialTable.SectionMaterial> materials = new ArrayList<>(4096);
         int[] firstFaces = new int[4096];

         for(int slot = 0; slot < 4096; ++slot) {
            materials.add(stressSectionMaterial(slot, 64, textureIds));
            firstFaces[slot] = slot * 64;
         }

         int faceCapacity = 262144;
         RtSceneMaterialTable.Snapshot previous = Snapshot.fromMaterialSlots(materials, firstFaces, faceCapacity, 1L, 1);
         RtSceneMaterialTable.MaterialBufferUploadPlan initialUploadPlan = RtSceneMaterialTable.materialBufferUploadPlan(Snapshot.empty(), previous, 0L, 0L, 0L, 0L);
         long currentSectionBufferBytes = initialUploadPlan.requiredSectionBytes();
         long currentFaceBufferBytes = initialUploadPlan.requiredFaceBytes();
         long currentTextureRecordBufferBytes = initialUploadPlan.requiredTextureRecordBytes();
         long currentTexturePixelBufferBytes = initialUploadPlan.requiredTexturePixelBytes();
         long totalDirtyFaces = 0L;
         long totalDirtyTexturePixels = 0L;
         long totalStagedBytes = 0L;
         long maxFrameStagedBytes = 0L;
         long copiedPreviousBytes = 0L;
         long materialBufferChanges = 0L;
         long unexpectedFullUploads = 0L;
         long inPlaceUploads = 0L;
         long sectionMutationNanos = 0L;
         long textureMutationNanos = 0L;
         long snapshotNanos = 0L;
         long diffNanos = 0L;
         long uploadPlanNanos = 0L;
         long start = System.nanoTime();

         for(int frame = 0; frame < 1500; ++frame) {
            long stageStart = profileStages ? System.nanoTime() : 0L;

            for(int mutation = 0; mutation < 16; ++mutation) {
               int slot = Math.floorMod(frame * 97 + mutation * 131, 4096);
               materials.set(slot, stressSectionMaterial(100000 + frame * 16 + mutation, 64, textureIds));
            }

            if (profileStages) {
               sectionMutationNanos += System.nanoTime() - stageStart;
               stageStart = System.nanoTime();
            }

            for(int mutation = 0; mutation < 8; ++mutation) {
               int texture = Math.floorMod(frame * 37 + mutation * 17, 128);
               textureScope.replaceTexturePixels(textureIds[texture], stressTexturePixels(texture, frame + 1, 16));
            }

            if (profileStages) {
               textureMutationNanos += System.nanoTime() - stageStart;
               stageStart = System.nanoTime();
            }

            RtSceneMaterialTable.Snapshot next = Snapshot.fromMaterialSlots(materials, firstFaces, faceCapacity, (long)frame + 2L, frame + 2);
            if (profileStages) {
               snapshotNanos += System.nanoTime() - stageStart;
               stageStart = System.nanoTime();
            }

            RtSceneMaterialTable.MaterialUploadDiff diff = next.uploadDiffFrom(previous, false);
            if (profileStages) {
               diffNanos += System.nanoTime() - stageStart;
               stageStart = System.nanoTime();
            }

            RtSceneMaterialTable.MaterialBufferUploadPlan uploadPlan = RtSceneMaterialTable.materialBufferUploadPlan(previous, next, currentSectionBufferBytes, currentFaceBufferBytes, currentTextureRecordBufferBytes, currentTexturePixelBufferBytes);
            if (profileStages) {
               uploadPlanNanos += System.nanoTime() - stageStart;
            }

            totalDirtyFaces += diff.dirtyFaceRecords();
            totalDirtyTexturePixels += diff.dirtyTexturePixels();
            totalStagedBytes += diff.stagedBytes();
            maxFrameStagedBytes = Math.max(maxFrameStagedBytes, diff.stagedBytes());
            copiedPreviousBytes += uploadPlan.copiedPreviousBytes();
            if (uploadPlan.materialBuffersChanged()) {
               ++materialBufferChanges;
               currentSectionBufferBytes = uploadPlan.requiredSectionBytes();
               currentFaceBufferBytes = uploadPlan.requiredFaceBytes();
               currentTextureRecordBufferBytes = uploadPlan.requiredTextureRecordBytes();
               currentTexturePixelBufferBytes = uploadPlan.requiredTexturePixelBytes();
            }

            if (uploadPlan.fullUpload()) {
               ++unexpectedFullUploads;
            }

            if (uploadPlan.inPlace()) {
               ++inPlaceUploads;
            }

            previous = next;
         }

         long elapsedNanos = Math.max(System.nanoTime() - start, 1L);
         long simulatedFps = 1500000000000L / elapsedNanos;
         long fullFrameBytes = 65536L + (long)faceCapacity * 12L * 4L + checkedStressBytes(previous.textureSnapshot().textureRecords().length) + checkedStressBytes(previous.textureSnapshot().texturePixels().length);
         long fullRewriteBytes = fullFrameBytes * 1500L;
         long expectedDirtyFaces = 1536000L;
         long texturePixelsPerMutation = 256L;
         long expectedDirtyTexturePixels = 12000L * texturePixelsPerMutation;
         long maxAllowedFrameBytes = 49152L + 8L * texturePixelsPerMutation * 4L;
         System.out.println("MaterialStreamingStressGate fps=" + simulatedFps + ", frames=1500, sectionSlots=4096, facesPerSection=64, mutationsPerFrame=16, dynamicTextureCount=128, textureMutationsPerFrame=8, totalDirtyFaces=" + totalDirtyFaces + ", totalDirtyTexturePixels=" + totalDirtyTexturePixels + ", totalStagedBytes=" + totalStagedBytes + ", maxFrameStagedBytes=" + maxFrameStagedBytes + ", inPlaceUploads=" + inPlaceUploads + ", materialBufferChanges=" + materialBufferChanges + ", copiedPreviousBytes=" + copiedPreviousBytes + ", unexpectedFullUploads=" + unexpectedFullUploads + ", fullFrameBytes=" + fullFrameBytes + ", fullRewriteBytes=" + fullRewriteBytes);
         if (profileStages) {
            System.out.println("MaterialStreamingStressGate profile={sectionMutationMs=" + sectionMutationNanos / 1000000L + ", textureMutationMs=" + textureMutationNanos / 1000000L + ", snapshotMs=" + snapshotNanos / 1000000L + ", diffMs=" + diffNanos / 1000000L + ", uploadPlanMs=" + uploadPlanNanos / 1000000L + "}");
         }

         require(totalDirtyFaces == expectedDirtyFaces, "stress gate dirty face count drifted: expected=" + expectedDirtyFaces + ", actual=" + totalDirtyFaces);
         require(totalDirtyTexturePixels == expectedDirtyTexturePixels, "stress gate dirty texture-pixel count drifted: expected=" + expectedDirtyTexturePixels + ", actual=" + totalDirtyTexturePixels);
         require(maxFrameStagedBytes <= maxAllowedFrameBytes, "stress gate regressed to broad material/texture staging: maxFrameStagedBytes=" + maxFrameStagedBytes + ", maxAllowedFrameBytes=" + maxAllowedFrameBytes + ", fullFrameBytes=" + fullFrameBytes);
         require(totalStagedBytes * 100L < fullRewriteBytes, "stress gate staged too much material data: totalStagedBytes=" + totalStagedBytes + ", fullFrameBytes=" + fullFrameBytes + ", fullRewriteBytes=" + fullRewriteBytes);
         require(inPlaceUploads == 1500L, "stress gate did not keep all streaming updates in-place: inPlaceUploads=" + inPlaceUploads + ", frames=1500");
         require(materialBufferChanges == 0L, "stress gate changed material descriptor buffers during streaming: materialBufferChanges=" + materialBufferChanges);
         require(copiedPreviousBytes == 0L, "stress gate copied previous material buffers during streaming: copiedPreviousBytes=" + copiedPreviousBytes);
         require(unexpectedFullUploads == 0L, "stress gate performed full uploads during streaming: unexpectedFullUploads=" + unexpectedFullUploads);
         require(simulatedFps >= 500L, "material streaming stress gate below 500fps: fps=" + simulatedFps + ", frames=1500, sectionSlots=4096, facesPerSection=64, mutationsPerFrame=16, dynamicTextureCount=128, textureMutationsPerFrame=8, totalDirtyFaces=" + totalDirtyFaces + ", totalDirtyTexturePixels=" + totalDirtyTexturePixels + ", totalStagedBytes=" + totalStagedBytes + ", maxFrameStagedBytes=" + maxFrameStagedBytes + ", inPlaceUploads=" + inPlaceUploads + ", materialBufferChanges=" + materialBufferChanges + ", copiedPreviousBytes=" + copiedPreviousBytes + ", unexpectedFullUploads=" + unexpectedFullUploads + ", fullFrameBytes=" + fullFrameBytes + ", fullRewriteBytes=" + fullRewriteBytes);
      } catch (Throwable value71) {
         if (textureScope != null) {
            try {
               textureScope.close();
            } catch (Throwable value70) {
               value71.addSuppressed(value70);
            }
         }

         throw value71;
      }

      if (textureScope != null) {
         textureScope.close();
      }

   }

   private static int rgba8(int red, int green, int blue, int alpha) {
      return red | green << 8 | blue << 16 | alpha << 24;
   }

   private static long checkedStressBytes(int ints) {
      if (ints < 0) {
         throw new IllegalArgumentException("int count must not be negative");
      } else {
         return (long)ints * 4L;
      }
   }

   private static int checkedStressInt(int value) {
      if (value < 0) {
         throw new IllegalArgumentException("stress value must not be negative");
      } else {
         return value;
      }
   }

   private static SectionKey stressSectionKey(int id) {
      if (id < 0) {
         throw new IllegalArgumentException("stress section id must not be negative");
      } else {
         return new SectionKey(id & 1023, id >>> 10 & 63, id >>> 16);
      }
   }

   private static int stressMcDynamicFaceCount(int frame, int section, int salt, int minFaces, int maxFaces) {
      if (minFaces > 0 && maxFaces >= minFaces) {
         int value = frame * 1103515245 + section * 65537 + salt * 4099;
         value ^= value >>> 16;
         return minFaces + Math.floorMod(value, maxFaces - minFaces + 1);
      } else {
         throw new IllegalArgumentException("invalid dynamic face range");
      }
   }

   private static long markTouchedMaterialSlot(int frame, int slot, int faceCount, int[] touchedFrames, int[] touchedFaces) {
      if (slot >= 0 && slot < touchedFrames.length && touchedFrames.length == touchedFaces.length) {
         if (faceCount <= 0) {
            throw new IllegalArgumentException("faceCount must be positive");
         } else {
            long previousFacesThisFrame = touchedFrames[slot] == frame ? (long)touchedFaces[slot] : 0L;
            touchedFrames[slot] = frame;
            touchedFaces[slot] = faceCount;
            return (long)faceCount - previousFacesThisFrame;
         }
      } else {
         throw new IllegalArgumentException("invalid touched material slot table");
      }
   }

   private static int stressMaterialSlotLayoutHash(MaterialSlotAllocator<SectionKey> materialSlots, SectionKey[] activeKeys) {
      int result = 1;

      for(int index = 0; index < activeKeys.length; ++index) {
         SectionKey key = (SectionKey)Objects.requireNonNull(activeKeys[index], "active stress section key");
         Integer slot = materialSlots.slotFor(key);
         if (slot == null) {
            throw new AssertionError("missing material slot for active stress key " + String.valueOf(key));
         }

         result = 31 * result + index;
         result = 31 * result + slot;
         result = 31 * result + key.x();
         result = 31 * result + key.y();
         result = 31 * result + key.z();
      }

      return result;
   }

   private static int[] stressTexturePixels(int texture, int frame, int size) {
      if (size <= 0) {
         throw new IllegalArgumentException("texture size must be positive");
      } else {
         int[] pixels = new int[Math.multiplyExact(size, size)];

         for(int y = 0; y < size; ++y) {
            for(int x = 0; x < size; ++x) {
               int value = texture * 1103515245 + frame * 65537 + x * 257 + y * 4099;
               int red = 96 + (value >>> 3 & 127);
               int green = 24 + (value >>> 11 & 127);
               int blue = 8 + (value >>> 19 & 63);
               pixels[y * size + x] = rgba8(red, green, blue, 255);
            }
         }

         return pixels;
      }
   }

   private static void putPixel(byte[] target, int pixelIndex, int rgba8) {
      int byteOffset = pixelIndex * 4;
      target[byteOffset] = (byte)rgba8;
      target[byteOffset + 1] = (byte)(rgba8 >>> 8);
      target[byteOffset + 2] = (byte)(rgba8 >>> 16);
      target[byteOffset + 3] = (byte)(rgba8 >>> 24);
   }

   private static RtSceneMaterialTable.SectionMaterial stressSectionMaterial(int seed, int faces) {
      return stressSectionMaterial(seed, faces, new int[0]);
   }

   private static RtSceneMaterialTable.SectionMaterial maskedSlotMaterial(int voxelTypeId, int firstMaskedTexture, int secondMaskedTexture, int opaqueTexture) {
      int metadata = FaceDirection.POSITIVE_Z.ordinal() << 8 | 16777216;
      int uv0 = RtTextureCatalog.packUv16(0.0F, 0.0F);
      int uv1 = RtTextureCatalog.packUv16(1.0F, 0.0F);
      int uv2 = RtTextureCatalog.packUv16(1.0F, 1.0F);
      int uv3 = RtTextureCatalog.packUv16(0.0F, 1.0F);
      int[] records = new int[36];
      System.arraycopy(singlePackedFaceRecord(voxelTypeId, metadata, 0, opaqueTexture, uv0, uv1, uv2, uv3), 0, records, 0, 12);
      System.arraycopy(singlePackedFaceRecord(voxelTypeId, metadata, 0, 1073741824 | firstMaskedTexture, uv0, uv1, uv2, uv3), 0, records, 12, 12);
      System.arraycopy(singlePackedFaceRecord(voxelTypeId, metadata, 0, 1073741824 | secondMaskedTexture, uv0, uv1, uv2, uv3), 0, records, 24, 12);
      return new RtSceneMaterialTable.SectionMaterial(records, 1);
   }

   private static int[] singlePackedFaceRecord(int voxelTypeId, int metadata, int mapColor, int textureInfo, int uv0, int uv1, int uv2, int uv3) {
      int[] record = new int[12];
      record[0] = voxelTypeId;
      record[1] = metadata;
      record[2] = mapColor;
      record[3] = textureInfo;
      record[4] = uv0;
      record[5] = uv1;
      record[6] = uv2;
      record[7] = uv3;
      int direction = metadata >>> 8 & 255;
      FaceDirection faceDirection = direction >= 0 && direction < FaceDirection.values().length ? FaceDirection.values()[direction] : FaceDirection.POSITIVE_Y;
      int vertexLighting = PackedVoxelLighting.packFlatVertex(mapColor, faceDirection);
      record[8] = vertexLighting;
      record[9] = vertexLighting;
      record[10] = vertexLighting;
      record[11] = vertexLighting;
      return record;
   }

   private static RtSceneMaterialTable.SectionMaterial stressSectionMaterial(int seed, int faces, int[] textureIds) {
      if (faces <= 0) {
         throw new IllegalArgumentException("faces must be positive");
      } else {
         textureIds = Arrays.copyOf((int[])Objects.requireNonNull(textureIds, "textureIds"), textureIds.length);
         int[] records = new int[faces * 12];
         int uv0 = RtTextureCatalog.packUv16(0.0F, 0.0F);
         int uv1 = RtTextureCatalog.packUv16(1.0F, 0.0F);
         int uv2 = RtTextureCatalog.packUv16(1.0F, 1.0F);
         int uv3 = RtTextureCatalog.packUv16(0.0F, 1.0F);

         for(int face = 0; face < faces; ++face) {
            int offset = face * 12;
            int value = seed * 1103515245 + face * 12345;
            int mediumAmount = value >>> 3 & 7;
            int direction = face % 6;
            int lightEmission = value >>> 11 & 15;
            int flags = 1 | (mediumAmount == 0 ? 0 : 4) | 32;
            records[offset] = 10000 + seed * 31 + face;
            records[offset + 1] = mediumAmount | direction << 8 | lightEmission << 16 | flags << 24;
            records[offset + 2] = 3158064 | value >>> 8 & 12632256;
            records[offset + 3] = textureIds.length == 0 ? 0 : textureIds[Math.floorMod(seed + face, textureIds.length)];
            records[offset + 4] = uv0;
            records[offset + 5] = uv1;
            records[offset + 6] = uv2;
            records[offset + 7] = uv3;
            int vertexLighting = PackedVoxelLighting.packVertex(240, 240, PackedVoxelLighting.cardinalShade(FaceDirection.values()[direction]));
            records[offset + 8] = vertexLighting;
            records[offset + 9] = vertexLighting;
            records[offset + 10] = vertexLighting;
            records[offset + 11] = vertexLighting;
         }

         return new RtSceneMaterialTable.SectionMaterial(records);
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException ex) {
         return ex;
      }

      throw new AssertionError("expected failure");
   }
}
