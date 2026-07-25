package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.SectionMaterial;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.Snapshot;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

public final class RtSectionMaterialPublicationStateSelfTest {
   private RtSectionMaterialPublicationStateSelfTest() {
   }

   public static void main(String[] arguments) {
      RtSectionMaterialPublicationState state = new RtSectionMaterialPublicationState(RtMaterialTelemetrySink.NOOP);
      SectionTriangleMesh mesh = mesh();
      SectionKey key = mesh.key();
      RtSceneMaterialTable.SectionMaterial material = SectionMaterial.fromMesh(mesh);
      require(state.revision() == 0L && state.slotCount() == 0, "new material publication owner was not empty");
      state.submit(key, material);
      require(state.revision() == 1L && state.activeSlotCount() == 1 && state.slotFor(key) != null, "material submit did not commit slot and revision together");
      RtSectionMaterialPublicationState.Composition first = state.compose(Snapshot.empty(), 17);
      require(first.changed() && first.snapshot().sectionCount() == 1, "first material composition did not publish the active slot");
      RtSectionMaterialPublicationState.Composition stable = state.compose(Snapshot.empty(), 17);
      require(!stable.changed() && stable.snapshot().signature().equals(first.snapshot().signature()), "stable material generation changed its immutable publication signature");
      state.remove(key);
      require(state.revision() == 2L && state.activeSlotCount() == 0, "material removal did not commit tombstone and revision together");
      state.clearAndAdvance();
      require(state.revision() == 3L && state.slotCount() == 0, "published material clear did not advance exactly one revision");
      state.submit(key, material);
      long revisionBeforeDiscard = state.revision();
      state.discard();
      require(state.revision() == revisionBeforeDiscard && state.slotCount() == 0, "terminal discard unexpectedly published a successor revision");
      state.advanceExternalRevision();
      require(state.revision() == revisionBeforeDiscard + 1L, "FarField material mutation did not advance shared composition identity");
      System.out.println("RtSectionMaterialPublicationStateSelfTest passed");
   }

   private static SectionTriangleMesh mesh() {
      return new SectionTriangleMesh(new SectionKey(2, 3, 4), new short[]{0, 0, 0, 16, 0, 0, 16, 16, 0, 0, 16, 0}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{42}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()});
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
