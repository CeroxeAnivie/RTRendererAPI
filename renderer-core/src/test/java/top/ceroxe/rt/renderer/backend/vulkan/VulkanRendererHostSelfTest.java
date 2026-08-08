package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.RendererStateException;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneRevisionException;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SubmissionOrderException;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.RayTracingRenderer.Status;
import top.ceroxe.rt.renderer.api.RendererDeviceException.Reason;
import top.ceroxe.rt.renderer.api.RendererDeviceException.RecoveryAction;
import top.ceroxe.rt.renderer.api.RendererDiagnostics.FrameGpuTiming;
import top.ceroxe.rt.renderer.api.RendererHealth.Kind;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.FrameDescriptor;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.HandleState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ImportDisposition;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop.FrameNotReady;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanRenderingSession.State;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanRenderingSession.Telemetry;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

public final class VulkanRendererHostSelfTest {
   private VulkanRendererHostSelfTest() {
   }

   public static void main(String[] args) {
      rejectsAndClosesInvalidInitialSession();
      publishesLiveFeatureCapabilities();
      advancesOnlyAfterBackendAdmission();
      validatesFrameOrderBeforeDispatch();
      preservesRendererAfterPermanentFrameValidationFailure();
      boundsSustainedProducerLeadUntilPresentationRetiresFrames();
      honorsBackendManagedPresentationProducerLeadLimit();
      closingManagedPresenterClearsProducerFlowControl();
      keepsManagedAndExpertFrameConsumersMutuallyExclusive();
      publishesCpuFramesWithoutNativeInterop();
      publishesBoundedDiagnosticsAndClosesOnce();
      retriesFailedSessionClose();
      defersSessionCloseUntilFrameLeaseCompletion();
      retriesDeferredSessionCloseWithoutRetiringLeaseTwice();
      backendContractViolationFailsPermanently();
      backendFailureClosesResourcesExactlyOnce();
      outOfMemoryAndDriverFailuresRemainTypedAndDoNotAutoRecover();
      recreatesDeviceAndReplaysCommittedSceneOnce();
      deviceRecoveryClearsDiscardedPresenterBacklog();
      doesNotRecoverBeforeLostSessionCloses();
      recoveryBeforeFirstScenePreservesRevisionZero();
      defersDeviceRecoveryUntilExternalLeaseCompletion();
      publishesDeviceRecoveryHistoryInvalidationOnFirstRecoveredFrame();
      System.out.println("VulkanRendererHostSelfTest passed");
   }

