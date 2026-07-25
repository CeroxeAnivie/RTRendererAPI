package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Best-effort, driver-identity-scoped persistence for one Vulkan RT pipeline cache.
 */
final class RtPipelineCache implements AutoCloseable {
    private static final int HEADER_BYTES = Integer.BYTES * 4 + VK10.VK_UUID_SIZE;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final String CACHE_DIRECTORY_PROPERTY = "top.ceroxe.rt.pipelineCache.directory";

    private final VkDevice device;
    private final long handle;
    private final Path cacheFile;
    private final DeviceIdentity identity;
    private boolean closed;

    private RtPipelineCache(VkDevice device, long handle, Path cacheFile, DeviceIdentity identity) {
        this.device = Objects.requireNonNull(device, "device");
        if (handle == VK10.VK_NULL_HANDLE) {
            throw new IllegalArgumentException("pipeline cache handle must not be null");
        }
        this.handle = handle;
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    static RtPipelineCache open(VkDevice device, VkPhysicalDevice physicalDevice, String namespace) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        String cacheNamespace = requireNamespace(namespace);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DeviceIdentity identity = identity(stack, physicalDevice);
            Path cacheFile = cacheDirectory().resolve(cacheNamespace + '-' + identity.fileToken() + ".bin");
            byte[] initialData = readValidCache(cacheFile, identity);
            long handle = create(stack, device, initialData);
            return new RtPipelineCache(device, handle, cacheFile, identity);
        }
    }

    private static long create(MemoryStack stack, VkDevice device, byte[] initialData) {
        long handle = createOnce(stack, device, initialData);
        if (handle != VK10.VK_NULL_HANDLE || initialData.length == 0) {
            return handle;
        }
        return createOnce(stack, device, new byte[0]);
    }

    private static long createOnce(MemoryStack stack, VkDevice device, byte[] initialData) {
        VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
        ByteBuffer nativeInitialData = null;
        try {
            if (initialData.length > 0) {
                // Driver cache blobs may be tens of MiB. MemoryStack is intentionally small and
                // must remain reserved for bounded Vulkan structs, never file-sized payloads.
                nativeInitialData = MemoryUtil.memAlloc(initialData.length);
                createInfo.pInitialData(nativeInitialData.put(initialData).flip());
            }
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            int result = VK10.vkCreatePipelineCache(device, createInfo, null, output);
            if (result != VK10.VK_SUCCESS) {
                if (initialData.length > 0 && result == VK10.VK_ERROR_INITIALIZATION_FAILED) {
                    return VK10.VK_NULL_HANDLE;
                }
                VulkanFailures.check(result, "vkCreatePipelineCache");
            }
            return output.get(0);
        } finally {
            MemoryUtil.memFree(nativeInitialData);
        }
    }

    private static byte[] readValidCache(Path cacheFile, DeviceIdentity identity) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return new byte[0];
            }
            long size = Files.size(cacheFile);
            if (size < HEADER_BYTES || size > MAX_CACHE_BYTES) {
                return new byte[0];
            }
            byte[] data = Files.readAllBytes(cacheFile);
            return headerMatches(data, identity) ? data : new byte[0];
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    static boolean headerMatches(byte[] data, DeviceIdentity identity) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(identity, "identity");
        if (data.length < HEADER_BYTES) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = header.getInt();
        int version = header.getInt();
        int vendorId = header.getInt();
        int deviceId = header.getInt();
        byte[] uuid = new byte[VK10.VK_UUID_SIZE];
        header.get(uuid);
        return headerSize >= HEADER_BYTES && headerSize <= data.length
                && version == VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE
                && vendorId == identity.vendorId()
                && deviceId == identity.deviceId()
                && java.util.Arrays.equals(uuid, identity.uuid());
    }

    private static DeviceIdentity identity(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
        ByteBuffer source = properties.pipelineCacheUUID();
        byte[] uuid = new byte[VK10.VK_UUID_SIZE];
        source.get(0, uuid);
        return new DeviceIdentity(properties.vendorID(), properties.deviceID(), uuid);
    }

    private static Path cacheDirectory() {
        String override = System.getProperty(CACHE_DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "RTRenderer", "pipeline-cache").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".rtrenderer", "pipeline-cache")
                .toAbsolutePath().normalize();
    }

    private static String requireNamespace(String value) {
        if (value == null || !value.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("pipeline cache namespace is invalid");
        }
        return value;
    }

    long handle() {
        if (closed) {
            throw new IllegalStateException("pipeline cache is closed");
        }
        return handle;
    }

    /**
     * Persists compatible cache bytes and destroys the native pipeline-cache handle.
     *
     * @throws RuntimeException if cache extraction or native destruction fails
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            byte[] data = queryData();
            if (data != null && data.length >= HEADER_BYTES && data.length <= MAX_CACHE_BYTES
                    && headerMatches(data, identity)) {
                persistAtomically(data);
            }
        } finally {
            VK10.vkDestroyPipelineCache(device, handle, null);
        }
    }

    private byte[] queryData() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer size = stack.mallocPointer(1);
                int countResult = VK10.vkGetPipelineCacheData(device, handle, size, null);
                if (countResult != VK10.VK_SUCCESS) {
                    return null;
                }
                long requested = size.get(0);
                if (requested < HEADER_BYTES || requested > MAX_CACHE_BYTES || requested > Integer.MAX_VALUE) {
                    return null;
                }
                ByteBuffer nativeData = MemoryUtil.memAlloc((int) requested);
                try {
                    int result = VK10.vkGetPipelineCacheData(device, handle, size, nativeData);
                    if (result == VK10.VK_INCOMPLETE) {
                        continue;
                    }
                    if (result != VK10.VK_SUCCESS) {
                        return null;
                    }
                    int actual = Math.toIntExact(size.get(0));
                    if (actual < HEADER_BYTES || actual > nativeData.capacity()) {
                        return null;
                    }
                    byte[] copy = new byte[actual];
                    nativeData.get(0, copy);
                    return copy;
                } finally {
                    MemoryUtil.memFree(nativeData);
                }
            }
        }
        return null;
    }

    private void persistAtomically(byte[] data) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheFile.getParent());
            temporary = cacheFile.resolveSibling(cacheFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(temporary, data);
            try {
                Files.move(
                        temporary,
                        cacheFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException ignored) {
            // Pipeline cache persistence is an optimization and must never fail renderer teardown.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup after an already ignored cache I/O failure.
                }
            }
        }
    }

    record DeviceIdentity(int vendorId, int deviceId, byte[] uuid) {
        DeviceIdentity {
            uuid = Objects.requireNonNull(uuid, "uuid").clone();
            if (uuid.length != VK10.VK_UUID_SIZE) {
                throw new IllegalArgumentException("pipeline cache UUID has the wrong length");
            }
        }

        /**
         * Returns an isolated copy of the Vulkan pipeline-cache UUID.
         *
         * @return defensive UUID copy
         */
        @Override
        public byte[] uuid() {
            return uuid.clone();
        }

        String fileToken() {
            return Integer.toUnsignedString(vendorId, 16) + '-'
                    + Integer.toUnsignedString(deviceId, 16) + '-'
                    + HexFormat.of().formatHex(uuid);
        }
    }
}
