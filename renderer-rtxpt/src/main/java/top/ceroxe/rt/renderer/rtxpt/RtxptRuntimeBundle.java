package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** Install the versioned native runtime once, outside the rendering loop. */
final class RtxptRuntimeBundle {
    private static final String ROOT = "/META-INF/native/windows-x86_64/";
    private static final Set<Path> VERIFIED_CACHES = new java.util.HashSet<>();
    private RtxptRuntimeBundle() { }

    static boolean packaged() {
        return RtxptRuntimeBundle.class.getResource(ROOT + "rtxpt-runtime.sha256") != null;
    }

    static synchronized Path extract(Path cacheRoot) {
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
            var base = (cacheRoot != null ? cacheRoot : Path.of(System.getProperty("top.ceroxe.rt.rtxpt.cache",
                local == null || local.isBlank() ? System.getProperty("java.io.tmpdir") : local))
                .resolve("RTRendererAPI/RTXPT")).toAbsolutePath().normalize();
            for (var existing = base; existing != null; existing = existing.getParent()) {
                if (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!existing.toRealPath().equals(existing))
                    throw new IOException("RTXPT runtime cache path crosses a reparse point: " + existing);
                break;
            }
            Files.createDirectories(base);
            if (!base.toRealPath().equals(base)) throw new IOException("RTXPT runtime cache root is a reparse point: " + base);
            var target = base.resolve("RTXPT-1.9.0-" + digest.substring(0,20));
            try (var channel = FileChannel.open(base.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var lock = channel.lock()) {
                if (VERIFIED_CACHES.contains(target) && completeMarker(target, digest)) {
                    return target.resolve("bin");
                }
                var expected = expectedFiles();
                if (complete(target, digest, expected)) {
                    cleanupManagedEntries(base, target);
                    VERIFIED_CACHES.add(target);
                    return target.resolve("bin");
                }
                VERIFIED_CACHES.remove(target);
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
                    verifyFiles(staging, expected);
                    Files.writeString(staging.resolve(".complete"), digest, US_ASCII);
                    deleteTree(target);
                    installStaging(staging, target, digest, expected);
                    VERIFIED_CACHES.add(target);
                    cleanupManagedEntries(base, target);
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
        for (var shader : new String[]{"RtrNativeLighting", "RtrNativeDirectLighting", "RtrNativeLightingReconstruct",
                "RtrNativeDynamicLighting", "RtrNativeDynamicLightingReconstruct",
                "RtrNativeLightingDeep", "RtrNativeLightingEncode", "RtrNativeLightingResolve", "RtrNativeDielectric",
                "RtrNativeGiReduce", "RtrNativeGiUpsample"}) {
            var file = bin.resolve("AdapterShaders/app/ProcessingPasses/" + shader + ".bin");
            if (!Files.isRegularFile(file) || Files.size(file) == 0)
                throw new IOException("Missing compiled adapter shader: " + shader);
        }
    }

    private static Map<String,String> expectedFiles() throws IOException {
        try (var input = RtxptRuntimeBundle.class.getResourceAsStream(ROOT + "rtxpt-runtime.files.sha256")) {
            if (input == null) throw new IOException("RTXPT runtime file manifest is missing");
            var expected = new LinkedHashMap<String,String>();
            for (var line : new String(input.readAllBytes(), US_ASCII).split("\\R")) {
                if (line.isBlank()) continue;
                int separator = line.indexOf("  ");
                if (separator != 64 || !line.substring(0, separator).matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid RTXPT runtime file manifest");
                String name = line.substring(separator + 2);
                if (name.isBlank() || Path.of(name).isAbsolute() || name.contains("..") || expected.put(name, line.substring(0, 64)) != null)
                    throw new IOException("Invalid RTXPT runtime manifest path: " + name);
            }
            if (expected.isEmpty()) throw new IOException("RTXPT runtime file manifest is empty");
            return expected;
        }
    }

    private static void verifyFiles(Path root, Map<String,String> expected) throws IOException, NoSuchAlgorithmException {
        var actual = regularFiles(root);
        actual.remove(root.resolve(".complete"));
        if (actual.size() != expected.size()) throw new IOException("RTXPT runtime file count mismatch");
        var hash = MessageDigest.getInstance("SHA-256");
        var bytes = new byte[65536];
        for (var path : actual) {
            String name = root.relativize(path).toString().replace('\\','/');
            String digest = expected.get(name);
            if (digest == null) throw new IOException("Unexpected RTXPT runtime file: " + name);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(bytes)) >= 0) if (count > 0) hash.update(bytes, 0, count);
            }
            if (!digest.equals(HexFormat.of().formatHex(hash.digest()))) throw new IOException("RTXPT runtime SHA-256 mismatch: " + name);
        }
    }

