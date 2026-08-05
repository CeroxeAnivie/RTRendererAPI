package compatibility;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.SceneTransaction;

/**
 * Consumer compiled against the previous formal artifact and executed with only the current API.
 *
 * <p>APIs introduced after the baseline must not appear in static bytecode. Reflection verifies
 * that a new default lifecycle method dispatches on an old provider implementation without
 * {@code AbstractMethodError}.</p>
 */
public final class PreviousApiConsumer implements RayTracingRenderer {
    private boolean closed;

    private PreviousApiConsumer() {
    }

    public static void main(String[] arguments) throws Exception {
        PreviousApiConsumer consumer = new PreviousApiConsumer();
        RayTracingRenderer renderer = consumer;
        if (renderer.extension(RayTracingRenderer.class).orElseThrow() != renderer) {
            throw new AssertionError("previous consumer lost default extension dispatch");
        }

        CompletionStage<?> close = invokeCurrentCloseAsync(renderer);
        close.toCompletableFuture().join();
        if (!consumer.closed) {
            throw new AssertionError("current closeAsync did not dispatch to the previous close implementation");
        }
        System.out.println("PreviousApiConsumer passed");
    }

    private static CompletionStage<?> invokeCurrentCloseAsync(RayTracingRenderer renderer) throws Exception {
        try {
            return (CompletionStage<?>) RayTracingRenderer.class.getMethod("closeAsync").invoke(renderer);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("closeAsync failed", cause);
        }
    }

    @Override
    public Status status() {
        return closed ? Status.CLOSED : Status.READY;
    }

    @Override
    public RendererHealth health() {
        throw new UnsupportedOperationException("not needed by compatibility fixture");
    }

    @Override
    public SceneUpdateResult apply(SceneTransaction transaction) {
        throw new UnsupportedOperationException("not needed by compatibility fixture");
    }

    @Override
    public FrameSubmissionResult submit(RenderFrameRequest request) {
        throw new UnsupportedOperationException("not needed by compatibility fixture");
    }

    @Override
    public Optional<CpuFrame> pollLatestCpuFrame() {
        return Optional.empty();
    }

    @Override
    public RendererDiagnostics diagnostics() {
        throw new UnsupportedOperationException("not needed by compatibility fixture");
    }

    @Override
    public void close() {
        closed = true;
    }
}
