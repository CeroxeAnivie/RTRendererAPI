package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Validates frame inputs and monotonic scene revisions before native state changes. */
final class RtxptInputContract {
    private RtxptInputContract() { }

    static void validateFrame(RenderFrameRequest frame) {
        validateFrame(frame,false);
    }
    static void validateFrame(RenderFrameRequest frame,boolean nativeLighting) {
        Objects.requireNonNull(frame, "frame");
        if ((!nativeLighting&&frame.camera().hasExactProjection())
                || (!nativeLighting&&frame.fog().opacity() > 0))
            throw new IllegalArgumentException("Exact projections and screen fog require the independent native-lighting output contract");


    }

    static void requireAdvancingRevision(long requested, long current, boolean published) {
        if (published && requested <= current)
            throw new SceneRevisionException("Scene revision must strictly increase: " + requested + " <= " + current);
    }
}
