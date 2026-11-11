package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipFile;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** Install the versioned native runtime once, outside the rendering loop. */
final class RtxptRuntimeBundle {
    private static final String ROOT = "/META-INF/native/windows-x86_64/";
    private RtxptRuntimeBundle() { }

    static boolean packaged() {
        return RtxptRuntimeBundle.class.getResource(ROOT + "rtxpt-runtime.sha256") != null;
    }

    static synchronized Path extract() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                || !java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)))
            throw new IllegalStateException("The packaged RTXPT backend requires Windows x86-64");
        try {
            String digest;
            try (var input = RtxptRuntimeBundle.class.getResourceAsStream(ROOT + "rtxpt-runtime.sha256")) {
                if (input == null) throw new IOException("RTXPT native runtime is not packaged in this JAR");
                digest = new String(input.readAllBytes(), US_ASCII).strip();
            }
            if (!digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid RTXPT archive digest");
            var local = System.getenv("LOCALAPPDATA");
            var base = Path.of(System.getProperty("top.ceroxe.rt.rtxpt.cache",
                local == null || local.isBlank() ? System.getProperty("java.io.tmpdir") : local))
                .toAbsolutePath().normalize().resolve("RTRendererAPI/RTXPT");
            Files.createDirectories(base);
            var target = base.resolve("1.9.0-" + digest.substring(0,20));
            try (var channel = FileChannel.open(base.resolve(digest + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                if (complete(target, digest)) return target.resolve("bin");
                var staging = Files.createTempDirectory(base, ".stage-");
                try {
                    var archive = staging.resolve("runtime.zip");
                    var hash = MessageDigest.getInstance("SHA-256");
                    try (var resource = RtxptRuntimeBundle.class.getResourceAsStream(ROOT + "rtxpt-runtime.zip")) {
                        if (resource == null) throw new IOException("RTXPT native runtime archive is missing");
                        try (var input = new DigestInputStream(resource, hash)) { Files.copy(input, archive); }
                    }
                    if (!digest.equals(HexFormat.of().formatHex(hash.digest()))) throw new IOException("RTXPT native runtime SHA-256 mismatch");
                    try (var zip = new ZipFile(archive.toFile())) {
                        for (var entries = zip.entries(); entries.hasMoreElements();) {
                            var entry = entries.nextElement();
                            var output = staging.resolve(entry.getName()).normalize();
                            if (!output.startsWith(staging) || output.equals(archive)) throw new IOException("Invalid RTXPT archive entry");
                            if (entry.isDirectory()) Files.createDirectories(output);
                            else {
                                Files.createDirectories(output.getParent());
                                try (var input = zip.getInputStream(entry)) { Files.copy(input, output); }
                                // RTXPT validates shader caches against source timestamps. The
                                // reproducible archive gives both the same timestamp, regardless
                                // of extraction order, so packaged binaries remain reusable.
                                if(entry.getLastModifiedTime()!=null)Files.setLastModifiedTime(output,entry.getLastModifiedTime());
                            }
                        }
                    }
                    Files.delete(archive);
                    verify(staging);
                    Files.writeString(staging.resolve(".complete"), digest, US_ASCII);
                    deleteTree(target);
                    try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(staging, target); }
                } finally { deleteTree(staging); }
                return target.resolve("bin");
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot install the packaged RTXPT runtime", e);
        }
    }

    private static void verify(Path root) throws IOException {
        verifyBin(root.resolve("bin"));
        if (!Files.isDirectory(root.resolve("Assets"))) throw new IOException("Missing official RTXPT Assets directory");
        if (!Files.isDirectory(root.resolve("Assets/EnvironmentMaps")))
            throw new IOException("Missing RTXPT EnvironmentMaps directory (it may be empty)");
    }

    static void verifyBin(Path bin) throws IOException {
        for (var file : new String[]{"rtrenderer_rtxpt.dll", "rtrenderer_jvm_host.exe",
                "sl.interposer.dll", "NRD.dll", "omm-lib.dll", "D3D12/D3D12Core.dll",
                "PrecompiledShaders/index.tsv", "PrecompiledShaders/globals.txt", "PrecompiledShaders/sources.tsv",
                "ShaderDynamic/Tools/d3d12/x64/dxc.exe",
                "ShaderDynamic/Source/Rtxpt/PathTracer/PathTracer.hlsli"})
            if (!Files.isRegularFile(bin.resolve(file))) throw new IOException("Missing RTXPT runtime file: " + file);
        for (var dir : new String[]{"ShaderPrecompiled/Rtxpt", "ShaderDynamic/Source/External"})
            if (!Files.isDirectory(bin.resolve(dir))) throw new IOException("Missing RTXPT shader directory: " + dir);
        for (var shader : new String[]{"RtrNativeLighting", "RtrNativeLightingReconstruct",
                "RtrNativeDynamicLighting", "RtrNativeDynamicLightingReconstruct",
                "RtrNativeLightingDeep", "RtrNativeLightingEncode", "RtrNativeLightingResolve",
                "RtrNativeGiReduce", "RtrNativeGiUpsample"}) {
            var file = bin.resolve("AdapterShaders/app/ProcessingPasses/" + shader + ".bin");
            if (!Files.isRegularFile(file) || Files.size(file) == 0)
                throw new IOException("Missing compiled adapter shader: " + shader);
        }
    }

    private static boolean complete(Path root, String digest) {
        try {
            if (!Files.isRegularFile(root.resolve(".complete")) || !digest.equals(Files.readString(root.resolve(".complete"), US_ASCII))) return false;
            verify(root);
            return true;
        } catch (IOException e) { return false; }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
