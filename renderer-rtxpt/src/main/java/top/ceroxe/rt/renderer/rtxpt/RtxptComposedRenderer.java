package top.ceroxe.rt.renderer.rtxpt;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;

/**
 * One visible Vulkan viewport with either complete RTXPT 3D or complete Vulkan 3D.
 * RTXPT's SDR output crosses shared GPU memory; legacy commands and UI remain Vulkan work.
 * This is stage composition, not split light transport or a union of arbitrary shader semantics.
 */
public final class RtxptComposedRenderer implements Renderer {
    private final Thread owner=Thread.currentThread();
    private final Renderer legacy;
    private final RtxptRenderer rtxpt;
    private final VulkanFramePresenter presenter;
    private RendererBackendPolicy policy;
    private RendererBackendPolicy.Selection selection;
    private RendererSceneRouting routing;
    private List<FrameCompositionPlan.Layer> overlays=List.of();
    private boolean closed,legacyScenePublished;
    private long sceneRevision,legacyRevision=-1,sequence=-1,rtxptFrames,legacyFrames;
    private int width,height;

    /** Opens one window; default automatic 3D selection, upstream RR and native completed frames. */
    public static RtxptComposedRenderer open(VulkanFramePresenterConfig window) {
        return open(RendererBackendPolicy.RTXPT_FOR_MEDIA,
            RendererConfig.builder().cpuFrameReadbackEnabled(false).gpuTimingsEnabled(true).build(),
            RendererConfig.pathTracedPresentation().copyBuilder().lowLatency(LowLatencyOptions.disabled()).build(),window);
    }

