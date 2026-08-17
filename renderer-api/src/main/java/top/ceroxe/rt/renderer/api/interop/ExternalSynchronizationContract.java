package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/** Exact synchronization mechanism selected for one producer-consumer direction. */
public sealed interface ExternalSynchronizationContract
        permits ExternalSynchronizationContract.CpuObserved,
                ExternalSynchronizationContract.ExternalSignal {
    /** CPU-side proof that all relevant producer or consumer GPU work has completed. */
    enum CpuObserved implements ExternalSynchronizationContract {
        /** Canonical CPU-observed contract. */
        INSTANCE
    }

    /**
     * One externally shared GPU signal contract.
     *
     * @param handleType exact synchronization handle type
     * @param primitiveKind binary or timeline behavior
     */
    record ExternalSignal(
            ExternalSynchronizationHandleType handleType,
            SynchronizationPrimitiveKind primitiveKind
    ) implements ExternalSynchronizationContract {
        public ExternalSignal {
            handleType = Objects.requireNonNull(handleType, "handleType");
            primitiveKind = Objects.requireNonNull(primitiveKind, "primitiveKind");
        }
    }
}
