package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.List;

/** Full-scene packing, reference resolution, coalescing, and sparse-update gate. */
public final class VulkanGpuSceneUploadPlannerSelfTest {
    private VulkanGpuSceneUploadPlannerSelfTest() {
    }

    public static void main(String[] arguments) {
        completeScenePacksEveryGpuDomainAndResolvesReferences();
        instanceOnlyGenerationDoesNotRewriteUnchangedGpuDomains();
        uploadsCompleteMipChainAsOneTextureGeneration();
        System.out.println("VulkanGpuSceneUploadPlannerSelfTest passed");
    }

    private static void uploadsCompleteMipChainAsOneTextureGeneration() {
        byte[] mipBytes = new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))];
        for (int index = 0; index < mipBytes.length; index++) mipBytes[index] = (byte) index;
        TextureAsset texture = new TextureAsset(
                10L, 4, 2, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.LINEAR, 3, mipBytes
        );
        Fixture fixture = new Fixture();
        PreparedGeneration generation = fixture.prepare(new SceneTransaction(
                0L, true,
                new SceneTransaction.Upserts(List.of(texture), List.of(), List.of(), List.of(), List.of()),
                SceneTransaction.Removals.empty()
        ));
        VulkanGpuSceneUploadPlanner.Chunk pixels = only(
                generation.plan, VulkanGpuSceneUploadPlanner.Target.TEXTURE_PIXELS);
        VulkanGpuSceneUploadPlanner.Chunk record = only(
                generation.plan, VulkanGpuSceneUploadPlanner.Target.TEXTURE_RECORDS);
        ByteBuffer words = ByteBuffer.wrap(record.payload()).order(ByteOrder.LITTLE_ENDIAN);
        require(java.util.Arrays.equals(pixels.payload(), mipBytes)
                        && words.getInt(VulkanGpuSceneAbi.TEXTURE_MIP_LEVEL_COUNT_WORD * Integer.BYTES) == 3,
                "one texture generation must upload all mip bytes and publish their level count atomically");
    }

    private static void completeScenePacksEveryGpuDomainAndResolvesReferences() {
        Fixture fixture = new Fixture();
        PreparedGeneration generation = fixture.prepare(initialScene());
        VulkanGpuSceneUploadPlanner.Plan plan = generation.plan;

        EnumSet<VulkanGpuSceneUploadPlanner.Target> targets = EnumSet.noneOf(
                VulkanGpuSceneUploadPlanner.Target.class
        );
        plan.chunks().forEach(chunk -> targets.add(chunk.target()));
        require(targets.equals(EnumSet.allOf(VulkanGpuSceneUploadPlanner.Target.class)),
                "complete generic scene did not produce every GPUScene upload target: " + targets);
        require(chunks(plan, VulkanGpuSceneUploadPlanner.Target.TEXTURE_RECORDS) == 1
                        && chunks(plan, VulkanGpuSceneUploadPlanner.Target.TEXTURE_PIXELS) == 1,
                "adjacent texture records or pixels were not coalesced");

        VulkanGpuSceneUploadPlanner.Chunk material = only(plan,
                VulkanGpuSceneUploadPlanner.Target.MATERIAL_RECORDS);
        ByteBuffer materialWords = ByteBuffer.wrap(material.payload()).order(ByteOrder.LITTLE_ENDIAN);
        require(materialWords.getInt(2 * Integer.BYTES) == generation.identities.textureSlot(10L)
                        && materialWords.getInt(3 * Integer.BYTES) == generation.identities.textureSlot(11L),
                "material texture identities were not packed as prepared stable slots");
        VulkanGpuSceneUploadPlanner.Chunk triangleMaterials = only(plan,
                VulkanGpuSceneUploadPlanner.Target.TRIANGLE_MATERIAL_SLOTS);
        require(ByteBuffer.wrap(triangleMaterials.payload()).order(ByteOrder.LITTLE_ENDIAN).getInt()
                        == generation.identities.materialSlot(20L),
                "triangle material identity was not packed as a stable material slot");
        require(plan.uploadBytes() > 0L && plan.logicalRecords() >= 10,
                "upload statistics did not include packed scene work");
    }

    private static void instanceOnlyGenerationDoesNotRewriteUnchangedGpuDomains() {
        Fixture fixture = new Fixture();
        fixture.commit(fixture.prepare(initialScene()), 0L);
        SceneInstance moved = new SceneInstance(
                40L, 30L,
                new AffineTransform(new float[]{
                        1, 0, 0, 8,
                        0, 1, 0, 4,
                        0, 0, 1, 2
                }),
                SceneInstance.Mobility.DYNAMIC, 0xff, true
        );
        SceneTransaction update = new SceneTransaction(
                1L, false,
                new SceneTransaction.Upserts(List.of(), List.of(), List.of(), List.of(moved), List.of()),
                SceneTransaction.Removals.empty()
        );
        PreparedGeneration generation = fixture.prepare(update);
        require(generation.plan.chunks().size() == 1
                        && generation.plan.chunks().get(0).target()
                        == VulkanGpuSceneUploadPlanner.Target.INSTANCE_RECORDS,
                "instance-only update rewrote unchanged GPUScene domains: " + generation.plan.chunks());
        require(fixture.memory.state().positions().revision() == 0L,
                "memory preparation published the next revision before admission");
        fixture.commit(generation, 1L);
        require(fixture.memory.state().positions().revision() == 1L,
                "accepted empty geometry generation did not advance memory authority");
    }

    private static int chunks(
            VulkanGpuSceneUploadPlanner.Plan plan,
            VulkanGpuSceneUploadPlanner.Target target
    ) {
        return (int) plan.chunks().stream().filter(chunk -> chunk.target() == target).count();
    }

    private static VulkanGpuSceneUploadPlanner.Chunk only(
            VulkanGpuSceneUploadPlanner.Plan plan,
            VulkanGpuSceneUploadPlanner.Target target
    ) {
        List<VulkanGpuSceneUploadPlanner.Chunk> chunks = plan.chunks().stream()
                .filter(chunk -> chunk.target() == target).toList();
        require(chunks.size() == 1, "expected one " + target + " chunk but found " + chunks.size());
        return chunks.get(0);
    }

    private static SceneTransaction initialScene() {
        TextureAsset first = texture(10L, (byte) 1);
        TextureAsset second = texture(11L, (byte) 2);
        MaterialAsset material = new MaterialAsset(
                20L, MaterialAsset.BlendMode.MASKED, 0xffffffff,
                10L, 11L, -1L, -1L, 0xff010203,
                1.0F, 0.5F, 0.75F, 0.25F, 0.0F, 1.5F, true
        );
        MeshAsset mesh = new MeshAsset(
                30L,
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1},
                new float[]{0, 0, 1, 0, 0, 1},
                new float[]{0, 0, 1, 0, 0, 1},
                new int[]{0xffffffff, 0xff00ffff, 0xffff00ff},
                new int[]{0, 1, 2}, new long[]{20L}
        );
        SceneInstance instance = new SceneInstance(
                40L, 30L, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true
        );
        SceneLight light = new SceneLight(
                50L, SceneLight.Type.POINT, 1.0, 2.0, 3.0,
                0, 0, 0, 1, 0.8F, 0.6F, 100, 32, 0, 0, true
        );
        return new SceneTransaction(
                0L, true,
                new SceneTransaction.Upserts(
                        List.of(first, second), List.of(material), List.of(mesh), List.of(instance), List.of(light)
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static TextureAsset texture(long id, byte value) {
        return new TextureAsset(
                id, 1, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.LINEAR, new byte[]{value, value, value, (byte) 0xff}
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        private final VulkanSceneResidency residency = new VulkanSceneResidency();
        private final VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
        private final VulkanGpuSceneIdentityIndex identities = new VulkanGpuSceneIdentityIndex();

        private PreparedGeneration prepare(SceneTransaction transaction) {
            VulkanSceneResidency.PreparedUpdate resident = residency.prepare(transaction);
            VulkanGpuSceneMemory.Prepared preparedMemory = memory.prepare(resident.changeSet());
            VulkanGpuSceneIdentityIndex.Prepared preparedIdentities = identities.prepare(resident.changeSet());
            VulkanGpuSceneUploadPlanner.Plan plan = VulkanGpuSceneUploadPlanner.plan(
                    resident.changeSet(), preparedMemory, preparedIdentities
            );
            return new PreparedGeneration(resident, preparedMemory, preparedIdentities, plan);
        }

        private void commit(PreparedGeneration generation, long retireAfterEpoch) {
            memory.commit(generation.memory, retireAfterEpoch);
            identities.commit(generation.identities);
            residency.commit(generation.residency);
        }
    }

    private record PreparedGeneration(
            VulkanSceneResidency.PreparedUpdate residency,
            VulkanGpuSceneMemory.Prepared memory,
            VulkanGpuSceneIdentityIndex.Prepared identities,
            VulkanGpuSceneUploadPlanner.Plan plan
    ) {
    }
}