    /**
     * Opens explicit ordinary/expert configurations. Legacy Streamline features cannot initialize a
     * second global SDK beside RTXPT; select its SR/RR on the RTXPT configuration instead. DXGI
     * FG/MFG does not produce frames for this Vulkan swapchain and is rejected, never counted.
     */
    public static RtxptComposedRenderer open(RendererBackendPolicy policy,RendererConfig legacyConfiguration,
            RendererConfig rtxptConfiguration,VulkanFramePresenterConfig window) {
        Objects.requireNonNull(policy,"policy");Objects.requireNonNull(legacyConfiguration,"legacyConfiguration");
        Objects.requireNonNull(rtxptConfiguration,"rtxptConfiguration");Objects.requireNonNull(window,"window");
        RtxptInputContract.validateConfiguration(rtxptConfiguration);
        if(rtxptConfiguration.frameGeneration().preference().requested()||rtxptConfiguration.lowLatency().preference().requested())
            throw new IllegalArgumentException("Composed Vulkan presentation requires RTXPT DXGI FG/MFG and Reflex disabled");
        if(legacyConfiguration.cpuFrameReadbackEnabled())throw new IllegalArgumentException("Composition requires GPU-native Vulkan output");
        if(legacyConfiguration.frameReconstruction().preference().requested()
                ||legacyConfiguration.frameGeneration().preference().requested()
                ||legacyConfiguration.lowLatency().preference().requested())
            throw new IllegalArgumentException("Only RTXPT owns Streamline in composed mode; legacy SR/NIS/FG/Reflex must be disabled");
        // RTXPT must load its pinned Streamline modules before the optional legacy provider's
        // bridge DLL; Windows reuses modules by basename within one process.
        RtxptRenderer rtxpt=new RtxptRenderer(rtxptConfiguration,true);
        Renderer legacy=null;
        VulkanFramePresenter presenter=null;
        try {
            legacy=Renderer.open("vulkan-rt",legacyConfiguration);
            presenter=VulkanFramePresenter.open(legacy,window);
            return new RtxptComposedRenderer(policy,legacy,rtxpt,presenter);
        } catch(RuntimeException|Error failure) {
            if(presenter!=null)try{presenter.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            if(legacy!=null)try{legacy.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            try{rtxpt.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private RtxptComposedRenderer(RendererBackendPolicy policy,Renderer legacy,RtxptRenderer rtxpt,VulkanFramePresenter presenter) {
        this.policy=policy;this.legacy=legacy;this.rtxpt=rtxpt;this.presenter=presenter;
    }
    private void ready(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the composed viewport on its creating thread");if(closed)throw new IllegalStateException("Composed renderer closed");}
    /** Changes the next frame's exact selection policy without replacing the visible window. */
    public void backendPolicy(RendererBackendPolicy value){ready();policy=Objects.requireNonNull(value,"policy");}
    /** Returns the requested policy; actual selection is available after the next successful submit. */
    public RendererBackendPolicy backendPolicy(){return policy;}
    /** Returns actual successful-frame backend selection, empty before the first frame. */
    public Optional<RendererBackendPolicy.Selection> selection(){return Optional.ofNullable(selection);}
    /** Borrowed legacy command lane for independent GPU computation, graphics and UI resources. */
    public RendererCommandAccess legacyCommands(){ready();return legacy;}
    /** Discovers a borrowed legacy extension; never close its owning renderer independently. */
    public <T> Optional<T> legacyExtension(Class<T> type){ready();return legacy.extension(Objects.requireNonNull(type));}
    /** Borrows the sole visible presenter for events, HUD and explicit diagnostic capture. */
    public VulkanFramePresenter presenter(){ready();return presenter;}
    /** Existing legacy GPU layers composed after complete RTXPT 3D; at most seven overlays. */
    public void overlays(List<FrameCompositionPlan.Layer> layers){
        ready();var next=List.copyOf(Objects.requireNonNull(layers,"layers"));
        if(next.size()>7)throw new IllegalArgumentException("At most seven overlays after the RTXPT base layer");
        overlays=next;
    }
    /** Counts actual work per selected 3D backend, independently of final present timing. */
    public WorkSnapshot workSnapshot(){return new WorkSnapshot(rtxptFrames,legacyFrames,presenter.performanceSnapshot());}
    /** Actual upstream rendering configuration; available after the first RTXPT frame. */
    public RtxptAccess.RuntimeStatus rtxptStatus(){ready();return rtxpt.runtimeStatus();}
    /** Explicit diagnostic source capture before Vulkan layers; never part of normal delivery. */
    public void captureRtxptSource(java.nio.file.Path png){ready();rtxpt.capture(Objects.requireNonNull(png,"png"));}
    /** Work counts do not include startup and do not infer frame generation. */
    public record WorkSnapshot(long rtxptFrames,long legacyFrames,VulkanFramePresenter.PerformanceSnapshot presentation) { }

    @Override public SceneUpdateResult apply(SceneTransaction tx){
        ready();Objects.requireNonNull(tx,"transaction");
        RendererSceneRouting candidate;
        if(routing!=null)candidate=routing.updated(tx);
        else if(tx.reset())candidate=new RendererSceneRouting(tx);
        else candidate=new RendererSceneRouting(SceneTransaction.builder(0).resetScene().build()).updated(tx);
        var prepared=rtxpt.prepareScene(tx,false);
        // Preserve the active Vulkan delta fast path, but never partially commit the retained
        // scene if its backend rejects an update. Structural switches replay once on demand.
        if(legacyScenePublished&&candidate==routing&&selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN) {
            legacy.apply(tx);legacyRevision=tx.revision();legacyScenePublished=true;
        }
        var result=rtxpt.commitScene(prepared);routing=candidate;sceneRevision=tx.revision();return result;
    }
    @Override public FrameSubmissionResult submit(RenderFrameRequest frame){
        ready();Objects.requireNonNull(frame,"frame");
        if(frame.sequence()<=sequence)throw new IllegalArgumentException("Frame sequence must increase across backend switches");
        if(routing==null)routing=new RendererSceneRouting(rtxpt.sceneSnapshot());
        var wanted=routing.select(policy,frame);
        // A caller cannot force unsupported media onto Vulkan and silently lose their optics.
        if(wanted.backend()==RendererBackendPolicy.Backend.VULKAN&&wanted.mediaPresent())
            throw new IllegalArgumentException("This complete scene contains optical media; Vulkan cannot render the managed medium contract");
        boolean switched=selection!=null&&selection.backend()!=wanted.backend();
        if(switched || (width!=0&&(width!=frame.width()||height!=frame.height())))retireExternalFrames();
        FrameSubmissionResult result;
        if(wanted.backend()==RendererBackendPolicy.Backend.RTXPT) {
            if(switched)rtxpt.resetHistory();
            result=rtxpt.submit(frame);
            rtxptFrames++;
            try {
                var shared=rtxpt.sharedFrame(frame.sequence());
                presenter.presentD3D12SharedFrame(shared,overlays);
            } catch(RuntimeException|Error failure) {
                try{retireExternalFrames();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        } else {
            if(!legacyScenePublished||legacyRevision<sceneRevision) {
                var snapshot=rtxpt.sceneSnapshot();
                legacy.apply(snapshot);legacyRevision=snapshot.revision();legacyScenePublished=true;
            }
            var submitted=switched?frame.toBuilder().resetTemporalHistory(HistoryResetReason.EXPLICIT_RESET).build():frame;
            result=legacy.submit(submitted);presenter.presentLatestFrame(overlays);legacyFrames++;
        }
        width=frame.width();height=frame.height();sequence=frame.sequence();selection=wanted;
        return result;
    }
    private void retireExternalFrames(){presenter.retireD3D12SharedFrames();rtxpt.retireSharedFrames();}
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest frame){return new FrameSubmitted(submit(frame));}
    @Override public <T> Optional<T> extension(Class<T> type){
        ready();Objects.requireNonNull(type,"extensionType");
        if(type.isInstance(this))return Optional.of(type.cast(this));
        if(type==VulkanFramePresenter.class)return Optional.of(type.cast(presenter));
        // Exposing the hidden host's renderFrame/window controls could publish unconsumed slots
        // or present a second window. The composed owner alone advances the native producer.
        if(type==RtxptAccess.class)return Optional.empty();
        return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?legacy.extension(type):rtxpt.extension(type);
    }
    @Override public Status status(){return closed?Status.CLOSED:selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?legacy.status():rtxpt.status();}
    @Override public RendererHealth health(){return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?legacy.health():rtxpt.health();}
    @Override public RendererDiagnostics diagnostics(){return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?legacy.diagnostics():rtxpt.diagnostics();}
    @Override public boolean readFrame(FrameConsumer consumer){ready();throw new UnsupportedOperationException("Composed viewport is GPU-native; use explicit presenter capture for diagnostics");}
    @Override public Optional<FrameSnapshot> pollFrame(){ready();throw new UnsupportedOperationException("Composed viewport is GPU-native");}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout){ready();Objects.requireNonNull(timeout);return pollFrame();}
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx){ready();return legacy.submitResources(tx);}
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key){ready();return legacy.resourceResidencyEvidence(key);}
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities(){
        var builder=RenderingSemanticCapabilities.builder();
        legacy.renderingSemanticCapabilities().entries().forEach(builder::feature);
        return builder.feature(RenderingSemanticCapabilities.Feature.COMBINED_WORKLOADS,
            new RenderingSemanticCapabilities.Entry(RenderingSemanticCapabilities.Status.UNSUPPORTED,
                "Use explicit legacy commands and overlays; a legacy combined workload cannot inject commands into RTXPT light transport")).build();
    }
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx){ready();return legacy.submitCommands(tx);}
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long id){ready();return legacy.commandExecutionEvidence(id);}
    @Override public void close(){
        if(closed)return;ready();Throwable failure=null;
        // A failed consumer drain is retryable: do not free borrowed source handles on failure.
        retireExternalFrames();
        try{presenter.close();}catch(RuntimeException|Error e){failure=e;}
        try{rtxpt.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{legacy.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        closed=true;
        if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
    }
}
