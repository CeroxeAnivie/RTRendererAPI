package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.util.List;

/** Cross-arena generation, optional-stream transition, and retirement gate. */
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
        TextureAsset texture = new TextureAsset(
                10L, 4, 2, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, 3,
                new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))]
        );
        VulkanSceneResidency residency = new VulkanSceneResidency();
        VulkanSceneResidency.PreparedUpdate resident = residency.prepare(scene(0L, true, null, texture));
        VulkanGpuSceneMemory.Prepared prepared = new VulkanGpuSceneMemory().prepare(resident.changeSet());
        VulkanGpuSceneMemory.TextureUpload upload = prepared.textureUploads().getFirst();
        require(upload.placement().byteCount() == 44L && upload.texture().mipLevelCount() == 3,
                "GPUScene arena must allocate every tightly packed mip level");
    }

    private static void preparesAllStreamsWithoutPublishingAndCommitsOneGeneration() {
        VulkanSceneResidency residency = new VulkanSceneResidency();
        VulkanSceneResidency.PreparedUpdate resident = residency.prepare(scene(0L, true, fullMesh(), texture(16)));
        VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
        VulkanGpuSceneMemory.Prepared prepared = memory.prepare(resident.changeSet());

        require(memory.state().positions().liveAllocations() == 0
                        && memory.state().texturePixels().highWaterBytes() == 0L,
                "GPUScene memory prepare published before native admission");
        require(prepared.textureUploads().size() == 1 && prepared.meshUploads().size() == 1,
                "GPUScene memory plan lost a changed asset");
        VulkanGpuSceneAbi.GeometryPlacement placement = prepared.meshUploads().get(0).placement();
        require(placement.positionBytes() >= 0L && placement.normalBytes() >= 0L
                        && placement.tangentBytes() >= 0L && placement.textureCoordinateBytes() >= 0L
                        && placement.colorBytes() >= 0L && placement.indexBytes() >= 0L
                        && placement.triangleMaterialSlotBytes() >= 0L,
                "full mesh did not receive every stream placement");
        VulkanGpuSceneAbi.packTexture(
                prepared.textureUploads().get(0).texture(), prepared.textureUploads().get(0).placement()
        );
        VulkanGpuSceneAbi.packMesh(prepared.meshUploads().get(0).mesh(), placement);

        VulkanGpuSceneMemory.State state = memory.commit(prepared, 0L);
        residency.commit(resident);
        require(state.texturePixels().liveAllocations() == 1
                        && state.positions().liveAllocations() == 1
                        && state.normals().liveAllocations() == 1
                        && state.tangents().liveAllocations() == 1
                        && state.textureCoordinates().liveAllocations() == 1
                        && state.colors().liveAllocations() == 1
                        && state.indices().liveAllocations() == 1
                        && state.triangleMaterialSlots().liveAllocations() == 1,
                "atomic GPUScene memory commit lost a stream domain");
    }

    private static void removingOptionalAttributesRetiresOnlyTheirRanges() {
        Fixture fixture = populated();
        VulkanSceneResidency.PreparedUpdate resident = fixture.residency.prepare(
                scene(1L, false, compactMesh(), null)
        );
        VulkanGpuSceneMemory.Prepared prepared = fixture.memory.prepare(resident.changeSet());
        VulkanGpuSceneAbi.GeometryPlacement placement = prepared.meshUploads().get(0).placement();
        require(placement.normalBytes() == -1L && placement.tangentBytes() == -1L
                        && placement.textureCoordinateBytes() == -1L && placement.colorBytes() == -1L,
                "removed optional streams remained addressable in the new descriptor");

        VulkanGpuSceneMemory.State state = fixture.memory.commit(prepared, 5L);
        fixture.residency.commit(resident);
        require(state.positions().retiredRangeCount() == 0
                        && state.indices().retiredRangeCount() == 0
                        && state.triangleMaterialSlots().retiredRangeCount() == 0,
                "same-capacity required streams were unnecessarily relocated");
        require(state.normals().liveAllocations() == 0 && state.normals().retiredRangeCount() == 1
                        && state.tangents().retiredRangeCount() == 1
                        && state.textureCoordinates().retiredRangeCount() == 1
                        && state.colors().retiredRangeCount() == 1,
                "removed optional streams were not protected until GPU completion");
        require(fixture.memory.releaseThrough(4L).normals().retiredRangeCount() == 1,
                "optional stream range was released before its completion epoch");
        require(fixture.memory.releaseThrough(5L).normals().retiredRangeCount() == 0,
                "optional stream range was not released at its completion epoch");
    }

    private static void resetRetiresTheCompletePreviousMemoryGeneration() {
        Fixture fixture = populated();
        VulkanSceneResidency.PreparedUpdate reset = fixture.residency.prepare(
                new SceneTransaction(1L, true, SceneTransaction.Upserts.empty(), SceneTransaction.Removals.empty())
        );
        VulkanGpuSceneMemory.Prepared prepared = fixture.memory.prepare(reset.changeSet());
        require(fixture.memory.state().positions().liveAllocations() == 1,
                "memory reset published during preparation");
        VulkanGpuSceneMemory.State state = fixture.memory.commit(prepared, 7L);
        fixture.residency.commit(reset);
        require(state.texturePixels().liveAllocations() == 0 && state.texturePixels().retiredRangeCount() == 1
                        && state.positions().liveAllocations() == 0 && state.positions().retiredRangeCount() == 1
                        && state.indices().retiredRangeCount() == 1,
                "authoritative reset did not retire the complete previous memory generation");
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
        return new SceneTransaction(
                revision, reset,
                new SceneTransaction.Upserts(
                        texture == null ? List.of() : List.of(texture),
                        List.of(), mesh == null ? List.of() : List.of(mesh), List.of(), List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static MeshAsset fullMesh() {
        return new MeshAsset(
                30L,
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1},
                new float[]{0, 0, 1, 0, 0, 1},
                new int[]{0xffffffff, 0xffffffff, 0xffffffff},
                new int[]{0, 1, 2}, new long[]{20L}
        );
    }

    private static MeshAsset compactMesh() {
        return new MeshAsset(
                30L,
                new float[]{0, 0, 0, 2, 0, 0, 0, 2, 0},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{20L}
        );
    }

    private static TextureAsset texture(int bytes) {
        int pixels = bytes / 4;
        return new TextureAsset(
                10L, pixels, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, new byte[bytes]
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(VulkanSceneResidency residency, VulkanGpuSceneMemory memory) {
    }
}
