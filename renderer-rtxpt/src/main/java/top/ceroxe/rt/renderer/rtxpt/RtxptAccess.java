package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.Path;

/** Expert host boundary. Call on the renderer's creating thread. The host owns window lifetime. */
public interface RtxptAccess {
    /** Configure the upstream pipeline before first submission. */
    void configure(RtxptOptions options);
    /** Load an upstream scene directly, bypassing the Java retained scene adapter. */
    void loadNativeScene(Path scene);
    /** Native HWND; only valid after the first submission and until close. */
    long windowHandle();
    /** GPU render/present of the resident scene; false means the user closed the window. */
    boolean renderFrame();
    /** Explicit GPU drain for completed-frame measurement, outside the normal frame loop. */
    void awaitIdle();
    /** Actual native settings and internal resolution after rendering, including upstream fallbacks. */
    RuntimeStatus runtimeStatus();
    record RuntimeStatus(int renderWidth,int renderHeight,RtxptOptions.Reconstruction reconstruction,
        boolean standaloneDenoiser,boolean restirDi,boolean restirGi,boolean sharc,boolean frameGeneration) { }
    /** Explicit one-shot readback. Never called in the presentation or benchmark frame loop. */
    void capture(Path png);
    BridgeTimings bridgeTimings();
    record BridgeTimings(long frames, long frameNanos, long sceneExports, long sceneExportNanos,
            long transformBatches, long transformNanos) { }
}
