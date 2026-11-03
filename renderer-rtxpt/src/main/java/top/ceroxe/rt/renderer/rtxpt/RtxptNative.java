package top.ceroxe.rt.renderer.rtxpt;
import java.nio.*;
import java.nio.file.*;

/** JNI declarations and runtime loading only. */
final class RtxptNative {
    static Path runtime() {
        return RuntimePath.VALUE;
    }
    private static final class RuntimePath { static final Path VALUE=resolveRuntime(); }
    private static Path resolveRuntime() {
        String override=System.getProperty("top.ceroxe.rt.rtxpt.runtime");
        Path p=override!=null?Path.of(override):RtxptRuntimeBundle.packaged()?RtxptRuntimeBundle.extract():Path.of("build/rtxpt-sdk/bin");
        p=p.toAbsolutePath().normalize();
        if(!Files.isDirectory(p.resolve("ShaderPrecompiled"))) throw new IllegalStateException("RTXPT runtime assets missing: "+p);
        return p;
    }
    static Path bridge() { return Path.of(System.getProperty("top.ceroxe.rt.rtxpt.bridge",runtime().resolve("rtrenderer_rtxpt.dll").toString())).toAbsolutePath().normalize(); }
    private static boolean loaded;
    static synchronized void load() {
        if(loaded)return;
        Path root=runtime(),dll=bridge();
        if(!Files.isRegularFile(dll))throw new IllegalStateException("Build the RTXPT JNI host: tools/Build-RtxptBridge.ps1");
        for(String dependency:new String[]{"sl.interposer.dll","NRD.dll","omm-lib.dll"})System.load(root.resolve(dependency).toString());
        System.load(dll.toString()); loaded=true;
    }
    static native long create(String runtime,String scene,int width,int height,boolean externalPresentation,boolean gpuTimingsEnabled,int maxFramesInFlight,int workerThreads,int shaderCompilerThreads,String[] options,int[] qualityInts,float[] qualityFloats);
    static native void prepare(String runtime);
    static native void diagnosticContext(long handle,long sequence,long revision);
    static native void diagnosticSubmitBegin(long handle,long sequence,long revision,long elapsedNanos);
    static native void diagnosticSubmitEnd(boolean failed);
    static native void diagnosticSubmitStage(int stage,long offsetNanos,long durationNanos,long items,long tasks);
    static native void diagnosticLevel(long handle,int level);
    static native long[] diagnosticSnapshot(long handle);
    static native long diagnosticMonitorToken(long handle);
    static native long[] diagnosticMonitorSnapshot(long token);
    static native String[] diagnosticGpuSnapshot(long handle);
    @FunctionalInterface interface Compositor { void compose(long[] frame); }
    static native void compositor(long handle,Compositor callback);
    static native void title(long handle,String text);
    static native long[] sharedFrame(long handle,long sequence);
    static native void retireSharedFrames(long handle);
    static native void resetHistory(long handle);
    static native int frame(long handle);
    static native void awaitIdle(long handle);
    static native int[] runtimeStatus(long handle);
    static native void pathQuality(long handle,int[] values,float[] constants);
    static native double[] pathQualityStatus(long handle);
    static native void overlay(long handle,String text);
    static native void overlayChoices(long handle,String[] labels,int selectedIndex);
    static native int overlaySelection(long handle);
    static native long[] gpuTiming(long handle);
    static native long[] generationTelemetry(long handle);
    static native double[] presentationStatus(long handle);
    static native double[] cameraState(long handle);
    static native void exposureCompensation(long handle,float stops);
    static native void camera(long handle,String pose,float verticalFovRadians);
    static native void loadScene(long handle,String path);
    static native void lighting(long handle,long[] ids,float[] records,float[] sky);
    static native void environment(long handle,float[] sun,float[] sky);
    static native void lightingDelta(long handle,long[] ids,float[] records);
    static native void lightingPatch(long handle,long[] ids,float[] records,long[] removals,float[] sun,float[] sky);
    static native long[] lightUpdateTelemetry(long handle);
    static native void sparseLighting(long handle,long[] ids,float[] records,long[] removals,boolean reset);
    static native long[] sparseLightingTelemetry(long handle);
    static native void hostSurfacePlan(long handle,int width,int height,int composition,int hostComponents,boolean motionIncludesJitter,long budget);
    static native long[] hostSurfaceAcquire(long handle,long sequence,long revision,long resourceGeneration,long viewIdentity,long time,long delta);
    static native void hostSurfaceInputAction(long handle,long generation,int slot,long ticket,int action);
    static native void hostSurfaceDiscard(long handle,long generation,long sequence);
    static native long[] hostSurfaceStatus(long handle);
    static native void hostSurfaceRead(long handle,long sequence,long revision,long resourceGeneration,long viewIdentity,long time,long delta);
    static native void hostSurfaceTriangles(long handle,String mesh,int[] mapping);
    static native void hostSurfaceMappingDirty(long handle);
    static native void hostSurfaceView(long handle,float[] matrices);
    static native void hostSurfaceWorkEnabled(long handle,boolean enabled);
    static native long[] hostSurfaceWork(long handle);
    static native void hostSurfaceRequireRetired(long handle);
    static native long[] hostSurfaceFrame(long handle);
    static native void hostSurfaceRelease(long handle,int slot,long generation,long sequence,boolean imported);
    static native boolean hostSurfaceCanPublish(long handle);
    static native void hostSurfaceOutputs(long handle,int signalMask);
    static native void hostSurfaceAugmentation(long handle,float[] parameters,int width,int height,int outputs,int colorDomain);
    static native void hostSurfaceCapture(long handle,int signal,String path);
    static native boolean hostSurfaceConsumersRetired(long handle);
    static native void nativeLighting(long handle,float[] parameters);
    static native void nativeLightingClock(long handle,long timeNanos,long deltaNanos);
    static native void nativeLightingAttributes(long handle,long generation,String[] instances,float[] attributes,float[] lookup,byte[] application);
    static native void nativeLightingApplicationPatch(long handle,long generation,int offset,byte[] data);
    static native long[] nativeLightingApplicationTelemetry(long handle);
    static native void nativeLightingEncoding(long handle,int encoding);
    /** Explicit diagnostic GPU encoder sweep; never called from the production frame path. */
    static native double[] nativeLightingCompactProbe(long handle);
    static native void nativeLightingActivateValidatedExtension(long handle,String manifest,String expectedHash,int signals,int applicationBytes);
    static native void nativeLightingClearAttributes(long handle);
    static native String[] nativeLightingExtensionValidate(String manifest,int signals,int bytes);
    static native void nativeLightingExtension(long handle,String manifest,String expectedHash);
    static native void nativeLightingWorkEnabled(long handle,boolean enabled);
    static native long[] nativeLightingWork(long handle);
    static native void nativeLightingBaseline(long handle,String geometry,float[] coordinates);
    static native long[] nativeLightingTelemetry(long handle);
    static native void nativeLightingCapture(long handle,String path,int signal);
    static native boolean nativeLightingCanPublish(long handle);
    static native boolean nativeLightingConsumersRetired(long handle);
    static native long[] nativeLightingFrame(long handle);
    static native void nativeLightingRelease(long handle,int slot,long generation,long sequence,boolean imported);
    static native void lightingPolicy(long handle,int continuity);
    static native void background(long handle,float[] color,boolean transparent);
    static native void resetWindowPollInterval(long handle);
    static native long[] executionTelemetry(long handle);
    static native void resources(long handle,String manifest);
    static native long[] resourceTelemetry(long handle);
    static native long[] lightSamplingTelemetry(long handle);
    static native void captureLinear(long handle,String path);
    static native long[] lightingTelemetry(long handle);
    static native long window(long handle);
    static native void resize(long handle,int width,int height);
    static native void windowState(long handle,int state);
    static native void settings(long handle,int maxBounces,int diffuseBounces,boolean reflex,
            int frameGenerationMode,int generatedFrames,int samplesPerPixel,int realtimeAa,
            int dlssMode,boolean standaloneDenoiser,boolean relaxDenoiser,boolean restirDi,boolean restirGi,boolean sharc,int exactOpticalInterfaces);
    static native void capture(long handle,String path);
    static native void captureSource(long handle,String path);
    static native void snapshotMaterials(long handle,String directory);
    static native void interiors(long handle,String[] names,FloatBuffer values);
    static native void materialIors(long handle,String[] names,FloatBuffer values);
    static native void transforms(long handle,String[] names,FloatBuffer rowMajorMatrices);
    static native void close(long handle);
}
