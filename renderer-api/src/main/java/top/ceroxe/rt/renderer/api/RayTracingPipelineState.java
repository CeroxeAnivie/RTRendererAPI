package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable executable request for a ray-tracing pipeline and its explicit SBT group model. */
public final class RayTracingPipelineState {
    private final ShaderProgram program;
    private final List<RayTracingShaderGroup> shaderGroups;
    private final int maxRecursionDepth;
    private final String identityDigest;

    /** Validates program membership, unique group roles, and non-zero recursion depth. */
    public RayTracingPipelineState(ShaderProgram program, List<RayTracingShaderGroup> shaderGroups, int maxRecursionDepth) {
        this.program = Objects.requireNonNull(program, "program");
        if (program.kind() != ShaderProgram.Kind.RAY_TRACING) {
            throw new IllegalArgumentException("ray-tracing pipeline requires a RAY_TRACING shader program");
        }
        Objects.requireNonNull(shaderGroups, "shaderGroups");
        if (shaderGroups.isEmpty()) throw new IllegalArgumentException("ray-tracing pipeline requires shader groups");
        this.shaderGroups = shaderGroups.stream().map(group -> Objects.requireNonNull(group, "shader group")).toList();
        if (maxRecursionDepth <= 0) throw new IllegalArgumentException("ray recursion depth must be positive");
        this.maxRecursionDepth = maxRecursionDepth;
        validateMembers();
        this.identityDigest = computeIdentityDigest();
    }

    /** @return exact RT program */
    public ShaderProgram program() { return program; }
    /** @return immutable SBT groups in exact native group-index order */
    public List<RayTracingShaderGroup> shaderGroups() { return shaderGroups; }
    /** @return requested positive recursion depth */
    public int maxRecursionDepth() { return maxRecursionDepth; }

    /**
     * Returns a stable identity for native pipeline and SBT resources.
     *
     * <p>The identity includes shader bytes, stage/entry-point declarations, group order,
     * recursion depth, descriptor layout, and push-constant declarations. It is intentionally
     * independent of Java object identity so equivalent immutable requests share one native
     * pipeline and retirement always addresses the exact declaration.</p>
     */
    public String identityDigest() { return identityDigest; }

    private void validateMembers() {
        java.util.Map<RenderResourceId, ShaderModule> programModules = new java.util.HashMap<>();
        for (ShaderModule module : program.modules()) programModules.put(module.id(), module);
        int rayGenerationGroups = 0;
        for (RayTracingShaderGroup group : shaderGroups) {
            for (ShaderModule module : new ShaderModule[] { group.general().orElse(null), group.closestHit().orElse(null),
                    group.anyHit().orElse(null), group.intersection().orElse(null) }) {
                if (module != null && (programModules.get(module.id()) == null
                        || !sameModule(programModules.get(module.id()), module))) {
                    throw new IllegalArgumentException("ray-tracing group refers to a module absent from its program: " + module.id());
                }
                if (module != null && module.stage() == ShaderStage.RAY_GENERATION) rayGenerationGroups++;
            }
        }
        if (rayGenerationGroups != 1) {
            throw new IllegalArgumentException("ray-tracing pipeline requires exactly one ray-generation shader group");
        }
    }

    private String computeIdentityDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "RTRendererAPI:rt-pipeline:v2");
            update(digest, program.id().value());
            update(digest, program.version().value());
            update(digest, program.pushConstantByteSize());
            update(digest, program.bindingLayout().entries().size());
            for (BindingLayoutEntry entry : program.bindingLayout().entries()) {
                update(digest, entry.key().group());
                update(digest, entry.key().binding());
                update(digest, entry.type().name());
                update(digest, entry.arrayCount());
                update(digest, entry.dynamicOffset());
                update(digest, entry.visibleStages().size());
                for (ShaderStage stage : entry.visibleStages()) update(digest, stage.name());
            }
            update(digest, program.modules().size());
            for (ShaderModule module : program.modules()) {
                update(digest, module.id().value());
                update(digest, module.version().value());
                update(digest, module.stage().name());
                update(digest, module.entryPoint());
                ByteBuffer bytes = module.spirv();
                update(digest, bytes.remaining());
                digest.update(bytes);
                ShaderReflection reflection = module.reflection();
                update(digest, reflection.pushConstantByteSize());
                update(digest, reflection.bindings().size());
                for (BindingLayoutEntry entry : reflection.bindings()) update(digest, entry.toString());
                update(digest, reflection.inputs().size());
                for (ShaderInterfaceVariable input : reflection.inputs()) update(digest, input.toString());
                update(digest, reflection.outputs().size());
                for (ShaderInterfaceVariable output : reflection.outputs()) update(digest, output.toString());
                update(digest, reflection.immediateUniforms().size());
                for (ImmediateUniform uniform : reflection.immediateUniforms()) update(digest, uniform.toString());
            }
            update(digest, maxRecursionDepth);
            update(digest, shaderGroups.size());
            for (RayTracingShaderGroup group : shaderGroups) {
                update(digest, group.kind().name());
                updateModuleDigest(digest, group.general().orElse(null));
                updateModuleDigest(digest, group.closestHit().orElse(null));
                updateModuleDigest(digest, group.anyHit().orElse(null));
                updateModuleDigest(digest, group.intersection().orElse(null));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK must provide SHA-256", impossible);
        }
    }

    private static boolean sameModule(ShaderModule left, ShaderModule right) {
        return left.version().equals(right.version()) && left.stage() == right.stage()
                && left.entryPoint().equals(right.entryPoint()) && left.spirv().equals(right.spirv());
    }

    private static void updateModuleDigest(MessageDigest digest, ShaderModule module) {
        if (module == null) {
            update(digest, "<none>");
            return;
        }
        update(digest, module.id().value());
        update(digest, module.version().value());
        update(digest, module.stage().name());
        update(digest, module.entryPoint());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static void update(MessageDigest digest, int value) { update(digest, (long) value); }

    private static void update(MessageDigest digest, boolean value) { update(digest, value ? 1 : 0); }
}
