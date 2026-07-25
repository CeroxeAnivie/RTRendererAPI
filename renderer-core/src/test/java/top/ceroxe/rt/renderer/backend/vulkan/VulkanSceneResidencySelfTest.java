package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;

public final class VulkanSceneResidencySelfTest {
   private VulkanSceneResidencySelfTest() {
   }

   public static void main(String[] args) {
      prepareDoesNotPublishAndCommitIsAtomic();
      retainsIdentitySlotsAndReusesReleasedCapacity();
      rejectsStalePreparedGenerationBeforeAnyDomainMutation();
      resetRetainsSurvivingIdentitiesAndClearsOtherDomains();
      sparseUpdatesDoNotRewriteOrExpandStableDomain();
      System.out.println("VulkanSceneResidencySelfTest passed");
   }

   private static void prepareDoesNotPublishAndCommitIsAtomic() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(initialScene(0L));
      require(residency.state().textures().liveSlots() == 0, "prepare published texture residency before native admission");
      require(prepared.textures().writes().size() == 2 && prepared.materials().writes().size() == 1 && prepared.meshes().writes().size() == 1 && prepared.instances().writes().size() == 1, "initial change set did not preserve independent dirty domains");
      VulkanSceneResidency.SceneResidencyState committed = residency.commit(prepared);
      require(committed.revision() == 0L && committed.textures().liveSlots() == 2 && committed.instances().liveSlots() == 1, "atomic resident publication lost a resource domain");
      expect(IllegalStateException.class, () -> residency.commit(prepared));
   }

   private static void retainsIdentitySlotsAndReusesReleasedCapacity() {
      VulkanSceneResidency residency = populatedResidency();
      int retainedSlot = residency.textureSlot(20L);
      int releasedSlot = residency.textureSlot(10L);
      TextureAsset replacement = texture(20L, -14535868);
      TextureAsset newTexture = texture(30L, -11180425);
      SceneTransaction update = SceneTransaction.builder(1L).upsertTextures(List.of(replacement, newTexture)).removeTexture(10L).build();
      VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(update);
      require(residency.textureSlot(10L) == releasedSlot && residency.textureSlot(30L) == -1, "prepared update mutated the live identity map");
      residency.commit(prepared);
      require(residency.textureSlot(20L) == retainedSlot, "updating an existing texture moved its persistent slot");
      require(residency.textureSlot(30L) == releasedSlot, "new texture did not reuse the lowest released slot");
      require(prepared.textures().slotUpperBound() == 2, "slot reuse unnecessarily increased the GPU buffer high-water mark");
   }

   private static void rejectsStalePreparedGenerationBeforeAnyDomainMutation() {
      VulkanSceneResidency residency = populatedResidency();
      VulkanSceneResidency.PreparedUpdate first = residency.prepare(SceneTransaction.empty(1L));
      VulkanSceneResidency.PreparedUpdate stale = residency.prepare(SceneTransaction.empty(2L));
      residency.commit(first);
      VulkanSceneResidency.SceneResidencyState beforeFailure = residency.state();
      expect(IllegalStateException.class, () -> residency.commit(stale));
      require(residency.state().equals(beforeFailure), "stale prepared update partially changed resident domains");
   }

   private static void resetRetainsSurvivingIdentitiesAndClearsOtherDomains() {
      VulkanSceneResidency residency = populatedResidency();
      int survivingSlot = residency.textureSlot(20L);
      SceneTransaction reset = SceneTransaction.builder(1L).resetScene().upsertTextures(List.of(texture(20L, -5517841), texture(40L, -16711165))).build();
      residency.commit(residency.prepare(reset));
      VulkanSceneResidency.SceneResidencyState state = residency.state();
      require(residency.textureSlot(20L) == survivingSlot, "authoritative reset moved a surviving identity");
      require(state.textures().liveSlots() == 2 && state.materials().liveSlots() == 0 && state.meshes().liveSlots() == 0 && state.instances().liveSlots() == 0, "authoritative reset did not clear omitted resident domains");
   }

   private static void sparseUpdatesDoNotRewriteOrExpandStableDomain() {
      int residentCount = 16384;
      int changedCount = 128;
      StableIdentitySlots<SlotValue> slots = new StableIdentitySlots<>(SlotValue::id);
      ArrayList<SlotValue> initial = new ArrayList<>(residentCount);

      for(int index = 0; index < residentCount; ++index) {
         initial.add(new SlotValue((long)index, 0));
      }

      StableIdentitySlots.Prepared<SlotValue> bootstrap = slots.prepare(0L, true, initial, LongBuffer.allocate(0));
      slots.validate(bootstrap);
      slots.commitValidated(bootstrap);
      long[] removals = new long[changedCount];
      ArrayList<SlotValue> updates = new ArrayList<>(changedCount * 2);

      for(int index = 0; index < changedCount; ++index) {
         removals[index] = (long)index;
         updates.add(new SlotValue(1000L + (long)index, 1));
         updates.add(new SlotValue((long)(residentCount + index), 1));
      }

      StableIdentitySlots.Prepared<SlotValue> sparse = slots.prepare(1L, false, updates, LongBuffer.wrap(removals));
      require(sparse.writes().size() == changedCount * 2, "sparse preparation rewrote unchanged resident slots");
      require(sparse.clearedSlots().length == 0, "slots overwritten in the same generation were redundantly cleared");
      require(sparse.slotUpperBound() == residentCount, "released capacity was not reused before increasing the high-water mark");
      slots.validate(sparse);
      slots.commitValidated(sparse);
      require(slots.liveCount() == residentCount && slots.slotUpperBound() == residentCount, "sparse churn changed resident capacity accounting");
   }

   private static VulkanSceneResidency populatedResidency() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      residency.commit(residency.prepare(initialScene(0L)));
      return residency;
   }

   private static SceneTransaction initialScene(long revision) {
      MaterialAsset material = MaterialAsset.builder(50L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-1).emissive(255, 0.0F).alphaCutoff(0.5F).roughness(1.0F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(false).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MeshAsset mesh = MeshAsset.triangles(60L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, 50L);
      SceneInstance instance = SceneInstance.builder(70L, 60L).build();
      return SceneTransaction.builder(revision).resetScene().upsertTextures(List.of(texture(20L, -15720400), texture(10L, -12562336))).upsert(material).upsert(mesh).upsert(instance).build();
   }

   private static TextureAsset texture(long id, int rgba8) {
      return TextureAsset.builder(id, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[]{(byte)rgba8, (byte)(rgba8 >>> 8), (byte)(rgba8 >>> 16), (byte)(rgba8 >>> 24)}).build();
   }

   private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return (T)(type.cast(failure));
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static record SlotValue(long id, int generation) {
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
