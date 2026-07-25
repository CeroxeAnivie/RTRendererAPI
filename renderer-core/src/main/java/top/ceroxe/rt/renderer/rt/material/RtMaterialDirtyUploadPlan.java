package top.ceroxe.rt.renderer.rt.material;

import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable dirty-range upload plan independent from material table ownership.
 */
final class RtMaterialDirtyUploadPlan {
    private static final RtMaterialDirtyUploadPlan EMPTY =
            new RtMaterialDirtyUploadPlan(List.of(), List.of(), 0L, 0L);

    private final List<RtGpuBuffer.IntBufferWriter> chunks;
    private final List<CopyRange> copyRanges;
    private final long byteCount;
    private final long recordCount;

    private RtMaterialDirtyUploadPlan(
            List<RtGpuBuffer.IntBufferWriter> chunks,
            List<CopyRange> copyRanges,
            long byteCount,
            long recordCount
    ) {
        this.chunks = List.copyOf(chunks);
        this.copyRanges = List.copyOf(copyRanges);
        if (this.chunks.size() != this.copyRanges.size()) {
            throw new IllegalArgumentException("dirty upload chunks and copy ranges must match");
        }
        if (byteCount < 0L || recordCount < 0L) {
            throw new IllegalArgumentException("dirty upload counts must not be negative");
        }
        this.byteCount = byteCount;
        this.recordCount = recordCount;
    }

    static RtMaterialDirtyUploadPlan empty() {
        return EMPTY;
    }

    static RtMaterialDirtyUploadPlan single(IntBuffer records, long targetOffsetBytes, long recordCount) {
        Objects.requireNonNull(records, "records");
        if (!records.hasRemaining()) {
            return empty();
        }
        IntBufferWriterView ownedView = new IntBufferWriterView(records);
        long byteCount = checkedMultiply(ownedView.intCount(), Integer.BYTES);
        return new RtMaterialDirtyUploadPlan(
                List.of(ownedView),
                List.of(new CopyRange(0L, targetOffsetBytes, byteCount)),
                byteCount,
                recordCount
        );
    }

    static Builder builder() {
        return new Builder();
    }

    private static long checkedAdd(long left, long right) {
        return Math.addExact(left, right);
    }

    private static long checkedMultiply(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    boolean isEmpty() {
        return chunks.isEmpty();
    }

    List<RtGpuBuffer.IntBufferWriter> chunks() {
        return chunks;
    }

    List<CopyRange> copyRanges() {
        return copyRanges;
    }

    long byteCount() {
        return byteCount;
    }

    long recordCount() {
        return recordCount;
    }

    static final class Builder {
        private final List<RtGpuBuffer.IntBufferWriter> chunks = new ArrayList<>();
        private final List<CopyRange> copyRanges = new ArrayList<>();
        private long byteCount;
        private long recordCount;

        void add(int[] records, long targetOffsetBytes, long recordsAdded) {
            Objects.requireNonNull(records, "records");
            add(IntBuffer.wrap(records), targetOffsetBytes, recordsAdded);
        }

        void add(IntBuffer records, long targetOffsetBytes, long recordsAdded) {
            Objects.requireNonNull(records, "records");
            if (!records.hasRemaining()) return;
            if (recordsAdded < 0L) throw new IllegalArgumentException("recordsAdded must not be negative");
            add(new IntBufferWriterView(records), targetOffsetBytes, recordsAdded);
        }

        void add(RtGpuBuffer.IntBufferWriter records, long targetOffsetBytes, long recordsAdded) {
            Objects.requireNonNull(records, "records");
            if (records.intCount() <= 0) return;
            if (recordsAdded < 0L) throw new IllegalArgumentException("recordsAdded must not be negative");
            long chunkBytes = checkedMultiply(records.intCount(), Integer.BYTES);
            chunks.add(records);
            copyRanges.add(new CopyRange(byteCount, targetOffsetBytes, chunkBytes));
            byteCount = checkedAdd(byteCount, chunkBytes);
            recordCount = checkedAdd(recordCount, recordsAdded);
        }

        RtMaterialDirtyUploadPlan build() {
            return chunks.isEmpty()
                    ? EMPTY
                    : new RtMaterialDirtyUploadPlan(chunks, copyRanges, byteCount, recordCount);
        }
    }

    record CopyRange(long sourceOffsetBytes, long targetOffsetBytes, long byteCount) {
        CopyRange {
            if (sourceOffsetBytes < 0L || targetOffsetBytes < 0L || byteCount <= 0L
                    || sourceOffsetBytes % Integer.BYTES != 0L
                    || targetOffsetBytes % Integer.BYTES != 0L) {
                throw new IllegalArgumentException("buffer copy range must be aligned and positive");
            }
        }
    }

    private record IntBufferWriterView(IntBuffer records) implements RtGpuBuffer.IntBufferWriter {
        private IntBufferWriterView {
            records = records.asReadOnlyBuffer();
        }

        @Override
        public int intCount() {
            return records.remaining();
        }

        @Override
        public void writeTo(IntBuffer target) {
            target.put(records.duplicate());
        }
    }
}
