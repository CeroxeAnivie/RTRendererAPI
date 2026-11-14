package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.feature.VulkanAccelerationStructureMemoryOptimizer;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtBottomLevelBuild;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns selection and failure recovery for one generation of asynchronous triangle BLAS builds.
 *
 * <p>The generation owner should not know which provider allocated a BLAS. This collaborator
 * keeps the generic Vulkan fallback transactional: a provider failure first disables that path,
 * then releases every partial vendor build, then submits the complete batch through core. The
 * provider can publish FALLBACK only after the replacement batch is accepted.</p>
 */
final class VulkanBlasBuildCoordinator {
    private final Backend backend;

    VulkanBlasBuildCoordinator(VulkanDeviceRuntime device) {
        this(new RuntimeBackend(device));
    }

    VulkanBlasBuildCoordinator(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    List<PendingBuild> submit(List<Request> requests) {
        List<Request> checked = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (checked.isEmpty()) return List.of();

        Optional<VulkanAccelerationStructureMemoryOptimizer> selected =
                backend.accelerationStructureMemoryOptimizer();
        VulkanAccelerationStructureMemoryOptimizer optimizer = selected.orElse(null);
        try {
            return submitBatch(checked, optimizer);
        } catch (RuntimeException | Error failure) {
            if (optimizer == null) throw failure;
            return recoverAtSubmission(checked, optimizer, failure);
        }
    }

    boolean advance(List<PendingBuild> builds) {
        List<PendingBuild> checked = Objects.requireNonNull(builds, "builds");
        boolean complete = true;
        for (PendingBuild build : checked) {
            if (build.completed != null) continue;
            try {
                RtAccelerationStructure result = build.submission.completeIfReady();
                if (result == null) {
                    complete = false;
                } else {
                    build.completed = result;
                }
            } catch (RuntimeException | Error failure) {
                VulkanAccelerationStructureMemoryOptimizer optimizer = build.optimizer;
                if (optimizer == null) throw failure;
                recoverAfterSubmission(checked, optimizer, failure);
                return false;
            }
        }
        return complete;
    }

    private List<PendingBuild> recoverAtSubmission(
            List<Request> requests,
            VulkanAccelerationStructureMemoryOptimizer optimizer,
            Throwable optimizerFailure
    ) {
        VulkanAccelerationStructureMemoryOptimizer.FailureRecovery recovery = optimizer
                .beginFailureRecovery(optimizerFailure)
                .orElseThrow(() -> rethrow(optimizerFailure));
        final List<PendingBuild> replacements;
        try {
            replacements = submitBatch(requests, null);
        } catch (RuntimeException | Error fallbackFailure) {
            fallbackFailure.addSuppressed(optimizerFailure);
            throw fallbackFailure;
        }
        commitRecovery(recovery, replacements, optimizerFailure);
        return replacements;
    }

    private void recoverAfterSubmission(
            List<PendingBuild> builds,
            VulkanAccelerationStructureMemoryOptimizer optimizer,
            Throwable optimizerFailure
    ) {
        VulkanAccelerationStructureMemoryOptimizer.FailureRecovery recovery = optimizer
                .beginFailureRecovery(optimizerFailure)
                .orElseThrow(() -> rethrow(optimizerFailure));

        Throwable closeFailure = closeAll(builds);
        if (closeFailure != null) {
            closeFailure.addSuppressed(optimizerFailure);
            throw rethrow(closeFailure);
        }

        ArrayList<PendingBuild> replacements = new ArrayList<>(builds.size());
        try {
            for (PendingBuild build : builds) {
                replacements.add(submitCore(build.request));
            }
        } catch (RuntimeException | Error fallbackFailure) {
            Throwable replacementCloseFailure = closeAll(replacements);
            if (replacementCloseFailure != null) fallbackFailure.addSuppressed(replacementCloseFailure);
            fallbackFailure.addSuppressed(optimizerFailure);
            throw fallbackFailure;
        }

        commitRecovery(recovery, replacements, optimizerFailure);
        builds.clear();
        builds.addAll(replacements);
    }

    private List<PendingBuild> submitBatch(
            List<Request> requests,
            VulkanAccelerationStructureMemoryOptimizer optimizer
    ) {
        ArrayList<PendingBuild> submitted = new ArrayList<>(requests.size());
        try {
            for (Request request : requests) {
                RtBottomLevelBuild build = optimizer == null
                        ? backend.submitCore(request.geometry)
                        : backend.submitOptimized(optimizer, request.geometry);
                submitted.add(new PendingBuild(request, optimizer, build));
            }
            return submitted;
        } catch (RuntimeException | Error failure) {
            Throwable closeFailure = closeAll(submitted);
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            throw failure;
        }
    }

    private static void commitRecovery(
            VulkanAccelerationStructureMemoryOptimizer.FailureRecovery recovery,
            List<PendingBuild> replacements,
            Throwable optimizerFailure
    ) {
        try {
            recovery.commitCoreSubmission();
        } catch (RuntimeException | Error commitFailure) {
            Throwable closeFailure = closeAll(replacements);
            if (closeFailure != null) commitFailure.addSuppressed(closeFailure);
            commitFailure.addSuppressed(optimizerFailure);
            throw commitFailure;
        }
    }

    private PendingBuild submitCore(Request request) {
        return new PendingBuild(request, null, backend.submitCore(request.geometry));
    }

    /**
     * Narrow submission boundary used to prove rollback without requiring a live Vulkan device.
     * The production adapter below remains the sole place that translates this policy into the
     * runtime's ordered command lane and the core static builder.
     */
    interface Backend {
        Optional<VulkanAccelerationStructureMemoryOptimizer> accelerationStructureMemoryOptimizer();

        RtBottomLevelBuild submitOptimized(
                VulkanAccelerationStructureMemoryOptimizer optimizer,
                RtDeviceTriangleBlasBuilder.Geometry geometry
        );

        RtBottomLevelBuild submitCore(RtDeviceTriangleBlasBuilder.Geometry geometry);
    }

    private static final class RuntimeBackend implements Backend {
        private final VulkanDeviceRuntime device;

        private RuntimeBackend(VulkanDeviceRuntime device) {
            this.device = Objects.requireNonNull(device, "device");
        }

        @Override
        public Optional<VulkanAccelerationStructureMemoryOptimizer>
        accelerationStructureMemoryOptimizer() {
            return device.featureSession().accelerationStructureMemoryOptimizer();
        }

        @Override
        public RtBottomLevelBuild submitOptimized(
                VulkanAccelerationStructureMemoryOptimizer optimizer,
                RtDeviceTriangleBlasBuilder.Geometry geometry
        ) {
            return Objects.requireNonNull(optimizer, "optimizer").submitTriangleBlas(
                    device.buildCommands(), List.of(geometry)
            );
        }

        @Override
        public RtBottomLevelBuild submitCore(RtDeviceTriangleBlasBuilder.Geometry geometry) {
            return RtDeviceTriangleBlasBuilder.submit(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment(),
                    List.of(geometry)
            );
        }
    }

    private static Throwable closeAll(List<PendingBuild> builds) {
        Throwable failure = null;
        for (PendingBuild build : builds) {
            try {
                build.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) return runtimeFailure;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("acceleration optimizer failed", failure);
    }

    record Request(int meshSlot, RtDeviceTriangleBlasBuilder.Geometry geometry) {
        Request {
            if (meshSlot < 0) throw new IllegalArgumentException("meshSlot must not be negative");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    static final class PendingBuild implements AutoCloseable {
        private final Request request;
        private final VulkanAccelerationStructureMemoryOptimizer optimizer;
        private RtBottomLevelBuild submission;
        private RtAccelerationStructure completed;

        private PendingBuild(
                Request request,
                VulkanAccelerationStructureMemoryOptimizer optimizer,
                RtBottomLevelBuild submission
        ) {
            this.request = Objects.requireNonNull(request, "request");
            this.optimizer = optimizer;
            this.submission = Objects.requireNonNull(submission, "submission");
        }

        int meshSlot() {
            return request.meshSlot;
        }

        RtAccelerationStructure completed() {
            return completed;
        }

        @Override
        public void close() {
            Throwable failure = null;
            try {
                submission.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = closeFailure;
            }
            try {
                if (completed != null) completed.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            completed = null;
            if (failure != null) throw rethrow(failure);
        }
    }
}
