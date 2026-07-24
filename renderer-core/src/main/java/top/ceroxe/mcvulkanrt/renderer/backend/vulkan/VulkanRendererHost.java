package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRenderer;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.api.RendererDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.RendererStateException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneRevisionException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.SubmissionOrderException;

import java.util.Objects;

/**
 * Provider-side lifecycle and ordering authority for one Vulkan renderer instance.
 *
 * <p>This class deliberately has package visibility: hosts can only obtain the public
 * {@link RayTracingRenderer} contract from a fully wired provider. Until the generic mesh and
 * material adapter supplies a real {@link VulkanRenderingSession}, no service registration can
 * accidentally expose a renderer that acknowledges work without dispatching it.</p>
 */
final class VulkanRendererHost implements RayTracingRenderer {
    private final RayTracingRendererConfig configuration;
    private final VulkanRenderingSession session;
    private final PersistentSceneRegistry scene = new PersistentSceneRegistry();
    private final VulkanSceneResidency residency = new VulkanSceneResidency();

    private Lifecycle lifecycle = Lifecycle.READY;
    private long latestSubmittedFrameSequence = -1L;
    private long latestCompletedFrameSequence = -1L;
    private long latestAcquiredFrameSequence = -1L;
    private RendererDiagnostics.FrameGpuTiming latestGpuTiming = RendererDiagnostics.FrameGpuTiming.unavailable();
    private Throwable terminalFailure;
    private boolean sessionClosed;
    private int openFrameLeases;

    VulkanRendererHost(RayTracingRendererConfig configuration, VulkanRenderingSession session) {
        VulkanRenderingSession ownedSession = Objects.requireNonNull(session, "session");
        RayTracingRendererConfig checkedConfiguration;
        try {
            checkedConfiguration = Objects.requireNonNull(configuration, "configuration");
            VulkanRenderingSession.State initialState = Objects.requireNonNull(
                    ownedSession.state(),
                    "session state"
            );
            if (initialState != VulkanRenderingSession.State.READY) {
                throw new IllegalArgumentException("Vulkan rendering session must be READY: state=" + initialState);
            }
        } catch (RuntimeException initializationFailure) {
            try {
                ownedSession.close();
            } catch (RuntimeException closeFailure) {
                initializationFailure.addSuppressed(closeFailure);
            }
            throw initializationFailure;
        }
        this.configuration = checkedConfiguration;
        this.session = ownedSession;
    }

    @Override
    public synchronized Status status() {
        observeSessionState();
        return publicStatus();
    }

    @Override
    public synchronized SceneUpdateResult apply(SceneTransaction transaction) {
        requireReady("apply scene transaction");
        PersistentSceneRegistry.PreparedMutation prepared = scene.prepare(transaction);
        VulkanSceneResidency.PreparedUpdate preparedResidency;
        try {
            preparedResidency = residency.prepare(prepared.transaction());
            requireEquivalentPreparedStates(prepared.prospectiveState(), preparedResidency.changeSet());
        } catch (RuntimeException failure) {
            throw fail("prepare Vulkan scene residency", failure);
        }
        VulkanRenderingSession.SceneAdmission admission;
        try {
            admission = Objects.requireNonNull(
                    session.apply(new VulkanRenderingSession.SceneSubmission(
                            preparedResidency.changeSet(),
                            prepared.prospectiveState()
                    )),
                    "session scene admission"
            );
        } catch (VulkanRenderingSession.SubmissionRejectedException rejection) {
            throw rejected("scene transaction " + prepared.transaction().revision(), rejection);
        } catch (RuntimeException failure) {
            throw fail("apply scene transaction", failure);
        }

        try {
            if (admission.acceptedSceneRevision() != preparedResidency.revision()) {
                throw new IllegalStateException(
                        "session admitted a different scene revision: submitted="
                                + preparedResidency.revision()
                                + ", admitted=" + admission.acceptedSceneRevision()
                );
            }
            // Validate every authority before publication. Neither commit below can reject after
            // this point, which keeps host-visible semantic state and GPU slot ownership aligned.
            scene.validate(prepared);
            residency.validate(preparedResidency);
            PersistentSceneRegistry.SceneState committed = scene.commitValidated(prepared);
            VulkanSceneResidency.SceneResidencyState resident = residency.commitValidated(preparedResidency);
            requireEquivalentCommittedStates(committed, resident);
            return new SceneUpdateResult(committed.revision());
        } catch (RuntimeException invariantFailure) {
            throw fail("publish admitted scene transaction", invariantFailure);
        }
    }

