package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.EvidenceLease;
import top.ceroxe.rt.renderer.api.EvidenceQuery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Access is serialized by the renderer lifecycle lock, including lease release. */
final class VulkanCommandEvidenceHistory {
    private final int capacity;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long expiredThrough = -1;
    private long evictions;
    private long budgetRejections;
    private int leases;
    private boolean closed;

    VulkanCommandEvidenceHistory(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("command capacity must be positive");
        this.capacity = capacity;
    }

    boolean admit() {
        requireOpen();
        if (entries.size() < capacity || entries.values().stream().anyMatch(Entry::evictable)) return true;
        budgetRejections++;
        return false;
    }

    void recorded(CommandExecutionEvidence evidence) {
        requireOpen();
        if (evidence.outcome() != CommandExecutionEvidence.Outcome.RECORDED
                || entries.containsKey(evidence.transactionSequence())) {
            throw new IllegalArgumentException("expected new recorded evidence");
        }
        if (entries.size() == capacity) {
            var iterator = entries.entrySet().iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                if (!candidate.getValue().evictable()) continue;
                expiredThrough = Math.max(expiredThrough, candidate.getKey());
                iterator.remove();
                evictions++;
                removed = true;
                break;
            }
            if (!removed) throw new IllegalStateException("command evidence admission was not reserved");
        }
        entries.put(evidence.transactionSequence(), new Entry(evidence));
    }

    void completed(CommandExecutionEvidence evidence) {
        requireOpen();
        Entry entry = entries.get(evidence.transactionSequence());
        if (entry == null || entry.terminal || (!evidence.outcome().gpuCompleted()
                && evidence.outcome() != CommandExecutionEvidence.Outcome.REJECTED)) {
            throw new IllegalArgumentException("expected terminal evidence for a pending command");
        }
        entry.evidence = evidence;
        entry.terminal = true;
    }

    EvidenceQuery<CommandExecutionEvidence> query(long sequence) {
        requireOpen();
        if (sequence < 0) throw new IllegalArgumentException("command sequence must not be negative");
        Entry entry = entries.get(sequence);
        if (entry == null) return EvidenceQuery.absent(sequence <= expiredThrough
                ? EvidenceQuery.Status.OUTSIDE_RETENTION_WINDOW : EvidenceQuery.Status.UNKNOWN);
        if (entry.terminal) entry.observed = true;
        return EvidenceQuery.available(entry.evidence);
    }

    EvidenceLease retain(long sequence) {
        requireOpen();
        Entry entry = entries.get(sequence);
        if (entry == null) throw new IllegalArgumentException("command evidence is unavailable: " + sequence);
        if (leases == capacity) {
            budgetRejections++;
            throw new IllegalStateException("command evidence lease budget exhausted: capacity=" + capacity);
        }
        entry.pins++;
        leases++;
        return new EvidenceLease() {
            private boolean released;

            @Override
            public void close() {
                if (released) return;
                released = true;
                if (!closed) {
                    entry.pins--;
                    leases--;
                }
            }
        };
    }

    void failPending(CommandExecutionEvidence.Reason reason, String detail) {
        requireOpen();
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.terminal) continue;
            entry.evidence = new CommandExecutionEvidence(item.getKey(), CommandExecutionEvidence.Outcome.REJECTED,
                    reason, java.util.OptionalLong.empty(), java.util.Optional.empty(), 0, detail);
            entry.terminal = true;
        }
    }

    int size() { return entries.size(); }
    int pending() { return (int) entries.values().stream().filter(entry -> !entry.terminal).count(); }
    int unobserved() { return (int) entries.values().stream().filter(entry -> entry.terminal && !entry.observed).count(); }
    int evictable() { return (int) entries.values().stream().filter(Entry::evictable).count(); }
    int leases() { return leases; }
    long evictions() { return evictions; }
    long budgetRejections() { return budgetRejections; }

    void close() {
        closed = true;
        entries.clear();
        leases = 0;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("command evidence history is closed");
    }

    private static final class Entry {
        private CommandExecutionEvidence evidence;
        private boolean terminal;
        private boolean observed;
        private int pins;

        Entry(CommandExecutionEvidence evidence) { this.evidence = Objects.requireNonNull(evidence, "evidence"); }
        boolean evictable() { return terminal && observed && pins == 0; }
    }
}
