package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.orchestration.work.SectionWorkLane;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.SectionMaterial;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

public final class RtSectionBlasBuildBatchSelfTest {
   private RtSectionBlasBuildBatchSelfTest() {
   }

   public static void main(String[] arguments) {
      claimedIdentityOrderOwnsEveryMetadataField();
      malformedClaimsFailBeforeNativeRecording();
      metadataRejectsInvalidGenerations();
      System.out.println("RtSectionBlasBuildBatchSelfTest passed");
   }

   private static void claimedIdentityOrderOwnsEveryMetadataField() {
      SectionTriangleMesh first = mesh(3);
      SectionTriangleMesh second = mesh(7);
      RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> firstWork = work(first, 11L, 32);
      RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> secondWork = work(second, 19L, 64);
      List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> candidates = new ArrayList<>(List.of(firstWork, secondWork));
      List<SectionTriangleMesh> claimed = new ArrayList<>(List.of(second, first));
      RtSectionBlasBuildBatch batch = RtSectionBlasBuildBatch.capture(candidates, claimed);
      candidates.clear();
      claimed.clear();
      require(batch.meshes().equals(List.of(second, first)), "batch did not preserve native claim identity order");
      require(batch.workItems().equals(List.of(secondWork, firstWork)), "claimed work order diverged from mesh ownership order");
      require(((RtSectionBlasBuildBatch.Section)batch.sections().get(0)).metadata().contentRevision() == 19L && ((RtSectionBlasBuildBatch.Section)batch.sections().get(0)).metadata().sourceFlags() == 64, "section metadata drifted from its claimed mesh");
      require(((RtSectionBlasBuildBatch.Section)batch.sections().get(1)).metadata().contentRevision() == 11L && ((RtSectionBlasBuildBatch.Section)batch.sections().get(1)).metadata().sourceFlags() == 32, "second claimed section metadata drifted");
      require(batch.retainedEstimatedBytes() == Math.addExact(first.estimatedBytes(), second.estimatedBytes()), "batch byte retention did not cover every claimed mesh");
      expectUnsupported(() -> batch.meshes().add(mesh(9)));
      expectUnsupported(() -> batch.sections().clear());
   }

   private static void malformedClaimsFailBeforeNativeRecording() {
      SectionTriangleMesh first = mesh(1);
      RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work = work(first, 1L, 0);
      expectIllegalArgument(() -> RtSectionBlasBuildBatch.capture(List.of(work, work), List.of(first)));
      expectIllegalArgument(() -> RtSectionBlasBuildBatch.capture(List.of(work), List.of(mesh(1))));
      expectIllegalArgument(() -> RtSectionBlasBuildBatch.capture(List.of(work), List.of(first, first)));
      expectIllegalArgument(() -> RtSectionBlasBuildBatch.capture(List.of(work), List.of()));
   }

   private static void metadataRejectsInvalidGenerations() {
      SectionTriangleMesh mesh = mesh(5);
      RtSceneMaterialTable.SectionMaterial material = SectionMaterial.fromMesh(mesh);
      expectIllegalArgument(() -> new RtSectionBlasBuildMetadata(-1L, RendererFrameCausality.untraced(1L), 0, material));
      expectNullPointer(() -> new RtSectionBlasBuildMetadata(1L, (RendererFrameCausality)null, 0, material));
      expectNullPointer(() -> new RtSectionBlasBuildMetadata(1L, RendererFrameCausality.untraced(1L), 0, (RtSceneMaterialTable.SectionMaterial)null));
   }

   private static RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work(SectionTriangleMesh mesh, long revision, int sourceFlags) {
      return new RtPendingBlasBuildQueue.Work<>(mesh, new RtSectionBlasBuildMetadata(revision, RendererFrameCausality.untraced(revision), sourceFlags, SectionMaterial.fromMesh(mesh)), SectionWorkLane.BACKGROUND);
   }

   private static SectionTriangleMesh mesh(int sectionX) {
      return new SectionTriangleMesh(new SectionKey(sectionX, 0, 0), new short[]{0, 0, 0, 16, 0, 0, 16, 16, 0, 0, 16, 0}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{42}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()});
   }

   private static void expectIllegalArgument(Runnable action) {
      expect(IllegalArgumentException.class, action);
   }

   private static void expectNullPointer(Runnable action) {
      expect(NullPointerException.class, action);
   }

   private static void expectUnsupported(Runnable action) {
      expect(UnsupportedOperationException.class, action);
   }

   private static void expect(Class<? extends RuntimeException> type, Runnable action) {
      try {
         action.run();
      } catch (RuntimeException failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw failure;
      }

      throw new AssertionError("expected " + type.getSimpleName());
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
