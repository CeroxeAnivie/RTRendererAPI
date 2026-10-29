import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/** Reproducible build-time packaging of the unmodified NVIDIA redistributable pair. */
public final class CudaNvrtcRuntime {
    public static final String VERSION = "12.9.86";
    public static final String ARCHIVE = "cuda_nvrtc-windows-x86_64-" + VERSION + "-archive.zip";
    public static final String URL = "https://developer.download.nvidia.com/compute/cuda/redist/cuda_nvrtc/windows-x86_64/" + ARCHIVE;
    public static final String SHA256 = "1aa0644fa53c8ca34cdc73db17bcc73530557bdd3f582c7bfdbd7916c8b48f65";
    public static final String RESOURCE_ROOT = "META-INF/native/cuda/windows-x86_64/";

    private CudaNvrtcRuntime() { }

    public static void prepare(File archiveFile, File resources, boolean offline) throws IOException {
        Path archive = archiveFile.toPath();
        if (!Files.isRegularFile(archive)) {
            if (offline) throw new IOException("Offline NVRTC archive missing; supply -PcudaNvrtcArchive=<" + ARCHIVE + ">");
            Files.createDirectories(archive.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(archive.toAbsolutePath().getParent(), "nvrtc-download-", ".tmp");
            try {
                var connection = URI.create(URL).toURL().openConnection();
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(120_000);
                try (InputStream input = connection.getInputStream()) {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                requireArchiveHash(temporary);
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        requireArchiveHash(archive);
        Path directory = resources.toPath().resolve(RESOURCE_ROOT);
        Files.createDirectories(directory);
        Map<String, String> members = new LinkedHashMap<>();
        members.put("nvrtc64_120_0.dll", "bin/nvrtc64_120_0.dll");
        members.put("nvrtc-builtins64_129.dll", "bin/nvrtc-builtins64_129.dll");
        members.put("LICENSE.txt", "LICENSE");
        String prefix = ARCHIVE.substring(0, ARCHIVE.length() - 4) + "/";
        StringBuilder manifest = new StringBuilder();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (var member : members.entrySet()) {
                var entry = zip.getEntry(prefix + member.getValue());
                if (entry == null || entry.isDirectory()) throw new IOException("NVRTC archive omits " + member.getValue());
                Path target = directory.resolve(member.getKey());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                manifest.append(sha256(target)).append('\t').append(member.getKey()).append('\n');
            }
        }
        Files.writeString(directory.resolve("runtime-files.sha256"), manifest, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("origin.properties"),
                "version=" + VERSION + "\nurl=" + URL + "\narchive.sha256=" + SHA256 + "\n", StandardCharsets.UTF_8);
    }

    private static void requireArchiveHash(Path archive) throws IOException {
        if (!SHA256.equals(sha256(archive))) throw new IOException("NVRTC archive SHA-256 differs from NVIDIA 12.9.1 redistributable index: " + archive);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
