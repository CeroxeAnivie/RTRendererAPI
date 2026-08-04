package demo;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Executable-JAR bootstrap that grants the native access required by LWJGL.
 *
 * <p>The parent process intentionally loads no LWJGL class, so it can relaunch the same JAR with
 * native access before backend initialization. IDE and Gradle class-directory launches call the
 * Demo directly and keep their configured JVM policy.</p>
 */
public final class DemoLauncher {
    private static final String CHILD_PROPERTY = "rtrenderer.demo.launcher.child";
    private static final List<String> FORWARDED_SYSTEM_PROPERTIES = List.of(
            DemoFeatureProfile.PROPERTY,
            DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,
            DemoRendererProfile.FRAME_GENERATION_MULTIPLIER_PROPERTY
    );

    private DemoLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        Path codeSource = codeSourcePath();
        if (Boolean.getBoolean(CHILD_PROPERTY) || Files.isDirectory(codeSource)) {
            HexBallDemo.main(arguments);
            return;
        }

        Process child = new ProcessBuilder(childCommand(codeSource, arguments))
                .inheritIO()
                .start();
        int exitCode = child.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("RTRendererAPI Demo child exited with code " + exitCode);
        }
    }

    static List<String> childCommand(Path codeSource, String[] arguments) {
        Objects.requireNonNull(codeSource, "codeSource");
        Objects.requireNonNull(arguments, "arguments");
        List<String> command = new ArrayList<>(
                arguments.length + FORWARDED_SYSTEM_PROPERTIES.size() + 8
        );
        command.add(javaExecutable().toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-D" + CHILD_PROPERTY + "=true");
        for (String property : FORWARDED_SYSTEM_PROPERTIES) {
            String value = System.getProperty(property);
            if (value != null) {
                // ProcessBuilder inherits the environment, not JVM system properties. Forward
                // only reviewed Demo controls; copying all properties could leak credentials or
                // silently alter the native child runtime.
                command.add("-D" + property + '=' + value);
            }
        }
        command.add("-jar");
        command.add(codeSource.toAbsolutePath().normalize().toString());
        command.addAll(Arrays.asList(arguments));
        return List.copyOf(command);
    }

    private static Path codeSourcePath() {
        try {
            return Path.of(DemoLauncher.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException("Demo code-source path is malformed", malformed);
        }
    }

    private static Path javaExecutable() {
        Path executable = Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"
        ).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Java executable is unavailable: " + executable);
        }
        return executable;
    }
}
