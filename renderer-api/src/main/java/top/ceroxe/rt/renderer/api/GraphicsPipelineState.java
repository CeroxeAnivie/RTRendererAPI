package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable composition of a graphics program, vertex input, attachment formats, and fixed state.
 *
 * <p>This is a validated pipeline request, not executable evidence. Backend admission must still
 * verify device limits, feature support, shader interfaces, and attachment compatibility before
 * reporting a compiled or executable pipeline.</p>
 */
public final class GraphicsPipelineState {
    private final ShaderProgram program;
    private final VertexLayout vertexLayout;
    private final PrimitiveTopology topology;
    private final boolean primitiveRestartEnabled;
    private final int patchControlPoints;
    private final RasterState rasterState;
    private final MultisampleState multisampleState;
    private final List<TextureFormat> colorTargetFormats;
    private final BlendState blendState;
    private final TextureFormat depthStencilFormat;
    private final DepthStencilState depthStencilState;

    private GraphicsPipelineState(Builder builder) {
        program = Objects.requireNonNull(builder.program, "program");
        if (program.kind() != ShaderProgram.Kind.GRAPHICS) {
            throw new IllegalArgumentException("graphics pipeline requires a graphics shader program");
        }
        vertexLayout = Objects.requireNonNull(builder.vertexLayout, "vertexLayout");
        topology = Objects.requireNonNull(builder.topology, "topology");
        primitiveRestartEnabled = builder.primitiveRestartEnabled;
        patchControlPoints = builder.patchControlPoints;
        rasterState = Objects.requireNonNull(builder.rasterState, "rasterState");
        validateShaderStages();
        validateVertexInterface();
        multisampleState = Objects.requireNonNull(builder.multisampleState, "multisampleState");
        colorTargetFormats = immutableColorFormats(builder.colorTargetFormats);
        blendState = Objects.requireNonNull(builder.blendState, "blendState");
        if (blendState.targets().size() != colorTargetFormats.size()) {
            throw new IllegalArgumentException("blend target count must equal color attachment count");
        }
        depthStencilFormat = builder.depthStencilFormat;
        depthStencilState = builder.depthStencilState;
        validateDepthStencilPair();
    }

    /** Starts a pipeline builder with conservative portable defaults. */
    public static Builder builder(ShaderProgram program) { return new Builder(program); }

    public ShaderProgram program() { return program; }
    public VertexLayout vertexLayout() { return vertexLayout; }
    public PrimitiveTopology topology() { return topology; }
    public boolean primitiveRestartEnabled() { return primitiveRestartEnabled; }
    public OptionalInt patchControlPoints() {
        return patchControlPoints == 0 ? OptionalInt.empty() : OptionalInt.of(patchControlPoints);
    }
    public RasterState rasterState() { return rasterState; }
    public MultisampleState multisampleState() { return multisampleState; }
    public List<TextureFormat> colorTargetFormats() { return colorTargetFormats; }
    public BlendState blendState() { return blendState; }
    public Optional<TextureFormat> depthStencilFormat() { return Optional.ofNullable(depthStencilFormat); }
    public Optional<DepthStencilState> depthStencilState() { return Optional.ofNullable(depthStencilState); }

    private static List<TextureFormat> immutableColorFormats(List<TextureFormat> formats) {
        Objects.requireNonNull(formats, "colorTargetFormats");
        ArrayList<TextureFormat> checked = new ArrayList<>(formats.size());
        for (TextureFormat format : formats) {
            TextureFormat value = Objects.requireNonNull(format, "color target format");
            if (!value.supports(TextureAspect.COLOR)) {
                throw new IllegalArgumentException("color attachment requires a color texture format: " + value);
            }
            checked.add(value);
        }
        return List.copyOf(checked);
    }

    private void validateDepthStencilPair() {
        if ((depthStencilFormat == null) != (depthStencilState == null)) {
            throw new IllegalArgumentException("depth/stencil format and state must be declared together");
        }
        if (depthStencilFormat == null) return;
        if (!depthStencilFormat.supports(TextureAspect.DEPTH)) {
            throw new IllegalArgumentException("depth/stencil attachment requires a depth texture format");
        }
        if (depthStencilState.stencilTestEnabled() && !depthStencilFormat.supports(TextureAspect.STENCIL)) {
            throw new IllegalArgumentException("stencil testing requires a format with a stencil aspect");
        }
    }

