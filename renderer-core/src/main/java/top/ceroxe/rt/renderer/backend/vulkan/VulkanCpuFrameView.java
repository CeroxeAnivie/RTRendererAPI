package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.*;

/** The buffer's confined arena is owned by the synchronous mapping scope. */
record VulkanCpuFrameView(long frameSequence, long renderedSceneRevision,
                          Optional<RenderResourceId> outputResource, int width, int height,
                          TextureFormat format, ByteBuffer storage) implements FrameView {
    VulkanCpuFrameView {
        Objects.requireNonNull(outputResource, "outputResource"); Objects.requireNonNull(format, "format");
        Objects.requireNonNull(storage, "storage");
        if (frameSequence < 0 || renderedSceneRevision < 0 || width <= 0 || height <= 0
                || storage.remaining() != Math.multiplyExact(Math.multiplyExact(width, height), format.bytesPerTexel())) {
            throw new IllegalArgumentException("invalid mapped frame metadata");
        }
    }
    @Override public FrameOrigin origin() { return FrameOrigin.TOP_LEFT; }
    @Override public int rowBytes() { return Math.multiplyExact(width, format.bytesPerTexel()); }
    @Override public int byteCount() { return storage.remaining(); }
    @Override public ByteBuffer pixels() { return storage.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); }
}
