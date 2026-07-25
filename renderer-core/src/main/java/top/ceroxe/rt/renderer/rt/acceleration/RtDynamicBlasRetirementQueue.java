package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Owns retired dynamic BLAS resources until their protected dynamic revision advances.
 */
final class RtDynamicBlasRetirementQueue {
    private final List<Entry> retired = new ArrayList<>();
    private long retainedBytes;
    private long peakRetainedBytes;

    void retire(long safeAfterRevision, RtAccelerationStructure blas) {
        if (safeAfterRevision < 0L) throw new IllegalArgumentException("safeAfterRevision must not be negative");
        Objects.requireNonNull(blas, "blas");
        retainedBytes = Math.addExact(retainedBytes, blas.storageBytes());
        peakRetainedBytes = Math.max(peakRetainedBytes, retainedBytes);
        retired.add(new Entry(safeAfterRevision, blas));
    }

    void releaseThrough(long protectedRevision) {
        /* -1 is the unbuilt generation: it intentionally releases no safe-after >= 0 resource. */
        if (protectedRevision < -1L) throw new IllegalArgumentException("protectedRevision must be -1 or greater");
        RuntimeException failure = null;
        Iterator<Entry> iterator = retired.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.safeAfterRevision() > protectedRevision) continue;
            failure = closeCollecting(failure, entry);
            iterator.remove();
        }
        if (failure != null) throw failure;
    }

    RuntimeException closeAllCollecting(RuntimeException failure) {
        for (Entry entry : retired) failure = closeCollecting(failure, entry);
        retired.clear();
        return failure;
    }

    int size() {
        return retired.size();
    }

    long retainedBytes() {
        return retainedBytes;
    }

    long peakRetainedBytes() {
        return peakRetainedBytes;
    }

    private RuntimeException closeCollecting(RuntimeException failure, Entry entry) {
        try {
            entry.blas().close();
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        } finally {
            retainedBytes = Math.max(0L, retainedBytes - entry.blas().storageBytes());
        }
        return failure;
    }

    private record Entry(long safeAfterRevision, RtAccelerationStructure blas) {
    }
}
