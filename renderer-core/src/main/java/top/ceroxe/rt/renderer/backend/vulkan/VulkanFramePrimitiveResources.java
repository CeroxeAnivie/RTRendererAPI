package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTlasBuilder;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Owns frame-slot-local instance records and a bounded ping-pong TLAS update lane.
 *
 * <p>The owner is touched only while its matching output slot is free. This lets host-visible
 * records be rewritten without racing an earlier descriptor generation and permits the displaced
 * TLAS to become the next update destination instead of allocating every frame.</p>
 */
final class VulkanFramePrimitiveResources implements AutoCloseable {
    private static final long MINIMUM_BUFFER_BYTES = (long) VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS
            * Integer.BYTES;

    private final VulkanDeviceRuntime device;
    private final RtStallTelemetrySink stalls;
    private final RtDeviceTlasBuilder.PersistentBuildLane buildLane;

    private RtGpuBuffer instanceBuffer;
    private RtAccelerationStructure activeTlas;
    private RtAccelerationStructure reusableTlas;
    private RtTlasBuildPlan pendingBuild;
    private FrameKey pendingKey;
    private FrameKey activeKey;
    private List<RtDeviceTlasBuilder.Instance> activeInstances = List.of();
    private boolean pendingUpdate;
    private boolean closed;
    private long fullBuilds;
    private long updates;
    private long bufferReallocations;

    VulkanFramePrimitiveResources(VulkanDeviceRuntime device, RtStallTelemetrySink stalls) {
        this.device = Objects.requireNonNull(device, "device");
        this.stalls = Objects.requireNonNull(stalls, "stalls");
        RtGpuBuffer createdBuffer = null;
        RtDeviceTlasBuilder.PersistentBuildLane createdLane = null;
        try {
            createdBuffer = createInstanceBuffer(MINIMUM_BUFFER_BYTES);
            createdBuffer.writeInts(new int[VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS]);
            createdLane = RtDeviceTlasBuilder.openPersistentLane(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment()
            );
        } catch (RuntimeException | Error failure) {
            closeSuppressing(failure, createdLane);
            closeSuppressing(failure, createdBuffer);
            throw failure;
        }
        instanceBuffer = createdBuffer;
        buildLane = createdLane;
    }

    synchronized long requiredBufferGrowthBytes(FramePrimitiveBatch batch) {
        requireOpen();
        long required = requiredBytes(Objects.requireNonNull(batch, "batch").size());
        return Math.max(0L, required - instanceBuffer.sizeBytes());
    }

    synchronized Prepared prepare(
            FramePrimitiveBatch batch,
            long sceneRevision,
            VulkanSceneRuntime scene
    ) {
        requireOpen();
        FramePrimitiveBatch checkedBatch = Objects.requireNonNull(batch, "batch");
        VulkanSceneRuntime checkedScene = Objects.requireNonNull(scene, "scene");
        if (sceneRevision < 0L) throw new IllegalArgumentException("sceneRevision must not be negative");

        if (checkedBatch.isEmpty()) {
            if (pendingBuild != null) {
                throw new IllegalStateException("prior frame primitive submission transaction is still open");
            }
            return new Prepared(
                    checkedScene.requireActiveTlas(sceneRevision),
                    instanceBuffer,
                    0,
                    false,
                    null
            );
        }

        FrameKey key = new FrameKey(sceneRevision, checkedBatch);
        if (pendingBuild != null) {
            throw new IllegalStateException("frame primitive submission transaction is still open");
        }
        if (key.equals(activeKey)) {
            return new Prepared(activeTlas, instanceBuffer, checkedBatch.size(), true, null);
        }

        VulkanSceneAcceleration.FrameInstances frame = checkedScene.frameInstances(
                checkedBatch, sceneRevision
        );
        int[] packedWords = frame.packedWords();
        ensureBufferCapacity(packedWords.length);
        instanceBuffer.writeInts(packedWords);

        List<RtDeviceTlasBuilder.Instance> nextInstances = frame.tlasInstances();
        /* A frame-local TLAS borrows BLAS addresses from exactly one scene generation. Instance
         * count compatibility is sufficient only inside that generation; crossing a revision may
         * leave the update source referring to BLAS resources already eligible for retirement. */
        boolean update = activeTlas != null
                && activeKey != null
                && activeKey.sceneRevision() == sceneRevision
                && activeInstances.size() == nextInstances.size();
        RtTlasBuildPlan preparedBuild;
        if (update) {
            int[] dirtySlots = dirtySlots(activeInstances, nextInstances);
            RtAccelerationStructure destination = reusableTlas;
            /*
             * Preparation owns the detached destination even when allocation or command
             * recording later fails. Clear the slot field before crossing that ownership
             * boundary so close/retry can never retain a stale, already-closed TLAS reference.
             */
            reusableTlas = null;
            try {
                preparedBuild = buildLane.prepareUpdate(
                        activeTlas, destination, nextInstances, dirtySlots
                );
            } catch (RuntimeException | Error failure) {
                closeSuppressing(failure, destination);
                throw failure;
            }
        } else {
            preparedBuild = buildLane.prepareBuild(nextInstances);
        }
        pendingBuild = preparedBuild;
        pendingUpdate = update;
        pendingKey = key;
        pendingInstances = nextInstances;
        return new Prepared(
                preparedBuild.accelerationStructure(),
                instanceBuffer,
                checkedBatch.size(),
                true,
                preparedBuild
        );
    }

