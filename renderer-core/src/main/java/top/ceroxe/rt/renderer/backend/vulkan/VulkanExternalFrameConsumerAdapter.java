package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameCompletionEvidence;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameConsumerCapabilities;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameConsumerSession;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameConsumptionEvidence;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameInterop;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameLease;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameNegotiation;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameOffer;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameTransport;
import top.ceroxe.rt.renderer.api.interop.ExternalHandleOwnership;
import top.ceroxe.rt.renderer.api.interop.ExternalHandleState;
import top.ceroxe.rt.renderer.api.interop.ExternalHandleTransport;
import top.ceroxe.rt.renderer.api.interop.ExternalImageImportProfile;
import top.ceroxe.rt.renderer.api.interop.ExternalMemoryHandleType;
import top.ceroxe.rt.renderer.api.interop.ExternalMemoryRegion;
import top.ceroxe.rt.renderer.api.interop.ExternalSynchronizationContract;
import top.ceroxe.rt.renderer.api.interop.ExternalSynchronizationHandleType;
import top.ceroxe.rt.renderer.api.interop.ExternalSynchronizationSignal;
import top.ceroxe.rt.renderer.api.interop.OwnedExternalHandle;
import top.ceroxe.rt.renderer.api.interop.PortableFrameDescriptor;
import top.ceroxe.rt.renderer.api.interop.SynchronizationPrimitiveKind;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Bridges the stable Vulkan expert ABI into the project-independent external-frame contract.
 *
 * <p>The adapter intentionally exposes only the completion semantics it can prove from the old
 * ABI. The current producer lease is created after a CPU-observed producer fence, so the generic
 * transport advertises CPU producer completion and CPU consumer completion. An acquire semaphore
 * returned by a future Vulkan implementation is rejected instead of being silently discarded.</p>
 */
final class VulkanExternalFrameConsumerAdapter implements ExternalFrameInterop {
    private static final ExternalHandleTransport WINDOWS = ExternalHandleTransport.WINDOWS_HANDLE;
    private static final ExternalMemoryHandleType MEMORY_TYPE = new ExternalMemoryHandleType(
            WINDOWS, "vulkan", "opaque-win32-memory-0x" + Integer.toHexString(
                    VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
            )
    );
    private static final ExternalImageImportProfile IMPORT_PROFILE =
            new ExternalImageImportProfile("vulkan", "external-image-opaque-win32", 1);
    private static final ExternalFrameTransport TRANSPORT = new ExternalFrameTransport(
            FrameOutputFormat.SDR_RGBA8,
            MEMORY_TYPE,
            IMPORT_PROFILE,
            ExternalSynchronizationContract.CpuObserved.INSTANCE,
            Set.of(ExternalSynchronizationContract.CpuObserved.INSTANCE)
    );

    private final VulkanFrameInterop delegate;
    private final FrameOutputFormat outputFormat;
    private final ExternalFrameOffer offer;
    private boolean closed;

