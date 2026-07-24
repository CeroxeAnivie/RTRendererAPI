package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Objects;

/**
 * Immutable-scene publication ledger for a ring of renderer-owned frame slots.
 *
 * <p>GPU completion, export, and host acknowledgement have distinct lifetimes.
 * This class owns only their identity transitions. The pipeline remains the sole
 * owner of Vulkan resources and performs releases from the decisions returned
 * here. Keeping the state machine resource-free makes retained-frame behavior
 * independently testable and prevents an export hold from being confused with a
 * completed-but-reusable slot.</p>
 */
final class RtSharedFramePublicationLedger<T> {
    private T latestCompletedSlot;
    private T presentedSlot;
    private T exportedSlot;
    private RtCore.SharedFrameState latestCompletedState = RtCore.SharedFrameState.unavailable();
    private RtCore.SharedFrameState presentedState = RtCore.SharedFrameState.unavailable();

    Completion<T> complete(T slot, RtCore.SharedFrameState state) {
        T completed = Objects.requireNonNull(slot, "slot");
        RtCore.SharedFrameState published = Objects.requireNonNull(state, "state");
        if (!published.available()) {
            throw new IllegalArgumentException("completed frame state must be available");
        }
        T previousLatest = latestCompletedSlot;
        latestCompletedSlot = completed;
        latestCompletedState = published;
        return new Completion<>(previousLatest, completed);
    }

    ExportReservation<T> reserveExport(T slot) {
        T completed = Objects.requireNonNull(slot, "slot");
        if (completed != latestCompletedSlot && completed != presentedSlot) {
            throw new IllegalStateException("shared frame export must reserve the latest or presented slot");
        }
        T previousExport = exportedSlot;
        exportedSlot = completed;
        return new ExportReservation<>(previousExport, completed);
    }

    Acknowledgement<T> acknowledge(T slot, long frameStateSequence) {
        T acknowledged = Objects.requireNonNull(slot, "slot");
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (exportedSlot == acknowledged) {
            exportedSlot = null;
        }
        if (presentedSlot == acknowledged) {
            publishAcknowledgedState(acknowledged, frameStateSequence);
            return new Acknowledgement<>(null, acknowledged, true);
        }
        T previousPresented = presentedSlot;
        presentedSlot = acknowledged;
        publishAcknowledgedState(acknowledged, frameStateSequence);
        return new Acknowledgement<>(previousPresented, acknowledged, false);
    }

    T clearLatestCompletion() {
        T previousLatest = latestCompletedSlot;
        latestCompletedSlot = null;
        latestCompletedState = RtCore.SharedFrameState.unavailable();
        return previousLatest;
    }

    void reset() {
        latestCompletedSlot = null;
        presentedSlot = null;
        exportedSlot = null;
        latestCompletedState = RtCore.SharedFrameState.unavailable();
        presentedState = RtCore.SharedFrameState.unavailable();
    }

    T latestCompletedSlot() {
        return latestCompletedSlot;
    }

    T presentedSlot() {
        return presentedSlot;
    }

    T exportedSlot() {
        return exportedSlot;
    }

    RtCore.SharedFrameState latestCompletedState() {
        return latestCompletedState;
    }

    RtCore.SharedFrameState presentedState() {
        return presentedState;
    }

    boolean retainsForPublication(T slot) {
        return slot != null && (slot == presentedSlot || slot == exportedSlot);
    }

    boolean references(T slot) {
        return slot != null && (slot == latestCompletedSlot || retainsForPublication(slot));
    }

    private void publishAcknowledgedState(T acknowledged, long frameStateSequence) {
        if (acknowledged == latestCompletedSlot
                && latestCompletedState.available()
                && latestCompletedState.frameStateSequence() == frameStateSequence) {
            presentedState = latestCompletedState;
        } else if (presentedState.frameStateSequence() != frameStateSequence) {
            /* A retired image can remain visible but has no current scene proof. */
            presentedState = RtCore.SharedFrameState.unavailable();
        }
    }

    record Completion<T>(T previousLatest, T completed) {
    }

    record ExportReservation<T>(T previousExport, T exported) {
    }

    record Acknowledgement<T>(T previousPresented, T acknowledged, boolean alreadyPresented) {
    }
}
