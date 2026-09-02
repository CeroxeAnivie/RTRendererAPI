package top.ceroxe.rt.renderer.api;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable requested composition of verified shader modules and one exact binding layout.
 *
 * <p>A program remains a request until a backend validates every module and creates an executable
 * pipeline. Merely constructing this object is never evidence of compilation or GPU execution.</p>
 */
public final class ShaderProgram {
    /** Program family with distinct legal stage composition. */
    public enum Kind { GRAPHICS, COMPUTE, RAY_TRACING }

    private final RenderResourceId id;
    private final ResourceVersion version;
    private final Kind kind;
    private final List<ShaderModule> modules;
    private final BindingLayout bindingLayout;
    private final int pushConstantByteSize;

    /**
     * Creates a shader-program request.
     *
     * @param id stable program identity
     * @param version published program generation
     * @param kind explicit program family
     * @param modules non-empty immutable module composition
     * @param bindingLayout exact resource interface used by all modules
     * @param pushConstantByteSize non-negative, four-byte-aligned program push-constant extent
     */
    public ShaderProgram(
            RenderResourceId id,
            ResourceVersion version,
            Kind kind,
            List<ShaderModule> modules,
            BindingLayout bindingLayout,
            int pushConstantByteSize
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = Objects.requireNonNull(version, "version");
        this.kind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(modules, "modules");
        if (modules.isEmpty()) throw new IllegalArgumentException("shader program must contain at least one module");
        this.modules = modules.stream().map(module -> Objects.requireNonNull(module, "shader module")).toList();
        this.bindingLayout = Objects.requireNonNull(bindingLayout, "bindingLayout");
        if (pushConstantByteSize < 0 || (pushConstantByteSize & 3) != 0) {
            throw new IllegalArgumentException("program push-constant byte size must be non-negative and four-byte aligned");
        }
        this.pushConstantByteSize = pushConstantByteSize;
        validateUniqueModules();
        validateStageComposition();
        validateInterfaces();
        validateStageLinkage();
    }

    /** @return stable program identity */
    public RenderResourceId id() { return id; }

    /** @return published program generation */
    public ResourceVersion version() { return version; }

    /** @return explicit program family */
    public Kind kind() { return kind; }

    /** @return non-empty immutable module composition */
    public List<ShaderModule> modules() { return modules; }

    /** @return exact immutable program binding layout */
    public BindingLayout bindingLayout() { return bindingLayout; }

    /** @return non-negative four-byte-aligned push-constant extent */
    public int pushConstantByteSize() { return pushConstantByteSize; }

    /** @return standalone numeric uniforms aggregated across the program's shader stages */
    public List<ImmediateUniform> immediateUniforms() {
        java.util.LinkedHashMap<String, ImmediateUniform> result = new java.util.LinkedHashMap<>();
        for (ShaderModule module : modules) {
            for (ImmediateUniform uniform : module.reflection().immediateUniforms()) {
                ImmediateUniform previous = result.putIfAbsent(uniform.name(), uniform);
                if (previous == null) continue;
                if (!previous.equals(uniform)) {
                    throw new IllegalStateException("inconsistent immediate uniform declaration: " + uniform.name());
                }
            }
        }
        return List.copyOf(result.values());
    }

    private void validateUniqueModules() {
        Set<RenderResourceId> identities = new HashSet<>();
        for (ShaderModule module : modules) {
            if (!identities.add(module.id())) {
                throw new IllegalArgumentException("shader program contains duplicate module identity: " + module.id());
            }
        }
    }

    private void validateStageComposition() {
        EnumMap<ShaderStage, Integer> counts = new EnumMap<>(ShaderStage.class);
        for (ShaderModule module : modules) counts.merge(module.stage(), 1, Integer::sum);
        switch (kind) {
            case GRAPHICS -> {
                if (counts.getOrDefault(ShaderStage.VERTEX, 0) != 1
                        || counts.getOrDefault(ShaderStage.FRAGMENT, 0) > 1
                        || counts.getOrDefault(ShaderStage.GEOMETRY, 0) > 1
                        || counts.getOrDefault(ShaderStage.TESSELLATION_CONTROL, 0) > 1
                        || counts.getOrDefault(ShaderStage.TESSELLATION_EVALUATION, 0) > 1
                        || counts.getOrDefault(ShaderStage.COMPUTE, 0) != 0
                        || counts.keySet().stream().anyMatch(ShaderProgram::isRayTracingStage)) {
                    throw new IllegalArgumentException("graphics programs require one vertex module and at most one of each optional graphics stage");
                }
                boolean control = counts.containsKey(ShaderStage.TESSELLATION_CONTROL);
                boolean evaluation = counts.containsKey(ShaderStage.TESSELLATION_EVALUATION);
                if (control != evaluation) {
                    throw new IllegalArgumentException("tessellation control and evaluation modules must be supplied together");
                }
            }
            case COMPUTE -> {
                if (modules.size() != 1 || counts.getOrDefault(ShaderStage.COMPUTE, 0) != 1) {
                    throw new IllegalArgumentException("compute programs require exactly one compute module");
                }
            }
            case RAY_TRACING -> {
                if (counts.getOrDefault(ShaderStage.RAY_GENERATION, 0) != 1) {
                    throw new IllegalArgumentException("ray-tracing programs require exactly one ray-generation module");
                }
                for (ShaderStage stage : counts.keySet()) {
                    if (!isRayTracingStage(stage)) {
                        throw new IllegalArgumentException("ray-tracing programs cannot contain raster or compute stages");
                    }
                }
            }
        }
    }

