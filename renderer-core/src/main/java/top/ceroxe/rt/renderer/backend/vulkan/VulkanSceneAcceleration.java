package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTlasBuilder;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.*;

/**
 * Stable-slot BLAS/TLAS generation authority for the generic GPUScene.
 *
 * <p>Dirty mesh BLAS builds and the candidate instance table are private until every build and the
 * successor TLAS complete. Activation swaps the entire generation at once. The displaced TLAS and
 * BLAS objects retire as one descriptor-epoch batch, with TLAS destroyed before any BLAS it may
 * still reference.</p>
 */
final class VulkanSceneAcceleration implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final VulkanGpuScene gpuScene;
    private final RtAccelerationStructure bootstrapBlas;
    private final ArrayList<RetiredGeneration> retired = new ArrayList<>();

    private Map<Integer, RtAccelerationStructure> activeMeshes = Map.of();
    private Map<Integer, VulkanGpuScene.InstanceGeometry> activeInstances = Map.of();
    private RtAccelerationStructure activeTlas;
    private PendingGeneration pending;
    private Lifecycle lifecycle = Lifecycle.READY;
    private long activeRevision = -1L;
    private long completedDescriptorEpoch = -1L;
    private Throwable terminalFailure;

    VulkanSceneAcceleration(VulkanDeviceRuntime device, VulkanGpuScene gpuScene) {
        this.device = Objects.requireNonNull(device, "device");
        this.gpuScene = Objects.requireNonNull(gpuScene, "gpuScene");
        this.bootstrapBlas = RtAccelerationStructure.buildBootstrapTriangleBlas(
                device.device(),
                device.allocator(),
                device.buildCommands(),
                device.accelerationStructureScratchAlignment()
        );
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close acceleration resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException collect(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    synchronized Admission submit(
            VulkanSceneResidency.SceneChangeSet changeSet,
            long retireAfterDescriptorEpoch
    ) throws BusyException {
        requireReady("submit acceleration generation");
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(changeSet, "changeSet");
        if (retireAfterDescriptorEpoch < 0L) {
            throw new IllegalArgumentException("retire descriptor epoch must not be negative");
        }
        try {
            advancePending();
        } catch (RuntimeException failure) {
            throw fail("advance prior acceleration generation", failure);
        }
        if (pending != null) {
            throw new BusyException("acceleration generation " + pending.revision + " is still building");
        }
        if (changes.baseRevision() != activeRevision) {
            throw fail(
                    "validate acceleration base revision",
                    new IllegalStateException(
                            "acceleration revision diverged: active=" + activeRevision
                                    + ", base=" + changes.baseRevision()
                    )
            );
        }
        VulkanGpuScene.Snapshot sceneState = gpuScene.snapshot();
        if (sceneState.activeRevision() != changes.revision()) {
            throw new BusyException(
                    "GPUScene generation is not active for acceleration build: required="
                            + changes.revision() + ", active=" + sceneState.activeRevision()
            );
        }

        PendingGeneration generation = null;
        try {
            Map<Integer, RtAccelerationStructure> candidateMeshes = changes.reset()
                    ? new HashMap<>()
                    : new HashMap<>(activeMeshes);
            for (int clearedSlot : changes.meshes().clearedSlots()) candidateMeshes.remove(clearedSlot);

            Map<Integer, VulkanGpuScene.InstanceGeometry> candidateInstances = changes.reset()
                    ? new HashMap<>()
                    : new HashMap<>(activeInstances);
            boolean accelerationChanged = changes.reset() || activeTlas == null
                    || !changes.meshes().writes().isEmpty()
                    || changes.meshes().clearedSlots().length != 0
                    || changes.instances().clearedSlots().length != 0;
            for (int clearedSlot : changes.instances().clearedSlots()) candidateInstances.remove(clearedSlot);
            for (StableIdentitySlots.SlotWrite<SceneInstance> write : changes.instances().writes()) {
                VulkanGpuScene.InstanceGeometry instance = gpuScene.resolveInstance(
                        write.value(), changes.revision()
                );
                if (instance.instanceSlot() != write.slot()) {
                    throw new IllegalStateException("GPUScene instance slot diverged from resident change set");
                }
                if (!instance.equals(activeInstances.get(instance.instanceSlot()))) {
                    accelerationChanged = true;
                }
                candidateInstances.put(instance.instanceSlot(), instance);
            }

            ArrayList<PendingMeshBuild> meshBuilds = new ArrayList<>(changes.meshes().writes().size());
            List<StableIdentitySlots.SlotWrite<MeshAsset>> orderedWrites = new ArrayList<>(
                    changes.meshes().writes()
            );
            orderedWrites.sort(Comparator.comparingInt(StableIdentitySlots.SlotWrite::slot));
            for (StableIdentitySlots.SlotWrite<MeshAsset> write : orderedWrites) {
                VulkanGpuScene.MeshGeometry geometry = gpuScene.resolveMesh(write.value(), changes.revision());
                if (geometry.meshSlot() != write.slot()) {
                    throw new IllegalStateException("GPUScene mesh slot diverged from resident change set");
                }
                RtDeviceTriangleBlasBuilder.PendingBuild build = RtDeviceTriangleBlasBuilder.submit(
                        device.device(),
                        device.allocator(),
                        device.buildCommands(),
                        device.accelerationStructureScratchAlignment(),
                        List.of(new RtDeviceTriangleBlasBuilder.Geometry(
                                geometry.positionDeviceAddress(),
                                geometry.indexDeviceAddress(),
                                geometry.vertexCount(),
                                geometry.primitiveCount(),
                                false
                        ))
                );
                meshBuilds.add(new PendingMeshBuild(geometry.meshSlot(), build));
            }
            if (!accelerationChanged) {
                // Material, texture, light and per-instance appearance fields live exclusively
                // in GPUScene buffers. Their scene revision can advance while retaining the
                // exact TLAS when BLAS addresses, transforms, masks and membership are unchanged.
                activeInstances = Map.copyOf(candidateInstances);
                activeRevision = changes.revision();
                return new Admission(changes.revision(), true, 0, candidateInstances.size());
            }
            generation = new PendingGeneration(
                    changes.revision(),
                    retireAfterDescriptorEpoch,
                    candidateMeshes,
                    candidateInstances,
                    meshBuilds
            );
            pending = generation;
            advancePending();
            return new Admission(
                    changes.revision(),
                    pending == null,
                    meshBuilds.size(),
                    candidateInstances.size()
            );
        } catch (RuntimeException failure) {
            if (pending == generation) pending = null;
            closeSuppressing(failure, generation);
            throw fail("submit acceleration generation " + changes.revision(), failure);
        }
    }

    synchronized Snapshot poll(long latestCompletedDescriptorEpoch) {
        requireReady("poll acceleration generation");
        if (latestCompletedDescriptorEpoch < completedDescriptorEpoch) {
            throw new IllegalArgumentException(
                    "completed descriptor epoch regressed: current=" + completedDescriptorEpoch
                            + ", supplied=" + latestCompletedDescriptorEpoch
            );
        }
        try {
            advancePending();
            if (latestCompletedDescriptorEpoch >= 0L) {
                releaseRetiredThrough(latestCompletedDescriptorEpoch);
                completedDescriptorEpoch = latestCompletedDescriptorEpoch;
            }
            return snapshot();
        } catch (RuntimeException failure) {
            throw fail("poll acceleration generation", failure);
        }
    }

    /**
     * Advances BLAS/TLAS fences without releasing descriptor-protected generations.
     */
    synchronized Snapshot pollCompletion() {
        requireReady("poll acceleration completion");
        try {
            advancePending();
            return snapshot();
        } catch (RuntimeException failure) {
            throw fail("poll acceleration completion", failure);
        }
    }

    synchronized RtAccelerationStructure requireActiveTlas(long requiredSceneRevision) {
        requireReady("resolve active TLAS");
        if (requiredSceneRevision < 0L || activeRevision != requiredSceneRevision || activeTlas == null) {
            throw new IllegalStateException(
                    "TLAS generation is not active: required=" + requiredSceneRevision
                            + ", active=" + activeRevision
            );
        }
        return activeTlas;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                lifecycle,
                activeRevision,
                pending == null ? -1L : pending.revision,
                activeMeshes.size(),
                activeInstances.size(),
                activeTlas != null,
                retired.size(),
                terminalFailure
        );
    }

    private void advancePending() {
        PendingGeneration generation = pending;
        if (generation == null) return;

        boolean allMeshesReady = true;
        for (PendingMeshBuild meshBuild : generation.meshBuilds) {
            if (meshBuild.completed != null) continue;
            RtAccelerationStructure completed = meshBuild.submission.completeIfReady();
            if (completed == null) {
                allMeshesReady = false;
                continue;
            }
            meshBuild.completed = completed;
            generation.candidateMeshes.put(meshBuild.meshSlot, completed);
        }
        if (!allMeshesReady) return;

        if (generation.tlasSubmission == null) {
            generation.tlasSubmission = RtDeviceTlasBuilder.submit(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment(),
                    tlasInstances(generation)
            );
        }
        RtDeviceTlasBuilder.CompletedBuild completedTlas = generation.tlasSubmission.completeIfReady();
        if (completedTlas == null) return;
        generation.completedTlas = completedTlas.accelerationStructure();
        activate(generation);
    }

    private List<RtDeviceTlasBuilder.Instance> tlasInstances(PendingGeneration generation) {
        if (generation.candidateInstances.isEmpty()) {
            return List.of(new RtDeviceTlasBuilder.Instance(
                    bootstrapBlas.deviceAddress(), AffineTransform.identity(), 0, 0
            ));
        }
        ArrayList<VulkanGpuScene.InstanceGeometry> ordered = new ArrayList<>(
                generation.candidateInstances.values()
        );
        ordered.sort(Comparator.comparingInt(VulkanGpuScene.InstanceGeometry::instanceSlot));
        ArrayList<RtDeviceTlasBuilder.Instance> result = new ArrayList<>(ordered.size());
        for (VulkanGpuScene.InstanceGeometry instance : ordered) {
            RtAccelerationStructure blas = generation.candidateMeshes.get(instance.meshSlot());
            if (blas == null) {
                throw new IllegalStateException(
                        "instance slot " + instance.instanceSlot()
                                + " references missing mesh slot " + instance.meshSlot()
                );
            }
            result.add(new RtDeviceTlasBuilder.Instance(
                    blas.deviceAddress(),
                    instance.transform(),
                    instance.instanceSlot(),
                    instance.visibilityMask()
            ));
        }
        return List.copyOf(result);
    }

    private void activate(PendingGeneration generation) {
        if (generation != pending || generation.completedTlas == null) {
            throw new IllegalStateException("acceleration activation generation is stale or incomplete");
        }
        ArrayList<RtAccelerationStructure> displacedBlases = new ArrayList<>();
        for (Map.Entry<Integer, RtAccelerationStructure> entry : activeMeshes.entrySet()) {
            if (generation.candidateMeshes.get(entry.getKey()) != entry.getValue()) {
                displacedBlases.add(entry.getValue());
            }
        }
        if (activeTlas != null || !displacedBlases.isEmpty()) {
            retired.add(new RetiredGeneration(
                    generation.retireAfterDescriptorEpoch,
                    activeTlas,
                    displacedBlases
            ));
        }
        activeMeshes = Map.copyOf(generation.candidateMeshes);
        activeInstances = Map.copyOf(generation.candidateInstances);
        activeTlas = generation.completedTlas;
        activeRevision = generation.revision;
        generation.transferred = true;
        pending = null;
    }

    private void releaseRetiredThrough(long completedEpoch) {
        RuntimeException failure = null;
        for (int index = retired.size() - 1; index >= 0; index--) {
            RetiredGeneration generation = retired.get(index);
            if (generation.safeAfterEpoch <= completedEpoch) {
                try {
                    generation.close();
                } catch (RuntimeException closeFailure) {
                    failure = collect(failure, closeFailure);
                }
                retired.remove(index);
            }
        }
        if (failure != null) throw failure;
    }

    private void requireReady(String operation) {
        if (lifecycle != Lifecycle.READY) {
            throw new IllegalStateException(
                    "cannot " + operation + " while acceleration owner is " + lifecycle,
                    terminalFailure
            );
        }
    }

    private IllegalStateException fail(String operation, RuntimeException cause) {
        lifecycle = Lifecycle.FAILED;
        if (terminalFailure == null) terminalFailure = cause;
        else if (terminalFailure != cause) terminalFailure.addSuppressed(cause);
        if (pending != null) {
            closeSuppressing(terminalFailure, pending);
            pending = null;
        }
        return new IllegalStateException(operation + " failed", cause);
    }

    @Override
    public synchronized void close() {
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.CLOSED;
        RuntimeException failure = null;
        failure = closeCollecting(failure, pending);
        pending = null;
        failure = closeCollecting(failure, activeTlas);
        activeTlas = null;
        for (RtAccelerationStructure blas : activeMeshes.values()) {
            failure = closeCollecting(failure, blas);
        }
        activeMeshes = Map.of();
        activeInstances = Map.of();
        for (RetiredGeneration generation : retired) {
            failure = closeCollecting(failure, generation);
        }
        retired.clear();
        failure = closeCollecting(failure, bootstrapBlas);
        if (failure != null) throw failure;
    }

    enum Lifecycle {
        READY,
        FAILED,
        CLOSED
    }

    record Admission(long revision, boolean active, int meshBuilds, int instances) {
        Admission {
            if (revision < 0L || meshBuilds < 0 || instances < 0) {
                throw new IllegalArgumentException("acceleration admission counters are invalid");
            }
        }
    }

    record Snapshot(
            Lifecycle lifecycle,
            long activeRevision,
            long pendingRevision,
            int activeMeshes,
            int activeInstances,
            boolean tlasReady,
            int retiredGenerations,
            Throwable terminalFailure
    ) {
        Snapshot {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            if (activeRevision < -1L || pendingRevision < -1L || activeMeshes < 0
                    || activeInstances < 0 || retiredGenerations < 0) {
                throw new IllegalArgumentException("acceleration snapshot contains invalid counters");
            }
        }
    }

    static final class BusyException extends Exception {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }

    private static final class PendingMeshBuild implements AutoCloseable {
        private final int meshSlot;
        private final RtDeviceTriangleBlasBuilder.PendingBuild submission;
        private RtAccelerationStructure completed;

        private PendingMeshBuild(int meshSlot, RtDeviceTriangleBlasBuilder.PendingBuild submission) {
            if (meshSlot < 0) throw new IllegalArgumentException("mesh slot must not be negative");
            this.meshSlot = meshSlot;
            this.submission = Objects.requireNonNull(submission, "submission");
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            failure = closeCollecting(failure, submission);
            failure = closeCollecting(failure, completed);
            completed = null;
            if (failure != null) throw failure;
        }
    }

    private static final class PendingGeneration implements AutoCloseable {
        private final long revision;
        private final long retireAfterDescriptorEpoch;
        private final Map<Integer, RtAccelerationStructure> candidateMeshes;
        private final Map<Integer, VulkanGpuScene.InstanceGeometry> candidateInstances;
        private final List<PendingMeshBuild> meshBuilds;
        private RtDeviceTlasBuilder.PendingBuild tlasSubmission;
        private RtAccelerationStructure completedTlas;
        private boolean transferred;

        private PendingGeneration(
                long revision,
                long retireAfterDescriptorEpoch,
                Map<Integer, RtAccelerationStructure> candidateMeshes,
                Map<Integer, VulkanGpuScene.InstanceGeometry> candidateInstances,
                List<PendingMeshBuild> meshBuilds
        ) {
            this.revision = revision;
            this.retireAfterDescriptorEpoch = retireAfterDescriptorEpoch;
            this.candidateMeshes = Objects.requireNonNull(candidateMeshes, "candidateMeshes");
            this.candidateInstances = Objects.requireNonNull(candidateInstances, "candidateInstances");
            this.meshBuilds = List.copyOf(Objects.requireNonNull(meshBuilds, "meshBuilds"));
        }

        @Override
        public void close() {
            if (transferred) return;
            RuntimeException failure = null;
            failure = closeCollecting(failure, tlasSubmission);
            if (completedTlas != null) failure = closeCollecting(failure, completedTlas);
            completedTlas = null;
            for (PendingMeshBuild build : meshBuilds) {
                failure = closeCollecting(failure, build);
            }
            if (failure != null) throw failure;
        }
    }

    private static final class RetiredGeneration implements AutoCloseable {
        private final long safeAfterEpoch;
        private final RtAccelerationStructure tlas;
        private final List<RtAccelerationStructure> blases;

        private RetiredGeneration(
                long safeAfterEpoch,
                RtAccelerationStructure tlas,
                List<RtAccelerationStructure> blases
        ) {
            if (safeAfterEpoch < 0L) throw new IllegalArgumentException("retirement epoch is invalid");
            this.safeAfterEpoch = safeAfterEpoch;
            this.tlas = tlas;
            this.blases = List.copyOf(Objects.requireNonNull(blases, "blases"));
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            failure = closeCollecting(failure, tlas);
            for (RtAccelerationStructure blas : blases) failure = closeCollecting(failure, blas);
            if (failure != null) throw failure;
        }
    }
}
