package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable compute pipeline request wrapping an explicitly compute shader program. */
public final class ComputePipelineState {
    private final ShaderProgram program;

    public ComputePipelineState(ShaderProgram program) {
        this.program = Objects.requireNonNull(program, "program");
        if (program.kind() != ShaderProgram.Kind.COMPUTE) {
            throw new IllegalArgumentException("compute pipeline requires a compute shader program");
        }
    }

    public ShaderProgram program() { return program; }
}
