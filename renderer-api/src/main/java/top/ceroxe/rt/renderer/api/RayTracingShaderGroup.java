package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/** One explicit shader-binding-table group in a {@link RayTracingPipelineState}. */
public final class RayTracingShaderGroup {
    /** Native-compatible group topology. */
    public enum Kind { GENERAL, TRIANGLES_HIT, PROCEDURAL_HIT }

    private final Kind kind;
    private final ShaderModule general;
    private final ShaderModule closestHit;
    private final ShaderModule anyHit;
    private final ShaderModule intersection;

    private RayTracingShaderGroup(
            Kind kind, ShaderModule general, ShaderModule closestHit, ShaderModule anyHit, ShaderModule intersection
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.general = general;
        this.closestHit = closestHit;
        this.anyHit = anyHit;
        this.intersection = intersection;
        validate();
    }

    /** Creates one ray-generation, miss, or callable general group. */
    public static RayTracingShaderGroup general(ShaderModule module) {
        return new RayTracingShaderGroup(Kind.GENERAL, Objects.requireNonNull(module, "module"), null, null, null);
    }

    /** Creates one triangle hit group with optional closest-hit and any-hit shaders. */
    public static RayTracingShaderGroup triangles(ShaderModule closestHit, ShaderModule anyHit) {
        return new RayTracingShaderGroup(Kind.TRIANGLES_HIT, null, closestHit, anyHit, null);
    }

    /** Creates one procedural hit group, which must supply an intersection shader. */
    public static RayTracingShaderGroup procedural(ShaderModule closestHit, ShaderModule anyHit, ShaderModule intersection) {
        return new RayTracingShaderGroup(Kind.PROCEDURAL_HIT, null, closestHit, anyHit,
                Objects.requireNonNull(intersection, "intersection"));
    }

    /** @return group topology */
    public Kind kind() { return kind; }
    /** @return present general shader */
    public Optional<ShaderModule> general() { return Optional.ofNullable(general); }
    /** @return optional closest-hit shader */
    public Optional<ShaderModule> closestHit() { return Optional.ofNullable(closestHit); }
    /** @return optional any-hit shader */
    public Optional<ShaderModule> anyHit() { return Optional.ofNullable(anyHit); }
    /** @return present intersection shader only for procedural groups */
    public Optional<ShaderModule> intersection() { return Optional.ofNullable(intersection); }

    private void validate() {
        switch (kind) {
            case GENERAL -> {
                requireStage(general, ShaderStage.RAY_GENERATION, ShaderStage.RAY_MISS, ShaderStage.CALLABLE);
                if (closestHit != null || anyHit != null || intersection != null) {
                    throw new IllegalArgumentException("general RT groups cannot contain hit shaders");
                }
            }
            case TRIANGLES_HIT -> {
                if (general != null || intersection != null) {
                    throw new IllegalArgumentException("triangle hit groups cannot contain general or intersection shaders");
                }
                requireOptionalStage(closestHit, ShaderStage.RAY_CLOSEST_HIT);
                requireOptionalStage(anyHit, ShaderStage.RAY_ANY_HIT);
                if (closestHit == null && anyHit == null) {
                    throw new IllegalArgumentException("triangle hit groups require a closest-hit or any-hit shader");
                }
            }
            case PROCEDURAL_HIT -> {
                if (general != null) throw new IllegalArgumentException("procedural hit groups cannot contain a general shader");
                requireOptionalStage(closestHit, ShaderStage.RAY_CLOSEST_HIT);
                requireOptionalStage(anyHit, ShaderStage.RAY_ANY_HIT);
                requireStage(intersection, ShaderStage.RAY_INTERSECTION);
            }
        }
    }

    private static void requireOptionalStage(ShaderModule module, ShaderStage stage) {
        if (module != null) requireStage(module, stage);
    }

    private static void requireStage(ShaderModule module, ShaderStage... stages) {
        Objects.requireNonNull(module, "shader module");
        for (ShaderStage stage : stages) if (module.stage() == stage) return;
        throw new IllegalArgumentException("shader stage is not legal for this RT group: " + module.stage());
    }
}
