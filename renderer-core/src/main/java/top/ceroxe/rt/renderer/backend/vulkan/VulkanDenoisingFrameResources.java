package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.feature.VulkanDenoisingResourceContract;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;

import java.util.Objects;

/**
 * One frame-slot-local set of non-exportable images for a temporal radiance denoiser.
 *
 * <p>NRD inputs and outputs intentionally remain independent from presentation and renderer
 * temporal-history images. That prevents native denoising from observing a display-tonemapped
 * value, and lets the frame-slot fence remain the sole owner of all image lifetimes.</p>
 */
final class VulkanDenoisingFrameResources implements AutoCloseable {
    static final long BYTES_PER_PIXEL = 68L;

    private RtGpuImage normalRoughness;
    private RtGpuImage viewZ;
    private RtGpuImage motionVectors;
    private RtGpuImage diffuseRadianceHitDistance;
    private RtGpuImage specularRadianceHitDistance;
    private RtGpuImage diffuseMaterialFactor;
    private RtGpuImage specularMaterialFactor;
    private RtGpuImage denoisedDiffuseRadianceHitDistance;
    private RtGpuImage denoisedSpecularRadianceHitDistance;
    private boolean layoutsInitialized;
    private boolean closed;

    private VulkanDenoisingFrameResources(
            RtGpuImage normalRoughness,
            RtGpuImage viewZ,
            RtGpuImage motionVectors,
            RtGpuImage diffuseRadianceHitDistance,
            RtGpuImage specularRadianceHitDistance,
            RtGpuImage diffuseMaterialFactor,
            RtGpuImage specularMaterialFactor,
            RtGpuImage denoisedDiffuseRadianceHitDistance,
            RtGpuImage denoisedSpecularRadianceHitDistance
    ) {
        this.normalRoughness = Objects.requireNonNull(normalRoughness, "normalRoughness");
        this.viewZ = Objects.requireNonNull(viewZ, "viewZ");
        this.motionVectors = Objects.requireNonNull(motionVectors, "motionVectors");
        this.diffuseRadianceHitDistance = Objects.requireNonNull(
                diffuseRadianceHitDistance, "diffuseRadianceHitDistance"
        );
        this.specularRadianceHitDistance = Objects.requireNonNull(
                specularRadianceHitDistance, "specularRadianceHitDistance"
        );
        this.diffuseMaterialFactor = Objects.requireNonNull(diffuseMaterialFactor, "diffuseMaterialFactor");
        this.specularMaterialFactor = Objects.requireNonNull(specularMaterialFactor, "specularMaterialFactor");
        this.denoisedDiffuseRadianceHitDistance = Objects.requireNonNull(
                denoisedDiffuseRadianceHitDistance, "denoisedDiffuseRadianceHitDistance"
        );
        this.denoisedSpecularRadianceHitDistance = Objects.requireNonNull(
                denoisedSpecularRadianceHitDistance, "denoisedSpecularRadianceHitDistance"
        );
        contract(); // Enforce the public native boundary before publishing this owner.
    }

    static VulkanDenoisingFrameResources create(VulkanDeviceRuntime device, int width, int height) {
        VulkanDeviceRuntime checked = Objects.requireNonNull(device, "device");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("denoising extent must be positive");
        VulkanDenoisingImageSupport.requireSupported(checked.physicalDevice());

        RtGpuImage normalRoughness = null;
        RtGpuImage viewZ = null;
        RtGpuImage motionVectors = null;
        RtGpuImage diffuseRadianceHitDistance = null;
        RtGpuImage specularRadianceHitDistance = null;
        RtGpuImage diffuseMaterialFactor = null;
        RtGpuImage specularMaterialFactor = null;
        RtGpuImage denoisedDiffuseRadianceHitDistance = null;
        RtGpuImage denoisedSpecularRadianceHitDistance = null;
        try {
            normalRoughness = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.NORMAL_ROUGHNESS_FORMAT
            );
            viewZ = createImage(checked, width, height, VulkanDenoisingImageSupport.VIEW_Z_FORMAT);
            motionVectors = createImage(checked, width, height, VulkanDenoisingImageSupport.MOTION_FORMAT);
            diffuseRadianceHitDistance = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            specularRadianceHitDistance = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            diffuseMaterialFactor = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            specularMaterialFactor = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            denoisedDiffuseRadianceHitDistance = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            denoisedSpecularRadianceHitDistance = createImage(
                    checked, width, height, VulkanDenoisingImageSupport.RADIANCE_HIT_DISTANCE_FORMAT
            );
            return new VulkanDenoisingFrameResources(
                    normalRoughness,
                    viewZ,
                    motionVectors,
                    diffuseRadianceHitDistance,
                    specularRadianceHitDistance,
                    diffuseMaterialFactor,
                    specularMaterialFactor,
                    denoisedDiffuseRadianceHitDistance,
                    denoisedSpecularRadianceHitDistance
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(denoisedSpecularRadianceHitDistance, failure);
            closeSuppressing(denoisedDiffuseRadianceHitDistance, failure);
            closeSuppressing(specularMaterialFactor, failure);
            closeSuppressing(diffuseMaterialFactor, failure);
            closeSuppressing(specularRadianceHitDistance, failure);
            closeSuppressing(diffuseRadianceHitDistance, failure);
            closeSuppressing(motionVectors, failure);
            closeSuppressing(viewZ, failure);
            closeSuppressing(normalRoughness, failure);
            throw failure;
        }
    }

