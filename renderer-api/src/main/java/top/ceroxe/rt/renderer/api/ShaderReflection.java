package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/**
 * Caller-declared, backend-verifiable interface reflected from one shader module.
 *
 * <p>This declaration is evidence to validate, not evidence that a backend compiled the module.
 * Admission must compare it with backend reflection before executable status can be published.</p>
 */
public final class ShaderReflection {
    private final List<BindingLayoutEntry> bindings;
    private final int pushConstantByteSize;
    private final List<ShaderInterfaceVariable> inputs;
    private final List<ShaderInterfaceVariable> outputs;
    private final List<ImmediateUniform> immediateUniforms;

    /**
     * Creates a reflected interface declaration.
     *
     * @param bindings possibly empty binding declarations used by this module
     * @param pushConstantByteSize non-negative byte size required by this module
     */
    public ShaderReflection(List<BindingLayoutEntry> bindings, int pushConstantByteSize) {
        this(bindings, pushConstantByteSize, List.of(), List.of(), List.of());
    }

    /** Creates reflection including exact user-defined stage inputs and outputs. */
    public ShaderReflection(
            List<BindingLayoutEntry> bindings,
            int pushConstantByteSize,
            List<ShaderInterfaceVariable> inputs,
            List<ShaderInterfaceVariable> outputs
    ) {
        this(bindings, pushConstantByteSize, inputs, outputs, List.of());
    }

    /** Creates reflection including stage interfaces and standalone numeric uniforms. */
    public ShaderReflection(
            List<BindingLayoutEntry> bindings,
            int pushConstantByteSize,
            List<ShaderInterfaceVariable> inputs,
            List<ShaderInterfaceVariable> outputs,
            List<ImmediateUniform> immediateUniforms
    ) {
        this.bindings = new BindingLayout(Objects.requireNonNull(bindings, "bindings")).entries();
        if (pushConstantByteSize < 0 || (pushConstantByteSize & 3) != 0) {
            throw new IllegalArgumentException("push-constant byte size must be non-negative and four-byte aligned");
        }
        this.pushConstantByteSize = pushConstantByteSize;
        this.inputs = interfaceVariables(inputs, "inputs");
        this.outputs = interfaceVariables(outputs, "outputs");
        this.immediateUniforms = immediateUniforms(immediateUniforms);
    }

    /** @return immutable reflected binding declarations */
    public List<BindingLayoutEntry> bindings() { return bindings; }

    /** @return non-negative four-byte-aligned push-constant extent */
    public int pushConstantByteSize() { return pushConstantByteSize; }

    /** @return immutable location-unique user-defined stage inputs */
    public List<ShaderInterfaceVariable> inputs() { return inputs; }

    /** @return immutable location-unique user-defined stage outputs */
    public List<ShaderInterfaceVariable> outputs() { return outputs; }

    /** @return immutable standalone numeric uniforms backed by push-constant storage */
    public List<ImmediateUniform> immediateUniforms() { return immediateUniforms; }

    private static List<ShaderInterfaceVariable> interfaceVariables(
            List<ShaderInterfaceVariable> values, String name
    ) {
        Objects.requireNonNull(values, name);
        java.util.LinkedHashMap<Integer, ShaderInterfaceVariable> indexed = new java.util.LinkedHashMap<>();
        for (ShaderInterfaceVariable value : values) {
            ShaderInterfaceVariable checked = Objects.requireNonNull(value, name + " element");
            if (indexed.putIfAbsent(checked.location(), checked) != null) {
                throw new IllegalArgumentException(name + " contains duplicate location " + checked.location());
            }
        }
        return List.copyOf(indexed.values());
    }

    private static List<ImmediateUniform> immediateUniforms(List<ImmediateUniform> values) {
        Objects.requireNonNull(values, "immediateUniforms");
        java.util.LinkedHashMap<String, ImmediateUniform> indexed = new java.util.LinkedHashMap<>();
        for (ImmediateUniform value : values) {
            ImmediateUniform checked = Objects.requireNonNull(value, "immediate uniform element");
            if (indexed.putIfAbsent(checked.name(), checked) != null) {
                throw new IllegalArgumentException("immediateUniforms contains duplicate name " + checked.name());
            }
        }
        return List.copyOf(indexed.values());
    }
}
