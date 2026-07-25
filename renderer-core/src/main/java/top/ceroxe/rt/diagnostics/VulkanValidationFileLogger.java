package top.ceroxe.rt.diagnostics;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional Vulkan validation callback that persists structured JSONL diagnostics.
 *
 * <p>The callback never performs file I/O. Validation messages are copied into a
 * bounded queue and a single writer thread owns the file, so enabling diagnostics
 * cannot make a validation callback wait on disk or on another renderer thread.</p>
 */
public final class VulkanValidationFileLogger implements AutoCloseable {
    /**
     * Enables Vulkan validation logging when set to {@code true}.
     */
    public static final String ENABLED_PROPERTY = "top.ceroxe.rt.validation.enabled";
    /**
     * Overrides the absolute or working-directory-relative JSONL output path.
     */
    public static final String LOG_PATH_PROPERTY = "top.ceroxe.rt.validation.logPath";
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final int QUEUE_CAPACITY = 2048;
    private static final int MAX_MESSAGE_CHARS = 64 * 1024;
    private static final int MAX_OBJECTS_PER_MESSAGE = 64;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS")
            .withZone(ZoneOffset.UTC);

    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedMessages = new AtomicLong();
    private final AtomicLong reportedDroppedMessages = new AtomicLong();
    private final AtomicLong writtenMessages = new AtomicLong();
    private final AtomicReference<WriteFailure> writeFailure = new AtomicReference<>();
    private final BufferedWriter writer;
    private final Path logPath;
    private final Thread writerThread;
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private volatile boolean closed;
    private long messengerHandle;

