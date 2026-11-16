package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.*;

/** An official session exposes only the original renderer and its observations. */
final class RtxptOfficialRenderer implements Renderer, RtxptAccess {
    private final RtxptRenderer renderer;
    RtxptOfficialRenderer(RendererConfig config,boolean hidden){renderer=new RtxptRenderer(config,hidden,true);}
    @Override public <T> Optional<T> extension(Class<T> type){
        Objects.requireNonNull(type);
        return type.isInstance(this)?Optional.of(type.cast(this)):Optional.empty();
    }
    @Override public Status status(){return renderer.status();}
    @Override public RendererDiagnostics diagnostics(){return renderer.diagnostics();}
    @Override public SceneUpdateResult apply(SceneTransaction transaction){return renderer.apply(transaction);}
    @Override public FrameSubmissionResult submit(RenderFrameRequest request){return renderer.submit(request);}
    @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest request){return renderer.trySubmit(request);}
    @Override public void close(){renderer.close();}
    @Override public void diagnosticLevel(RtxptDiagnostics.Level level){renderer.diagnosticLevel(level);}
    @Override public RtxptDiagnostics.Snapshot diagnosticSnapshot(long after){return renderer.diagnosticSnapshot(after);}
    @Override public RtxptDiagnostics.Monitor diagnosticMonitor(){return renderer.diagnosticMonitor();}
    @Override public RtxptDiagnostics.NativeMonitor nativeDiagnosticMonitor(){return renderer.nativeDiagnosticMonitor();}
    @Override public void loadNativeScene(Path path){renderer.loadNativeScene(path);}
    @Override public long windowHandle(){return renderer.windowHandle();}
    @Override public void setWindowTitle(String title){renderer.setWindowTitle(title);}
    @Override public void setExposureOffset(float value){renderer.setExposureOffset(value);}
    @Override public PresentationState presentationState(){return renderer.presentationState();}
    @Override public ExposureState exposureState(){return renderer.exposureState();}
    @Override public WindowState windowState(){return renderer.windowState();}
    @Override public Denoiser denoiserState(){return renderer.denoiserState();}
    @Override public RenderingState renderingState(){return renderer.renderingState();}
    @Override public ExecutionState executionState(){return renderer.executionState();}
    @Override public String frameStatistics(){return renderer.frameStatistics();}
    @Override public void setOverlayText(String text){renderer.setOverlayText(text);}
    @Override public void setOverlayChoices(List<String> choices,int selected){renderer.setOverlayChoices(choices,selected);}
    @Override public int overlaySelection(){return renderer.overlaySelection();}
    @Override public void awaitIdle(){renderer.awaitIdle();}
    @Override public void capture(Path path){renderer.capture(path);}
}
