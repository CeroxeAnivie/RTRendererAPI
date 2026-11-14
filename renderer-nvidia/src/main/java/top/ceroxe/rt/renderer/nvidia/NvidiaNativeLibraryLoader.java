package top.ceroxe.rt.renderer.nvidia;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Loads the NVIDIA runtime from the OS path or a versioned classpath artifact. */
final class NvidiaNativeLibraryLoader {
    private static final String LIBRARY_NAME = "rtrenderer_nvidia";
    private static final String RESOURCE_ROOT = "/META-INF/native/windows-x86_64/";
    private static final String MANIFEST = RESOURCE_ROOT + "runtime-files.sha256";

    private NvidiaNativeLibraryLoader() {
    }

    static void load() {
        LinkageError systemFailure;
        try {
            System.loadLibrary(LIBRARY_NAME);
            return;
        } catch (LinkageError failure) {
            systemFailure = failure;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw systemFailure;
        }
        try {
            byte[] manifestBytes = readRequiredResource(MANIFEST);
            List<RuntimeFile> files = parseManifest(manifestBytes);
            Path directory = extractionDirectory(manifestBytes);
            prepareExtractionDirectory(directory);
            for (RuntimeFile file : files) extract(directory, file);
            Path bridge = directory.resolve(LIBRARY_NAME + ".dll");
            if (!Files.isRegularFile(bridge, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("native runtime manifest omits " + bridge.getFileName());
            }
            System.load(bridge.toAbsolutePath().toString());
        } catch (IOException | RuntimeException | LinkageError failure) {
            LinkageError unavailable = new LinkageError(
                    "unable to load packaged NVIDIA runtime: " + failure.getMessage()
            );
            unavailable.initCause(failure);
            unavailable.addSuppressed(systemFailure);
            throw unavailable;
        }
    }

    private static List<RuntimeFile> parseManifest(byte[] bytes) throws IOException {
        ArrayList<RuntimeFile> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")
                    || !fields[1].matches("[A-Za-z0-9._-]+")) {
                throw new IOException("invalid packaged NVIDIA runtime manifest entry");
            }
            if (!names.add(fields[1])) {
                throw new IOException("duplicate packaged NVIDIA runtime manifest entry: " + fields[1]);
            }
            result.add(new RuntimeFile(fields[1], fields[0]));
        }
        if (result.isEmpty()) throw new IOException("packaged NVIDIA runtime manifest is empty");
        if (!names.contains(LIBRARY_NAME + ".dll")) {
            throw new IOException("packaged NVIDIA runtime manifest omits " + LIBRARY_NAME + ".dll");
        }
        return List.copyOf(result);
    }

    private static Path extractionDirectory(byte[] manifestBytes) {
        String identity = HexFormat.of().formatHex(digest().digest(manifestBytes));
        return Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-nvidia", identity);
    }

    private static void prepareExtractionDirectory(Path directory) throws IOException {
        Path root = directory.getParent();
        Files.createDirectories(root);
        requireRealDirectory(root);
        Files.createDirectories(directory);
        requireRealDirectory(directory);
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe packaged NVIDIA runtime extraction directory: " + directory);
        }
    }

    private static void extract(Path directory, RuntimeFile file) throws IOException {
        Path target = directory.resolve(file.name());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && file.sha256().equals(sha256(target))) return;
        Path temporary = Files.createTempFile(directory, file.name() + '.', ".tmp");
        boolean moved = false;
        try (InputStream input = requiredResource(RESOURCE_ROOT + file.name())) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!file.sha256().equals(sha256(temporary))) {
                throw new IOException("packaged NVIDIA runtime hash mismatch: " + file.name());
            }
            try {
                Files.move(
                        temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                moveReplacingOrAcceptConcurrentWinner(temporary, target, file);
            } catch (IOException concurrentMoveFailure) {
                // Another JVM can publish the same content-addressed extraction concurrently.
                // Accept only a fully written winner with the expected digest; every other
                // failure remains visible instead of loading an unverified DLL.
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !file.sha256().equals(sha256(target))) {
                    throw concurrentMoveFailure;
                }
                Files.deleteIfExists(temporary);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplacingOrAcceptConcurrentWinner(
            Path temporary,
            Path target,
            RuntimeFile file
    ) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException concurrentMoveFailure) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !file.sha256().equals(sha256(target))) {
                throw concurrentMoveFailure;
            }
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read != 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] readRequiredResource(String name) throws IOException {
        try (InputStream input = requiredResource(name)) {
            return input.readAllBytes();
        }
    }

    private static InputStream requiredResource(String name) throws IOException {
        InputStream input = NvidiaNativeLibraryLoader.class.getResourceAsStream(name);
        if (input == null) throw new IOException("missing classpath resource " + name);
        return input;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java runtime does not provide mandatory SHA-256", impossible);
        }
    }

    private record RuntimeFile(String name, String sha256) {
    }
}
