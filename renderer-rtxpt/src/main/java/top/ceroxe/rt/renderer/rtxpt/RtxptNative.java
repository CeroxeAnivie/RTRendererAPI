package top.ceroxe.rt.renderer.rtxpt;
import java.nio.*;
import java.nio.file.*;

/** JNI declarations and runtime loading only. */
final class RtxptNative {
    static Path runtime() {
        Path p=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.runtime","build/rtxpt-sdk/bin")).toAbsolutePath().normalize();
        if(!Files.isDirectory(p.resolve("ShaderPrecompiled"))) throw new IllegalStateException("RTXPT runtime assets missing: "+p);
        return p;
    }
    static Path bridge() { return Path.of(System.getProperty("top.ceroxe.rt.rtxpt.bridge",runtime().resolve("rtrenderer_rtxpt.dll").toString())).toAbsolutePath().normalize(); }
    private static boolean loaded;
    static synchronized void load() {
        if(loaded)return;
        Path root=runtime(),dll=bridge();
        if(!Files.isRegularFile(dll))throw new IllegalStateException("Build the RTXPT JNI host: tools/Build-RtxptBridge.ps1");
        System.load(dll.toString()); loaded=true;
    }
    static native long create(String runtime,String scene,int width,int height,String[] options);
    static native boolean frame(long handle);
    static native void camera(long handle,String pose,float verticalFovRadians);
    static native void loadScene(long handle,String path);
    static native long window(long handle);
    static native void resize(long handle,int width,int height);
    static native void settings(long handle,int maxBounces,int diffuseBounces,boolean reflex);
    static native void interiors(long handle,String[] names,FloatBuffer values);
    static native void transforms(long handle,String[] names,FloatBuffer rowMajorMatrices);
    static native void close(long handle);
}
