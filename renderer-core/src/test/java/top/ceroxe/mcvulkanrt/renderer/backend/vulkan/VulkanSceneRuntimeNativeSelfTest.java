package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;

import java.util.List;

/** Production scene admission, convergence, backpressure, and retirement boundary gate. */
public final class VulkanSceneRuntimeNativeSelfTest {
    private static final long TIMEOUT_NANOS = 10_000_000_000L;

    private VulkanSceneRuntimeNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "scene runtime gate requires hardware RT: " + capability.summary());

        try (VulkanSceneRuntime runtime = VulkanSceneRuntime.open(
                capability, RendererRtDiagnostics.noop())) {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate initial = residency.prepare(initialScene());
            VulkanSceneRuntime.Admission admitted = runtime.apply(initial.changeSet(), 0L);
            residency.commit(initial);
            require(admitted.revision() == 0L && admitted.uploadBytes() > 0L,
                    "scene runtime did not admit real GPU work");

            VulkanSceneResidency.PreparedUpdate premature = residency.prepare(moveInstance(1L));
            boolean busy = false;
            VulkanSceneRuntime.Admission moved = null;
            try {
                moved = runtime.apply(premature.changeSet(), 1L);
            } catch (VulkanSceneRuntime.BusyException expected) {
                busy = true;
            }
            if (busy) {
                VulkanSceneRuntime.Snapshot active = awaitActive(runtime, 0L);
                require(active.gpuScene().activeRevision() == 0L
                                && active.acceleration().activeRevision() == 0L,
                        "scene runtime exposed mismatched GPUScene and AS generations");
                moved = runtime.apply(premature.changeSet(), 1L);
            }
            require(moved != null, "successor scene generation was neither accepted nor backpressured");
            residency.commit(premature);
            VulkanSceneRuntime.Snapshot successor = awaitActive(runtime, 1L);
            require(moved.revision() == 1L && successor.activeRevision() == 1L
                            && successor.acceleration().retiredGenerations() == 1,
                    "successor scene generation did not converge or retire its predecessor");
            VulkanSceneRuntime.Snapshot retired = runtime.poll(1L);
            require(retired.acceleration().retiredGenerations() == 0,
                    "real descriptor completion epoch did not release retired acceleration");
            System.out.println("VulkanSceneRuntimeNativeSelfTest passed: device="
                    + capability.preferredDevice().name()
                    + ", accepted=" + retired.acceptedRevision()
                    + ", active=" + retired.activeRevision());
        }
    }

    private static VulkanSceneRuntime.Snapshot awaitActive(VulkanSceneRuntime runtime, long revision)
            throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        VulkanSceneRuntime.Snapshot snapshot;
        do {
            snapshot = runtime.snapshot();
            if (snapshot.activeRevision() == revision) return snapshot;
            runtime.pollCompletion();
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("scene runtime did not converge: " + snapshot);
    }

    private static SceneTransaction initialScene() {
        return new SceneTransaction(
                0L, true,
                new SceneTransaction.Upserts(
                        List.of(), List.of(material()), List.of(mesh()),
                        List.of(instance(AffineTransform.identity())), List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static SceneTransaction moveInstance(long revision) {
        return new SceneTransaction(
                revision, false,
                new SceneTransaction.Upserts(
                        List.of(), List.of(), List.of(),
                        List.of(instance(new AffineTransform(new float[]{
                                1, 0, 0, 3,
                                0, 1, 0, 2,
                                0, 0, 1, -1
                        }))),
                        List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static MaterialAsset material() {
        return new MaterialAsset(
                20L, MaterialAsset.BlendMode.OPAQUE, 0xff80c040,
                -1L, -1L, -1L, -1L, 0xff000000,
                1.0F, 0.0F, 0.55F, 0.0F, 0.0F, 1.5F, true
        );
    }

    private static MeshAsset mesh() {
        return new MeshAsset(
                30L,
                new float[]{-1, -1, 0, 1, -1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{20L}
        );
    }

    private static SceneInstance instance(AffineTransform transform) {
        return new SceneInstance(40L, 30L, transform, SceneInstance.Mobility.DYNAMIC, 0xff, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
