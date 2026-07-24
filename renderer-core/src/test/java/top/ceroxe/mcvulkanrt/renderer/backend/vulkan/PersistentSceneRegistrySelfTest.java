package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.SceneRevisionException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.SceneValidationException;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.util.List;

/** Transaction preparation, publication, and rollback contract for the Vulkan scene authority. */
public final class PersistentSceneRegistrySelfTest {
    private PersistentSceneRegistrySelfTest() {
    }

    public static void main(String[] args) {
        preparationDoesNotPublishBeforeCommit();
        invalidReferenceDoesNotAdvanceState();
        stalePreparedMutationCannotOverwriteNewerGeneration();
        strictRemovalAndResetRulesPreserveState();
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
        require(committed.textures() == 1 && committed.materials() == 1 && committed.meshes() == 1
                && committed.instances() == 1 && committed.lights() == 1, "complete scene was not committed");
        expect(IllegalStateException.class, () -> registry.commit(prepared));
        expect(SceneRevisionException.class, () -> registry.prepare(SceneTransaction.empty(0L)));
    }

    private static void invalidReferenceDoesNotAdvanceState() {
        PersistentSceneRegistry registry = populatedRegistry();
        expect(SceneValidationException.class, () -> registry.prepare(new SceneTransaction(
                1L,
                false,
                new SceneTransaction.Upserts(
                        List.of(), List.of(), List.of(mesh(30L, 999L)), List.of(), List.of()
                ),
                SceneTransaction.Removals.empty()
        )));

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
        expect(SceneValidationException.class, () -> registry.prepare(new SceneTransaction(
                1L,
                false,
                SceneTransaction.Upserts.empty(),
                new SceneTransaction.Removals(
                        new long[0], new long[0], new long[]{999L}, new long[0], new long[0]
                )
        )));
        expect(SceneValidationException.class, () -> registry.prepare(new SceneTransaction(
                1L,
                true,
                SceneTransaction.Upserts.empty(),
                new SceneTransaction.Removals(
                        new long[]{1L}, new long[0], new long[0], new long[0], new long[0]
                )
        )));
        expect(SceneValidationException.class, () -> registry.prepare(new SceneTransaction(
                1L,
                false,
                SceneTransaction.Upserts.empty(),
                new SceneTransaction.Removals(
                        new long[0], new long[0], new long[]{3L}, new long[0], new long[0]
                )
        )));

        require(registry.state().revision() == 0L && registry.state().instances() == 1,
                "rejected removal changed the published scene");

        SceneTransaction.Removals all = new SceneTransaction.Removals(
                new long[]{1L}, new long[]{2L}, new long[]{3L}, new long[]{4L}, new long[]{5L}
        );
        PersistentSceneRegistry.PreparedMutation removal = registry.prepare(new SceneTransaction(
                1L, false, SceneTransaction.Upserts.empty(), all
        ));
        PersistentSceneRegistry.SceneState empty = registry.commit(removal);
        require(empty.textures() == 0 && empty.materials() == 0 && empty.meshes() == 0
                && empty.instances() == 0 && empty.lights() == 0, "complete dependency removal failed");
    }

    private static void snapshotOrderIsDeterministic() {
        PersistentSceneRegistry registry = new PersistentSceneRegistry();
        SceneTransaction transaction = new SceneTransaction(
                0L,
                true,
                new SceneTransaction.Upserts(
                        List.of(),
                        List.of(material(20L, -1L), material(10L, -1L)),
                        List.of(mesh(40L, 20L), mesh(30L, 10L)),
                        List.of(instance(60L, 40L), instance(50L, 30L)),
                        List.of()
                ),
                SceneTransaction.Removals.empty()
        );
        registry.commit(registry.prepare(transaction));
        PersistentSceneRegistry.Snapshot snapshot = registry.snapshot();
        require(snapshot.materials().get(0).id() == 10L && snapshot.meshes().get(0).id() == 30L
                && snapshot.instances().get(0).id() == 50L, "snapshot identities are not sorted");
    }

    private static PersistentSceneRegistry populatedRegistry() {
        PersistentSceneRegistry registry = new PersistentSceneRegistry();
        registry.commit(registry.prepare(completeScene(0L)));
        return registry;
    }

    private static SceneTransaction completeScene(long revision) {
        return new SceneTransaction(
                revision,
                true,
                new SceneTransaction.Upserts(
                        List.of(texture(1L)),
                        List.of(material(2L, 1L)),
                        List.of(mesh(3L, 2L)),
                        List.of(instance(4L, 3L)),
                        List.of(light(5L))
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static TextureAsset texture(long id) {
        return new TextureAsset(
                id, 1, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, new byte[]{-1, -1, -1, -1}
        );
    }

    private static MaterialAsset material(long id, long textureId) {
        return new MaterialAsset(
                id, MaterialAsset.BlendMode.OPAQUE, 0xffffffff,
                textureId, -1L, -1L, -1L, 0x000000ff,
                0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 1.5F, false
        );
    }

    private static MeshAsset mesh(long id, long materialId) {
        return new MeshAsset(
                id,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{materialId}
        );
    }

    private static SceneInstance instance(long id, long meshId) {
        return new SceneInstance(id, meshId, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true);
    }

    private static SceneLight light(long id) {
        return new SceneLight(
                id, SceneLight.Type.POINT,
                0.0D, 1.0D, 0.0D,
                0.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 1.0F, 10.0F, 8.0F,
                0.0F, 0.0F, true
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
}
