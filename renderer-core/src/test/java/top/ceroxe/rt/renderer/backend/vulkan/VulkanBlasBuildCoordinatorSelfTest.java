package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.feature.VulkanAccelerationStructureMemoryOptimizer;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtBottomLevelBuild;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Deterministic ownership and publication checks for RTXMU-to-core BLAS recovery. */
public final class VulkanBlasBuildCoordinatorSelfTest {
    private VulkanBlasBuildCoordinatorSelfTest() {
    }

    public static void main(String[] args) {
        nthVendorSubmissionFailureClosesEarlierBuilds();
        partialCompletionClosesCompletedAndInFlightBuilds();
        requiredFailureNeverFallsBack();
        terminalDeviceAndMemoryFailuresNeverFallBack();
        coreRetryFailureClosesEveryAcceptedReplacement();
        fallbackPublishesOnlyAfterEveryReplacementIsAccepted();
        fallbackCommitFailureClosesAcceptedReplacements();
        System.out.println("VulkanBlasBuildCoordinatorSelfTest passed");
    }

    private static void nthVendorSubmissionFailureClosesEarlierBuilds() {
        IllegalStateException vendorFailure = new IllegalStateException("injected vendor submit 3 failure");
        FakeOptimizer optimizer = new FakeOptimizer(failure -> false, new ArrayList<>());
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        backend.vendorFailureAt = 3;
        backend.vendorFailure = vendorFailure;

        Throwable observed = expectFailure(
                () -> new VulkanBlasBuildCoordinator(backend).submit(requests(4))
        );

        require(observed == vendorFailure, "the original strict vendor failure must escape");
        require(backend.vendorBuilds.size() == 2, "exactly two vendor builds must precede failure 3");
        requireClosedExactlyOnce(backend.vendorBuilds, "earlier vendor submission");
        require(backend.coreAttempts == 0, "strict recovery must not submit core replacements");
    }

    private static void partialCompletionClosesCompletedAndInFlightBuilds() {
        IllegalStateException completionFailure =
                new IllegalStateException("injected vendor completion failure");
        FakeOptimizer optimizer = new FakeOptimizer(failure -> true, new ArrayList<>());
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        VulkanBlasBuildCoordinator coordinator = new VulkanBlasBuildCoordinator(backend);
        List<VulkanBlasBuildCoordinator.PendingBuild> pending = coordinator.submit(requests(3));
        AtomicInteger completedRelease = new AtomicInteger();
        backend.vendorBuilds.get(0).completion = externalBlas(completedRelease);
        // Build 2 intentionally remains in flight while build 3 reports the provider failure.
        backend.vendorBuilds.get(2).completionFailure = completionFailure;

        try {
            require(!coordinator.advance(pending), "recovered core builds require a later completion poll");
            requireClosedExactlyOnce(backend.vendorBuilds, "failed vendor generation");
            require(completedRelease.get() == 1,
                    "a completed vendor BLAS must close with its failed generation");
            require(backend.coreBuilds.size() == 3,
                    "recovery must rebuild the complete generation through core");
            require(optimizer.commits == 1 && optimizer.state == RecoveryState.FALLBACK,
                    "accepted replacements must publish fallback exactly once");
        } finally {
            closeAll(pending);
        }
    }

    private static void requiredFailureNeverFallsBack() {
        // REQUIRED/terminal classification belongs to the provider. The coordinator's contract
        // is to treat an empty recovery token as strict and preserve the original failure.
        IllegalStateException requiredFailure = new IllegalStateException("required RTXMU failed");
        FakeOptimizer optimizer = new FakeOptimizer(failure -> false, new ArrayList<>());
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        backend.vendorFailureAt = 1;
        backend.vendorFailure = requiredFailure;

        Throwable observed = expectFailure(
                () -> new VulkanBlasBuildCoordinator(backend).submit(requests(2))
        );

        require(observed == requiredFailure, "REQUIRED must preserve the provider failure identity");
        require(optimizer.recoveryAttempts == 1 && optimizer.commits == 0,
                "REQUIRED may classify failure but must never commit fallback");
        require(backend.coreAttempts == 0, "REQUIRED must never enter the core replacement path");
        require(optimizer.state == RecoveryState.AVAILABLE,
                "a refused recovery token must not publish a fallback state");
    }

    private static void terminalDeviceAndMemoryFailuresNeverFallBack() {
        RendererDeviceException deviceLost = new RendererDeviceException(
                "device lost",
                RendererDeviceException.Reason.DEVICE_LOST,
                RendererDeviceException.RecoveryAction.RECREATE_RENDERER,
                "vkQueueSubmit",
                -4
        );
        assertTerminalFailureDoesNotFallback(deviceLost);
        assertTerminalFailureDoesNotFallback(new OutOfMemoryError("injected host OOM"));
    }

