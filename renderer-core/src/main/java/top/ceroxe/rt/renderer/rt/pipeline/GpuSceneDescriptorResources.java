package top.ceroxe.rt.renderer.rt.pipeline;

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
    private final long historyColorInputView;
    private final long historyColorOutputView;
    private final long historyGeometryInputView;
    private final long historyGeometryOutputView;
    private final long motionOutputView;
    private final BufferRange frameUniforms;
    private final BufferRange[] sceneBuffers = new BufferRange[GpuSceneShaderBindings.COUNT];

    /**
     * 创建并完整校验一个 GPUScene 描述符资源代次。
     *
     * @param topLevelAccelerationStructure 非零 TLAS 句柄
     * @param outputImageView               非零输出图像视图
     * @param historyColorInputView         非零上一帧线性色 history 视图
     * @param historyColorOutputView        非零当前帧线性色 history 视图
     * @param historyGeometryInputView      非零上一帧几何 history 视图
     * @param historyGeometryOutputView     非零当前帧几何 history 视图
     * @param motionOutputView              非零当前帧 motion-vector 视图
     * @param frameUniforms                 帧常量缓冲区范围
     * @param sceneBuffers                  全部持久场景存储绑定
     */
    public GpuSceneDescriptorResources(
            long topLevelAccelerationStructure,
            long outputImageView,
            long historyColorInputView,
            long historyColorOutputView,
            long historyGeometryInputView,
            long historyGeometryOutputView,
            long motionOutputView,
            BufferRange frameUniforms,
            List<StorageBinding> sceneBuffers
    ) {
        if (topLevelAccelerationStructure == 0L) {
            throw new IllegalArgumentException("topLevelAccelerationStructure must not be null");
        }
        requireImageView(outputImageView, "outputImageView");
        requireImageView(historyColorInputView, "historyColorInputView");
        requireImageView(historyColorOutputView, "historyColorOutputView");
        requireImageView(historyGeometryInputView, "historyGeometryInputView");
        requireImageView(historyGeometryOutputView, "historyGeometryOutputView");
        requireImageView(motionOutputView, "motionOutputView");
        if (historyColorInputView == historyColorOutputView) {
            throw new IllegalArgumentException("history color input and output must not alias");
        }
        if (historyGeometryInputView == historyGeometryOutputView) {
            throw new IllegalArgumentException("history geometry input and output must not alias");
        }
        this.topLevelAccelerationStructure = topLevelAccelerationStructure;
        this.outputImageView = outputImageView;
        this.historyColorInputView = historyColorInputView;
        this.historyColorOutputView = historyColorOutputView;
        this.historyGeometryInputView = historyGeometryInputView;
        this.historyGeometryOutputView = historyGeometryOutputView;
        this.motionOutputView = motionOutputView;
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
        if (checkedBindings.size() != LAST_SCENE_BUFFER_BINDING - FIRST_SCENE_BUFFER_BINDING + 1) {
            throw new IllegalArgumentException("GPUScene storage publication has unexpected binding count");
        }
    }

    private static void requireImageView(long imageView, String name) {
        if (imageView == 0L) throw new IllegalArgumentException(name + " must not be null");
    }

    long topLevelAccelerationStructure() {
        return topLevelAccelerationStructure;
    }

    long outputImageView() {
        return outputImageView;
    }

    long historyColorInputView() {
        return historyColorInputView;
    }

    long historyColorOutputView() {
        return historyColorOutputView;
    }

    long historyGeometryInputView() {
        return historyGeometryInputView;
    }

    long historyGeometryOutputView() {
        return historyGeometryOutputView;
    }

    long motionOutputView() {
        return motionOutputView;
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

    /**
     * A shader-visible subrange whose allocation extent has already been proven.
     *
     * @param buffer          non-zero Vulkan buffer handle
     * @param offsetBytes     aligned byte offset
     * @param rangeBytes      positive aligned descriptor range
     * @param allocationBytes backing allocation extent
     */
    public record BufferRange(long buffer, long offsetBytes, long rangeBytes, long allocationBytes) {
        /**
         * 校验句柄、对齐和后备分配边界。
         */
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

        /**
         * 创建覆盖完整后备分配的描述符范围。
         *
         * @param buffer          非零 Vulkan 缓冲区句柄
         * @param allocationBytes 正后备分配字节数
         * @return 偏移为零且覆盖完整分配的范围
         */
        public static BufferRange whole(long buffer, long allocationBytes) {
            return new BufferRange(buffer, 0L, allocationBytes, allocationBytes);
        }
    }

    /**
     * Associates one persistent storage lane with its canonical shader binding.
     *
     * @param binding canonical GPUScene storage binding
     * @param range   validated buffer range
     */
    public record StorageBinding(int binding, BufferRange range) {
        /**
         * 校验绑定属于持久 GPUScene 存储区间。
         */
        public StorageBinding {
            if (binding < FIRST_SCENE_BUFFER_BINDING || binding > LAST_SCENE_BUFFER_BINDING) {
                throw new IllegalArgumentException("binding is not a GPUScene storage lane: " + binding);
            }
            range = Objects.requireNonNull(range, "range");
        }
    }
}
