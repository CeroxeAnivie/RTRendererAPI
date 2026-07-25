package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.util.List;
import java.util.Objects;

/**
 * Generic affine-instance TLAS submission boundary over the renderer's native AS owner.
 */
public final class RtDeviceTlasBuilder {
    private RtDeviceTlasBuilder() {
    }

    /**
     * Submits an initial top-level acceleration-structure build without blocking for GPU completion.
     *
     * <p>The returned owner retains all transient submission resources. Callers must either poll it
     * to completion or close it; abandoning it would keep the native submission and destination
     * allocation alive. The supplied BLAS addresses must remain valid until completion.</p>
     *
     * @param device                logical device that owns every referenced native resource
     * @param allocator             non-null VMA allocator handle associated with {@code device}
     * @param commands              command context used to submit the build
     * @param scratchAlignmentBytes positive device scratch-address alignment
     * @param instances             non-empty immutable source set for the TLAS
     * @return an owning asynchronous build handle
     * @throws NullPointerException     if an object argument or an instance transform is {@code null}
     * @throws IllegalArgumentException if a native handle, alignment, instance field, or collection is invalid
     */
    public static PendingBuild submit(
            VkDevice device,
            long allocator,
            RtCommandContext commands,
            int scratchAlignmentBytes,
            List<Instance> instances
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(commands, "commands");
        List<Instance> checkedInstances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        if (allocator == 0L) throw new IllegalArgumentException("allocator must not be null");
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratch alignment must be positive");
        }
        if (checkedInstances.isEmpty()) {
            throw new IllegalArgumentException("TLAS requires at least one visible instance");
        }
        List<RtAccelerationStructure.TlasInstance> nativeInstances = checkedInstances.stream()
                .map(RtDeviceTlasBuilder::nativeInstance)
                .toList();
        return new PendingBuild(
                RtAccelerationStructure.submitWorldTlasAsync(
                        device,
                        allocator,
                        commands,
                        scratchAlignmentBytes,
                        nativeInstances
                ),
                false,
                () -> { }
        );
    }

    /**
     * Opens a serialized TLAS lane that reuses instance-upload and scratch allocations.
     *
     * <p>The lane follows the same model as mature Vulkan RHIs: the initial build carries
     * {@code ALLOW_UPDATE}; transform-only generations use {@code MODE_UPDATE} with distinct
     * source/destination TLAS objects so descriptor-visible frames remain immutable. Exactly one
     * build may be pending because its persistent input buffers are intentionally shared.</p>
     *
     * @param device                logical device owning every referenced resource
     * @param allocator             VMA allocator associated with {@code device}
     * @param commands              serialized acceleration-structure command lane
     * @param scratchAlignmentBytes positive device scratch-address alignment
     * @return owned persistent build lane
     */
    public static PersistentBuildLane openPersistentLane(
            VkDevice device,
            long allocator,
            RtCommandContext commands,
            int scratchAlignmentBytes
    ) {
        return new PersistentBuildLane(device, allocator, commands, scratchAlignmentBytes);
    }

    private static RtAccelerationStructure.TlasInstance nativeInstance(Instance instance) {
        AffineTransform transform = instance.transform();
        return new RtAccelerationStructure.TlasInstance(
                instance.blasDeviceAddress(),
                transform.element(0), transform.element(1), transform.element(2), transform.element(3),
                transform.element(4), transform.element(5), transform.element(6), transform.element(7),
                transform.element(8), transform.element(9), transform.element(10), transform.element(11),
                instance.customIndex(),
                instance.visibilityMask()
        );
    }

    /**
     * Validated instance description consumed by one TLAS build.
     *
     * @param blasDeviceAddress non-null Vulkan device address of a live BLAS
     * @param transform         row-major 3-by-4 object-to-world transform
     * @param customIndex       unsigned 24-bit shader-visible instance index
     * @param visibilityMask    unsigned 8-bit ray visibility mask
     */
    public record Instance(
            long blasDeviceAddress,
            AffineTransform transform,
            int customIndex,
            int visibilityMask
    ) {
        /**
         * Validates the native address and Vulkan bit-width constraints at the ownership boundary.
         */
        public Instance {
            if (blasDeviceAddress == 0L) {
                throw new IllegalArgumentException("TLAS instance BLAS address must not be null");
            }
            transform = Objects.requireNonNull(transform, "transform");
            if (customIndex < 0 || customIndex > 0x00ff_ffff) {
                throw new IllegalArgumentException("TLAS custom index must fit 24 bits");
            }
            if (visibilityMask < 0 || visibilityMask > 0xff) {
                throw new IllegalArgumentException("TLAS visibility mask must fit 8 bits");
            }
        }
    }

    /**
     * Completed TLAS and immutable measurements captured for the submitted build.
     *
     * @param accelerationStructure owning completed TLAS; the receiver must eventually close it
     * @param instanceCount         number of encoded instances
     * @param instanceBufferBytes   bytes used by the transient instance input
     * @param scratchBufferBytes    bytes used by the build scratch allocation
     * @param elapsedNanos          measured GPU submission duration in nanoseconds
     * @param update                whether this completion used Vulkan TLAS update mode
     * @param sourceHandle          source TLAS handle for an update, or zero for a full build
     * @param recycledDestination   whether a descriptor-safe destination allocation was reused
     */
    public record CompletedBuild(
            RtAccelerationStructure accelerationStructure,
            int instanceCount,
            long instanceBufferBytes,
            long scratchBufferBytes,
            long elapsedNanos,
            boolean update,
            long sourceHandle,
            boolean recycledDestination
    ) {
        /**
         * Rejects partial or internally inconsistent completion reports.
         */
        public CompletedBuild {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            if (instanceCount <= 0 || instanceBufferBytes <= 0L || scratchBufferBytes <= 0L
                    || elapsedNanos < 0L) {
                throw new IllegalArgumentException("completed TLAS build statistics are invalid");
            }
            if (update != (sourceHandle != 0L)) {
                throw new IllegalArgumentException("TLAS update metadata is inconsistent");
            }
        }
    }

    /** Serialized owner for allocation-stable initial builds and TLAS updates. */
    public static final class PersistentBuildLane implements AutoCloseable {
        private final VkDevice device;
        private final long allocator;
        private final RtCommandContext commands;
        private final int scratchAlignmentBytes;
        private final RtAccelerationStructure.PersistentTlasBuildInputs inputs;
        private PendingBuild pending;
        private boolean closed;

        private PersistentBuildLane(
                VkDevice device,
                long allocator,
                RtCommandContext commands,
                int scratchAlignmentBytes
        ) {
            this.device = Objects.requireNonNull(device, "device");
            if (allocator == 0L) throw new IllegalArgumentException("allocator must not be null");
            this.allocator = allocator;
            this.commands = Objects.requireNonNull(commands, "commands");
            if (scratchAlignmentBytes <= 0) {
                throw new IllegalArgumentException("scratch alignment must be positive");
            }
            this.scratchAlignmentBytes = scratchAlignmentBytes;
            inputs = new RtAccelerationStructure.PersistentTlasBuildInputs(
                    device, allocator, commands.stallTelemetry()
            );
        }

        /**
         * Submits an allocation-stable full build. All instance records are uploaded.
         *
         * @param instances non-empty immutable TLAS instance set
         * @return exclusive asynchronous build owner
         */
        public synchronized PendingBuild submitBuild(List<Instance> instances) {
            requireAvailable();
            List<RtAccelerationStructure.TlasInstance> nativeInstances = nativeInstances(instances);
            int[] dirtySlots = allSlots(nativeInstances.size());
            return publish(RtAccelerationStructure.submitPersistentWorldTlasAsync(
                    device,
                    allocator,
                    commands,
                    scratchAlignmentBytes,
                    nativeInstances,
                    dirtySlots,
                    inputs
            ), false);
        }

        /**
         * Submits a descriptor-safe update into an optional recycled destination TLAS.
         * Ownership of {@code reusableDestination} transfers to the returned pending build.
         *
         * @param source                live descriptor-visible source TLAS
         * @param reusableDestination   safe detached destination, or {@code null}
         * @param instances             non-empty immutable successor instance set
         * @param dirtyInstanceSlots    sorted unique physical slots whose records changed
         * @return exclusive asynchronous update owner
         */
        public synchronized PendingBuild submitUpdate(
                RtAccelerationStructure source,
                RtAccelerationStructure reusableDestination,
                List<Instance> instances,
                int[] dirtyInstanceSlots
        ) {
            requireAvailable();
            List<RtAccelerationStructure.TlasInstance> nativeInstances = nativeInstances(instances);
            int[] checkedDirtySlots = Objects.requireNonNull(
                    dirtyInstanceSlots, "dirtyInstanceSlots"
            ).clone();
            RtTlasInstanceEncoder.validateDirtySlots(checkedDirtySlots, nativeInstances.size());
            return publish(RtAccelerationStructure.submitPersistentWorldTlasUpdateAsync(
                    device,
                    allocator,
                    commands,
                    scratchAlignmentBytes,
                    Objects.requireNonNull(source, "source"),
                    reusableDestination,
                    nativeInstances,
                    checkedDirtySlots,
                    inputs
            ), true);
        }

        private PendingBuild publish(
                RtAccelerationStructure.WorldTlasBuildSubmission submission,
                boolean update
        ) {
            PendingBuild result = new PendingBuild(submission, update, this::retirePending);
            pending = result;
            return result;
        }

        private synchronized void retirePending() {
            pending = null;
        }

        private void requireAvailable() {
            if (closed) throw new IllegalStateException("persistent TLAS build lane is closed");
            if (pending != null) {
                throw new IllegalStateException("persistent TLAS build lane already has pending work");
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (pending != null) {
                try {
                    pending.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
                pending = null;
            }
            try {
                inputs.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }

        private static List<RtAccelerationStructure.TlasInstance> nativeInstances(
                List<Instance> instances
        ) {
            List<Instance> checked = List.copyOf(Objects.requireNonNull(instances, "instances"));
            if (checked.isEmpty()) throw new IllegalArgumentException("TLAS requires instances");
            return checked.stream().map(RtDeviceTlasBuilder::nativeInstance).toList();
        }

        private static int[] allSlots(int count) {
            int[] slots = new int[count];
            for (int index = 0; index < count; index++) slots[index] = index;
            return slots;
        }
    }

    /**
     * Exclusive owner of an in-flight TLAS build.
     *
     * <p>Completion transfers ownership of the resulting acceleration structure to the returned
     * {@link CompletedBuild}. Closing before completion cancels host-side ownership and releases all
     * submission resources once the underlying command context permits it.</p>
     */
    public static final class PendingBuild implements AutoCloseable {
        private final RtAccelerationStructure.WorldTlasBuildSubmission submission;
        private final boolean expectedUpdate;
        private final Runnable retiredCallback;
        private boolean closed;

        private PendingBuild(
                RtAccelerationStructure.WorldTlasBuildSubmission submission,
                boolean expectedUpdate,
                Runnable retiredCallback
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.expectedUpdate = expectedUpdate;
            this.retiredCallback = Objects.requireNonNull(retiredCallback, "retiredCallback");
        }

        /**
         * Polls once without waiting for the GPU.
         *
         * @return the completed build with transferred TLAS ownership, or {@code null} while pending
         * @throws IllegalStateException if this owner was already completed or closed
         */
        public synchronized CompletedBuild completeIfReady() {
            requireOpen();
            RtAccelerationStructure.CompletedWorldTlasBuild completed = submission.completeIfReady();
            if (completed == null) return null;
            closed = true;
            retiredCallback.run();
            if (completed.update() != expectedUpdate
                    || completed.update() != (completed.sourceHandle() != 0L)) {
                RtAccelerationStructure unexpected = completed.accelerationStructure();
                IllegalStateException invariantFailure = new IllegalStateException(
                        "generic TLAS completion mode diverged from its submission"
                );
                try {
                    unexpected.close();
                } catch (RuntimeException | LinkageError | OutOfMemoryError closeFailure) {
                    invariantFailure.addSuppressed(closeFailure);
                }
                throw invariantFailure;
            }
            return new CompletedBuild(
                    completed.accelerationStructure(),
                    completed.instanceCount(),
                    completed.instanceBufferBytes(),
                    completed.scratchBufferBytes(),
                    completed.elapsedNanos(),
                    completed.update(),
                    completed.sourceHandle(),
                    completed.recycledDestination()
            );
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("generic TLAS submission is already completed or closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                submission.close();
            } finally {
                retiredCallback.run();
            }
        }
    }
}
