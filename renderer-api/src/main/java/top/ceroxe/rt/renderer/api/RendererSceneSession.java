package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * One scene, one fixed renderer and one owned lifecycle. The ordinary factory chooses defaults
 * for each backend; the expert factory accepts separate complete configurations. Selection and
 * initialization happen once. All rendering, windows, features and resources stay with the chosen
 * provider. A category change under {@link RendererBackendPolicy#RTXPT_FOR_MEDIA} requires a new
 * session; an existing window, frame consumer or expert extension is never silently replaced.
 */
public final class RendererSceneSession implements Renderer {
    private final Renderer delegate;
    private final RendererBackendPolicy.Selection selection;
    private final FrameSubmissionResult initialSubmission;
    private final Thread owner=Thread.currentThread();
    private RendererSceneRouting routing;

    private RendererSceneSession(Renderer delegate,RendererBackendPolicy.Selection selection,
            RendererSceneRouting routing,FrameSubmissionResult initialSubmission) {
        this.delegate=delegate;this.selection=selection;this.routing=routing;this.initialSubmission=initialSubmission;
    }

    /**
     * Opens, publishes the complete initial scene and submits its first frame. Vulkan uses the
     * ordinary CPU-readable preset; RTXPT uses its GPU-window path-traced preset (FG off).
     * @param policy exact provider policy
     * @param initialScene complete reset scene, with all dependencies
     * @param firstFrame first submitted frame, including frame-local primitives
     * @return owned session; subsequent frames must advance beyond {@code firstFrame.sequence()}
     */
    public static RendererSceneSession open(RendererBackendPolicy policy,SceneTransaction initialScene,
            RenderFrameRequest firstFrame) {
        return open(policy,initialScene,firstFrame,RendererConfig.recommended(),RendererConfig.pathTracedPresentation());
    }

    /**
     * Opens a scene using only the selected backend's configuration. No GPU feature, output format
     * or unsupported expert control is rewritten to force a match. No provider fallback occurs.
     * @param policy exact provider policy
     * @param initialScene complete reset scene
     * @param firstFrame first submitted frame
     * @param vulkanConfiguration full policy used only when Vulkan is selected
     * @param rtxptConfiguration full policy used only when RTXPT is selected
     * @return owned fixed-provider session
     */
    public static RendererSceneSession open(RendererBackendPolicy policy,SceneTransaction initialScene,
            RenderFrameRequest firstFrame,RendererConfig vulkanConfiguration,RendererConfig rtxptConfiguration) {
        return open(policy,initialScene,firstFrame,vulkanConfiguration,rtxptConfiguration,Renderer::open);
    }

    static RendererSceneSession open(RendererBackendPolicy policy,SceneTransaction initialScene,
            RenderFrameRequest firstFrame,RendererConfig vulkanConfiguration,RendererConfig rtxptConfiguration,
            BiFunction<String,RendererConfig,Renderer> factory) {
        Objects.requireNonNull(policy,"policy");Objects.requireNonNull(firstFrame,"firstFrame");
        Objects.requireNonNull(vulkanConfiguration,"vulkanConfiguration");Objects.requireNonNull(rtxptConfiguration,"rtxptConfiguration");
        Objects.requireNonNull(factory,"factory");
        var state=new RendererSceneRouting(initialScene);
        if(firstFrame.minimumSceneRevision()>initialScene.revision())
            throw new IllegalArgumentException("The complete initial scene does not satisfy the first frame's minimum revision");
        var selected=state.select(policy,firstFrame);
        var config=selected.backend()==RendererBackendPolicy.Backend.RTXPT?rtxptConfiguration:vulkanConfiguration;
        var renderer=Objects.requireNonNull(factory.apply(selected.backend().providerId(),config),"opened renderer");
        try {
            renderer.apply(initialScene);
            var submitted=renderer.submit(firstFrame);
            return new RendererSceneSession(renderer,selected,state,submitted);
        } catch(RuntimeException|Error failure) {
            try { renderer.close(); } catch(RuntimeException|Error closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Returns the exact provider and original reason; selection stays fixed until close. */
    public RendererBackendPolicy.Selection selection() { return selection; }
    /** Returns admission evidence for the frame already submitted by {@code open}. */
    public FrameSubmissionResult initialSubmission() { return initialSubmission; }

    @Override public SceneUpdateResult apply(SceneTransaction transaction) {
        requireSubmissionThread();
        Objects.requireNonNull(transaction,"transaction");
        if(selection.policy()!=RendererBackendPolicy.RTXPT_FOR_MEDIA)return delegate.apply(transaction);
        var next=routing.updated(transaction);
        // Check newly introduced persistent optical work before the provider can mutate state.
        // Frame-local primitives are checked against the next submitted batch, never a stale one.
        if(selection.backend()==RendererBackendPolicy.Backend.VULKAN&&next.hasMedia(FramePrimitiveBatch.empty()))
            throw categoryChange(RendererBackendPolicy.Backend.RTXPT);
        var result=delegate.apply(transaction);
        routing=next;
        return result;
    }

    private void checkFrame(RenderFrameRequest frame) {
        requireSubmissionThread();
        Objects.requireNonNull(frame,"frame");
        if(selection.policy()==RendererBackendPolicy.RTXPT_FOR_MEDIA) {
            var wanted=routing.select(selection.policy(),frame).backend();
            if(wanted!=selection.backend())throw categoryChange(wanted);
        }
    }

    private void requireSubmissionThread() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Submit scene sessions on their creating thread");
        if(delegate.status()==Status.CLOSED)throw new IllegalStateException("Scene session is closed");
    }

    private IllegalStateException categoryChange(RendererBackendPolicy.Backend required) {
        return new IllegalStateException("This scene now requires "+required.providerId()+" under RTXPT_FOR_MEDIA; close the "
            +selection.backend().providerId()+" scene session and open a new session with a complete scene and first frame");
    }

    @Override public FrameSubmissionResult submit(RenderFrameRequest frame) { checkFrame(frame);return delegate.submit(frame); }
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest frame) { checkFrame(frame);return delegate.trySubmit(frame); }
    @Override public <T> Optional<T> extension(Class<T> type) {
        Objects.requireNonNull(type,"extensionType");
        return type.isInstance(this)?Optional.of(type.cast(this)):delegate.extension(type);
    }
    @Override public Status status() { return delegate.status(); }
    @Override public RendererHealth health() { return delegate.health(); }
    @Override public RendererDiagnostics diagnostics() { return delegate.diagnostics(); }
    @Override public boolean readFrame(FrameConsumer consumer) { return delegate.readFrame(consumer); }
    @Override public Optional<FrameSnapshot> pollFrame() { return delegate.pollFrame(); }
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout) throws InterruptedException { return delegate.awaitFrame(timeout); }
    @Override public WorkloadExecutionEvidence submitWorkload(RenderWorkload workload) {
        Objects.requireNonNull(workload,"workload").sceneFrame().ifPresent(this::checkFrame);
        return delegate.submitWorkload(workload);
    }
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction transaction) { return delegate.submitResources(transaction); }
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey generation) { return delegate.resourceResidencyEvidence(generation); }
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities() { return delegate.renderingSemanticCapabilities(); }
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction transaction) { return delegate.submitCommands(transaction); }
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long sequence) { return delegate.commandExecutionEvidence(sequence); }
    @Override public void close() { delegate.close(); }
    @Override public CompletionStage<Void> closeAsync() { return delegate.closeAsync(); }
    @Override public boolean awaitClosed(Duration timeout) throws InterruptedException { return delegate.awaitClosed(timeout); }
}
