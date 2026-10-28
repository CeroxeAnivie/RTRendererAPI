package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import top.ceroxe.rt.renderer.RtEdgeSink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns shader resource loading, shaderc handles/results and Vulkan shader-module lifetime.
 */
final class RtShaderModuleCompiler {
    private static final Pattern LOCAL_INCLUDE = Pattern.compile(
            "(?m)^[\\t ]*#include[\\t ]+\"([^\"\\r\\n]+)\"[\\t ]*(?:\\r?\\n|$)"
    );
    private static final int MAX_INCLUDE_DEPTH = 16;
    private static final String DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY = "top.ceroxe.rt.oracleGBuffer.enabled";
    private static final String DISABLE_DYNAMIC_ANALYTIC_LOOP_PROPERTY =
            "top.ceroxe.rt.smoke.causality.disableDynamicAnalyticLoop";
    private static final String SURFACE_ONLY_PROPERTY =
            "top.ceroxe.rt.shader.surfaceOnly";
    private static final String SHADER_CACHE_DIRECTORY_PROPERTY = "top.ceroxe.rt.shaderCache.directory";
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final long MAX_CACHED_SPIRV_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_MEMORY_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final LinkedHashMap<String, byte[]> SCENE_SHADER_MEMORY_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static long sceneShaderMemoryBytes;
    private static final ConcurrentMap<String, CompletableFuture<byte[]>> SCENE_SHADER_IN_FLIGHT =
            new ConcurrentHashMap<>();
    // The set of bundled stage/variant resources is fixed for this class loader. Reopening a
    // renderer must not decompress and validate the same pinned binary for every pipeline.
    private static final ConcurrentMap<String, byte[]> PINNED_SPIRV = new ConcurrentHashMap<>();

    private RtShaderModuleCompiler() {
    }

