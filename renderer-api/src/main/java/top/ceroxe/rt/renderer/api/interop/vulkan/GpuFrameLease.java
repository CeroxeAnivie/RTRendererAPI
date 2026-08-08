package top.ceroxe.rt.renderer.api.interop.vulkan;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static void requireNonZeroBits(int value, String name) {
        if (value == 0) throw new IllegalArgumentException(name + " must contain at least one bit");
    }

    /**
     * Returns immutable metadata required to import and access the leased image.
     *
     * @return external-image descriptor
     */
    FrameDescriptor descriptor();

    /**
     * Returns the stateful owner of the exported memory handle.
     *
     * @return memory handle owner associated with this lease
     */
    ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle();

    /**
     * Returns the GPU signal that makes producer writes visible to the consumer.
     *
     * @return acquire signal, or empty when producer completion was observed on the CPU
     */
    Optional<AcquireSignal> acquireSignal();

    /**
     * Declares which completion mechanisms this concrete lease accepts.
     *
     * @return immutable completion capability set for this lease
     */
    ConsumerCompletionCapabilities consumerCompletionCapabilities();

    /**
     * Returns the slot after consumer access and any required external queue-family release.
     *
     * @param completion evidence that consumer access has completed
     * @throws UnsupportedOperationException when {@code completion} is not advertised by
     *                                       {@link #consumerCompletionCapabilities()}
     */
    void release(ConsumerCompletion completion);

    /**
     * Returns the authoritative consumer-ownership state.
     *
     * <p>The mutually exclusive enum is the only lifecycle observation surface, so callers cannot
     * construct or observe contradictory released/closed boolean combinations.</p>
     *
     * @return current lease lifecycle state
     */
    LeaseState state();

    @Override
    void close();

    /**
     * External semaphore synchronization model.
     */
    enum SemaphoreKind {
        /**
         * Single-use binary signal.
         */
        BINARY,
        /**
         * Monotonically increasing timeline value.
         */
        TIMELINE
    }

    /**
     * Ownership rule applied by a successful Vulkan handle import.
     */
    enum ImportDisposition {
        /**
         * A successful Vulkan import consumes the operating-system handle.
         */
        IMPORT_CONSUMES_HANDLE,
        /**
         * The handle remains caller-owned and must be closed after a successful import.
         */
        CALLER_RETAINS_HANDLE
    }

    /**
     * Ownership state of an exported native handle.
     */
    enum HandleState {
        /**
         * Exporter still owns an unimported operating-system handle.
         */
        EXPORTED,
        /**
         * A successful import applied the declared ownership disposition.
         */
        IMPORTED,
        /**
         * Exporter-owned handle resources were closed.
         */
        CLOSED
    }

    /**
     * Mutually exclusive lifecycle states for consumer ownership of a frame slot.
     */
    enum LeaseState {
        /**
         * The consumer owns the lease and has not published completion.
         */
        ACTIVE,
        /**
         * Consumer completion was published; native handle ownership may still need closing.
         */
        RELEASED,
        /**
         * Consumer-side ownership and exporter-owned native handles were closed.
         */
        CLOSED
    }

    /**
     * Stateful, externally serialized owner of one exported native handle.
     *
     * @param <T> memory or semaphore handle-type domain
     */
    interface ExportedNativeHandle<T extends VulkanExternalHandleType> extends AutoCloseable {
        /**
         * Returns the operating-system handle value.
         *
         * @return non-zero handle value while exported
         */
        long value();

        /**
         * Returns the strongly typed Vulkan external handle type.
         *
         * @return Vulkan external handle type used for import
         */
        T handleType();

        /**
         * Returns the import ownership rule.
         *
         * @return ownership rule applied after successful import
         */
        ImportDisposition importDisposition();

        /**
         * Returns the ownership state.
         *
         * @return current handle ownership state
         */
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

    /**
     * Evidence accepted by the producer before it reuses the image slot.
     */
    sealed interface ConsumerCompletion permits CpuCompleted, ExternalSemaphoreSignal {
    }

    /**
     * Marker for Vulkan external-handle type domains that must never be mixed positionally.
     */
    sealed interface VulkanExternalHandleType
            permits VulkanMemoryHandleType, VulkanSemaphoreHandleType {
        /**
         * Returns the Vulkan flag bits passed to the import operation.
         *
         * @return non-zero Vulkan external-handle type bits
         */
        int value();
    }

    /**
     * Complete immutable Vulkan metadata for one externally shared image.
     *
     * <p>The semantic builder deliberately replaces the former 18-argument constructor. Every
     * Vulkan property remains explicit while adjacent integer flags, counts, and allocation
     * values can no longer be exchanged positionally.</p>
     */
    final class FrameDescriptor {
        private final long resourceId;
        private final long frameSequence;
        private final long renderedSceneRevision;
        private final int width;
        private final int height;
        private final VulkanFormat format;
        private final VulkanImageType imageType;
        private final VulkanImageTiling imageTiling;
        private final VulkanImageUsage imageUsage;
        private final VulkanImageCreateFlags imageCreateFlags;
        private final VulkanImageLayout imageLayout;
        private final int mipLevels;
        private final int arrayLayers;
        private final VulkanSampleCount sampleCount;
        private final VulkanSharingMode sharingMode;
        private final VulkanQueueFamily producerQueueFamily;
        private final int memoryTypeIndex;
        private final long allocationSize;
        private final long allocationOffset;
        private final boolean dedicatedAllocation;

        private FrameDescriptor(Builder builder) {
            resourceId = builder.resourceId;
            frameSequence = builder.frameSequence;
            renderedSceneRevision = builder.renderedSceneRevision;
            width = builder.width;
            height = builder.height;
            format = requireSelected(builder.format, "format");
            imageType = requireSelected(builder.imageType, "imageType");
            imageTiling = requireSelected(builder.imageTiling, "imageTiling");
            imageUsage = requireSelected(builder.imageUsage, "imageUsage");
            imageCreateFlags = requireSelected(builder.imageCreateFlags, "imageCreateFlags");
            imageLayout = requireSelected(builder.imageLayout, "imageLayout");
            mipLevels = builder.mipLevels;
            arrayLayers = builder.arrayLayers;
            sampleCount = requireSelected(builder.sampleCount, "sampleCount");
            sharingMode = requireSelected(builder.sharingMode, "sharingMode");
            producerQueueFamily = requireSelected(builder.producerQueueFamily, "producerQueueFamily");
            memoryTypeIndex = builder.memoryTypeIndex;
            allocationSize = builder.allocationSize;
            allocationOffset = builder.allocationOffset;
            dedicatedAllocation = builder.dedicatedAllocation;
            if (resourceId <= 0L || frameSequence < 0L || renderedSceneRevision < 0L) {
                throw new IllegalArgumentException(
                        "frame resource identity must be positive and revisions must not be negative"
                );
            }
            if (width <= 0 || height <= 0 || mipLevels <= 0 || arrayLayers <= 0 || memoryTypeIndex < 0
                    || allocationSize <= 0L || allocationOffset < 0L) {
                throw new IllegalArgumentException("external image descriptor contains invalid dimensions or allocation metadata");
            }
            if (allocationOffset >= allocationSize) {
                throw new IllegalArgumentException("allocationOffset must be inside the exported allocation");
            }
        }

        /**
         * Starts an empty semantic builder for an external image descriptor.
         *
         * @return new single-thread-confined builder
         */
        public static Builder builder() {
            return new Builder();
        }

        private static <T> T requireSelected(T value, String name) {
            if (value == null) throw new IllegalStateException(name + " must be selected before build");
            return value;
        }

        /**
         * Starts an independent builder initialized from this descriptor.
         *
         * @return builder containing every current descriptor property
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Returns the stable identity of the exported image allocation.
         *
         * <p>The value remains unchanged while the producer reuses the same underlying external
         * image and changes whenever that image is replaced. Consumers may therefore cache one
         * imported image/memory pair by this identity instead of importing a new Win32 memory
         * handle every frame. The identity is scoped to the owning renderer lifetime.</p>
         *
         * @return positive renderer-lifetime resource identity
         */
        public long resourceId() {
            return resourceId;
        }

        /**
         * Returns the rendered frame sequence.
         *
         * @return non-negative frame sequence
         */
        public long frameSequence() {
            return frameSequence;
        }

        /**
         * Returns the scene revision used to render the frame.
         *
         * @return non-negative scene revision
         */
        public long renderedSceneRevision() {
            return renderedSceneRevision;
        }

        /**
         * Returns external image width.
         *
         * @return positive width in pixels
         */
        public int width() {
            return width;
        }

        /**
         * Returns external image height.
         *
         * @return positive height in pixels
         */
        public int height() {
            return height;
        }

        /**
         * Returns the Vulkan image format.
         *
         * @return strongly typed format
         */
        public VulkanFormat format() {
            return format;
        }

        /**
         * Returns the Vulkan image dimensionality.
         *
         * @return strongly typed image type
         */
        public VulkanImageType imageType() {
            return imageType;
        }

        /**
         * Returns the Vulkan image tiling.
         *
         * @return strongly typed tiling
         */
        public VulkanImageTiling imageTiling() {
            return imageTiling;
        }

        /**
         * Returns Vulkan image usage flags.
         *
         * @return non-zero strongly typed usage flags
         */
        public VulkanImageUsage imageUsage() {
            return imageUsage;
        }

        /**
         * Returns Vulkan image creation flags.
         *
         * @return strongly typed creation flags
         */
        public VulkanImageCreateFlags imageCreateFlags() {
            return imageCreateFlags;
        }

        /**
         * Returns the layout exported to the consumer.
         *
         * @return strongly typed image layout
         */
        public VulkanImageLayout imageLayout() {
            return imageLayout;
        }

        /**
         * Returns image mip-level count.
         *
         * @return positive count
         */
        public int mipLevels() {
            return mipLevels;
        }

        /**
         * Returns image array-layer count.
         *
         * @return positive count
         */
        public int arrayLayers() {
            return arrayLayers;
        }

        /**
         * Returns image sample count.
         *
         * @return valid single-bit sample count
         */
        public VulkanSampleCount sampleCount() {
            return sampleCount;
        }

        /**
         * Returns Vulkan image sharing mode.
         *
         * @return strongly typed sharing mode
         */
        public VulkanSharingMode sharingMode() {
            return sharingMode;
        }

        /**
         * Returns producer queue-family identity.
         *
         * @return strongly typed queue-family identity
         */
        public VulkanQueueFamily producerQueueFamily() {
            return producerQueueFamily;
        }

        /**
         * Returns the producer allocation's exact physical-device memory-type index.
         *
         * <p>OPAQUE_WIN32 handles do not expose this value through the Win32 handle-properties
         * query, so a same-GPU Vulkan consumer must reuse the producer-selected index.</p>
         *
         * @return non-negative Vulkan memory-type index
         */
        public int memoryTypeIndex() {
            return memoryTypeIndex;
        }

        /**
         * Returns exported allocation size.
         *
         * @return positive byte count
         */
        public long allocationSize() {
            return allocationSize;
        }

        /**
         * Returns the image offset inside the allocation.
         *
         * @return non-negative byte offset below {@link #allocationSize()}
         */
        public long allocationOffset() {
            return allocationOffset;
        }

        /**
         * Reports whether the image owns a dedicated allocation.
         *
         * @return dedicated-allocation state
         */
        public boolean dedicatedAllocation() {
            return dedicatedAllocation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FrameDescriptor descriptor)) return false;
            return resourceId == descriptor.resourceId
                    && frameSequence == descriptor.frameSequence
                    && renderedSceneRevision == descriptor.renderedSceneRevision
                    && width == descriptor.width
                    && height == descriptor.height
                    && mipLevels == descriptor.mipLevels
                    && arrayLayers == descriptor.arrayLayers
                    && memoryTypeIndex == descriptor.memoryTypeIndex
                    && allocationSize == descriptor.allocationSize
                    && allocationOffset == descriptor.allocationOffset
                    && dedicatedAllocation == descriptor.dedicatedAllocation
                    && format.equals(descriptor.format)
                    && imageType.equals(descriptor.imageType)
                    && imageTiling.equals(descriptor.imageTiling)
                    && imageUsage.equals(descriptor.imageUsage)
                    && imageCreateFlags.equals(descriptor.imageCreateFlags)
                    && imageLayout.equals(descriptor.imageLayout)
                    && sampleCount.equals(descriptor.sampleCount)
                    && sharingMode.equals(descriptor.sharingMode)
                    && producerQueueFamily.equals(descriptor.producerQueueFamily);
        }

        @Override
        public int hashCode() {
            return Objects.hash(resourceId, frameSequence, renderedSceneRevision, width, height, format, imageType,
                    imageTiling, imageUsage, imageCreateFlags, imageLayout, mipLevels, arrayLayers,
                    sampleCount, sharingMode, producerQueueFamily, memoryTypeIndex, allocationSize, allocationOffset,
                    dedicatedAllocation);
        }

        @Override
        public String toString() {
            return "FrameDescriptor[resourceId=" + resourceId
                    + ", frameSequence=" + frameSequence
                    + ", renderedSceneRevision=" + renderedSceneRevision
                    + ", width=" + width + ", height=" + height
                    + ", format=" + format + ", imageType=" + imageType
                    + ", imageTiling=" + imageTiling + ", imageUsage=" + imageUsage
                    + ", imageCreateFlags=" + imageCreateFlags + ", imageLayout=" + imageLayout
                    + ", mipLevels=" + mipLevels + ", arrayLayers=" + arrayLayers
                    + ", sampleCount=" + sampleCount + ", sharingMode=" + sharingMode
                    + ", producerQueueFamily=" + producerQueueFamily
                    + ", memoryTypeIndex=" + memoryTypeIndex
                    + ", allocationSize=" + allocationSize + ", allocationOffset=" + allocationOffset
                    + ", dedicatedAllocation=" + dedicatedAllocation + ']';
        }

        /**
         * Single-thread-confined semantic builder for one complete descriptor.
         */
        public static final class Builder {
            private long resourceId = -1L;
            private long frameSequence = -1L;
            private long renderedSceneRevision = -1L;
            private int width;
            private int height;
            private VulkanFormat format;
            private VulkanImageType imageType;
            private VulkanImageTiling imageTiling;
            private VulkanImageUsage imageUsage;
            private VulkanImageCreateFlags imageCreateFlags;
            private VulkanImageLayout imageLayout;
            private int mipLevels;
            private int arrayLayers;
            private VulkanSampleCount sampleCount;
            private VulkanSharingMode sharingMode;
            private VulkanQueueFamily producerQueueFamily;
            private int memoryTypeIndex = -1;
            private long allocationSize;
            private long allocationOffset;
            private boolean dedicatedAllocation;

            private Builder() {
            }

            private Builder(FrameDescriptor source) {
                resourceId = source.resourceId;
                frameSequence = source.frameSequence;
                renderedSceneRevision = source.renderedSceneRevision;
                width = source.width;
                height = source.height;
                format = source.format;
                imageType = source.imageType;
                imageTiling = source.imageTiling;
                imageUsage = source.imageUsage;
                imageCreateFlags = source.imageCreateFlags;
                imageLayout = source.imageLayout;
                mipLevels = source.mipLevels;
                arrayLayers = source.arrayLayers;
                sampleCount = source.sampleCount;
                sharingMode = source.sharingMode;
                producerQueueFamily = source.producerQueueFamily;
                memoryTypeIndex = source.memoryTypeIndex;
                allocationSize = source.allocationSize;
                allocationOffset = source.allocationOffset;
                dedicatedAllocation = source.dedicatedAllocation;
            }

            /**
             * Selects the stable renderer-lifetime identity of the external image allocation.
             *
             * @param value positive identity that changes when the producer replaces the image
             * @return this builder
             */
            public Builder resourceId(long value) {
                resourceId = value;
                return this;
            }

            /**
             * Selects rendered frame sequence.
             *
             * @param value non-negative sequence
             * @return this builder
             */
            public Builder frameSequence(long value) {
                frameSequence = value;
                return this;
            }

            /**
             * Selects rendered scene revision.
             *
             * @param value non-negative revision
             * @return this builder
             */
            public Builder renderedSceneRevision(long value) {
                renderedSceneRevision = value;
                return this;
            }

            /**
             * Selects external image extent.
             *
             * @param widthPixels  positive width
             * @param heightPixels positive height
             * @return this builder
             */
            public Builder extent(int widthPixels, int heightPixels) {
                width = widthPixels;
                height = heightPixels;
                return this;
            }

            /**
             * Selects Vulkan image format.
             *
             * @param value non-null format
             * @return this builder
             */
            public Builder format(VulkanFormat value) {
                format = Objects.requireNonNull(value, "format");
                return this;
            }

            /**
             * Selects Vulkan image type.
             *
             * @param value non-null image type
             * @return this builder
             */
            public Builder imageType(VulkanImageType value) {
                imageType = Objects.requireNonNull(value, "imageType");
                return this;
            }

            /**
             * Selects Vulkan image tiling.
             *
             * @param value non-null tiling
             * @return this builder
             */
            public Builder imageTiling(VulkanImageTiling value) {
                imageTiling = Objects.requireNonNull(value, "imageTiling");
                return this;
            }

            /**
             * Selects Vulkan image usage flags.
             *
             * @param value non-null, non-zero flags
             * @return this builder
             */
            public Builder imageUsage(VulkanImageUsage value) {
                imageUsage = Objects.requireNonNull(value, "imageUsage");
                return this;
            }

            /**
             * Selects Vulkan image creation flags.
             *
             * @param value non-null flags
             * @return this builder
             */
            public Builder imageCreateFlags(VulkanImageCreateFlags value) {
                imageCreateFlags = Objects.requireNonNull(value, "imageCreateFlags");
                return this;
            }

            /**
             * Selects the layout exported to the consumer.
             *
             * @param value non-null layout
             * @return this builder
             */
            public Builder imageLayout(VulkanImageLayout value) {
                imageLayout = Objects.requireNonNull(value, "imageLayout");
                return this;
            }

            /**
             * Selects image mip-level count.
             *
             * @param value positive count
             * @return this builder
             */
            public Builder mipLevels(int value) {
                mipLevels = value;
                return this;
            }

            /**
             * Selects image array-layer count.
             *
             * @param value positive count
             * @return this builder
             */
            public Builder arrayLayers(int value) {
                arrayLayers = value;
                return this;
            }

            /**
             * Selects image sample count.
             *
             * @param value non-null single-bit sample count
             * @return this builder
             */
            public Builder sampleCount(VulkanSampleCount value) {
                sampleCount = Objects.requireNonNull(value, "sampleCount");
                return this;
            }

            /**
             * Selects image sharing mode.
             *
             * @param value non-null sharing mode
             * @return this builder
             */
            public Builder sharingMode(VulkanSharingMode value) {
                sharingMode = Objects.requireNonNull(value, "sharingMode");
                return this;
            }

            /**
             * Selects producer queue-family identity.
             *
             * @param value non-null queue-family identity
             * @return this builder
             */
            public Builder producerQueueFamily(VulkanQueueFamily value) {
                producerQueueFamily = Objects.requireNonNull(value, "producerQueueFamily");
                return this;
            }

            /**
             * Selects the producer allocation's exact physical-device memory-type index.
             *
             * @param value non-negative Vulkan memory-type index
             * @return this builder
             */
            public Builder memoryTypeIndex(int value) {
                memoryTypeIndex = value;
                return this;
            }

            /**
             * Selects exported allocation size.
             *
             * @param value positive byte count
             * @return this builder
             */
            public Builder allocationSize(long value) {
                allocationSize = value;
                return this;
            }

            /**
             * Selects image offset inside the allocation.
             *
             * @param value non-negative byte offset
             * @return this builder
             */
            public Builder allocationOffset(long value) {
                allocationOffset = value;
                return this;
            }

            /**
             * Selects whether the image owns a dedicated allocation.
             *
             * @param value dedicated-allocation state
             * @return this builder
             */
            public Builder dedicatedAllocation(boolean value) {
                dedicatedAllocation = value;
                return this;
            }

            /**
             * Validates and returns an independent immutable external image descriptor.
             *
             * @return validated descriptor
             */
            public FrameDescriptor build() {
                return new FrameDescriptor(this);
            }
        }
    }

    /**
     * Producer semaphore signal that makes the external image available to the consumer.
     *
     * @param handle        stateful owner of the exported semaphore handle
     * @param kind          binary or timeline semaphore kind
     * @param timelineValue zero for binary semaphores or a positive timeline value
     */
    record AcquireSignal(
            ExportedNativeHandle<VulkanSemaphoreHandleType> handle,
            SemaphoreKind kind,
            long timelineValue
    ) {
        /**
         * Validates and creates an acquire signal.
         *
         * @param handle        stateful owner of the exported semaphore handle
         * @param kind          binary or timeline semaphore kind
         * @param timelineValue zero for binary semaphores or a positive timeline value
         */
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

    /**
     * Host asserts it has already observed consumer GPU completion before returning the lease.
     * If the image was acquired into another Vulkan queue family, the consumer must also have
     * released it to {@code VK_QUEUE_FAMILY_EXTERNAL} before publishing this completion.
     */
    record CpuCompleted() implements ConsumerCompletion {
    }

    /**
     * Explicit feature negotiation for consumer-to-producer completion.
     *
     * @param cpuCompleted           whether synchronous CPU-observed completion is accepted
     * @param externalSemaphoreKinds immutable accepted external semaphore kinds
     */
    record ConsumerCompletionCapabilities(
            boolean cpuCompleted,
            Set<SemaphoreKind> externalSemaphoreKinds
    ) {
        private static final ConsumerCompletionCapabilities CPU_ONLY =
                new ConsumerCompletionCapabilities(true, Set.of());

        /**
         * Validates and creates a completion capability set.
         *
         * @param cpuCompleted           whether synchronous CPU-observed completion is accepted
         * @param externalSemaphoreKinds immutable accepted external semaphore kinds
         */
        public ConsumerCompletionCapabilities {
            externalSemaphoreKinds = Set.copyOf(Objects.requireNonNull(
                    externalSemaphoreKinds, "externalSemaphoreKinds"));
            if (!cpuCompleted && externalSemaphoreKinds.isEmpty()) {
                throw new IllegalArgumentException("at least one consumer completion mechanism is required");
            }
        }

        /**
         * Returns the canonical CPU-only capability set.
         *
         * @return immutable CPU-only capabilities
         */
        public static ConsumerCompletionCapabilities cpuOnly() {
            return CPU_ONLY;
        }

        /**
         * Creates capabilities accepting CPU and binary semaphore completion.
         *
         * @return immutable CPU-and-binary capabilities
         */
        public static ConsumerCompletionCapabilities cpuAndBinarySemaphore() {
            return new ConsumerCompletionCapabilities(true, Set.of(SemaphoreKind.BINARY));
        }

        /**
         * Reports whether any external semaphore completion kind is accepted.
         *
         * @return {@code true} when at least one semaphore kind is supported
         */
        public boolean externalSemaphoreSignal() {
            return !externalSemaphoreKinds.isEmpty();
        }

        /**
         * Tests one external semaphore kind.
         *
         * @param kind non-null semaphore kind
         * @return {@code true} when {@code kind} is accepted
         */
        public boolean supports(SemaphoreKind kind) {
            return externalSemaphoreKinds.contains(Objects.requireNonNull(kind, "kind"));
        }
    }

    /**
     * External semaphore signaled by the consumer when image access is complete.
     *
     * <p>The caller retains handle ownership if {@link #release(ConsumerCompletion)} throws. On
     * success, {@code importDisposition} defines whether Vulkan import consumed the handle.</p>
     *
     * @param handle            non-zero native semaphore handle
     * @param handleType        Vulkan external semaphore handle type
     * @param kind              binary or timeline semaphore kind
     * @param timelineValue     zero for binary semaphores or a positive timeline value
     * @param importDisposition ownership rule applied after a successful import
     */
    record ExternalSemaphoreSignal(
            long handle,
            VulkanSemaphoreHandleType handleType,
            SemaphoreKind kind,
            long timelineValue,
            ImportDisposition importDisposition
    ) implements ConsumerCompletion {
        /**
         * Validates and creates an external semaphore completion signal.
         *
         * @param handle            non-zero native semaphore handle
         * @param handleType        Vulkan external semaphore handle type
         * @param kind              binary or timeline semaphore kind
         * @param timelineValue     zero for binary semaphores or a positive timeline value
         * @param importDisposition ownership rule applied after successful import
         */
        public ExternalSemaphoreSignal {
            if (handle == 0L) {
                throw new IllegalArgumentException("consumer completion requires a native semaphore handle");
            }
            handleType = Objects.requireNonNull(handleType, "handleType");
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

    /**
     * Strong type for Vulkan external-memory handle type bits.
     *
     * @param value non-zero Vulkan external-memory handle type bits
     */
    record VulkanMemoryHandleType(int value) implements VulkanExternalHandleType {
        /**
         * Validates Vulkan external-memory handle type bits.
         *
         * @param value non-zero handle type bits
         */
        public VulkanMemoryHandleType {
            requireNonZeroBits(value, "Vulkan memory handle type");
        }
    }

    /**
     * Strong type for Vulkan external-semaphore handle type bits.
     *
     * @param value non-zero Vulkan external-semaphore handle type bits
     */
    record VulkanSemaphoreHandleType(int value) implements VulkanExternalHandleType {
        /**
         * Validates Vulkan external-semaphore handle type bits.
         *
         * @param value non-zero handle type bits
         */
        public VulkanSemaphoreHandleType {
            requireNonZeroBits(value, "Vulkan semaphore handle type");
        }
    }

    /**
     * Strong type for a Vulkan image format.
     *
     * @param value positive Vulkan format value
     */
    record VulkanFormat(int value) {
        /**
         * Validates a Vulkan format value.
         *
         * @param value positive Vulkan format value
         */
        public VulkanFormat {
            requirePositive(value, "Vulkan format");
        }
    }

    /**
     * Strong type for a Vulkan image type.
     *
     * @param value non-negative Vulkan image type value
     */
    record VulkanImageType(int value) {
        /**
         * Validates a Vulkan image type.
         *
         * @param value non-negative Vulkan image type value
         */
        public VulkanImageType {
            requireNonNegative(value, "Vulkan image type");
        }
    }

    /**
     * Strong type for Vulkan image tiling.
     *
     * @param value non-negative Vulkan image tiling value
     */
    record VulkanImageTiling(int value) {
        /**
         * Validates Vulkan image tiling.
         *
         * @param value non-negative Vulkan image tiling value
         */
        public VulkanImageTiling {
            requireNonNegative(value, "Vulkan image tiling");
        }
    }

    /**
     * Strong type for Vulkan image usage flags.
     *
     * @param value non-zero Vulkan image usage bits
     */
    record VulkanImageUsage(int value) {
        /**
         * Validates Vulkan image usage flags.
         *
         * @param value non-zero usage bits
         */
        public VulkanImageUsage {
            requireNonZeroBits(value, "Vulkan image usage");
        }
    }

    /**
     * Strong type for Vulkan image creation flags; zero is the canonical no-flags value.
     *
     * @param value Vulkan image creation bits
     */
    record VulkanImageCreateFlags(int value) {
    }

    /**
     * Strong type for a Vulkan image layout.
     *
     * @param value non-negative Vulkan image layout value
     */
    record VulkanImageLayout(int value) {
        /**
         * Validates a Vulkan image layout.
         *
         * @param value non-negative Vulkan image layout value
         */
        public VulkanImageLayout {
            requireNonNegative(value, "Vulkan image layout");
        }
    }

    /**
     * Strong type for a single Vulkan sample-count flag.
     *
     * @param value one of the single-bit Vulkan sample-count flags through 64 samples
     */
    record VulkanSampleCount(int value) {
        /**
         * Validates a single Vulkan sample-count flag.
         *
         * @param value one positive sample-count bit through 64
         */
        public VulkanSampleCount {
            if (value <= 0 || value > 64 || Integer.bitCount(value) != 1) {
                throw new IllegalArgumentException("Vulkan sample count must be one bit in [1, 64]");
            }
        }
    }

    /**
     * Strong type for Vulkan image sharing mode.
     *
     * @param value non-negative Vulkan sharing-mode value
     */
    record VulkanSharingMode(int value) {
        /**
         * Validates Vulkan image sharing mode.
         *
         * @param value non-negative Vulkan sharing-mode value
         */
        public VulkanSharingMode {
            requireNonNegative(value, "Vulkan sharing mode");
        }
    }

    /**
     * Strong type for a concrete Vulkan queue-family index.
     *
     * @param value non-negative queue-family index
     */
    record VulkanQueueFamily(int value) {
        /**
         * Validates a concrete queue-family index.
         *
         * @param value non-negative queue-family index
         */
        public VulkanQueueFamily {
            requireNonNegative(value, "Vulkan queue-family index");
        }
    }
}
