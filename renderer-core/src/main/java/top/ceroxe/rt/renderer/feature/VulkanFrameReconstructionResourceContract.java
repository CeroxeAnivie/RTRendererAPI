package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact renderer-owned input contract consumed by a frame reconstruction implementation.
 *
 * <p>Color, depth, and motion describe the same internal render extent. Exposure is intentionally
 * a separate one-texel frame-global signal: treating it as a render-sized image would both waste
 * memory and permit an integration to silently consume a semantically different resource. The
 * contract is validated before a native feature callback so JNI cannot reinterpret an alias or an
 * incompatible format as valid Streamline input.</p>
 *
 * @param inputColor render-resolution linear HDR color input
 * @param depth render-resolution linear depth input
 * @param motionVectors render-resolution dense motion-vector input
 * @param exposure distinct one-texel frame exposure input
 */
public record VulkanFrameReconstructionResourceContract(
        Image inputColor,
        Image depth,
        Image motionVectors,
        Image exposure
) {
    /**
     * Immutable Vulkan image metadata borrowed for one feature callback.
     *
     * @param handle non-zero Vulkan image handle
     * @param memory non-zero backing device-memory handle
     * @param view non-zero Vulkan image-view handle
     * @param format Vulkan format token
     * @param width positive image width in pixels
     * @param height positive image height in pixels
     * @param usageFlags Vulkan image usage bit mask including storage access
     */
    public record Image(long handle, long memory, long view, int format, int width, int height, int usageFlags) {
        /** Validates handles, extent, and the storage-image usage contract. */
        public Image {
            if (handle == 0L) throw new IllegalArgumentException("reconstruction image handle must not be null");
            if (memory == 0L) throw new IllegalArgumentException("reconstruction image memory must not be null");
            if (view == 0L) throw new IllegalArgumentException("reconstruction image view must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("reconstruction image extent must be positive");
            }
            if ((usageFlags & VK10.VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
                throw new IllegalArgumentException("reconstruction image must support storage access");
            }
        }
    }

    /** Validates exact formats, common input extent, frame-global exposure, and no aliasing. */
    public VulkanFrameReconstructionResourceContract {
        inputColor = requireFormat(inputColor, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "inputColor");
        depth = requireFormat(depth, VK10.VK_FORMAT_R32_SFLOAT, "depth");
        motionVectors = requireFormat(motionVectors, VK10.VK_FORMAT_R16G16_SFLOAT, "motionVectors");
        exposure = requireFormat(exposure, VK10.VK_FORMAT_R32_SFLOAT, "exposure");
        requireSampled(inputColor, "inputColor");
        requireSampled(depth, "depth");
        requireSampled(motionVectors, "motionVectors");
        requireSampled(exposure, "exposure");
        requireSameExtent(List.of(inputColor, depth, motionVectors));
        if (exposure.width() != 1 || exposure.height() != 1) {
            throw new IllegalArgumentException("exposure must be a one-texel frame-global image");
        }
        requireDistinct(List.of(inputColor, depth, motionVectors, exposure));
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

    private static void requireSampled(Image image, String name) {
        if ((image.usageFlags() & VK10.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException(name + " must support sampled access for reconstruction");
        }
    }

    private static void requireSameExtent(List<Image> images) {
        Image first = images.getFirst();
        for (Image image : images) {
            if (image.width() != first.width() || image.height() != first.height()) {
                throw new IllegalArgumentException("reconstruction color, depth, and motion must share one extent");
            }
        }
    }

    private static void requireDistinct(List<Image> images) {
        Set<Long> handles = new HashSet<>();
        for (Image image : images) {
            if (!handles.add(image.handle())) {
                throw new IllegalArgumentException("reconstruction semantic images must not alias");
            }
        }
    }
}
