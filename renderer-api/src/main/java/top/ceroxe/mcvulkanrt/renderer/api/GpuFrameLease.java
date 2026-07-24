package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive consumer lease for one completed renderer-owned external image.
 *
 * <p>The renderer retains image and allocation ownership. Exported operating-system handles are
 * owned by {@link ExportedNativeHandle} until {@link ExportedNativeHandle#markImported()} records
 * a successful import. An import failure must leave the handle unmarked so closing it releases the
 * exporter-owned handle. This prevents both leak-on-failure and double-close-after-import.</p>
 *
 * <p>After submitting consumer GPU work, the host must call {@link #release(ConsumerCompletion)}.
 * The renderer cannot reuse the image slot until that completion is observed. Closing an imported
 * but unreleased lease is a contract error; implementations must never guess that presentation
 * implies GPU completion.</p>
 *
 * <p>Calls that mutate a lease ({@link #release(ConsumerCompletion)} and {@link #close()}) must be
 * serialized by the consumer. Handle import must complete before either operation begins.</p>
 */
public interface GpuFrameLease extends AutoCloseable {
    FrameDescriptor descriptor();

    ExportedNativeHandle memoryHandle();

    Optional<AcquireSignal> acquireSignal();

    void release(ConsumerCompletion completion);

    boolean released();

    boolean closed();

    @Override
    void close();

    /** Stateful owner of one exported native handle. */
    interface ExportedNativeHandle extends AutoCloseable {
        long value();

        int vulkanHandleType();

        ImportDisposition importDisposition();

        HandleState state();

        /**
         * Records a successful import and applies the handle type's ownership rule exactly once.
         *
         * @return {@code true} only for the first successful state transition
         */
        boolean markImported();

        @Override
        void close();
    }

    record FrameDescriptor(
            long frameSequence,
            long renderedSceneRevision,
            int width,
            int height,
            int vulkanFormat,
            int imageType,
            int imageTiling,
            int imageUsageFlags,
            int imageCreateFlags,
            int imageLayout,
            int mipLevels,
            int arrayLayers,
            int sampleCount,
            int sharingMode,
            int producerQueueFamilyIndex,
            long allocationSize,
            long allocationOffset,
            boolean dedicatedAllocation
    ) {
        public FrameDescriptor {
            if (frameSequence < 0L || renderedSceneRevision < 0L) {
                throw new IllegalArgumentException("frame descriptor revisions must not be negative");
            }
            if (width <= 0 || height <= 0 || vulkanFormat <= 0 || imageType < 0 || imageTiling < 0
                    || imageUsageFlags == 0 || imageLayout < 0 || mipLevels <= 0 || arrayLayers <= 0
                    || sampleCount <= 0 || sharingMode < 0 || producerQueueFamilyIndex < 0
                    || allocationSize <= 0L || allocationOffset < 0L) {
                throw new IllegalArgumentException("external image descriptor contains invalid Vulkan metadata");
            }
            if (allocationOffset >= allocationSize) {
                throw new IllegalArgumentException("allocationOffset must be inside the exported allocation");
            }
        }
    }

    record AcquireSignal(ExportedNativeHandle handle, SemaphoreKind kind, long timelineValue) {
        public AcquireSignal {
            handle = Objects.requireNonNull(handle, "handle");
            kind = Objects.requireNonNull(kind, "kind");
            if (kind == SemaphoreKind.BINARY && timelineValue != 0L) {
                throw new IllegalArgumentException("binary semaphore signal must use timelineValue 0");
            }
            if (kind == SemaphoreKind.TIMELINE && timelineValue <= 0L) {
                throw new IllegalArgumentException("timeline semaphore signal requires a positive value");
            }
        }
    }

    sealed interface ConsumerCompletion permits CpuCompleted, ExternalSemaphoreSignal {
    }

    /** Host asserts it has already observed consumer GPU completion before returning the lease. */
    record CpuCompleted() implements ConsumerCompletion {
    }

    /**
     * External semaphore signaled by the consumer when image access is complete.
     *
     * <p>The caller retains handle ownership if {@link #release(ConsumerCompletion)} throws. On
     * success, {@code importDisposition} defines whether Vulkan import consumed the handle.</p>
     */
    record ExternalSemaphoreSignal(
            long handle,
            int vulkanHandleType,
            SemaphoreKind kind,
            long timelineValue,
            ImportDisposition importDisposition
    ) implements ConsumerCompletion {
        public ExternalSemaphoreSignal {
            if (handle == 0L || vulkanHandleType == 0) {
                throw new IllegalArgumentException("consumer completion requires a native semaphore handle");
            }
            kind = Objects.requireNonNull(kind, "kind");
            importDisposition = Objects.requireNonNull(importDisposition, "importDisposition");
            if (kind == SemaphoreKind.BINARY && timelineValue != 0L) {
                throw new IllegalArgumentException("binary semaphore completion must use timelineValue 0");
            }
            if (kind == SemaphoreKind.TIMELINE && timelineValue <= 0L) {
                throw new IllegalArgumentException("timeline semaphore completion requires a positive value");
            }
        }
    }

    enum SemaphoreKind {
        BINARY,
        TIMELINE
    }

    enum ImportDisposition {
        /** A successful Vulkan import consumes the operating-system handle. */
        IMPORT_CONSUMES_HANDLE,
        /** The handle remains caller-owned and must be closed after a successful import. */
        CALLER_RETAINS_HANDLE
    }

    enum HandleState {
        EXPORTED,
        IMPORTED,
        CLOSED
    }
}
