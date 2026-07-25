package top.ceroxe.rt.renderer.orchestration.work;

import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;

/**
 * Latency class for one immutable section generation.
 *
 * <p>The lane describes scheduling policy, not resource ownership. All lanes may share the same
 * bounded CPU/GPU workers, but background pressure is never allowed to consume the capacity or
 * ordering guarantees reserved for an interactive host mutation.</p>
 */
public enum SectionWorkLane {
    /** Host mutation that must reach the next admissible frame. */
    INTERACTIVE(0),
    /** Camera-relevant foreground population. */
    FOREGROUND(1),
    /** Normal chunk-streaming population. */
    STREAMING(2),
    /** Speculative or maintenance population. */
    BACKGROUND(3);

    private final int rank;

    SectionWorkLane(int rank) {
        this.rank = rank;
    }

    /** Returns this lane's deterministic scheduling rank.
     * @return lower-is-higher scheduling rank */
    public int rank() {
        return rank;
    }

    /**
     * Tests whether this lane has stricter latency priority.
     *
     * @param other lane to compare
     * @return whether this lane has stricter latency priority
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public boolean outranks(SectionWorkLane other) {
        return rank < java.util.Objects.requireNonNull(other, "other").rank;
    }

    /**
     * Classifies producer facts without importing queue-specific state into the producer.
     * @param sourceFlags source-causality flags from the scene update
     * @param foreground whether the source is part of the active foreground set
     * @return deterministic scheduling lane
     */
    public static SectionWorkLane fromSourceFlags(int sourceFlags, boolean foreground) {
        if ((sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0) {
            return INTERACTIVE;
        }
        if (foreground) {
            return FOREGROUND;
        }
        if ((sourceFlags & SceneUpdateBatch.SOURCE_CHUNK_STREAMING) != 0) {
            return STREAMING;
        }
        return BACKGROUND;
    }
}
