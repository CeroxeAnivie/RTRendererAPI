package top.ceroxe.rt.renderer.api;

/** Synchronous consumption of a completed frame. Do not reenter the owning renderer. */
@FunctionalInterface
public interface FrameConsumer {
    void accept(FrameView frame);
}
