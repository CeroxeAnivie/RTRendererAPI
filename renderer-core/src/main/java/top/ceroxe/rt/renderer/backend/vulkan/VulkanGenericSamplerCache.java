package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import top.ceroxe.rt.renderer.api.SamplerState;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Device-local immutable sampler cache. It is independent from texture generation lifetime. */
final class VulkanGenericSamplerCache implements AutoCloseable {
    private final VkDevice device;
    private final boolean anisotropyEnabled;
    private final float maximumAnisotropy;
    private final Map<Key, Long> handles = new HashMap<>();
    private boolean closed;

    VulkanGenericSamplerCache(VkDevice device, boolean anisotropyEnabled, float maximumAnisotropy) {
        this.device = Objects.requireNonNull(device, "device");
        if (!Float.isFinite(maximumAnisotropy) || maximumAnisotropy < 1.0F) {
            throw new IllegalArgumentException("maximumAnisotropy must be finite and at least one");
        }
        this.anisotropyEnabled = anisotropyEnabled;
        this.maximumAnisotropy = maximumAnisotropy;
    }

    long require(SamplerState state) {
        requireOpen();
        Key key = Key.of(Objects.requireNonNull(state, "state"));
        if (key.maximumAnisotropy() > 1.0F && !anisotropyEnabled) {
            throw new UnsupportedOperationException("sampler anisotropy is not enabled on the logical device");
        }
        if (key.maximumAnisotropy() > maximumAnisotropy) {
            throw new UnsupportedOperationException("requested sampler anisotropy " + key.maximumAnisotropy()
                    + " exceeds device limit " + maximumAnisotropy);
        }
        Long existing = handles.get(key);
        if (existing != null) return existing;
        long created = create(key);
        handles.put(key, created);
        return created;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (long handle : handles.values()) VK10.vkDestroySampler(device, handle, null);
        handles.clear();
    }

    private long create(Key key) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter(key.magFilter()))
                    .minFilter(filter(key.minFilter()))
                    .mipmapMode(mipFilter(key.mipFilter()))
                    .addressModeU(addressMode(key.addressU()))
                    .addressModeV(addressMode(key.addressV()))
                    .addressModeW(addressMode(key.addressW()))
                    .mipLodBias(0.0F)
                    .minLod(key.lodMinClamp())
                    .maxLod(key.lodMaxClamp())
                    .borderColor(VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .unnormalizedCoordinates(false)
                    .anisotropyEnable(key.maximumAnisotropy() > 1.0F)
                    .maxAnisotropy(key.maximumAnisotropy())
                    .compareEnable(key.compareOperation() != null)
                    .compareOp(key.compareOperation() == null ? VK10.VK_COMPARE_OP_ALWAYS : compare(key.compareOperation()));
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            VulkanFailures.check(VK10.vkCreateSampler(device, info, null, output), "vkCreateSampler.generic");
            return output.get(0);
        }
    }

    private static int filter(SamplerState.Filter value) {
        return value == SamplerState.Filter.NEAREST ? VK10.VK_FILTER_NEAREST : VK10.VK_FILTER_LINEAR;
    }

    private static int mipFilter(SamplerState.MipFilter value) {
        return value == SamplerState.MipFilter.NEAREST ? VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST : VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR;
    }

    private static int addressMode(SamplerState.AddressMode value) {
        return switch (value) {
            case CLAMP_TO_EDGE -> VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case REPEAT -> VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case MIRRORED_REPEAT -> VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
        };
    }

    private static int compare(SamplerState.CompareOperation value) {
        return switch (value) {
            case NEVER -> VK10.VK_COMPARE_OP_NEVER;
            case LESS -> VK10.VK_COMPARE_OP_LESS;
            case EQUAL -> VK10.VK_COMPARE_OP_EQUAL;
            case LESS_OR_EQUAL -> VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
            case GREATER -> VK10.VK_COMPARE_OP_GREATER;
            case NOT_EQUAL -> VK10.VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_OR_EQUAL -> VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
            case ALWAYS -> VK10.VK_COMPARE_OP_ALWAYS;
        };
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic sampler cache is closed");
    }

    private record Key(
            SamplerState.Filter minFilter,
            SamplerState.Filter magFilter,
            SamplerState.MipFilter mipFilter,
            SamplerState.AddressMode addressU,
            SamplerState.AddressMode addressV,
            SamplerState.AddressMode addressW,
            int lodMinBits,
            int lodMaxBits,
            int anisotropyBits,
            SamplerState.CompareOperation compareOperation
    ) {
        private static Key of(SamplerState value) {
            return new Key(value.minFilter(), value.magFilter(), value.mipFilter(), value.addressU(), value.addressV(),
                    value.addressW(), Float.floatToRawIntBits(value.lodMinClamp()), Float.floatToRawIntBits(value.lodMaxClamp()),
                    Float.floatToRawIntBits(value.maximumAnisotropy()), value.compareOperation());
        }

        private float lodMinClamp() { return Float.intBitsToFloat(lodMinBits); }
        private float lodMaxClamp() { return Float.intBitsToFloat(lodMaxBits); }
        private float maximumAnisotropy() { return Float.intBitsToFloat(anisotropyBits); }
    }
}
