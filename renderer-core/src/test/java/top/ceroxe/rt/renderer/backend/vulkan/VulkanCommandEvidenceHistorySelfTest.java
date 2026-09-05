package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.EvidenceQuery;

import java.util.Optional;
import java.util.OptionalLong;

/** Deterministic admission, delayed-reader, failure and close regression with two slots. */
public final class VulkanCommandEvidenceHistorySelfTest {
    public static void main(String[] arguments) {
        var history = new VulkanCommandEvidenceHistory(2);
        record(history, 1);
        record(history, 5);
        require(!history.admit(), "pending evidence did not backpressure");
        history.completed(evidence(5, CommandExecutionEvidence.Outcome.GPU_COMPLETED));
        require(!history.admit(), "unobserved terminal result was evicted");
        var firstReader = history.retain(5);
        var secondReader = history.retain(5);
        expect(IllegalStateException.class, () -> history.retain(1));
        require(history.query(5).evidence().orElseThrow().outcome().gpuCompleted(), "terminal result missing");
        require(!history.admit(), "observed leased evidence was evicted");
        firstReader.close();
        firstReader.close();
        require(!history.admit(), "one reader released another reader's pin");
        secondReader.close();
        record(history, 7);
        require(history.query(5).status() == EvidenceQuery.Status.OUTSIDE_RETENTION_WINDOW, "expired sequence misclassified");
        require(history.query(1).evidence().orElseThrow().outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                "late pending command was evicted below the history watermark");
        require(history.query(8).status() == EvidenceQuery.Status.UNKNOWN, "future query misclassified");
        history.completed(evidence(1, CommandExecutionEvidence.Outcome.REJECTED));
        require(history.query(1).evidence().orElseThrow().reason() == CommandExecutionEvidence.Reason.DEVICE_LOST,
                "device failure was confused with missing history");
        history.completed(evidence(7, CommandExecutionEvidence.Outcome.GPU_COMPLETED));
        history.query(7);
        for (long sequence = 8; sequence < 100_008; sequence++) {
            record(history, sequence);
            history.completed(evidence(sequence, CommandExecutionEvidence.Outcome.GPU_COMPLETED));
            history.query(sequence);
            require(history.size() == 2, "completed evidence grew with cumulative frames");
        }
        long evictions = history.evictions();
        require(history.query(200_000).status() == EvidenceQuery.Status.UNKNOWN, "future query mutated retention");
        require(history.evictions() == evictions, "query evicted evidence");
        expect(IllegalArgumentException.class, () -> history.query(-1));
        expect(IllegalArgumentException.class, () -> history.retain(200_000));
        var lease = history.retain(100_007);
        history.close();
        lease.close();
        lease.close();
        require(history.size() == 0 && history.leases() == 0, "close retained evidence");
        expect(IllegalStateException.class, () -> history.query(100_007));
        require(new VulkanCommandEvidenceHistory(2).query(1).status() == EvidenceQuery.Status.UNKNOWN,
                "new session inherited old history");
        var failed = new VulkanCommandEvidenceHistory(2);
        record(failed, 10);
        record(failed, 20);
        failed.failPending(CommandExecutionEvidence.Reason.DEVICE_LOST, "device lost");
        failed.failPending(CommandExecutionEvidence.Reason.DEVICE_LOST, "repeated device lost");
        require(failed.pending() == 0 && failed.unobserved() == 2 && !failed.admit(),
                "failure lost unobserved terminal results");
        require(failed.query(10).evidence().orElseThrow().reason() == CommandExecutionEvidence.Reason.DEVICE_LOST,
                "failure reason lost");
        failed.close();
        System.out.println("VulkanCommandEvidenceHistorySelfTest passed: 100000 completions, capacity=2");
    }

    private static void record(VulkanCommandEvidenceHistory history, long sequence) {
        require(history.admit(), "expected admission");
        history.recorded(evidence(sequence, CommandExecutionEvidence.Outcome.RECORDED));
    }

    private static CommandExecutionEvidence evidence(long sequence, CommandExecutionEvidence.Outcome outcome) {
        return new CommandExecutionEvidence(sequence, outcome,
                outcome == CommandExecutionEvidence.Outcome.REJECTED ? CommandExecutionEvidence.Reason.DEVICE_LOST
                        : CommandExecutionEvidence.Reason.NONE,
                outcome.recorded() ? OptionalLong.of(sequence) : OptionalLong.empty(), Optional.empty(), 0, "test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected failure", failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
