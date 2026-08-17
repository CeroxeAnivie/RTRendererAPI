package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Owned native handle and exact signal value for one synchronization operation.
 *
 * @param handle exported synchronization handle owner
 * @param primitiveKind binary or timeline behavior
 * @param timelineValue absent for binary, present and non-negative for timeline
 */
public record ExternalSynchronizationSignal(
        OwnedExternalHandle<ExternalSynchronizationHandleType> handle,
        SynchronizationPrimitiveKind primitiveKind,
        OptionalLong timelineValue
) {
    public ExternalSynchronizationSignal {
        handle = Objects.requireNonNull(handle, "handle");
        primitiveKind = Objects.requireNonNull(primitiveKind, "primitiveKind");
        timelineValue = Objects.requireNonNull(timelineValue, "timelineValue");
        if (handle.state() != ExternalHandleState.EXPORTED) {
            throw new IllegalArgumentException("synchronization handle must be exported");
        }
        if ((primitiveKind == SynchronizationPrimitiveKind.TIMELINE) != timelineValue.isPresent()) {
            throw new IllegalArgumentException("timeline value must be present exactly for timeline signals");
        }
        if (timelineValue.isPresent() && timelineValue.getAsLong() < 0L) {
            throw new IllegalArgumentException("timeline value must not be negative");
        }
    }

    /** @return exact portable contract implemented by this signal */
    public ExternalSynchronizationContract.ExternalSignal contract() {
        return new ExternalSynchronizationContract.ExternalSignal(handle.handleType(), primitiveKind);
    }
}
