package top.ceroxe.mcvulkanrt.renderer.api;

/** A frame or transaction sequence did not advance beyond the last accepted submission. */
public final class SubmissionOrderException extends RendererException {
    public SubmissionOrderException(String message) {
        super(message);
    }
}
