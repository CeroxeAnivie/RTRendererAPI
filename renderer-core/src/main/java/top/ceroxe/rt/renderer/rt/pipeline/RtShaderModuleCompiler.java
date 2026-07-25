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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
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
            MemoryUtil.memFree(code);
        }
    }

    static byte[] compile(String resourcePath, int shaderKind, RtEdgeSink edges) {
        return compile(
                resourcePath,
                shaderKind,
                Boolean.getBoolean(DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY),
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
        RtEdgeSink checkedEdges = java.util.Objects.requireNonNull(edges, "edges");
        boolean diagnosticGBufferEnabled = Boolean.getBoolean(DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY);
        boolean disableDynamicAnalyticLoop = "assets/rtrenderer/shaders/bootstrap.rgen".equals(resourcePath)
                && checkedEdges.enabled()
                && Boolean.getBoolean(DISABLE_DYNAMIC_ANALYTIC_LOOP_PROPERTY);
        if (disableDynamicAnalyticLoop) {
            /* This fault-isolation variant is intentionally diagnostic-only and not a startup dependency. */
            return compile(resourcePath, shaderKind, diagnosticGBufferEnabled, linearHdrOutput, checkedEdges);
        }
        return loadPrecompiled(resourcePath, diagnosticGBufferEnabled, linearHdrOutput);
    }

    static byte[] compileForVerification(String resourcePath, int shaderKind, boolean diagnosticGBufferEnabled) {
        return compile(resourcePath, shaderKind, diagnosticGBufferEnabled, false, RtEdgeSink.NOOP);
    }

    static byte[] compileForVerification(
            String resourcePath,
            int shaderKind,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput
    ) {
        return compile(resourcePath, shaderKind, diagnosticGBufferEnabled, linearHdrOutput, RtEdgeSink.NOOP);
    }

    static byte[] loadPrecompiled(String resourcePath, boolean diagnosticGBufferEnabled) {
        return loadPrecompiled(resourcePath, diagnosticGBufferEnabled, false);
    }

    static byte[] loadPrecompiled(
            String resourcePath,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput
    ) {
        String normalized = normalizeResourcePath(resourcePath);
        String shaderRoot = "assets/rtrenderer/shaders/";
        if (!normalized.startsWith(shaderRoot)) {
            throw new IllegalArgumentException("shader is outside the renderer shader root: " + resourcePath);
        }
        String variant = (diagnosticGBufferEnabled ? "gbuffer" : "base")
                + (linearHdrOutput ? "-hdr" : "");
        String file = normalized.substring(shaderRoot.length()).replace('/', '_') + ".spv";
        String spirvPath = shaderRoot + "spv/" + variant + "/" + file;
        byte[] spirv = readBinaryResource(spirvPath);
        if (spirv.length == 0 || (spirv.length & 3) != 0
                || ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0x07230203) {
            throw new IllegalStateException("invalid precompiled SPIR-V resource: " + spirvPath);
        }
        return spirv;
    }

    static byte[] compileForDiagnosticVerification(String resourcePath, int shaderKind) {
        return compile(resourcePath, shaderKind, true, false, RtEdgeSink.NOOP);
    }

    private static byte[] compile(
            String resourcePath,
            int shaderKind,
            boolean diagnosticGBufferEnabled,
            boolean linearHdrOutput,
            RtEdgeSink edges
    ) {
        String source = readExpandedUtf8Resource(resourcePath);
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
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2
            );
            Shaderc.shaderc_compile_options_set_optimization_level(
                    options, Shaderc.shaderc_optimization_level_performance
            );
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
            result = Shaderc.shaderc_compile_into_spv(compiler, source, shaderKind, resourcePath, "main", options);
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
    private static String readExpandedUtf8Resource(String resourcePath) {
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
