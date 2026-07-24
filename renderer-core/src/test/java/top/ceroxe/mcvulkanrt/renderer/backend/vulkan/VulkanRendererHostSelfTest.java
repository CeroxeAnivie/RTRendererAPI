package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.CameraState;
import top.ceroxe.mcvulkanrt.renderer.api.EnvironmentState;
import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRenderer;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.RendererDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.RendererStateException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneRevisionException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.SubmissionOrderException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** State-machine and rollback gate for the unregistered Vulkan provider host. */
public final class VulkanRendererHostSelfTest {
    private VulkanRendererHostSelfTest() {
    }

    public static void main(String[] args) {
        rejectsAndClosesInvalidInitialSession();
        advancesOnlyAfterBackendAdmission();
        validatesFrameOrderBeforeDispatch();
        publishesBoundedDiagnosticsAndClosesOnce();
        defersSessionCloseUntilFrameLeaseCompletion();
        backendContractViolationFailsPermanently();
        backendFailureClosesResourcesExactlyOnce();
        System.out.println("VulkanRendererHostSelfTest passed");
    }

    private static void rejectsAndClosesInvalidInitialSession() {
        TrackingSession session = new TrackingSession();
        session.state = VulkanRenderingSession.State.FAILED;
        expect(IllegalArgumentException.class, () -> renderer(session));
        require(session.closes == 1, "invalid initial session leaked its resources");
    }

    private static void advancesOnlyAfterBackendAdmission() {
        TrackingSession session = new TrackingSession();
        VulkanRendererHost renderer = renderer(session);
        RayTracingRenderer.SceneUpdateResult initial = renderer.apply(scene(0L));
        require(initial.acceptedSceneRevision() == 0L, "initial scene revision changed");
        require(session.lastSceneChangeSet != null
                        && session.lastSceneChangeSet.materials().statistics().writes() == 1
                        && session.lastSceneChangeSet.meshes().statistics().writes() == 1
                        && session.lastSceneChangeSet.instances().statistics().writes() == 1,
                "host did not submit the sparse GPUScene payload to native admission");

        session.rejectNextScene = true;
        expect(top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException.class,
                () -> renderer.apply(SceneTransaction.empty(1L)));
        require(renderer.status() == RayTracingRenderer.Status.READY, "recoverable scene rejection failed renderer");
        require(renderer.diagnostics().latestAcceptedSceneRevision() == 0L,
                "rejected scene advanced host authority");

        RayTracingRenderer.SceneUpdateResult retried = renderer.apply(SceneTransaction.empty(1L));
        require(retried.acceptedSceneRevision() == 1L, "same revision could not retry after atomic rejection");
        require(session.sceneSubmissions == 3, "scene submissions were not delegated exactly once per attempt");
        require(session.lastSceneChangeSet.baseRevision() == 0L
                        && session.lastSceneChangeSet.revision() == 1L
                        && session.lastSceneChangeSet.totalWrites() == 0
                        && session.lastSceneChangeSet.totalClears() == 0,
                "rejected native admission mutated GPUScene residency before retry");
        renderer.close();
    }

    private static void validatesFrameOrderBeforeDispatch() {
        TrackingSession session = new TrackingSession();
        VulkanRendererHost renderer = renderer(session);
        renderer.apply(scene(0L));

        expect(SceneRevisionException.class, () -> renderer.submit(frame(1L, 1L)));
        require(session.frameSubmissions == 0, "future scene requirement reached backend");

        session.rejectNextFrame = true;
        expect(top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException.class,
                () -> renderer.submit(frame(5L, 0L)));
        require(renderer.diagnostics().latestSubmittedFrameSequence() == -1L,
                "rejected frame advanced submitted sequence");

        RayTracingRenderer.FrameSubmissionResult accepted = renderer.submit(frame(5L, 0L));
        require(accepted.frameSequence() == 5L && accepted.scheduledSceneRevision() == 0L,
                "accepted frame submission changed");
        expect(SubmissionOrderException.class, () -> renderer.submit(frame(5L, 0L)));
        require(session.frameSubmissions == 2, "duplicate frame sequence reached backend");
        renderer.close();
    }