    private static boolean isRayTracingStage(ShaderStage stage) {
        return switch (stage) {
            case RAY_GENERATION, RAY_MISS, RAY_CLOSEST_HIT, RAY_ANY_HIT, RAY_INTERSECTION, CALLABLE -> true;
            default -> false;
        };
    }

    private void validateInterfaces() {
        java.util.Map<String, ImmediateUniform> uniforms = new java.util.LinkedHashMap<>();
        for (ShaderModule module : modules) {
            if (module.reflection().pushConstantByteSize() > pushConstantByteSize) {
                throw new IllegalArgumentException("program push-constant extent is smaller than a module requirement");
            }
            for (BindingLayoutEntry requirement : module.reflection().bindings()) {
                BindingLayoutEntry declared = bindingLayout.require(requirement.key());
                if (declared.type() != requirement.type()
                        || declared.arrayCount() != requirement.arrayCount()
                        || declared.dynamicOffset() != requirement.dynamicOffset()
                        || !declared.visibleStages().contains(module.stage())) {
                    throw new IllegalArgumentException("program binding layout does not satisfy module reflection at "
                            + requirement.key());
                }
            }
            for (ImmediateUniform uniform : module.reflection().immediateUniforms()) {
                if (uniform.endBytes() > pushConstantByteSize) {
                    throw new IllegalArgumentException("immediate uniform exceeds program push-constant extent: " + uniform.name());
                }
                ImmediateUniform previous = uniforms.putIfAbsent(uniform.name(), uniform);
                if (previous != null && (!previous.type().equals(uniform.type())
                        || previous.arrayCount() != uniform.arrayCount()
                        || previous.offsetBytes() != uniform.offsetBytes()
                        || previous.byteSize() != uniform.byteSize()
                        || !previous.stages().equals(uniform.stages()))) {
                    throw new IllegalArgumentException("inconsistent immediate uniform declaration: " + uniform.name());
                }
                if (!uniform.stages().contains(module.stage())) {
                    throw new IllegalArgumentException("immediate uniform visibility does not include module stage: " + uniform.name());
                }
            }
        }
        List<ImmediateUniform> declared = List.copyOf(uniforms.values());
        for (int i = 0; i < declared.size(); i++) {
            ImmediateUniform a = declared.get(i);
            for (int j = i + 1; j < declared.size(); j++) {
                ImmediateUniform b = declared.get(j);
                if (a.offsetBytes() < b.endBytes() && b.offsetBytes() < a.endBytes()) {
                    throw new IllegalArgumentException("immediate uniform ranges overlap: " + a.name() + " and " + b.name());
                }
            }
        }
    }

    private void validateStageLinkage() {
        if (kind != Kind.GRAPHICS) return;
        List<ShaderStage> order = List.of(
                ShaderStage.VERTEX, ShaderStage.TESSELLATION_CONTROL,
                ShaderStage.TESSELLATION_EVALUATION, ShaderStage.GEOMETRY, ShaderStage.FRAGMENT
        );
        ShaderModule producer = null;
        for (ShaderStage stage : order) {
            ShaderModule consumer = modules.stream().filter(module -> module.stage() == stage).findFirst().orElse(null);
            if (consumer == null) continue;
            if (producer != null) requireLinked(producer, consumer);
            producer = consumer;
        }
    }

    private static void requireLinked(ShaderModule producer, ShaderModule consumer) {
        java.util.Map<Integer, ShaderInterfaceVariable> outputs = producer.reflection().outputs().stream()
                .collect(java.util.stream.Collectors.toMap(ShaderInterfaceVariable::location, value -> value));
        for (ShaderInterfaceVariable input : consumer.reflection().inputs()) {
            ShaderInterfaceVariable output = outputs.get(input.location());
            if (output == null || !output.type().equals(input.type())
                    || output.interpolation() != input.interpolation()) {
                throw new IllegalArgumentException("shader stage interface mismatch at location "
                        + input.location() + " between " + producer.stage() + " and " + consumer.stage());
            }
        }
    }
}