   private static void publishesDeviceRecoveryHistoryInvalidationOnFirstRecoveredFrame() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), () ->
            opens[0]++ == 0 ? initial : recovered);
      initial.failNextFrameWith = deviceLost("syntheticRecoveredFrame");
      expect(RendererDeviceException.class, () -> renderer.submit(frame(0L, 0L)));
      RayTracingRenderer.FrameSubmissionAttempt retry = renderer.trySubmit(frame(0L, 0L));
      require(retry instanceof RayTracingRenderer.FrameSubmitted,
            "first frame after device recovery was not admitted");
      require(((RayTracingRenderer.FrameSubmitted) retry).submission().historyInvalidations()
                    .contains(top.ceroxe.rt.renderer.api.HistoryInvalidationReason.DEVICE_RECOVERY),
            "first frame after device recovery did not force temporal history restart");
      renderer.close();
   }

   private static void rejectsAndClosesInvalidInitialSession() {
      TrackingSession session = new TrackingSession();
      session.state = State.FAILED;
      expect(IllegalArgumentException.class, () -> renderer(session));
      require(session.closes == 1, "invalid initial session leaked its resources");
   }

   private static void publishesLiveFeatureCapabilities() {
      TrackingSession session = new TrackingSession();
      session.featureCapabilities = frameGenerationCapability(
              RenderingFeatureCapabilities.Status.AVAILABLE
      );
      VulkanRendererHost renderer = renderer(session);
      RenderingFeatureCapabilities initial = renderer.extension(RenderingFeatureCapabilities.class)
              .orElseThrow();
      require(initial.feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION).status()
                      == RenderingFeatureCapabilities.Status.AVAILABLE,
              "renderer did not publish the armed feature snapshot");

      session.featureCapabilities = frameGenerationCapability(
              RenderingFeatureCapabilities.Status.ACTIVE
      );
      RenderingFeatureCapabilities active = renderer.extension(RenderingFeatureCapabilities.class)
              .orElseThrow();
      require(active.feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION).status()
                      == RenderingFeatureCapabilities.Status.ACTIVE,
              "renderer retained a stale feature snapshot after execution evidence changed");
      renderer.close();
   }

   private static RenderingFeatureCapabilities frameGenerationCapability(
           RenderingFeatureCapabilities.Status status
   ) {
      return RenderingFeatureCapabilities.builder()
              .feature(
                      RenderingFeatureCapabilities.Feature.FRAME_GENERATION,
                      RenderingFeatureCapabilities.Entry.of(
                              status, "test.frame-generation", "synthetic execution state"
                      )
              )
              .build();
   }

   private static void advancesOnlyAfterBackendAdmission() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      RayTracingRenderer.SceneUpdateResult initial = renderer.apply(scene(0L));
      require(initial.acceptedSceneRevision() == 0L, "initial scene revision changed");
      require(session.lastSceneChangeSet != null && session.lastSceneChangeSet.materials().statistics().writes() == 1 && session.lastSceneChangeSet.meshes().statistics().writes() == 1 && session.lastSceneChangeSet.instances().statistics().writes() == 1, "host did not submit the sparse GPUScene payload to native admission");
      session.rejectNextScene = true;
      expect(SubmissionRejectedException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
      require(renderer.status() == Status.READY, "recoverable scene rejection failed renderer");
      require(renderer.diagnostics().latestAcceptedSceneRevision() == 0L, "rejected scene advanced host authority");
      RayTracingRenderer.SceneUpdateResult retried = renderer.apply(SceneTransaction.empty(1L));
      require(retried.acceptedSceneRevision() == 1L, "same revision could not retry after atomic rejection");
      require(session.sceneSubmissions == 3, "scene submissions were not delegated exactly once per attempt");
      require(session.lastSceneChangeSet.baseRevision() == 0L && session.lastSceneChangeSet.revision() == 1L && session.lastSceneChangeSet.totalWrites() == 0 && session.lastSceneChangeSet.totalClears() == 0, "rejected native admission mutated GPUScene residency before retry");
      renderer.close();
   }

   private static void validatesFrameOrderBeforeDispatch() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      expect(SceneRevisionException.class, () -> renderer.submit(frame(1L, 1L)));
      require(session.frameSubmissions == 0, "future scene requirement reached backend");
      session.rejectNextFrame = true;
      expect(SubmissionRejectedException.class, () -> renderer.submit(frame(5L, 0L)));
      require(renderer.diagnostics().latestSubmittedFrameSequence() == -1L, "rejected frame advanced submitted sequence");
      RayTracingRenderer.FrameSubmissionResult accepted = renderer.submit(frame(5L, 0L));
      require(accepted.frameSequence() == 5L && accepted.scheduledSceneRevision() == 0L, "accepted frame submission changed");
      session.rejectNextFrame = true;
      RayTracingRenderer.FrameSubmissionAttempt deferred = renderer.trySubmit(frame(6L, 0L));
      require(deferred instanceof RayTracingRenderer.FrameSubmissionDeferred,
              "capacity-aware submission converted recoverable backpressure into an exception");
      RayTracingRenderer.FrameSubmissionAttempt submitted = renderer.trySubmit(frame(6L, 0L));
      require(submitted instanceof RayTracingRenderer.FrameSubmitted success
                      && success.submission().frameSequence() == 6L,
              "capacity-aware retry did not preserve frame identity");
      expect(SubmissionOrderException.class, () -> renderer.submit(frame(6L, 0L)));
      require(session.frameSubmissions == 4, "duplicate frame sequence reached backend");
      renderer.close();
   }

   private static void preservesRendererAfterPermanentFrameValidationFailure() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      session.failNextFrameWith = new FrameValidationException(
              FrameValidationException.Reason.MISSING_DEPTH_PROJECTION,
              "synthetic missing projection"
      );
      try {
         renderer.trySubmit(frame(1L, 0L));
         throw new AssertionError("permanent frame error became a deferred or accepted submission");
      } catch (FrameValidationException validation) {
         require(validation.reason() == FrameValidationException.Reason.MISSING_DEPTH_PROJECTION,
                 "frame validation reason changed at the host boundary");
      }
      require(renderer.status() == Status.READY,
              "caller-correctable frame validation failure poisoned the renderer lifecycle");
      require(renderer.trySubmit(frame(1L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "corrected retry with the same unaccepted sequence was not admitted");
      renderer.close();
   }

   private static void boundsSustainedProducerLeadUntilPresentationRetiresFrames() {
      TrackingSession session = new TrackingSession();
      TrackingPresenterOpener presenterOpener = new TrackingPresenterOpener();
      VulkanRendererHost renderer = new VulkanRendererHost(
              RendererPreset.CPU_READBACK.configuration(), session, presenterOpener
      );
      renderer.apply(scene(0L));
      TrackingPresenter presenter = presenterOpener.openedBy(renderer, 2);

      require(renderer.trySubmit(frame(1L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "managed presenter rejected the first producer frame");
      require(renderer.trySubmit(frame(2L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "managed presenter rejected the configured overlap frame");
      for (int attempt = 0; attempt < 10_000; attempt++) {
         RayTracingRenderer.FrameSubmissionAttempt deferred = renderer.trySubmit(frame(3L, 0L));
         require(deferred instanceof RayTracingRenderer.FrameSubmissionDeferred,
                 "sustained producer attempts bypassed the managed presentation bound");
         if (attempt == 0) {
            require(((RayTracingRenderer.FrameSubmissionDeferred) deferred).deferralReason()
                        == SubmissionDeferralReason.PRESENTATION_BACKLOG,
                  "managed presenter backlog lost its stable deferral classification");
            require(deferred == renderer.trySubmit(frame(3L, 0L)),
                    "managed queue backpressure allocated a new result on every hot-loop attempt");
         }
      }
      require(session.frameSubmissions == 2,
              "deferred frames reached the backend and could starve presentation GPU work");
      require(renderer.diagnostics().latestSubmittedFrameSequence() == 2L,
              "deferred submission advanced public frame authority");

      presenter.retire(1L);
      require(renderer.trySubmit(frame(3L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "actual frame retirement did not restore exactly one producer permit");
      require(session.frameSubmissions == 3,
              "retirement did not admit exactly one replacement frame");
      presenter.close();
      renderer.close();
   }

   private static void closingManagedPresenterClearsProducerFlowControl() {
      TrackingSession session = new TrackingSession();
      TrackingPresenterOpener presenterOpener = new TrackingPresenterOpener();
      VulkanRendererHost renderer = new VulkanRendererHost(
              RendererPreset.CPU_READBACK.configuration(), session, presenterOpener
      );
      renderer.apply(scene(0L));
      TrackingPresenter presenter = presenterOpener.openedBy(renderer, 1);

      require(renderer.trySubmit(frame(7L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "single-frame presenter bound rejected its first frame");
      require(renderer.trySubmit(frame(8L, 0L)) instanceof RayTracingRenderer.FrameSubmissionDeferred,
              "single-frame presenter bound did not defer producer lead");
      presenter.close();
      require(renderer.trySubmit(frame(8L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "closed presenter left stale producer flow-control state");
      require(session.frameSubmissions == 2,
              "presenter close admitted an unexpected number of backend frames");
      renderer.close();
   }

   private static void keepsManagedAndExpertFrameConsumersMutuallyExclusive() {
      TrackingSession session = new TrackingSession();
      TrackingPresenterOpener presenterOpener = new TrackingPresenterOpener();
      VulkanRendererHost renderer = new VulkanRendererHost(
            RendererPreset.CPU_READBACK.configuration(), session, presenterOpener
      );
      renderer.apply(scene(0L));
      renderer.submit(frame(1L, 0L));
      session.nextFrame = new TrackingFrameLease(1L, 0L);
      GpuFrameLease expertLease = availableLease(renderer.pollLatestFrame());
      expect(RendererStateException.class, () -> renderer.openPresenter(
            VulkanFramePresenterConfig.builder().build()
      ));
      expertLease.close();

      TrackingPresenter presenter = presenterOpener.openedBy(renderer, 2);
      expect(RendererStateException.class, renderer::pollLatestFrame);
      presenter.close();
      renderer.close();
   }

   private static void honorsBackendManagedPresentationProducerLeadLimit() {
      TrackingSession session = new TrackingSession();
      session.managedPresentationProducerLeadLimit = 1;
      TrackingPresenterOpener presenterOpener = new TrackingPresenterOpener();
      VulkanRendererHost renderer = new VulkanRendererHost(
              RendererPreset.CPU_READBACK.configuration(), session, presenterOpener
      );
      renderer.apply(scene(0L));
      TrackingPresenter presenter = presenterOpener.openedBy(renderer, 4);

      require(renderer.trySubmit(frame(1L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "backend producer-lead limit rejected the first managed frame");
      require(renderer.trySubmit(frame(2L, 0L)) instanceof RayTracingRenderer.FrameSubmissionDeferred,
              "presenter configuration bypassed the stricter backend producer-lead contract");
      require(session.frameSubmissions == 1,
              "backend-limited managed frame reached the native submission lane");
      presenter.retire(1L);
      require(renderer.trySubmit(frame(2L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "retiring the frame did not restore the backend producer permit");
      presenter.close();
      renderer.close();
   }

   private static void publishesBoundedDiagnosticsAndClosesOnce() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      renderer.submit(frame(2L, 0L));
      FrameGenerationEvidence generation = FrameGenerationEvidence.builder()
              .reported(true)
              .requestedGeneratedFramesPerNativeFrame(1)
              .lastSubmittedGeneratedFramesPerNativeFrame(1)
              .configuredGeneratedFramesPerNativeFrame(1)
              .proxyPresentCalls(2L)
              .stateSamples(1L)
              .stateQueryCalls(2L)
              .totalFramesActuallyPresented(2L)
              .generatedFramesActuallyPresented(1L)
              .lastFramesActuallyPresented(2)
              .maximumSupportedGeneratedFramesPerNativeFrame(1)
              .maximumGeneratedFramesObservedPerSample(1)
              .latestNativeStatus(OptionalInt.of(0))
              .proxyPresentSequenceRange(1L, 2L)
              .lastGeneratedObservationSequence(2L)
              .resetEpoch(1L)
              .build();
      session.telemetry = new VulkanRenderingSession.Telemetry(
              2L,
              FrameGpuTiming.builder().enabled(true).completedSamples(1L)
                      .averageTraceNanos(400L).averagePostTraceNanos(100L)
                      .averageTotalNanos(500L).maxTotalNanos(500L).build(),
              generation
      );
      RendererDiagnostics diagnostics = renderer.diagnostics();
      require(diagnostics.latestCompletedFrameSequence() == 2L, "completed frame telemetry changed");
      require(diagnostics.residentMeshes() == 1L && diagnostics.residentInstances() == 1L, "diagnostics leaked incorrect resident counts");
      require(diagnostics.frameGenerationEvidence().equals(generation),
              "host lost typed frame-generation session telemetry");
      require(renderer.pollLatestFrame() == FrameNotReady.INSTANCE, "host fabricated a GPU frame lease");
      renderer.close();
      renderer.close();
      require(renderer.status() == Status.CLOSED, "close did not publish CLOSED");
      require(session.closes == 1, "session was not closed exactly once");
      expect(RendererStateException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
   }

   private static void retriesFailedSessionClose() {
      TrackingSession session = new TrackingSession();
      session.closeFailuresRemaining = 1;
      VulkanRendererHost renderer = renderer(session);
      Objects.requireNonNull(renderer);
      expect(IllegalStateException.class, renderer::close);
      require(renderer.status() == Status.CLOSED, "failed cleanup did not stop new renderer work");
      require(session.closes == 1 && session.state == State.READY, "failed session close was incorrectly published as complete");
      renderer.close();
      renderer.close();
      require(session.closes == 2 && session.state == State.CLOSED, "session cleanup was not retried exactly once after failure");
   }

   private static void publishesCpuFramesWithoutNativeInterop() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      renderer.submit(frame(2L, 0L));
      session.nextCpuFrame = CpuFrame.builder().frameSequence(2L).renderedSceneRevision(0L).extent(1, 1).pixelsRgba8(new byte[]{1, 2, 3, 4}).build();
      CpuFrame frame = (CpuFrame)renderer.pollLatestCpuFrame().orElseThrow();
      require(frame.frameSequence() == 2L && frame.pixelsRgba8().get(0) == 1, "host changed the managed CPU frame");
      require(renderer.pollLatestCpuFrame().isEmpty(), "host returned the same CPU frame twice");
      require(session.cpuFramePolls == 2, "host did not delegate CPU frame polling exactly once per call");
      renderer.close();
   }

   private static void backendContractViolationFailsPermanently() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      session.returnWrongFrameAdmission = true;
      expect(RendererStateException.class, () -> renderer.submit(frame(3L, 0L)));
      require(renderer.status() == Status.FAILED, "mismatched backend admission did not fail renderer");
      require(session.closes == 1, "failed backend contract did not close session");
      renderer.close();
      require(session.closes == 1, "close repeated cleanup after contract failure");
   }

   private static void defersSessionCloseUntilFrameLeaseCompletion() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      renderer.submit(frame(2L, 0L));
      session.nextFrame = new TrackingFrameLease(2L, 0L);
      GpuFrameLease lease = availableLease(renderer.pollLatestFrame());
      require(lease.memoryHandle().markImported(), "memory handle import was not recorded");
      var closeCompletion = renderer.closeAsync().toCompletableFuture();
      require(session.closes == 0, "renderer destroyed a session with consumer-owned GPU work");
      require(!closeCompletion.isDone(), "closeAsync completed before external GPU ownership retired");
      try {
         require(!renderer.awaitClosed(java.time.Duration.ZERO),
               "zero-timeout close wait fabricated native cleanup completion");
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new AssertionError("close wait was unexpectedly interrupted", interrupted);
      }
      require(renderer.health().obligations().outstandingGpuFrameLeases() == 1 && renderer.health().obligations().nativeCleanupPending(), "deferred close debt was not observable");
      Objects.requireNonNull(lease);
      expect(IllegalStateException.class, lease::close);
      require(session.closes == 0, "failed lease close destroyed the Vulkan session");
      lease.release(new GpuFrameLease.CpuCompleted());
      lease.close();
      require(session.closes == 1, "last completed frame lease did not release the deferred session");
      require(closeCompletion.isDone() && !closeCompletion.isCompletedExceptionally(),
            "closeAsync did not complete after the last external lease retired");
      require(lease.state() == LeaseState.CLOSED, "completed frame lease did not close");
      renderer.close();
      require(session.closes == 1, "deferred session close was not idempotent");
   }

   private static void retriesDeferredSessionCloseWithoutRetiringLeaseTwice() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      renderer.apply(scene(0L));
      renderer.submit(frame(2L, 0L));
      session.nextFrame = new TrackingFrameLease(2L, 0L);
      GpuFrameLease lease = availableLease(renderer.pollLatestFrame());
      renderer.close();
      session.closeFailuresRemaining = 1;
      Objects.requireNonNull(lease);
      expect(IllegalStateException.class, lease::close);
      require(session.closes == 1, "deferred session close failure was not attempted");
      require(((RendererHealth.Failure)renderer.health().activeFailure().orElseThrow()).kind() == Kind.RESOURCE_CLEANUP_FAILURE, "deferred session close failure was not typed");
      lease.close();
      require(session.closes == 2 && lease.state() == LeaseState.CLOSED, "session close retry either leaked or retired the same lease twice");
      require(renderer.health().activeFailure().isEmpty() && !renderer.health().obligations().nativeCleanupPending(), "successful cleanup retry retained stale failure or debt");
      renderer.close();
      require(session.closes == 2, "successful deferred close was not idempotent");
   }

   private static void backendFailureClosesResourcesExactlyOnce() {
      TrackingSession session = new TrackingSession();
      VulkanRendererHost renderer = renderer(session);
      session.failNextScene = true;
      expect(RendererStateException.class, () -> renderer.apply(scene(0L)));
      require(renderer.status() == Status.FAILED, "backend failure stayed READY");
      require(((RendererHealth.Failure)renderer.health().activeFailure().orElseThrow()).kind() == Kind.BACKEND_FAILURE, "backend failure was not classified for policy");
      require(renderer.diagnostics().latestAcceptedSceneRevision() == 0L, "backend failure published scene state");
      require(session.closes == 1, "backend failure did not close resources exactly once");
      var closeCompletion = renderer.closeAsync().toCompletableFuture();
      require(closeCompletion.isDone() && !closeCompletion.isCompletedExceptionally(),
            "closeAsync remained pending after failure cleanup had already released the session");
      require(session.closes == 1, "explicit close duplicated failed-session cleanup");
   }

   private static void outOfMemoryAndDriverFailuresRemainTypedAndDoNotAutoRecover() {
      requireTerminalDeviceFailure(
              Reason.DEVICE_OUT_OF_MEMORY,
              RecoveryAction.REDUCE_MEMORY_AND_RECREATE,
              Kind.DEVICE_OUT_OF_MEMORY,
              -2
      );
      requireTerminalDeviceFailure(
              Reason.HOST_OUT_OF_MEMORY,
              RecoveryAction.ABORT,
              Kind.HOST_OUT_OF_MEMORY,
              -1
      );
      requireTerminalDeviceFailure(
              Reason.DRIVER_FAILURE,
              RecoveryAction.ABORT,
              Kind.DRIVER_FAILURE,
              -3
      );
   }

   private static void requireTerminalDeviceFailure(
           Reason reason,
           RecoveryAction recoveryAction,
           Kind healthKind,
           int nativeResult
   ) {
      TrackingSession session = new TrackingSession();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(
              RendererPreset.CPU_READBACK.configuration(),
              () -> {
                 opens[0]++;
                 return session;
              }
      );
      session.failNextFrameWith = new RendererDeviceException(
              "synthetic " + reason,
              reason,
              recoveryAction,
              "syntheticFrameSubmit",
              nativeResult
      );
      RendererDeviceException failure = (RendererDeviceException) expect(
              RendererDeviceException.class,
              () -> renderer.submit(frame(0L, 0L))
      );
      require(failure.reason() == reason && failure.recoveryAction() == recoveryAction,
              "device failure lost its public reason or recovery guidance: " + reason);
      require(renderer.status() == Status.FAILED && opens[0] == 1,
              "non-device-loss failure incorrectly attempted automatic device recreation: " + reason);
      RendererHealth.Failure health = (RendererHealth.Failure) renderer.health()
              .activeFailure().orElseThrow();
      require(health.kind() == healthKind && health.recoveryAction() == recoveryAction
                      && health.nativeResult().orElseThrow() == nativeResult,
              "renderer health lost typed native failure evidence: " + reason);
      require(session.closes == 1,
              "terminal native failure did not release its session exactly once: " + reason);
      renderer.close();
      require(session.closes == 1,
              "explicit close duplicated terminal native cleanup: " + reason);
   }

   private static void recreatesDeviceAndReplaysCommittedSceneOnce() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), () -> {
         int value10003 = opens[0];
         int value10000 = opens[0];
         opens[0] = value10003 + 1;
         return value10000 == 0 ? initial : recovered;
      });
      renderer.apply(scene(0L));
      initial.failNextSceneWith = new RendererDeviceException("synthetic device loss", Reason.DEVICE_LOST, RecoveryAction.RECREATE_RENDERER, "syntheticSceneApply", -4);
      RendererDeviceException recoveredFailure = (RendererDeviceException)expect(RendererDeviceException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
      require(recoveredFailure.recoveryAction() == RecoveryAction.RETRY_OPERATION, "completed device recreation still instructed the caller to recreate the renderer");
      require(initial.closes == 1, "lost device session was not closed exactly once");
      require(opens[0] == 2, "device recovery did not open exactly one replacement session");
      require(renderer.status() == Status.READY, "successful device recreation did not restore READY");
      require(renderer.diagnostics().deviceRecovery().generation() == 1L && renderer.diagnostics().deviceRecovery().attempts() == 1L && renderer.diagnostics().deviceRecovery().failures() == 0L, "successful device recreation did not publish bounded generation evidence");
      require(recovered.lastSceneChangeSet != null && recovered.lastSceneChangeSet.reset() && recovered.lastSceneChangeSet.revision() == 0L && recovered.lastSceneChangeSet.materials().statistics().writes() == 1 && recovered.lastSceneChangeSet.meshes().statistics().writes() == 1 && recovered.lastSceneChangeSet.instances().statistics().writes() == 1, "replacement session did not receive an authoritative full-scene replay");
      RayTracingRenderer.SceneUpdateResult retry = renderer.apply(SceneTransaction.empty(1L));
      require(retry.acceptedSceneRevision() == 1L, "failed scene revision could not retry after recovery");
      renderer.close();
      require(recovered.closes == 1, "replacement session leaked during renderer close");
   }

   private static void deviceRecoveryClearsDiscardedPresenterBacklog() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      TrackingPresenterOpener presenterOpener = new TrackingPresenterOpener();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(
              RendererPreset.CPU_READBACK.configuration(),
              () -> opens[0]++ == 0 ? initial : recovered,
              presenterOpener
      );
      renderer.apply(scene(0L));
      TrackingPresenter presenter = presenterOpener.openedBy(renderer, 1);
      require(renderer.trySubmit(frame(1L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "pre-recovery presenter frame was not admitted");
      require(renderer.trySubmit(frame(2L, 0L)) instanceof RayTracingRenderer.FrameSubmissionDeferred,
              "pre-recovery presenter backlog did not reach its bound");

      initial.failNextSceneWith = deviceLost("syntheticPresenterRecovery");
      RendererDeviceException recovery = (RendererDeviceException) expect(
              RendererDeviceException.class,
              () -> renderer.apply(SceneTransaction.empty(1L))
      );
      require(recovery.recoveryAction() == RecoveryAction.RETRY_OPERATION,
              "presenter recovery did not complete device recreation");
      require(renderer.trySubmit(frame(2L, 0L)) instanceof RayTracingRenderer.FrameSubmitted,
              "discarded pre-recovery frames retained stale presenter permits");
      require(recovered.frameSubmissions == 1,
              "post-recovery frame was not submitted exactly once to the replacement session");
      presenter.close();
      renderer.close();
   }

   private static void doesNotRecoverBeforeLostSessionCloses() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      initial.closeFailuresRemaining = 1;
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), () -> {
         int value10003 = opens[0];
         int value10000 = opens[0];
         opens[0] = value10003 + 1;
         return value10000 == 0 ? initial : recovered;
      });
      renderer.apply(scene(0L));
      initial.failNextSceneWith = deviceLost("syntheticCloseFailure");
      expect(RendererDeviceException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
      require(renderer.status() == Status.FAILED, "renderer recovered while the lost session still owned native resources");
      require(opens[0] == 1 && recovered.closes == 0, "replacement device was opened before old-session cleanup completed");
      renderer.close();
      require(initial.closes == 2 && opens[0] == 1, "explicit cleanup either failed to retry or created a replacement after close");
   }

   private static void recoveryBeforeFirstScenePreservesRevisionZero() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), () -> {
         int value10003 = opens[0];
         int value10000 = opens[0];
         opens[0] = value10003 + 1;
         return value10000 == 0 ? initial : recovered;
      });
      initial.failNextFrameWith = deviceLost("syntheticInitialFrameSubmit");
      RendererDeviceException recoveredFailure = (RendererDeviceException)expect(RendererDeviceException.class, () -> renderer.submit(frame(0L, 0L)));
      require(recoveredFailure.recoveryAction() == RecoveryAction.RETRY_OPERATION, "empty-scene device recreation did not publish retry semantics");
      require(recovered.lastSceneChangeSet == null, "empty CPU authority was replayed as an initialized revision");
      require(renderer.apply(scene(0L)).acceptedSceneRevision() == 0L, "empty-scene recovery consumed the caller's first revision");
      renderer.close();
   }

   private static void defersDeviceRecoveryUntilExternalLeaseCompletion() {
      TrackingSession initial = new TrackingSession();
      TrackingSession recovered = new TrackingSession();
      int[] opens = new int[]{0};
      VulkanRendererHost renderer = new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), () -> {
         int value10003 = opens[0];
         int value10000 = opens[0];
         opens[0] = value10003 + 1;
         return value10000 == 0 ? initial : recovered;
      });
      renderer.apply(scene(0L));
      renderer.submit(frame(2L, 0L));
      initial.nextFrame = new TrackingFrameLease(2L, 0L);
      GpuFrameLease lease = availableLease(renderer.pollLatestFrame());
      initial.failNextSceneWith = deviceLost("syntheticLeasedSceneApply");
      RendererDeviceException failure = (RendererDeviceException)expect(RendererDeviceException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
      require(failure.recoveryAction() == RecoveryAction.RECREATE_RENDERER, "deferred recovery claimed completion before external ownership retired");
      require(renderer.status() == Status.RECOVERING, "outstanding external lease did not publish RECOVERING");
      require(((RendererHealth.Failure)renderer.health().activeFailure().orElseThrow()).kind() == Kind.DEVICE_LOST && renderer.health().obligations().outstandingGpuFrameLeases() == 1 && renderer.health().obligations().deviceRecoveryPending(), "deferred device recovery debt was not typed or bounded");
      require(initial.closes == 0 && opens[0] == 1, "renderer destroyed or replaced a device with an outstanding frame lease");
      lease.close();
      require(initial.closes == 1 && opens[0] == 2, "last frame lease did not trigger exactly one deferred device recreation");
      require(renderer.status() == Status.READY, "deferred device recreation did not restore READY");
      require(recovered.lastSceneChangeSet != null && recovered.lastSceneChangeSet.reset(), "deferred device recreation did not replay committed scene authority");
      renderer.close();
   }

   private static RendererDeviceException deviceLost(String operation) {
      return new RendererDeviceException("synthetic device loss", Reason.DEVICE_LOST, RecoveryAction.RECREATE_RENDERER, operation, -4);
   }

   private static VulkanRendererHost renderer(TrackingSession session) {
      return new VulkanRendererHost(RendererPreset.CPU_READBACK.configuration(), session);
   }

   private static SceneTransaction scene(long revision) {
      MaterialAsset material = MaterialAsset.builder(10L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-1).emissive(255, 0.0F).alphaCutoff(0.5F).roughness(1.0F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(false).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MeshAsset mesh = MeshAsset.triangles(20L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, 10L);
      SceneInstance instance = SceneInstance.builder(30L, 20L).build();
      return SceneTransaction.builder(revision).resetScene().upsert(material).upsert(mesh).upsert(instance).build();
   }

   private static RenderFrameRequest frame(long sequence, long minimumSceneRevision) {
      CameraState camera = CameraState.explicitBasis(0.0, 0.0, 0.0).forward(0.0F, 0.0F, -1.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, 1.0F, 0.0F).projectionTangents(1.0F, 1.0F).build();
      return RenderFrameRequest.builder(sequence, 640, 480, camera).minimumSceneRevision(minimumSceneRevision).environment(EnvironmentState.neutral()).build();
   }

   private static GpuFrameLease availableLease(VulkanFrameInterop.FramePollResult result) {
      if (result instanceof VulkanFrameInterop.FrameAvailable available) {
         return available.lease();
      } else {
         throw new AssertionError("expected an available Vulkan frame but poll returned not-ready");
      }
   }

   private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return (T)(type.cast(failure));
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class TrackingSession implements VulkanRenderingSession {
      private VulkanRenderingSession.State state;
      private VulkanRenderingSession.Telemetry telemetry;
      private int sceneSubmissions;
      private int frameSubmissions;
      private int closes;
      private int closeFailuresRemaining;
      private boolean rejectNextScene;
      private boolean rejectNextFrame;
      private boolean failNextScene;
      private RuntimeException failNextSceneWith;
      private RuntimeException failNextFrameWith;
      private boolean returnWrongFrameAdmission;
      private GpuFrameLease nextFrame;
      private CpuFrame nextCpuFrame;
      private int cpuFramePolls;
      private VulkanSceneResidency.SceneChangeSet lastSceneChangeSet;
      private RenderingFeatureCapabilities featureCapabilities =
              RenderingFeatureCapabilities.builder().build();
      private int managedPresentationProducerLeadLimit = Integer.MAX_VALUE;

      private TrackingSession() {
         this.state = State.READY;
         this.telemetry = Telemetry.unavailable();
      }

      public VulkanRenderingSession.State state() {
         return this.state;
      }

      @Override
      public RenderingFeatureCapabilities featureCapabilities() {
         return featureCapabilities;
      }

      @Override
      public int managedPresentationProducerLeadLimit() {
         return managedPresentationProducerLeadLimit;
      }

      public String gpuStableId() {
         return "00000000000000000000000000000000";
      }

      public VulkanRenderingSession.SceneAdmission apply(VulkanRenderingSession.SceneSubmission submission) throws VulkanRenderingSession.SubmissionRejectedException {
         ++this.sceneSubmissions;
         this.lastSceneChangeSet = submission.residentChanges();
         if (this.rejectNextScene) {
            this.rejectNextScene = false;
            throw new VulkanRenderingSession.SubmissionRejectedException("synthetic scene backpressure");
         } else if (this.failNextScene) {
            this.failNextScene = false;
            this.state = State.FAILED;
            throw new IllegalStateException("synthetic device failure");
         } else if (this.failNextSceneWith != null) {
            RuntimeException failure = this.failNextSceneWith;
            this.failNextSceneWith = null;
            this.state = State.FAILED;
            throw failure;
         } else {
            return new VulkanRenderingSession.SceneAdmission(submission.residentChanges().revision());
         }
      }

      public VulkanRenderingSession.FrameAdmission submit(VulkanRenderingSession.FrameSubmission submission) throws VulkanRenderingSession.SubmissionRejectedException {
         ++this.frameSubmissions;
         if (this.failNextFrameWith != null) {
            RuntimeException failure = this.failNextFrameWith;
            this.failNextFrameWith = null;
            if (!(failure instanceof FrameValidationException)) this.state = State.FAILED;
            throw failure;
         } else if (this.rejectNextFrame) {
            this.rejectNextFrame = false;
            throw new VulkanRenderingSession.SubmissionRejectedException("synthetic frame backpressure");
         } else {
            return this.returnWrongFrameAdmission ? new VulkanRenderingSession.FrameAdmission(submission.request().sequence() + 1L, submission.acceptedSceneRevision(), Set.of()) : new VulkanRenderingSession.FrameAdmission(submission.request().sequence(), submission.acceptedSceneRevision(), Set.of());
         }
      }

      public GpuFrameLease acquireLatestFrame() {
         GpuFrameLease acquired = this.nextFrame;
         this.nextFrame = null;
         return acquired;
      }

      public CpuFrame captureLatestCpuFrame(long afterFrameSequence) {
         ++this.cpuFramePolls;
         CpuFrame captured = this.nextCpuFrame;
         if (captured != null && captured.frameSequence() > afterFrameSequence) {
            this.nextCpuFrame = null;
            return captured;
         } else {
            return null;
         }
      }

      public VulkanRenderingSession.Telemetry telemetry() {
         return this.telemetry;
      }

      public void close() {
         ++this.closes;
         if (this.closeFailuresRemaining > 0) {
            --this.closeFailuresRemaining;
            throw new IllegalStateException("synthetic session close failure");
         } else {
            this.state = State.CLOSED;
         }
      }
   }

   private static final class TrackingFrameLease implements GpuFrameLease {
      private final GpuFrameLease.FrameDescriptor descriptor;
      private final TrackingHandle memoryHandle = new TrackingHandle();
      private boolean released;
      private boolean closed;

      private TrackingFrameLease(long sequence, long sceneRevision) {
         this.descriptor = FrameDescriptor.builder().resourceId(1L).frameSequence(sequence).renderedSceneRevision(sceneRevision).extent(640, 480).format(new GpuFrameLease.VulkanFormat(37)).imageType(new GpuFrameLease.VulkanImageType(1)).imageTiling(new GpuFrameLease.VulkanImageTiling(1)).imageUsage(new GpuFrameLease.VulkanImageUsage(16)).imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0)).imageLayout(new GpuFrameLease.VulkanImageLayout(1)).mipLevels(1).arrayLayers(1).sampleCount(new GpuFrameLease.VulkanSampleCount(1)).sharingMode(new GpuFrameLease.VulkanSharingMode(0)).producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(0)).memoryTypeIndex(0).allocationSize(1228800L).allocationOffset(0L).dedicatedAllocation(true).build();
      }

      public GpuFrameLease.LeaseState state() {
         if (this.closed) {
            return LeaseState.CLOSED;
         } else {
            return this.released ? LeaseState.RELEASED : LeaseState.ACTIVE;
         }
      }

      public GpuFrameLease.FrameDescriptor descriptor() {
         return this.descriptor;
      }

      public GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> memoryHandle() {
         return this.memoryHandle;
      }

      public Optional<GpuFrameLease.AcquireSignal> acquireSignal() {
         return Optional.empty();
      }

      public GpuFrameLease.ConsumerCompletionCapabilities consumerCompletionCapabilities() {
         return GpuFrameLease.ConsumerCompletionCapabilities.cpuOnly();
      }

      public void release(GpuFrameLease.ConsumerCompletion completion) {
         Objects.requireNonNull(completion, "completion");
         if (!this.released && !this.closed) {
            this.released = true;
         } else {
            throw new IllegalStateException("tracking lease was already consumed");
         }
      }

      public void close() {
         if (!this.closed) {
            if (this.memoryHandle.state() == HandleState.IMPORTED && !this.released) {
               throw new IllegalStateException("consumer GPU completion is still outstanding");
            } else {
               this.memoryHandle.close();
               this.closed = true;
            }
         }
      }
   }

   private static final class TrackingPresenterOpener implements VulkanRendererHost.ManagedPresenterOpener {
      private TrackingPresenter presenter;

      @Override
      public VulkanFramePresenter open(
              VulkanDeviceRuntime runtime,
              String gpuStableId,
              VulkanFramePresenterConfig configuration,
              Supplier<GpuFrameLease> managedFrameSupplier,
              LongConsumer frameRetiredCallback,
              Runnable closeCallback
      ) {
         require(!gpuStableId.isBlank(), "presenter opener received a blank GPU identity");
         require(presenter == null, "test opener unexpectedly created multiple presenters");
         presenter = new TrackingPresenter(configuration, frameRetiredCallback, closeCallback);
         return presenter;
      }

      private TrackingPresenter openedBy(VulkanRendererHost renderer, int maximumFramesQueuedAhead) {
         VulkanFramePresenter opened = renderer.openPresenter(
                 VulkanFramePresenterConfig.builder()
                         .maximumFramesQueuedAhead(maximumFramesQueuedAhead)
                         .build()
         );
         require(opened == presenter && presenter != null,
                 "renderer did not publish the presenter returned by its provider");
         return presenter;
      }
   }

   private static final class TrackingPresenter implements VulkanFramePresenter {
      private final VulkanFramePresenterConfig configuration;
      private final LongConsumer frameRetiredCallback;
      private final Runnable closeCallback;
      private boolean closed;

      private TrackingPresenter(
              VulkanFramePresenterConfig configuration,
              LongConsumer frameRetiredCallback,
              Runnable closeCallback
      ) {
         this.configuration = Objects.requireNonNull(configuration, "configuration");
         this.frameRetiredCallback = Objects.requireNonNull(frameRetiredCallback, "frameRetiredCallback");
         this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
      }

      private void retire(long frameSequence) {
         require(!closed, "closed test presenter retired a frame");
         frameRetiredCallback.accept(frameSequence);
      }

      @Override
      public void pollEvents() {
         require(!closed, "closed test presenter polled events");
      }

      @Override
      public WindowState windowState() {
         return new WindowState(closed, configuration.initialWidth(), configuration.initialHeight());
      }

      @Override
      public SwapchainPresentMode activePresentMode() {
         return SwapchainPresentMode.FIFO;
      }

      @Override
      public PerformanceSnapshot performanceSnapshot() {
         return new PerformanceSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
      }

      @Override
      public void setTitle(String title) {
         require(!closed && title != null && !title.isBlank(), "invalid test presenter title");
      }

      @Override
      public void setOverlayText(String text) {
         Objects.requireNonNull(text, "text");
      }

      @Override
      public Optional<PresentationResult> presentLatestFrame() {
         return Optional.empty();
      }

      @Override
      public PresentationResult presentAndRelease(GpuFrameLease lease) {
         Objects.requireNonNull(lease, "lease");
         throw new UnsupportedOperationException("flow-control test does not consume native frame leases");
      }

      @Override
      public void close() {
         if (closed) return;
         closed = true;
         closeCallback.run();
      }
   }

   private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> {
      private GpuFrameLease.HandleState state;

      private TrackingHandle() {
         this.state = HandleState.EXPORTED;
      }

      public long value() {
         return 1L;
      }

      public GpuFrameLease.VulkanMemoryHandleType handleType() {
         return new GpuFrameLease.VulkanMemoryHandleType(2);
      }

      public GpuFrameLease.ImportDisposition importDisposition() {
         return ImportDisposition.IMPORT_CONSUMES_HANDLE;
      }

      public GpuFrameLease.HandleState state() {
         return this.state;
      }

      public boolean markImported() {
         if (this.state != HandleState.EXPORTED) {
            return false;
         } else {
            this.state = HandleState.IMPORTED;
            return true;
         }
      }

      public void close() {
         this.state = HandleState.CLOSED;
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
