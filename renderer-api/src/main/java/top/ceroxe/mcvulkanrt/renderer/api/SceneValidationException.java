package top.ceroxe.mcvulkanrt.renderer.api;

/** A prospective scene generation is internally inconsistent and was not published. */
public final class SceneValidationException extends RendererException {
    public SceneValidationException(String message) {
        super(message);
    }

    public SceneValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
