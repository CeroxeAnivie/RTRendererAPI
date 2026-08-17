package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.Objects;

/** Owns exactly one generic texture generation's image and VMA allocation. */
final class VulkanGenericTextureImage implements AutoCloseable {
    private final long allocator;
    private final long image;
    private final long allocation;
    private boolean closed;

    private VulkanGenericTextureImage(VkDevice device, long allocator, long image, long allocation) {
        Objects.requireNonNull(device, "device");
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
    }

    static VulkanGenericTextureImage create(VkDevice device, long allocator, TextureResource texture) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(texture, "texture");
        if (allocator == VK10.VK_NULL_HANDLE) throw new IllegalArgumentException("allocator must not be null");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo createInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(imageType(texture.dimension()))
                    .format(format(texture.format()))
                    .mipLevels(texture.mipLevelCount())
                    .arrayLayers(texture.arrayLayerCount())
                    .samples(sampleCount(texture.sampleCount()))
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage(texture))
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            if (texture.dimension() == TextureDimension.TEXTURE_2D
                    && texture.width() == texture.height() && texture.arrayLayerCount() >= 6) {
                // A future validated cube view cannot retrofit this creation flag. Enabling it for
                // any compatible image is semantically neutral and preserves the portable view contract.
                createInfo.flags(VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT);
            }
            createInfo.extent().width(texture.width()).height(texture.height()).depth(texture.depth());
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                    .flags(Vma.VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT);
            LongBuffer imageHandle = stack.longs(VK10.VK_NULL_HANDLE);
            PointerBuffer allocationHandle = stack.mallocPointer(1);
            VulkanFailures.check(
                    Vma.vmaCreateImage(allocator, createInfo, allocationInfo, imageHandle, allocationHandle, null),
                    "vmaCreateImage.genericTexture"
            );
            return new VulkanGenericTextureImage(device, allocator, imageHandle.get(0), allocationHandle.get(0));
        }
    }

    long image() {
        if (closed) throw new IllegalStateException("generic texture image is closed");
        return image;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyImage(allocator, image, allocation);
    }

    private static int imageType(TextureDimension value) {
        return switch (value) {
            case TEXTURE_1D -> VK10.VK_IMAGE_TYPE_1D;
            case TEXTURE_2D -> VK10.VK_IMAGE_TYPE_2D;
            case TEXTURE_3D -> VK10.VK_IMAGE_TYPE_3D;
        };
    }

    private static int sampleCount(int value) {
        return switch (value) {
            case 1 -> VK10.VK_SAMPLE_COUNT_1_BIT;
            case 2 -> VK10.VK_SAMPLE_COUNT_2_BIT;
            case 4 -> VK10.VK_SAMPLE_COUNT_4_BIT;
            case 8 -> VK10.VK_SAMPLE_COUNT_8_BIT;
            case 16 -> VK10.VK_SAMPLE_COUNT_16_BIT;
            case 32 -> VK10.VK_SAMPLE_COUNT_32_BIT;
            case 64 -> VK10.VK_SAMPLE_COUNT_64_BIT;
            default -> throw new IllegalArgumentException("unsupported Vulkan sample count: " + value);
        };
    }

    private static int usage(TextureResource texture) {
        int flags = 0;
        for (TextureUsage value : texture.usage()) flags |= switch (value) {
            case COPY_SOURCE -> VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            case COPY_DESTINATION -> VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            case SAMPLED -> VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
            case STORAGE_READ, STORAGE_READ_WRITE -> VK10.VK_IMAGE_USAGE_STORAGE_BIT;
            case COLOR_ATTACHMENT -> VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            case DEPTH_STENCIL_ATTACHMENT -> VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        };
        return flags;
    }

    static int format(TextureFormat value) {
        return switch (value) {
            case R8_UNORM -> VK10.VK_FORMAT_R8_UNORM;
            case RG8_UNORM -> VK10.VK_FORMAT_R8G8_UNORM;
            case RGBA8_UNORM -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SRGB -> VK10.VK_FORMAT_R8G8B8A8_SRGB;
            case R16_FLOAT -> VK10.VK_FORMAT_R16_SFLOAT;
            case RG16_FLOAT -> VK10.VK_FORMAT_R16G16_SFLOAT;
            case RGBA16_FLOAT -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32_FLOAT -> VK10.VK_FORMAT_R32_SFLOAT;
            case RG32_FLOAT -> VK10.VK_FORMAT_R32G32_SFLOAT;
            case RGBA32_FLOAT -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            case D32_FLOAT -> VK10.VK_FORMAT_D32_SFLOAT;
            case D24_UNORM_S8_UINT -> VK10.VK_FORMAT_D24_UNORM_S8_UINT;
        };
    }
}