    static long createModule(MemoryStack stack, VkDevice device, byte[] spirv) {
        if (spirv.length == 0 || spirv.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("SPIR-V bytecode size must be positive and 4-byte aligned");
        }
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try {
            code.put(spirv).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer handle = stack.longs(0L);
            checkVk(VK10.vkCreateShaderModule(device, createInfo, null, handle), "vkCreateShaderModule");
            return handle.get(0);
        } finally {
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(code));
        }
    }

    static byte[] compile(String resourcePath, int shaderKind, RtEdgeSink edges) {
        return compile(
                resourcePath,
                shaderKind,
                Boolean.getBoolean(DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY),
                false,
                false,
                edges
        );
    }

    static byte[] loadProduction(String resourcePath, int shaderKind, RtEdgeSink edges) {
        return loadProduction(resourcePath, shaderKind, false, edges);
    }

    static byte[] loadProduction(
            String resourcePath,
            int shaderKind,
            boolean linearHdrOutput,
            RtEdgeSink edges
    ) {
        return loadProduction(resourcePath, shaderKind, linearHdrOutput, false, edges);
    }

    static byte[] loadProduction(
            String resourcePath,
            int shaderKind,
            boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled,
            RtEdgeSink edges
    ) {
        RtEdgeSink checkedEdges = java.util.Objects.requireNonNull(edges, "edges");
        boolean diagnosticGBufferEnabled = Boolean.getBoolean(DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY);
        boolean disableDynamicAnalyticLoop = "assets/rtrenderer/shaders/bootstrap.rgen".equals(resourcePath)
                && checkedEdges.enabled()
                && Boolean.getBoolean(DISABLE_DYNAMIC_ANALYTIC_LOOP_PROPERTY);
        if (disableDynamicAnalyticLoop) {
            /* This fault-isolation variant is intentionally diagnostic-only and not a startup dependency. */
            return compile(
                    resourcePath, shaderKind, diagnosticGBufferEnabled, linearHdrOutput,
                    shaderExecutionReorderingEnabled, checkedEdges
            );
        }
        return loadPrecompiled(
                resourcePath, diagnosticGBufferEnabled, linearHdrOutput, shaderExecutionReorderingEnabled
        );
    }

    static byte[] compileForVerification(String resourcePath, int shaderKind, boolean diagnosticGBufferEnabled) {
        return compile(resourcePath, shaderKind, diagnosticGBufferEnabled, false, false, RtEdgeSink.NOOP);
    }

    static byte[] compileForVerification(
            String resourcePath,
            int shaderKind,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput
    ) {
        return compile(
                resourcePath, shaderKind, diagnosticGBufferEnabled, linearHdrOutput, false, RtEdgeSink.NOOP
        );
    }

    static byte[] compileForVerification(
            String resourcePath,
            int shaderKind,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled
    ) {
        return compile(
                resourcePath, shaderKind, diagnosticGBufferEnabled, linearHdrOutput,
                shaderExecutionReorderingEnabled, RtEdgeSink.NOOP
        );
    }

    static byte[] loadPrecompiled(String resourcePath, boolean diagnosticGBufferEnabled) {
        return loadPrecompiled(resourcePath, diagnosticGBufferEnabled, false);
    }

    static byte[] loadPrecompiled(
            String resourcePath,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput
    ) {
        return loadPrecompiled(resourcePath, diagnosticGBufferEnabled, linearHdrOutput, false);
    }

    static byte[] loadPrecompiled(
            String resourcePath,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled
    ) {
        String normalized = normalizeResourcePath(resourcePath);
        String shaderRoot = "assets/rtrenderer/shaders/";
        if (!normalized.startsWith(shaderRoot)) {
            throw new IllegalArgumentException("shader is outside the renderer shader root: " + resourcePath);
        }
        String variant = (diagnosticGBufferEnabled ? "gbuffer" : "base")
                + (linearHdrOutput ? "-hdr" : "")
                + (shaderExecutionReorderingEnabled ? "-ser" : "");
        String file = normalized.substring(shaderRoot.length()).replace('/', '_') + ".spv";
        String spirvPath = shaderRoot + "spv/" + variant + "/" + file;
        return PINNED_SPIRV.computeIfAbsent(spirvPath, path -> {
            byte[] spirv = readBinaryResource(path);
            if (spirv.length == 0 || (spirv.length & 3) != 0
                    || ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt() != SPIRV_MAGIC) {
                throw new IllegalStateException("invalid precompiled SPIR-V resource: " + path);
            }
            return spirv;
        }).clone();
    }

    static byte[] compileForDiagnosticVerification(String resourcePath, int shaderKind) {
        return compile(resourcePath, shaderKind, true, false, false, RtEdgeSink.NOOP);
    }

    static boolean usesDiagnosticGBuffer(String resourcePath) {
        return readExpandedUtf8Resource(resourcePath).contains("RTRENDERER_GBUFFER");
    }

    private static byte[] compile(
            String resourcePath,
            int shaderKind,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled,
            RtEdgeSink edges
    ) {
        return compileSource(readExpandedUtf8Resource(resourcePath), resourcePath, shaderKind,
                diagnosticGBufferEnabled, linearHdrOutput, shaderExecutionReorderingEnabled, edges);
    }

    static byte[] compileSceneSource(String source, String file, int kind, boolean hdr, boolean ser) {
        String path = "assets/rtrenderer/shaders/gpuscene/" + file;
        Matcher matcher = LOCAL_INCLUDE.matcher(source);
        StringBuffer expanded = new StringBuffer(source.length());
        while (matcher.find()) {
            String included = resolveInclude(path, matcher.group(1));
            if (!included.startsWith("assets/rtrenderer/shaders/gpuscene/")) {
                throw new IllegalArgumentException("scene includes must belong to the scene shader library");
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(readExpandedUtf8Resource(included)));
        }
        matcher.appendTail(expanded);
        boolean surfaceOnly = Boolean.getBoolean(SURFACE_ONLY_PROPERTY);
        return compileSceneSourceCached(expanded.toString(), path, kind, hdr, ser, surfaceOnly);
    }

    /**
     * Compiles one application scene stage once per exact source/options identity.
     *
     * <p>The public scene-shader contract accepts source text, so Vulkan's pipeline cache cannot
     * avoid the CPU shaderc step. This cache is keyed by the fully expanded source and every
     * compilation option, then validated as SPIR-V before reuse. The in-flight map also prevents
     * two renderer instances in one JVM from compiling the same stage concurrently.</p>
     */
    private static byte[] compileSceneSourceCached(
            String expandedSource, String path, int kind, boolean hdr, boolean ser, boolean surfaceOnly
    ) {
        String key = sceneShaderCacheKey(expandedSource, path, kind, hdr, ser, surfaceOnly);
        byte[] memory = sceneShaderMemoryGet(key);
        if (memory != null) return memory.clone();

        CompletableFuture<byte[]> pending = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = SCENE_SHADER_IN_FLIGHT.putIfAbsent(key, pending);
        if (existing != null) {
            try {
                return existing.join().clone();
            } catch (java.util.concurrent.CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw failure;
            }
        }
        try {
            byte[] cached = readSceneShaderCache(key);
            byte[] compiled = cached != null
                    ? cached
                    : compileSource(expandedSource, path, kind, false, hdr, ser, RtEdgeSink.NOOP);
            if (cached == null) persistSceneShaderCache(key, compiled);
            byte[] retained = compiled;
            sceneShaderMemoryPut(key, retained);
            pending.complete(retained);
            return retained.clone();
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("scene shader compilation failed", failure);
        } finally {
            SCENE_SHADER_IN_FLIGHT.remove(key, pending);
        }
    }

    private static synchronized byte[] sceneShaderMemoryGet(String key) {
        return SCENE_SHADER_MEMORY_CACHE.get(key);
    }

    private static synchronized void sceneShaderMemoryPut(String key, byte[] binary) {
        if (binary.length > MAX_MEMORY_CACHE_BYTES) return;
        byte[] previous = SCENE_SHADER_MEMORY_CACHE.put(key, binary);
        sceneShaderMemoryBytes += binary.length - (previous == null ? 0 : previous.length);
        while (sceneShaderMemoryBytes > MAX_MEMORY_CACHE_BYTES) {
            var oldest = SCENE_SHADER_MEMORY_CACHE.pollFirstEntry();
            sceneShaderMemoryBytes -= oldest.getValue().length;
        }
    }

    private static String sceneShaderCacheKey(
            String source, String path, int kind, boolean hdr, boolean ser, boolean surfaceOnly
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("rtrenderer-scene-spirv-v4-vulkan1.2-structured-volume\0".getBytes(StandardCharsets.UTF_8));
            digest.update(org.lwjgl.Version.getVersion().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(kind).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) (hdr ? 1 : 0));
            digest.update((byte) (ser ? 1 : 0));
            digest.update((byte) (surfaceOnly ? 1 : 0));
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static byte[] readSceneShaderCache(String key) {
        try {
            Path file = sceneShaderCacheDirectory().resolve(key + ".bin");
            if (!Files.isRegularFile(file)) return null;
            long size = Files.size(file);
            if (size < 52L || size > MAX_CACHED_SPIRV_BYTES + 32L || (size & 3L) != 0L) return null;
            byte[] stored = Files.readAllBytes(file);
            byte[] data = java.util.Arrays.copyOfRange(stored, 32, stored.length);
            if (!validSpirvHeader(data)
                    || !MessageDigest.isEqual(java.util.Arrays.copyOf(stored, 32), sha256(data))) return null;
            return data;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void persistSceneShaderCache(String key, byte[] spirv) {
        if (!validSpirvHeader(spirv) || spirv.length > MAX_CACHED_SPIRV_BYTES) return;
        Path temporary = null;
        try {
            Path directory = sceneShaderCacheDirectory();
            Files.createDirectories(directory);
            Path target = directory.resolve(key + ".bin");
            temporary = directory.resolve(key + "." + UUID.randomUUID() + ".tmp");
            ByteBuffer stored = ByteBuffer.allocate(32 + spirv.length);
            stored.put(sha256(spirv)).put(spirv);
            Files.write(temporary, stored.array(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException ignored) {
            // Shader caching is an optimization. A read-only or full cache directory must never
            // change renderer correctness; the freshly compiled module remains usable.
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static boolean validSpirvHeader(byte[] data) {
        if (data.length < 20 || (data.length & 3) != 0) return false;
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(0) == SPIRV_MAGIC && header.getInt(12) > 0 && header.getInt(16) == 0;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static Path sceneShaderCacheDirectory() {
        String override = System.getProperty(SHADER_CACHE_DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "RTRenderer", "shader-cache").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".rtrenderer", "shader-cache")
                .toAbsolutePath().normalize();
    }

    private static byte[] compileSource(String source, String resourcePath, int shaderKind,
            boolean diagnosticGBufferEnabled, boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled, RtEdgeSink edges) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc_compiler_initialize returned null");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc_compile_options_initialize returned null");
        }
        long result = 0L;
        ByteBuffer sourceUtf8 = null;
        ByteBuffer resourcePathUtf8 = null;
        ByteBuffer entryPointUtf8 = null;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2
            );
            // Exhaustive inlining of the extended volume/light call tree exhausts shaderc's
            // ID/memory budget and can fail in native optimization. Preserve its function
            // structure for the Vulkan driver's compiler; sampling and transport stay intact.
            boolean structuredVolume = source.contains("#define RTRENDERER_VOLUME_TRANSPORT");
            Shaderc.shaderc_compile_options_set_optimization_level(options, structuredVolume
                    ? Shaderc.shaderc_optimization_level_zero : Shaderc.shaderc_optimization_level_performance);
            boolean disableDynamicAnalyticLoop = "assets/rtrenderer/shaders/bootstrap.rgen".equals(resourcePath)
                    && edges.enabled()
                    && Boolean.getBoolean(DISABLE_DYNAMIC_ANALYTIC_LOOP_PROPERTY);
            if (disableDynamicAnalyticLoop) {
                Shaderc.shaderc_compile_options_add_macro_definition(
                        options, "RTRENDERER_DISABLE_DYNAMIC_ANALYTIC_LOOP", "1"
                );
                edges.edge("shaderCausality", "dynamicAnalyticLoop=disabledForCausality");
            }
            if (diagnosticGBufferEnabled) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, "RTRENDERER_GBUFFER", "1");
            }
            if (linearHdrOutput) {
                Shaderc.shaderc_compile_options_add_macro_definition(
                        options, "RTRENDERER_LINEAR_HDR_OUTPUT", "1"
                );
            }
            if (shaderExecutionReorderingEnabled) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, "RTRENDERER_SER", "1");
            }
            if (Boolean.getBoolean(SURFACE_ONLY_PROPERTY)
                    && resourcePath.startsWith("assets/rtrenderer/shaders/gpuscene/")) {
                Shaderc.shaderc_compile_options_add_macro_definition(
                        options, "RTRENDERER_SURFACE_ONLY", "1"
                );
            }
            /*
             * The CharSequence overload marshals the fully expanded source through LWJGL's
             * thread-local MemoryStack. GPUScene includes are intentionally substantial, so that
             * transient command stack is not a valid ownership boundary for shader source.
             */
            // Shaderc receives source length explicitly; including a terminal NUL would make it
            // part of the GLSL token stream. File name and entry point remain C strings.
            sourceUtf8 = MemoryUtil.memUTF8(source, false);
            resourcePathUtf8 = MemoryUtil.memUTF8(resourcePath);
            entryPointUtf8 = MemoryUtil.memUTF8("main");
            result = Shaderc.shaderc_compile_into_spv(
                    compiler, sourceUtf8, shaderKind, resourcePathUtf8, entryPointUtf8, options
            );
            if (result == 0L) {
                throw new IllegalStateException("shaderc_compile_into_spv returned null for " + resourcePath);
            }
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException(
                        "shaderc failed for " + resourcePath + ": " + Shaderc.shaderc_result_get_error_message(result)
                );
            }
            long length = Shaderc.shaderc_result_get_length(result);
            if (length <= 0L || length > Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "shaderc returned invalid SPIR-V length for " + resourcePath + ": " + length
                );
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result, length);
            byte[] copy = new byte[(int) length];
            bytes.get(0, copy);
            return copy;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            MemoryUtil.memFree(entryPointUtf8);
            MemoryUtil.memFree(resourcePathUtf8);
            MemoryUtil.memFree(sourceUtf8);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    static void destroyModule(VkDevice device, long shaderModule) {
        if (shaderModule != 0L) {
            VK10.vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    private static String readUtf8Resource(String resourcePath) {
        ClassLoader loader = RtShaderModuleCompiler.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("shader resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read shader resource: " + resourcePath, ex);
        }
    }

    private static byte[] readBinaryResource(String resourcePath) {
        ClassLoader loader = RtShaderModuleCompiler.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("shader resource not found: " + resourcePath);
            }
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read shader resource: " + resourcePath, ex);
        }
    }

    /**
     * Expands classpath-local includes before invoking shaderc.
     *
     * <p>Shader resources are immutable build inputs rather than files on an ambient working
     * directory. Resolving quoted includes here keeps production and self-test compilation
     * identical, rejects traversal, and makes include cycles deterministic diagnostics.</p>
     */
    static String readExpandedUtf8Resource(String resourcePath) {
        String root = normalizeResourcePath(resourcePath);
        return expandIncludes(root, new ArrayDeque<>(), new HashSet<>());
    }

    private static String expandIncludes(String resourcePath, ArrayDeque<String> stack, Set<String> active) {
        if (stack.size() >= MAX_INCLUDE_DEPTH) {
            throw new IllegalStateException("shader include depth exceeds " + MAX_INCLUDE_DEPTH + ": " + stack);
        }
        if (!active.add(resourcePath)) {
            throw new IllegalStateException("shader include cycle: " + stack + " -> " + resourcePath);
        }
        stack.addLast(resourcePath);
        try {
            String source = readUtf8Resource(resourcePath);
            Matcher matcher = LOCAL_INCLUDE.matcher(source);
            StringBuffer expanded = new StringBuffer(source.length());
            while (matcher.find()) {
                String included = resolveInclude(resourcePath, matcher.group(1));
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(
                        "// begin include: " + included + '\n'
                                + expandIncludes(included, stack, active)
                                + "\n// end include: " + included + '\n'
                ));
            }
            matcher.appendTail(expanded);
            return expanded.toString();
        } finally {
            stack.removeLast();
            active.remove(resourcePath);
        }
    }

    private static String resolveInclude(String owner, String included) {
        String candidate = included.startsWith("/")
                ? included.substring(1)
                : owner.substring(0, owner.lastIndexOf('/') + 1) + included;
        return normalizeResourcePath(candidate);
    }

    private static String normalizeResourcePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("shader resource path must use non-empty '/' separators");
        }
        Path normalized = Path.of(value).normalize();
        String path = normalized.toString().replace('\\', '/');
        if (normalized.isAbsolute() || path.equals("..") || path.startsWith("../")) {
            throw new IllegalArgumentException("shader resource path escapes the classpath root: " + value);
        }
        return path;
    }

    private static void checkVk(int result, String stage) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            default -> Integer.toString(result);
        };
    }
}