    private void validateShaderStages() {
        boolean hasTessellationControl = hasStage(ShaderStage.TESSELLATION_CONTROL);
        boolean hasTessellationEvaluation = hasStage(ShaderStage.TESSELLATION_EVALUATION);
        boolean hasTessellation = hasTessellationControl || hasTessellationEvaluation;
        if (topology.isPatchList()) {
            if (!hasTessellationControl || !hasTessellationEvaluation) {
                throw new IllegalArgumentException("patch topology requires paired tessellation shader stages");
            }
            if (patchControlPoints <= 0) {
                throw new IllegalArgumentException("patch topology requires a positive control-point count");
            }
        } else if (hasTessellation || patchControlPoints != 0) {
            throw new IllegalArgumentException("tessellation stages and patch control points require patch topology");
        }
        if (!hasStage(ShaderStage.FRAGMENT) && !rasterState.rasterizerDiscardEnabled()) {
            throw new IllegalArgumentException("graphics programs without a fragment stage require rasterizer discard");
        }
    }

    private void validateVertexInterface() {
        ShaderModule vertex = program.modules().stream()
                .filter(module -> module.stage() == ShaderStage.VERTEX)
                .findFirst().orElseThrow();
        java.util.Map<Integer, ShaderInterfaceVariable> inputs = vertex.reflection().inputs().stream()
                .collect(java.util.stream.Collectors.toMap(ShaderInterfaceVariable::location, value -> value));
        if (!inputs.keySet().equals(vertexLayout.attributesByLocation().keySet())) {
            throw new IllegalArgumentException("vertex layout locations must exactly match vertex shader inputs");
        }
        for (VertexAttribute attribute : vertexLayout.attributes()) {
            if (!inputs.get(attribute.shaderLocation()).type().accepts(attribute.format())) {
                throw new IllegalArgumentException("vertex format does not match shader input at location "
                        + attribute.shaderLocation());
            }
        }
    }

    private boolean hasStage(ShaderStage stage) {
        return program.modules().stream().anyMatch(module -> module.stage() == stage);
    }

    /** Single-thread-confined builder for an immutable graphics-pipeline request. */
    public static final class Builder {
        private final ShaderProgram program;
        private VertexLayout vertexLayout = VertexLayout.empty();
        private PrimitiveTopology topology = PrimitiveTopology.TRIANGLE_LIST;
        private boolean primitiveRestartEnabled;
        private int patchControlPoints;
        private RasterState rasterState = RasterState.filled();
        private MultisampleState multisampleState = MultisampleState.singleSample();
        private List<TextureFormat> colorTargetFormats = List.of();
        private BlendState blendState = BlendState.replace(0);
        private TextureFormat depthStencilFormat;
        private DepthStencilState depthStencilState;

        private Builder(ShaderProgram program) {
            this.program = Objects.requireNonNull(program, "program");
        }

        /** Selects exact vertex-buffer to shader-location mapping. */
        public Builder vertexLayout(VertexLayout value) {
            vertexLayout = Objects.requireNonNull(value, "vertexLayout");
            return this;
        }

        /** Selects primitive assembly and restart behavior. */
        public Builder primitiveAssembly(PrimitiveTopology value, boolean restartEnabled) {
            topology = Objects.requireNonNull(value, "topology");
            primitiveRestartEnabled = restartEnabled;
            patchControlPoints = 0;
            return this;
        }

        /** Selects patch assembly with a positive device-validated control-point count. */
        public Builder patchAssembly(int controlPoints) {
            return patchAssembly(controlPoints, false);
        }

        /** Selects patch assembly and its explicit primitive-restart request. */
        public Builder patchAssembly(int controlPoints, boolean restartEnabled) {
            if (controlPoints <= 0) throw new IllegalArgumentException("patch control points must be positive");
            topology = PrimitiveTopology.PATCH_LIST;
            primitiveRestartEnabled = restartEnabled;
            patchControlPoints = controlPoints;
            return this;
        }

        /** Selects primitive rasterization state. */
        public Builder rasterState(RasterState value) {
            rasterState = Objects.requireNonNull(value, "rasterState");
            return this;
        }

        /** Selects multisample coverage and shading state. */
        public Builder multisampleState(MultisampleState value) {
            multisampleState = Objects.requireNonNull(value, "multisampleState");
            return this;
        }

        /** Declares color attachment formats and exactly matching blend targets. */
        public Builder colorTargets(List<TextureFormat> formats, BlendState state) {
            colorTargetFormats = List.copyOf(Objects.requireNonNull(formats, "colorTargetFormats"));
            blendState = Objects.requireNonNull(state, "blendState");
            return this;
        }

        /** Declares one depth/stencil attachment format and its complete test state. */
        public Builder depthStencil(TextureFormat format, DepthStencilState state) {
            depthStencilFormat = Objects.requireNonNull(format, "depthStencilFormat");
            depthStencilState = Objects.requireNonNull(state, "depthStencilState");
            return this;
        }

        /** Removes the optional depth/stencil attachment declaration. */
        public Builder withoutDepthStencil() {
            depthStencilFormat = null;
            depthStencilState = null;
            return this;
        }

        /** Validates cross-state invariants and creates an immutable request. */
        public GraphicsPipelineState build() { return new GraphicsPipelineState(this); }
    }
}
