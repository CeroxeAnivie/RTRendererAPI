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
    static native long create(String runtime,String scene,int width,int height,String[] options);
    static native int frame(long handle);
    static native void awaitIdle(long handle);
    static native int[] runtimeStatus(long handle);
    static native void camera(long handle,String pose,float verticalFovRadians);
    static native void loadScene(long handle,String path);
    static native long window(long handle);
    static native void resize(long handle,int width,int height);
    static native void settings(long handle,int maxBounces,int diffuseBounces,boolean reflex,
            int frameGenerationMode,int generatedFrames,int samplesPerPixel,int realtimeAa,
            int dlssMode,boolean standaloneDenoiser,boolean restirDi,boolean restirGi,boolean sharc);
    static native void capture(long handle,String path);
    static native void snapshotMaterials(long handle,String directory);
    static native void interiors(long handle,String[] names,FloatBuffer values);
    static native void transforms(long handle,String[] names,FloatBuffer rowMajorMatrices);
    static native void close(long handle);
}