    private static void assertTerminalFailureDoesNotFallback(Throwable terminalFailure) {
        Predicate<Throwable> preferredPolicy = failure ->
                !(failure instanceof RendererDeviceException) && !(failure instanceof OutOfMemoryError);
        FakeOptimizer optimizer = new FakeOptimizer(preferredPolicy, new ArrayList<>());
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        backend.vendorFailureAt = 1;
        backend.vendorFailure = terminalFailure;

        Throwable observed = expectFailure(
                () -> new VulkanBlasBuildCoordinator(backend).submit(requests(1))
        );

        require(observed == terminalFailure, "terminal failures must escape without translation");
        require(backend.coreAttempts == 0 && optimizer.commits == 0,
                "device/OOM failures must never reach or publish core fallback");
        require(optimizer.state == RecoveryState.AVAILABLE,
                "terminal failure classification must not claim recovery execution");
    }

    private static void coreRetryFailureClosesEveryAcceptedReplacement() {
        IllegalStateException vendorFailure = new IllegalStateException("injected vendor failure");
        IllegalStateException coreFailure = new IllegalStateException("injected core submit 3 failure");
        FakeOptimizer optimizer = new FakeOptimizer(failure -> true, new ArrayList<>());
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        backend.vendorFailureAt = 2;
        backend.vendorFailure = vendorFailure;
        backend.coreFailureAt = 3;
        backend.coreFailure = coreFailure;

        Throwable observed = expectFailure(
                () -> new VulkanBlasBuildCoordinator(backend).submit(requests(4))
        );

        require(observed == coreFailure, "the replacement submission failure must escape");
        require(containsIdentity(observed.getSuppressed(), vendorFailure),
                "the replacement failure must retain the original vendor cause");
        requireClosedExactlyOnce(backend.vendorBuilds, "abandoned vendor submission");
        require(backend.coreBuilds.size() == 2, "two core builds must precede core failure 3");
        requireClosedExactlyOnce(backend.coreBuilds, "partial core replacement batch");
        require(optimizer.commits == 0 && optimizer.state == RecoveryState.RECOVERING,
                "failed replacement submission must remain pending and never claim FALLBACK");
    }

    private static void fallbackPublishesOnlyAfterEveryReplacementIsAccepted() {
        ArrayList<String> events = new ArrayList<>();
        FakeOptimizer optimizer = new FakeOptimizer(failure -> true, events);
        FakeBackend backend = new FakeBackend(optimizer, events);
        backend.vendorFailureAt = 2;
        backend.vendorFailure = new IllegalStateException("injected preferred vendor failure");
        List<VulkanBlasBuildCoordinator.PendingBuild> replacements =
                new VulkanBlasBuildCoordinator(backend).submit(requests(3));

        try {
            require(events.equals(List.of(
                            "vendor:1", "vendor:2", "recovering",
                            "core:1", "core:2", "core:3", "fallback"
                    )),
                    "fallback publication must follow acceptance of the complete replacement batch: " + events);
            require(optimizer.commits == 1 && optimizer.state == RecoveryState.FALLBACK,
                    "complete replacement acceptance must publish exactly one fallback");
        } finally {
            closeAll(replacements);
        }
    }

    private static void fallbackCommitFailureClosesAcceptedReplacements() {
        IllegalStateException vendorFailure = new IllegalStateException("injected preferred vendor failure");
        IllegalStateException commitFailure = new IllegalStateException("injected fallback commit failure");
        FakeOptimizer optimizer = new FakeOptimizer(failure -> true, new ArrayList<>());
        optimizer.commitFailure = commitFailure;
        FakeBackend backend = new FakeBackend(optimizer, optimizer.events);
        backend.vendorFailureAt = 2;
        backend.vendorFailure = vendorFailure;

        Throwable observed = expectFailure(
                () -> new VulkanBlasBuildCoordinator(backend).submit(requests(3))
        );

        require(observed == commitFailure, "fallback commit failure must retain its identity");
        require(containsIdentity(observed.getSuppressed(), vendorFailure),
                "fallback commit failure must retain the original optimizer failure");
        requireClosedExactlyOnce(backend.vendorBuilds, "abandoned vendor generation");
        requireClosedExactlyOnce(backend.coreBuilds, "unpublished core replacements");
        require(optimizer.state == RecoveryState.RECOVERING,
                "failed fallback publication must not claim FALLBACK");
    }

