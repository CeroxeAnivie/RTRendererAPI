package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes stable vec4-aligned dirty uploads for an already encoded dynamic scene.
 *
 * <p>This is deliberately unaware of Vulkan buffers, scene ownership, and shader
 * content. The encoder supplies candidate records and the frame slot supplies the
 * last successfully committed mirror. This is the CPU equivalent of GPUScene's
 * dirty upload discipline: unchanged records never become transfer work merely
 * because a new frame was observed.</p>
 */
final class RtDynamicSceneUploadPlanner {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneUploadPlanner() {
    }

    static List<RtRayTracingPipeline.UploadRange> merge(List<RtRayTracingPipeline.UploadRange> ranges) {
        Objects.requireNonNull(ranges, "ranges");
        if (ranges.isEmpty()) {
            return List.of();
        }
        ArrayList<RtRayTracingPipeline.UploadRange> merged = new ArrayList<>();
        RtRayTracingPipeline.UploadRange current = ranges.get(0);
        for (int index = 1; index < ranges.size(); index++) {
            RtRayTracingPipeline.UploadRange next = ranges.get(index);
            if (current.endOffsetBytes() == next.offsetBytes()) {
                current = new RtRayTracingPipeline.UploadRange(
                        current.offsetBytes(),
                        current.byteCount() + next.byteCount()
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    static List<RtRayTracingPipeline.UploadRange> dirtyRanges(
            byte[] committed,
            byte[] current,
            boolean initialized,
            List<RtRayTracingPipeline.UploadRange> candidateRanges,
            int totalBytes
    ) {
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(candidateRanges, "candidateRanges");
        if (totalBytes <= 0 || (totalBytes & (RECORD_BYTES - 1)) != 0
                || committed.length != totalBytes || current.length != totalBytes) {
            throw new IllegalArgumentException("dynamic scene byte mirrors must match the vec4-aligned shader ABI");
        }
        if (!initialized) {
            return List.of(new RtRayTracingPipeline.UploadRange(0, totalBytes));
        }
        ArrayList<RtRayTracingPipeline.UploadRange> ranges = new ArrayList<>();
        int previousCandidateEnd = 0;
        for (RtRayTracingPipeline.UploadRange candidate : candidateRanges) {
            if ((candidate.offsetBytes() & (RECORD_BYTES - 1)) != 0
                    || (candidate.byteCount() & (RECORD_BYTES - 1)) != 0
                    || candidate.endOffsetBytes() > totalBytes
                    || candidate.offsetBytes() < previousCandidateEnd) {
                throw new IllegalArgumentException("dynamic scene candidate ranges must be ordered bounded vec4 records");
            }
            previousCandidateEnd = candidate.endOffsetBytes();
            int firstChangedOffset = -1;
            for (int offset = candidate.offsetBytes(); offset < candidate.endOffsetBytes(); offset += RECORD_BYTES) {
                boolean changed = false;
                for (int byteIndex = 0; byteIndex < RECORD_BYTES; byteIndex++) {
                    if (committed[offset + byteIndex] != current[offset + byteIndex]) {
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    if (firstChangedOffset < 0) {
                        firstChangedOffset = offset;
                    }
                } else if (firstChangedOffset >= 0) {
                    ranges.add(new RtRayTracingPipeline.UploadRange(firstChangedOffset, offset - firstChangedOffset));
                    firstChangedOffset = -1;
                }
            }
            if (firstChangedOffset >= 0) {
                ranges.add(new RtRayTracingPipeline.UploadRange(
                        firstChangedOffset,
                        candidate.endOffsetBytes() - firstChangedOffset
                ));
            }
        }
        return ranges.isEmpty() ? List.of() : merge(ranges);
    }
}
