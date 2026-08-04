package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.PrimitiveInstance;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.TextureAsset;
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
    private static final int MAX_REUSABLE_TLAS_DESTINATIONS = 2;
    private final VulkanDeviceRuntime device;
    private final VulkanGpuScene gpuScene;
    private final VulkanBlasBuildCoordinator blasBuilds;
    private final RtAccelerationStructure bootstrapBlas;
    private final RtDeviceTlasBuilder.PersistentBuildLane tlasBuildLane;
    private final ArrayList<RetiredGeneration> retired = new ArrayList<>();
    private final ArrayDeque<RtAccelerationStructure> reusableTlasDestinations = new ArrayDeque<>();

    private final HashMap<Integer, RtAccelerationStructure> activeMeshes = new HashMap<>();
    private final HashMap<Integer, MeshAsset> activeMeshAssets = new HashMap<>();
    private final HashMap<Long, MaterialAsset> activeMaterials = new HashMap<>();
    private final HashMap<Long, TextureAsset> activeTextures = new HashMap<>();
    private final HashMap<Integer, VulkanGpuScene.InstanceGeometry> activeInstances = new HashMap<>();
    private RtAccelerationStructure activeTlas;
    private PendingGeneration pending;
    private Lifecycle lifecycle = Lifecycle.READY;
    private long activeRevision = -1L;
    private long completedDescriptorEpoch = -1L;
    private long tlasBuilds;
    private long tlasUpdates;
    private long recycledTlasDestinations;
    private Throwable terminalFailure;

    VulkanSceneAcceleration(VulkanDeviceRuntime device, VulkanGpuScene gpuScene) {
        this.device = Objects.requireNonNull(device, "device");
        this.gpuScene = Objects.requireNonNull(gpuScene, "gpuScene");
        this.blasBuilds = new VulkanBlasBuildCoordinator(device);
        RtAccelerationStructure createdBootstrap = null;
        RtDeviceTlasBuilder.PersistentBuildLane createdLane = null;
        try {
            createdBootstrap = RtAccelerationStructure.buildBootstrapTriangleBlas(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment()
            );
            createdLane = RtDeviceTlasBuilder.openPersistentLane(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment()
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, createdLane);
            closeSuppressing(failure, createdBootstrap);
            throw failure;
        }
        bootstrapBlas = createdBootstrap;
        tlasBuildLane = createdLane;
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
        } catch (Error failure) {
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
            HashMap<Long, MaterialAsset> nextMaterials = nextMaterials(changes);
            HashMap<Long, TextureAsset> nextTextures = nextTextures(changes);
            HashMap<Integer, MeshAsset> nextMeshAssets = nextMeshAssets(changes);
            HashMap<Integer, VulkanGpuScene.InstanceGeometry> instanceWrites = new HashMap<>();
            for (StableIdentitySlots.SlotWrite<SceneInstance> write : changes.instances().writes()) {
                VulkanGpuScene.InstanceGeometry instance = gpuScene.resolveInstance(
                        write.value(), changes.revision()
                );
                if (instance.instanceSlot() != write.slot()) {
                    throw new IllegalStateException("GPUScene instance slot diverged from resident change set");
                }
                if (changes.reset() || !instance.equals(activeInstances.get(instance.instanceSlot()))) {
                    instanceWrites.put(instance.instanceSlot(), instance);
                }
            }

            int[] clearedMeshSlots = changes.meshes().clearedSlots();
            int[] clearedInstanceSlots = changes.instances().clearedSlots();
            TreeMap<Integer, MeshAsset> dirtyMeshAssets = dirtyMeshAssets(
                    changes, nextMeshAssets, nextMaterials
            );
            boolean accelerationChanged = changes.reset() || activeTlas == null
                    || !dirtyMeshAssets.isEmpty()
                    || clearedMeshSlots.length != 0
                    || clearedInstanceSlots.length != 0
                    || !instanceWrites.isEmpty();
            if (!accelerationChanged) {
                // Texture, material, light, vertex shading attributes and per-instance appearance
                // remain outside acceleration inputs. Alpha coverage is evaluated by any-hit
                // shading.
                replaceAssetState(nextTextures, nextMaterials, nextMeshAssets);
                activeRevision = changes.revision();
                return new Admission(changes.revision(), true, 0, activeInstances.size());
            }

            ArrayList<VulkanBlasBuildCoordinator.Request> meshPlan =
                    new ArrayList<>(dirtyMeshAssets.size());
            for (Map.Entry<Integer, MeshAsset> entry : dirtyMeshAssets.entrySet()) {
                MeshAsset mesh = entry.getValue();
                VulkanGpuScene.MeshGeometry geometry = gpuScene.resolveMesh(mesh, changes.revision());
                if (geometry.meshSlot() != entry.getKey()) {
                    throw new IllegalStateException("GPUScene mesh slot diverged from resident change set");
                }
                RtDeviceTriangleBlasBuilder.Geometry triangleGeometry =
                        new RtDeviceTriangleBlasBuilder.Geometry(
                                geometry.positionDeviceAddress(),
                                geometry.indexDeviceAddress(),
                                geometry.vertexCount(),
                                geometry.primitiveCount(),
                                false
                        );
                meshPlan.add(new VulkanBlasBuildCoordinator.Request(
                        geometry.meshSlot(), triangleGeometry
                ));
            }
            List<VulkanBlasBuildCoordinator.PendingBuild> meshBuilds = blasBuilds.submit(meshPlan);
            generation = new PendingGeneration(
                    changes.revision(),
                    retireAfterDescriptorEpoch,
                    changes.reset(),
                    clearedMeshSlots,
                    clearedInstanceSlots,
                    instanceWrites,
                    meshBuilds,
                    nextTextures,
                    nextMaterials,
                    nextMeshAssets
            );
            pending = generation;
            advancePending();
            return new Admission(
                    changes.revision(),
                    pending == null,
                    meshBuilds.size(),
                    prospectiveInstanceCount(generation)
            );
        } catch (RuntimeException failure) {
            if (pending == generation) pending = null;
            closeSuppressing(failure, generation);
            throw fail("submit acceleration generation " + changes.revision(), failure);
        } catch (Error failure) {
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
        } catch (Error failure) {
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
        } catch (Error failure) {
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

    /**
     * Resolves a frame-slot-local instance generation without changing persistent revision state.
     * Persistent BLAS owners remain the sole geometry authority; this method only borrows their
     * device addresses while the scene revision is active.
     */
    synchronized FrameInstances frameInstances(
            FramePrimitiveBatch batch,
            long requiredSceneRevision
    ) {
        requireReady("resolve frame primitive instances");
        FramePrimitiveBatch checked = Objects.requireNonNull(batch, "batch");
        if (requiredSceneRevision < 0L || activeRevision != requiredSceneRevision || activeTlas == null) {
            throw new IllegalStateException(
                    "frame primitives require active scene revision " + requiredSceneRevision
                            + ", active=" + activeRevision
            );
        }
        if (checked.isEmpty()) return new FrameInstances(List.of(), new int[0]);

        ArrayList<RtDeviceTlasBuilder.Instance> instances = new ArrayList<>(
                activeInstances.size() + checked.size()
        );
        if (!activeInstances.isEmpty()) {
            for (RtDeviceTlasBuilder.Instance persistent
                    : tlasInstances(activeInstances.values(), activeMeshes::get)) {
                if ((persistent.customIndex() & VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT) != 0) {
                    throw new IllegalStateException(
                            "persistent instance slot collides with transient custom-index namespace"
                    );
                }
                instances.add(persistent);
            }
        }

        int[] packedWords = new int[Math.multiplyExact(
                checked.size(), VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS
        )];
        for (int index = 0; index < checked.size(); index++) {
            PrimitiveInstance primitive = checked.primitives().get(index);
            int meshSlot = gpuScene.resolveMeshSlot(primitive.meshAssetId(), requiredSceneRevision);
            RtAccelerationStructure blas = activeMeshes.get(meshSlot);
            if (blas == null) {
                throw new IllegalStateException(
                        "frame primitive references mesh without active BLAS: " + primitive.meshAssetId()
                );
            }
            instances.add(new RtDeviceTlasBuilder.Instance(
                    blas.deviceAddress(),
                    primitive.transform(),
                    VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT | index,
                    primitive.visibilityMask()
            ));
            int[] record = VulkanGpuSceneAbi.packPrimitive(
                    primitive,
                    id -> gpuScene.resolveMeshSlot(id, requiredSceneRevision)
            );
            System.arraycopy(
                    record, 0, packedWords,
                    index * VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS,
                    VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS
            );
        }
        return new FrameInstances(List.copyOf(instances), packedWords);
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
                tlasBuilds,
                tlasUpdates,
                recycledTlasDestinations,
                terminalFailure
        );
    }

    private HashMap<Long, MaterialAsset> nextMaterials(VulkanSceneResidency.SceneChangeSet changes) {
        HashMap<Long, MaterialAsset> result = changes.reset()
                ? new HashMap<>() : new HashMap<>(activeMaterials);
        for (long identity : changes.materials().removedIdentities()) result.remove(identity);
        for (StableIdentitySlots.SlotWrite<MaterialAsset> write : changes.materials().writes()) {
            result.put(write.id(), write.value());
        }
        return result;
    }

    private HashMap<Long, TextureAsset> nextTextures(VulkanSceneResidency.SceneChangeSet changes) {
        HashMap<Long, TextureAsset> result = changes.reset()
                ? new HashMap<>() : new HashMap<>(activeTextures);
        for (long identity : changes.textures().removedIdentities()) result.remove(identity);
        for (StableIdentitySlots.SlotWrite<TextureAsset> write : changes.textures().writes()) {
            result.put(write.id(), write.value());
        }
        return result;
    }

    private HashMap<Integer, MeshAsset> nextMeshAssets(VulkanSceneResidency.SceneChangeSet changes) {
        HashMap<Integer, MeshAsset> result = changes.reset()
                ? new HashMap<>() : new HashMap<>(activeMeshAssets);
        for (int slot : changes.meshes().clearedSlots()) result.remove(slot);
        for (StableIdentitySlots.SlotWrite<MeshAsset> write : changes.meshes().writes()) {
            result.put(write.slot(), write.value());
        }
        return result;
    }

    private TreeMap<Integer, MeshAsset> dirtyMeshAssets(
            VulkanSceneResidency.SceneChangeSet changes,
            Map<Integer, MeshAsset> nextMeshAssets,
            Map<Long, MaterialAsset> nextMaterials
    ) {
        TreeMap<Integer, MeshAsset> dirty = new TreeMap<>();
        for (StableIdentitySlots.SlotWrite<MeshAsset> write : changes.meshes().writes()) {
            VulkanSceneResidency.MeshUpdate update = changes.meshUpdates().get(write.id());
            if (update.dirty(VulkanSceneResidency.MeshDirtyMask.BLAS)) {
                dirty.put(write.slot(), write.value());
            }
        }
        return dirty;
    }

    private void replaceAssetState(
            Map<Long, TextureAsset> textures,
            Map<Long, MaterialAsset> materials,
            Map<Integer, MeshAsset> meshes
    ) {
        activeTextures.clear();
        activeTextures.putAll(textures);
        activeMaterials.clear();
        activeMaterials.putAll(materials);
        activeMeshAssets.clear();
        activeMeshAssets.putAll(meshes);
    }

    private void advancePending() {
        PendingGeneration generation = pending;
        if (generation == null) return;

        boolean allMeshesReady = blasBuilds.advance(generation.meshBuilds);
        if (!allMeshesReady) return;

        if (generation.tlasSubmission == null) {
            List<RtDeviceTlasBuilder.Instance> instances = tlasInstances(generation);
            List<RtDeviceTlasBuilder.Instance> active = activeTlasInstances();
            if (instances.equals(active)) {
                generation.reuseActiveTlas = true;
                activate(generation);
                return;
            }
            boolean update = activeTlas != null && active.size() == instances.size();
            generation.tlasSubmission = update
                    ? tlasBuildLane.submitUpdate(
                    activeTlas,
                    reusableTlasDestinations.pollFirst(),
                    instances,
                    dirtyInstanceSlots(active, instances)
            )
                    : tlasBuildLane.submitBuild(instances);
        }
        RtDeviceTlasBuilder.CompletedBuild completedTlas = generation.tlasSubmission.completeIfReady();
        if (completedTlas == null) return;
        if (completedTlas.update()) tlasUpdates++;
        else tlasBuilds++;
        if (completedTlas.recycledDestination()) recycledTlasDestinations++;
        generation.completedTlas = completedTlas.accelerationStructure();
        activate(generation);
    }

    private List<RtDeviceTlasBuilder.Instance> tlasInstances(PendingGeneration generation) {
        ArrayList<VulkanGpuScene.InstanceGeometry> instances = new ArrayList<>(prospectiveInstanceCount(generation));
        if (!generation.reset) {
            for (VulkanGpuScene.InstanceGeometry instance : activeInstances.values()) {
                if (!generation.clearedInstanceSlots.contains(instance.instanceSlot())
                        && !generation.instanceWrites.containsKey(instance.instanceSlot())) {
                    instances.add(instance);
                }
            }
        }
        instances.addAll(generation.instanceWrites.values());
        return tlasInstances(instances, slot -> generation.mesh(slot, activeMeshes));
    }

    private List<RtDeviceTlasBuilder.Instance> activeTlasInstances() {
        if (activeTlas == null) return List.of();
        return tlasInstances(activeInstances.values(), activeMeshes::get);
    }

    private List<RtDeviceTlasBuilder.Instance> tlasInstances(
            Collection<VulkanGpuScene.InstanceGeometry> instances,
            java.util.function.IntFunction<RtAccelerationStructure> meshResolver
    ) {
        if (instances.isEmpty()) {
            return List.of(new RtDeviceTlasBuilder.Instance(
                    bootstrapBlas.deviceAddress(), AffineTransform.identity(), 0, 0
            ));
        }
        ArrayList<VulkanGpuScene.InstanceGeometry> ordered = new ArrayList<>(
                instances
        );
        ordered.sort(Comparator.comparingInt(VulkanGpuScene.InstanceGeometry::instanceSlot));
        ArrayList<RtDeviceTlasBuilder.Instance> result = new ArrayList<>(ordered.size());
        for (VulkanGpuScene.InstanceGeometry instance : ordered) {
            RtAccelerationStructure blas = meshResolver.apply(instance.meshSlot());
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

    private int prospectiveInstanceCount(PendingGeneration generation) {
        if (generation.reset) return generation.instanceWrites.size();
        int count = activeInstances.size();
        for (int slot : generation.clearedInstanceSlots) {
            if (activeInstances.containsKey(slot) && !generation.instanceWrites.containsKey(slot)) count--;
        }
        for (int slot : generation.instanceWrites.keySet()) {
            if (!activeInstances.containsKey(slot) || generation.clearedInstanceSlots.contains(slot)) count++;
        }
        return count;
    }

    private static int[] dirtyInstanceSlots(
            List<RtDeviceTlasBuilder.Instance> previous,
            List<RtDeviceTlasBuilder.Instance> next
    ) {
        if (previous.size() != next.size()) {
            throw new IllegalArgumentException("TLAS update instance counts must match");
        }
        int[] dirty = new int[next.size()];
        int count = 0;
        for (int index = 0; index < next.size(); index++) {
            if (!next.get(index).equals(previous.get(index))) dirty[count++] = index;
        }
        return Arrays.copyOf(dirty, count);
    }

    private void activate(PendingGeneration generation) {
        if (generation != pending || generation.completedTlas == null && !generation.reuseActiveTlas) {
            throw new IllegalStateException("acceleration activation generation is stale or incomplete");
        }
        ArrayList<RtAccelerationStructure> displacedBlases = new ArrayList<>();
        for (Map.Entry<Integer, RtAccelerationStructure> entry : activeMeshes.entrySet()) {
            if (generation.mesh(entry.getKey(), activeMeshes) != entry.getValue()) {
                displacedBlases.add(entry.getValue());
            }
        }
        RtAccelerationStructure displacedTlas = generation.reuseActiveTlas ? null : activeTlas;
        if (displacedTlas != null || !displacedBlases.isEmpty()) {
            retired.add(new RetiredGeneration(
                    generation.retireAfterDescriptorEpoch,
                    displacedTlas,
                    displacedBlases
            ));
        }
        if (generation.reset) {
            activeMeshes.clear();
            activeInstances.clear();
        } else {
            for (int slot : generation.clearedMeshSlots) activeMeshes.remove(slot);
            for (int slot : generation.clearedInstanceSlots) activeInstances.remove(slot);
        }
        for (VulkanBlasBuildCoordinator.PendingBuild meshBuild : generation.meshBuilds) {
            activeMeshes.put(meshBuild.meshSlot(), meshBuild.completed());
        }
        replaceAssetState(generation.textures, generation.materials, generation.meshAssets);
        activeInstances.putAll(generation.instanceWrites);
        if (!generation.reuseActiveTlas) activeTlas = generation.completedTlas;
        activeRevision = generation.revision;
        generation.transferred = true;
        pending = null;
    }

    private void releaseRetiredThrough(long completedEpoch) {
        RuntimeException failure = null;
        for (int index = retired.size() - 1; index >= 0; index--) {
            RetiredGeneration generation = retired.get(index);
            if (generation.safeAfterEpoch <= completedEpoch) {
                RtAccelerationStructure reusable = generation.detachTlas();
                try {
                    generation.close();
                } catch (RuntimeException closeFailure) {
                    failure = collect(failure, closeFailure);
                }
                if (reusable != null) {
                    if (reusableTlasDestinations.size() < MAX_REUSABLE_TLAS_DESTINATIONS) {
                        reusableTlasDestinations.addLast(reusable);
                    } else {
                        failure = closeCollecting(failure, reusable);
                    }
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
        recordTerminalFailure(cause);
        return new IllegalStateException(operation + " failed", cause);
    }

    private Error fail(String operation, Error cause) {
        recordTerminalFailure(cause);
        return cause;
    }

    private void recordTerminalFailure(Throwable cause) {
        lifecycle = Lifecycle.FAILED;
        if (terminalFailure == null) terminalFailure = cause;
        else if (terminalFailure != cause) terminalFailure.addSuppressed(cause);
        if (pending != null) {
            closeSuppressing(terminalFailure, pending);
            pending = null;
        }
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
        activeMeshes.clear();
        activeMeshAssets.clear();
        activeMaterials.clear();
        activeTextures.clear();
        activeInstances.clear();
        for (RetiredGeneration generation : retired) {
            failure = closeCollecting(failure, generation);
        }
        retired.clear();
        for (RtAccelerationStructure reusable : reusableTlasDestinations) {
            failure = closeCollecting(failure, reusable);
        }
        reusableTlasDestinations.clear();
        failure = closeCollecting(failure, tlasBuildLane);
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

    record FrameInstances(List<RtDeviceTlasBuilder.Instance> tlasInstances, int[] packedWords) {
        FrameInstances {
            tlasInstances = List.copyOf(Objects.requireNonNull(tlasInstances, "tlasInstances"));
            packedWords = Objects.requireNonNull(packedWords, "packedWords").clone();
            if (packedWords.length % VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS != 0) {
                throw new IllegalArgumentException("frame instance payload has an invalid record stride");
            }
        }

        @Override
        public int[] packedWords() {
            return packedWords.clone();
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
            long tlasBuilds,
            long tlasUpdates,
            long recycledTlasDestinations,
            Throwable terminalFailure
    ) {
        Snapshot {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            if (activeRevision < -1L || pendingRevision < -1L || activeMeshes < 0
                    || activeInstances < 0 || retiredGenerations < 0 || tlasBuilds < 0L
                    || tlasUpdates < 0L || recycledTlasDestinations < 0L
                    || recycledTlasDestinations > tlasUpdates) {
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

    private static final class PendingGeneration implements AutoCloseable {
        private final long revision;
        private final long retireAfterDescriptorEpoch;
        private final boolean reset;
        private final IntOpenHashSet clearedMeshSlots;
        private final IntOpenHashSet clearedInstanceSlots;
        private final Map<Integer, VulkanGpuScene.InstanceGeometry> instanceWrites;
        private final ArrayList<VulkanBlasBuildCoordinator.PendingBuild> meshBuilds;
        private final Map<Long, TextureAsset> textures;
        private final Map<Long, MaterialAsset> materials;
        private final Map<Integer, MeshAsset> meshAssets;
        private RtDeviceTlasBuilder.PendingBuild tlasSubmission;
        private RtAccelerationStructure completedTlas;
        private boolean reuseActiveTlas;
        private boolean transferred;

        private PendingGeneration(
                long revision,
                long retireAfterDescriptorEpoch,
                boolean reset,
                int[] clearedMeshSlots,
                int[] clearedInstanceSlots,
                Map<Integer, VulkanGpuScene.InstanceGeometry> instanceWrites,
                List<VulkanBlasBuildCoordinator.PendingBuild> meshBuilds,
                Map<Long, TextureAsset> textures,
                Map<Long, MaterialAsset> materials,
                Map<Integer, MeshAsset> meshAssets
        ) {
            this.revision = revision;
            this.retireAfterDescriptorEpoch = retireAfterDescriptorEpoch;
            this.reset = reset;
            this.clearedMeshSlots = new IntOpenHashSet(
                    Objects.requireNonNull(clearedMeshSlots, "clearedMeshSlots")
            );
            this.clearedInstanceSlots = new IntOpenHashSet(
                    Objects.requireNonNull(clearedInstanceSlots, "clearedInstanceSlots")
            );
            this.instanceWrites = Map.copyOf(Objects.requireNonNull(instanceWrites, "instanceWrites"));
            this.meshBuilds = new ArrayList<>(Objects.requireNonNull(meshBuilds, "meshBuilds"));
            this.textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
            this.materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
            this.meshAssets = Map.copyOf(Objects.requireNonNull(meshAssets, "meshAssets"));
        }

        private RtAccelerationStructure mesh(
                int slot,
                Map<Integer, RtAccelerationStructure> activeMeshes
        ) {
            for (VulkanBlasBuildCoordinator.PendingBuild build : meshBuilds) {
                if (build.meshSlot() == slot) return build.completed();
            }
            if (reset || clearedMeshSlots.contains(slot)) return null;
            return activeMeshes.get(slot);
        }

        @Override
        public void close() {
            if (transferred) return;
            RuntimeException failure = null;
            failure = closeCollecting(failure, tlasSubmission);
            if (completedTlas != null) failure = closeCollecting(failure, completedTlas);
            completedTlas = null;
            for (VulkanBlasBuildCoordinator.PendingBuild build : meshBuilds) {
                failure = closeCollecting(failure, build);
            }
            if (failure != null) throw failure;
        }
    }

    private static final class RetiredGeneration implements AutoCloseable {
        private final long safeAfterEpoch;
        private RtAccelerationStructure tlas;
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

        private RtAccelerationStructure detachTlas() {
            RtAccelerationStructure detached = tlas;
            tlas = null;
            return detached;
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