    private static List<VulkanBlasBuildCoordinator.Request> requests(int count) {
        ArrayList<VulkanBlasBuildCoordinator.Request> requests = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long offset = (long) index * 0x100L;
            requests.add(new VulkanBlasBuildCoordinator.Request(
                    index,
                    new RtDeviceTriangleBlasBuilder.Geometry(
                            0x1000L + offset,
                            0x2000L + offset,
                            3,
                            1,
                            false
                    )
            ));
        }
        return List.copyOf(requests);
    }

    private static RtAccelerationStructure externalBlas(AtomicInteger releases) {
        // External wrappers never dereference the device during close. Allocating the opaque
        // handle without a constructor keeps this ownership test independent of a Vulkan loader.
        VkDevice opaqueDevice = allocateWithoutConstructor(VkDevice.class);
        return RtAccelerationStructure.wrapExternalBottomLevel(
                opaqueDevice,
                1L,
                2L,
                64L,
                32L,
                16,
                releases::incrementAndGet
        );
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeType.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            Object unsafe = singleton.get(null);
            Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
            return type.cast(allocate.invoke(unsafe, type));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot allocate opaque Vulkan test handle", failure);
        }
    }

    private static void closeAll(List<VulkanBlasBuildCoordinator.PendingBuild> builds) {
        RuntimeException failure = null;
        for (VulkanBlasBuildCoordinator.PendingBuild build : builds) {
            try {
                build.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static void requireClosedExactlyOnce(List<FakeBuild> builds, String label) {
        for (FakeBuild build : builds) {
            require(build.closeCount == 1, label + " must close exactly once: " + build.name);
        }
    }

    private static boolean containsIdentity(Throwable[] failures, Throwable expected) {
        for (Throwable failure : failures) {
            if (failure == expected) return true;
        }
        return false;
    }

    private static Throwable expectFailure(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("expected failure");
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("unexpected checked test failure", failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private enum RecoveryState {
        AVAILABLE,
        RECOVERING,
        FALLBACK
    }

    private static final class FakeOptimizer implements VulkanAccelerationStructureMemoryOptimizer {
        private final Predicate<Throwable> recoverable;
        private final List<String> events;
        private int recoveryAttempts;
        private int commits;
        private RuntimeException commitFailure;
        private RecoveryState state = RecoveryState.AVAILABLE;

        private FakeOptimizer(Predicate<Throwable> recoverable, List<String> events) {
            this.recoverable = recoverable;
            this.events = events;
        }

        @Override
        public RtBottomLevelBuild submitTriangleBlas(
                RtCommandContext commands,
                List<RtDeviceTriangleBlasBuilder.Geometry> geometries
        ) {
            throw new AssertionError("the deterministic backend owns fake vendor submission");
        }

        @Override
        public Optional<FailureRecovery> beginFailureRecovery(Throwable failure) {
            recoveryAttempts++;
            if (!recoverable.test(failure)) return Optional.empty();
            state = RecoveryState.RECOVERING;
            events.add("recovering");
            return Optional.of(new FailureRecovery() {
                private boolean committed;

                @Override
                public void commitCoreSubmission() {
                    if (committed) return;
                    if (commitFailure != null) throw commitFailure;
                    committed = true;
                    commits++;
                    state = RecoveryState.FALLBACK;
                    events.add("fallback");
                }
            });
        }
    }

    private static final class FakeBackend implements VulkanBlasBuildCoordinator.Backend {
        private final FakeOptimizer optimizer;
        private final List<String> events;
        private final ArrayList<FakeBuild> vendorBuilds = new ArrayList<>();
        private final ArrayList<FakeBuild> coreBuilds = new ArrayList<>();
        private int vendorAttempts;
        private int coreAttempts;
        private int vendorFailureAt = -1;
        private int coreFailureAt = -1;
        private Throwable vendorFailure;
        private Throwable coreFailure;

        private FakeBackend(FakeOptimizer optimizer, List<String> events) {
            this.optimizer = optimizer;
            this.events = events;
        }

        @Override
        public Optional<VulkanAccelerationStructureMemoryOptimizer>
        accelerationStructureMemoryOptimizer() {
            return Optional.of(optimizer);
        }

        @Override
        public RtBottomLevelBuild submitOptimized(
                VulkanAccelerationStructureMemoryOptimizer selected,
                RtDeviceTriangleBlasBuilder.Geometry geometry
        ) {
            require(selected == optimizer, "coordinator changed the selected optimizer identity");
            int attempt = ++vendorAttempts;
            events.add("vendor:" + attempt);
            if (attempt == vendorFailureAt) throwUnchecked(vendorFailure);
            FakeBuild build = new FakeBuild("vendor:" + attempt);
            vendorBuilds.add(build);
            return build;
        }

        @Override
        public RtBottomLevelBuild submitCore(RtDeviceTriangleBlasBuilder.Geometry geometry) {
            require(optimizer.state != RecoveryState.FALLBACK,
                    "fallback was published before all core submissions were accepted");
            int attempt = ++coreAttempts;
            events.add("core:" + attempt);
            if (attempt == coreFailureAt) throwUnchecked(coreFailure);
            FakeBuild build = new FakeBuild("core:" + attempt);
            coreBuilds.add(build);
            return build;
        }
    }

    private static final class FakeBuild implements RtBottomLevelBuild {
        private final String name;
        private RtAccelerationStructure completion;
        private Throwable completionFailure;
        private int closeCount;

        private FakeBuild(String name) {
            this.name = name;
        }

        @Override
        public RtAccelerationStructure completeIfReady() {
            if (completionFailure != null) throwUnchecked(completionFailure);
            return completion;
        }

        @Override
        public RtAccelerationStructure waitAndComplete() {
            RtAccelerationStructure result = completeIfReady();
            if (result == null) throw new AssertionError("fake build is still in flight: " + name);
            return result;
        }

        @Override
        public int geometryCount() {
            return 1;
        }

        @Override
        public long primitiveCount() {
            return 1L;
        }

        @Override
        public long sourceStorageBytes() {
            return 64L;
        }

        @Override
        public long completedStorageBytes() {
            return completion == null ? -1L : 64L;
        }

        @Override
        public boolean compacted() {
            return false;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
