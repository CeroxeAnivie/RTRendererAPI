package top.ceroxe.mcvulkanrt.renderer.orchestration.work;

import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;

/**
 * Latency class for one immutable section generation.
 *
 * <p>The lane describes scheduling policy, not resource ownership. All lanes may share the same
 * bounded CPU/GPU workers, but background pressure is never allowed to consume the capacity or
 * ordering guarantees reserved for an interactive host mutation.</p>
 */
public enum SectionWorkLane {
    INTERACTIVE(0),
    FOREGROUND(1),
    STREAMING(2),
    BACKGROUND(3);

    private final int rank;

    SectionWorkLane(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean outranks(SectionWorkLane other) {
        return rank < java.util.Objects.requireNonNull(other, "other").rank;
    }

    /** Classifies producer facts without importing queue-specific state into the producer. */
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
