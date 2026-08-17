package top.ceroxe.rt.renderer.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable executable request for a ray-tracing pipeline and its explicit SBT group model. */
public final class RayTracingPipelineState {
    private final ShaderProgram program;
    private final List<RayTracingShaderGroup> shaderGroups;
    private final int maxRecursionDepth;

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
    }

    /** @return exact RT program */
    public ShaderProgram program() { return program; }
    /** @return immutable SBT groups in exact native group-index order */
    public List<RayTracingShaderGroup> shaderGroups() { return shaderGroups; }
    /** @return requested positive recursion depth */
    public int maxRecursionDepth() { return maxRecursionDepth; }

    private void validateMembers() {
        Set<RenderResourceId> programModules = new HashSet<>();
        for (ShaderModule module : program.modules()) programModules.add(module.id());
        int rayGenerationGroups = 0;
        for (RayTracingShaderGroup group : shaderGroups) {
            for (ShaderModule module : new ShaderModule[] { group.general().orElse(null), group.closestHit().orElse(null),
                    group.anyHit().orElse(null), group.intersection().orElse(null) }) {
                if (module != null && !programModules.contains(module.id())) {
                    throw new IllegalArgumentException("ray-tracing group refers to a module absent from its program: " + module.id());
                }
                if (module != null && module.stage() == ShaderStage.RAY_GENERATION) rayGenerationGroups++;
            }
        }
        if (rayGenerationGroups != 1) {
            throw new IllegalArgumentException("ray-tracing pipeline requires exactly one ray-generation shader group");
        }
    }
}
