package top.ceroxe.rt.renderer.rtxpt;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** CPU-only extraction contracts. Mock native files are never loaded or executed. */
public final class RtxptRuntimeBundleSelfTest {
    private static final String PACKAGE = "top.ceroxe.rt.renderer.rtxpt.";
    private static final String CACHE = "top.ceroxe.rt.rtxpt.cache";
    private static final String OVERRIDE = "top.ceroxe.rt.rtxpt.runtime";
    private static final String RESOURCES = "META-INF/native/windows-x86_64";
    private static final String SOURCE = "bin/ShaderDynamic/Source/Rtxpt/PathTracer/PathTracer.hlsli";
    private static final String PRECOMPILED = "bin/ShaderPrecompiled/Rtxpt/fixture.bin";
    private static final FileTime ARCHIVE_TIME = FileTime.fromMillis(1_700_000_000_000L);

    private RtxptRuntimeBundleSelfTest() { }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("rtxpt-runtime-bundle-");
        var saved = new HashMap<String, String>();
        for (var name : new String[]{CACHE, OVERRIDE, "os.name", "os.arch"})
            saved.put(name, System.getProperty(name));
        try {
            System.clearProperty(OVERRIDE);
            // Exercise the Windows package contract without loading any platform binary.
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            coldAndHot(root.resolve("cold-hot"));
            requiredPaths(root.resolve("required-paths"));
            digestMismatch(root.resolve("digest"));
            zipBoundaries(root.resolve("zip-boundaries"));
            flatRejected(root.resolve("flat"));
            explicitOverride(root.resolve("override"));
            invalidCacheRebuilt(root.resolve("rebuild"));
            System.out.println("RTXPT_RUNTIME_BUNDLE_SELFTEST PASS cases=7 CPU_EXTRACTION_ONLY mockNativeFiles=true nativeLoaded=false");
        } finally {
            for (var entry : saved.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
            deleteTree(root);
        }
    }

    private static void coldAndHot(Path root) throws Exception {
        var fixture = fixture(root, runtimeEntries(), null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            require((Boolean) call(loader, "RtxptRuntimeBundle", "packaged"), "Controlled resource package was not detected");
            Path bin = bin(loader);
            require(bin.isAbsolute() && bin.getFileName().toString().equals("bin"), "Public path must return the absolute bin directory");
            require(bin.startsWith(fixture.cache()), "Extraction escaped the configured cache");
            require(Files.isDirectory(bin.resolve("../Assets").normalize()), "Official Assets must be beside bin");
            Path environmentMaps = bin.resolve("../Assets/EnvironmentMaps").normalize();
            require(Files.isDirectory(environmentMaps), "An empty EnvironmentMaps ZIP directory must be extracted");
            try (var contents = Files.list(environmentMaps)) {
                require(contents.findAny().isEmpty(), "EnvironmentMaps fixture must not require an example HDR");
            }
            require(Files.readString(bin.resolve("../.complete").normalize()).equals(fixture.digest()), "Completion marker must identify the verified archive");
            require(Files.getLastModifiedTime(bin.resolve(SOURCE.substring(4))).equals(ARCHIVE_TIME), "Dynamic shader source timestamp changed on extraction");
            require(Files.getLastModifiedTime(bin.resolve(PRECOMPILED.substring(4))).equals(ARCHIVE_TIME), "Precompiled shader timestamp changed on extraction");
            require(!Files.exists(bin.resolve("ShaderDynamic/Bin/dxil/bundle.json")), "Fixture unexpectedly depends on the removed private shader manifest");
            require(call(loader, "RtxptRuntime", "jvmHostExecutable").equals(bin.resolve("rtrenderer_jvm_host.exe")), "Public host path must be inside returned bin");
            Path sentinel = bin.getParent().resolve("hot-cache-sentinel");
            Files.writeString(sentinel, "preserve a valid cache");
            Files.delete(fixture.archiveResource());
            require(bin(loader).equals(bin), "Hot cache changed its returned path");
            require(Files.exists(sentinel), "Hot lookup unnecessarily replaced a complete cache");
            noStages(fixture.cache());
        }
        passed("cold-hot-current-layout-no-private-manifest");
    }

    private static void requiredPaths(Path root) throws Exception {
        var wrongShader = runtimeEntries();
        wrongShader.put("bin/ShaderDynamic/Source/PathTracer.hlsl", wrongShader.remove(SOURCE));
        var fixture = fixture(root.resolve("wrong-shader"), wrongShader, null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            rejected(loader, "PathTracer/PathTracer.hlsli");
            noInstall(fixture.cache());
        }
        var noAssets = runtimeEntries();
        noAssets.remove("Assets/EnvironmentMaps/");
        fixture = fixture(root.resolve("no-assets"), noAssets, null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            rejected(loader, "Missing official RTXPT Assets");
            noInstall(fixture.cache());
        }
        var noEnvironmentMaps = runtimeEntries();
        noEnvironmentMaps.remove("Assets/EnvironmentMaps/");
        noEnvironmentMaps.put("Assets/fixture.txt", bytes("Assets exists without EnvironmentMaps"));
        fixture = fixture(root.resolve("no-environment-maps"), noEnvironmentMaps, null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            rejected(loader, "Missing RTXPT EnvironmentMaps directory");
            noInstall(fixture.cache());
        }
        passed("fixed-shader-path-sibling-assets-and-empty-environment-directory");
    }

