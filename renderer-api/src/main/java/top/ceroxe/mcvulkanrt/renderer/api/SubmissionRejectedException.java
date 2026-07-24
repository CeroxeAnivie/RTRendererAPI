package top.ceroxe.mcvulkanrt.renderer.api;

/** Recoverable capacity refusal; no logical or native submission state was retained. */
public final class SubmissionRejectedException extends RendererException {
    public SubmissionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
