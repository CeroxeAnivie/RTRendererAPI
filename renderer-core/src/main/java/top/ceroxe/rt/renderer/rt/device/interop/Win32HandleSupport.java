package top.ceroxe.rt.renderer.rt.device.interop;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WinBase;

import java.nio.IntBuffer;

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
    private static final long GET_HANDLE_INFORMATION =
            Kernel32.getLibrary().getFunctionAddress("GetHandleInformation");
    private static final long DUPLICATE_HANDLE =
            Kernel32.getLibrary().getFunctionAddress("DuplicateHandle");
    private static final int DUPLICATE_SAME_ACCESS = 0x00000002;
    private static final int ERROR_PROC_NOT_FOUND = 127;
    private static final ThreadLocal<Integer> LAST_CLOSE_ERROR = ThreadLocal.withInitial(() -> 0);

    private Win32HandleSupport() {
    }

    /**
     * Reports whether this process resolved the Win32 {@code CloseHandle} entry point.
     *
     * @return {@code true} when {@link #close(long)} can invoke {@code CloseHandle}
     */
    public static boolean available() {
        return CLOSE_HANDLE != 0L;
    }

    /**
     * Closes an owned handle; zero is treated as an idempotent no-op.
     *
     * <p>Ownership is relinquished only when this method returns {@code true}. On failure the
     * caller continues to own a nonzero handle and may inspect {@link #lastError()} or retry.</p>
     *
     * @param handle Win32 handle owned by the caller, or zero for no handle
     * @return {@code true} if the handle was closed or {@code handle} was zero; {@code false} if
     * {@code CloseHandle} was unavailable or rejected the handle
     */
    public static boolean close(long handle) {
        if (handle == 0L) {
            LAST_CLOSE_ERROR.set(0);
            return true;
        }
        if (CLOSE_HANDLE == 0L) {
            LAST_CLOSE_ERROR.set(ERROR_PROC_NOT_FOUND);
            return false;
        }
        boolean closed = JNI.invokePI(handle, CLOSE_HANDLE) != 0;
        LAST_CLOSE_ERROR.set(closed ? 0 : WinBase.GetLastError());
        return closed;
    }

    /**
     * Returns the error captured by this thread's most recent {@link #close(long)} invocation.
     *
     * @return zero after a successful close or zero-handle no-op; otherwise the Win32 error code
     */
    public static int lastError() {
        return LAST_CLOSE_ERROR.get();
    }

    /**
     * Tests whether the calling process can query a candidate kernel handle.
     *
     * <p>This method only observes the handle. It neither closes the handle nor changes its
     * ownership.</p>
     *
     * @param handle candidate Win32 handle, or zero
     * @return {@code true} if {@code GetHandleInformation} accepts the handle; {@code false} for
     * zero, an unavailable entry point, or an invalid handle
     */
    public static boolean valid(long handle) {
        if (handle == 0L || GET_HANDLE_INFORMATION == 0L) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer flags = stack.mallocInt(1);
            return JNI.invokePPI(handle, MemoryUtil.memAddress(flags), GET_HANDLE_INFORMATION) != 0;
        }
    }

    /**
     * Duplicates a kernel handle into the current process with identical access rights.
     *
     * <p>The source remains owned by its caller. A successful return transfers ownership of the
     * distinct duplicate to the caller, which must eventually pass it to {@link #close(long)}.</p>
     *
     * @param sourceHandle live, nonzero source handle; ownership is not transferred
     * @return nonzero duplicate independently owned by the caller
     * @throws IllegalArgumentException if {@code sourceHandle} is zero
     * @throws IllegalStateException    if {@code DuplicateHandle} is unavailable, fails, or produces
     *                                  a null target handle
     */
    public static long duplicate(long sourceHandle) {
        if (sourceHandle == 0L) throw new IllegalArgumentException("sourceHandle must not be null");
        if (DUPLICATE_HANDLE == 0L) {
            throw new IllegalStateException("DuplicateHandle is unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long process = Kernel32.GetCurrentProcess();
            PointerBuffer duplicate = stack.mallocPointer(1);
            long succeeded = JNI.invokePPPPP(
                    process,
                    sourceHandle,
                    process,
                    MemoryUtil.memAddress(duplicate),
                    0,
                    0,
                    DUPLICATE_SAME_ACCESS,
                    DUPLICATE_HANDLE
            );
            if (succeeded == 0L) {
                throw new IllegalStateException(
                        "DuplicateHandle failed for source=0x" + Long.toHexString(sourceHandle)
                                + ", error=" + WinBase.GetLastError());
            }
            long value = duplicate.get(0);
            if (value == 0L) {
                throw new IllegalStateException("DuplicateHandle succeeded with a null target handle");
            }
            return value;
        }
    }
}
