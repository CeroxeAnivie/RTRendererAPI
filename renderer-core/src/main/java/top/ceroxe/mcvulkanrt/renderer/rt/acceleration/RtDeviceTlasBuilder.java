package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;

import java.util.List;
import java.util.Objects;

/** Generic affine-instance TLAS submission boundary over the renderer's native AS owner. */
public final class RtDeviceTlasBuilder {
    private RtDeviceTlasBuilder() {
    }

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

    public record Instance(
            long blasDeviceAddress,
            AffineTransform transform,
            int customIndex,
            int visibilityMask
    ) {
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

    public record CompletedBuild(
            RtAccelerationStructure accelerationStructure,
            int instanceCount,
            long instanceBufferBytes,
            long scratchBufferBytes,
            long elapsedNanos
    ) {
        public CompletedBuild {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            if (instanceCount <= 0 || instanceBufferBytes <= 0L || scratchBufferBytes <= 0L
                    || elapsedNanos < 0L) {
                throw new IllegalArgumentException("completed TLAS build statistics are invalid");
            }
        }
    }

    public static final class PendingBuild implements AutoCloseable {
        private final RtAccelerationStructure.WorldTlasBuildSubmission submission;
        private boolean closed;

        private PendingBuild(RtAccelerationStructure.WorldTlasBuildSubmission submission) {
            this.submission = Objects.requireNonNull(submission, "submission");
        }

        public synchronized CompletedBuild completeIfReady() {
            requireOpen();
            RtAccelerationStructure.CompletedWorldTlasBuild completed = submission.completeIfReady();
            if (completed == null) return null;
            closed = true;
            if (completed.update() || completed.sourceHandle() != 0L) {
                RtAccelerationStructure unexpected = completed.accelerationStructure();
                try {
                    unexpected.close();
                } finally {
                    throw new IllegalStateException("initial generic TLAS unexpectedly completed as an update");
                }
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
