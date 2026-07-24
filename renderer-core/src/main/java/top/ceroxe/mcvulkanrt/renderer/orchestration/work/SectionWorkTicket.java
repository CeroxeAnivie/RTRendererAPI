package top.ceroxe.mcvulkanrt.renderer.orchestration.work;

import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Immutable identity of one section generation while it is owned by a lifecycle stage.
 *
 * <p>Keeping generation, producer generation, source facts and scheduling lane in one value
 * prevents the split-map failure mode where a consumer observes metadata from different updates.
 * Stage generations are monotonic and also provide stable FIFO order inside one lane.</p>
 */
public record SectionWorkTicket(
        SectionKey key,
        long stageGeneration,
        long producerGeneration,
        int sourceFlags,
        SectionWorkLane lane
) implements Comparable<SectionWorkTicket> {
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
