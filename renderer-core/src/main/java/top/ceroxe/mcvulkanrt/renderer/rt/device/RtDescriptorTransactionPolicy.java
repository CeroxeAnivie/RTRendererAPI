package top.ceroxe.mcvulkanrt.renderer.rt.device;

/** Pure resource-mutation policy for descriptor-visible scene transactions. */
final class RtDescriptorTransactionPolicy {
    private RtDescriptorTransactionPolicy() {
    }

    /**
     * A bound scene is an immutable front. Successor uploads must target copy-on-write buffers so
     * already-bound descriptors and in-flight frames remain valid until fence retirement. In-place
     * upload is permitted only during bootstrap, when no visible descriptor generation exists.
     */
    static boolean allowInPlaceMaterialUpload(
            boolean boundScenePresent,
            boolean pipelineCanUpdateInPlace
    ) {
        return !boundScenePresent && pipelineCanUpdateInPlace;
    }
}
