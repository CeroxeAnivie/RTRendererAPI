package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, strictly ordered generic rendering command transaction.
 *
 * <p>The sequence is an application-owned monotonic identity; the value type deliberately does
 * not guess ordering relative to another transaction. A renderer session enforces monotonicity
 * at admission. Construction validates all context-free command data plus pass sequencing and
 * draw prerequisites before a provider can observe the transaction.</p>
 */
public final class RenderCommandTransaction {
    private final long sequence;
    private final List<RenderCommand> commands;

    /** Creates a validated immutable transaction snapshot. */
    public RenderCommandTransaction(long sequence, List<? extends RenderCommand> commands) {
        if (sequence < 0L) throw new IllegalArgumentException("command transaction sequence must not be negative");
        Objects.requireNonNull(commands, "commands");
        ArrayList<RenderCommand> copied = new ArrayList<>(commands.size());
        for (RenderCommand command : commands) {
            copied.add(Objects.requireNonNull(command, "command"));
        }
        if (copied.isEmpty()) throw new IllegalArgumentException("command transaction must not be empty");
        List<RenderCommand> immutable = List.copyOf(copied);
        RenderCommandSequenceValidator.validate(immutable);
        this.sequence = sequence;
        this.commands = immutable;
    }

    /** @return non-negative caller-owned ordering sequence */
    public long sequence() { return sequence; }

    /** @return immutable commands in exact execution order */
    public List<RenderCommand> commands() { return commands; }

    /** Starts a single-thread-confined convenience builder. */
    public static Builder builder(long sequence) { return new Builder(sequence); }

    /** Single-thread-confined builder whose output is always revalidated at the immutable boundary. */
    public static final class Builder {
        private final long sequence;
        private final ArrayList<RenderCommand> commands = new ArrayList<>();

        private Builder(long sequence) {
            if (sequence < 0L) throw new IllegalArgumentException("command transaction sequence must not be negative");
            this.sequence = sequence;
        }

        /** Appends one command without changing its relative order. */
        public Builder add(RenderCommand command) {
            commands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        /** Appends commands in iteration order. */
        public Builder addAll(Iterable<? extends RenderCommand> values) {
            Objects.requireNonNull(values, "commands").forEach(this::add);
            return this;
        }

        /** Creates an independent immutable and fully validated transaction snapshot. */
        public RenderCommandTransaction build() {
            return new RenderCommandTransaction(sequence, commands);
        }
    }
}