    synchronized void commit(Prepared prepared) {
        requireOpen();
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        RtTlasBuildPlan build = checked.buildPlan();
        if (build == null) return;
        requirePending(build);

        RtAccelerationStructure next = build.commit();
        pendingBuild = null;
        RtAccelerationStructure retiredActive = null;
        RtAccelerationStructure retiredReusable = null;
        if (pendingUpdate) {
            retiredReusable = reusableTlas;
            reusableTlas = activeTlas;
            updates++;
        } else {
            retiredActive = activeTlas;
            retiredReusable = reusableTlas;
            reusableTlas = null;
            fullBuilds++;
        }
        activeTlas = next;
        activeInstances = List.copyOf(pendingInstances);
        activeKey = pendingKey;
        pendingKey = null;
        pendingInstances = List.of();
        pendingUpdate = false;
        closeReplacing(retiredReusable);
        closeReplacing(retiredActive);
    }

    synchronized void abort(Prepared prepared) {
        requireOpen();
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        RtTlasBuildPlan build = checked.buildPlan();
        if (build == null) return;
        requirePending(build);
        pendingBuild = null;
        pendingKey = null;
        pendingInstances = List.of();
        pendingUpdate = false;
        build.close();
    }

    private void requirePending(RtTlasBuildPlan build) {
        if (pendingBuild != build) {
            throw new IllegalStateException("frame primitive plan does not belong to this slot transaction");
        }
    }

    private List<RtDeviceTlasBuilder.Instance> pendingInstances = List.of();

    private void ensureBufferCapacity(int requiredWords) {
        long required = Math.max(MINIMUM_BUFFER_BYTES, Math.multiplyExact((long) requiredWords, Integer.BYTES));
        if (required <= instanceBuffer.sizeBytes()) return;
        long capacity = MINIMUM_BUFFER_BYTES;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2L);
        RtGpuBuffer replacement = createInstanceBuffer(capacity);
        RtGpuBuffer previous = instanceBuffer;
        instanceBuffer = replacement;
        bufferReallocations++;
        previous.close();
    }

    private RtGpuBuffer createInstanceBuffer(long bytes) {
        return RtGpuBuffer.createHostVisibleUploadBuffer(
                device.device(),
                device.allocator(),
                bytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                stalls
        );
    }

    private static long requiredBytes(int primitiveCount) {
        if (primitiveCount < 0 || primitiveCount > FramePrimitiveBatch.MAX_PRIMITIVES) {
            throw new IllegalArgumentException("primitiveCount is outside the public batch contract");
        }
        return Math.max(
                MINIMUM_BUFFER_BYTES,
                Math.multiplyExact(
                        Math.multiplyExact((long) primitiveCount, VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS),
                        Integer.BYTES
                )
        );
    }

    private static int[] dirtySlots(
            List<RtDeviceTlasBuilder.Instance> previous,
            List<RtDeviceTlasBuilder.Instance> next
    ) {
        if (previous.size() != next.size()) {
            throw new IllegalArgumentException("TLAS update instance counts must match");
        }
        int[] dirty = new int[next.size()];
        int count = 0;
        for (int index = 0; index < next.size(); index++) {
            if (!previous.get(index).equals(next.get(index))) dirty[count++] = index;
        }
        return Arrays.copyOf(dirty, count);
    }

    synchronized Snapshot snapshot() {
        requireOpen();
        return new Snapshot(
                activeKey == null ? -1L : activeKey.sceneRevision(),
                activeKey == null ? 0 : activeKey.batch().size(),
                pendingBuild != null,
                fullBuilds,
                updates,
                bufferReallocations,
                instanceBuffer.sizeBytes()
        );
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame primitive resources are closed");
    }

    private static void closeReplacing(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("failed to replace frame primitive resource", failure);
        }
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(failure, pendingBuild);
        pendingBuild = null;
        failure = closeCollecting(failure, buildLane);
        failure = closeCollecting(failure, reusableTlas);
        reusableTlas = null;
        failure = closeCollecting(failure, activeTlas);
        activeTlas = null;
        failure = closeCollecting(failure, instanceBuffer);
        instanceBuffer = null;
        if (failure != null) throw failure;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close frame primitive resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    record Prepared(
            RtAccelerationStructure tlas,
            RtGpuBuffer instanceBuffer,
            int primitiveCount,
            boolean frameLocalTlas,
            RtTlasBuildPlan buildPlan
    ) {
        Prepared {
            tlas = Objects.requireNonNull(tlas, "tlas");
            instanceBuffer = Objects.requireNonNull(instanceBuffer, "instanceBuffer");
            if (primitiveCount < 0) throw new IllegalArgumentException("primitiveCount must not be negative");
            if (frameLocalTlas != (primitiveCount > 0)) {
                throw new IllegalArgumentException("frame-local TLAS metadata is inconsistent");
            }
            if (buildPlan != null && (!frameLocalTlas || buildPlan.accelerationStructure() != tlas)) {
                throw new IllegalArgumentException("frame primitive build plan targets a different TLAS");
            }
        }
    }

    record Snapshot(
            long sceneRevision,
            int primitiveCount,
            boolean pending,
            long fullBuilds,
            long updates,
            long bufferReallocations,
            long bufferCapacityBytes
    ) {
        Snapshot {
            if (sceneRevision < -1L || primitiveCount < 0 || fullBuilds < 0L || updates < 0L
                    || bufferReallocations < 0L || bufferCapacityBytes < MINIMUM_BUFFER_BYTES) {
                throw new IllegalArgumentException("frame primitive snapshot is invalid");
            }
        }
    }

    private record FrameKey(long sceneRevision, FramePrimitiveBatch batch) {
        private FrameKey {
            if (sceneRevision < 0L) throw new IllegalArgumentException("sceneRevision must not be negative");
            batch = Objects.requireNonNull(batch, "batch");
        }
    }
}
