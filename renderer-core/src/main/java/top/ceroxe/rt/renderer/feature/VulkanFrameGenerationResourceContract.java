package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

import java.util.Objects;

/**
 * Depth and dense motion-vector inputs retained until one Streamline DLSS-G present.
 *
 * @param depth render-resolution linear depth image
 * @param motionVectors render-resolution dense motion-vector image
 * @param exposure distinct one-texel frame exposure image
 */
public record VulkanFrameGenerationResourceContract(
        VulkanFrameReconstructionResourceContract.Image depth,
        VulkanFrameReconstructionResourceContract.Image motionVectors,
        VulkanFrameReconstructionResourceContract.Image exposure
) {
    /** Validates formats, usage, extents, and semantic non-aliasing. */
    public VulkanFrameGenerationResourceContract {
        depth = require(depth, VK10.VK_FORMAT_R32_SFLOAT, "depth");
        motionVectors = require(motionVectors, VK10.VK_FORMAT_R16G16_SFLOAT, "motionVectors");
        exposure = require(exposure, VK10.VK_FORMAT_R32_SFLOAT, "exposure");
        if (depth.width() != motionVectors.width() || depth.height() != motionVectors.height()) {
            throw new IllegalArgumentException("DLSS-G depth and motion vectors must share one extent");
        }
        if (depth.handle() == motionVectors.handle()) {
            throw new IllegalArgumentException("DLSS-G depth and motion vectors must not alias");
        }
        if (exposure.width() != 1 || exposure.height() != 1
                || exposure.handle() == depth.handle() || exposure.handle() == motionVectors.handle()) {
            throw new IllegalArgumentException("DLSS-G exposure sentinel must be a distinct one-texel image");
        }
    }

    private static VulkanFrameReconstructionResourceContract.Image require(
            VulkanFrameReconstructionResourceContract.Image image,
            int format,
            String name
    ) {
        VulkanFrameReconstructionResourceContract.Image checked = Objects.requireNonNull(image, name);
        if (checked.format() != format) throw new IllegalArgumentException(name + " has an incompatible format");
        if ((checked.usageFlags() & VK10.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException(name + " must support sampled access for DLSS-G");
        }
        return checked;
    }
}
