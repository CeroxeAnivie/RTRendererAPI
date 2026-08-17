package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Consumer-issued proof used by the producer before image reuse.
 *
 * <p>Creating CPU evidence asserts that GPU completion was actually observed on the CPU. Merely
 * enqueueing, submitting, presenting, or flushing consumer work does not satisfy that contract.</p>
 */
public sealed interface ExternalFrameCompletionEvidence
        permits ExternalFrameCompletionEvidence.CpuObserved,
                ExternalFrameCompletionEvidence.ExternalSignal {
    /** @return exact frame sequence to which this evidence belongs */
    long frameSequence();

    /** @return portable completion contract implemented by this evidence */
    ExternalSynchronizationContract contract();

    /** CPU-observed completion of all consumer access to one frame. */
    record CpuObserved(long frameSequence) implements ExternalFrameCompletionEvidence {
        public CpuObserved {
            if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        }

        @Override
        public ExternalSynchronizationContract contract() {
            return ExternalSynchronizationContract.CpuObserved.INSTANCE;
        }
    }

    /** External GPU signal that the producer must wait on before image reuse. */
    record ExternalSignal(
            long frameSequence,
            ExternalSynchronizationSignal signal
    ) implements ExternalFrameCompletionEvidence {
        public ExternalSignal {
            if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
            signal = Objects.requireNonNull(signal, "signal");
        }

        @Override
        public ExternalSynchronizationContract contract() {
            return signal.contract();
        }
    }
}
