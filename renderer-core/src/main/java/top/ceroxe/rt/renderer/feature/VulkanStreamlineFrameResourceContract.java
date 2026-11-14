package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Complete Vulkan resource set consumed by one Streamline reconstruction evaluation.
 *
 * <p>The existing reconstruction contract deliberately owns only renderer-produced inputs. This
 * companion contract adds the published output at the feature boundary, where its full Vulkan
 * metadata and distinct lifetime are both available. Keeping that ownership split prevents a
 * frame slot from becoming a feature-specific resource owner while still making an incomplete
 * JNI tag set impossible.</p>
 *
 * @param inputColor render-resolution linear HDR color input
 * @param outputColor published output-resolution linear HDR destination
 * @param depth render-resolution linear depth input
 * @param motionVectors render-resolution dense motion-vector input
 * @param exposure distinct one-texel frame exposure input
 */
public record VulkanStreamlineFrameResourceContract(
        VulkanFrameReconstructionResourceContract.Image inputColor,
        VulkanFrameReconstructionResourceContract.Image outputColor,
        VulkanFrameReconstructionResourceContract.Image depth,
        VulkanFrameReconstructionResourceContract.Image motionVectors,
        VulkanFrameReconstructionResourceContract.Image exposure
) {
    /**
     * Builds the complete tag set from resources borrowed for the current feature callback.
     *
     * @param context feature callback resources, valid only for this invocation
     * @return validated Streamline resource metadata
     */
    public static VulkanStreamlineFrameResourceContract from(VulkanFeatureFrameContext context) {
        VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
        VulkanFrameReconstructionResourceContract reconstruction = checked.reconstructionResources()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Streamline frame resources require reconstruction inputs"
                ));
        return new VulkanStreamlineFrameResourceContract(
                reconstruction.inputColor(),
                image(checked.reconstructionOutput()),
                reconstruction.depth(),
                reconstruction.motionVectors(),
                reconstruction.exposure()
        );
    }

    /** Validates formats, render/output extents, role-specific usage, and semantic non-aliasing. */
    public VulkanStreamlineFrameResourceContract {
        inputColor = requireColor(inputColor, "inputColor");
        outputColor = requireColor(outputColor, "outputColor");
        depth = Objects.requireNonNull(depth, "depth");
        motionVectors = Objects.requireNonNull(motionVectors, "motionVectors");
        exposure = Objects.requireNonNull(exposure, "exposure");
        requireSampled(inputColor, "inputColor");
        requireSampled(depth, "depth");
        requireSampled(motionVectors, "motionVectors");
        requireSampled(exposure, "exposure");
        requireStorage(outputColor, "outputColor");
        if (inputColor.width() != depth.width() || inputColor.height() != depth.height()
                || inputColor.width() != motionVectors.width() || inputColor.height() != motionVectors.height()) {
            throw new IllegalArgumentException("Streamline input color, depth, and motion must share the render extent");
        }
        if (exposure.width() != 1 || exposure.height() != 1) {
            throw new IllegalArgumentException("Streamline exposure must remain a one-texel frame-global image");
        }
        Set<Long> handles = new HashSet<>();
        for (VulkanFrameReconstructionResourceContract.Image image
                : new VulkanFrameReconstructionResourceContract.Image[]{
                inputColor, outputColor, depth, motionVectors, exposure
        }) {
            if (!handles.add(image.handle())) {
                throw new IllegalArgumentException("Streamline semantic images must not alias");
            }
        }
    }

    private static VulkanFrameReconstructionResourceContract.Image requireColor(
            VulkanFrameReconstructionResourceContract.Image image,
            String name
    ) {
        VulkanFrameReconstructionResourceContract.Image checked = Objects.requireNonNull(image, name);
        if (checked.format() != VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
            throw new IllegalArgumentException(name + " must use VK_FORMAT_R16G16B16A16_SFLOAT");
        }
        return checked;
    }

    private static void requireSampled(VulkanFrameReconstructionResourceContract.Image image, String name) {
        if ((image.usageFlags() & VK10.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException(name + " must support sampled access for Streamline");
        }
    }

    private static void requireStorage(VulkanFrameReconstructionResourceContract.Image image, String name) {
        if ((image.usageFlags() & VK10.VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
            throw new IllegalArgumentException(name + " must support storage access for Streamline output");
        }
    }

    private static VulkanFrameReconstructionResourceContract.Image image(RtGpuImage source) {
        RtGpuImage checked = Objects.requireNonNull(source, "reconstructionOutput");
        return new VulkanFrameReconstructionResourceContract.Image(
                checked.image(),
                checked.memory(),
                checked.imageView(),
                checked.format(),
                checked.width(),
                checked.height(),
                checked.usageFlags()
        );
    }
}
