package top.ceroxe.mcvulkanrt.renderer.api;

/** A scene or frame revision violates the renderer's monotonic ordering contract. */
public final class SceneRevisionException extends RendererException {
    public SceneRevisionException(String message) {
        super(message);
    }
}
