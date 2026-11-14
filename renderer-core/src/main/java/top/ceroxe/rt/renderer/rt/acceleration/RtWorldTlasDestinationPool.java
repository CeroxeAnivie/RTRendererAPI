package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns descriptor-retired world TLAS destinations and their bounded reuse pool.
 */
final class RtWorldTlasDestinationPool {
    private final int reusableCapacity;
    private final List<RetiredDestination> retired = new ArrayList<>();
    private final List<RtAccelerationStructure> reusable = new ArrayList<>();
    private long retiredBatches;
    private long releasedRetiredBatches;
    private long reusedDestinations;
    private long newDestinations;
    private long pooledDestinations;
    private long poolCapacityReleases;

    RtWorldTlasDestinationPool(int reusableCapacity) {
        if (reusableCapacity < 0) {
            throw new IllegalArgumentException("reusableCapacity must not be negative");
        }
        this.reusableCapacity = reusableCapacity;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, RtAccelerationStructure destination) {
        try {
            destination.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    void retire(RtAccelerationStructure destination, long descriptorGeneration) {
        if (destination == null) {
            return;
        }
        if (descriptorGeneration < -1L) {
            throw new IllegalArgumentException("descriptorGeneration must be -1 or non-negative");
        }
        retired.add(new RetiredDestination(destination, descriptorGeneration));
        retiredBatches++;
    }

    void assignUnboundRetirements(long descriptorGeneration) {
        if (descriptorGeneration < 0L) {
            throw new IllegalArgumentException("descriptorGeneration must not be negative");
        }
        for (int index = 0; index < retired.size(); index++) {
            RetiredDestination entry = retired.get(index);
            if (entry.descriptorGeneration() < 0L) {
                retired.set(index, entry.withDescriptorGeneration(descriptorGeneration));
            }
        }
    }

    RuntimeException releaseThrough(
            RuntimeException failure,
            long completedDescriptorGeneration,
            boolean cacheClosed
    ) {
        if (completedDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("completedDescriptorGeneration must not be negative");
        }
        for (int index = retired.size() - 1; index >= 0; index--) {
            RetiredDestination entry = retired.get(index);
            if (!cacheClosed && (entry.descriptorGeneration() < 0L
                    || entry.descriptorGeneration() > completedDescriptorGeneration)) {
                continue;
            }
            retired.remove(index);
            releasedRetiredBatches++;
            if (!cacheClosed && reusable.size() < reusableCapacity) {
                reusable.add(entry.destination());
                pooledDestinations++;
            } else {
                if (!cacheClosed) {
                    poolCapacityReleases++;
                }
                failure = closeCollecting(failure, entry.destination());
            }
        }
        return failure;
    }

    RtAccelerationStructure takeReusableDestination() {
        return reusable.isEmpty() ? null : reusable.remove(reusable.size() - 1);
    }

    void recordCompletedDestination(boolean recycled) {
        if (recycled) {
            reusedDestinations++;
        } else {
            newDestinations++;
        }
    }

    RuntimeException closeCollecting(RuntimeException failure) {
        failure = releaseThrough(failure, Long.MAX_VALUE, true);
        for (int index = reusable.size() - 1; index >= 0; index--) {
            failure = closeCollecting(failure, reusable.get(index));
        }
        reusable.clear();
        return failure;
    }

    int retiredCount() {
        return retired.size();
    }

    int reusableCount() {
        return reusable.size();
    }

    long retiredBatches() {
        return retiredBatches;
    }

    long releasedRetiredBatches() {
        return releasedRetiredBatches;
    }

    long reusedDestinations() {
        return reusedDestinations;
    }

    long newDestinations() {
        return newDestinations;
    }

    long pooledDestinations() {
        return pooledDestinations;
    }

    long poolCapacityReleases() {
        return poolCapacityReleases;
    }

    private record RetiredDestination(RtAccelerationStructure destination, long descriptorGeneration) {
        private RetiredDestination {
            destination = Objects.requireNonNull(destination, "destination");
        }

        private RetiredDestination withDescriptorGeneration(long generation) {
            return new RetiredDestination(destination, generation);
        }
    }
}
