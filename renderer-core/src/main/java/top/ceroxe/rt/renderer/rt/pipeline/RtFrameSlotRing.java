package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the replaceable RT frame-slot ring and deferred resource retirement.
 *
 * <p>The ring knows slot-resource lifetime, but deliberately does not know why a slot is retained.
 * The frame-publication ledger remains the authoritative source for that policy and supplies a
 * predicate at each replacement boundary. This prevents export/presentation state from being
 * duplicated in the allocator while making ring reuse and retirement independently observable.</p>
 */
final class RtFrameSlotRing implements AutoCloseable {
    private final List<RtPipelineFrameSlot[]> retiredSlots = new ArrayList<>();
    private RtPipelineFrameSlot[] currentSlots;
    private int nextSlotIndex;

    RtFrameSlotRing(RtPipelineFrameSlot[] initialSlots, int minimumSlotCount) {
        currentSlots = validate(initialSlots, minimumSlotCount);
    }

    static void closeSlots(RtPipelineFrameSlot[] slots) {
        RuntimeException failure = closeCollecting(null, slots);
        if (failure != null) {
            throw failure;
        }
    }

    private static RtPipelineFrameSlot[] validate(RtPipelineFrameSlot[] slots, int minimumSlotCount) {
        Objects.requireNonNull(slots, "slots");
        if (minimumSlotCount <= 0 || slots.length < minimumSlotCount) {
            throw new IllegalArgumentException("frame-slot ring does not satisfy its minimum capacity");
        }
        RtPipelineFrameSlot[] copy = slots.clone();
        boolean[] seen = new boolean[copy.length];
        for (RtPipelineFrameSlot slot : copy) {
            Objects.requireNonNull(slot, "slot");
            int index = slot.index();
            if (index < 0 || index >= copy.length || seen[index]) {
                throw new IllegalArgumentException("frame-slot ring indexes must be unique and contiguous");
            }
            seen[index] = true;
        }
        return copy;
    }

    private static boolean containsRetainedSlot(
            RtPipelineFrameSlot[] slots,
            Predicate<RtPipelineFrameSlot> retainsSlot
    ) {
        for (RtPipelineFrameSlot slot : slots) {
            if (retainsSlot.test(slot)) {
                return true;
            }
        }
        return false;
    }

    private static long resourceBytes(RtPipelineFrameSlot[] slots) {
        long bytes = 0L;
        for (RtPipelineFrameSlot slot : slots) {
            bytes = Math.addExact(bytes, slot.nativeResourceBytes());
        }
        return bytes;
    }

    private static RtPipelineFrameSlot findCompleted(
            RtPipelineFrameSlot[] slots,
            long frameStateSequence,
            long vulkanImage
    ) {
        for (RtPipelineFrameSlot slot : slots) {
            if (slot.matchesCompletedFrame(frameStateSequence, vulkanImage)) {
                return slot;
            }
        }
        return null;
    }

