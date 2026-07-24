package top.ceroxe.mcvulkanrt.renderer.rt.material;

import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns retired and reusable material-buffer generations through descriptor-safe release. */
final class RtMaterialBufferPool {
    private static final int MAX_REUSABLE_BATCHES = 3;

    private final RtMaterialTelemetrySink telemetry;
    private final List<Retired> retired = new ArrayList<>();
    private final List<Buffers> reusable = new ArrayList<>();
    private long retiredBatches;
    private long releasedRetiredBatches;
    private long pooledBatches;
    private long reusedBatches;
    private long closedReusableBatches;
    private boolean closed;

    RtMaterialBufferPool(RtMaterialTelemetrySink telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    Buffers acquire(long sectionBytes, long faceBytes, long textureRecordBytes, long texturePixelBytes) {
        int bestIndex = -1;
        long bestCapacityBytes = Long.MAX_VALUE;
        for (int index = 0; index < reusable.size(); index++) {
            Buffers candidate = reusable.get(index);
            if (!candidate.canStore(sectionBytes, faceBytes, textureRecordBytes, texturePixelBytes)) {
                continue;
            }
            long capacityBytes = candidate.capacity().totalBytes();
            if (capacityBytes < bestCapacityBytes) {
                bestIndex = index;
                bestCapacityBytes = capacityBytes;
            }
        }
        if (bestIndex < 0) {
            return null;
        }
        reusedBatches++;
        return reusable.remove(bestIndex);
    }

    void retire(Buffers buffers, long descriptorGeneration) {
        if (buffers == null) {
            return;
        }
        if (descriptorGeneration < 0L) {
            throw new IllegalArgumentException("descriptorGeneration must not be negative");
        }
        retired.add(new Retired(buffers, descriptorGeneration));
        retiredBatches++;
        telemetry.materialBuffersRetired(descriptorGeneration, retired.size());
    }

    RuntimeException releaseThrough(RuntimeException failure, long completedDescriptorGeneration) {
        if (completedDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("completedDescriptorGeneration must not be negative");
        }
        int releasedBatches = 0;
        for (int index = retired.size() - 1; index >= 0; index--) {
            Retired candidate = retired.get(index);
            if (candidate.descriptorGeneration() > completedDescriptorGeneration) {
                continue;
            }
            failure = recycleCollecting(failure, candidate.buffers());
            retired.remove(index);
            releasedRetiredBatches++;
            releasedBatches++;
        }
        telemetry.materialBuffersReleased(
                completedDescriptorGeneration, releasedBatches, reusable.size(), retired.size()
        );
        return failure;
    }

    void recycle(Buffers buffers) {
        RuntimeException failure = recycleCollecting(null, buffers);
        if (failure != null) {
            throw failure;
        }
    }

    RuntimeException recycleCollecting(RuntimeException failure, Buffers buffers) {
        if (buffers == null) {
            return failure;
        }
        if (!closed && reusable.size() < MAX_REUSABLE_BATCHES) {
            reusable.add(buffers);
            pooledBatches++;
            return failure;
        }
        if (!closed) {
            int replacementIndex = replacementIndex(capacities(), buffers.capacity());
            if (replacementIndex >= 0) {
                Buffers evicted = reusable.set(replacementIndex, buffers);
                pooledBatches++;
                recordOverflowClose(evicted);
                closedReusableBatches++;
                return evicted.closeCollecting(failure);
            }
        }
        recordOverflowClose(buffers);
        closedReusableBatches++;
        return buffers.closeCollecting(failure);
    }

    RuntimeException closeCollecting(RuntimeException failure) {
        if (closed) {
            return failure;
        }
        closed = true;
        failure = releaseThrough(failure, Long.MAX_VALUE);
        for (Buffers buffers : reusable) {
            failure = buffers.closeCollecting(failure);
            closedReusableBatches++;
        }
        reusable.clear();
        return failure;
    }

    int reusableCount() {
        return reusable.size();
    }

    String summary() {
        return "retiredMaterialBufferBatches=" + retiredBatches
                + ", releasedRetiredMaterialBufferBatches=" + releasedRetiredBatches
                + ", pendingRetiredMaterialBufferBatches=" + retired.size()
                + ", reusableMaterialBufferBatches=" + reusable.size()
                + ", pooledMaterialBufferBatches=" + pooledBatches
                + ", reusedMaterialBufferBatches=" + reusedBatches
                + ", closedReusableMaterialBufferBatches=" + closedReusableBatches;
    }

    static int replacementIndex(List<Capacity> pooledCapacities, Capacity incomingCapacity) {
        Objects.requireNonNull(pooledCapacities, "pooledCapacities");
        Objects.requireNonNull(incomingCapacity, "incomingCapacity");
        int replacementIndex = -1;
        long smallestCapacityBytes = Long.MAX_VALUE;
        for (int index = 0; index < pooledCapacities.size(); index++) {
            Capacity pooled = Objects.requireNonNull(
                    pooledCapacities.get(index), "pooledCapacities[" + index + "]"
            );
            if (!incomingCapacity.strictlyDominates(pooled)) {
                continue;
            }
            long capacityBytes = pooled.totalBytes();
            if (capacityBytes < smallestCapacityBytes) {
                replacementIndex = index;
                smallestCapacityBytes = capacityBytes;
            }
        }
        return replacementIndex;
    }

    private List<Capacity> capacities() {
        List<Capacity> capacities = new ArrayList<>(reusable.size());
        for (Buffers buffers : reusable) {
            capacities.add(buffers.capacity());
        }
        return capacities;
    }

    private void recordOverflowClose(Buffers buffers) {
        telemetry.materialBufferOverflowClosed(
                buffers.sectionRecordBuffer().sizeBytes(),
                buffers.faceRecordBuffer().sizeBytes(),
                buffers.textureRecordBuffer().sizeBytes(),
                buffers.texturePixelBuffer().sizeBytes()
        );
    }

    record Buffers(
            RtGpuBuffer sectionRecordBuffer,
            RtGpuBuffer faceRecordBuffer,
            RtGpuBuffer textureRecordBuffer,
            RtGpuBuffer texturePixelBuffer
    ) implements AutoCloseable {
        Buffers {
            Objects.requireNonNull(sectionRecordBuffer, "sectionRecordBuffer");
            Objects.requireNonNull(faceRecordBuffer, "faceRecordBuffer");
            Objects.requireNonNull(textureRecordBuffer, "textureRecordBuffer");
            Objects.requireNonNull(texturePixelBuffer, "texturePixelBuffer");
        }

        boolean canStore(long sectionBytes, long faceBytes, long textureRecordBytes, long texturePixelBytes) {
            return sectionRecordBuffer.sizeBytes() >= sectionBytes
                    && faceRecordBuffer.sizeBytes() >= faceBytes
                    && textureRecordBuffer.sizeBytes() >= textureRecordBytes
                    && texturePixelBuffer.sizeBytes() >= texturePixelBytes;
        }

        Capacity capacity() {
            return new Capacity(
                    sectionRecordBuffer.sizeBytes(),
                    faceRecordBuffer.sizeBytes(),
                    textureRecordBuffer.sizeBytes(),
                    texturePixelBuffer.sizeBytes()
            );
        }

        RuntimeException closeCollecting(RuntimeException failure) {
            failure = closeCollecting(failure, texturePixelBuffer);
            failure = closeCollecting(failure, textureRecordBuffer);
            failure = closeCollecting(failure, faceRecordBuffer);
            return closeCollecting(failure, sectionRecordBuffer);
        }

        @Override
        public void close() {
            RuntimeException failure = closeCollecting(null);
            if (failure != null) {
                throw failure;
            }
        }

        private static RuntimeException closeCollecting(RuntimeException failure, RtGpuBuffer buffer) {
            try {
                buffer.close();
                return failure;
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    return closeFailure;
                }
                failure.addSuppressed(closeFailure);
                return failure;
            }
        }
    }

