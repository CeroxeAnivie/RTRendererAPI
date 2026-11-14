package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.DynamicRenderScene;

import java.util.Objects;

/**
 * Authoritative CPU front and lifecycle counters for the most recently accepted dynamic scene.
 *
 * <p>The pipeline may receive a poll-only empty value while an earlier dynamic generation remains
 * authoritative. This owner prevents that sentinel from clearing the retained scene and keeps
 * accepted, uploaded, and dispatched revisions as distinct lifecycle facts.</p>
 */
final class RtDynamicSceneFront {
    private DynamicRenderScene scene = DynamicRenderScene.empty();
    private long uploads;
    private long revisionChanges;
    private long dispatchedRevision = -1L;
    private int elements;

    void accept(DynamicRenderScene candidate) {
        DynamicRenderScene accepted = Objects.requireNonNull(candidate, "candidate");
        if (!accepted.hasSceneUpdate()) {
            return;
        }
        if (accepted.revision() != scene.revision()) {
            revisionChanges++;
        }
        scene = accepted;
        elements = accepted.totalElements();
    }

    void recordUpload() {
        uploads++;
    }

    void recordDispatched(long sceneRevision) {
        if (sceneRevision < 0L) {
            throw new IllegalArgumentException("dispatched dynamic scene revision must not be negative");
        }
        dispatchedRevision = sceneRevision;
    }

    DynamicRenderScene scene() {
        return scene;
    }

    long revision() {
        return scene.revision();
    }

    long dispatchedRevision() {
        return dispatchedRevision;
    }

    long uploads() {
        return uploads;
    }

    long revisionChanges() {
        return revisionChanges;
    }

    int elements() {
        return elements;
    }
}
