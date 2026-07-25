package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;

public final class VulkanGpuSceneMemorySelfTest {
   private VulkanGpuSceneMemorySelfTest() {
   }

   public static void main(String[] arguments) {
      preparesAllStreamsWithoutPublishingAndCommitsOneGeneration();
      removingOptionalAttributesRetiresOnlyTheirRanges();
      resetRetiresTheCompletePreviousMemoryGeneration();
      allocatesCompleteMipChain();
      System.out.println("VulkanGpuSceneMemorySelfTest passed");
   }

   private static void allocatesCompleteMipChain() {
      TextureAsset texture = TextureAsset.builder(10L, 4, 2).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).mipChainRgba8(3, new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))]).build();
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanSceneResidency.PreparedUpdate resident = residency.prepare(scene(0L, true, (MeshAsset)null, texture));
      VulkanGpuSceneMemory.Prepared prepared = (new VulkanGpuSceneMemory()).prepare(resident.changeSet());
      VulkanGpuSceneMemory.TextureUpload upload = (VulkanGpuSceneMemory.TextureUpload)prepared.textureUploads().getFirst();
      require(upload.placement().byteCount() == 44L && upload.texture().mipLevelCount() == 3, "GPUScene arena must allocate every tightly packed mip level");
   }

   private static void preparesAllStreamsWithoutPublishingAndCommitsOneGeneration() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanSceneResidency.PreparedUpdate resident = residency.prepare(scene(0L, true, fullMesh(), texture(16)));
      VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
      VulkanGpuSceneMemory.Prepared prepared = memory.prepare(resident.changeSet());
      require(memory.state().positions().liveAllocations() == 0 && memory.state().texturePixels().highWaterBytes() == 0L, "GPUScene memory prepare published before native admission");
      require(prepared.textureUploads().size() == 1 && prepared.meshUploads().size() == 1, "GPUScene memory plan lost a changed asset");
      VulkanGpuSceneAbi.GeometryPlacement placement = ((VulkanGpuSceneMemory.MeshUpload)prepared.meshUploads().get(0)).placement();
      require(placement.positionBytes() >= 0L && placement.normalBytes() >= 0L && placement.tangentBytes() >= 0L && placement.textureCoordinateBytes() >= 0L && placement.colorBytes() >= 0L && placement.indexBytes() >= 0L && placement.triangleMaterialSlotBytes() >= 0L, "full mesh did not receive every stream placement");
      VulkanGpuSceneAbi.packTexture(((VulkanGpuSceneMemory.TextureUpload)prepared.textureUploads().get(0)).texture(), ((VulkanGpuSceneMemory.TextureUpload)prepared.textureUploads().get(0)).placement());
      VulkanGpuSceneAbi.packMesh(((VulkanGpuSceneMemory.MeshUpload)prepared.meshUploads().get(0)).mesh(), placement);
      VulkanGpuSceneMemory.State state = memory.commit(prepared, 0L);
      residency.commit(resident);
      require(state.texturePixels().liveAllocations() == 1 && state.positions().liveAllocations() == 1 && state.normals().liveAllocations() == 1 && state.tangents().liveAllocations() == 1 && state.textureCoordinates().liveAllocations() == 1 && state.colors().liveAllocations() == 1 && state.indices().liveAllocations() == 1 && state.triangleMaterialSlots().liveAllocations() == 1, "atomic GPUScene memory commit lost a stream domain");
   }

   private static void removingOptionalAttributesRetiresOnlyTheirRanges() {
      Fixture fixture = populated();
      VulkanSceneResidency.PreparedUpdate resident = fixture.residency.prepare(scene(1L, false, compactMesh(), (TextureAsset)null));
      VulkanGpuSceneMemory.Prepared prepared = fixture.memory.prepare(resident.changeSet());
      VulkanGpuSceneAbi.GeometryPlacement placement = ((VulkanGpuSceneMemory.MeshUpload)prepared.meshUploads().get(0)).placement();
      require(placement.normalBytes() == -1L && placement.tangentBytes() == -1L && placement.textureCoordinateBytes() == -1L && placement.colorBytes() == -1L, "removed optional streams remained addressable in the new descriptor");
      VulkanGpuSceneMemory.State state = fixture.memory.commit(prepared, 5L);
      fixture.residency.commit(resident);
      require(state.positions().retiredRangeCount() == 0 && state.indices().retiredRangeCount() == 0 && state.triangleMaterialSlots().retiredRangeCount() == 0, "same-capacity required streams were unnecessarily relocated");
      require(state.normals().liveAllocations() == 0 && state.normals().retiredRangeCount() == 1 && state.tangents().retiredRangeCount() == 1 && state.textureCoordinates().retiredRangeCount() == 1 && state.colors().retiredRangeCount() == 1, "removed optional streams were not protected until GPU completion");
      require(fixture.memory.releaseThrough(4L).normals().retiredRangeCount() == 1, "optional stream range was released before its completion epoch");
      require(fixture.memory.releaseThrough(5L).normals().retiredRangeCount() == 0, "optional stream range was not released at its completion epoch");
   }

   private static void resetRetiresTheCompletePreviousMemoryGeneration() {
      Fixture fixture = populated();
      VulkanSceneResidency.PreparedUpdate reset = fixture.residency.prepare(SceneTransaction.builder(1L).resetScene().build());
      VulkanGpuSceneMemory.Prepared prepared = fixture.memory.prepare(reset.changeSet());
      require(fixture.memory.state().positions().liveAllocations() == 1, "memory reset published during preparation");
      VulkanGpuSceneMemory.State state = fixture.memory.commit(prepared, 7L);
      fixture.residency.commit(reset);
      require(state.texturePixels().liveAllocations() == 0 && state.texturePixels().retiredRangeCount() == 1 && state.positions().liveAllocations() == 0 && state.positions().retiredRangeCount() == 1 && state.indices().retiredRangeCount() == 1, "authoritative reset did not retire the complete previous memory generation");
   }

   private static Fixture populated() {
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
      VulkanSceneResidency.PreparedUpdate resident = residency.prepare(scene(0L, true, fullMesh(), texture(16)));
      memory.commit(memory.prepare(resident.changeSet()), 0L);
      residency.commit(resident);
      return new Fixture(residency, memory);
   }

   private static SceneTransaction scene(long revision, boolean reset, MeshAsset mesh, TextureAsset texture) {
      SceneTransaction.Builder builder = SceneTransaction.builder(revision);
      if (reset) {
         builder.resetScene();
      }

      if (texture != null) {
         builder.upsert(texture);
      }

      if (mesh != null) {
         builder.upsert(mesh);
      }

      return builder.build();
   }

   private static MeshAsset fullMesh() {
      return MeshAsset.builder(30L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new long[]{20L}).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).tangents(new float[]{1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F}).textureCoordinates(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).vertexColorsRgba8(new int[]{-1, -1, -1}).build();
   }

   private static MeshAsset compactMesh() {
      return MeshAsset.triangles(30L, new float[]{0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F}, new int[]{0, 1, 2}, 20L);
   }

   private static TextureAsset texture(int bytes) {
      int pixels = bytes / 4;
      return TextureAsset.builder(10L, pixels, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[bytes]).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static record Fixture(VulkanSceneResidency residency, VulkanGpuSceneMemory memory) {
   }
}
