package top.ceroxe.mcvulkanrt.renderer.api;

/** Base class for renderer lifecycle, admission, and native backend failures. */
public class RendererException extends RuntimeException {
    public RendererException(String message) {
        super(message);
    }

    public RendererException(String message, Throwable cause) {
        super(message, cause);
    }
}