    private static java.util.List<Path> regularFiles(Path root) throws IOException {
        var files = new java.util.ArrayList<Path>();
        collectFiles(root, root.toRealPath(), files);
        return files;
    }

    private static void collectFiles(Path directory, Path canonicalRoot, java.util.List<Path> files) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            for (var child : children) {
                var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) throw new IOException("RTXPT runtime contains a symbolic link: " + child);
                if (attributes.isDirectory()) {
                    var realChild = child.toRealPath();
                    if (!realChild.equals(child.toAbsolutePath().normalize()) || !realChild.startsWith(canonicalRoot))
                        throw new IOException("RTXPT runtime directory is a reparse point or escapes its cache: " + child);
                    collectFiles(child, canonicalRoot, files);
                } else if (attributes.isRegularFile()) files.add(child);
                else throw new IOException("RTXPT runtime contains a non-regular file: " + child);
            }
        }
    }

    private static boolean completeMarker(Path root, String digest) {
        try {
            var attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory() && !attributes.isSymbolicLink()
                && root.toRealPath().equals(root.toAbsolutePath().normalize())
                && Files.isRegularFile(root.resolve(".complete"), LinkOption.NOFOLLOW_LINKS)
                && digest.equals(Files.readString(root.resolve(".complete"), US_ASCII));
        } catch (IOException failure) { return false; }
    }

    private static void cleanupManagedEntries(Path base, Path keep) throws IOException {
        try (var children = Files.newDirectoryStream(base)) {
            for (var child : children) {
                String name = child.getFileName().toString();
                if (!(name.matches("(?:RTXPT-)?1\\.9\\.0-[0-9a-f]{20}") || name.matches("\\.stage-[0-9]+"))
                        || keep != null && child.equals(keep)) continue;
                deleteTree(child);
            }
        }
    }

    private static boolean complete(Path root, String digest, Map<String,String> expected) {
        try {
            if (!completeMarker(root, digest)) return false;
            verifyFiles(root, expected);
            verify(root);
            return true;
        } catch (IOException | NoSuchAlgorithmException e) { return false; }
    }

    private static void installStaging(Path staging, Path target, String digest, Map<String,String> expected) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staging, target);
            return;
        } catch (AccessDeniedException atomicFailure) {
            IOException lastFailure = atomicFailure;
            for (long delayMillis : new long[]{100, 300, 700}) {
                if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS) && complete(target, digest, expected)) return;
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTree(target);
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    interrupted.addSuppressed(lastFailure);
                    throw new IOException("Interrupted while installing the verified RTXPT runtime", interrupted);
                }
                try {
                    Files.move(staging, target);
                    return;
                } catch (AccessDeniedException retryFailure) {
                    lastFailure.addSuppressed(retryFailure);
                    lastFailure = retryFailure;
                }
            }
            throw lastFailure;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        var attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) { Files.delete(root); return; }
        var realRoot = root.toRealPath();
        if (!realRoot.equals(root.toAbsolutePath().normalize())) { Files.delete(root); return; }
        try (var children = Files.newDirectoryStream(root)) {
            for (var child : children) {
                var childAttributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (childAttributes.isSymbolicLink() || !childAttributes.isDirectory()) Files.delete(child);
                else {
                    var realChild = child.toRealPath();
                    if (realChild.equals(child.toAbsolutePath().normalize()) && realChild.startsWith(realRoot)) deleteTree(child);
                    else Files.delete(child);
                }
            }
        }
        Files.delete(root);
    }
}
