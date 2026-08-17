package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Submission-local overlay: native recording never mutates persistent layout state before submit succeeds. */
final class VulkanGenericTextureLayoutUpdates {
    private final Map<Key, Integer> values = new HashMap<>();

    int layout(VulkanGenericResourceRegistry.TextureRecord record, TextureAspect aspect, int mipLevel, int arrayLayer) {
        Key key = new Key(record, aspect, mipLevel, arrayLayer);
        return values.getOrDefault(key, record.layouts().layout(aspect, mipLevel, arrayLayer));
    }

    void set(VulkanGenericResourceRegistry.TextureRecord record, TextureSubresourceRange range, int layout) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(range, "range");
        for (int mip = range.baseMipLevel(); mip < range.mipEndExclusive(); mip++) {
            for (int layer = range.baseArrayLayer(); layer < range.arrayLayerEndExclusive(); layer++) {
                values.put(new Key(record, range.aspect(), mip, layer), layout);
            }
        }
    }

    void commit() {
        for (Map.Entry<Key, Integer> entry : values.entrySet()) {
            Key key = entry.getKey();
            key.record().layouts().set(new TextureSubresourceRange(key.aspect(), key.mipLevel(), 1, key.arrayLayer(), 1),
                    entry.getValue());
        }
    }

    private record Key(
            VulkanGenericResourceRegistry.TextureRecord record,
            TextureAspect aspect,
            int mipLevel,
            int arrayLayer
    ) {
        private Key {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(aspect, "aspect");
            if (mipLevel < 0 || arrayLayer < 0) throw new IllegalArgumentException("subresource coordinates must be non-negative");
        }
    }
}
