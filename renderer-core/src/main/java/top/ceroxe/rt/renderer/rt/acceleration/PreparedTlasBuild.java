package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Owns a prepared TLAS command's transient resources and destination transfer state.
 */
final class PreparedTlasBuild implements AutoCloseable {
    private final RtGpuBuffer instanceBuffer;
    private final RtGpuBuffer scratchBuffer;
    private final RtAccelerationStructure accelerationStructure;
    private final long scratchAddress;
    private final List<RtAccelerationStructure.TlasInstance> instances;
    private final RtAccelerationStructure sourceTlas;
    private final boolean update;
    private final boolean recycledDestination;
    private final RtAccelerationStructure.PersistentTlasBuildInputs persistentInputs;
    private final int[] dirtyInstanceSlots;
    private final boolean fullInstanceUpload;
    private boolean accelerationStructureReleased;
    private boolean closed;

    PreparedTlasBuild(
            RtGpuBuffer instanceBuffer,
            RtGpuBuffer scratchBuffer,
            RtAccelerationStructure accelerationStructure,
            long scratchAddress,
            List<RtAccelerationStructure.TlasInstance> instances,
            RtAccelerationStructure sourceTlas,
            boolean update,
            boolean recycledDestination,
            RtAccelerationStructure.PersistentTlasBuildInputs persistentInputs,
            int[] dirtyInstanceSlots,
            boolean fullInstanceUpload
    ) {
        this.instanceBuffer = Objects.requireNonNull(instanceBuffer, "instanceBuffer");
        this.scratchBuffer = Objects.requireNonNull(scratchBuffer, "scratchBuffer");
        this.accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
        if (scratchAddress == 0L) {
            throw new IllegalArgumentException("scratchAddress must not be null");
        }
        this.scratchAddress = scratchAddress;
        this.instances = RtTlasInstanceEncoder.freeze(instances);
        this.sourceTlas = sourceTlas;
        this.update = update;
        this.recycledDestination = recycledDestination;
        this.persistentInputs = persistentInputs;
        this.dirtyInstanceSlots = Arrays.copyOf(
                Objects.requireNonNull(dirtyInstanceSlots, "dirtyInstanceSlots"),
                dirtyInstanceSlots.length
        );
        this.fullInstanceUpload = fullInstanceUpload;
        if (this.instances.isEmpty()) {
            throw new IllegalArgumentException("prepared TLAS build must contain instances");
        }
        RtTlasInstanceEncoder.validateDirtySlots(this.dirtyInstanceSlots, this.instances.size());
    }

    static VkAccelerationStructureGeometryKHR.Buffer instanceGeometry(
            MemoryStack stack,
            RtGpuBuffer instanceBuffer
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(instanceBuffer, "instanceBuffer");
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .geometry(data -> data.instances(instances -> instances
                        .sType$Default()
                        .arrayOfPointers(false)
                        .data(address -> address.deviceAddress(instanceBuffer.deviceAddress()))))
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
        return geometry;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
            return failure;
        } catch (Exception ex) {
            RuntimeException wrapped = ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("failed to close prepared TLAS build resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    void record(VkCommandBuffer commandBuffer, MemoryStack stack) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        try (MemoryStack buildStack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = instanceGeometry(buildStack, instanceBuffer);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuffer =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, buildStack);
            buildInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                    .mode(update
                            ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                            : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometry.remaining())
                    .pGeometries(geometry)
                    .srcAccelerationStructure(sourceTlas == null ? 0L : sourceTlas.handle())
                    .dstAccelerationStructure(accelerationStructure.handle())
                    .scratchData(scratch -> scratch.deviceAddress(scratchAddress));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, buildStack);
            rangeInfo.get(0)
                    .primitiveCount(instances.size())
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            PointerBuffer rangeInfoPointers = buildStack.pointers(rangeInfo.get(0).address());
            if (persistentInputs == null) {
                recordTransientBuild(commandBuffer, stack, buildInfoBuffer, rangeInfoPointers);
            } else {
                recordPersistentBuild(commandBuffer, stack, buildInfoBuffer, rangeInfoPointers);
            }
        }
    }

    int instanceCount() {
        return instances.size();
    }

    boolean update() {
        return update;
    }

    long sourceHandle() {
        return sourceTlas == null ? 0L : sourceTlas.handle();
    }

    long instanceBufferBytes() {
        return instanceBuffer.sizeBytes();
    }

    long scratchBufferBytes() {
        return scratchBuffer.sizeBytes();
    }

    boolean recycledDestination() {
        return recycledDestination;
    }

    RtAccelerationStructure releaseAccelerationStructure() {
        requireOpen();
        if (accelerationStructureReleased) {
            throw new IllegalStateException("TLAS acceleration structure was already released");
        }
        accelerationStructureReleased = true;
        return accelerationStructure;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        if (persistentInputs == null) {
            failure = closeCollecting(failure, scratchBuffer);
            failure = closeCollecting(failure, instanceBuffer);
        }
        if (!accelerationStructureReleased) {
            failure = closeCollecting(failure, accelerationStructure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void recordTransientBuild(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo,
            PointerBuffer rangeInfoPointers
    ) {
        long uploadBytes = RtAccelerationStructureBuildSupport.checkedMultiply(
                instances.size(), VkAccelerationStructureInstanceKHR.SIZEOF
        );
        ByteBuffer instanceBytes = MemoryUtil.memCalloc(
                RtAccelerationStructureBuildSupport.checkedByteBufferSize(uploadBytes, "TLAS instance upload")
        );
        try {
            VkAccelerationStructureInstanceKHR.Buffer encoded =
                    new VkAccelerationStructureInstanceKHR.Buffer(instanceBytes);
            for (int index = 0; index < instances.size(); index++) {
                RtTlasInstanceEncoder.write(encoded.get(index), instances.get(index));
            }
            RtAccelerationStructureCommandRecorder.recordByteUpload(
                    commandBuffer,
                    instanceBuffer.buffer(),
                    instanceBytes,
                    VkAccelerationStructureInstanceKHR.SIZEOF
            );
        } finally {
            MemoryUtil.memFree(instanceBytes);
        }
        RtAccelerationStructureCommandRecorder.recordInputUploadBarrier(commandBuffer, stack);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                commandBuffer, buildInfo, rangeInfoPointers
        );
        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, stack);
    }

    private void recordPersistentBuild(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo,
            PointerBuffer rangeInfoPointers
    ) {
        boolean uploaded = persistentInputs.recordInstanceUpload(
                commandBuffer,
                instances,
                dirtyInstanceSlots,
                fullInstanceUpload
        );
        if (uploaded) {
            RtAccelerationStructureCommandRecorder.recordInputUploadBarrier(commandBuffer, stack);
        }
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                commandBuffer, buildInfo, rangeInfoPointers
        );
        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, stack);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("prepared TLAS build is already closed");
        }
    }
}
