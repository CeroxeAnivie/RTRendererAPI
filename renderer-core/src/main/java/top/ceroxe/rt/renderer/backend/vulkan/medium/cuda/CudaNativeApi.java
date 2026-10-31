package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static java.lang.foreign.ValueLayout.*;

/** Instance-owned FFM bindings. No library downloads or process-wide path mutation. */
final class CudaNativeApi {
    static final String NVRTC_LIBRARY_PROPERTY = "top.ceroxe.rt.medium.cuda.nvrtcLibrary";
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup driver;
    private final SymbolLookup compiler;
    // Keep the matching compiler builtins loaded without changing PATH for the process.
    private final SymbolLookup builtins;
    private final Map<String, MethodHandle> calls = new HashMap<>();

    CudaNativeApi(Arena arena, Path nvrtcLibrary) {
        this(arena,nvrtcLibrary,false);
    }
    static CudaNativeApi compiler(Arena arena,Path nvrtcLibrary) {
        return new CudaNativeApi(arena,nvrtcLibrary,true);
    }
    private CudaNativeApi(Arena arena,Path nvrtcLibrary,boolean compilerOnly) {
        try {
            driver = compilerOnly?null:SymbolLookup.libraryLookup(isWindows() ? "nvcuda.dll" : "libcuda.so.1", arena);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("CUDA medium requires an available NVIDIA CUDA driver", e);
        }
        if(!compilerOnly){compiler=null;builtins=null;return;}
        try {
            compiler = nvrtcLibrary == null
                    ? SymbolLookup.libraryLookup(isWindows() ? "nvrtc64_120_0.dll" : "libnvrtc.so", arena)
                    : SymbolLookup.libraryLookup(nvrtcLibrary.toAbsolutePath(), arena);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("CUDA medium requires NVRTC. Set -D" + NVRTC_LIBRARY_PROPERTY
                    + "=<NVRTC library file>, or install NVRTC on the process library path", e);
        }
        SymbolLookup localBuiltins = null;
        if (nvrtcLibrary != null && isWindows()) {
            MemorySegment major = arena.allocate(JAVA_INT), minor = arena.allocate(JAVA_INT);
            checked("nvrtcVersion", args(ADDRESS, ADDRESS), major, minor);
            Path path = nvrtcLibrary.toAbsolutePath().getParent().resolve("nvrtc-builtins64_"
                    + major.get(JAVA_INT, 0) + minor.get(JAVA_INT, 0) + ".dll");
            if (!Files.isRegularFile(path))
                throw new IllegalStateException("Matching NVRTC builtins library does not exist beside compiler: " + path);
            localBuiltins = SymbolLookup.libraryLookup(path, arena);
        }
        builtins = localBuiltins;
    }
    static Path configuredNvrtcLibrary() {
        String configured = System.getProperty(NVRTC_LIBRARY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath();
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("NVRTC library does not exist: " + path);
            return path;
        }
        Path packaged = CudaNvrtcLibraryLoader.packagedLibrary();
        if (packaged != null) return packaged;
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null && !cudaPath.isBlank()) {
            Path directory = Path.of(cudaPath).resolve(isWindows() ? "bin" : "lib64");
            if (Files.isDirectory(directory)) try (Stream<Path> files = Files.list(directory)) {
                return files.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString();
                    return isWindows() ? n.matches("nvrtc64_[0-9_]+\\.dll") : n.startsWith("libnvrtc.so");
                }).sorted().findFirst().orElse(null);
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot inspect CUDA_PATH", e); }
        }
        return null;
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }
    int call(String name, MemoryLayout[] layouts, Object... arguments) {
        MethodHandle method = calls.computeIfAbsent(name, key -> linker.downcallHandle(
                (name.startsWith("nvrtc") ? compiler : driver).find(name)
                        .orElseThrow(() -> new IllegalStateException("Missing native CUDA symbol: " + name)),
                FunctionDescriptor.of(JAVA_INT, layouts)));
        try { return (int) method.invokeWithArguments(arguments); }
        catch (Throwable e) { throw new IllegalStateException("Native CUDA invocation failed: " + name, e); }
    }
    void checked(String name, MemoryLayout[] layouts, Object... arguments) {
        int result = call(name, layouts, arguments);
        if (result != 0) throw new IllegalStateException(name + " failed with native status " + result);
    }
    static MemoryLayout[] args(MemoryLayout... layouts) { return layouts; }
}
