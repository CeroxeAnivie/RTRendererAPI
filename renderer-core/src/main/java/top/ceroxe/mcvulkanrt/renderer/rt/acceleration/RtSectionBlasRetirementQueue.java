package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns deferred BLAS destruction until every protected scene revision has advanced. */
final class RtSectionBlasRetirementQueue {
    private final List<RetiredSectionBlas> retired = new ArrayList<>();
    private long closedCount;
    private long retainedBytes;
    private long peakRetainedBytes;

    void retireAll(
            Map<SectionKey, RtAccelerationStructure> accelerationStructures,
            long safeAfterRevision
    ) {
        Objects.requireNonNull(accelerationStructures, "accelerationStructures");
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : accelerationStructures.entrySet()) {
            retire(entry.getKey(), safeAfterRevision, entry.getValue());
        }
    }

    void retire(SectionKey key, long safeAfterRevision, RtAccelerationStructure accelerationStructure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(accelerationStructure, "accelerationStructure");
        if (safeAfterRevision < 0L) {
            throw new IllegalArgumentException("safeAfterRevision must not be negative");
        }
        long nextRetainedBytes = Math.addExact(retainedBytes, accelerationStructure.storageBytes());
        retired.add(new RetiredSectionBlas(key, safeAfterRevision, accelerationStructure));
        retainedBytes = nextRetainedBytes;
        peakRetainedBytes = Math.max(peakRetainedBytes, retainedBytes);
    }

    void releaseThrough(long protectedRevision) {
        if (retired.isEmpty()) {
            return;
        }
        if (protectedRevision < 0L) {
            throw new IllegalArgumentException("protectedRevision must not be negative");
        }
        RuntimeException failure = null;
        Iterator<RetiredSectionBlas> iterator = retired.iterator();
        while (iterator.hasNext()) {
            RetiredSectionBlas candidate = iterator.next();
            if (!isReleasable(candidate.safeAfterRevision(), protectedRevision)) {
                continue;
            }
            failure = closeCollecting(failure, candidate);
            iterator.remove();
        }
        if (failure != null) {
            throw failure;
        }
    }

    RuntimeException closeAllCollecting(RuntimeException failure) {
        for (RetiredSectionBlas candidate : retired) {
            failure = closeCollecting(failure, candidate);
        }
        retired.clear();
        return failure;
    }

    int size() {
        return retired.size();
    }

    long closedCount() {
        return closedCount;
    }

    long retainedBytes() {
        return retainedBytes;
    }

    long peakRetainedBytes() {
        return peakRetainedBytes;
    }

    static boolean isReleasable(long safeAfterRevision, long protectedRevision) {
        if (safeAfterRevision < 0L) {
            throw new IllegalArgumentException("safeAfterRevision must not be negative");
        }
        if (protectedRevision < 0L) {
            throw new IllegalArgumentException("protectedRevision must not be negative");
        }
        return safeAfterRevision <= protectedRevision;
    }

    private RuntimeException closeCollecting(RuntimeException failure, RetiredSectionBlas retiredBlas) {
        long storageBytes = retiredBlas.accelerationStructure().storageBytes();
        try {
            retiredBlas.accelerationStructure().close();
            closedCount++;
            return failure;
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        } finally {
            retainedBytes = Math.max(0L, retainedBytes - storageBytes);
        }
    }

    private record RetiredSectionBlas(
            SectionKey key,
            long safeAfterRevision,
            RtAccelerationStructure accelerationStructure
    ) {
    }
}
