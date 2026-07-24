package top.ceroxe.mcvulkanrt.renderer.rt.device.interop;

import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WinBase;

/**
 * Centralizes ownership release for Win32 NT handles exported by Vulkan.
 *
 * <p>Both Vulkan and external graphics API external-object specs keep handle lifetime in the
 * application for OPAQUE_WIN32 imports. Keeping CloseHandle behind one small
 * utility makes that ownership rule visible and avoids each probe inventing a
 * slightly different native call path.</p>
 */
public final class Win32HandleSupport {
    private static final long CLOSE_HANDLE = Kernel32.getLibrary().getFunctionAddress("CloseHandle");

    private Win32HandleSupport() {
    }

    public static boolean available() {
        return CLOSE_HANDLE != 0L;
    }

    public static boolean close(long handle) {
        if (handle == 0L) {
            return true;
        }
        return JNI.invokePI(handle, CLOSE_HANDLE) != 0;
    }

    public static int lastError() {
        return WinBase.GetLastError();
    }
}
