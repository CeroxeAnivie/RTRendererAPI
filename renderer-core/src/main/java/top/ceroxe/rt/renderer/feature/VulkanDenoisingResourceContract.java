package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact image contract consumed by a temporal radiance denoiser.
 *
 * <p>This deliberately names semantic inputs rather than exposing a generic image array. NRD's
 * normal/roughness, view-Z, motion and radiance-hit-distance inputs are not interchangeable, and
 * accepting a merely extent-compatible image would create stable-looking but invalid history.</p>
 *
 * @param normalRoughness packed normal and roughness signal
 * @param viewZ linear view-space depth signal
 * @param motionVectors dense screen-space motion signal
 * @param diffuseRadianceHitDistance diffuse radiance and hit-distance input
 * @param specularRadianceHitDistance specular radiance and hit-distance input
 * @param diffuseMaterialFactor diffuse material factor used after denoising
 * @param specularMaterialFactor specular material factor used after denoising
 * @param denoisedDiffuseRadianceHitDistance denoised diffuse output
 * @param denoisedSpecularRadianceHitDistance denoised specular output
 */
public record VulkanDenoisingResourceContract(
        Image normalRoughness,
        Image viewZ,
        Image motionVectors,
        Image diffuseRadianceHitDistance,
        Image specularRadianceHitDistance,
        Image diffuseMaterialFactor,
        Image specularMaterialFactor,
        Image denoisedDiffuseRadianceHitDistance,
        Image denoisedSpecularRadianceHitDistance
) {
    /**
     * Immutable Vulkan image metadata borrowed for one feature callback.
     *
     * @param handle non-zero Vulkan image handle
     * @param format Vulkan format token
     * @param width positive image width in pixels
     * @param height positive image height in pixels
     */
    public record Image(long handle, int format, int width, int height) {
        /** Validates the borrowed image handle and positive extent. */
        public Image {
            if (handle == 0L) throw new IllegalArgumentException("denoising image handle must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("denoising image extent must be positive");
            }
        }
    }

    /**
     * Validates formats, extent and aliasing before native code can observe the images.
     */
    public VulkanDenoisingResourceContract {
        normalRoughness = requireFormat(normalRoughness, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "normalRoughness");
        viewZ = requireFormat(viewZ, VK10.VK_FORMAT_R32_SFLOAT, "viewZ");
        motionVectors = requireFormat(motionVectors, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "motionVectors");
        diffuseRadianceHitDistance = requireFormat(
                diffuseRadianceHitDistance, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "diffuseRadianceHitDistance"
        );
        specularRadianceHitDistance = requireFormat(
                specularRadianceHitDistance, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "specularRadianceHitDistance"
        );
        diffuseMaterialFactor = requireFormat(
                diffuseMaterialFactor, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "diffuseMaterialFactor"
        );
        specularMaterialFactor = requireFormat(
                specularMaterialFactor, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "specularMaterialFactor"
        );
        denoisedDiffuseRadianceHitDistance = requireFormat(
                denoisedDiffuseRadianceHitDistance,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "denoisedDiffuseRadianceHitDistance"
        );
        denoisedSpecularRadianceHitDistance = requireFormat(
                denoisedSpecularRadianceHitDistance,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "denoisedSpecularRadianceHitDistance"
        );
        requireSameExtent(
                List.of(
                        normalRoughness,
                        viewZ,
                        motionVectors,
                        diffuseRadianceHitDistance,
                        specularRadianceHitDistance,
                        diffuseMaterialFactor,
                        specularMaterialFactor,
                        denoisedDiffuseRadianceHitDistance,
                        denoisedSpecularRadianceHitDistance
                )
        );
        requireDistinct(
                List.of(
                        normalRoughness,
                        viewZ,
                        motionVectors,
                        diffuseRadianceHitDistance,
                        specularRadianceHitDistance,
                        diffuseMaterialFactor,
                        specularMaterialFactor,
                        denoisedDiffuseRadianceHitDistance,
                        denoisedSpecularRadianceHitDistance
                )
        );
    }

    private static Image requireFormat(Image image, int expectedFormat, String name) {
        Image checked = Objects.requireNonNull(image, name);
        if (checked.format() != expectedFormat) {
            throw new IllegalArgumentException(
                    name + " has incompatible Vulkan format " + checked.format() + ", expected " + expectedFormat
            );
        }
        return checked;
    }

    private static void requireSameExtent(List<Image> images) {
        Image first = images.getFirst();
        for (Image image : images) {
            if (image.width() != first.width() || image.height() != first.height()) {
                throw new IllegalArgumentException("all denoising images must share one extent");
            }
        }
    }

    private static void requireDistinct(List<Image> images) {
        Set<Long> handles = new HashSet<>();
        for (Image image : images) {
            if (!handles.add(image.handle())) {
                throw new IllegalArgumentException("denoising semantic images must not alias");
            }
        }
    }
}