    private static void digestMismatch(Path root) throws Exception {
        var fixture = fixture(root, runtimeEntries(), "0".repeat(64));
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            rejected(loader, "SHA-256 mismatch");
            noInstall(fixture.cache());
        }
        passed("digest-mismatch");
    }

    private static void zipBoundaries(Path root) throws Exception {
        int index = 0;
        for (var dangerous : new String[]{"../escaped.txt", "runtime.zip"}) {
            var entries = runtimeEntries();
            entries.put(dangerous, bytes("must not be written outside the extraction payload"));
            var fixture = fixture(root.resolve("entry-" + index++), entries, null);
            System.setProperty(CACHE, fixture.cache().toString());
            try (var loader = fixture.open()) {
                rejected(loader, "Invalid RTXPT archive entry");
                noInstall(fixture.cache());
                try (var files = Files.walk(root)) {
                    require(files.noneMatch(p -> p.getFileName().toString().equals("escaped.txt")), "ZIP traversal wrote outside staging");
                }
            }
        }
        passed("zip-traversal-and-archive-overwrite-rejected");
    }

    private static void flatRejected(Path root) throws Exception {
        var flat = new LinkedHashMap<String, byte[]>();
        runtimeEntries().forEach((name, data) -> flat.put(name.startsWith("bin/") ? name.substring(4) : name, data));
        var fixture = fixture(root, flat, null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            rejected(loader, "Missing RTXPT runtime file");
            noInstall(fixture.cache());
            Path flatDirectory = Files.createDirectories(root.resolve("legacy-flat-directory"));
            for (var entry : flat.entrySet()) {
                Path file = flatDirectory.resolve(entry.getKey());
                if (entry.getKey().endsWith("/")) { Files.createDirectories(file); continue; }
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            System.setProperty(OVERRIDE, flatDirectory.toString());
            try {
                rejected(loader, "Missing official RTXPT Assets beside runtime bin");
                noInstall(fixture.cache());
            } finally { System.clearProperty(OVERRIDE); }
        }
        passed("old-flat-archive-and-explicit-directory-rejected");
    }

    private static void explicitOverride(Path root) throws Exception {
        var valid = fixture(root.resolve("valid-source"), runtimeEntries(), null);
        System.setProperty(CACHE, valid.cache().toString());
        Path validBin;
        try (var loader = valid.open()) { validBin = bin(loader); }
        var fallback = fixture(root.resolve("packaged-fallback"), runtimeEntries(), null);
        System.setProperty(CACHE, fallback.cache().toString());
        try (var loader = fallback.open()) {
            System.setProperty(OVERRIDE, validBin.resolve(".").toString());
            require(bin(loader).equals(validBin), "Explicit runtime path was not normalized and returned");
            require(call(loader, "RtxptRuntime", "jvmHostExecutable").equals(validBin.resolve("rtrenderer_jvm_host.exe")), "Host path ignored the explicit runtime override");
            noInstall(fallback.cache());
            System.setProperty(OVERRIDE, root.resolve("missing-runtime/bin").toString());
            rejected(loader, "RTXPT runtime is incomplete");
            noInstall(fallback.cache());
            System.setProperty(OVERRIDE, validBin.toString());
            Path environmentMaps = validBin.resolve("../Assets/EnvironmentMaps").normalize();
            Files.delete(environmentMaps);
            rejected(loader, "Missing RTXPT EnvironmentMaps directory");
            noInstall(fallback.cache());
            Files.createDirectories(environmentMaps);
            deleteTree(validBin.resolve("../Assets").normalize());
            rejected(loader, "Missing official RTXPT Assets beside runtime bin");
            noInstall(fallback.cache());
        } finally { System.clearProperty(OVERRIDE); }
        passed("explicit-valid-or-invalid-override-never-falls-back");
    }

    private static void invalidCacheRebuilt(Path root) throws Exception {
        var fixture = fixture(root, runtimeEntries(), null);
        System.setProperty(CACHE, fixture.cache().toString());
        try (var loader = fixture.open()) {
            Path bin = bin(loader);
            Path shader = bin.resolve(SOURCE.substring(4));
            Path sentinel = bin.getParent().resolve("stale-cache-sentinel");
            Files.writeString(sentinel, "must disappear on rebuild");
            Files.delete(shader);
            require(bin(loader).equals(bin) && Files.isRegularFile(shader), "Missing required shader did not rebuild the cache");
            require(!Files.exists(sentinel), "Incomplete cache contents survived replacement");
            Path adapter = bin.resolve("AdapterShaders/app/ProcessingPasses/RtrNativeLighting.bin");
            Files.write(adapter, new byte[0]);
            require(bin(loader).equals(bin) && Files.size(adapter) > 0,
                    "Empty compiled adapter shader did not rebuild the cache");
            Files.writeString(sentinel, "bad-marker rebuild");
            Files.writeString(bin.getParent().resolve(".complete"), "wrong archive identity");
            require(bin(loader).equals(bin), "Marker repair changed the cache target");
            require(Files.readString(bin.getParent().resolve(".complete")).equals(fixture.digest()), "Invalid completion marker was not repaired");
            require(!Files.exists(sentinel), "Invalid-marker cache contents survived replacement");
            noStages(fixture.cache());
        }
        passed("missing-file-and-invalid-marker-cache-rebuilt");
    }

    private record Fixture(Path classes, Path cache, Path archiveResource, String digest) {
        URLClassLoader open() throws Exception {
            // Bootstrap-only parent prevents the fixture from borrowing real packaged resources.
            return new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
        }
    }

    private static Fixture fixture(Path root, Map<String, byte[]> entries, String declaredDigest) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        copyActualClass(classes, RtxptRuntimeBundle.class);
        copyActualClass(classes, RtxptRuntime.class);
        Path resources = Files.createDirectories(classes.resolve(RESOURCES));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setLastModifiedTime(ARCHIVE_TIME);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        byte[] archive = bytes.toByteArray();
        String digest = declaredDigest != null ? declaredDigest : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        Path resource = resources.resolve("rtxpt-runtime.zip");
        Files.write(resource, archive);
        Files.writeString(resources.resolve("rtxpt-runtime.sha256"), digest + "\n", StandardCharsets.US_ASCII);
        return new Fixture(classes, Files.createDirectories(root.resolve("cache")), resource, digest);
    }

    private static void copyActualClass(Path root, Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        Path target = root.resolve(resource);
        Files.createDirectories(target.getParent());
        try (var source = type.getResourceAsStream("/" + resource)) {
            require(source != null, "Actual production class bytecode is unavailable: " + resource);
            Files.copy(source, target);
        }
    }

    private static LinkedHashMap<String, byte[]> runtimeEntries() {
        var entries = new LinkedHashMap<String, byte[]>();
        for (var path : new String[]{"bin/rtrenderer_rtxpt.dll", "bin/rtrenderer_jvm_host.exe",
                "bin/sl.interposer.dll", "bin/NRD.dll", "bin/omm-lib.dll", "bin/D3D12/D3D12Core.dll",
                "bin/PrecompiledShaders/index.tsv", "bin/PrecompiledShaders/globals.txt", "bin/PrecompiledShaders/sources.tsv",
                "bin/ShaderDynamic/Tools/d3d12/x64/dxc.exe", SOURCE, PRECOMPILED,
                "bin/ShaderDynamic/Source/External/fixture.hlsli"})
            entries.put(path, bytes("non-executable fixture: " + path));
        entries.put("Assets/EnvironmentMaps/", new byte[0]);
        for (var shader : new String[]{"RtrNativeLighting", "RtrNativeLightingReconstruct",
                "RtrNativeDynamicLighting", "RtrNativeDynamicLightingReconstruct",
                "RtrNativeLightingDeep", "RtrNativeLightingEncode", "RtrNativeLightingResolve",
                "RtrNativeGiReduce", "RtrNativeGiUpsample"})
            entries.put("bin/AdapterShaders/app/ProcessingPasses/" + shader + ".bin", bytes("compiled adapter fixture"));
        return entries;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Path bin(ClassLoader loader) throws Exception { return (Path) call(loader, "RtxptRuntime", "binDirectory"); }

    private static Object call(ClassLoader loader, String type, String method) throws Exception {
        try {
            var target = Class.forName(PACKAGE + type, true, loader).getDeclaredMethod(method);
            target.setAccessible(true);
            return target.invoke(null);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(wrapped.getCause());
        }
    }

    private static void rejected(ClassLoader loader, String reason) throws Exception {
        try { bin(loader); }
        catch (IllegalStateException expected) {
            for (Throwable cause = expected; cause != null; cause = cause.getCause())
                if (cause.getMessage() != null && cause.getMessage().contains(reason)) return;
            throw new AssertionError("Failure did not explain " + reason, expected);
        }
        throw new AssertionError("Invalid runtime was accepted: " + reason);
    }

    private static void noInstall(Path cache) throws Exception {
        noStages(cache);
        try (var files = Files.walk(cache)) {
            require(files.noneMatch(p -> p.getFileName().toString().equals(".complete")), "Rejected package installed a complete cache");
        }
    }

    private static void noStages(Path cache) throws Exception {
        try (var files = Files.walk(cache)) {
            require(files.noneMatch(p -> p.getFileName().toString().startsWith(".stage-")), "Extraction leaked a staging directory");
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void passed(String name) { System.out.println("RTXPT_RUNTIME_BUNDLE_CASE PASS " + name); }
}