    @Override
    public synchronized FrameSubmissionResult submit(RenderFrameRequest request) {
        requireReady("submit frame");
        RenderFrameRequest checked = Objects.requireNonNull(request, "request");
        if (checked.sequence() <= latestSubmittedFrameSequence) {
            throw new SubmissionOrderException(
                    "frame sequence must advance: current=" + latestSubmittedFrameSequence
                            + ", submitted=" + checked.sequence()
            );
        }

        long acceptedSceneRevision = scene.state().revision();
        if (checked.minimumSceneRevision() > acceptedSceneRevision) {
            throw new SceneRevisionException(
                    "frame requires scene revision " + checked.minimumSceneRevision()
                            + " but renderer has accepted only " + acceptedSceneRevision
            );
        }

        VulkanRenderingSession.FrameAdmission admission;
        try {
            admission = Objects.requireNonNull(
                    session.submit(new VulkanRenderingSession.FrameSubmission(checked, acceptedSceneRevision)),
                    "session frame admission"
            );
        } catch (VulkanRenderingSession.SubmissionRejectedException rejection) {
            throw rejected("frame " + checked.sequence(), rejection);
        } catch (RuntimeException failure) {
            throw fail("submit frame", failure);
        }

        if (admission.frameSequence() != checked.sequence()
                || admission.sceneRevision() != acceptedSceneRevision) {
            throw fail(
                    "verify frame admission",
                    new IllegalStateException(
                            "session admitted a different frame: requested=" + checked.sequence()
                                    + "/" + acceptedSceneRevision
                                    + ", admitted=" + admission.frameSequence()
                                    + "/" + admission.sceneRevision()
                    )
            );
        }
        latestSubmittedFrameSequence = checked.sequence();
        return new FrameSubmissionResult(admission.frameSequence(), admission.sceneRevision());
    }

