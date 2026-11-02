package top.ceroxe.rt.renderer.rtxpt;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;

/**
 * Native RTXPT presentation with Vulkan UI, or explicit Vulkan presentation with 3D switching.
 * RTXPT's SDR output crosses shared GPU memory; Vulkan commands and UI remain Vulkan work.
 * This is stage composition, not split light transport or a union of arbitrary shader semantics.
 */
public final class RtxptComposedRenderer implements Renderer {
    private final Thread owner=Thread.currentThread();
    private static final VulkanFramePresenter.PerformanceSnapshot NO_PRESENTS=
        new VulkanFramePresenter.PerformanceSnapshot(0,0,0,0,0,0,0,0);
    private final RendererConfig vulkanConfiguration;
    private final VulkanFramePresenterConfig windowConfiguration;
    private Renderer vulkan;
    private final RtxptRenderer rtxpt;
    private VulkanFramePresenter presenter;
    private final boolean nativePresentation;
    private RendererBackendPolicy policy;
    private RendererBackendPolicy.Selection selection;
    private RendererSceneRouting routing;
    private List<FrameCompositionPlan.Layer> overlays=List.of();
    private boolean closed,vulkanScenePublished;
    private long sceneRevision,vulkanRevision=-1,sequence=-1,vulkanFrames;
    private int width,height;

    /** Opens RTXPT 3D and native presentation, with Vulkan reserved for independent work and UI. */
    public static RtxptComposedRenderer open() {
        return openRtxptPresentation(
            RendererConfig.builder().cpuFrameReadbackEnabled(false).gpuTimingsEnabled(true).build(),
            RendererConfig.pathTracedPresentation(),"RTRendererAPI - RTXPT + Vulkan UI");
    }

    /**
     * RTXPT owns final DXGI delivery and all requested SR/RR, FG/MFG and Reflex features.
     * Vulkan layers return through GPU memory before the upstream Present; its depth, motion
     * vectors and HUD-less source remain upstream-owned. Vulkan never initializes Streamline.
     */
    public static RtxptComposedRenderer openRtxptPresentation(RendererConfig vulkanConfiguration,
            RendererConfig rtxptConfiguration,String title) {
        // Native window extent comes from RenderFrameRequest; its delivery policy is upstream.
        var hidden=VulkanFramePresenterConfig.builder().title(title).initialExtent(1,1).visible(false).resizable(false).build();
        return open(true,RendererBackendPolicy.RTXPT_ONLY,vulkanConfiguration,rtxptConfiguration,hidden);
    }

