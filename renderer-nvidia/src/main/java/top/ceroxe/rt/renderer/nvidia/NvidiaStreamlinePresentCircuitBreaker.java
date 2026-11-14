package top.ceroxe.rt.renderer.nvidia;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRSwapchain;

import java.util.Objects;
import java.util.Optional;

/** One-way DLSS-G/MFG presentation fuse scoped to a negotiated feature session. */
final class NvidiaStreamlinePresentCircuitBreaker {
    private int requestedGeneratedFrames;
    private boolean disabled;
    private FailureSnapshot failure;

    NvidiaStreamlinePresentCircuitBreaker(int requestedGeneratedFrames) {
        this.requestedGeneratedFrames = requestedGeneratedFrames;
    }

    synchronized int generatedFramesForPresent() {
        return disabled ? 0 : requestedGeneratedFrames;
    }

    synchronized boolean observeResult(
            int result,
            int attemptedGeneratedFrames,
            long frameSequence
    ) {
        requireSequence(frameSequence);
        boolean normalWsiResult = result == VK10.VK_SUCCESS
                || result == KHRSwapchain.VK_SUBOPTIMAL_KHR
                || result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
        return attemptedGeneratedFrames != 0 && !normalWsiResult && trip(
                FailureSnapshot.wsiResult(frameSequence, result)
        );
    }

    synchronized boolean observeFailure(
            int attemptedGeneratedFrames,
            long frameSequence,
            Throwable thrown
    ) {
        requireSequence(frameSequence);
        return attemptedGeneratedFrames != 0 && trip(
                FailureSnapshot.thrown(frameSequence, Objects.requireNonNull(thrown, "thrown"))
        );
    }

    synchronized boolean disable() {
        if (requestedGeneratedFrames == 0 || disabled) return false;
        disabled = true;
        return true;
    }

    synchronized boolean enabled() {
        return requestedGeneratedFrames != 0 && !disabled;
    }

    synchronized int requestedGeneratedFrames() {
        return requestedGeneratedFrames;
    }

    synchronized void reconfigure(int generatedFrames) {
        if (generatedFrames < -3 || generatedFrames > 3) {
            throw new IllegalArgumentException("generated frame request must be in [-3, 3]");
        }
        if (disabled && generatedFrames != 0) {
            throw new IllegalStateException("tripped frame-generation circuit requires swapchain rebuild");
        }
        requestedGeneratedFrames = generatedFrames;
    }

    synchronized Optional<FailureSnapshot> failureSnapshot() {
        return Optional.ofNullable(failure);
    }

    private boolean trip(FailureSnapshot snapshot) {
        if (disabled) return false;
        failure = Objects.requireNonNull(snapshot, "snapshot");
        disabled = true;
        return true;
    }

    private static void requireSequence(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
    }

    enum FailureKind {
        WSI_RESULT,
        THROWN_FAILURE
    }

    /** Immutable failure fact retained after the one-way presentation fuse opens. */
    record FailureSnapshot(
            FailureKind kind,
            long frameSequence,
            String code,
            String reason
    ) {
        private static final int MAX_REASON_LENGTH = 192;

        FailureSnapshot {
            Objects.requireNonNull(kind, "kind");
            requireSequence(frameSequence);
            code = requireText(code, "code");
            reason = concise(requireText(reason, "reason"));
        }

        private static FailureSnapshot wsiResult(long frameSequence, int result) {
            String code = "VK_RESULT_" + result;
            return new FailureSnapshot(
                    FailureKind.WSI_RESULT,
                    frameSequence,
                    code,
                    "Streamline proxy vkQueuePresentKHR returned " + result
            );
        }

        private static FailureSnapshot thrown(long frameSequence, Throwable failure) {
            String code = failure.getClass().getSimpleName();
            if (code.isBlank()) code = failure.getClass().getName();
            String message = failure.getMessage();
            return new FailureSnapshot(
                    FailureKind.THROWN_FAILURE,
                    frameSequence,
                    code,
                    "Streamline proxy present threw " + code
                            + (message == null || message.isBlank() ? "" : ": " + message.trim())
            );
        }

        String description() {
            return code + ": " + reason;
        }

        private static String requireText(String value, String label) {
            String checked = Objects.requireNonNull(value, label).trim();
            if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
            return checked;
        }

        private static String concise(String value) {
            return value.length() <= MAX_REASON_LENGTH
                    ? value : value.substring(0, MAX_REASON_LENGTH - 3) + "...";
        }
    }
}
