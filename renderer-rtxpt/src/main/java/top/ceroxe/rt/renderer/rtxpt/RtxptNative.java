package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runtime loading and the external adapter JNI boundary. */
final class RtxptNative {
    static Path runtime(){return RtxptRuntime.binDirectory();}
    static Path bridge(){return Path.of(System.getProperty("top.ceroxe.rt.rtxpt.bridge",runtime().resolve("rtrenderer_rtxpt.dll").toString())).toAbsolutePath().normalize();}
    private static boolean loaded;
    static synchronized void load(){
        if(loaded)return;
        var root=runtime();var dll=bridge();
        if(!Files.isRegularFile(dll))throw new IllegalStateException("Build the external bridge with tools/Build-RtxptBridge.ps1");
        for(String dependency:new String[]{"sl.interposer.dll","NRD.dll","omm-lib.dll"})System.load(root.resolve(dependency).toString());
        System.load(dll.toString());
        try{if(bridgeAbiVersion()!=8)throw new IllegalStateException("External RTXPT bridge ABI mismatch: "+dll);}
        catch(UnsatisfiedLinkError legacy){throw new IllegalStateException("The DLL is not the external RTXPT 8 bridge: "+dll,legacy);}
        loaded=true;
    }
    private static native int bridgeAbiVersion();
    /** Test-only: fresh process, before creating any renderer; observes the public upstream progress API. */
    static native long[] progressWindowProof();
    static native long create(String runtime,String scene,int width,int height,boolean externalPresentation,String[] options);
    static native void diagnosticLevel(long handle,int level);
    static native long[] boundaryDiagnostics(long handle,long generation,long nextOrdinal);
    static native void frameContext(long handle,long sequence,long revision);
    static native void presentation(long handle,int maxBounces,int diffuseBounces,boolean reflex,int generatedFrames);
    static native long[] presentationState(long handle);
    static native void exposure(long handle,float compensation);
    static native float[] exposureState(long handle);
    static native void quality(long handle,int sampler,int samples,int candidates,int adaptiveQuality,float localRatio,int layers,int maxDepth,int nestedQuality,boolean halfPrecision,float lodBias,boolean bloom,float bloomRadius,float bloomIntensity,boolean toneMapping,int beautyTransport);
    static native float[] qualityState(long handle);
    static native void denoiser(long handle,boolean relax);
    static native int denoiserState(long handle);
    static native long[] renderingState(long handle);
    static native String frameStatistics(long handle);
    static native double[] meshResourceState(long handle,String mesh);
    static native double[] instanceTransformState(long handle,String instance);
    static native long[] windowState(long handle);
    static native void overlayText(long handle,String text);
    static native void overlayChoices(long handle,String[] choices,int selected);
    static native int overlaySelection(long handle);
    static native void resourcesMemory(long handle,long revision,ByteBuffer packet);
    static native void loadScene(long handle,String path);
    static native void camera(long handle,String pose,float verticalFovRadians);
    static native void lightingPatch(long handle,long[] ids,float[] records,long[] removals,float[] sun,float[] sky);
    static native void sparseLighting(long handle,long[] ids,float[] records,long[] removals,boolean reset);
    static native long[] sparseLightingTelemetry(long handle);
    static native void nativeLighting(long handle,float[] parameters);
    static native void nativeLightingClock(long handle,long timeNanos,long deltaNanos);
    static native void nativeLightingEncoding(long handle,int encoding);
    static native void nativeLightingReconstruction(long handle,boolean enabled,boolean reset);
    static native void nativeLightingReconstructionScale(long handle,int divisor);
    static native long[] nativeLightingReconstructionState(long handle);
    static native void nativeLightingAttributes(long handle,long generation,String[] instances,float[] attributes,float[] lookup,byte[] application);
    static native void nativeLightingApplicationPatch(long handle,long generation,int offset,byte[] data);
    static native void nativeLightingClearAttributes(long handle);
    static native void nativeLightingAttributeBatch(long handle,long revision,String[] names,float[][] baselines,int[][] colors);
    static native long[] nativeLightingTelemetry(long handle);
    static native void nativeLightingCapture(long handle,String path,int signal);
    static native boolean nativeLightingCanPublish(long handle);
    static native boolean nativeLightingConsumersRetired(long handle);
    static native long[] nativeLightingFrame(long handle);
    static native void nativeLightingRelease(long handle,int slot,long generation,long sequence,boolean imported);
    static native void resetHistory(long handle);
    static native int frame(long handle);
    static native void resize(long handle,int width,int height);
    static native void awaitIdle(long handle);
    static native long window(long handle);
    static native void title(long handle,String title);
    static native void capture(long handle,String path);
    static native void close(long handle);
}
