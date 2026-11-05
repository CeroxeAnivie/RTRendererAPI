package top.ceroxe.rt.renderer.rtxpt;

/** Native preparation has not mutated the resident scene or accepted a frame. */
final class RtxptResourcePendingException extends RuntimeException {
    RtxptResourcePendingException(String message) { super(message); }
}