    /**
     * Opens explicit ordinary/expert configurations. Vulkan Streamline features cannot initialize a
     * second global SDK beside RTXPT; select its SR/RR on the RTXPT configuration instead. DXGI
     * FG/MFG does not produce frames for this Vulkan swapchain and is rejected, never counted.
     */
    public static RtxptComposedRenderer openVulkanPresentation(RendererBackendPolicy policy,RendererConfig vulkanConfiguration,
            RendererConfig rtxptConfiguration,VulkanFramePresenterConfig window) {
        return open(false,policy,vulkanConfiguration,rtxptConfiguration,window);
    }
    private static RtxptComposedRenderer open(boolean nativePresentation,RendererBackendPolicy policy,RendererConfig vulkanConfiguration,
            RendererConfig rtxptConfiguration,VulkanFramePresenterConfig window) {
        Objects.requireNonNull(policy,"policy");Objects.requireNonNull(vulkanConfiguration,"vulkanConfiguration");
        Objects.requireNonNull(rtxptConfiguration,"rtxptConfiguration");Objects.requireNonNull(window,"window");
        RtxptInputContract.validateConfiguration(rtxptConfiguration);
        if(!nativePresentation&&(rtxptConfiguration.frameGeneration().preference().requested()||rtxptConfiguration.lowLatency().preference().requested()))
            throw new IllegalArgumentException("Composed Vulkan presentation requires RTXPT DXGI FG/MFG and Reflex disabled");
        if(vulkanConfiguration.cpuFrameReadbackEnabled())throw new IllegalArgumentException("Composition requires GPU-native Vulkan output");
        if(vulkanConfiguration.frameReconstruction().preference().requested()
                ||vulkanConfiguration.frameGeneration().preference().requested()
                ||vulkanConfiguration.lowLatency().preference().requested())
            throw new IllegalArgumentException("Only RTXPT owns Streamline in composed mode; vulkan SR/NIS/FG/Reflex must be disabled");
        // RTXPT must load its pinned Streamline modules before the optional vulkan provider's
        // bridge DLL; Windows reuses modules by basename within one process.
        RtxptRenderer rtxpt=new RtxptRenderer(rtxptConfiguration,!nativePresentation);
        if(nativePresentation)rtxpt.viewportTitle(window.title());
        var renderer=new RtxptComposedRenderer(policy,rtxpt,nativePresentation,vulkanConfiguration,window);
        try {
            // Native-only frames and upstream HUD need no second device, allocator or window.
            // An explicit Vulkan-final viewport must create its visible window immediately.
            if(!nativePresentation)renderer.ensureVulkan();
            return renderer;
        } catch(RuntimeException|Error failure) {
            try{rtxpt.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private RtxptComposedRenderer(RendererBackendPolicy policy,RtxptRenderer rtxpt,boolean nativePresentation,
            RendererConfig vulkanConfiguration,VulkanFramePresenterConfig windowConfiguration) {
        this.policy=policy;this.rtxpt=rtxpt;this.nativePresentation=nativePresentation;
        this.vulkanConfiguration=vulkanConfiguration;this.windowConfiguration=windowConfiguration;
    }
    private void ensureVulkan(){
        if(vulkan!=null)return;
        var opened=Renderer.open("vulkan-rt",vulkanConfiguration);
        try {
            var window=nativePresentation?VulkanFramePresenterConfig.builder()
                .title(windowConfiguration.title()+" [GPU compositor]")
                .initialExtent(windowConfiguration.initialWidth(),windowConfiguration.initialHeight())
                .visible(false).resizable(false).maximumFramesQueuedAhead(windowConfiguration.maximumFramesQueuedAhead()).build():windowConfiguration;
            var openedPresenter=VulkanFramePresenter.open(opened,window);
            // Publish only after both objects exist; a failed first use can be retried safely.
            vulkan=opened;presenter=openedPresenter;
        } catch(RuntimeException|Error failure) {
            try{opened.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private Renderer vulkanRenderer(){
        ready();ensureVulkan();return vulkan;
    }
    private void ready(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the composed viewport on its creating thread");if(closed)throw new IllegalStateException("Composed renderer closed");}
    /** Changes the next frame's exact selection policy without replacing the visible window. */
    public void backendPolicy(RendererBackendPolicy value){
        ready();Objects.requireNonNull(value,"policy");
        if(nativePresentation&&value!=RendererBackendPolicy.RTXPT_ONLY)
            throw new IllegalArgumentException("RTXPT final presentation owns its 3D guides; use the explicit Vulkan-presentation viewport for switching complete 3D backends");
        policy=value;
    }
    /** Returns the requested policy; actual selection is available after the next successful submit. */
    public RendererBackendPolicy backendPolicy(){return policy;}
    /** Returns actual successful-frame backend selection, empty before the first frame. */
    public Optional<RendererBackendPolicy.Selection> selection(){return Optional.ofNullable(selection);}
    /** Borrowed Vulkan command lane for independent GPU computation, graphics and UI resources. */
    public RendererCommandAccess vulkanCommands(){return vulkanRenderer();}
    /** Discovers a borrowed Vulkan extension; never close its owning renderer independently. */
    public <T> Optional<T> vulkanExtension(Class<T> type){Objects.requireNonNull(type);return vulkanRenderer().extension(type);}
    /** Borrows the sole visible presenter for events, HUD and explicit diagnostic capture. */
    public VulkanFramePresenter presenter(){
        ready();if(nativePresentation)throw new IllegalStateException("RTXPT owns the visible window; use its RtxptAccess extension");return presenter;
    }
    /** Vulkan GPU layers composed after complete RTXPT 3D; at most seven overlays. */
    public void overlays(List<FrameCompositionPlan.Layer> layers){
        ready();var next=List.copyOf(Objects.requireNonNull(layers,"layers"));
        if(next.size()>7)throw new IllegalArgumentException("At most seven overlays after the RTXPT base layer");
        if(!next.isEmpty())ensureVulkan();
        // Keep the bounded ring cached across UI toggles. No queue drain or resource
        // destruction here: applications can explicitly trim during an idle period.
        if(nativePresentation)rtxpt.compositor(next.isEmpty()?null:values->{
            try{presenter.composeD3D12SharedFrame(new top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame(
                values[0],values[1],values[2],(int)values[3],(int)values[4],values[5],values[6],values[7],values[8],values[9]),overlays);}
            catch(RuntimeException|Error failure){
                try{retireExternalFrames();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        });
        overlays=next;
    }
    /**
     * Releases cached cross-API images while native presentation has no overlays.
     * This explicit idle maintenance call waits for GPU consumers. Ordinary overlay
     * toggles retain the bounded cache to avoid stalls and repeated allocation.
     * Re-enabling overlays recreates only these temporary resources on demand.
     * @throws IllegalStateException if Vulkan owns presentation or overlays are enabled
     */
    public void trimIdleCompositionResources(){
        ready();
        if(!nativePresentation||!overlays.isEmpty())
            throw new IllegalStateException("Trim requires native presentation with no overlays; call during an idle period");
        retireExternalFrames();
    }
    /** Counts actual work per selected 3D backend, independently of final present timing. */
    public WorkSnapshot workSnapshot(){return new WorkSnapshot(rtxpt.bridgeTimings().frames(),vulkanFrames,
        presenter==null?NO_PRESENTS:presenter.performanceSnapshot());}
    /** Actual upstream rendering configuration; available after the first RTXPT frame. */
    public RtxptAccess.RuntimeStatus rtxptStatus(){ready();return rtxpt.runtimeStatus();}
    /** Explicit diagnostic source capture before Vulkan layers; never part of normal delivery. */
    public void captureRtxptSource(java.nio.file.Path png){ready();rtxpt.captureSource(Objects.requireNonNull(png,"png"));}
    /** Work counts do not include startup and do not infer frame generation. */
    public record WorkSnapshot(long rtxptFrames,long vulkanFrames,VulkanFramePresenter.PerformanceSnapshot presentation) { }

    @Override public SceneUpdateResult apply(SceneTransaction tx){
        ready();Objects.requireNonNull(tx,"transaction");
        RendererSceneRouting candidate;
        if(routing!=null)candidate=routing.updated(tx);
        else if(tx.reset())candidate=new RendererSceneRouting(tx);
        else candidate=new RendererSceneRouting(SceneTransaction.builder(0).resetScene().build()).updated(tx);
        var prepared=rtxpt.prepareScene(tx,false);
        // Preserve the active Vulkan delta fast path, but never partially commit the retained
        // scene if its backend rejects an update. Structural switches replay once on demand.
        if(vulkanScenePublished&&candidate==routing&&selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN) {
            vulkan.apply(tx);vulkanRevision=tx.revision();vulkanScenePublished=true;
        }
        var result=rtxpt.commitScene(prepared);routing=candidate;sceneRevision=tx.revision();return result;
    }
    @Override public FrameSubmissionResult submit(RenderFrameRequest frame){
        var attempt=trySubmit(frame);
        if(attempt instanceof FrameSubmitted accepted)return accepted.submission();
        var deferred=(FrameSubmissionDeferred)attempt;
        throw new SubmissionRejectedException(deferred.deferralReason(),deferred.detail());
    }
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest frame){
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
            try {
                result=rtxpt.submit(frame);
                if(!nativePresentation)presenter.presentD3D12SharedFrame(rtxpt.sharedFrame(frame.sequence()),overlays);
            } catch(RuntimeException|Error failure) {
                try{retireExternalFrames();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        } else {
            if(!vulkanScenePublished||vulkanRevision<sceneRevision) {
                var snapshot=rtxpt.sceneSnapshot();
                vulkan.apply(snapshot);vulkanRevision=snapshot.revision();vulkanScenePublished=true;
            }
            var submitted=switched?frame.toBuilder().resetTemporalHistory(HistoryResetReason.EXPLICIT_RESET).build():frame;
            // Retire completed presents before testing admission; preserve the Vulkan bounded
            // queue contract instead of converting recoverable backpressure into a hard failure.
            presenter.presentLatestFrame(overlays);
            var attempt=vulkan.trySubmit(submitted);
            if(attempt instanceof FrameSubmissionDeferred)return attempt;
            result=((FrameSubmitted)attempt).submission();presenter.presentLatestFrame(overlays);vulkanFrames++;
        }
        width=frame.width();height=frame.height();sequence=frame.sequence();selection=wanted;
        return new FrameSubmitted(result);
    }
    private void retireExternalFrames(){if(presenter!=null){presenter.retireD3D12SharedFrames();rtxpt.retireSharedFrames();}}
    @Override public <T> Optional<T> extension(Class<T> type){
        ready();Objects.requireNonNull(type,"extensionType");
        if(type.isInstance(this))return Optional.of(type.cast(this));
        if(type==VulkanFramePresenter.class)return nativePresentation?Optional.empty():Optional.of(type.cast(presenter));
        // Exposing the hidden host's renderFrame/window controls could publish unconsumed slots
        // or present a second window. The composed owner alone advances the native producer.
        if(type==RtxptAccess.class)return nativePresentation?Optional.of(type.cast(rtxpt)):Optional.empty();
        return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?vulkan.extension(type):rtxpt.extension(type);
    }
    @Override public Status status(){return closed?Status.CLOSED:selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?vulkan.status():rtxpt.status();}
    @Override public RendererHealth health(){return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?vulkan.health():rtxpt.health();}
    @Override public RendererDiagnostics diagnostics(){return selection!=null&&selection.backend()==RendererBackendPolicy.Backend.VULKAN?vulkan.diagnostics():rtxpt.diagnostics();}
    @Override public boolean readFrame(FrameConsumer consumer){ready();throw new UnsupportedOperationException("Composed viewport is GPU-native; use explicit presenter capture for diagnostics");}
    @Override public Optional<FrameSnapshot> pollFrame(){ready();throw new UnsupportedOperationException("Composed viewport is GPU-native");}
    @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout){ready();Objects.requireNonNull(timeout);return pollFrame();}
    @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx){Objects.requireNonNull(tx);return vulkanRenderer().submitResources(tx);}
    @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key){Objects.requireNonNull(key);return vulkanRenderer().resourceResidencyEvidence(key);}
    @Override public RenderingSemanticCapabilities renderingSemanticCapabilities(){
        var builder=RenderingSemanticCapabilities.builder();
        vulkanRenderer().renderingSemanticCapabilities().entries().forEach(builder::feature);
        return builder.feature(RenderingSemanticCapabilities.Feature.COMBINED_WORKLOADS,
            new RenderingSemanticCapabilities.Entry(RenderingSemanticCapabilities.Status.UNSUPPORTED,
                "Use explicit Vulkan commands and overlays; a Vulkan combined workload cannot inject commands into RTXPT light transport")).build();
    }
    @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx){Objects.requireNonNull(tx);return vulkanRenderer().submitCommands(tx);}
    @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long id){return vulkanRenderer().commandExecutionEvidence(id);}
    @Override public void close(){
        if(closed)return;ready();Throwable failure=null;
        // A failed consumer drain is retryable: do not free borrowed source handles on failure.
        retireExternalFrames();
        if(presenter!=null)try{presenter.close();}catch(RuntimeException|Error e){failure=e;}
        try{rtxpt.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(vulkan!=null)try{vulkan.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        closed=true;routing=null;overlays=List.of();
        if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
    }
}
