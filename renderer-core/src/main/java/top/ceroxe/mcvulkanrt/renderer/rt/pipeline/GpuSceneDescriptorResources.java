package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Immutable resource publication for one GPUScene descriptor set.
 *
 * <p>A descriptor generation is accepted only when the TLAS, output image, frame constants, and
 * every persistent GPUScene storage lane are present together. This all-or-nothing boundary keeps
 * a frame slot from observing a mixture of scene generations while buffers are being replaced.</p>
 */
public final class GpuSceneDescriptorResources {
    private static final int FIRST_SCENE_BUFFER_BINDING = GpuSceneShaderBindings.TEXTURE_RECORDS;
    private static final int LAST_SCENE_BUFFER_BINDING = GpuSceneShaderBindings.LIGHT_RECORDS;

    private final long topLevelAccelerationStructure;
    private final long outputImageView;
    private final BufferRange frameUniforms;
    private final BufferRange[] sceneBuffers = new BufferRange[GpuSceneShaderBindings.COUNT];

    public GpuSceneDescriptorResources(
            long topLevelAccelerationStructure,
            long outputImageView,
            BufferRange frameUniforms,
            List<StorageBinding> sceneBuffers
    ) {
        if (topLevelAccelerationStructure == 0L) {
            throw new IllegalArgumentException("topLevelAccelerationStructure must not be null");
        }
        if (outputImageView == 0L) {
            throw new IllegalArgumentException("outputImageView must not be null");
        }
        this.topLevelAccelerationStructure = topLevelAccelerationStructure;
        this.outputImageView = outputImageView;
        this.frameUniforms = Objects.requireNonNull(frameUniforms, "frameUniforms");
        List<StorageBinding> checkedBindings = List.copyOf(
                Objects.requireNonNull(sceneBuffers, "sceneBuffers")
        );
        for (StorageBinding binding : checkedBindings) {
            int index = binding.binding();
            if (this.sceneBuffers[index] != null) {
                throw new IllegalArgumentException("duplicate GPUScene storage binding " + index);
            }
            this.sceneBuffers[index] = binding.range();
        }
        for (int binding = FIRST_SCENE_BUFFER_BINDING;
             binding <= LAST_SCENE_BUFFER_BINDING;
             binding++) {
            if (this.sceneBuffers[binding] == null) {
                throw new IllegalArgumentException("missing GPUScene storage binding " + binding);
            }
        }
        if (checkedBindings.size() != GpuSceneShaderBindings.COUNT - FIRST_SCENE_BUFFER_BINDING) {
            throw new IllegalArgumentException("GPUScene storage publication has unexpected binding count");
        }
    }

    long topLevelAccelerationStructure() {
        return topLevelAccelerationStructure;
    }

    long outputImageView() {
        return outputImageView;
    }

    BufferRange frameUniforms() {
        return frameUniforms;
    }

    BufferRange sceneBuffer(int binding) {
        if (binding < FIRST_SCENE_BUFFER_BINDING || binding > LAST_SCENE_BUFFER_BINDING) {
            throw new IndexOutOfBoundsException("not a GPUScene storage binding: " + binding);
        }
        return sceneBuffers[binding];
    }

    /** A shader-visible subrange whose allocation extent has already been proven. */
    public record BufferRange(long buffer, long offsetBytes, long rangeBytes, long allocationBytes) {
        public BufferRange {
            if (buffer == 0L) throw new IllegalArgumentException("buffer must not be null");
            if (offsetBytes < 0L || rangeBytes <= 0L || allocationBytes <= 0L) {
                throw new IllegalArgumentException("buffer range dimensions are invalid");
            }
            if ((offsetBytes & 3L) != 0L || (rangeBytes & 3L) != 0L) {
                throw new IllegalArgumentException("GPUScene buffer ranges must be word aligned");
            }
            long end = Math.addExact(offsetBytes, rangeBytes);
            if (end > allocationBytes) {
                throw new IllegalArgumentException("descriptor range exceeds its buffer allocation");
            }
        }

        public static BufferRange whole(long buffer, long allocationBytes) {
            return new BufferRange(buffer, 0L, allocationBytes, allocationBytes);
        }
    }

    /** Associates one persistent storage lane with its canonical shader binding. */
    public record StorageBinding(int binding, BufferRange range) {
        public StorageBinding {
            if (binding < FIRST_SCENE_BUFFER_BINDING || binding > LAST_SCENE_BUFFER_BINDING) {
                throw new IllegalArgumentException("binding is not a GPUScene storage lane: " + binding);
            }
            range = Objects.requireNonNull(range, "range");
        }
    }
}
