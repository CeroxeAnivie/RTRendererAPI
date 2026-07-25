package top.ceroxe.rt.renderer.rt.material;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Produces sparse, immutable texture-record and pixel upload plans without owning GPU resources.
 */
final class RtTextureUploadPlanner {
    private RtTextureUploadPlanner() {
    }

    static RtMaterialDirtyUploadPlan planRecords(
            IntBuffer records,
            IntBuffer previousRecords,
            int intsPerRecord,
            boolean forceFullUpload
    ) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(previousRecords, "previousRecords");
        if (intsPerRecord <= 0) {
            throw new IllegalArgumentException("intsPerRecord must be positive");
        }
        IntBuffer current = records.duplicate();
        IntBuffer previous = previousRecords.duplicate();
        if (current.remaining() % intsPerRecord != 0 || previous.remaining() % intsPerRecord != 0) {
            throw new IllegalArgumentException("texture upload arrays must be aligned to logical records");
        }
        if (!forceFullUpload && current.equals(previous)) {
            return RtMaterialDirtyUploadPlan.empty();
        }
        int recordCount = current.remaining() / intsPerRecord;
        if (forceFullUpload || current.remaining() != previous.remaining()) {
            return RtMaterialDirtyUploadPlan.single(current, 0L, recordCount);
        }

        RtMaterialDirtyUploadPlan.Builder builder = RtMaterialDirtyUploadPlan.builder();
        int record = 0;
        while (record < recordCount) {
            int recordOffset = record * intsPerRecord;
            if (recordEquals(current, previous, recordOffset, intsPerRecord)) {
                record++;
                continue;
            }
            int firstDirtyRecord = record;
            record++;
            while (record < recordCount) {
                recordOffset = record * intsPerRecord;
                if (recordEquals(current, previous, recordOffset, intsPerRecord)) {
                    break;
                }
                record++;
            }
            int firstDirtyInt = firstDirtyRecord * intsPerRecord;
            int dirtyIntCount = (record - firstDirtyRecord) * intsPerRecord;
            builder.add(
                    current.slice(firstDirtyInt, dirtyIntCount).asReadOnlyBuffer(),
                    checkedMultiply(firstDirtyInt, Integer.BYTES),
                    record - firstDirtyRecord
            );
        }
        return builder.build();
    }

    static RtMaterialDirtyUploadPlan planPixels(
            RtTextureCatalog.Snapshot snapshot,
            RtTextureCatalog.Snapshot previousSnapshot,
            boolean forceFullUpload
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        List<RtTextureCatalog.Snapshot.PixelSegment> segments =
                snapshot.pixelSegmentsForUpload(previousSnapshot, forceFullUpload);
        if (segments.isEmpty()) {
            return RtMaterialDirtyUploadPlan.empty();
        }
        RtMaterialDirtyUploadPlan.Builder builder = RtMaterialDirtyUploadPlan.builder();
        for (RtTextureCatalog.Snapshot.PixelSegment segment : segments) {
            IntBuffer pixels = segment.pixels();
            builder.add(
                    pixels,
                    checkedMultiply(segment.targetOffsetInts(), Integer.BYTES),
                    pixels.remaining()
            );
        }
        return builder.build();
    }

    private static boolean recordEquals(IntBuffer left, IntBuffer right, int offset, int intsPerRecord) {
        for (int index = 0; index < intsPerRecord; index++) {
            if (left.get(offset + index) != right.get(offset + index)) {
                return false;
            }
        }
        return true;
    }

    private static long checkedMultiply(long left, long right) {
        long result = left * right;
        if (left != 0L && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }
}