    private static void publishesBoundedDiagnosticsAndClosesOnce() {
        TrackingSession session = new TrackingSession();
        VulkanRendererHost renderer = renderer(session);
        renderer.apply(scene(0L));
        renderer.submit(frame(2L, 0L));
        session.telemetry = new VulkanRenderingSession.Telemetry(
                2L,
                new RendererDiagnostics.FrameGpuTiming(true, 1L, 0L, 0L, 400L, 100L, 500L, 500L)
        );

        RendererDiagnostics diagnostics = renderer.diagnostics();
        require(diagnostics.latestCompletedFrameSequence() == 2L, "completed frame telemetry changed");
        require(diagnostics.residentMeshes() == 1L && diagnostics.residentInstances() == 1L,
                "diagnostics leaked incorrect resident counts");
        require(renderer.acquireLatestFrame() == null, "host fabricated a GPU frame lease");

        renderer.close();
        renderer.close();
        require(renderer.status() == RayTracingRenderer.Status.CLOSED, "close did not publish CLOSED");
        require(session.closes == 1, "session was not closed exactly once");
        expect(RendererStateException.class, () -> renderer.apply(SceneTransaction.empty(1L)));
    }

    private static void backendContractViolationFailsPermanently() {
        TrackingSession session = new TrackingSession();
        VulkanRendererHost renderer = renderer(session);
        renderer.apply(scene(0L));
        session.returnWrongFrameAdmission = true;

        expect(RendererStateException.class, () -> renderer.submit(frame(3L, 0L)));
        require(renderer.status() == RayTracingRenderer.Status.FAILED,
                "mismatched backend admission did not fail renderer");
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

        GpuFrameLease lease = Objects.requireNonNull(renderer.acquireLatestFrame(), "tracked lease");
        require(lease.memoryHandle().markImported(), "memory handle import was not recorded");
        renderer.close();
        require(session.closes == 0, "renderer destroyed a session with consumer-owned GPU work");

        expect(IllegalStateException.class, lease::close);
        require(session.closes == 0, "failed lease close destroyed the Vulkan session");

        lease.release(new GpuFrameLease.CpuCompleted());
        lease.close();
        require(session.closes == 1, "last completed frame lease did not release the deferred session");
        require(lease.closed(), "completed frame lease did not close");
        renderer.close();
        require(session.closes == 1, "deferred session close was not idempotent");
    }

    private static void backendFailureClosesResourcesExactlyOnce() {
        TrackingSession session = new TrackingSession();
        VulkanRendererHost renderer = renderer(session);
        session.failNextScene = true;

        expect(RendererStateException.class, () -> renderer.apply(scene(0L)));
        require(renderer.status() == RayTracingRenderer.Status.FAILED, "backend failure stayed READY");
        require(renderer.diagnostics().latestAcceptedSceneRevision() == 0L,
                "backend failure published scene state");
        require(session.closes == 1, "backend failure did not close resources exactly once");
        renderer.close();
        require(session.closes == 1, "explicit close duplicated failed-session cleanup");
    }

    private static VulkanRendererHost renderer(TrackingSession session) {
        return new VulkanRendererHost(RayTracingRendererConfig.defaults(), session);
    }

    private static SceneTransaction scene(long revision) {
        MaterialAsset material = new MaterialAsset(
                10L, MaterialAsset.BlendMode.OPAQUE, 0xffffffff,
                -1L, -1L, -1L, -1L, 0x000000ff,
                0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 1.5F, false
        );
        MeshAsset mesh = new MeshAsset(
                20L,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{10L}
        );
        SceneInstance instance = new SceneInstance(
                30L, 20L, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true
        );
        return new SceneTransaction(
                revision,
                true,
                new SceneTransaction.Upserts(List.of(), List.of(material), List.of(mesh), List.of(instance), List.of()),
                SceneTransaction.Removals.empty()
        );
    }