    record Capacity(long sectionBytes, long faceBytes, long textureRecordBytes, long texturePixelBytes) {
        Capacity {
            if (sectionBytes <= 0L || faceBytes <= 0L || textureRecordBytes <= 0L || texturePixelBytes <= 0L) {
                throw new IllegalArgumentException("material buffer capacities must be positive");
            }
        }

        long totalBytes() {
            return Math.addExact(
                    Math.addExact(sectionBytes, faceBytes),
                    Math.addExact(textureRecordBytes, texturePixelBytes)
            );
        }

        boolean strictlyDominates(Capacity other) {
            Objects.requireNonNull(other, "other");
            return sectionBytes >= other.sectionBytes
                    && faceBytes >= other.faceBytes
                    && textureRecordBytes >= other.textureRecordBytes
                    && texturePixelBytes >= other.texturePixelBytes
                    && (sectionBytes > other.sectionBytes
                    || faceBytes > other.faceBytes
                    || textureRecordBytes > other.textureRecordBytes
                    || texturePixelBytes > other.texturePixelBytes);
        }
    }

    private record Retired(Buffers buffers, long descriptorGeneration) {
        private Retired {
            Objects.requireNonNull(buffers, "buffers");
            if (descriptorGeneration < 0L) {
                throw new IllegalArgumentException("descriptorGeneration must not be negative");
            }
        }
    }
}
