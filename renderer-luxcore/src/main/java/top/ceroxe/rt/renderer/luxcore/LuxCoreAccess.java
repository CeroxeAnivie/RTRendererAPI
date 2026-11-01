package top.ceroxe.rt.renderer.luxcore;

import java.nio.ByteBuffer;

/** Optional native SDL escape hatch: all advanced algorithms remain upstream-owned. */
public interface LuxCoreAccess {
    /** Apply official LuxCore scene properties in one upstream scene-edit transaction. */
    void sceneProperties(String properties);
    /** Override official render configuration before the first submit. */
    void configurationProperties(String properties);
    /** Read linear, bottom-left RGBA32F directly, without Java pixel processing. */
    double readLinearRgba(ByteBuffer destination);
    /** Actual completed film samples; divide its delta by pixel count and elapsed seconds for SPP/s. */
    double totalSamples();
    /** Upstream statistics, including actual selected devices and engine. */
    String nativeStatistics();
    /** Export the complete upstream config, scene, meshes and images for independent LuxCore rendering. */
    void exportScene(java.nio.file.Path directory);
    /** Lifetime bridge counters; phases overlap asynchronous upstream rendering and must not be added to GPU time. */
    Timings bridgeTimings();
    record Timings(long sceneEdits, long sceneEditNanos, long submits, long submitNanos,
                   long filmReads, long filmReadNanos, long uploadedMeshes, long uploadedImages) { }
}
