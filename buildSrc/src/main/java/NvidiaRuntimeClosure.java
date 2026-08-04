import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Single fail-closed verifier for every NVIDIA runtime-bearing archive produced by this build. */
public final class NvidiaRuntimeClosure {
    public static final String ROOT = "META-INF/native/windows-x86_64/";
    public static final String MANIFEST = "runtime-files.sha256";
    public static final List<String> REQUIRED_FILES = List.of(
            "rtrenderer_nvidia.dll",
            "sl.interposer.dll",
            "sl.common.dll",
            "sl.dlss.dll",
            "sl.dlss_g.dll",
            "sl.nis.dll",
            "sl.reflex.dll",
            "sl.pcl.dll"
    );

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private NvidiaRuntimeClosure() {
    }

    public static void verify(File archiveFile, String artifactLabel) {
        if (archiveFile == null || !archiveFile.isFile()) {
            throw new GradleException(artifactLabel + " archive does not exist: " + archiveFile);
        }
        try (ZipFile archive = new ZipFile(archiveFile)) {
            ZipEntry manifestEntry = archive.getEntry(ROOT + MANIFEST);
            if (manifestEntry == null) {
                throw new GradleException(artifactLabel + " omits the NVIDIA runtime manifest");
            }
            Map<String, String> manifest = readManifest(archive, manifestEntry, artifactLabel);
            List<String> missing = REQUIRED_FILES.stream().filter(name -> !manifest.containsKey(name)).toList();
            if (!missing.isEmpty()) {
                throw new GradleException(artifactLabel + " NVIDIA runtime is incomplete: " + missing);
            }
            for (Map.Entry<String, String> item : manifest.entrySet()) {
                ZipEntry runtime = archive.getEntry(ROOT + item.getKey());
                if (runtime == null) {
                    throw new GradleException(
                            artifactLabel + " NVIDIA runtime manifest references missing file: " + item.getKey()
                    );
                }
                String actual = digest(archive, runtime);
                if (!actual.equals(item.getValue())) {
                    throw new GradleException(
                            artifactLabel + " NVIDIA runtime hash mismatch for " + item.getKey()
                                    + ": expected=" + item.getValue() + ", actual=" + actual
                    );
                }
            }
        } catch (IOException failure) {
            throw new GradleException("Failed to verify " + artifactLabel + ": " + archiveFile, failure);
        }
    }

    private static Map<String, String> readManifest(
            ZipFile archive,
            ZipEntry entry,
            String artifactLabel
    ) throws IOException {
        List<String> lines;
        try (InputStream input = archive.getInputStream(entry)) {
            lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        }
        Map<String, String> result = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || !HASH.matcher(fields[0]).matches()
                    || !FILE_NAME.matcher(fields[1]).matches()) {
                throw new GradleException("Invalid NVIDIA runtime manifest line: " + line);
            }
            if (!names.add(fields[1])) {
                throw new GradleException(
                        artifactLabel + " NVIDIA runtime manifest contains duplicate name: " + fields[1]
                );
            }
            result.put(fields[1], fields[0]);
        }
        return Map.copyOf(result);
    }

    private static String digest(ZipFile archive, ZipEntry entry) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java runtime does not provide SHA-256", impossible);
        }
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
