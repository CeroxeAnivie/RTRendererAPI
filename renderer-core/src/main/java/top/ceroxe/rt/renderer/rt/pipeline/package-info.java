/**
 * Renderer-owned RT frame-chain stages.
 *
 * <p>The core pipeline is deliberately a coordinator, not a resource catch-all. Scene ingestion
 * publishes immutable section and dynamic-scene revisions; material and acceleration subsystems
 * bind descriptor-visible resources before a frame is admitted. A {@link
 * top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchRequest} freezes that proof. {@link
 * top.ceroxe.rt.renderer.rt.pipeline.RtAsyncFrameDispatchRecorder} then owns only native
 * command recording and submission, while {@link
 * top.ceroxe.rt.renderer.rt.pipeline.RtPendingFrameSubmission} retains the exact proof
 * until FIFO fence completion.</p>
 *
 * <p>{@link top.ceroxe.rt.renderer.rt.pipeline.RtFrameSlotRing} owns output-image ring
 * replacement and delayed resource retirement. {@link
 * top.ceroxe.rt.renderer.rt.pipeline.RtSharedFramePublicationLedger} owns completion,
 * export, and presentation identity without owning Vulkan resources. This separation prevents a
 * presentation hold from permitting reuse of an image still visible to another API.</p>
 *
 * <p>Each stage preserves the same frame causality through its immutable request or publication
 * proof. {@link top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchFlightRecorder} is the
 * dispatch-level JFR/flight-recorder evidence sink; it is observational only and must never alter
 * admission, synchronization, or resource-release decisions.</p>
 */
package top.ceroxe.rt.renderer.rt.pipeline;