    private VulkanValidationFileLogger(Path logPath) throws IOException {
        this.logPath = logPath;
        Path parent = logPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(
                logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
        writer.write("{\"event\":\"opened\",\"time\":\""
                + jsonEscape(Instant.now().toString())
                + "\",\"path\":\""
                + jsonEscape(logPath.toString())
                + "\"}\n");
        writer.flush();
        this.callback = new VkDebugUtilsMessengerCallbackEXT() {
            @Override
            public int invoke(int messageSeverity, int messageType, long callbackDataAddress, long userData) {
                return enqueue(messageSeverity, messageType, callbackDataAddress);
            }
        };
        this.writerThread = new Thread(this::drainMessages, "rtrenderer-vulkan-validation-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Opens the logger only when explicitly requested. A missing layer or
     * extension is reported as a hard setup error instead of silently disabling
     * a requested validation run.
     *
     * @return an owned logger, or {@code null} when validation is disabled
     * @throws IllegalStateException if validation was requested but cannot be initialized
     */
    public static VulkanValidationFileLogger openIfEnabled() {
        return openIfEnabled(false);
    }

    /**
     * Enables validation from an immutable renderer configuration without mutating JVM globals.
     *
     * @param explicitlyEnabled whether the renderer configuration explicitly enables validation
     * @return an owned logger, or {@code null} when neither configuration source enables validation
     * @throws IllegalStateException if validation is enabled but the layer, extension, or log is unavailable
     */
    public static VulkanValidationFileLogger openIfEnabled(boolean explicitlyEnabled) {
        if (!explicitlyEnabled && !Boolean.getBoolean(ENABLED_PROPERTY)) {
            return null;
        }
        requireAvailable(VALIDATION_LAYER, EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        try {
            return new VulkanValidationFileLogger(resolveLogPath());
        } catch (IOException ex) {
            throw new IllegalStateException("unable to open Vulkan validation log", ex);
        }
    }

    private static Path resolveLogPath() {
        String configured = System.getProperty(LOG_PATH_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path base = Path.of(System.getProperty("user.home", "."), ".rtrenderer", "logs");
        return base.resolve("vulkan-validation-" + FILE_TIMESTAMP.format(Instant.now()) + ".jsonl");
    }

    private static void requireAvailable(String layerName, String extensionName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer layerCount = stack.ints(0);
            checkVk(VK10.vkEnumerateInstanceLayerProperties(layerCount, null), "vkEnumerateInstanceLayerProperties.count");
            VkLayerProperties.Buffer layers = VkLayerProperties.malloc(layerCount.get(0), stack);
            checkVk(VK10.vkEnumerateInstanceLayerProperties(layerCount, layers), "vkEnumerateInstanceLayerProperties.values");
            boolean layerFound = false;
            for (int index = 0; index < layers.capacity(); index++) {
                if (layerName.equals(layers.get(index).layerNameString())) {
                    layerFound = true;
                    break;
                }
            }
            if (!layerFound) {
                throw new IllegalStateException("requested Vulkan validation layer is unavailable: " + layerName);
            }

            IntBuffer extensionCount = stack.ints(0);
            checkVk(VK10.vkEnumerateInstanceExtensionProperties((String) null, extensionCount, null),
                    "vkEnumerateInstanceExtensionProperties.count");
            VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            checkVk(VK10.vkEnumerateInstanceExtensionProperties((String) null, extensionCount, extensions),
                    "vkEnumerateInstanceExtensionProperties.values");
            for (int index = 0; index < extensions.capacity(); index++) {
                if (extensionName.equals(extensions.get(index).extensionNameString())) {
                    return;
                }
            }
            throw new IllegalStateException("requested Vulkan instance extension is unavailable: " + extensionName);
        }
    }

    private static void checkVk(int result, String operation) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, operation);
    }

    private static String jsonLine(
            int messageSeverity,
            int messageType,
            int messageIdNumber,
            String messageIdName,
            String message,
            String objectsJson
    ) {
        return "{\"event\":\"validation\",\"time\":\""
                + jsonEscape(Instant.now().toString())
                + "\",\"severity\":\""
                + severityName(messageSeverity)
                + "\",\"severityBits\":"
                + messageSeverity
                + ",\"type\":\""
                + typeName(messageType)
                + "\",\"typeBits\":"
                + messageType
                + ",\"messageIdNumber\":"
                + messageIdNumber
                + ",\"messageIdName\":\""
                + jsonEscape(messageIdName)
                + "\",\"message\":\""
                + jsonEscape(message)
                + "\",\"objects\":"
                + objectsJson
                + "}";
    }

    private static String severityName(int severity) {
        if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            return "ERROR";
        }
        if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            return "WARNING";
        }
        if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            return "INFO";
        }
        return "VERBOSE";
    }

    private static String typeName(int type) {
        StringBuilder names = new StringBuilder();
        appendTypeName(names, type, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT, "GENERAL");
        appendTypeName(names, type, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT, "VALIDATION");
        appendTypeName(names, type, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT, "PERFORMANCE");
        return names.isEmpty() ? "UNKNOWN" : names.toString();
    }

    private static void appendTypeName(StringBuilder names, int type, int bit, String name) {
        if ((type & bit) == 0) {
            return;
        }
        if (!names.isEmpty()) {
            names.append('|');
        }
        names.append(name);
    }

    private static String objectsJson(VkDebugUtilsObjectNameInfoEXT.Buffer objects) {
        if (objects == null || objects.capacity() == 0) {
            return "[]";
        }
        int objectCount = Math.min(objects.capacity(), MAX_OBJECTS_PER_MESSAGE);
        StringBuilder result = new StringBuilder(objectCount * 80).append('[');
        for (int index = 0; index < objectCount; index++) {
            VkDebugUtilsObjectNameInfoEXT object = objects.get(index);
            if (index > 0) {
                result.append(',');
            }
            result.append("{\"type\":")
                    .append(object.objectType())
                    .append(",\"handle\":\"0x")
                    .append(Long.toUnsignedString(object.objectHandle(), 16))
                    .append("\",\"name\":\"")
                    .append(jsonEscape(object.pObjectNameString()))
                    .append("\"}");
        }
        return result.append(']').toString();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...[truncated]";
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Adds the validation layer, debug-utils extension and creation callback to
     * the caller's instance create info. All native structures are stack-owned
     * and are valid for the enclosing vkCreateInstance call.
     *
     * @param stack      open stack frame that must outlive the subsequent {@code vkCreateInstance} call
     * @param createInfo mutable instance create info to chain with validation configuration
     */
    public void configureInstanceCreateInfo(MemoryStack stack, VkInstanceCreateInfo createInfo) {
        PointerBuffer layerNames = stack.mallocPointer(1)
                .put(stack.UTF8(VALIDATION_LAYER))
                .flip();
        PointerBuffer existingExtensions = createInfo.ppEnabledExtensionNames();
        int existingCount = existingExtensions == null ? 0 : existingExtensions.remaining();
        PointerBuffer extensionNames = stack.mallocPointer(existingCount + 1);
        if (existingExtensions != null) {
            for (int index = existingExtensions.position(); index < existingExtensions.limit(); index++) {
                extensionNames.put(existingExtensions.get(index));
            }
        }
        extensionNames.put(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)).flip();
        VkDebugUtilsMessengerCreateInfoEXT callbackInfo = callbackCreateInfo(stack);
        callbackInfo.pNext(createInfo.pNext());
        createInfo
                .ppEnabledLayerNames(layerNames)
                .ppEnabledExtensionNames(extensionNames)
                .pNext(callbackInfo.address());
    }

    /**
     * Creates the post-instance messenger and keeps its native callback alive.
     *
     * @param stack    open stack frame used for temporary Vulkan structures
     * @param instance live Vulkan instance that owns the new messenger
     * @throws IllegalStateException if messenger creation fails
     */
    public void createMessenger(MemoryStack stack, VkInstance instance) {
        VkDebugUtilsMessengerCreateInfoEXT callbackInfo = callbackCreateInfo(stack);
        LongBuffer handle = stack.longs(VK10.VK_NULL_HANDLE);
        int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, callbackInfo, null, handle);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDebugUtilsMessengerEXT failed with result " + result);
        }
        messengerHandle = handle.get(0);
    }

    /**
     * Destroys the messenger before the owning Vulkan instance is destroyed. Repeated calls are safe.
     *
     * @param instance live Vulkan instance that was passed to {@link #createMessenger(MemoryStack, VkInstance)}
     */
    public void destroyMessenger(VkInstance instance) {
        long handle = messengerHandle;
        messengerHandle = VK10.VK_NULL_HANDLE;
        if (handle != VK10.VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, handle, null);
        }
    }

    /**
     * Returns the durable validation output destination.
     *
     * @return normalized output path owned by this logger
     */
    public Path logPath() {
        return logPath;
    }

    /**
     * Returns diagnostic-delivery health without exposing the writer or queue.
     * A validation callback may never wait for this information, but shutdown
     * and higher-level diagnostics need to distinguish a quiet validator from a
     * full queue or an unavailable output file.
     *
     * @return immutable queue, delivery, and first-failure state
     */
    public HealthSnapshot healthSnapshot() {
        WriteFailure failure = writeFailure.get();
        return new HealthSnapshot(
                messages.size(),
                droppedMessages.get(),
                writtenMessages.get(),
                failure == null ? "" : failure.operation(),
                failure == null ? "" : failure.throwableClass(),
                failure == null ? "" : failure.message()
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        writerThread.interrupt();
        try {
            writerThread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            writer.write("{\"event\":\"closed\",\"droppedMessages\":"
                    + droppedMessages.get() + "}\n");
            writer.flush();
            writer.close();
        } catch (IOException ex) {
            recordWriteFailure("close", ex);
        }
        HealthSnapshot health = healthSnapshot();
        if (health.hasDeliveryFailure() || health.droppedMessages() > 0L) {
            top.ceroxe.rt.renderer.RendererLog.warn(
                    "Vulkan validation diagnostic delivery incomplete: path={}, {}",
                    logPath,
                    health.asLogFragment()
            );
        }
    }

    private int enqueue(int messageSeverity, int messageType, long callbackDataAddress) {
        VkDebugUtilsMessengerCallbackDataEXT callbackData =
                VkDebugUtilsMessengerCallbackDataEXT.create(callbackDataAddress);
        String line = jsonLine(
                messageSeverity,
                messageType,
                callbackData.messageIdNumber(),
                callbackData.pMessageIdNameString(),
                truncate(callbackData.pMessageString(), MAX_MESSAGE_CHARS),
                objectsJson(callbackData.pObjects())
        );
        if (!messages.offer(line)) {
            droppedMessages.incrementAndGet();
        }
        return VK10.VK_FALSE;
    }

    private void drainMessages() {
        try {
            while (!closed || !messages.isEmpty()) {
                String line = messages.poll(100L, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                reportDropsIfRecovered();
                writer.write(line);
                writer.newLine();
                writtenMessages.incrementAndGet();
            }
            writer.flush();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            drainRemainingMessages();
        } catch (IOException ex) {
            /* The callback remains non-fatal when the diagnostic disk path fails. */
            recordWriteFailure("drain", ex);
        }
    }

    private void drainRemainingMessages() {
        String line;
        try {
            while ((line = messages.poll()) != null) {
                reportDropsIfRecovered();
                writer.write(line);
                writer.newLine();
                writtenMessages.incrementAndGet();
            }
            writer.flush();
        } catch (IOException ex) {
            /* Best-effort shutdown only. */
            recordWriteFailure("drainRemaining", ex);
        }
    }

    /**
     * Writer-thread-only recovery marker: never performed from the callback.
     */
    private void reportDropsIfRecovered() throws IOException {
        long dropped = droppedMessages.get();
        if (dropped <= reportedDroppedMessages.get()) {
            return;
        }
        writer.write("{\"event\":\"dropSummary\",\"droppedMessages\":" + dropped + "}");
        writer.newLine();
        writtenMessages.incrementAndGet();
        reportedDroppedMessages.set(dropped);
    }

    private void recordWriteFailure(String operation, IOException failure) {
        writeFailure.compareAndSet(
                null,
                new WriteFailure(operation, failure.getClass().getName(), failure.getMessage() == null ? "" : failure.getMessage())
        );
    }

    private VkDebugUtilsMessengerCreateInfoEXT callbackCreateInfo(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT
                )
                .messageType(
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
                )
                .pfnUserCallback(callback);
    }

    private record WriteFailure(String operation, String throwableClass, String message) {
    }

    /**
     * Immutable delivery state for post-mortem diagnostics and shutdown reporting.
     *
     * @param queuedMessages   messages currently awaiting durable delivery
     * @param droppedMessages  messages dropped because the bounded delivery queue was full
     * @param writtenMessages  messages successfully written to the diagnostic log
     * @param failureOperation operation that caused the first delivery failure, or an empty string
     * @param failureClass     exception class for the first delivery failure, or an empty string
     * @param failureMessage   exception message for the first delivery failure, or an empty string
     */
    public record HealthSnapshot(
            int queuedMessages,
            long droppedMessages,
            long writtenMessages,
            String failureOperation,
            String failureClass,
            String failureMessage
    ) {
        /**
         * Validates counters and normalizes nullable failure text.
         *
         * @throws IllegalArgumentException if any counter is negative
         */
        public HealthSnapshot {
            if (queuedMessages < 0 || droppedMessages < 0L || writtenMessages < 0L) {
                throw new IllegalArgumentException("validation diagnostic counts must not be negative");
            }
            failureOperation = failureOperation == null ? "" : failureOperation;
            failureClass = failureClass == null ? "" : failureClass;
            failureMessage = failureMessage == null ? "" : failureMessage;
        }

        /**
         * Reports whether asynchronous delivery failed.
         *
         * @return whether the writer recorded an I/O delivery failure
         */
        public boolean hasDeliveryFailure() {
            return !failureOperation.isEmpty();
        }

        /**
         * Formats queue and delivery health for renderer diagnostics.
         *
         * @return stable single-line diagnostic fragment containing queue and delivery health
         */
        public String asLogFragment() {
            return "validationDelivery{queued=" + queuedMessages
                    + ", dropped=" + droppedMessages
                    + ", written=" + writtenMessages
                    + ", failure=" + (hasDeliveryFailure()
                    ? failureOperation + ':' + failureClass + ':' + failureMessage
                    : "none") + '}';
        }
    }
}