    @Override
    public synchronized GpuFrameLease acquireLatestFrame() {
        requireReady("acquire latest GPU frame");
        GpuFrameLease lease;
        try {
            lease = session.acquireLatestFrame();
        } catch (RuntimeException failure) {
            throw fail("acquire latest GPU frame", failure);
        }
        if (lease == null) {
            return null;
        }

        try {
            GpuFrameLease.FrameDescriptor descriptor = Objects.requireNonNull(
                    lease.descriptor(), "lease descriptor"
            );
            if (lease.closed() || lease.released()) {
                throw new IllegalStateException("session returned a consumed GPU frame lease");
            }
            if (lease.memoryHandle().state() != GpuFrameLease.HandleState.EXPORTED) {
                throw new IllegalStateException("session returned a non-exported memory handle");
            }
            if (lease.acquireSignal().isPresent()
                    && lease.acquireSignal().orElseThrow().handle().state()
                    != GpuFrameLease.HandleState.EXPORTED) {
                throw new IllegalStateException("session returned a non-exported acquire semaphore handle");
            }
            if (descriptor.frameSequence() <= latestAcquiredFrameSequence) {
                throw new IllegalStateException(
                        "session returned a non-new frame: latest=" + latestAcquiredFrameSequence
                                + ", returned=" + descriptor.frameSequence()
                );
            }
            if (descriptor.frameSequence() > latestSubmittedFrameSequence) {
                throw new IllegalStateException(
                        "session returned an unsubmitted frame " + descriptor.frameSequence()
                );
            }
            long acceptedSceneRevision = scene.state().revision();
            if (descriptor.renderedSceneRevision() > acceptedSceneRevision) {
                throw new IllegalStateException(
                        "session returned frame for unaccepted scene revision "
                                + descriptor.renderedSceneRevision()
                );
            }
            latestAcquiredFrameSequence = descriptor.frameSequence();
            latestCompletedFrameSequence = Math.max(latestCompletedFrameSequence, descriptor.frameSequence());
            openFrameLeases++;
            return new TrackedGpuFrameLease(lease, this::onFrameLeaseClosed);
        } catch (RuntimeException contractFailure) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                contractFailure.addSuppressed(closeFailure);
            }
            throw fail("validate acquired GPU frame", contractFailure);
        }
    }

    @Override
    public synchronized RendererDiagnostics diagnostics() {
        observeSessionState();
        if (lifecycle == Lifecycle.READY) {
            refreshTelemetry();
        }
        PersistentSceneRegistry.SceneState sceneState = scene.state();
        return new RendererDiagnostics(
                publicStatus(),
                sceneState.revision(),
                latestSubmittedFrameSequence,
                latestCompletedFrameSequence,
                sceneState.meshes(),
                sceneState.instances(),
                latestGpuTiming
        );
    }

    @Override
    public synchronized void close() {
        if (lifecycle == Lifecycle.CLOSED) {
            return;
        }
        lifecycle = Lifecycle.CLOSED;
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            if (terminalFailure != null) {
                terminalFailure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    synchronized Throwable terminalFailure() {
        return terminalFailure;
    }

    private void refreshTelemetry() {
        VulkanRenderingSession.Telemetry telemetry;
        try {
            telemetry = Objects.requireNonNull(session.telemetry(), "session telemetry");
            if (telemetry.latestCompletedFrameSequence() < latestCompletedFrameSequence) {
                throw new IllegalStateException(
                        "completed frame telemetry regressed from " + latestCompletedFrameSequence
                                + " to " + telemetry.latestCompletedFrameSequence()
                );
            }
            if (telemetry.latestCompletedFrameSequence() > latestSubmittedFrameSequence) {
                throw new IllegalStateException(
                        "completed frame telemetry exceeds submitted sequence: completed="
                                + telemetry.latestCompletedFrameSequence()
                                + ", submitted=" + latestSubmittedFrameSequence
                );
            }
            if (!configuration.gpuTimingsEnabled() && telemetry.frameGpuTiming().enabled()) {
                throw new IllegalStateException("session published GPU timings while configuration disabled them");
            }
            latestCompletedFrameSequence = telemetry.latestCompletedFrameSequence();
            latestGpuTiming = telemetry.frameGpuTiming();
        } catch (RuntimeException failure) {
            transitionToFailed("read renderer telemetry", failure);
        }
    }

    private void requireReady(String operation) {
        observeSessionState();
        if (lifecycle != Lifecycle.READY) {
            throw new RendererStateException(
                    "renderer is not ready for " + operation + ": status=" + publicStatus(),
                    publicStatus(),
                    terminalFailure
            );
        }
    }

    private void observeSessionState() {
        if (lifecycle != Lifecycle.READY) {
            return;
        }
        VulkanRenderingSession.State state;
        try {
            state = Objects.requireNonNull(session.state(), "session state");
        } catch (RuntimeException failure) {
            transitionToFailed("observe Vulkan session state", failure);
            return;
        }
        if (state != VulkanRenderingSession.State.READY) {
            transitionToFailed(
                    "observe Vulkan session state",
                    new IllegalStateException("Vulkan session left READY state: " + state)
            );
        }
    }

    private top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException rejected(
            String submission,
            VulkanRenderingSession.SubmissionRejectedException rejection
    ) {
        return new top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException(
                "Vulkan renderer rejected " + submission + " without publishing partial state: "
                        + rejection.getMessage(),
                rejection
        );
    }

    private static void requireEquivalentPreparedStates(
            PersistentSceneRegistry.SceneState authority,
            VulkanSceneResidency.SceneChangeSet residentChanges
    ) {
        if (authority.revision() != residentChanges.revision()
                || authority.textures() != residentChanges.textures().statistics().liveSlots()
                || authority.materials() != residentChanges.materials().statistics().liveSlots()
                || authority.meshes() != residentChanges.meshes().statistics().liveSlots()
                || authority.instances() != residentChanges.instances().statistics().liveSlots()
                || authority.lights() != residentChanges.lights().statistics().liveSlots()) {
            throw new IllegalStateException("CPU scene authority and GPUScene prepared residency diverged");
        }
    }

    private static void requireEquivalentCommittedStates(
            PersistentSceneRegistry.SceneState authority,
            VulkanSceneResidency.SceneResidencyState resident
    ) {
        if (authority.revision() != resident.revision()
                || authority.textures() != resident.textures().liveSlots()
                || authority.materials() != resident.materials().liveSlots()
                || authority.meshes() != resident.meshes().liveSlots()
                || authority.instances() != resident.instances().liveSlots()
                || authority.lights() != resident.lights().liveSlots()) {
            throw new IllegalStateException("CPU scene authority and committed GPUScene residency diverged");
        }
    }

    private RendererStateException fail(String operation, RuntimeException failure) {
        transitionToFailed(operation, failure);
        return new RendererStateException(
                "Vulkan renderer failed during " + operation,
                Status.FAILED,
                failure
        );
    }

    private void transitionToFailed(String operation, RuntimeException failure) {
        if (lifecycle != Lifecycle.READY) {
            return;
        }
        lifecycle = Lifecycle.FAILED;
        terminalFailure = new IllegalStateException("Vulkan renderer failed during " + operation, failure);
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            terminalFailure.addSuppressed(closeFailure);
        }
    }

    private synchronized void onFrameLeaseClosed() {
        if (openFrameLeases <= 0) {
            throw new IllegalStateException("GPU frame lease tracking underflow");
        }
        openFrameLeases--;
        if (lifecycle == Lifecycle.READY) {
            return;
        }
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            if (terminalFailure != null) {
                terminalFailure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private RuntimeException closeSessionIfUnleased() {
        if (sessionClosed || openFrameLeases != 0) {
            return null;
        }
        sessionClosed = true;
        try {
            session.close();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private Status publicStatus() {
        return switch (lifecycle) {
            case READY -> Status.READY;
            case FAILED -> Status.FAILED;
            case CLOSED -> Status.CLOSED;
        };
    }

    private enum Lifecycle {
        READY,
        FAILED,
        CLOSED
    }
}
