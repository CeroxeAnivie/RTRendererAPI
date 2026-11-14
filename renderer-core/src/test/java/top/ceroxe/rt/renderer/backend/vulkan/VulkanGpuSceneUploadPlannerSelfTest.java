package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;

public final class VulkanGpuSceneUploadPlannerSelfTest {
   private VulkanGpuSceneUploadPlannerSelfTest() {
   }

   public static void main(String[] arguments) {
      completeScenePacksEveryGpuDomainAndResolvesReferences();
      instanceOnlyGenerationDoesNotRewriteUnchangedGpuDomains();
      triangleMaterialUpdateDoesNotRewriteBlasGeometry();
      uploadsCompleteMipChainAsOneTextureGeneration();
      System.out.println("VulkanGpuSceneUploadPlannerSelfTest passed");
   }

   private static void uploadsCompleteMipChainAsOneTextureGeneration() {
      byte[] mipBytes = new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))];

      for(int index = 0; index < mipBytes.length; ++index) {
         mipBytes[index] = (byte)index;
      }

      TextureAsset texture = TextureAsset.builder(10L, 4, 2).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.LINEAR).mipChainRgba8(3, mipBytes).build();
      Fixture fixture = new Fixture();
      PreparedGeneration generation = fixture.prepare(SceneTransaction.builder(0L).resetScene().upsert(texture).build());
      VulkanGpuSceneUploadPlanner.Chunk pixels = only(generation.plan, Target.TEXTURE_PIXELS);
      VulkanGpuSceneUploadPlanner.Chunk record = only(generation.plan, Target.TEXTURE_RECORDS);
      ByteBuffer words = ByteBuffer.wrap(record.payload()).order(ByteOrder.LITTLE_ENDIAN);
      require(Arrays.equals(pixels.payload(), mipBytes) && words.getInt(48) == 3, "one texture generation must upload all mip bytes and publish their level count atomically");
   }

   private static void completeScenePacksEveryGpuDomainAndResolvesReferences() {
      Fixture fixture = new Fixture();
      PreparedGeneration generation = fixture.prepare(initialScene());
      VulkanGpuSceneUploadPlanner.Plan plan = generation.plan;
      EnumSet<VulkanGpuSceneUploadPlanner.Target> targets = EnumSet.noneOf(VulkanGpuSceneUploadPlanner.Target.class);
      plan.chunks().forEach((chunk) -> targets.add(chunk.target()));
      require(targets.equals(EnumSet.allOf(VulkanGpuSceneUploadPlanner.Target.class)), "complete generic scene did not produce every GPUScene upload target: " + String.valueOf(targets));
      require(chunks(plan, Target.TEXTURE_RECORDS) == 1 && chunks(plan, Target.TEXTURE_PIXELS) == 1, "adjacent texture records or pixels were not coalesced");
      VulkanGpuSceneUploadPlanner.Chunk material = only(plan, Target.MATERIAL_RECORDS);
      ByteBuffer materialWords = ByteBuffer.wrap(material.payload()).order(ByteOrder.LITTLE_ENDIAN);
      require(materialWords.getInt(8) == generation.identities.textureSlot(10L) && materialWords.getInt(12) == generation.identities.textureSlot(11L), "material texture identities were not packed as prepared stable slots");
      VulkanGpuSceneUploadPlanner.Chunk triangleMaterials = only(plan, Target.TRIANGLE_MATERIAL_SLOTS);
      require(ByteBuffer.wrap(triangleMaterials.payload()).order(ByteOrder.LITTLE_ENDIAN).getInt() == generation.identities.materialSlot(20L), "triangle material identity was not packed as a stable material slot");
      require(plan.uploadBytes() > 0L && plan.logicalRecords() >= 10, "upload statistics did not include packed scene work");
   }

   private static void instanceOnlyGenerationDoesNotRewriteUnchangedGpuDomains() {
      Fixture fixture = new Fixture();
      fixture.commit(fixture.prepare(initialScene()), 0L);
      fixture.residency.markFrameRendered(0L);
      SceneInstance moved = SceneInstance.builder(40L, 30L).transform(new AffineTransform(new float[]{1.0F, 0.0F, 0.0F, 8.0F, 0.0F, 1.0F, 0.0F, 4.0F, 0.0F, 0.0F, 1.0F, 2.0F})).mobility(Mobility.DYNAMIC).build();
      SceneTransaction update = SceneTransaction.builder(1L).upsert(moved).build();
      PreparedGeneration generation = fixture.prepare(update);
      boolean condition10000 = generation.plan.chunks().size() == 1 && ((VulkanGpuSceneUploadPlanner.Chunk)generation.plan.chunks().get(0)).target() == Target.INSTANCE_RECORDS;
      String details10001 = String.valueOf(generation.plan.chunks());
      require(condition10000, "instance-only update rewrote unchanged GPUScene domains: " + details10001);
      VulkanGpuSceneUploadPlanner.Chunk instanceChunk = only(generation.plan, Target.INSTANCE_RECORDS);
      require(instanceChunk.payload().length == VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS * Integer.BYTES,
            "instance upload byte stride diverged from the ABI");
      ByteBuffer instanceWords = ByteBuffer.wrap(instanceChunk.payload()).order(ByteOrder.LITTLE_ENDIAN);
      require(instanceWords.getFloat(28 * Integer.BYTES + 3 * Float.BYTES) == 0.0F,
            "instance upload did not retain the last rendered transform");
      require(instanceWords.getInt(40 * Integer.BYTES) == 1
                  && instanceWords.getInt(41 * Integer.BYTES) == 0,
            "instance upload lost the 64-bit motion revision");
      require(fixture.memory.state().positions().revision() == 0L, "memory preparation published the next revision before admission");
      fixture.commit(generation, 1L);
      require(fixture.memory.state().positions().revision() == 1L, "accepted empty geometry generation did not advance memory authority");

      SceneLight brighter = SceneLight.point(50L, 1.0, 2.0, 3.0)
            .color(1.0F, 0.8F, 0.6F).intensity(200.0F).range(32.0F).build();
      PreparedGeneration unrelated = fixture.prepare(SceneTransaction.builder(2L).upsert(brighter).build());
      require(chunks(unrelated.plan, Target.INSTANCE_RECORDS) == 0,
            "an unrelated light revision rewrote sparse instance records");
   }

   private static void triangleMaterialUpdateDoesNotRewriteBlasGeometry() {
      Fixture fixture = new Fixture();
      fixture.commit(fixture.prepare(initialScene()), 0L);
      MaterialAsset replacement = MaterialAsset.builder(21L).baseColorRgba8(-65536).build();
      MeshAsset remapped = MeshAsset.builder(30L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new long[]{21L}).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).tangents(new float[]{1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F}).textureCoordinates(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).lightmapCoordinates(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).vertexColorsRgba8(new int[]{-1, -16711681, -65281}).build();
      PreparedGeneration generation = fixture.prepare(SceneTransaction.builder(1L).upsert(replacement).upsert(remapped).build());
      VulkanSceneResidency.MeshUpdate update = generation.residency.changeSet().meshUpdates().get(30L);
      require(update.dirtyMask() == VulkanSceneResidency.MeshDirtyMask.TRIANGLE_MATERIALS, "material remap was misclassified as geometry work");
      EnumSet<Target> targets = EnumSet.noneOf(Target.class);
      generation.plan.chunks().forEach(chunk -> targets.add(chunk.target()));
      require(targets.equals(EnumSet.of(Target.MATERIAL_RECORDS, Target.MESH_RECORDS, Target.TRIANGLE_MATERIAL_SLOTS)), "material remap uploaded BLAS geometry streams: " + targets);
      require(generation.residency.changeSet().meshUpdates().blasDirtyCount() == 0, "material remap requested a BLAS build");
   }

   private static int chunks(VulkanGpuSceneUploadPlanner.Plan plan, VulkanGpuSceneUploadPlanner.Target target) {
      return (int)plan.chunks().stream().filter((chunk) -> chunk.target() == target).count();
   }

   private static VulkanGpuSceneUploadPlanner.Chunk only(VulkanGpuSceneUploadPlanner.Plan plan, VulkanGpuSceneUploadPlanner.Target target) {
      List<VulkanGpuSceneUploadPlanner.Chunk> chunks = plan.chunks().stream().filter((chunk) -> chunk.target() == target).toList();
      boolean condition10000 = chunks.size() == 1;
      String details10001 = String.valueOf(target);
      require(condition10000, "expected one " + details10001 + " chunk but found " + chunks.size());
      return (VulkanGpuSceneUploadPlanner.Chunk)chunks.get(0);
   }

   private static SceneTransaction initialScene() {
      TextureAsset first = texture(10L, (byte)1);
      TextureAsset second = texture(11L, (byte)2);
      MaterialAsset material = MaterialAsset.builder(20L).blendMode(BlendMode.MASKED).baseColorRgba8(-1).baseColorTextureId(10L).normalTextureId(11L).emissive(-16711165, 1.0F).alphaCutoff(0.5F).roughness(0.75F).metallic(0.25F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MeshAsset mesh = MeshAsset.builder(30L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new long[]{20L}).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).tangents(new float[]{1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F}).textureCoordinates(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).lightmapCoordinates(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).vertexColorsRgba8(new int[]{-1, -16711681, -65281}).build();
      SceneInstance instance = SceneInstance.builder(40L, 30L).build();
      SceneLight light = SceneLight.point(50L, 1.0, 2.0, 3.0).color(1.0F, 0.8F, 0.6F).intensity(100.0F).range(32.0F).build();
      return SceneTransaction.builder(0L).resetScene().upsertTextures(List.of(first, second)).upsert(material).upsert(mesh).upsert(instance).upsert(light).build();
   }

   private static TextureAsset texture(long id, byte value) {
      return TextureAsset.builder(id, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.LINEAR).pixelsRgba8(new byte[]{value, value, value, -1}).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class Fixture {
      private final VulkanSceneResidency residency = new VulkanSceneResidency();
      private final VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
      private final VulkanGpuSceneIdentityIndex identities = new VulkanGpuSceneIdentityIndex();

      private PreparedGeneration prepare(SceneTransaction transaction) {
         VulkanSceneResidency.PreparedUpdate resident = this.residency.prepare(transaction);
         VulkanGpuSceneMemory.Prepared preparedMemory = this.memory.prepare(resident.changeSet());
         VulkanGpuSceneIdentityIndex.Prepared preparedIdentities = this.identities.prepare(resident.changeSet());
         VulkanGpuSceneUploadPlanner.Plan plan = VulkanGpuSceneUploadPlanner.plan(resident.changeSet(), preparedMemory, preparedIdentities);
         return new PreparedGeneration(resident, preparedMemory, preparedIdentities, plan);
      }

      private void commit(PreparedGeneration generation, long retireAfterEpoch) {
         this.memory.commit(generation.memory, retireAfterEpoch);
         this.identities.commit(generation.identities);
         this.residency.commit(generation.residency);
      }
   }

   private static record PreparedGeneration(VulkanSceneResidency.PreparedUpdate residency, VulkanGpuSceneMemory.Prepared memory, VulkanGpuSceneIdentityIndex.Prepared identities, VulkanGpuSceneUploadPlanner.Plan plan) {
   }
}
