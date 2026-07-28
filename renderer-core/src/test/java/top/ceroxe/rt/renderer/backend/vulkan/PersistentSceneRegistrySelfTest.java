package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneRevisionException;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SceneValidationException;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;

public final class PersistentSceneRegistrySelfTest {
   private PersistentSceneRegistrySelfTest() {
   }

   public static void main(String[] args) {
      preparationDoesNotPublishBeforeCommit();
      invalidReferenceDoesNotAdvanceState();
      stalePreparedMutationCannotOverwriteNewerGeneration();
      strictRemovalAndResetRulesPreserveState();
      reverseReferencesTrackDependencyRewrites();
      resetRebuildsReverseReferences();
      snapshotOrderIsDeterministic();
      System.out.println("PersistentSceneRegistrySelfTest passed");
   }

   private static void preparationDoesNotPublishBeforeCommit() {
      PersistentSceneRegistry registry = new PersistentSceneRegistry();
      PersistentSceneRegistry.PreparedMutation prepared = registry.prepare(completeScene(0L));
      require(registry.snapshot().meshes().isEmpty(), "prepare published mesh state before admission");
      require(prepared.prospectiveState().instances() == 1, "prospective instance count changed");
      PersistentSceneRegistry.SceneState committed = registry.commit(prepared);
      require(committed.revision() == 0L, "committed revision changed");
      require(committed.textures() == 1 && committed.materials() == 1 && committed.meshes() == 1 && committed.instances() == 1 && committed.lights() == 1, "complete scene was not committed");
      expect(IllegalStateException.class, () -> registry.commit(prepared));
      expect(SceneRevisionException.class, () -> registry.prepare(SceneTransaction.empty(0L)));
   }

   private static void invalidReferenceDoesNotAdvanceState() {
      PersistentSceneRegistry registry = populatedRegistry();
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(1L).upsert(mesh(30L, 999L)).build()));
      PersistentSceneRegistry.SceneState state = registry.state();
      require(state.revision() == 0L && state.meshes() == 1, "invalid transaction mutated scene authority");
   }

   private static void stalePreparedMutationCannotOverwriteNewerGeneration() {
      PersistentSceneRegistry registry = populatedRegistry();
      PersistentSceneRegistry.PreparedMutation first = registry.prepare(SceneTransaction.empty(1L));
      PersistentSceneRegistry.PreparedMutation competing = registry.prepare(SceneTransaction.empty(2L));
      registry.commit(first);
      expect(IllegalStateException.class, () -> registry.commit(competing));
      require(registry.state().revision() == 1L, "stale prepared mutation overwrote committed generation");
   }

   private static void strictRemovalAndResetRulesPreserveState() {
      PersistentSceneRegistry registry = populatedRegistry();
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(1L).removeMesh(999L).build()));
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(1L).resetScene().removeTexture(1L).build());
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(1L).removeMesh(3L).build()));
      require(registry.state().revision() == 0L && registry.state().instances() == 1, "rejected removal changed the published scene");
      PersistentSceneRegistry.PreparedMutation removal = registry.prepare(SceneTransaction.builder(1L).removeTexture(1L).removeMaterial(2L).removeMesh(3L).removeInstance(4L).removeLight(5L).build());
      PersistentSceneRegistry.SceneState empty = registry.commit(removal);
      require(empty.textures() == 0 && empty.materials() == 0 && empty.meshes() == 0 && empty.instances() == 0 && empty.lights() == 0, "complete dependency removal failed");
   }

   private static void reverseReferencesTrackDependencyRewrites() {
      PersistentSceneRegistry registry = populatedRegistry();
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(1L).removeTexture(1L).build()));
      PersistentSceneRegistry.PreparedMutation rewrite = registry.prepare(SceneTransaction.builder(1L).upsert(material(2L, -1L)).removeTexture(1L).build());
      registry.commit(rewrite);
      require(registry.state().revision() == 1L && registry.state().textures() == 0, "dependency rewrite did not release removed texture");
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(2L).removeMaterial(2L).build()));
      require(registry.state().revision() == 1L, "failed reverse-reference validation advanced revision");
   }

   private static void resetRebuildsReverseReferences() {
      PersistentSceneRegistry registry = populatedRegistry();
      registry.commit(registry.prepare(SceneTransaction.builder(1L).resetScene().upsert(texture(11L)).upsert(material(12L, 11L)).upsert(mesh(13L, 12L)).upsert(instance(14L, 13L)).build()));
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(2L).removeTexture(11L).build()));
      expect(SceneValidationException.class, () -> registry.prepare(SceneTransaction.builder(2L).removeTexture(1L).build()));
      require(registry.state().revision() == 1L, "reset reverse-reference rejection changed authority");
   }

   private static void snapshotOrderIsDeterministic() {
      PersistentSceneRegistry registry = new PersistentSceneRegistry();
      SceneTransaction transaction = SceneTransaction.builder(0L).resetScene().upsertMaterials(List.of(material(20L, -1L), material(10L, -1L))).upsertMeshes(List.of(mesh(40L, 20L), mesh(30L, 10L))).upsertInstances(List.of(instance(60L, 40L), instance(50L, 30L))).build();
      registry.commit(registry.prepare(transaction));
      PersistentSceneRegistry.Snapshot snapshot = registry.snapshot();
      require(((MaterialAsset)snapshot.materials().get(0)).id() == 10L && ((MeshAsset)snapshot.meshes().get(0)).id() == 30L && ((SceneInstance)snapshot.instances().get(0)).id() == 50L, "snapshot identities are not sorted");
   }

   private static PersistentSceneRegistry populatedRegistry() {
      PersistentSceneRegistry registry = new PersistentSceneRegistry();
      registry.commit(registry.prepare(completeScene(0L)));
      return registry;
   }

   private static SceneTransaction completeScene(long revision) {
      return SceneTransaction.builder(revision).resetScene().upsert(texture(1L)).upsert(material(2L, 1L)).upsert(mesh(3L, 2L)).upsert(instance(4L, 3L)).upsert(light(5L)).build();
   }

   private static TextureAsset texture(long id) {
      return TextureAsset.builder(id, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[]{-1, -1, -1, -1}).build();
   }

   private static MaterialAsset material(long id, long textureId) {
      return MaterialAsset.builder(id).blendMode(BlendMode.OPAQUE).baseColorRgba8(-1).baseColorTextureId(textureId).emissive(255, 0.0F).alphaCutoff(0.5F).roughness(1.0F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(false).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
   }

   private static MeshAsset mesh(long id, long materialId) {
      return MeshAsset.triangles(id, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, materialId);
   }

   private static SceneInstance instance(long id, long meshId) {
      return SceneInstance.builder(id, meshId).build();
   }

   private static SceneLight light(long id) {
      return SceneLight.point(id, 0.0, 1.0, 0.0).color(1.0F, 1.0F, 1.0F).intensity(10.0F).range(8.0F).build();
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

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
