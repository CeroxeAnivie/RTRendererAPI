package top.ceroxe.rt.renderer.luxcore;

import java.nio.ByteBuffer;
import java.nio.file.*;

/** JNI declarations only. The unmodified upstream engine owns all rendering work. */
final class LuxCoreNative {
    private static boolean loaded;
    private LuxCoreNative() { }

    static synchronized void load() {
        if (loaded) return;
        Path sdk = sdk();
        Path bridge = Path.of(System.getProperty("top.ceroxe.rt.luxcore.bridge",
                "renderer-luxcore/build/native/rtrenderer_luxcore.dll")).toAbsolutePath();
        if (!Files.isRegularFile(bridge)) throw new IllegalStateException("Build the thin JNI bridge: " + bridge);
        System.load(bridge.toString());
        initialize(sdk.resolve("bin").toString());
        loaded = true;
    }

    static Path sdk() {
        String location = System.getProperty("top.ceroxe.rt.luxcore.sdk", System.getenv("LUXCORE_SDK"));
        if (location == null || location.isBlank())
            throw new IllegalStateException("Install LuxCore 2.11.2 and set LUXCORE_SDK or top.ceroxe.rt.luxcore.sdk");
        Path root = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("bin/luxcore.dll")))
            throw new IllegalStateException("Missing official LuxCore runtime: " + root);
        return root;
    }

    private static native void initialize(String binaryDirectory);
    static native long create();
    static native void close(long handle);
    static native void beginEdit(long handle);
    static native void endEdit(long handle);
    static native void parse(long handle, byte[] propertiesUtf8);
    static native void deleteObjects(long handle, byte[] namesUtf8);
    static native void deleteLights(long handle, byte[] namesUtf8);
    static native void collectUnused(long handle);
    static native void defineMesh(long handle, byte[] name, int vertices, int triangles,
            ByteBuffer positions, ByteBuffer indices, ByteBuffer normals, ByteBuffer uvs, ByteBuffer colors, ByteBuffer alphas);
    static native void defineImage(long handle, byte[] name, int width, int height, ByteBuffer linearRgba);
    static native void start(long handle, byte[] propertiesUtf8);
    static native void resize(long handle, byte[] propertiesUtf8);
    static native double samples(long handle);
    static native double readFilm(long handle, ByteBuffer rgba32f);
    static native double readDisplay(long handle, ByteBuffer rgba8);
    static native String statistics(long handle);
}