    private static RenderFrameRequest frame(long sequence, long minimumSceneRevision) {
        CameraState camera = new CameraState(
                0.0D, 0.0D, 0.0D,
                0.0F, 0.0F, -1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 1.0F
        );
        return new RenderFrameRequest(
                sequence, minimumSceneRevision, 640, 480, camera, EnvironmentState.neutral()
        );
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class TrackingSession implements VulkanRenderingSession {
        private State state = State.READY;
        private Telemetry telemetry = Telemetry.unavailable();
        private int sceneSubmissions;
        private int frameSubmissions;
        private int closes;
        private boolean rejectNextScene;
        private boolean rejectNextFrame;
        private boolean failNextScene;
        private boolean returnWrongFrameAdmission;
        private GpuFrameLease nextFrame;
        private VulkanSceneResidency.SceneChangeSet lastSceneChangeSet;

        @Override
        public State state() {
            return state;
        }

        @Override
        public SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException {
            sceneSubmissions++;
            lastSceneChangeSet = submission.residentChanges();
            if (rejectNextScene) {
                rejectNextScene = false;
                throw new SubmissionRejectedException("synthetic scene backpressure");
            }
            if (failNextScene) {
                failNextScene = false;
                state = State.FAILED;
                throw new IllegalStateException("synthetic device failure");
            }
            return new SceneAdmission(submission.residentChanges().revision());
        }

        @Override
        public FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException {
            frameSubmissions++;
            if (rejectNextFrame) {
                rejectNextFrame = false;
                throw new SubmissionRejectedException("synthetic frame backpressure");
            }
            if (returnWrongFrameAdmission) {
                return new FrameAdmission(submission.request().sequence() + 1L, submission.acceptedSceneRevision());
            }
            return new FrameAdmission(submission.request().sequence(), submission.acceptedSceneRevision());
        }

        @Override
        public GpuFrameLease acquireLatestFrame() {
            GpuFrameLease acquired = nextFrame;
            nextFrame = null;
            return acquired;
        }

        @Override
        public Telemetry telemetry() {
            return telemetry;
        }

        @Override
        public void close() {
            closes++;
            state = State.CLOSED;
        }
    }

    private static final class TrackingFrameLease implements GpuFrameLease {
        private final FrameDescriptor descriptor;
        private final TrackingHandle memoryHandle = new TrackingHandle();
        private boolean released;
        private boolean closed;

        private TrackingFrameLease(long sequence, long sceneRevision) {
            descriptor = new FrameDescriptor(
                    sequence, sceneRevision, 640, 480,
                    37, 1, 1, 0x10, 0, 1,
                    1, 1, 1, 0, 0, 1_228_800L, 0L, true
            );
        }

        @Override
        public FrameDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ExportedNativeHandle memoryHandle() {
            return memoryHandle;
        }

        @Override
        public Optional<AcquireSignal> acquireSignal() {
            return Optional.empty();
        }

        @Override
        public void release(ConsumerCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (released || closed) {
                throw new IllegalStateException("tracking lease was already consumed");
            }
            released = true;
        }

        @Override
        public boolean released() {
            return released;
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (memoryHandle.state() == HandleState.IMPORTED && !released) {
                throw new IllegalStateException("consumer GPU completion is still outstanding");
            }
            memoryHandle.close();
            closed = true;
        }
    }

    private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle {
        private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;

        @Override
        public long value() {
            return 1L;
        }

        @Override
        public int vulkanHandleType() {
            return 2;
        }

        @Override
        public GpuFrameLease.ImportDisposition importDisposition() {
            return GpuFrameLease.ImportDisposition.IMPORT_CONSUMES_HANDLE;
        }

        @Override
        public GpuFrameLease.HandleState state() {
            return state;
        }

        @Override
        public boolean markImported() {
            if (state != GpuFrameLease.HandleState.EXPORTED) {
                return false;
            }
            state = GpuFrameLease.HandleState.IMPORTED;
            return true;
        }

        @Override
        public void close() {
            state = GpuFrameLease.HandleState.CLOSED;
        }
    }
}
