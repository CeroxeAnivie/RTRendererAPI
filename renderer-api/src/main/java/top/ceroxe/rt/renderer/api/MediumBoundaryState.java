package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Declares that a closed, consistently wound instance bounds a participating medium.
 *
 * <p>The boundary owns immutable interior-medium coefficients independently of surface materials.
 * Instances are tracked independently, so one medium definition can be reused by many volumes.
 * When volumes overlap, the active boundary with the greatest priority supplies the medium;
 * equal priorities use most-recent entry order and restore the prior medium on exit.</p>
 */
public final class MediumBoundaryState {
    private final EnvironmentState.Medium medium;
    private final int priority;
    private final Orientation orientation;
    private final boolean surfaceInteraction;

    private MediumBoundaryState(EnvironmentState.Medium medium, int priority, Orientation orientation, boolean surfaceInteraction) {
        this.medium = Objects.requireNonNull(medium, "medium");
        this.priority = priority;
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.surfaceInteraction = surfaceInteraction;
        if (medium.scatteringRed() > medium.extinctionRed() || medium.scatteringGreen() > medium.extinctionGreen()
                || medium.scatteringBlue() > medium.extinctionBlue())
            throw new IllegalArgumentException("boundary scattering must not exceed total extinction");
    }

    public static Builder builder(EnvironmentState.Medium medium) { return new Builder(medium); }
    public EnvironmentState.Medium medium() { return medium; }
    public int priority() { return priority; }
    public Orientation orientation() { return orientation; }
    /** False selects an invisible volume interface, useful for bounded fog or emissive gas. */
    public boolean surfaceInteraction() { return surfaceInteraction; }

    public Builder toBuilder() { return new Builder(medium).priority(priority).orientation(orientation).surfaceInteraction(surfaceInteraction); }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof MediumBoundaryState boundary
                && medium.equals(boundary.medium)
                && priority == boundary.priority && orientation == boundary.orientation && surfaceInteraction == boundary.surfaceInteraction;
    }

    @Override public int hashCode() { return Objects.hash(medium, priority, orientation, surfaceInteraction); }

    @Override public String toString() {
        return "MediumBoundaryState[medium=" + medium
                + ", priority=" + priority + ", orientation=" + orientation + ", surfaceInteraction=" + surfaceInteraction + ']';
    }

    /** Winding convention used to interpret front and back face crossings. */
    public enum Orientation { OUTWARD, INWARD }

    public static final class Builder {
        private final EnvironmentState.Medium medium;
        private int priority;
        private Orientation orientation = Orientation.OUTWARD;
        private boolean surfaceInteraction = true;

        private Builder(EnvironmentState.Medium medium) { this.medium = Objects.requireNonNull(medium, "medium"); }

        /** Higher priority wins where closed media overlap. */
        public Builder priority(int value) { priority = value; return this; }
        public Builder orientation(Orientation value) { orientation = Objects.requireNonNull(value); return this; }
        /** Selects surface-material shading/refraction, or an invisible volume-only boundary. */
        public Builder surfaceInteraction(boolean value) { surfaceInteraction = value; return this; }
        public MediumBoundaryState build() { return new MediumBoundaryState(medium, priority, orientation, surfaceInteraction); }
    }
}
