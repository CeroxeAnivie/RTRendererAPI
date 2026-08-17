package top.ceroxe.rt.renderer.api.interop;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;

/**
 * One exact, indivisible frame-sharing contract.
 *
 * @param format portable output encoding
 * @param memoryHandleType exact external-memory handle type
 * @param imageImportProfile complete backend-adapter import profile
 * @param producerCompletion mechanism making producer writes available to the consumer
 * @param consumerCompletions non-empty mechanisms accepted before producer reuse
 */
public record ExternalFrameTransport(
        FrameOutputFormat format,
        ExternalMemoryHandleType memoryHandleType,
        ExternalImageImportProfile imageImportProfile,
        ExternalSynchronizationContract producerCompletion,
        Set<ExternalSynchronizationContract> consumerCompletions
) {
    public ExternalFrameTransport {
        format = Objects.requireNonNull(format, "format");
        memoryHandleType = Objects.requireNonNull(memoryHandleType, "memoryHandleType");
        imageImportProfile = Objects.requireNonNull(imageImportProfile, "imageImportProfile");
        producerCompletion = Objects.requireNonNull(producerCompletion, "producerCompletion");
        Objects.requireNonNull(consumerCompletions, "consumerCompletions");
        LinkedHashSet<ExternalSynchronizationContract> copy = new LinkedHashSet<>();
        for (ExternalSynchronizationContract completion : consumerCompletions) {
            if (!copy.add(Objects.requireNonNull(completion, "consumerCompletions contains null"))) {
                throw new IllegalArgumentException("consumerCompletions contains a duplicate contract");
            }
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("at least one consumer completion is required");
        consumerCompletions = Set.copyOf(copy);
    }
}
