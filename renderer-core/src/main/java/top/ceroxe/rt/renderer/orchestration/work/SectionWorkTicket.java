package top.ceroxe.rt.renderer.orchestration.work;

import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Immutable identity of one section generation while it is owned by a lifecycle stage.
 *
 * <p>Keeping generation, producer generation, source facts and scheduling lane in one value
 * prevents the split-map failure mode where a consumer observes metadata from different updates.
 * Stage generations are monotonic and also provide stable FIFO order inside one lane.</p>
 *
 * @param key                stable section identity
 * @param stageGeneration    positive generation assigned by the owning lifecycle stage
 * @param producerGeneration source mutation generation, or zero for non-mutation work
 * @param sourceFlags        non-zero bit set describing the source update
 * @param lane               scheduling lane consistent with the source flags
 */
public record SectionWorkTicket(
        SectionKey key,
        long stageGeneration,
        long producerGeneration,
        int sourceFlags,
        SectionWorkLane lane
) implements Comparable<SectionWorkTicket> {
    /**
     * Validates generation, source, and scheduling-lane consistency.
     */
    public SectionWorkTicket {
        key = Objects.requireNonNull(key, "key");
        lane = Objects.requireNonNull(lane, "lane");
        if (stageGeneration <= 0L) {
            throw new IllegalArgumentException("section work stage generation must be positive");
        }
        if (producerGeneration < 0L || sourceFlags == 0) {
            throw new IllegalArgumentException("section work producer generation and source flags are invalid");
        }
        boolean mutation = (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0;
        if (mutation != (producerGeneration > 0L) || mutation != (lane == SectionWorkLane.INTERACTIVE)) {
            throw new IllegalArgumentException("interactive section work identity is inconsistent");
        }
    }

    /**
     * Orders tickets by latency lane, stage generation, then stable section coordinates.
     *
     * @param other ticket to compare
     * @return negative, zero, or positive according to the deterministic scheduling order
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Override
    public int compareTo(SectionWorkTicket other) {
        Objects.requireNonNull(other, "other");
        int laneOrder = Integer.compare(lane.rank(), other.lane.rank());
        if (laneOrder != 0) {
            return laneOrder;
        }
        int generationOrder = Long.compare(stageGeneration, other.stageGeneration);
        if (generationOrder != 0) {
            return generationOrder;
        }
        int xOrder = Integer.compare(key.x(), other.key.x());
        if (xOrder != 0) {
            return xOrder;
        }
        int yOrder = Integer.compare(key.y(), other.key.y());
        return yOrder != 0 ? yOrder : Integer.compare(key.z(), other.key.z());
    }
}
