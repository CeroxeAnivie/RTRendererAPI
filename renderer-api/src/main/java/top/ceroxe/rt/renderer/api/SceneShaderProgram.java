package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Retained-scene GLSL stage replacements and application storage-buffer interface.
 *
 * <p>Omitted stages use the built-in shader. A replacement is a complete GLSL 460 module with a
 * {@code main} entry point, compiled when the renderer opens. Quoted includes resolve inside the
 * bundled {@code assets/rtrenderer/shaders/gpuscene/} shader library. The public scene ABI is
 * {@code gpuscene_common.glsl}; supplied includes and the built-in stage sources ship in core.
 * Output/temporal/denoising contracts still apply to replacement shaders. A custom raygen must
 * write every signal it enables; requesting vendor/temporal features does not synthesize signals.
 * Use the explicit command API for arbitrary stage groups, procedural geometry and recursion.</p>
 *
 * <p>Application data binding i is a read-only std430 storage buffer at set 0, binding
 * {@code SceneShaderBindings.APPLICATION_DATA + i}, visible in all four scene stages. Each
 * frame must supply exactly {@link #dataBindingCount()} immutable {@link ResourceData} values.
 * The payload uses ordinary storage buffers, with no prescribed application schema.</p>
 */
public final class SceneShaderProgram {
    private static final SceneShaderProgram BUILT_IN = builder().build();
    private final Map<ShaderStage, String> sources;
    private final int dataBindingCount;

    private SceneShaderProgram(Builder builder) {
        sources = Collections.unmodifiableMap(new EnumMap<>(builder.sources));
        dataBindingCount = builder.dataBindingCount;
    }

    public static SceneShaderProgram builtIn() { return BUILT_IN; }
    public static Builder builder() { return new Builder(); }
    public Map<ShaderStage, String> sources() { return sources; }
    public int dataBindingCount() { return dataBindingCount; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof SceneShaderProgram program
                && dataBindingCount == program.dataBindingCount && sources.equals(program.sources);
    }
    @Override public int hashCode() { return Objects.hash(sources, dataBindingCount); }
    @Override public String toString() {
        return "SceneShaderProgram[stages=" + sources.keySet() + ", dataBindingCount=" + dataBindingCount + "]";
    }

    public static final class Builder {
        private final EnumMap<ShaderStage, String> sources = new EnumMap<>(ShaderStage.class);
        private int dataBindingCount;

        private Builder() { }

        public Builder shader(ShaderStage stage, String glsl) {
            Objects.requireNonNull(stage, "stage");
            if (stage != ShaderStage.RAY_GENERATION && stage != ShaderStage.RAY_MISS
                    && stage != ShaderStage.RAY_CLOSEST_HIT && stage != ShaderStage.RAY_ANY_HIT) {
                throw new IllegalArgumentException("not a retained-scene shader stage: " + stage);
            }
            if (Objects.requireNonNull(glsl, "glsl").isBlank()) {
                throw new IllegalArgumentException("shader source must not be blank");
            }
            sources.put(stage, glsl);
            return this;
        }

        /** Declares additional read-only storage buffers; actual device limits are checked at open. */
        public Builder dataBindingCount(int count) {
            if (count < 0 || count > 1024) throw new IllegalArgumentException("data binding count must be in [0, 1024]");
            dataBindingCount = count;
            return this;
        }

        public SceneShaderProgram build() { return new SceneShaderProgram(this); }
    }
}
