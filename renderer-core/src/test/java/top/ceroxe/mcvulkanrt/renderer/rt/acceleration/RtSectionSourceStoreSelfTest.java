package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

import java.util.Set;

/** Verifies atomic source publication, observation-safe LRU, and FarField-only clear semantics. */
public final class RtSectionSourceStoreSelfTest {
    private RtSectionSourceStoreSelfTest() {
    }

    public static void main(String[] arguments) {
        observationDoesNotChangePayloadEvictionOrder();
        clearInvalidatesFarFieldOnlyMembership();
        failedPublicationDoesNotCommitAccounting();
        System.out.println("RtSectionSourceStoreSelfTest passed");
    }

    private static void observationDoesNotChangePayloadEvictionOrder() {
        SectionTriangleMesh first = mesh(1);
        SectionTriangleMesh second = mesh(2);
        SectionTriangleMesh third = mesh(3);
        RtSectionSourceStore store = new RtSectionSourceStore(
                Math.multiplyExact(basePayloadBytes(first), 2L)
        );
        publish(store, first);
        publish(store, second);

        /* A diagnostic/FarField read must not make the oldest Base payload look recently used. */
        require(store.publicationsView().get(first.key()) != null, "first publication is missing");
        publish(store, third);
        RtSectionSourceStore.TrimResult trim = store.trimToBudget(null, Set.of());
        require(trim.releasedPayloads() == 1 && !trim.overBudget(),
                "one oldest payload should restore the source budget");
        require(store.mesh(first.key()) == null, "observational read changed payload eviction order");
        require(store.mesh(second.key()) != null && store.mesh(third.key()) != null,
                "newer payloads must remain resident");
    }

    private static void clearInvalidatesFarFieldOnlyMembership() {
        SectionTriangleMesh mesh = mesh(7);
        RtSectionSourceStore store = new RtSectionSourceStore(basePayloadBytes(mesh));
        publish(store, mesh);
        store.releasePayload(mesh.key());
        require(store.payloadCount() == 0 && !store.membership().isEmpty(),
                "compact FarField source must outlive the heavyweight Base payload");
        RtSectionSourcePublication compact = store.publication(mesh.key());
        require(compact.contentRevision() == 7L
                        && compact.causality().equals(RendererFrameCausality.untraced(7L)),
                "compact FarField source must retain its captured content revision and causality");
        long beforeClear = store.membershipRevision();
        store.clear();
        require(store.membershipRevision() == beforeClear + 1L,
                "clearing a FarField-only publication must advance membership");
        require(store.membership().isEmpty(), "cleared source membership remained stale");
    }

    private static void failedPublicationDoesNotCommitAccounting() {
        SectionTriangleMesh valid = mesh(9);
        RtSectionSourceStore store = new RtSectionSourceStore(basePayloadBytes(valid));
        SectionTriangleMesh invalid = new SectionTriangleMesh(
                new SectionKey(10, 0, 0),
                valid.vertexPositions(),
                valid.indices(),
                valid.faceVoxelStateIds(),
                valid.faceFluidAmounts(),
                new byte[]{127}
        );
        RtSceneMaterialTable.SectionMaterial material = RtSceneMaterialTable.SectionMaterial.fromMesh(valid);
        expectFailure(() -> store.publish(
                invalid, material, 10L, RendererFrameCausality.untraced(10L), false, true
        ));
        require(store.publicationCount() == 0 && store.payloadCount() == 0 && store.payloadBytes() == 0L,
                "failed source construction partially committed store accounting");
        require(store.membershipRevision() == 0L,
                "failed source construction advanced membership revision");
    }

    private static void publish(RtSectionSourceStore store, SectionTriangleMesh mesh) {
        store.publish(
                mesh,
                RtSceneMaterialTable.SectionMaterial.fromMesh(mesh),
                mesh.key().x(),
                RendererFrameCausality.untraced(mesh.key().x()),
                false,
                true
        );
    }

    private static long basePayloadBytes(SectionTriangleMesh mesh) {
        return Math.addExact(
                mesh.estimatedBytes(),
                RtSceneMaterialTable.SectionMaterial.fromMesh(mesh).estimatedBytes()
        );
    }

    private static SectionTriangleMesh mesh(int sectionX) {
        return new SectionTriangleMesh(
                new SectionKey(sectionX, 0, 0),
                new short[]{0, 0, 0, 16, 0, 0, 16, 16, 0, 0, 16, 0},
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{42},
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()}
        );
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
