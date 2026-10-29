package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Exercises cache races, corruption and hostile manifests without loading a DLL. */
public final class CudaNvrtcLibraryLoaderSelfTest {
    public static void main(String[] arguments) throws Exception {
        Path root = Files.createTempDirectory("nvrtc-loader-test-");
        try {
            Map<String, byte[]> resources = new LinkedHashMap<>();
            resources.put("nvrtc64_120_0.dll", new byte[]{1, 2, 3});
            resources.put("nvrtc-builtins64_129.dll", new byte[]{4, 5, 6});
            resources.put("LICENSE.txt", "NVRTC license fixture".getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (var resource : resources.entrySet()) text.append(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(resource.getValue())))
                    .append('\t').append(resource.getKey()).append('\n');
            byte[] manifest = text.toString().getBytes(StandardCharsets.UTF_8);
            CudaNvrtcLibraryLoader.Resources source = name -> new ByteArrayInputStream(resources.get(name));
            Path compiler;
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<Callable<Path>>();
                for (int i = 0; i < 16; i++) tasks.add(() -> CudaNvrtcLibraryLoader.extract(root, manifest, source));
                var results = workers.invokeAll(tasks);
                compiler = results.getFirst().get();
                for (var result : results) require(result.get().equals(compiler), "cache identity differs under concurrency");
            }
            Files.write(compiler, new byte[]{0});
            require(CudaNvrtcLibraryLoader.extract(root, manifest, source).equals(compiler), "repair changed cache identity");
            require(java.util.Arrays.equals(Files.readAllBytes(compiler), resources.get(compiler.getFileName().toString())),
                    "corrupt cache entry was reused");
            expectIo(() -> CudaNvrtcLibraryLoader.extract(root.resolve("bad-resource"), manifest,
                    name -> new ByteArrayInputStream(new byte[]{9})));
            expectIo(() -> CudaNvrtcLibraryLoader.extract(root, (text + text.toString()).getBytes(StandardCharsets.UTF_8), source));
            expectIo(() -> CudaNvrtcLibraryLoader.extract(root,
                    text.toString().replace("LICENSE.txt", "../LICENSE.txt").getBytes(StandardCharsets.UTF_8), source));
            expectIo(() -> CudaNvrtcLibraryLoader.extract(root, new byte[0], source));
            try (var paths = Files.walk(root)) {
                require(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "temporary cache files leaked");
            }
            String resourceRoot = "/META-INF/native/cuda/windows-x86_64/";
            for (String name : resources.keySet()) try (var input = CudaNvrtcLibraryLoaderSelfTest.class.getResourceAsStream(resourceRoot + name)) {
                require(input != null, "release runtime resource missing: " + name);
            }
            System.out.println("CudaNvrtcLibraryLoaderSelfTest passed: concurrent extraction, corruption repair, manifest rejection, packaged closure");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void expectIo(IoOperation operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("invalid runtime resource accepted");
    }
    @FunctionalInterface private interface IoOperation { void run() throws Exception; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
