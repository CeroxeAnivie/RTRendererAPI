package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Submission-local overlay: native recording never mutates persistent layout state before submit succeeds. */
final class VulkanGenericTextureLayoutUpdates {
    private final Map<Key, Integer> values = new HashMap<>();
    private boolean committed;

    int layout(VulkanGenericResourceRegistry.TextureRecord record, TextureAspect aspect, int mipLevel, int arrayLayer) {
        Objects.requireNonNull(record, "record");
        return layout(record.layouts(), aspect, mipLevel, arrayLayer);
    }

    int layout(VulkanGenericTextureLayoutState persistent, TextureAspect aspect, int mipLevel, int arrayLayer) {
        requireOpen();
        Key key = new Key(persistent, aspect, mipLevel, arrayLayer);
        return values.getOrDefault(key, persistent.layout(aspect, mipLevel, arrayLayer));
    }

    void set(VulkanGenericResourceRegistry.TextureRecord record, TextureSubresourceRange range, int layout) {
        Objects.requireNonNull(record, "record");
        set(record.layouts(), range, layout);
    }

    void set(VulkanGenericTextureLayoutState persistent, TextureSubresourceRange range, int layout) {
        requireOpen();
        Objects.requireNonNull(range, "range");
        for (int mip = range.baseMipLevel(); mip < range.mipEndExclusive(); mip++) {
            for (int layer = range.baseArrayLayer(); layer < range.arrayLayerEndExclusive(); layer++) {
                values.put(new Key(persistent, range.aspect(), mip, layer), layout);
            }
        }
    }

    void commit() {
        requireOpen();
        for (Map.Entry<Key, Integer> entry : values.entrySet()) {
            Key key = entry.getKey();
            key.persistent().set(new TextureSubresourceRange(key.aspect(), key.mipLevel(), 1, key.arrayLayer(), 1),
                    entry.getValue());
        }
        values.clear();
        committed = true;
    }

    private void requireOpen() {
        if (committed) throw new IllegalStateException("texture layout overlay has already been committed");
    }

    private record Key(
            VulkanGenericTextureLayoutState persistent,
            TextureAspect aspect,
            int mipLevel,
            int arrayLayer
    ) {
        private Key {
            Objects.requireNonNull(persistent, "persistent");
            Objects.requireNonNull(aspect, "aspect");
            if (mipLevel < 0 || arrayLayer < 0) throw new IllegalArgumentException("subresource coordinates must be non-negative");
        }
    }
}
