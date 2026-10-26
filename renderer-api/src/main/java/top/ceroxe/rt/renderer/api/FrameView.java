package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Native-format pixels and exact provenance of a completed frame.
 * A view received by {@link FrameConsumer} is confined to that callback and its thread.
 * Retaining pixels requires an explicit {@link FrameSnapshot#copyOf(FrameView)}.
 */
public interface FrameView {
    long frameSequence();
    long renderedSceneRevision();
    Optional<RenderResourceId> outputResource();
    int width();
    int height();
    TextureFormat format();
    FrameOrigin origin();
    int rowBytes();
    int byteCount();
    /** Independent read-only cursor. Scoped native views become inaccessible after the callback. */
    ByteBuffer pixels();
}
