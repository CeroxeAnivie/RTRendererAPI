package top.ceroxe.mcvulkanrt.renderer.rt.material;

/** Locks material upload counter semantics and the externally consumed summary field names. */
public final class RtMaterialUploadStatisticsSelfTest {
    private RtMaterialUploadStatisticsSelfTest() {
    }

    public static void main(String[] args) {
        recordsLifecycleAndCommittedSnapshot();
        rejectsInvalidEvidenceWithoutMutatingState();
        System.out.println("RtMaterialUploadStatisticsSelfTest passed");
    }

    private static void recordsLifecycleAndCommittedSnapshot() {
        RtMaterialUploadStatistics statistics = new RtMaterialUploadStatistics();
        statistics.submitted();
        statistics.polledNotReady();
        statistics.closeWaited();
        statistics.completed(7L);
        RtSceneMaterialTable.Snapshot snapshot = RtSceneMaterialTable.bootstrapSnapshot();
        statistics.committed(
                snapshot.signature(),
                4,
                true,
                true,
                1L,
                2L,
                3L,
                4L,
                40L,
                false
        );

        String summary = statistics.summary("material", "pool=ready", 64L, 128L, 256L, 512L);
        requireContains(summary, "material{sections=1, faces=1, uploads=1");
        requireContains(summary, "asyncUploadSubmissions=1");
        requireContains(summary, "asyncUploadCompletions=1");
        requireContains(summary, "asyncUploadPollsNotReady=1");
        requireContains(summary, "asyncUploadCloseWaits=1");
        requireContains(summary, "reallocations=4");
        requireContains(summary, "fullMaterialUploads=1");
        requireContains(summary, "dirtySectionRecordUploads=1");
        requireContains(summary, "dirtyFaceRecordUploads=2");
        requireContains(summary, "dirtyTextureRecordUploads=3");
        requireContains(summary, "dirtyTexturePixelUploads=4");
        requireContains(summary, "stagedMaterialUploadBytes=40");
        requireContains(summary, "lastAsyncUploadLatencyMillis=7");
        requireContains(summary, "sectionBufferBytes=64");
        requireContains(summary, "texturePixelBufferBytes=512}");
    }

    private static void rejectsInvalidEvidenceWithoutMutatingState() {
        RtMaterialUploadStatistics statistics = new RtMaterialUploadStatistics();
        requireFailure(() -> statistics.completed(-1L));
        requireFailure(() -> statistics.committed(
                RtSceneMaterialTable.bootstrapSnapshot().signature(),
                -1,
                true,
                true,
                0L,
                0L,
                0L,
                0L,
                0L,
                false
        ));
        String summary = statistics.summary("material", "pool=empty", 0L, 0L, 0L, 0L);
        requireContains(summary, "uploads=0");
        requireContains(summary, "asyncUploadCompletions=0");
    }

    private static void requireFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid material upload evidence was accepted");
    }

    private static void requireContains(String value, String expected) {
        if (!value.contains(expected)) {
            throw new AssertionError("missing summary field: " + expected + " in " + value);
        }
    }
}
