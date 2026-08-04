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
    private final DenoisingImageViews denoisingImages;
    private final ReconstructionImageViews reconstructionImages;
    private final BufferRange frameUniforms;
    private final BufferRange transientInstanceRecords;
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
     * @param denoisingImages                格式匹配的 NRD 信号视图或安全占位视图
     * @param reconstructionImages          格式匹配的重建信号视图或安全占位视图
     * @param frameUniforms                 帧常量缓冲区范围
     * @param transientInstanceRecords      当前帧瞬态实例记录范围
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
            DenoisingImageViews denoisingImages,
            ReconstructionImageViews reconstructionImages,
            BufferRange frameUniforms,
            BufferRange transientInstanceRecords,
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
        this.denoisingImages = Objects.requireNonNull(denoisingImages, "denoisingImages");
        this.reconstructionImages = Objects.requireNonNull(reconstructionImages, "reconstructionImages");
        this.frameUniforms = Objects.requireNonNull(frameUniforms, "frameUniforms");
        this.transientInstanceRecords = Objects.requireNonNull(
                transientInstanceRecords, "transientInstanceRecords"
        );
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

    /**
     * Compatibility constructor for descriptor-only tests and non-transient callers. The frame
     * uniform range is a valid fallback because no transient custom index is emitted in this mode.
     *
     * @param topLevelAccelerationStructure non-zero TLAS handle
     * @param outputImageView non-zero output image view
     * @param historyColorInputView non-zero prior color-history view
     * @param historyColorOutputView non-zero current color-history view
     * @param historyGeometryInputView non-zero prior geometry-history view
     * @param historyGeometryOutputView non-zero current geometry-history view
     * @param motionOutputView non-zero current motion-vector view
     * @param denoisingImages format-compatible denoising views or sentinels
     * @param reconstructionImages format-compatible reconstruction views or sentinels
     * @param frameUniforms frame-uniform range reused as the inert transient range
     * @param sceneBuffers complete persistent GPUScene storage bindings
     */
    public GpuSceneDescriptorResources(
            long topLevelAccelerationStructure,
            long outputImageView,
            long historyColorInputView,
            long historyColorOutputView,
            long historyGeometryInputView,
            long historyGeometryOutputView,
            long motionOutputView,
            DenoisingImageViews denoisingImages,
            ReconstructionImageViews reconstructionImages,
            BufferRange frameUniforms,
            List<StorageBinding> sceneBuffers
    ) {
        this(
                topLevelAccelerationStructure,
                outputImageView,
                historyColorInputView,
                historyColorOutputView,
                historyGeometryInputView,
                historyGeometryOutputView,
                motionOutputView,
                denoisingImages,
                reconstructionImages,
                frameUniforms,
                frameUniforms,
                sceneBuffers
        );
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

    DenoisingImageViews denoisingImages() {
        return denoisingImages;
    }

    ReconstructionImageViews reconstructionImages() { return reconstructionImages; }

    BufferRange frameUniforms() {
        return frameUniforms;
    }

    BufferRange transientInstanceRecords() {
        return transientInstanceRecords;
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

    /**
     * Nine storage-image views consumed by the NRD signal-producing and compose paths.
     *
     * <p>Inactive sessions publish format-matched sentinel views rather than reusing unrelated
     * output images. The views may intentionally alias by compatible format only in that sentinel
     * case; active frame-slot resources publish independent signal and modulation images.</p>
     *
     * @param normalRoughness normal and roughness signal view
     * @param viewZ linear view-space depth signal view
     * @param motionVectors dense motion-vector signal view
     * @param diffuseRadianceHitDistance diffuse radiance and hit-distance view
     * @param specularRadianceHitDistance specular radiance and hit-distance view
     * @param diffuseMaterialFactor diffuse remodulation factor view
     * @param specularMaterialFactor specular remodulation factor view
     * @param denoisedDiffuseRadianceHitDistance denoised diffuse output view
     * @param denoisedSpecularRadianceHitDistance denoised specular output view
     */
    public record DenoisingImageViews(
            long normalRoughness,
            long viewZ,
            long motionVectors,
            long diffuseRadianceHitDistance,
            long specularRadianceHitDistance,
            long diffuseMaterialFactor,
            long specularMaterialFactor,
            long denoisedDiffuseRadianceHitDistance,
            long denoisedSpecularRadianceHitDistance
    ) {
        /** Validates that every semantic role has a non-zero image view. */
        public DenoisingImageViews {
            requireImageView(normalRoughness, "normalRoughness");
            requireImageView(viewZ, "viewZ");
            requireImageView(motionVectors, "motionVectors");
            requireImageView(diffuseRadianceHitDistance, "diffuseRadianceHitDistance");
            requireImageView(specularRadianceHitDistance, "specularRadianceHitDistance");
            requireImageView(diffuseMaterialFactor, "diffuseMaterialFactor");
            requireImageView(specularMaterialFactor, "specularMaterialFactor");
            requireImageView(denoisedDiffuseRadianceHitDistance, "denoisedDiffuseRadianceHitDistance");
            requireImageView(denoisedSpecularRadianceHitDistance, "denoisedSpecularRadianceHitDistance");
        }
    }

    /**
     * Typed storage-image views used only for the reconstruction input contract.
     *
     * @param depth linear depth image view
     * @param motionVectors dense motion-vector image view
     * @param exposure one-texel exposure image view
     */
    public record ReconstructionImageViews(long depth, long motionVectors, long exposure) {
        /** Validates that every reconstruction role has a non-zero image view. */
        public ReconstructionImageViews {
            requireImageView(depth, "depth");
            requireImageView(motionVectors, "motionVectors");
            requireImageView(exposure, "exposure");
        }
    }
}
