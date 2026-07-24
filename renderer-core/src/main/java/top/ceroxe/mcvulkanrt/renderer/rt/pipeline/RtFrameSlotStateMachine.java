package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.Objects;

/** Pure frame-slot lifecycle policy with no Vulkan resource ownership. */
final class RtFrameSlotStateMachine {
    enum State {
        WRITABLE,
        WRITING,
        COMPLETED,
        PRESENTED
    }

    enum Event {
        BEGIN_WRITE,
        ABORT_WRITE,
        COMPLETE_WRITE,
        PRESENT,
        SUPERSEDE_COMPLETED,
        RELEASE_PRESENTED_TO_COMPLETED,
        RELEASE_PRESENTED_TO_WRITABLE
    }

    private RtFrameSlotStateMachine() {
    }

    static State transition(State state, Event event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case BEGIN_WRITE -> require(state, State.WRITABLE, State.WRITING, event);
            case ABORT_WRITE -> require(state, State.WRITING, State.WRITABLE, event);
            case COMPLETE_WRITE -> require(state, State.WRITING, State.COMPLETED, event);
            case PRESENT -> {
                if (state != State.COMPLETED && state != State.PRESENTED) {
                    throw new IllegalStateException("cannot apply " + event + " to frame slot in " + state);
                }
                yield State.PRESENTED;
            }
            case SUPERSEDE_COMPLETED -> require(state, State.COMPLETED, State.WRITABLE, event);
            case RELEASE_PRESENTED_TO_COMPLETED -> require(state, State.PRESENTED, State.COMPLETED, event);
            case RELEASE_PRESENTED_TO_WRITABLE -> require(state, State.PRESENTED, State.WRITABLE, event);
        };
    }

    private static State require(
            State actual,
            State required,
            State next,
            Event event
    ) {
        if (actual != required) {
            throw new IllegalStateException("cannot apply " + event + " to frame slot in " + actual);
        }
        return next;
    }
}
