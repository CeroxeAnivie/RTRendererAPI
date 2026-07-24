package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.List;

/** Stable BLAS reuse, TLAS generation activation, and descriptor-epoch retirement gate. */
public final class VulkanSceneAccelerationNativeSelfTest {
    private static final long TIMEOUT_NANOS = 10_000_000_000L;

    private VulkanSceneAccelerationNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "scene acceleration gate requires hardware RT: " + capability.summary());

        try (VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);
             VulkanGpuScene gpuScene = new VulkanGpuScene(new VulkanGpuSceneBuffers(
                     device.device(), device.allocator(), device.buildCommands()));
             VulkanSceneAcceleration acceleration = new VulkanSceneAcceleration(device, gpuScene)) {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate initial = residency.prepare(initialScene());
            activateGpuScene(gpuScene, initial.changeSet(), 0L);
            VulkanSceneAcceleration.Admission initialAdmission = acceleration.submit(
                    initial.changeSet(), 0L
            );
            residency.commit(initial);
            VulkanSceneAcceleration.Snapshot first = awaitAcceleration(acceleration, 0L, 0L);
            require(initialAdmission.meshBuilds() == 1 && first.activeMeshes() == 1
                            && first.activeInstances() == 1 && first.tlasReady(),
                    "initial acceleration generation did not publish one mesh and instance");
            long firstTlas = acceleration.requireActiveTlas(0L).handle();

            VulkanSceneResidency.PreparedUpdate moved = residency.prepare(instanceMove(1L));
            activateGpuScene(gpuScene, moved.changeSet(), 1L);
            VulkanSceneAcceleration.Admission movedAdmission = acceleration.submit(
                    moved.changeSet(), 1L
            );
            residency.commit(moved);
            VulkanSceneAcceleration.Snapshot second = awaitAcceleration(acceleration, 1L, 0L);
            long secondTlas = acceleration.requireActiveTlas(1L).handle();
            require(movedAdmission.meshBuilds() == 0,
                    "instance-only generation rebuilt an unchanged BLAS");
            require(second.activeMeshes() == 1 && second.activeInstances() == 1
                            && second.retiredGenerations() == 1 && secondTlas != firstTlas,
                    "instance-only generation did not replace and retire its TLAS");

            VulkanSceneAcceleration.Snapshot released = acceleration.poll(1L);
            require(released.retiredGenerations() == 0,
                    "completed descriptor epoch did not release the retired TLAS generation");

            VulkanSceneResidency.PreparedUpdate faded = residency.prepare(instanceFade(2L));
            activateGpuScene(gpuScene, faded.changeSet(), 2L);
            VulkanSceneAcceleration.Admission fadedAdmission = acceleration.submit(
                    faded.changeSet(), 2L
            );
            residency.commit(faded);
            VulkanSceneAcceleration.Snapshot third = awaitAcceleration(acceleration, 2L, 1L);
            long thirdTlas = acceleration.requireActiveTlas(2L).handle();
            require(fadedAdmission.active() && fadedAdmission.meshBuilds() == 0,
                    "appearance-only generation did not activate synchronously");
            require(thirdTlas == secondTlas && third.retiredGenerations() == 0,
                    "appearance-only instance update rebuilt or retired an unchanged TLAS");
            System.out.println("VulkanSceneAccelerationNativeSelfTest passed: device="
                    + capability.preferredDevice().name()
                    + ", activeRevision=" + third.activeRevision()
                    + ", meshes=" + third.activeMeshes()
                    + ", instances=" + third.activeInstances()
                    + ", appearanceOnlyTlasReused=" + (thirdTlas == secondTlas));
        }
    }

    private static void activateGpuScene(
            VulkanGpuScene gpuScene,
            VulkanSceneResidency.SceneChangeSet changes,
            long retireEpoch
    ) throws Exception {
        gpuScene.submit(changes, retireEpoch);
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            if (gpuScene.poll(Math.max(0L, retireEpoch - 1L)).activeRevision() == changes.revision()) return;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("GPUScene generation did not activate: " + changes.revision());
    }

    private static VulkanSceneAcceleration.Snapshot awaitAcceleration(
            VulkanSceneAcceleration acceleration,
            long revision,
            long completedEpoch
    ) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        VulkanSceneAcceleration.Snapshot state;
        do {
            state = acceleration.poll(completedEpoch);
            if (state.activeRevision() == revision) return state;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("acceleration generation did not activate: " + state);
    }

    private static SceneTransaction initialScene() {
        return new SceneTransaction(
                0L,
                true,
                new SceneTransaction.Upserts(
                        List.of(),
                        List.of(material()),
                        List.of(mesh()),
                        List.of(instance(AffineTransform.identity())),
                        List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static SceneTransaction instanceMove(long revision) {
        AffineTransform moved = new AffineTransform(new float[]{
                1, 0, 0, 4,
                0, 1, 0, 2,
                0, 0, 1, -3
        });
        return new SceneTransaction(
                revision,
                false,
                new SceneTransaction.Upserts(
                        List.of(), List.of(), List.of(), List.of(instance(moved)), List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static SceneTransaction instanceFade(long revision) {
        return new SceneTransaction(
                revision,
                false,
                new SceneTransaction.Upserts(
                        List.of(), List.of(), List.of(),
                        List.of(new SceneInstance(
                                40L, 30L, new AffineTransform(new float[]{
                                        1, 0, 0, 4,
                                        0, 1, 0, 2,
                                        0, 0, 1, -3
                                }),
                                SceneInstance.Mobility.DYNAMIC, 0xff, true, 0.5F
                        )),
                        List.of()
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static MaterialAsset material() {
        return new MaterialAsset(
                20L, MaterialAsset.BlendMode.OPAQUE, 0xffc08040,
                -1L, -1L, -1L, -1L, 0xff000000,
                1.0F, 0.0F, 0.65F, 0.0F, 0.0F, 1.5F, true
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
        return new SceneInstance(
                40L, 30L, transform, SceneInstance.Mobility.DYNAMIC, 0xff, true
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
