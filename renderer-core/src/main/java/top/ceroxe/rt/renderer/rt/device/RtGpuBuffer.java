package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Objects;

/**
 * VMA backed Vulkan buffer with an optional device address.
 *
 * <p>This wrapper owns both {@code VkBuffer} and {@code VmaAllocation}. It is
 * deliberately small because buffer role policy belongs to higher-level RT
 * resources: vertex staging, BLAS scratch, TLAS instances, and SBT records will
 * all need different usage flags but the same destroy discipline.</p>
 */
public final class RtGpuBuffer implements AutoCloseable {
    /*
     * VMA allocation and teardown routinely cross a couple of milliseconds on
     * a busy Windows driver.  Preserve those samples for opt-in diagnosis, but
     * reserve WARN for a stall that can actually disturb a visible frame.
     */
    private static final long MEMORY_STAGE_DIAGNOSTIC_NANOS = 2_000_000L;
    private final VkDevice device;
    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final long sizeBytes;
    private final int usageFlags;
    private final long deviceAddress;
    private final boolean hostVisible;
    private final RtStallTelemetrySink stallTelemetry;
    private boolean closed;

    private RtGpuBuffer(
            VkDevice device,
            long allocator,
            long buffer,
            long allocation,
            long sizeBytes,
            int usageFlags,
            long deviceAddress,
            boolean hostVisible,
            RtStallTelemetrySink stallTelemetry
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.allocator = allocator;
        this.buffer = buffer;
        this.allocation = allocation;
        this.sizeBytes = sizeBytes;
        this.usageFlags = usageFlags;
        this.deviceAddress = deviceAddress;
        this.hostVisible = hostVisible;
        this.stallTelemetry = Objects.requireNonNull(stallTelemetry, "stallTelemetry");
    }

