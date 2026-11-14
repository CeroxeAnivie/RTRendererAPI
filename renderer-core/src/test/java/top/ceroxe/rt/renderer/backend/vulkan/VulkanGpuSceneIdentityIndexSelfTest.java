package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;

public final class VulkanGpuSceneIdentityIndexSelfTest {
   private VulkanGpuSceneIdentityIndexSelfTest() {
   }

   public static void main(String[] arguments) {
      resolvesInitialAndIncrementalGenerationsWithoutEarlyPublication();
      removalAndReplacementResolveAgainstThePreparedGeneration();
      resetDropsOmittedIdentitiesEvenWhenSlotsSurvive();
      System.out.println("VulkanGpuSceneIdentityIndexSelfTest passed");
   }

   private static void resolvesInitialAndIncrementalGenerationsWithoutEarlyPublication() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuSceneIdentityIndex index = new VulkanGpuSceneIdentityIndex();
      VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, List.of(texture(10L), texture(20L)), new long[0]));
      VulkanGpuSceneIdentityIndex.Prepared prepared = index.prepare(initial.changeSet());
      require(prepared.textureSlot(10L) == ((StableIdentitySlots.SlotWrite)initial.textures().writes().get(0)).slot() || prepared.textureSlot(10L) == ((StableIdentitySlots.SlotWrite)initial.textures().writes().get(1)).slot(), "initial identity did not resolve through the prepared overlay");
      require(index.revision() == -1L, "prepare published identity index revision early");
      index.commit(prepared);
      residency.commit(initial);
      VulkanSceneResidency.PreparedUpdate noChange = residency.prepare(SceneTransaction.empty(1L));
      VulkanGpuSceneIdentityIndex.Prepared successor = index.prepare(noChange.changeSet());
      require(successor.textureSlot(10L) >= 0 && successor.textureSlot(20L) >= 0, "incremental overlay could not resolve existing identities");
      index.commit(successor);
      residency.commit(noChange);
   }

   private static void removalAndReplacementResolveAgainstThePreparedGeneration() {
      Fixture fixture = populated();
      int oldSlot = fixture.residency.textureSlot(10L);
      VulkanSceneResidency.PreparedUpdate update = fixture.residency.prepare(transaction(1L, false, List.of(texture(30L)), new long[]{10L}));
      VulkanGpuSceneIdentityIndex.Prepared rejected = fixture.index.prepare(update.changeSet());
      require(rejected.textureSlot(10L) == -1 && rejected.textureSlot(30L) == oldSlot, "prepared overlay did not hide removal or expose replacement slot reuse");
      require(fixture.index.revision() == 0L, "discarding an uncommitted overlay advanced the persistent identity index");
      VulkanGpuSceneIdentityIndex.Prepared retry = fixture.index.prepare(update.changeSet());
      fixture.index.commit(retry);
      fixture.residency.commit(update);
      require(fixture.index.revision() == 1L, "accepted retry did not advance identity index");
   }

   private static void resetDropsOmittedIdentitiesEvenWhenSlotsSurvive() {
      Fixture fixture = populated();
      VulkanSceneResidency.PreparedUpdate reset = fixture.residency.prepare(transaction(1L, true, List.of(texture(20L)), new long[0]));
      VulkanGpuSceneIdentityIndex.Prepared prepared = fixture.index.prepare(reset.changeSet());
      require(prepared.textureSlot(10L) == -1 && prepared.textureSlot(20L) >= 0, "reset overlay retained an omitted identity");
      fixture.index.commit(prepared);
      fixture.residency.commit(reset);
      VulkanSceneResidency.PreparedUpdate next = fixture.residency.prepare(SceneTransaction.empty(2L));
      VulkanGpuSceneIdentityIndex.Prepared persisted = fixture.index.prepare(next.changeSet());
      require(persisted.textureSlot(10L) == -1 && persisted.textureSlot(20L) >= 0, "reset commit did not replace the persistent identity map");
   }

   private static Fixture populated() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuSceneIdentityIndex index = new VulkanGpuSceneIdentityIndex();
      VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, List.of(texture(10L), texture(20L)), new long[0]));
      index.commit(index.prepare(initial.changeSet()));
      residency.commit(initial);
      return new Fixture(residency, index);
   }

   private static SceneTransaction transaction(long revision, boolean reset, List<TextureAsset> textures, long[] removals) {
      SceneTransaction.Builder builder = SceneTransaction.builder(revision).upsertTextures(textures).removeTextures(removals);
      if (reset) {
         builder.resetScene();
      }

      return builder.build();
   }

   private static TextureAsset texture(long id) {
      return TextureAsset.builder(id, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[4]).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static record Fixture(VulkanSceneResidency residency, VulkanGpuSceneIdentityIndex index) {
   }
}
