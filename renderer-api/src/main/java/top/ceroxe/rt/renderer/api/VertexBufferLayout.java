package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable stride and stepping declaration for one vertex-buffer binding. */
public final class VertexBufferLayout {
    /** Determines which draw counter advances the buffer element. */
    public enum StepMode {
        VERTEX,
        INSTANCE
    }

    private final int binding;
    private final int strideBytes;
    private final StepMode stepMode;
    private final int stepRate;

    /**
     * Creates a vertex-buffer layout.
     *
     * @param binding non-negative binding number
     * @param strideBytes non-negative element stride in bytes; zero repeatedly addresses one element
     * @param stepMode vertex- or instance-index stepping
     * @param stepRate one for vertex stepping; non-negative instance divisor for instance stepping
     */
    public VertexBufferLayout(int binding, int strideBytes, StepMode stepMode, int stepRate) {
        if (binding < 0) throw new IllegalArgumentException("vertex-buffer binding must be non-negative");
        if (strideBytes < 0) throw new IllegalArgumentException("vertex-buffer stride must be non-negative");
        this.stepMode = Objects.requireNonNull(stepMode, "stepMode");
        if (stepRate < 0) throw new IllegalArgumentException("vertex-buffer step rate must be non-negative");
        if (stepMode == StepMode.VERTEX && stepRate != 1) {
            throw new IllegalArgumentException("per-vertex buffer layouts require a step rate of one");
        }
        this.binding = binding;
        this.strideBytes = strideBytes;
        this.stepRate = stepRate;
    }

    /** Creates a per-vertex binding with a step rate of one. */
    public static VertexBufferLayout perVertex(int binding, int strideBytes) {
        return new VertexBufferLayout(binding, strideBytes, StepMode.VERTEX, 1);
    }

    /** Creates a per-instance binding with the supplied non-negative divisor. */
    public static VertexBufferLayout perInstance(int binding, int strideBytes, int stepRate) {
        return new VertexBufferLayout(binding, strideBytes, StepMode.INSTANCE, stepRate);
    }

    /** @return non-negative binding number */
    public int binding() { return binding; }

    /** @return non-negative element stride */
    public int strideBytes() { return strideBytes; }

    /** @return element stepping domain */
    public StepMode stepMode() { return stepMode; }

    /** @return one for vertex stepping or a non-negative instance divisor */
    public int stepRate() { return stepRate; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof VertexBufferLayout that)) return false;
        return binding == that.binding && strideBytes == that.strideBytes
                && stepRate == that.stepRate && stepMode == that.stepMode;
    }

    @Override public int hashCode() { return Objects.hash(binding, strideBytes, stepMode, stepRate); }

    @Override public String toString() {
        return "VertexBufferLayout[binding=" + binding + ", strideBytes=" + strideBytes
                + ", stepMode=" + stepMode + ", stepRate=" + stepRate + ']';
    }
}