    /**
     * Creates device-local storage with a shader device address and no-op stall telemetry.
     *
     * @param device     logical device that owns the buffer
     * @param allocator  live VMA allocator associated with {@code device}
     * @param sizeBytes  positive allocation size
     * @param usageFlags Vulkan usage flags in addition to shader-device-address usage
     * @return independently owned buffer that the caller must close after GPU use completes
     */
    public static RtGpuBuffer createDeviceAddressBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags
    ) {
        return createDeviceAddressBuffer(device, allocator, sizeBytes, usageFlags, RtStallTelemetrySink.NOOP);
    }

    /**
     * Creates device-local storage whose address may be embedded in RT build descriptors.
     * The returned owner must be closed only after all GPU users have completed.
     *
     * @param device         logical device that owns the buffer
     * @param allocator      live VMA allocator associated with {@code device}
     * @param sizeBytes      positive allocation size
     * @param usageFlags     Vulkan usage flags in addition to shader-device-address usage
     * @param stallTelemetry sink for slow native operations
     * @return independently owned buffer that the caller must close after GPU use completes
     */
    public static RtGpuBuffer createDeviceAddressBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags,
            RtStallTelemetrySink stallTelemetry
    ) {
        return createBuffer(
                device,
                allocator,
                sizeBytes,
                usageFlags | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
                0,
                true,
                false,
                stallTelemetry
        );
    }

    /**
     * Creates random-access host-visible storage with no-op stall telemetry.
     *
     * @param device     logical device that owns the buffer
     * @param allocator  live VMA allocator associated with {@code device}
     * @param sizeBytes  positive allocation size
     * @param usageFlags Vulkan usage flags
     * @return independently owned buffer that the caller must close
     */
    public static RtGpuBuffer createHostVisibleBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags
    ) {
        return createHostVisibleBuffer(device, allocator, sizeBytes, usageFlags, RtStallTelemetrySink.NOOP);
    }

    /**
     * Creates mapped random-access storage; callers must externally synchronize GPU access.
     *
     * @param device         logical device that owns the buffer
     * @param allocator      live VMA allocator associated with {@code device}
     * @param sizeBytes      positive allocation size
     * @param usageFlags     Vulkan usage flags
     * @param stallTelemetry sink for slow native operations
     * @return independently owned buffer that the caller must close
     */
    public static RtGpuBuffer createHostVisibleBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags,
            RtStallTelemetrySink stallTelemetry
    ) {
        return createBuffer(
                device,
                allocator,
                sizeBytes,
                usageFlags,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
                false,
                true,
                stallTelemetry
        );
    }

    /**
     * Creates sequential-write host-visible upload storage with no-op stall telemetry.
     *
     * @param device     logical device that owns the buffer
     * @param allocator  live VMA allocator associated with {@code device}
     * @param sizeBytes  positive allocation size
     * @param usageFlags Vulkan usage flags
     * @return independently owned upload buffer that the caller must close
     */
    public static RtGpuBuffer createHostVisibleUploadBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags
    ) {
        return createHostVisibleUploadBuffer(device, allocator, sizeBytes, usageFlags,
                RtStallTelemetrySink.NOOP);
    }

    /**
     * Creates sequential-write host-visible upload storage and records slow host operations.
     *
     * @param device         logical device that owns the buffer
     * @param allocator      live VMA allocator associated with {@code device}
     * @param sizeBytes      positive allocation size
     * @param usageFlags     Vulkan usage flags
     * @param stallTelemetry sink for slow native operations
     * @return independently owned upload buffer that the caller must close
     */
    public static RtGpuBuffer createHostVisibleUploadBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags,
            RtStallTelemetrySink stallTelemetry
    ) {
        return createBuffer(
                device,
                allocator,
                sizeBytes,
                usageFlags,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
                false,
                true,
                stallTelemetry
        );
    }

    private static RtGpuBuffer createBuffer(
            VkDevice device,
            long allocator,
            long sizeBytes,
            int usageFlags,
            int memoryUsage,
            int allocationFlags,
            boolean queryDeviceAddress,
            boolean hostVisible,
            RtStallTelemetrySink stallTelemetry
    ) {
        Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (sizeBytes <= 0L) {
            throw new IllegalArgumentException("buffer size must be positive");
        }

        long createStartNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(usageFlags)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(memoryUsage)
                    .flags(allocationFlags | Vma.VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT);

            LongBuffer bufferHandle = stack.longs(0L);
            PointerBuffer allocationHandle = stack.mallocPointer(1);
            checkVk(
                    Vma.vmaCreateBuffer(allocator, bufferCreateInfo, allocationCreateInfo, bufferHandle, allocationHandle, null),
                    "vmaCreateBuffer"
            );

            long buffer = bufferHandle.get(0);
            long allocation = allocationHandle.get(0);
            try {
                long deviceAddress = queryDeviceAddress ? queryDeviceAddress(stack, device, buffer) : 0L;
                if (queryDeviceAddress && deviceAddress == 0L) {
                    throw new IllegalStateException("vkGetBufferDeviceAddress returned null for device-address buffer");
                }
                RtGpuBuffer result = new RtGpuBuffer(
                        device,
                        allocator,
                        buffer,
                        allocation,
                        sizeBytes,
                        usageFlags,
                        deviceAddress,
                        hostVisible,
                        stallTelemetry
                );
                stallTelemetry.gpuMemoryHostStall(
                        "createBuffer:usage=0x" + Integer.toHexString(usageFlags),
                        sizeBytes,
                        System.nanoTime() - createStartNanos
                );
                return result;
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                Vma.vmaDestroyBuffer(allocator, buffer, allocation);
                throw ex;
            }
        }
    }

    private static long queryDeviceAddress(MemoryStack stack, VkDevice device, long buffer) {
        VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                .sType$Default()
                .buffer(buffer);
        return VK12.vkGetBufferDeviceAddress(device, addressInfo);
    }

    private static long checkedMultiply(long left, long right) {
        long result = left * right;
        if (left != 0L && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static void checkVk(int result, String stage) {
        VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            default -> Integer.toString(result);
        };
    }

    /**
     * Writes native-order integers from byte offset zero.
     *
     * @param values non-empty values copied into this buffer
     */
    public synchronized void writeInts(int[] values) {
        Objects.requireNonNull(values, "values");
        writeIntChunksAt(0L, List.of(values));
    }

    /**
     * Writes an opaque staging payload without coercing packed ABI bytes through integers.
     *
     * @param values non-empty payload copied into this buffer
     */
    public synchronized void writeBytes(byte[] values) {
        Objects.requireNonNull(values, "values");
        if (!hostVisible) {
            throw new IllegalStateException("buffer was not created as host-visible");
        }
        if (closed) {
            throw new IllegalStateException("buffer is already closed");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.length > sizeBytes) {
            throw new IllegalArgumentException(
                    "write exceeds buffer size: requested=" + values.length + ", size=" + sizeBytes
            );
        }

        long writeStartNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(allocator, allocation, mapped), "vmaMapMemory.writeBytes");
            try {
                MemoryUtil.memByteBuffer(mapped.get(0), values.length).put(values);
                Vma.vmaFlushAllocation(allocator, allocation, 0L, values.length);
            } finally {
                Vma.vmaUnmapMemory(allocator, allocation);
            }
        }
        logSlowMemoryStage("writeBytes", values.length, System.nanoTime() - writeStartNanos);
    }

    /**
     * Writes consecutive native-order chunks from byte offset zero.
     *
     * @param chunks non-null chunks forming a non-empty aggregate payload
     */
    public synchronized void writeIntChunks(List<int[]> chunks) {
        writeIntChunksAt(0L, chunks);
    }

    /**
     * Writes buffer views without materializing another Java array.
     *
     * @param chunks source views whose positions are preserved
     */
    public synchronized void writeIntBuffers(List<IntBuffer> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        if (!hostVisible) {
            throw new IllegalStateException("buffer was not created as host-visible");
        }
        if (closed) {
            throw new IllegalStateException("buffer is already closed");
        }
        long totalInts = 0L;
        for (IntBuffer chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            totalInts += chunk.remaining();
            if (totalInts > Integer.MAX_VALUE) {
                throw new IllegalStateException("buffer write is too large for a Java NIO view: "
                        + checkedMultiply(totalInts, Integer.BYTES));
            }
        }
        long requestedBytes = checkedMultiply(totalInts, Integer.BYTES);
        if (requestedBytes <= 0L) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (requestedBytes > sizeBytes || requestedBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "write exceeds buffer size: requested=" + requestedBytes + ", size=" + sizeBytes
            );
        }

        long writeStartNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(allocator, allocation, mapped), "vmaMapMemory.writeIntBuffers");
            try {
                IntBuffer target = MemoryUtil.memByteBuffer(mapped.get(0), (int) requestedBytes)
                        .order(ByteOrder.nativeOrder())
                        .asIntBuffer();
                for (IntBuffer chunk : chunks) {
                    target.put(chunk.duplicate());
                }
                Vma.vmaFlushAllocation(allocator, allocation, 0L, requestedBytes);
            } finally {
                Vma.vmaUnmapMemory(allocator, allocation);
            }
        }
        logSlowMemoryStage("writeIntBuffers", requestedBytes, System.nanoTime() - writeStartNanos);
    }

    /**
     * Writes derived records directly into mapped memory.
     *
     * @param writers writers whose declared counts must be positive and exact
     */
    public synchronized void writeIntWriters(List<? extends IntBufferWriter> writers) {
        Objects.requireNonNull(writers, "writers");
        if (!hostVisible) {
            throw new IllegalStateException("buffer was not created as host-visible");
        }
        if (closed) {
            throw new IllegalStateException("buffer is already closed");
        }
        long totalInts = 0L;
        for (IntBufferWriter writer : writers) {
            IntBufferWriter checked = Objects.requireNonNull(writer, "writer");
            if (checked.intCount() <= 0) {
                throw new IllegalArgumentException("writer intCount must be positive");
            }
            totalInts = Math.addExact(totalInts, checked.intCount());
        }
        long requestedBytes = checkedMultiply(totalInts, Integer.BYTES);
        if (requestedBytes <= 0L || requestedBytes > sizeBytes || requestedBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "write exceeds buffer size: requested=" + requestedBytes + ", size=" + sizeBytes
            );
        }

        long writeStartNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(allocator, allocation, mapped), "vmaMapMemory.writeIntWriters");
            try {
                IntBuffer target = MemoryUtil.memByteBuffer(mapped.get(0), (int) requestedBytes)
                        .order(ByteOrder.nativeOrder())
                        .asIntBuffer();
                for (IntBufferWriter writer : writers) {
                    int start = target.position();
                    writer.writeTo(target);
                    if (target.position() - start != writer.intCount()) {
                        throw new IllegalStateException("int writer emitted a different number of values than declared");
                    }
                }
                Vma.vmaFlushAllocation(allocator, allocation, 0L, requestedBytes);
            } finally {
                Vma.vmaUnmapMemory(allocator, allocation);
            }
        }
        logSlowMemoryStage("writeIntWriters", requestedBytes, System.nanoTime() - writeStartNanos);
    }

    /**
     * Writes native-order integers at an aligned byte offset.
     *
     * @param byteOffset non-negative four-byte-aligned destination offset
     * @param values     non-empty values copied into this buffer
     */
    public synchronized void writeIntsAt(long byteOffset, int[] values) {
        Objects.requireNonNull(values, "values");
        writeIntChunksAt(byteOffset, List.of(values));
    }

    /**
     * Writes consecutive native-order chunks at an aligned byte offset.
     *
     * @param byteOffset non-negative, four-byte-aligned destination offset
     * @param chunks     non-empty aggregate payload
     */
    public synchronized void writeIntChunksAt(long byteOffset, List<int[]> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        if (!hostVisible) {
            throw new IllegalStateException("buffer was not created as host-visible");
        }
        if (closed) {
            throw new IllegalStateException("buffer is already closed");
        }
        if (byteOffset < 0L || byteOffset % Integer.BYTES != 0L) {
            throw new IllegalArgumentException("byteOffset must be non-negative and int-aligned");
        }
        long totalInts = 0L;
        for (int[] chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            totalInts += chunk.length;
            if (totalInts > Integer.MAX_VALUE) {
                throw new IllegalStateException("buffer write is too large for a Java NIO view: "
                        + checkedMultiply(totalInts, Integer.BYTES));
            }
        }
        long requestedBytes = checkedMultiply(totalInts, Integer.BYTES);
        if (requestedBytes <= 0L) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (byteOffset > sizeBytes || requestedBytes > sizeBytes - byteOffset) {
            throw new IllegalArgumentException("write exceeds buffer size: requested=" + requestedBytes + ", size=" + sizeBytes);
        }
        if (requestedBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("buffer write is too large for a Java NIO view: " + requestedBytes);
        }

        long writeStartNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(allocator, allocation, mapped), "vmaMapMemory.writeInts");
            try {
                ByteBuffer view = MemoryUtil.memByteBuffer(mapped.get(0) + byteOffset, (int) requestedBytes)
                        .order(ByteOrder.nativeOrder());
                IntBuffer ints = view.asIntBuffer();
                for (int[] chunk : chunks) {
                    ints.put(chunk);
                }
                Vma.vmaFlushAllocation(allocator, allocation, byteOffset, requestedBytes);
            } finally {
                Vma.vmaUnmapMemory(allocator, allocation);
            }
        }
        logSlowMemoryStage("writeIntChunks", requestedBytes, System.nanoTime() - writeStartNanos);
    }

    /**
     * Reads the complete host-visible allocation.
     *
     * @return independent CPU copy of all buffer bytes
     */
    public synchronized byte[] readBytes() {
        return readBytes(sizeBytes);
    }

    /**
     * Reads a prefix after invalidating non-coherent allocation state.
     *
     * @param requestedBytes positive byte count no greater than the allocation size
     * @return independent CPU byte array
     */
    public synchronized byte[] readBytes(long requestedBytes) {
        if (!hostVisible) {
            throw new IllegalStateException("buffer was not created as host-visible");
        }
        if (closed) {
            throw new IllegalStateException("buffer is already closed");
        }
        if (requestedBytes <= 0L) {
            throw new IllegalArgumentException("requestedBytes must be positive");
        }
        if (requestedBytes > sizeBytes) {
            throw new IllegalArgumentException("requestedBytes exceeds buffer size: requested=" + requestedBytes + ", size=" + sizeBytes);
        }
        if (requestedBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("buffer is too large to read into a Java byte array: " + requestedBytes);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(allocator, allocation, mapped), "vmaMapMemory");
            try {
                Vma.vmaInvalidateAllocation(allocator, allocation, 0L, requestedBytes);
                ByteBuffer view = MemoryUtil.memByteBuffer(mapped.get(0), (int) requestedBytes);
                byte[] copy = new byte[(int) requestedBytes];
                view.get(0, copy);
                return copy;
            } finally {
                Vma.vmaUnmapMemory(allocator, allocation);
            }
        }
    }

    /**
     * Returns the logical allocation size.
     *
     * @return size in bytes
     */
    public long sizeBytes() {
        return sizeBytes;
    }

    /**
     * Returns the optional shader device address.
     *
     * @return address, or zero when device-address support was not requested
     */
    public long deviceAddress() {
        return deviceAddress;
    }

    /**
     * Returns the native buffer without transferring ownership.
     *
     * @return owned {@code VkBuffer}, valid until {@link #close()}
     */
    public long buffer() {
        return buffer;
    }

    /**
     * Builds a bounded native-resource summary.
     *
     * @param name diagnostic label
     * @return summary without mapped contents
     */
    public String summary(String name) {
        return name
                + "{buffer=0x" + Long.toHexString(buffer)
                + ", allocation=0x" + Long.toHexString(allocation)
                + ", size=" + sizeBytes
                + ", usage=0x" + Integer.toHexString(usageFlags)
                + ", deviceAddress=0x" + Long.toHexString(deviceAddress)
                + "}";
    }

    /**
     * Destroys the owned Vulkan buffer and VMA allocation.
     *
     * <p>The caller must ensure that no GPU submission still references this buffer.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        long destroyStartNanos = System.nanoTime();
        Vma.vmaDestroyBuffer(allocator, buffer, allocation);
        logSlowMemoryStage(
                "destroyBuffer:usage=0x" + Integer.toHexString(usageFlags),
                sizeBytes,
                System.nanoTime() - destroyStartNanos
        );
    }

    private void logSlowMemoryStage(String stage, long bytes, long elapsedNanos) {
        if (elapsedNanos < MEMORY_STAGE_DIAGNOSTIC_NANOS) {
            return;
        }
        stallTelemetry.gpuMemoryHostStall(stage, bytes, elapsedNanos);
    }

    /**
     * Emits one fixed-size record into a mapped native-order integer view.
     */
    public interface IntBufferWriter {
        /**
         * Declares the fixed record size.
         *
         * @return exact positive number of integers that {@link #writeTo(IntBuffer)} will append
         */
        int intCount();

        /**
         * Appends one record to a mapped buffer view.
         *
         * @param target writable mapped view whose position must advance by {@link #intCount()}
         */
        void writeTo(IntBuffer target);
    }
}
