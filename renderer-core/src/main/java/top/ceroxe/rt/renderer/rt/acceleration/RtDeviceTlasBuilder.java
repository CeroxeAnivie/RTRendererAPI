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
                )
        );
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
     */
    public record CompletedBuild(
            RtAccelerationStructure accelerationStructure,
            int instanceCount,
            long instanceBufferBytes,
            long scratchBufferBytes,
            long elapsedNanos
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
        private boolean closed;

        private PendingBuild(RtAccelerationStructure.WorldTlasBuildSubmission submission) {
            this.submission = Objects.requireNonNull(submission, "submission");
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
            if (completed.update() || completed.sourceHandle() != 0L) {
                RtAccelerationStructure unexpected = completed.accelerationStructure();
                IllegalStateException invariantFailure = new IllegalStateException(
                        "initial generic TLAS unexpectedly completed as an update"
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
                    completed.elapsedNanos()
            );
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("generic TLAS submission is already completed or closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            submission.close();
        }
    }
}
