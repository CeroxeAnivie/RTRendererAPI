package top.ceroxe.rt.renderer.nvidia;

import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WinBase;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prevents vendor helper processes from inheriting a Gradle JavaExec output pipe. */
public final class WindowsChildProcessIsolation {
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final long GET_STD_HANDLE = function("GetStdHandle");
    private static final long SET_HANDLE_INFORMATION = function("SetHandleInformation");
    private static final AtomicBoolean CLEANUP_INSTALLED = new AtomicBoolean();

    private WindowsChildProcessIsolation() {
    }

    /**
     * Makes this test JVM's standard handles non-inheritable before Streamline starts OTA helpers.
     *
     * <p>The signed production Streamline loader launches updater threads asynchronously and uses
     * broad Win32 handle inheritance. Without this test-process boundary, an updater can retain
     * Gradle's anonymous output pipe after the JavaExec child exits, leaving the build waiting for
     * EOF indefinitely. This class lives in {@code src/test}; application processes are untouched.</p>
     */
    public static void preventGradlePipeInheritance() {
        if (Platform.get() != Platform.WINDOWS) return;
        if (GET_STD_HANDLE == 0L || SET_HANDLE_INFORMATION == 0L) {
            throw new IllegalStateException("Win32 standard-handle isolation is unavailable");
        }
        clearInheritance(STD_INPUT_HANDLE);
        clearInheritance(STD_OUTPUT_HANDLE);
        clearInheritance(STD_ERROR_HANDLE);
        installVendorHelperCleanup();
    }

    private static void clearInheritance(int standardHandle) {
        long handle = JNI.invokeP(standardHandle, GET_STD_HANDLE);
        if (handle == 0L || handle == -1L) return;
        if (JNI.invokePI(handle, HANDLE_FLAG_INHERIT, 0, SET_HANDLE_INFORMATION) == 0) {
            throw new IllegalStateException(
                    "SetHandleInformation failed for standard handle " + standardHandle
                            + ", error=" + WinBase.GetLastError()
            );
        }
    }

    private static long function(String name) {
        return Platform.get() == Platform.WINDOWS
                ? Kernel32.getLibrary().getFunctionAddress(name)
                : 0L;
    }

    private static void installVendorHelperCleanup() {
        if (!CLEANUP_INSTALLED.compareAndSet(false, true)) return;
        ProcessHandle owner = ProcessHandle.current();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> owner.descendants()
                .filter(WindowsChildProcessIsolation::isNvidiaUpdater)
                .forEach(ProcessHandle::destroyForcibly), "rtrenderer-test-vendor-helper-cleanup"));
    }

    private static boolean isNvidiaUpdater(ProcessHandle process) {
        return process.info().command()
                .map(Path::of)
                .map(Path::getFileName)
                .map(Path::toString)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter("nvngx_update.exe"::equals)
                .isPresent();
    }
}
