package top.ceroxe.rt.renderer.rt.acceleration;

import jdk.jfr.*;

/**
 * JFR payload that makes bounded section-BLAS flight-recorder loss observable.
 */
@Name("top.ceroxe.rt.SectionBlasCaptureLoss")
@Label("RTRenderer Section BLAS Capture Loss")
@Category({"RTRenderer", "Acceleration", "BLAS"})
@StackTrace(false)
final class RtSectionBlasCaptureLossEvent extends Event {
    long maxEvents;
    long attemptedEvents;
    long droppedEventsLowerBound;
    long overflowSampleStride;
}