    VulkanExternalFrameConsumerAdapter(VulkanFrameInterop delegate, FrameOutputFormat outputFormat) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.outputFormat = Objects.requireNonNull(outputFormat, "outputFormat");
        this.offer = new ExternalFrameOffer(List.of(new ExternalFrameTransport(
                outputFormat,
                MEMORY_TYPE,
                IMPORT_PROFILE,
                ExternalSynchronizationContract.CpuObserved.INSTANCE,
                Set.of(ExternalSynchronizationContract.CpuObserved.INSTANCE)
        )));
    }

    @Override
    public synchronized ExternalFrameOffer offer() {
        if (closed) throw new IllegalStateException("Vulkan external-frame adapter is closed");
        return offer;
    }

    @Override
    public synchronized ExternalFrameNegotiation negotiate(ExternalFrameConsumerCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (closed) {
            return new ExternalFrameNegotiation.Rejected(
                    ExternalFrameNegotiation.Reason.PROVIDER_UNAVAILABLE,
                    "Vulkan external-frame adapter is closed"
            );
        }
        Optional<ExternalFrameTransport> selected = capabilities.selectFrom(offer);
        if (selected.isEmpty()) {
            return new ExternalFrameNegotiation.Rejected(
                    ExternalFrameNegotiation.Reason.NO_COMMON_TRANSPORT,
                    "consumer did not accept the exact Vulkan external-image transport"
            );
        }
        return new ExternalFrameNegotiation.Accepted(new Session(selected.orElseThrow()));
    }

    synchronized void close() {
        closed = true;
    }

    private final class Session implements ExternalFrameConsumerSession {
        private final ExternalFrameTransport transport;
        private boolean sessionClosed;

        private Session(ExternalFrameTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        @Override
        public ExternalFrameTransport transport() {
            return transport;
        }

        @Override
        public synchronized PollResult pollLatestFrame() {
            if (sessionClosed) throw new IllegalStateException("external-frame consumer session is closed");
            VulkanFrameInterop.FramePollResult result = delegate.pollLatestFrame();
            if (result instanceof VulkanFrameInterop.FrameNotReady) {
                return FrameNotReady.INSTANCE;
            }
            VulkanFrameInterop.FrameAvailable available = (VulkanFrameInterop.FrameAvailable) result;
            GpuFrameLease lease = available.lease();
            try {
                return new FrameAvailable(new Lease(lease, transport));
            } catch (RuntimeException failure) {
                try {
                    lease.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            sessionClosed = true;
        }
    }

    private final class Lease implements ExternalFrameLease {
        private final GpuFrameLease delegateLease;
        private final ExternalFrameTransport transport;
        private final PortableFrameDescriptor descriptor;
        private final ExternalMemoryRegion memoryRegion;
        private final OwnedExternalHandle<ExternalMemoryHandleType> memoryHandle;
        private ExternalFrameConsumptionEvidence evidence;

        private Lease(GpuFrameLease delegateLease, ExternalFrameTransport transport) {
            this.delegateLease = Objects.requireNonNull(delegateLease, "delegateLease");
            this.transport = Objects.requireNonNull(transport, "transport");
            GpuFrameLease.FrameDescriptor nativeDescriptor = delegateLease.descriptor();
            if (delegateLease.acquireSignal().isPresent()) {
                throw new IllegalStateException(
                        "Vulkan expert lease exposes an acquire signal not representable by this adapter"
                );
            }
            FrameOutputFormat actualFormat = format(nativeDescriptor.format().value());
            if (actualFormat != outputFormat || actualFormat != transport.format()) {
                throw new IllegalStateException(
                        "Vulkan lease format does not match negotiated transport: expected="
                                + transport.format() + ", actual=" + actualFormat
                );
            }
            long accessibleBytes = Math.multiplyExact(
                    Math.multiplyExact((long) nativeDescriptor.width(), nativeDescriptor.height()),
                    actualFormat == FrameOutputFormat.SDR_RGBA8 ? 4L : 8L
            );
            this.descriptor = new PortableFrameDescriptor(
                    new RenderResourceId(nativeDescriptor.resourceId()),
                    new ResourceVersion(nativeDescriptor.frameSequence()),
                    nativeDescriptor.frameSequence(), nativeDescriptor.width(), nativeDescriptor.height(),
                    actualFormat, PortableFrameDescriptor.ImageOrigin.TOP_LEFT,
                    PortableFrameDescriptor.AlphaMode.OPAQUE
            );
            this.memoryRegion = new ExternalMemoryRegion(
                    nativeDescriptor.allocationSize(), nativeDescriptor.allocationOffset(),
                    accessibleBytes, nativeDescriptor.dedicatedAllocation()
            );
            this.memoryHandle = new MemoryHandle(delegateLease.memoryHandle());
            this.evidence = new ExternalFrameConsumptionEvidence(
                    descriptor.frameSequence(), ExternalFrameConsumptionEvidence.Outcome.LEASED,
                    Optional.empty(), 0L, "Vulkan expert lease acquired"
            );
        }

        @Override public synchronized PortableFrameDescriptor descriptor() { return descriptor; }
        @Override public synchronized ExternalFrameTransport transport() { return transport; }
        @Override public synchronized OwnedExternalHandle<ExternalMemoryHandleType> memoryHandle() { return memoryHandle; }
        @Override public synchronized ExternalMemoryRegion memoryRegion() { return memoryRegion; }
        @Override public synchronized Optional<ExternalSynchronizationSignal> acquireSignal() { return Optional.empty(); }

        @Override
        public synchronized void release(ExternalFrameCompletionEvidence completion) {
            Objects.requireNonNull(completion, "completion");
            if (completion.frameSequence() != descriptor.frameSequence()) {
                throw new IllegalArgumentException("completion evidence belongs to a different frame");
            }
            if (!(completion instanceof ExternalFrameCompletionEvidence.CpuObserved)) {
                throw new UnsupportedOperationException(
                        "this Vulkan adapter exposes CPU-observed consumer completion only"
                );
            }
            delegateLease.release(new GpuFrameLease.CpuCompleted());
            evidence = new ExternalFrameConsumptionEvidence(
                    descriptor.frameSequence(), ExternalFrameConsumptionEvidence.Outcome.COMPLETION_PUBLISHED,
                    Optional.of(completion), 0L, "consumer CPU completion published to Vulkan producer"
            );
        }

        @Override
        public synchronized LeaseState state() {
            return switch (delegateLease.state()) {
                case ACTIVE -> LeaseState.ACTIVE;
                case RELEASED -> LeaseState.RELEASED;
                case CLOSED -> LeaseState.CLOSED;
            };
        }

        @Override public synchronized ExternalFrameConsumptionEvidence evidence() { return evidence; }

        @Override
        public synchronized void close() {
            LeaseState current = state();
            if (current == LeaseState.ACTIVE && memoryHandle.state() == ExternalHandleState.IMPORTED) {
                throw new IllegalStateException("cannot close an imported active Vulkan external lease");
            }
            delegateLease.close();
            if (current == LeaseState.RELEASED) {
                evidence = new ExternalFrameConsumptionEvidence(
                        descriptor.frameSequence(), ExternalFrameConsumptionEvidence.Outcome.RETIRED,
                        evidence.completion(), 0L, "Vulkan producer retired the released image"
                );
            } else if (current == LeaseState.ACTIVE) {
                evidence = new ExternalFrameConsumptionEvidence(
                        descriptor.frameSequence(), ExternalFrameConsumptionEvidence.Outcome.ABANDONED,
                        Optional.empty(), 0L, "unimported Vulkan image lease abandoned before consumer use"
                );
            }
        }
    }

    private static final class MemoryHandle implements OwnedExternalHandle<ExternalMemoryHandleType> {
        private final GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> delegate;

        private MemoryHandle(GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public ExternalMemoryHandleType handleType() { return MEMORY_TYPE; }
        @Override public ExternalHandleOwnership ownership() {
            return delegate.importDisposition() == GpuFrameLease.ImportDisposition.IMPORT_CONSUMES_HANDLE
                    ? ExternalHandleOwnership.IMPORT_CONSUMES_HANDLE
                    : ExternalHandleOwnership.EXPORTER_RETAINS_HANDLE;
        }
        @Override public ExternalHandleState state() {
            return switch (delegate.state()) {
                case EXPORTED -> ExternalHandleState.EXPORTED;
                case IMPORTED -> ExternalHandleState.IMPORTED;
                case CLOSED -> ExternalHandleState.CLOSED;
            };
        }
        @Override public long nativeValue() { return delegate.value(); }
        @Override public boolean markImported() { return delegate.markImported(); }
        @Override public void close() { delegate.close(); }
    }

    private static FrameOutputFormat format(int value) {
        if (value == VK10.VK_FORMAT_R8G8B8A8_UNORM) return FrameOutputFormat.SDR_RGBA8;
        if (value == VK10.VK_FORMAT_R16G16B16A16_SFLOAT) return FrameOutputFormat.LINEAR_HDR_RGBA16F;
        throw new IllegalStateException("unsupported Vulkan external frame format: " + value);
    }
}
