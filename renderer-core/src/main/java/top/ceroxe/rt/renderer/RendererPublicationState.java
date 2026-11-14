package top.ceroxe.rt.renderer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the coherent host-to-renderer publication slots.
 *
 * <p>Revision counters and their immutable payloads must advance together.
 * Keeping both pairs here prevents the orchestration facade from becoming the
 * accidental owner of publication state while preserving lock-free readers.</p>
 */
final class RendererPublicationState {
    private final AtomicLong rendererViewRevisions = new AtomicLong();
    private final AtomicReference<RendererViewState> latestRendererView =
            new AtomicReference<>(RendererViewState.allResident());
    private final AtomicLong rasterObservationRevisions = new AtomicLong();
    private final AtomicReference<RendererRasterSectionObservation> latestRasterSectionObservation =
            new AtomicReference<>(RendererRasterSectionObservation.empty());

    RendererViewState latestRendererView() {
        return latestRendererView.get();
    }

    void publishRendererView(RendererViewState view) {
        latestRendererView.set(view);
    }

    long nextRendererViewRevision() {
        return rendererViewRevisions.incrementAndGet();
    }

    RendererRasterSectionObservation latestRasterSectionObservation() {
        return latestRasterSectionObservation.get();
    }

    void publishRasterSectionObservation(RendererRasterSectionObservation observation) {
        latestRasterSectionObservation.set(observation);
    }

    long nextRasterObservationRevision() {
        return rasterObservationRevisions.incrementAndGet();
    }

    void resetRendererView() {
        latestRendererView.set(RendererViewState.allResident());
    }
}
