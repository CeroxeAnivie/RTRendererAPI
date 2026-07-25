package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.CameraMedium;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSlotStateMachine.Event;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSlotStateMachine.State;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtPipelineFrameLifecycleSelfTest {
   private RtPipelineFrameLifecycleSelfTest() {
   }

   public static void main(String[] args) {
      enablesReadbackWhenPresentationNeedsCpuFrames();
      followsRenderTargetForVisibleRtOutput();
      throttlesDiagnosticFrameDispatchAndReadback();
      boundsFrameResourceRingSizeForGpuToGpuOutput();
      keepsDescriptorGenerationProgressIndependentFromFrameBacklog();
      gatesPresentationReadbackWithoutDisablingDiagnostics();
      keepsSharedFrameAvailableDuringBackBufferDispatch();
      enforcesFrameSlotOwnershipTransitions();
      retainsExportedSharedSlotsUntilPresentationAcknowledgement();
      snapshotsDispatchSectionKeysBeforeAsyncCompletion();
      tracksSharedFrameSyncHandleType();
      disablesPerFrameExternalSemaphoresByDefault();
      resolvesFrameOutputExtentForPresentation();
      allocatesBlockDecalHashSlotsWithLinearProbing();
      System.out.println("RtPipelineFrameLifecycleSelfTest passed");
   }

   private static void enablesReadbackWhenPresentationNeedsCpuFrames() {
      require(!RtRayTracingPipeline.shouldEnableFrameReadback(false, false, false, false), "background RT dispatch should not force CPU readback when no presentation path needs it");
      require(RtRayTracingPipeline.shouldEnableFrameReadback(true, false, false, false), "explicit readback property should enable frame snapshots");
      require(RtRayTracingPipeline.shouldEnableFrameReadback(false, true, false, false), "CPU presentation bridge requires frame snapshots");
      require(!RtRayTracingPipeline.shouldEnableFrameReadback(false, true, true, false), "GPU shared presentation must not force CPU frame snapshots");
      require(RtRayTracingPipeline.shouldEnableFrameReadback(false, false, false, true), "CPU render replacement requires frame snapshots when shared presentation is disabled");
      require(!RtRayTracingPipeline.shouldEnableFrameReadback(false, false, true, true), "GPU shared render replacement must not force CPU frame snapshots");
   }

   private static void followsRenderTargetForVisibleRtOutput() {
      require(!RtRayTracingPipeline.shouldFollowRenderTargetForOutput(false, false, false, false, false), "background RT output should keep its fixed diagnostic extent");
      require(RtRayTracingPipeline.shouldFollowRenderTargetForOutput(true, false, false, false, false), "explicit CPU readback should preserve target aspect instead of stretching diagnostics");
      require(RtRayTracingPipeline.shouldFollowRenderTargetForOutput(false, true, true, false, false), "GPU shared presentation must not be stuck at the diagnostic 320x180 extent");
      require(RtRayTracingPipeline.shouldFollowRenderTargetForOutput(false, false, true, true, false), "GPU shared render replacement must render at the visible target-derived extent");
      require(RtRayTracingPipeline.shouldFollowRenderTargetForOutput(false, false, false, false, true), "visual output experiments should use target-derived output dimensions");
   }

   private static void throttlesDiagnosticFrameDispatchAndReadback() {
      String previousDispatchInterval = System.getProperty("top.ceroxe.rt.rt.output.dispatchInterval");
      String previousReadbackInterval = System.getProperty("top.ceroxe.rt.rt.output.readback.interval");

      try {
         System.clearProperty("top.ceroxe.rt.rt.output.dispatchInterval");
         System.clearProperty("top.ceroxe.rt.rt.output.readback.interval");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(false, false, false, false) == 8, "background RT dispatch should use the conservative default cadence");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, false, false, false) == 8, "temporary presentation readback diagnostics must not silently force every-frame dispatch");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, false, true, false) == 1, "explicit visual experiments should default to every-frame RT dispatch");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, true, false, false) == 1, "render replacement should default to every-frame RT dispatch");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(false, false, false, true) == 1, "GPU shared presentation should use the visible shared-frame cadence");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(false, true, false, true) == 1, "GPU shared render replacement should attempt fresh visible frames without building a queue");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(false, false, true, true) == 1, "GPU shared visual experiments should attempt fresh visible frames without building a queue");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(false, true, false, true) == 1, "GPU-shared high-throughput mode must retain per-frame RT submission cadence");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(true, true, true, true, true) == 1000000, "GPU shared visual smoke should prove readback once without turning presentation into a CPU copy loop");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(true, true, false, true, true) == 1, "CPU visual presentation still needs explicit readback sampling");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(false, true, false, false, false) == 4, "presentation gate diagnostics should default to low-frequency CPU readback");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(false, true, false, false, true) == 1, "explicit visual experiments may sample every throttled RT dispatch");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(false, false, false, true, false) == 1, "render replacement needs fresh CPU snapshots until shared-image presentation exists");
         require(RtRayTracingPipeline.presentationFreshnessDispatchWatermark(1) == 10L, "every-frame visible RT dispatch should still catch up before the presentation freshness fence");
         require(!RtRayTracingPipeline.shouldBypassFrameDispatchInterval(false), "presentation gate warmup must not bypass cadence unless freshness catch-up says the held front buffer is aging out");
         require(!RtRayTracingPipeline.shouldDispatchForPresentationFreshness(105L, 100L, -1L, 4), "fresh completed shared frame should allow cadence-based reuse");
         require(RtRayTracingPipeline.shouldDispatchForPresentationFreshness(106L, 100L, -1L, 4), "completed shared frame at the freshness watermark should force a catch-up dispatch");
         require(!RtRayTracingPipeline.shouldDispatchForPresentationFreshness(106L, 100L, 104L, 4), "already queued newer frame should prevent catch-up over-submission");
         require(RtRayTracingPipeline.shouldDispatchForPresentationFreshness(106L, -1L, -1L, 4), "visible RT must dispatch immediately when no completed or queued frame exists");
         System.setProperty("top.ceroxe.rt.rt.output.dispatchInterval", "1");
         System.setProperty("top.ceroxe.rt.rt.output.readback.interval", "2");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, false, true, false) == 1, "explicit dispatch interval should allow bounded deep-diagnostic runs");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(false, true, false, false, false) == 2, "explicit readback interval should allow bounded deep-diagnostic runs");
         System.setProperty("top.ceroxe.rt.rt.output.dispatchInterval", "0");
         System.setProperty("top.ceroxe.rt.rt.output.readback.interval", "bad");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, false, true, false) == 1, "invalid dispatch interval should fall back to the visual experiment default");
         require(RtRayTracingPipeline.frameDispatchIntervalByProperties(true, false, false, false) == 8, "invalid diagnostic dispatch interval should fall back to the conservative default");
         require(RtRayTracingPipeline.frameReadbackIntervalByProperties(false, true, false, false, false) == 4, "invalid readback interval should fall back to the safe default");
         require(!RtRayTracingPipeline.shouldCaptureFrameReadback(false, 0L, 8), "disabled readback must not capture even on the first dispatch");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 1L, 1), "default readback cadence should capture every throttled diagnostic dispatch");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 0L, 8), "first enabled dispatch should capture a diagnostic snapshot immediately");
         require(!RtRayTracingPipeline.shouldCaptureFrameReadback(true, 1L, 8), "readback should be skipped between sampled dispatches");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 7L, 8), "eighth enabled dispatch should refresh the diagnostic snapshot");
         require(!RtRayTracingPipeline.shouldCaptureFrameReadback(true, 8L, 8), "readback cadence should restart after a sampled dispatch");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 15L, 8), "readback cadence should remain stable over multiple periods");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 2L, 8, 10000L, 22L), "large frame-state sequence jumps should force a fresh diagnostic readback");
         require(!RtRayTracingPipeline.shouldCaptureFrameReadback(true, 2L, 8, 29L, 22L), "sequence-age guard should not collapse bounded diagnostic sampling into per-frame readback");
         require(RtRayTracingPipeline.shouldCaptureFrameReadback(true, 2L, 8, 30L, 22L), "sequence-age guard should refresh when the diagnostic snapshot reaches the configured lag");
         require(!RtRayTracingPipeline.shouldCaptureFrameReadback(false, 2L, 8, 10000L, 22L), "disabled readback must ignore sequence-age forcing");
         require(expectFailure(() -> RtRayTracingPipeline.shouldCaptureFrameReadback(true, -1L, 8)) instanceof IllegalArgumentException, "negative completed dispatch count should be rejected");
         require(expectFailure(() -> RtRayTracingPipeline.shouldCaptureFrameReadback(true, 0L, 0)) instanceof IllegalArgumentException, "non-positive readback interval should be rejected");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.output.dispatchInterval", previousDispatchInterval);
         restoreProperty("top.ceroxe.rt.rt.output.readback.interval", previousReadbackInterval);
      }

   }

   private static void boundsFrameResourceRingSizeForGpuToGpuOutput() {
      String previousRingSize = System.getProperty("top.ceroxe.rt.rt.output.frameResourceRingSize");
      String previousMaxPending = System.getProperty("top.ceroxe.rt.rt.output.maxPendingFrames");

      try {
         System.clearProperty("top.ceroxe.rt.rt.output.frameResourceRingSize");
         System.clearProperty("top.ceroxe.rt.rt.output.maxPendingFrames");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties() == 3, "default RT frame resource ring should keep a writable slot separate from the latest shared frame");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties(true, true, false) == 6, "GPU-shared render replacement should cover the complete six-slot ownership set");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties(true, false, true) == 6, "GPU-shared visual output should cover writer, presenter, and front-buffer ownership");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties(true, false, false) == 3, "GPU-shared allocation alone must not inflate non-visual background dispatches");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(false, false, false, 3) == 3, "background RT may use the whole small ring because it is not feeding visible presentation");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(true, true, false, 12) == 3, "GPU-shared render replacement should allow bounded render/driver overlap");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(true, false, true, 12) == 3, "GPU-shared visual output should use the same bounded producer overlap as render replacement");
         System.setProperty("top.ceroxe.rt.rt.output.frameResourceRingSize", "1");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties() == 2, "frame resource ring must never shrink below double buffering");
         System.setProperty("top.ceroxe.rt.rt.output.frameResourceRingSize", "4");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties() == 4, "valid frame resource ring override should be accepted for heavy external gates");
         System.setProperty("top.ceroxe.rt.rt.output.maxPendingFrames", "4");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(true, true, false, 12) == 4, "explicit max pending override should support deep diagnostic runs without changing the resident ring");
         System.setProperty("top.ceroxe.rt.rt.output.frameResourceRingSize", "64");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties() == 24, "frame resource ring override should allow heavy GPU-resident gates without runaway allocation");
         System.setProperty("top.ceroxe.rt.rt.output.maxPendingFrames", "64");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(true, true, false, 12) == 12, "max pending override must be clamped to the resident frame slot count");
         System.setProperty("top.ceroxe.rt.rt.output.frameResourceRingSize", "bad");
         System.setProperty("top.ceroxe.rt.rt.output.maxPendingFrames", "bad");
         require(RtRayTracingPipeline.frameResourceRingSizeByProperties() == 3, "invalid frame resource ring override should fall back to the default");
         require(RtRayTracingPipeline.maxPendingFrameSubmissionsByProperties(true, true, false, 12) == 3, "invalid max pending override should fall back to the bounded visible producer default");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.output.frameResourceRingSize", previousRingSize);
         restoreProperty("top.ceroxe.rt.rt.output.maxPendingFrames", previousMaxPending);
      }

   }

   private static void keepsDescriptorGenerationProgressIndependentFromFrameBacklog() {
      require(RtRayTracingPipeline.descriptorSetCountForFrameSlots(24) == 48, "every output slot must own two immutable descriptor generations");
      require(RtRayTracingPipeline.stageableDescriptorIndex(new long[]{7L, 7L}, 0) == 1, "an in-flight descriptor must leave the second generation writable");
      require(RtRayTracingPipeline.stageableDescriptorIndex(new long[]{8L, 3L}, -1) == 1, "an idle descriptor bank should overwrite its oldest generation first");
      require(RtRayTracingPipeline.frameBoundResourceRetirementGeneration(9L, 7L) == 6L, "resource retirement must stop before the oldest in-flight descriptor generation");
      require(RtRayTracingPipeline.frameBoundResourceRetirementGeneration(9L, -1L) == 9L, "an empty frame queue should release every active descriptor generation");
      require(expectFailure(() -> RtRayTracingPipeline.stageableDescriptorIndex(new long[]{1L}, 0)) instanceof IllegalArgumentException, "single-buffered descriptor ownership must be rejected");
   }

   private static void gatesPresentationReadbackWithoutDisablingDiagnostics() {
      require(!RtRayTracingPipeline.shouldRequirePresentationEligibleForFrameDispatch(false, false, false, false), "background RT dispatch should keep its normal diagnostic cadence");
      require(!RtRayTracingPipeline.shouldRequirePresentationEligibleForFrameDispatch(true, true, true, true), "explicit readback is a diagnostic override and must not be hidden behind presentation readiness");
      require(RtRayTracingPipeline.shouldRequirePresentationEligibleForFrameDispatch(false, true, false, false), "presentation readback should wait for scene readiness instead of stalling every frame");
      require(RtRayTracingPipeline.shouldRequirePresentationEligibleForFrameDispatch(false, false, true, false), "GPU shared presentation should wait for scene readiness without requiring CPU readback");
      require(!RtRayTracingPipeline.shouldRequirePresentationEligibleForFrameDispatch(false, false, false, true), "render replacement must keep dispatching so RT output can recover instead of clearing to a blocked frame");
      require(RtRayTracingPipeline.shouldDispatchPresentationGateProbe(1L, 60), "the first gated frame should still probe so diagnostics can discover scene output");
      require(!RtRayTracingPipeline.shouldDispatchPresentationGateProbe(59L, 60), "gated presentation probes should be throttled between intervals");
      require(RtRayTracingPipeline.shouldDispatchPresentationGateProbe(60L, 60), "gated presentation probes should periodically refresh readiness diagnostics");
   }

   private static void keepsSharedFrameAvailableDuringBackBufferDispatch() {
      require(RtRayTracingPipeline.sharedFrameAvailable(false, false, 42L, true), "completed shared front buffer must remain presentable while a different back buffer dispatch is pending");
      require(!RtRayTracingPipeline.sharedFrameAvailable(false, true, 42L, true), "shared frame must not present when the pending dispatch writes the same image");
      require(!RtRayTracingPipeline.sharedFrameAvailable(false, false, -1L, true), "shared frame must not present before the first completed RT frame");
      require(!RtRayTracingPipeline.sharedFrameAvailable(false, false, 42L, false), "shared frame must not present from non-exportable image memory");
      require(!RtRayTracingPipeline.sharedFrameAvailable(true, false, 42L, true), "closed pipeline must not expose shared frame handles");
   }

   private static void enforcesFrameSlotOwnershipTransitions() {
      RtFrameSlotStateMachine.State state = State.WRITABLE;
      state = RtFrameSlotStateMachine.transition(state, Event.BEGIN_WRITE);
      require(state == State.WRITING, "writer acquisition must move a frame slot to WRITING");
      state = RtFrameSlotStateMachine.transition(state, Event.COMPLETE_WRITE);
      require(state == State.COMPLETED, "GPU completion must move a frame slot to COMPLETED");
      state = RtFrameSlotStateMachine.transition(state, Event.PRESENT);
      require(state == State.PRESENTED, "successful external blit must move a frame slot to PRESENTED");
      state = RtFrameSlotStateMachine.transition(state, Event.RELEASE_PRESENTED_TO_WRITABLE);
      require(state == State.WRITABLE, "only replacement of the presented frame may return its slot to WRITABLE");
      require(expectFailure(() -> RtFrameSlotStateMachine.transition(State.PRESENTED, Event.BEGIN_WRITE)) instanceof IllegalStateException, "a presented frame slot must never be reacquired as a writer");
      require(expectFailure(() -> RtFrameSlotStateMachine.transition(State.WRITABLE, Event.PRESENT)) instanceof IllegalStateException, "an uncompleted frame slot must never be published to the presenter");
   }

   private static void retainsExportedSharedSlotsUntilPresentationAcknowledgement() {
      require(RtRayTracingPipeline.retainsCompletedFrameForSharedPresentation(false, true), "an exported shared slot must survive newer GPU completions until GL consumption is acknowledged");
      require(RtRayTracingPipeline.retainsCompletedFrameForSharedPresentation(true, false), "the committed shared front must remain retained while later frames render");
      require(!RtRayTracingPipeline.retainsCompletedFrameForSharedPresentation(false, false), "an unexported superseded completion should return to the writable ring");
   }

   private static void snapshotsDispatchSectionKeysBeforeAsyncCompletion() {
      SectionKey dispatchedSection = new SectionKey(0, 4, 0);
      SectionKey laterStreamingSection = new SectionKey(40, 4, 40);
      PackedSectionMembership packedSections = PackedSectionMembership.canonicalDistinct(List.of(dispatchedSection));
      require(RtRayTracingPipeline.snapshotFrameSectionKeys(packedSections) == packedSections, "pending frame must retain the bound world-scene packed identity");
      Set<SectionKey> boundSections = new LinkedHashSet<>(Set.of(dispatchedSection));
      Set<SectionKey> dispatchSnapshot = RtRayTracingPipeline.snapshotFrameSectionKeys(boundSections);
      boundSections.clear();
      boundSections.add(laterStreamingSection);
      require(dispatchSnapshot.equals(Set.of(dispatchedSection)), "completed shared frame must retain the exact section keys bound at dispatch time");
      RuntimeException mutationFailure = expectFailure(() -> dispatchSnapshot.add(laterStreamingSection));
      require(mutationFailure instanceof UnsupportedOperationException, "dispatch section-key snapshot must be immutable across asynchronous completion");
   }

   private static void tracksSharedFrameSyncHandleType() {
      require(RtRayTracingPipeline.sharedFrameSyncHandleType(0L, 16) == 0, "missing sync handle should clear the exported sync handle type");
      require(RtRayTracingPipeline.sharedFrameSyncHandleType(1234L, 16) == 16, "present sync handle should preserve the exported semaphore handle type");
      require(expectFailure(() -> RtRayTracingPipeline.sharedFrameSyncHandleType(1234L, 0)) instanceof IllegalArgumentException, "present sync handle without a handle type should be rejected");
   }

   private static void disablesPerFrameExternalSemaphoresByDefault() {
      String previous = System.getProperty("top.ceroxe.rt.rt.output.externalSemaphore.enabled");

      try {
         System.clearProperty("top.ceroxe.rt.rt.output.externalSemaphore.enabled");
         require(!RtRayTracingPipeline.externalFrameSemaphoreEnabled(), "per-frame external semaphores should be opt-in while fence-satisfied shared frames are the stable path");
         System.setProperty("top.ceroxe.rt.rt.output.externalSemaphore.enabled", "true");
         require(RtRayTracingPipeline.externalFrameSemaphoreEnabled(), "explicit external semaphore property should enable focused interop validation runs");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.output.externalSemaphore.enabled", previous);
      }

   }

   private static void resolvesFrameOutputExtentForPresentation() {
      RtFrameOutputConfig backgroundConfig = RtRayTracingPipeline.frameOutputConfig(0, 0, 921600, false);
      require(backgroundConfig.resolve(frameState(124L, 1920, 1080)).equals(new RtFrameOutputConfig.Extent(320, 180)), "background RT output should keep the conservative default extent");
      RtFrameOutputConfig presentationConfig = RtRayTracingPipeline.frameOutputConfig(0, 0, 409920, true);
      require(presentationConfig.initialExtent().equals(new RtFrameOutputConfig.Extent(854, 480)), "visible RT output should start from the high-FPS internal budget instead of the tiny diagnostic extent");
      require(presentationConfig.resolve(frameState(125L, 1920, 1080)).equals(new RtFrameOutputConfig.Extent(853, 480)), "presentation output should downscale large render targets while CPU readback presentation is temporary");
      require(presentationConfig.resolve(frameState(126L, 854, 480)).equals(new RtFrameOutputConfig.Extent(854, 480)), "presentation output should not upscale or distort a target already within budget");
      String previousWidth = System.getProperty("top.ceroxe.rt.rt.output.width");
      String previousHeight = System.getProperty("top.ceroxe.rt.rt.output.height");
      String previousMaxPixels = System.getProperty("top.ceroxe.rt.rt.output.maxPixels");
      String previousPrimaryRayScale = System.getProperty("top.ceroxe.rt.rt.primaryRays.upscaleFactor");

      try {
         System.clearProperty("top.ceroxe.rt.rt.output.width");
         System.clearProperty("top.ceroxe.rt.rt.output.height");
         System.clearProperty("top.ceroxe.rt.rt.output.maxPixels");
         System.clearProperty("top.ceroxe.rt.rt.primaryRays.upscaleFactor");
         RtFrameOutputConfig defaultVisibleConfig = RtRayTracingPipeline.frameOutputConfigByProperties(true);
         require(defaultVisibleConfig.resolve(frameState(126L, 2560, 1494)).equals(new RtFrameOutputConfig.Extent(2560, 1494)), "default visible RT output must not blur 2560x1494 by silently downsampling");
         require(defaultVisibleConfig.resolve(frameState(127L, 5120, 2880)).equals(new RtFrameOutputConfig.Extent(5120, 2880)), "default visible RT output must follow high-resolution host application targets without a hard-coded 4K cap");
         require(defaultVisibleConfig.primaryRayUpscaleFactor() == 1, "visible primary rays must trace at native presentation resolution");
         require(defaultVisibleConfig.resolve(frameState(128L, 2560, 1440)).divideAndRoundUp(defaultVisibleConfig.primaryRayUpscaleFactor()).equals(new RtFrameOutputConfig.Extent(2560, 1440)), "2.5K visible RT must not downscale primary rays before presentation");
         System.setProperty("top.ceroxe.rt.rt.primaryRays.upscaleFactor", "4");
         require(RtRayTracingPipeline.frameOutputConfigByProperties(true).primaryRayUpscaleFactor() == 1, "visible RT must reject legacy downscale configuration");
         require(expectFailure(() -> RtRayTracingPipeline.frameOutputConfig(0, 0, 3686400, true, 2)) instanceof IllegalArgumentException, "visible RT must reject explicit blurry primary-ray scaling");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.output.width", previousWidth);
         restoreProperty("top.ceroxe.rt.rt.output.height", previousHeight);
         restoreProperty("top.ceroxe.rt.rt.output.maxPixels", previousMaxPixels);
         restoreProperty("top.ceroxe.rt.rt.primaryRays.upscaleFactor", previousPrimaryRayScale);
      }

      RtFrameOutputConfig highResolutionConfig = RtRayTracingPipeline.frameOutputConfig(0, 0, 8294400, true);
      require(highResolutionConfig.resolve(frameState(126L, 2560, 1494)).equals(new RtFrameOutputConfig.Extent(2560, 1494)), "visible RT output should preserve native render-target resolution inside the default 4K-class budget");
      RtFrameOutputConfig fixedFromWidth = RtRayTracingPipeline.frameOutputConfig(854, 0, 921600, true);
      require(fixedFromWidth.initialExtent().equals(new RtFrameOutputConfig.Extent(854, 480)), "single fixed width should infer the default 16:9 height");
      require(fixedFromWidth.resolve(frameState(127L, 1920, 1080)).equals(new RtFrameOutputConfig.Extent(854, 480)), "explicit output size should take precedence over target-following");
   }

   private static void allocatesBlockDecalHashSlotsWithLinearProbing() {
      boolean[] negativeCoordinateSlots = new boolean[128];
      require(RtRayTracingPipeline.blockDecalTableSlot(-17, 65, 42, negativeCoordinateSlots) == 49, "block decal hashing must preserve Java/GLSL unsigned-shift parity for negative coordinates");
      boolean[] collidingSlots = new boolean[128];
      int firstSlot = RtRayTracingPipeline.blockDecalTableSlot(-32, -8, -16, collidingSlots);
      int secondSlot = RtRayTracingPipeline.blockDecalTableSlot(-32, -8, -14, collidingSlots);
      require(firstSlot == 123 && secondSlot == 124, "block decal collisions must use the same bounded linear probing as closest-hit lookup");
      boolean[] fullTable = new boolean[128];
      Arrays.fill(fullTable, true);
      require(expectFailure(() -> RtRayTracingPipeline.blockDecalTableSlot(0, 0, 0, fullTable)) instanceof IllegalStateException, "a full block decal table must fail before corrupting an occupied shader slot");
      require(expectFailure(() -> RtRayTracingPipeline.blockDecalTableSlot(0, 0, 0, new boolean[64])) instanceof IllegalArgumentException, "block decal occupancy must reject capacities that diverge from the shader ABI");
   }

   private static RendererFrameState frameState(long sequence) {
      return frameState(sequence, 640, 360);
   }

   private static RendererFrameState frameState(long sequence, int targetWidth, int targetHeight) {
      return frameState(sequence, targetWidth, targetHeight, CameraMedium.air());
   }

   private static RendererFrameState frameState(long sequence, int targetWidth, int targetHeight, CameraMedium cameraFluidMedium) {
      return new RendererFrameState(sequence, true, targetWidth, targetHeight, 1.0, 2.0, 3.0, 10.0F, 20.0F, 0.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, -1.0F, 0.0F, cameraFluidMedium, false, true);
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException ex) {
         return ex;
      }

      throw new AssertionError("expected failure");
   }

   private static void restoreProperty(String name, String value) {
      if (value == null) {
         System.clearProperty(name);
      } else {
         System.setProperty(name, value);
      }

   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
