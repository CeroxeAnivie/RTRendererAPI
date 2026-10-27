package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Retained-scene shader stage replacements and application storage-buffer interface.
 *
 * <p>Omitted stages use the built-in shader. A replacement may be supplied as a complete GLSL
 * 460 module or as validated SPIR-V. SPIR-V is the preferred production path when an application
 * has a stable shader build; it avoids a cold-start shaderc optimization pass while keeping the
 * same pipeline and ABI validation. Ray generation can supply independent SDR and HDR binaries;
 * the other three stages are independent of output encoding. GLSL supplies missing variants.
 * A binary-only replacement fails explicitly when its requested variant is absent. Quoted
 * GLSL includes resolve inside the bundled {@code assets/rtrenderer/shaders/gpuscene/} shader
 * library. The public scene ABI is {@code gpuscene_common.glsl}; supplied includes and the
 * built-in stage sources ship in core.
 * Output/temporal/denoising contracts still apply to replacement shaders. A custom raygen must
 * write every signal it enables; requesting vendor/temporal features does not synthesize signals.
 * Use the explicit command API for arbitrary stage groups, procedural geometry and recursion.</p>
 *
 * <p>Application data binding i is a read-only std430 storage buffer at set 0, binding
 * {@code SceneShaderBindings.APPLICATION_DATA + i}, visible in all scene stages. Each
 * frame must supply exactly {@link #dataBindingCount()} immutable {@link ResourceData} values.
 * The payload uses ordinary storage buffers, with no prescribed application schema.</p>
 */
public final class SceneShaderProgram {
    private static final SceneShaderProgram BUILT_IN = builder().build();
    private final Map<ShaderStage, String> sources;
    private final Map<ShaderStage, byte[]> spirv;
    private final byte[] hdrRaygen;
    private final int dataBindingCount;
    private final int hash;

    private SceneShaderProgram(Builder builder) {
        sources = Collections.unmodifiableMap(new EnumMap<>(builder.sources));
        EnumMap<ShaderStage, byte[]> binaries = new EnumMap<>(ShaderStage.class);
        binaries.putAll(builder.spirv);
        spirv = Collections.unmodifiableMap(binaries);
        hdrRaygen = builder.hdrRaygen;
        dataBindingCount = builder.dataBindingCount;
        int value = Objects.hash(sources, dataBindingCount, java.util.Arrays.hashCode(hdrRaygen));
        for (var entry : spirv.entrySet()) {
            value = 31 * value + entry.getKey().hashCode();
            value = 31 * value + java.util.Arrays.hashCode(entry.getValue());
        }
        hash = value;
    }

    public static SceneShaderProgram builtIn() { return BUILT_IN; }
    public static Builder builder() { return new Builder(); }
    public Map<ShaderStage, String> sources() { return sources; }
    /** Returns defensive copies of the SDR raygen and output-independent hit/miss binaries. */
    public Map<ShaderStage, byte[]> spirv() {
        EnumMap<ShaderStage, byte[]> copy = new EnumMap<>(ShaderStage.class);
        spirv.forEach((stage, bytes) -> copy.put(stage, bytes.clone()));
        return Collections.unmodifiableMap(copy);
    }
    /** Returns whether a precompiled replacement exists for the requested stage. */
    public boolean hasSpirv(ShaderStage stage) {
        return spirv.containsKey(Objects.requireNonNull(stage, "stage"))
                || stage == ShaderStage.RAY_GENERATION && hdrRaygen != null;
    }
    /** Returns a defensive copy of one precompiled replacement, or {@code null} when absent. */
    public byte[] spirv(ShaderStage stage) {
        byte[] binary = spirv.get(Objects.requireNonNull(stage, "stage"));
        return binary == null ? null : binary.clone();
    }
    /** Returns the binary for an output encoding; hit and miss stages are output-independent. */
    public byte[] spirv(ShaderStage stage, FrameOutputFormat outputFormat) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(outputFormat, "outputFormat");
        if (stage == ShaderStage.RAY_GENERATION && outputFormat == FrameOutputFormat.LINEAR_HDR_RGBA16F) {
            return hdrRaygen == null ? null : hdrRaygen.clone();
        }
        return spirv(stage);
    }
    public int dataBindingCount() { return dataBindingCount; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof SceneShaderProgram program
                && dataBindingCount == program.dataBindingCount
                && sources.equals(program.sources)
                && java.util.Arrays.equals(hdrRaygen, program.hdrRaygen)
                && binaryMapsEqual(spirv, program.spirv);
    }
    @Override public int hashCode() {
        return hash;
    }
    @Override public String toString() {
        return "SceneShaderProgram[glslStages=" + sources.keySet()
                + ", spirvStages=" + spirv.keySet() + ", hdrRaygen=" + (hdrRaygen != null)
                + ", dataBindingCount=" + dataBindingCount + "]";
    }

    private static boolean binaryMapsEqual(Map<ShaderStage, byte[]> left, Map<ShaderStage, byte[]> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (ShaderStage stage : left.keySet()) {
            if (!java.util.Arrays.equals(left.get(stage), right.get(stage))) return false;
        }
        return true;
    }

    public static final class Builder {
        private final EnumMap<ShaderStage, String> sources = new EnumMap<>(ShaderStage.class);
        private final EnumMap<ShaderStage, byte[]> spirv = new EnumMap<>(ShaderStage.class);
        private byte[] hdrRaygen;
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

        /**
         * Supplies a complete SPIR-V replacement for one retained-scene stage.
         *
         * <p>The module must be a non-empty, four-byte aligned SPIR-V binary. The renderer still
         * validates its public descriptor ABI before creating the Vulkan pipeline. Supplying a
         * mutable array is safe: it is copied at registration time.</p>
         */
        public Builder spirv(ShaderStage stage, byte[] binary) {
            return spirv(stage, FrameOutputFormat.SDR_RGBA8, binary);
        }

        /**
         * Supplies a raygen binary for the selected output encoding. Hit/miss binaries use SDR
         * registration and are shared across both encodings. Source is used only if the exact
         * requested binary is absent, and never replaces an invalid supplied binary.
         */
        public Builder spirv(ShaderStage stage, FrameOutputFormat outputFormat, byte[] binary) {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(outputFormat, "outputFormat");
            if (stage != ShaderStage.RAY_GENERATION && stage != ShaderStage.RAY_MISS
                    && stage != ShaderStage.RAY_CLOSEST_HIT && stage != ShaderStage.RAY_ANY_HIT) {
                throw new IllegalArgumentException("not a retained-scene shader stage: " + stage);
            }
            if (stage != ShaderStage.RAY_GENERATION && outputFormat != FrameOutputFormat.SDR_RGBA8) {
                throw new IllegalArgumentException("hit/miss stages are output-independent; register their SDR binary");
            }
            Objects.requireNonNull(binary, "binary");
            if (binary.length < 20 || binary.length > 64 * 1024 * 1024 || (binary.length & 3) != 0) {
                throw new IllegalArgumentException("SPIR-V must have a complete aligned header and at most 64 MiB");
            }
            byte[] checked = binary.clone();
            var words = java.nio.ByteBuffer.wrap(checked).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (words.getInt(0) != 0x07230203 || words.getInt(12) <= 0 || words.getInt(16) != 0) {
                throw new IllegalArgumentException("SPIR-V binary has an invalid header");
            }
            if (outputFormat == FrameOutputFormat.LINEAR_HDR_RGBA16F) hdrRaygen = checked;
            else spirv.put(stage, checked);
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
