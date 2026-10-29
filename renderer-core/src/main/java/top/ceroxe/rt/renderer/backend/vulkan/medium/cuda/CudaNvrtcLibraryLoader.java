package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Content-addressed extraction; compiler and matching builtins always remain together. */
final class CudaNvrtcLibraryLoader {
    private static final String ROOT = "/META-INF/native/cuda/windows-x86_64/";
    private static final String COMPILER = "nvrtc64_120_0.dll";
    private static final Set<String> FILES = Set.of(COMPILER, "nvrtc-builtins64_129.dll", "LICENSE.txt");

    private CudaNvrtcLibraryLoader() { }

    static Path packagedLibrary() {
        String arch = System.getProperty("os.arch", "");
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !(arch.equals("amd64") || arch.equals("x86_64"))) return null;
        try (InputStream input = CudaNvrtcLibraryLoader.class.getResourceAsStream(ROOT + "runtime-files.sha256")) {
            if (input == null) return null;
            byte[] manifest = input.readAllBytes();
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-cuda");
            return extract(root, manifest, name -> CudaNvrtcLibraryLoader.class.getResourceAsStream(ROOT + name));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot extract packaged NVRTC runtime", failure);
        }
    }

    @FunctionalInterface
    interface Resources { InputStream open(String name) throws IOException; }

    // Package scope permits filesystem/concurrency tests without loading executable libraries.
    static Path extract(Path root, byte[] manifest, Resources resources) throws IOException {
        Map<String, String> entries = parseManifest(manifest);
        Files.createDirectories(root);
        requireDirectory(root);
        Path directory = root.resolve(hash(manifest));
        Files.createDirectories(directory);
        requireDirectory(directory);
        for (var entry : entries.entrySet()) {
            Path target = directory.resolve(entry.getKey());
            if (valid(target, entry.getValue())) continue;
            Path temporary = Files.createTempFile(directory, entry.getKey() + ".", ".tmp");
            try {
                try (InputStream input = resources.open(entry.getKey())) {
                    if (input == null) throw new IOException("Packaged NVRTC resource missing: " + entry.getKey());
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!valid(temporary, entry.getValue())) throw new IOException("Packaged NVRTC hash mismatch: " + entry.getKey());
                try {
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException concurrentMove) {
                    if (!valid(target, entry.getValue())) throw concurrentMove;
                }
            } finally { Files.deleteIfExists(temporary); }
        }
        return directory.resolve(COMPILER).toAbsolutePath();
    }

    private static Map<String, String> parseManifest(byte[] manifest) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}") || !FILES.contains(fields[1])
                    || entries.putIfAbsent(fields[1], fields[0]) != null)
                throw new IOException("Invalid packaged NVRTC manifest");
        }
        if (!entries.keySet().equals(FILES)) throw new IOException("Packaged NVRTC manifest omits required compiler/builtins/license");
        return entries;
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Unsafe NVRTC extraction directory: " + path);
    }

    private static boolean valid(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return expected.equals(HexFormat.of().formatHex(digest.digest()));
    }

    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