    private static RuntimeException closeCollecting(RuntimeException firstFailure, RtPipelineFrameSlot[] slots) {
        if (slots == null) {
            return firstFailure;
        }
        for (RtPipelineFrameSlot slot : slots) {
            if (slot == null) {
                continue;
            }
            try {
                slot.close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        return firstFailure;
    }

    RtPipelineFrameSlot acquireWritable() {
        for (int offset = 0; offset < currentSlots.length; offset++) {
            int index = (nextSlotIndex + offset) % currentSlots.length;
            RtPipelineFrameSlot slot = currentSlots[index];
            if (!slot.writable()) {
                continue;
            }
            nextSlotIndex = (index + 1) % currentSlots.length;
            return slot;
        }
        return null;
    }

    boolean hasWritableSlot() {
        for (RtPipelineFrameSlot slot : currentSlots) {
            if (slot.writable()) {
                return true;
            }
        }
        return false;
    }

    boolean canStageDescriptorGeneration() {
        for (RtPipelineFrameSlot slot : currentSlots) {
            if (!slot.hasStageableDescriptorSet()) {
                return false;
            }
        }
        return true;
    }

    boolean matches(RtFrameOutputConfig.Extent outputExtent, RtFrameOutputConfig.Extent traceExtent) {
        Objects.requireNonNull(outputExtent, "outputExtent");
        Objects.requireNonNull(traceExtent, "traceExtent");
        for (RtPipelineFrameSlot slot : currentSlots) {
            if (slot.outputImage().width() != outputExtent.width()
                    || slot.outputImage().height() != outputExtent.height()
                    || slot.traceImage().width() != traceExtent.width()
                    || slot.traceImage().height() != traceExtent.height()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces the active ring. The returned old ring must be passed to {@link #retireOrClose}.
     */
    RtPipelineFrameSlot[] replace(RtPipelineFrameSlot[] nextSlots, int minimumSlotCount) {
        RtPipelineFrameSlot[] previousSlots = currentSlots;
        currentSlots = validate(nextSlots, minimumSlotCount);
        nextSlotIndex = 0;
        return previousSlots;
    }

    /**
     * Retires a replaced ring only when a publication still references one of its slots; otherwise
     * it closes immediately through the caller-provided diagnostic sink.
     */
    void retireOrClose(
            RtPipelineFrameSlot[] replacedSlots,
            Predicate<RtPipelineFrameSlot> retainsSlot,
            Consumer<RtPipelineFrameSlot[]> closeSlots
    ) {
        Objects.requireNonNull(replacedSlots, "replacedSlots");
        Objects.requireNonNull(retainsSlot, "retainsSlot");
        Objects.requireNonNull(closeSlots, "closeSlots");
        if (containsRetainedSlot(replacedSlots, retainsSlot)) {
            retiredSlots.add(replacedSlots);
        } else {
            closeSlots.accept(replacedSlots);
        }
    }

    /**
     * Releases every retired ring no longer retained by publication.
     */
    int releaseUnretained(Predicate<RtPipelineFrameSlot> retainsSlot, Consumer<RtPipelineFrameSlot[]> closeSlots) {
        Objects.requireNonNull(retainsSlot, "retainsSlot");
        Objects.requireNonNull(closeSlots, "closeSlots");
        int released = 0;
        for (int index = retiredSlots.size() - 1; index >= 0; index--) {
            RtPipelineFrameSlot[] retiredRing = retiredSlots.get(index);
            if (containsRetainedSlot(retiredRing, retainsSlot)) {
                continue;
            }
            closeSlots.accept(retiredRing);
            retiredSlots.remove(index);
            released++;
        }
        return released;
    }

    RtPipelineFrameSlot findCompleted(long frameStateSequence, long vulkanImage) {
        RtPipelineFrameSlot found = findCompleted(currentSlots, frameStateSequence, vulkanImage);
        if (found != null) {
            return found;
        }
        for (RtPipelineFrameSlot[] retiredRing : retiredSlots) {
            found = findCompleted(retiredRing, frameStateSequence, vulkanImage);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    RtPipelineFrameSlot[] currentSlots() {
        return currentSlots;
    }

    int slotCount() {
        return currentSlots.length;
    }

    int nextSlotIndex() {
        return nextSlotIndex;
    }

    int retiredRingCount() {
        return retiredSlots.size();
    }

    String resourceSummary() {
        long currentBytes = resourceBytes(currentSlots);
        long retiredBytes = 0L;
        int retiredSlotCount = 0;
        for (RtPipelineFrameSlot[] retiredRing : retiredSlots) {
            retiredBytes = Math.addExact(retiredBytes, resourceBytes(retiredRing));
            retiredSlotCount = Math.addExact(retiredSlotCount, retiredRing.length);
        }
        return "frameSlotResources{currentSlots=" + currentSlots.length
                + ", currentNativeBytes=" + currentBytes
                + ", retiredRings=" + retiredSlots.size()
                + ", retiredSlots=" + retiredSlotCount
                + ", retiredNativeBytes=" + retiredBytes
                + '}';
    }

    String summary() {
        StringBuilder summary = new StringBuilder("[");
        for (int index = 0; index < currentSlots.length; index++) {
            if (index > 0) {
                summary.append(',');
            }
            summary.append(currentSlots[index].summary());
        }
        return summary.append(']').toString();
    }

    /**
     * Releases current and retired slot rings, preserving all close failures.
     *
     * @throws RuntimeException if any frame slot cannot be released
     */
    @Override
    public void close() {
        RuntimeException firstFailure = null;
        firstFailure = closeCollecting(firstFailure, currentSlots);
        for (RtPipelineFrameSlot[] retiredRing : retiredSlots) {
            firstFailure = closeCollecting(firstFailure, retiredRing);
        }
        retiredSlots.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
