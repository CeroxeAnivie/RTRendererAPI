package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/** Owns the bounded frame-slot ring and per-slot primitive acceleration resources. */
final class VulkanGpuSceneFrameRing implements AutoCloseable {
    private final VulkanFrameSlot[] slots;
    private final VulkanFramePrimitiveResources[] primitiveResources;
    private boolean slotsClosed;
    private boolean primitivesClosed;

    static VulkanGpuSceneFrameRing open(
            VulkanDeviceRuntime device,
            RayTracingRendererConfig configuration,
            VulkanFrameOutput frameOutput,
            VulkanDeviceRuntime.ExternalFrameInterop interop,
            RendererRtDiagnostics diagnostics,
            VulkanGpuSceneFeatureComposition.Selection features
    ) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        RayTracingRendererConfig checkedConfiguration = Objects.requireNonNull(configuration, "configuration");
        VulkanDeviceRuntime.ExternalFrameInterop checkedInterop = Objects.requireNonNull(interop, "interop");
        RendererRtDiagnostics checkedDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        VulkanGpuSceneFeatureComposition.Selection checkedFeatures = Objects.requireNonNull(features, "features");
        VulkanFrameSlot[] slots = new VulkanFrameSlot[checkedConfiguration.maxFramesInFlight()];
        VulkanFramePrimitiveResources[] primitives = new VulkanFramePrimitiveResources[slots.length];
        try {
            for (int index = 0; index < slots.length; index++) {
                slots[index] = new VulkanFrameSlot(
                        index,
                        checkedDevice,
                        Objects.requireNonNull(frameOutput, "frameOutput"),
                        checkedInterop.dedicatedAllocationRequired(),
                        checkedInterop.semaphoreImportReady(),
                        checkedConfiguration.cpuFrameReadbackEnabled(),
                        checkedDiagnostics.stalls(),
                        checkedConfiguration.temporalRendering().enabled(),
                        checkedFeatures.denoising(),
                        checkedFeatures.reconstruction(),
                        checkedFeatures.frameGeneration()
                );
                primitives[index] = new VulkanFramePrimitiveResources(
                        checkedDevice, checkedDiagnostics.stalls()
                );
            }
            return new VulkanGpuSceneFrameRing(slots, primitives);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressingReverse(failure, primitives);
            closeSuppressingReverse(failure, slots);
            throw failure;
        }
    }

    private VulkanGpuSceneFrameRing(
            VulkanFrameSlot[] slots,
            VulkanFramePrimitiveResources[] primitiveResources
    ) {
        this.slots = Objects.requireNonNull(slots, "slots").clone();
        this.primitiveResources = Objects.requireNonNull(primitiveResources, "primitiveResources").clone();
        if (this.slots.length == 0 || this.slots.length != this.primitiveResources.length) {
            throw new IllegalArgumentException("frame slots and primitive resources must have equal non-zero size");
        }
        for (VulkanFrameSlot slot : this.slots) Objects.requireNonNull(slot, "slot");
        for (VulkanFramePrimitiveResources resources : this.primitiveResources) {
            Objects.requireNonNull(resources, "primitiveResources entry");
        }
    }

    int size() {
        return slots.length;
    }

    VulkanFramePrimitiveResources primitives(VulkanFrameSlot slot) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        if (checked.index() < 0 || checked.index() >= primitiveResources.length
                || slots[checked.index()] != checked) {
            throw new IllegalArgumentException("frame slot does not belong to this ring");
        }
        return primitiveResources[checked.index()];
    }

    boolean hasProducerPending() {
        for (VulkanFrameSlot slot : slots) {
            if (slot.producerPending()) return true;
        }
        return false;
    }

    long reclaimIdleOutputs() {
        long reclaimedBytes = 0L;
        for (VulkanFrameSlot slot : slots) {
            if (slot.writable()) {
                reclaimedBytes = Math.addExact(reclaimedBytes, slot.trimIdleOutputImage());
            }
        }
        return reclaimedBytes;
    }

    Progress poll(long completedDescriptorEpoch, long completedFrameSequence) {
        long epoch = completedDescriptorEpoch;
        long sequence = completedFrameSequence;
        for (VulkanFrameSlot slot : slots) {
            slot.pollProducer();
            epoch = Math.max(epoch, slot.observedProducerDescriptorEpoch());
            sequence = Math.max(sequence, slot.observedProducerFrameSequence());
        }
        return new Progress(epoch, sequence);
    }

    VulkanFrameSlot writableSlot() {
        for (VulkanFrameSlot slot : slots) {
            if (slot.writable()) return slot;
        }
        return null;
    }

    boolean allSlotsWritable() {
        for (VulkanFrameSlot slot : slots) {
            if (!slot.writable()) return false;
        }
        return true;
    }

    VulkanFrameSlot latestCompletedSlot() {
        VulkanFrameSlot latest = null;
        for (VulkanFrameSlot slot : slots) {
            if (slot.completed() && (latest == null || slot.frameSequence() > latest.frameSequence())) {
                latest = slot;
            }
        }
        return latest;
    }

    VulkanFrameSlot earliestManagedPresentableSlot(long afterFrameSequence) {
        VulkanFrameSlot earliest = null;
        for (VulkanFrameSlot slot : slots) {
            if (slot.managedPresentable()
                    && slot.frameSequence() > afterFrameSequence
                    && (earliest == null || slot.frameSequence() < earliest.frameSequence())) {
                earliest = slot;
            }
        }
        return earliest;
    }

    void discardCompletedExcept(VulkanFrameSlot retained) {
        VulkanFrameSlot checked = Objects.requireNonNull(retained, "retained");
        for (VulkanFrameSlot slot : slots) {
            if (slot != checked && slot.completed()) slot.discardCompleted();
        }
    }

    void discardAllCompleted() {
        for (VulkanFrameSlot slot : slots) {
            if (slot.completed()) slot.discardCompleted();
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        if (!slotsClosed) {
            failure = closeReverse(slots);
            if (failure == null) slotsClosed = true;
        }
        // A failed slot close means a submission may still reference every downstream primitive
        // resource. Preserve the retryable dependency boundary and stop here.
        if (failure != null) throw failure;
        if (!primitivesClosed) {
            failure = closeReverse(primitiveResources);
            if (failure == null) primitivesClosed = true;
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException closeReverse(AutoCloseable[] resources) {
        RuntimeException failure = null;
        for (int index = resources.length - 1; index >= 0; index--) {
            try {
                resources[index].close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } catch (Exception impossible) {
                RuntimeException closeFailure = new IllegalStateException("unexpected checked close failure", impossible);
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static void closeSuppressingReverse(Throwable failure, AutoCloseable[] resources) {
        for (int index = resources.length - 1; index >= 0; index--) {
            AutoCloseable resource = resources[index];
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    record Progress(long descriptorEpoch, long frameSequence) {
    }
}