    private static RtGpuImage createImage(VulkanDeviceRuntime device, int width, int height, int format) {
        // NRD creates sampled views for several dispatch resources even though GPUScene authors
        // them through storage descriptors. Both usage bits must exist on the underlying image.
        return RtGpuImage.createStorageSampledImage(device.device(), device.allocator(), width, height, format);
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    static long requiredBytes(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("denoising extent must be positive");
        return Math.multiplyExact(Math.multiplyExact((long) width, height), BYTES_PER_PIXEL);
    }

    boolean matchesExtent(int width, int height) {
        requireOpen();
        return normalRoughness.width() == width && normalRoughness.height() == height;
    }

    long allocationSizeBytes() {
        requireOpen();
        long bytes = 0L;
        bytes = Math.addExact(bytes, normalRoughness.allocationSize());
        bytes = Math.addExact(bytes, viewZ.allocationSize());
        bytes = Math.addExact(bytes, motionVectors.allocationSize());
        bytes = Math.addExact(bytes, diffuseRadianceHitDistance.allocationSize());
        bytes = Math.addExact(bytes, specularRadianceHitDistance.allocationSize());
        bytes = Math.addExact(bytes, diffuseMaterialFactor.allocationSize());
        bytes = Math.addExact(bytes, specularMaterialFactor.allocationSize());
        bytes = Math.addExact(bytes, denoisedDiffuseRadianceHitDistance.allocationSize());
        bytes = Math.addExact(bytes, denoisedSpecularRadianceHitDistance.allocationSize());
        return bytes;
    }

    VulkanDenoisingResourceContract contract() {
        requireOpen();
        return new VulkanDenoisingResourceContract(
                image(normalRoughness),
                image(viewZ),
                image(motionVectors),
                image(diffuseRadianceHitDistance),
                image(specularRadianceHitDistance),
                image(diffuseMaterialFactor),
                image(specularMaterialFactor),
                image(denoisedDiffuseRadianceHitDistance),
                image(denoisedSpecularRadianceHitDistance)
        );
    }

    /** Renderer-private image view for the ray-generation normal/roughness signal. */
    long normalRoughnessView() {
        requireOpen();
        return normalRoughness.imageView();
    }

    /** Renderer-private image view for camera-space positive view-Z. */
    long viewZView() {
        requireOpen();
        return viewZ.imageView();
    }

    /** Renderer-private image view for NRD motion vectors, independent from temporal AA history. */
    long motionVectorsView() {
        requireOpen();
        return motionVectors.imageView();
    }

    /** Renderer-private image view for unfiltered diffuse radiance and hit distance. */
    long diffuseRadianceHitDistanceView() {
        requireOpen();
        return diffuseRadianceHitDistance.imageView();
    }

    /** Renderer-private image view for unfiltered specular radiance and hit distance. */
    long specularRadianceHitDistanceView() {
        requireOpen();
        return specularRadianceHitDistance.imageView();
    }

    GpuSceneDescriptorResources.DenoisingImageViews descriptorViews() {
        requireOpen();
        return new GpuSceneDescriptorResources.DenoisingImageViews(
                normalRoughnessView(),
                viewZView(),
                motionVectorsView(),
                diffuseRadianceHitDistanceView(),
                specularRadianceHitDistanceView(),
                diffuseMaterialFactor.imageView(),
                specularMaterialFactor.imageView(),
                denoisedDiffuseRadianceHitDistance.imageView(),
                denoisedSpecularRadianceHitDistance.imageView()
        );
    }

    boolean layoutsInitialized() {
        requireOpen();
        return layoutsInitialized;
    }

    /** Advances layout state only after the command buffer owning the transitions is submitted. */
    void markLayoutsInitialized() {
        requireOpen();
        layoutsInitialized = true;
    }

    private static VulkanDenoisingResourceContract.Image image(RtGpuImage image) {
        return new VulkanDenoisingResourceContract.Image(
                image.image(), image.format(), image.width(), image.height()
        );
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("denoising frame resources are closed");
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        if (denoisedSpecularRadianceHitDistance != null) {
            try {
                denoisedSpecularRadianceHitDistance.close();
                denoisedSpecularRadianceHitDistance = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (denoisedDiffuseRadianceHitDistance != null) {
            try {
                denoisedDiffuseRadianceHitDistance.close();
                denoisedDiffuseRadianceHitDistance = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (specularMaterialFactor != null) {
            try {
                specularMaterialFactor.close();
                specularMaterialFactor = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (diffuseMaterialFactor != null) {
            try {
                diffuseMaterialFactor.close();
                diffuseMaterialFactor = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (specularRadianceHitDistance != null) {
            try {
                specularRadianceHitDistance.close();
                specularRadianceHitDistance = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (diffuseRadianceHitDistance != null) {
            try {
                diffuseRadianceHitDistance.close();
                diffuseRadianceHitDistance = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (motionVectors != null) {
            try {
                motionVectors.close();
                motionVectors = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (viewZ != null) {
            try {
                viewZ.close();
                viewZ = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (normalRoughness != null) {
            try {
                normalRoughness.close();
                normalRoughness = null;
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        closed = normalRoughness == null
                && viewZ == null
                && motionVectors == null
                && diffuseRadianceHitDistance == null
                && specularRadianceHitDistance == null
                && diffuseMaterialFactor == null
                && specularMaterialFactor == null
                && denoisedDiffuseRadianceHitDistance == null
                && denoisedSpecularRadianceHitDistance == null;
        layoutsInitialized = false;
        if (failure != null) throw failure;
    }

    private static RuntimeException append(RuntimeException failure, RuntimeException next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }
}
