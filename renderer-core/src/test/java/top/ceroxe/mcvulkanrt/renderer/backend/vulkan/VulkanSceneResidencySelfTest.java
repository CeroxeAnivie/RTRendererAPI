package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/** Stable-slot, sparse-update, and atomic-publication gate for the Vulkan GPUScene mirror. */
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

        require(residency.state().textures().liveSlots() == 0,
                "prepare published texture residency before native admission");
        require(prepared.textures().writes().size() == 2
                        && prepared.materials().writes().size() == 1
                        && prepared.meshes().writes().size() == 1
                        && prepared.instances().writes().size() == 1,
                "initial change set did not preserve independent dirty domains");

        VulkanSceneResidency.SceneResidencyState committed = residency.commit(prepared);
        require(committed.revision() == 0L
                        && committed.textures().liveSlots() == 2
                        && committed.instances().liveSlots() == 1,
                "atomic resident publication lost a resource domain");
        expect(IllegalStateException.class, () -> residency.commit(prepared));
    }

    private static void retainsIdentitySlotsAndReusesReleasedCapacity() {
        VulkanSceneResidency residency = populatedResidency();
        int retainedSlot = residency.textureSlot(20L);
        int releasedSlot = residency.textureSlot(10L);
        TextureAsset replacement = texture(20L, 0xff223344);
        TextureAsset newTexture = texture(30L, 0xff556677);
        SceneTransaction update = new SceneTransaction(
                1L,
                false,
                new SceneTransaction.Upserts(
                        List.of(replacement, newTexture), List.of(), List.of(), List.of(), List.of()
                ),
                new SceneTransaction.Removals(
                        new long[]{10L}, new long[0], new long[0], new long[0], new long[0]
                )
        );

        VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(update);
        require(residency.textureSlot(10L) == releasedSlot && residency.textureSlot(30L) == -1,
                "prepared update mutated the live identity map");
        residency.commit(prepared);

        require(residency.textureSlot(20L) == retainedSlot,
                "updating an existing texture moved its persistent slot");
        require(residency.textureSlot(30L) == releasedSlot,
                "new texture did not reuse the lowest released slot");
        require(prepared.textures().slotUpperBound() == 2,
                "slot reuse unnecessarily increased the GPU buffer high-water mark");
    }

    private static void rejectsStalePreparedGenerationBeforeAnyDomainMutation() {
        VulkanSceneResidency residency = populatedResidency();
        VulkanSceneResidency.PreparedUpdate first = residency.prepare(SceneTransaction.empty(1L));
        VulkanSceneResidency.PreparedUpdate stale = residency.prepare(SceneTransaction.empty(2L));
        residency.commit(first);
        VulkanSceneResidency.SceneResidencyState beforeFailure = residency.state();

        expect(IllegalStateException.class, () -> residency.commit(stale));
        require(residency.state().equals(beforeFailure),
                "stale prepared update partially changed resident domains");
    }

    private static void resetRetainsSurvivingIdentitiesAndClearsOtherDomains() {
        VulkanSceneResidency residency = populatedResidency();
        int survivingSlot = residency.textureSlot(20L);
        SceneTransaction reset = new SceneTransaction(
                1L,
                true,
                new SceneTransaction.Upserts(
                        List.of(texture(20L, 0xffabcdef), texture(40L, 0xff010203)),
                        List.of(), List.of(), List.of(), List.of()
                ),
                SceneTransaction.Removals.empty()
        );
        residency.commit(residency.prepare(reset));

        VulkanSceneResidency.SceneResidencyState state = residency.state();
        require(residency.textureSlot(20L) == survivingSlot,
                "authoritative reset moved a surviving identity");
        require(state.textures().liveSlots() == 2
                        && state.materials().liveSlots() == 0
                        && state.meshes().liveSlots() == 0
                        && state.instances().liveSlots() == 0,
                "authoritative reset did not clear omitted resident domains");
    }

    private static void sparseUpdatesDoNotRewriteOrExpandStableDomain() {
        int residentCount = 16_384;
        int changedCount = 128;
        StableIdentitySlots<SlotValue> slots = new StableIdentitySlots<>(SlotValue::id);
        ArrayList<SlotValue> initial = new ArrayList<>(residentCount);
        for (int index = 0; index < residentCount; index++) {
            initial.add(new SlotValue(index, 0));
        }
        StableIdentitySlots.Prepared<SlotValue> bootstrap = slots.prepare(
                0L, true, initial, LongBuffer.allocate(0)
        );
        slots.validate(bootstrap);
        slots.commitValidated(bootstrap);

        long[] removals = new long[changedCount];
        ArrayList<SlotValue> updates = new ArrayList<>(changedCount * 2);
        for (int index = 0; index < changedCount; index++) {
            removals[index] = index;
            updates.add(new SlotValue(1_000L + index, 1));
            updates.add(new SlotValue(residentCount + index, 1));
        }
        StableIdentitySlots.Prepared<SlotValue> sparse = slots.prepare(
                1L, false, updates, LongBuffer.wrap(removals)
        );

        require(sparse.writes().size() == changedCount * 2,
                "sparse preparation rewrote unchanged resident slots");
        require(sparse.clearedSlots().length == 0,
                "slots overwritten in the same generation were redundantly cleared");
        require(sparse.slotUpperBound() == residentCount,
                "released capacity was not reused before increasing the high-water mark");
        slots.validate(sparse);
        slots.commitValidated(sparse);
        require(slots.liveCount() == residentCount && slots.slotUpperBound() == residentCount,
                "sparse churn changed resident capacity accounting");
    }

    private static VulkanSceneResidency populatedResidency() {
        VulkanSceneResidency residency = new VulkanSceneResidency();
        residency.commit(residency.prepare(initialScene(0L)));
        return residency;
    }

    private static SceneTransaction initialScene(long revision) {
        MaterialAsset material = new MaterialAsset(
                50L, MaterialAsset.BlendMode.OPAQUE, 0xffffffff,
                -1L, -1L, -1L, -1L, 0x000000ff,
                0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 1.5F, false
        );
        MeshAsset mesh = new MeshAsset(
                60L,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{50L}
        );
        SceneInstance instance = new SceneInstance(
                70L, 60L, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true
        );
        return new SceneTransaction(
                revision,
                true,
                new SceneTransaction.Upserts(
                        List.of(texture(20L, 0xff102030), texture(10L, 0xff405060)),
                        List.of(material), List.of(mesh), List.of(instance), List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static TextureAsset texture(long id, int rgba8) {
        return new TextureAsset(
                id, 1, 1,
                TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT,
                TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST,
                new byte[]{
                        (byte) rgba8,
                        (byte) (rgba8 >>> 8),
                        (byte) (rgba8 >>> 16),
                        (byte) (rgba8 >>> 24)
                }
        );
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
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

    private record SlotValue(long id, int generation) {
    }
}
