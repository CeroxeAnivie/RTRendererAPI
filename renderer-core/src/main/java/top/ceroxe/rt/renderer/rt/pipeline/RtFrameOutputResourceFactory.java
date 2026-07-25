package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/**
 * Allocates a complete frame-slot ring and owns rollback until a slot is transferred to the pipeline.
 *
 * <p>The request is immutable resource-publication proof: every descriptor starts against the same
 * TLAS/material generation and every allocated resource is either transferred into one slot or
 * closed locally. This keeps allocation failure from leaking a partial GPU frame ring.</p>
 */
final class RtFrameOutputResourceFactory {
    private RtFrameOutputResourceFactory() {
    }

    static RtPipelineFrameSlot[] createSlots(Request request) {
        Objects.requireNonNull(request, "request");
        RtFrameOutputConfig.Extent traceExtent = primaryRayTraceExtent(
                request.outputExtent(), request.primaryRayUpscaleFactor(), request.minimumTraceWidth(), request.minimumTraceHeight()
        );
        int frameSlotCount = request.frameSlotCount();
        RtPipelineFrameSlot[] frameSlots = new RtPipelineFrameSlot[frameSlotCount];
        try {
            for (int index = 0; index < frameSlotCount; index++) {
                frameSlots[index] = createSlot(request, traceExtent, index);
            }
            return frameSlots;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, () -> closeSlots(frameSlots));
            throw failure;
        }
    }

    static RtGpuBuffer createFrameUniformBuffer(VkDevice device, long allocator, long frameUniformBytes, RtStallTelemetrySink stalls) {
        return RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                frameUniformBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                stalls
        );
    }

    static RtGpuBuffer createReadbackBuffer(VkDevice device, long allocator, int width, int height, RtStallTelemetrySink stalls) {
        return createReadbackBuffer(device, allocator, imageByteSize(width, height, Integer.BYTES), stalls);
    }

    static long imageByteSize(int width, int height, int bytesPerPixel) {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) {
            throw new IllegalArgumentException("image byte-size inputs must be positive");
        }
        return Math.multiplyExact(Math.multiplyExact((long) width, height), bytesPerPixel);
    }

    static RtFrameOutputConfig.Extent primaryRayTraceExtent(
            RtFrameOutputConfig.Extent outputExtent,
            int primaryRayUpscaleFactor,
            int minimumWidth,
            int minimumHeight
    ) {
        Objects.requireNonNull(outputExtent, "outputExtent");
        if (minimumWidth <= 0 || minimumHeight <= 0) {
            throw new IllegalArgumentException("minimum trace extent must be positive");
        }
        RtFrameOutputConfig.Extent scaled = outputExtent.divideAndRoundUp(primaryRayUpscaleFactor);
        return new RtFrameOutputConfig.Extent(
                Math.max(minimumWidth, scaled.width()),
                Math.max(minimumHeight, scaled.height())
        );
    }

    static void closeSlots(RtPipelineFrameSlot[] frameSlots) {
        if (frameSlots == null) {
            return;
        }
        RuntimeException firstFailure = null;
        for (RtPipelineFrameSlot slot : frameSlots) {
            if (slot == null) {
                continue;
            }
            try {
                slot.close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static RtPipelineFrameSlot createSlot(
            Request request,
            RtFrameOutputConfig.Extent traceExtent,
            int index
    ) {
        long[] descriptorSets = descriptorSetsForSlot(request, index);
        RtGpuImage outputImage = null;
        RtGpuImage traceImage = null;
        RtGpuBuffer readbackBuffer = null;
        RtGpuBuffer diagnosticGBuffer = null;
        RtGpuBuffer diagnosticGBufferReadback = null;
        RtGpuBuffer frameSlotUniformBuffer = null;
        RtGpuBuffer dynamicSceneBuffer = null;
        Throwable failure = null;
        try {
            outputImage = createOutputImage(request);
            traceImage = RtGpuImage.createStorageImage(
                    request.device(), request.allocator(), traceExtent.width(), traceExtent.height(), request.outputFormat()
            );
            readbackBuffer = request.frameReadbackResourcesEnabled()
                    ? createReadbackBuffer(
                    request.device(), request.allocator(), request.outputExtent().width(), request.outputExtent().height(),
                    request.diagnostics().stalls())
                    : null;
            diagnosticGBuffer = request.diagnosticGBufferResourcesEnabled()
                    ? createDiagnosticGBuffer(request, traceExtent)
                    : null;
            diagnosticGBufferReadback = request.diagnosticGBufferResourcesEnabled()
                    ? createDiagnosticGBufferReadback(request, traceExtent)
                    : null;
            frameSlotUniformBuffer = createFrameUniformBuffer(
                    request.device(), request.allocator(), request.frameUniformBytes(), request.diagnostics().stalls()
            );
            dynamicSceneBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    request.device(), request.allocator(), request.dynamicSceneBufferBytes(),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    request.diagnostics().stalls()
            );
            for (int descriptorIndex = 0; descriptorIndex < descriptorSets.length; descriptorIndex++) {
                long descriptorSet = descriptorSets[descriptorIndex];
                RtFrameDescriptorWriter.updateDescriptors(
                        request.stack(), request.device(), descriptorSet, request.topLevelAccelerationStructure(),
                        request.dynamicTopLevelAccelerationStructure(), traceImage, request.sceneMaterialTable(),
                        frameSlotUniformBuffer, dynamicSceneBuffer, diagnosticGBuffer,
                        request.frameUniformBytes(), request.dynamicSceneBufferBytes()
                );
                recordDescriptorWrite(request, descriptorIndex, descriptorSet);
            }
            RtPipelineFrameSlot slot = new RtPipelineFrameSlot(
                    index, descriptorSets, outputImage, traceImage, readbackBuffer, diagnosticGBuffer,
                    diagnosticGBufferReadback, frameSlotUniformBuffer, dynamicSceneBuffer,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_UNDEFINED, request.descriptorGeneration(),
                    request.descriptorSetsPerSlot(), request.dynamicSceneBufferBytes()
            );
            outputImage = null;
            traceImage = null;
            readbackBuffer = null;
            diagnosticGBuffer = null;
            diagnosticGBufferReadback = null;
            frameSlotUniformBuffer = null;
            dynamicSceneBuffer = null;
            return slot;
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            failure = error;
            throw error;
        } finally {
            closeSuppressing(failure, dynamicSceneBuffer);
            closeSuppressing(failure, readbackBuffer);
            closeSuppressing(failure, diagnosticGBufferReadback);
            closeSuppressing(failure, diagnosticGBuffer);
            closeSuppressing(failure, frameSlotUniformBuffer);
            closeSuppressing(failure, outputImage);
            closeSuppressing(failure, traceImage);
        }
    }

    private static long[] descriptorSetsForSlot(Request request, int slotIndex) {
        long[] descriptorSets = new long[request.descriptorSetsPerSlot()];
        for (int descriptorIndex = 0; descriptorIndex < descriptorSets.length; descriptorIndex++) {
            long descriptorSet = request.descriptorSetAt(slotIndex, descriptorIndex);
            if (descriptorSet == 0L) {
                throw new IllegalArgumentException("descriptor set handle must not be null");
            }
            descriptorSets[descriptorIndex] = descriptorSet;
        }
        return descriptorSets;
    }

    private static RtGpuImage createOutputImage(Request request) {
        return request.externalOutputExportEnabled()
                ? RtGpuImage.createExportableStorageImage(
                request.physicalDevice(), request.device(), request.outputExtent().width(), request.outputExtent().height(),
                request.outputFormat(), request.externalOutputDedicatedAllocation())
                : RtGpuImage.createStorageImage(
                request.device(), request.allocator(), request.outputExtent().width(), request.outputExtent().height(),
                request.outputFormat());
    }

    private static RtGpuBuffer createDiagnosticGBuffer(Request request, RtFrameOutputConfig.Extent traceExtent) {
        return RtGpuBuffer.createDeviceAddressBuffer(
                request.device(), request.allocator(),
                imageByteSize(traceExtent.width(), traceExtent.height(), request.diagnosticGBufferBytesPerPixel()),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                request.diagnostics().stalls()
        );
    }

    private static RtGpuBuffer createDiagnosticGBufferReadback(Request request, RtFrameOutputConfig.Extent traceExtent) {
        return createReadbackBuffer(
                request.device(), request.allocator(),
                imageByteSize(traceExtent.width(), traceExtent.height(), request.diagnosticGBufferBytesPerPixel()),
                request.diagnostics().stalls()
        );
    }

    private static RtGpuBuffer createReadbackBuffer(VkDevice device, long allocator, long sizeBytes, RtStallTelemetrySink stalls) {
        return RtGpuBuffer.createHostVisibleBuffer(
                device, allocator, sizeBytes, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, stalls
        );
    }

    private static void recordDescriptorWrite(Request request, int descriptorIndex, long descriptorSet) {
        RtSceneMaterialTable table = request.sceneMaterialTable();
        request.diagnostics().materials().descriptorSetWritten(
                request.descriptorGeneration(), descriptorIndex, descriptorSet, request.topLevelAccelerationStructure(),
                request.dynamicTopLevelAccelerationStructure(), table.sectionRecordBuffer(), table.sectionRecordBufferBytes(),
                table.faceRecordBuffer(), table.faceRecordBufferBytes(), table.textureRecordBuffer(),
                table.textureRecordBufferBytes(), table.texturePixelBuffer(), table.texturePixelBufferBytes()
        );
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    record Request(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            long allocator,
            boolean externalOutputExportEnabled,
            boolean externalOutputDedicatedAllocation,
            boolean frameReadbackResourcesEnabled,
            boolean diagnosticGBufferResourcesEnabled,
            long[] descriptorSets,
            int descriptorSetsPerSlot,
            int minimumFrameSlots,
            long topLevelAccelerationStructure,
            long dynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            RtFrameOutputConfig.Extent outputExtent,
            int primaryRayUpscaleFactor,
            int minimumTraceWidth,
            int minimumTraceHeight,
            int outputFormat,
            long frameUniformBytes,
            int dynamicSceneBufferBytes,
            int diagnosticGBufferBytesPerPixel,
            long descriptorGeneration,
            RendererRtDiagnostics diagnostics
    ) {
        Request {
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(physicalDevice, "physicalDevice");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(descriptorSets, "descriptorSets");
            Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
            Objects.requireNonNull(outputExtent, "outputExtent");
            Objects.requireNonNull(diagnostics, "diagnostics");
            if (allocator == 0L || descriptorSetsPerSlot <= 0 || minimumFrameSlots <= 0
                    || topLevelAccelerationStructure == 0L || dynamicTopLevelAccelerationStructure == 0L
                    || frameUniformBytes <= 0L || dynamicSceneBufferBytes <= 0L
                    || diagnosticGBufferBytesPerPixel <= 0 || descriptorGeneration <= 0L) {
                throw new IllegalArgumentException("frame output resource request contains invalid native or ABI values");
            }
            if (descriptorSets.length < Math.multiplyExact(minimumFrameSlots, descriptorSetsPerSlot)
                    || descriptorSets.length % descriptorSetsPerSlot != 0) {
                throw new IllegalArgumentException("descriptor set count must provide complete frame-slot descriptor banks");
            }
            descriptorSets = descriptorSets.clone();
        }

        /**
         * Returns an isolated copy of the descriptor-set bank.
         *
         * @return defensive descriptor-set handle copy
         */
        @Override
        public long[] descriptorSets() {
            return descriptorSets.clone();
        }

        int frameSlotCount() {
            return descriptorSets.length / descriptorSetsPerSlot;
        }

        long descriptorSetAt(int slotIndex, int descriptorIndex) {
            if (slotIndex < 0 || slotIndex >= frameSlotCount()
                    || descriptorIndex < 0 || descriptorIndex >= descriptorSetsPerSlot) {
                throw new IllegalArgumentException("frame slot descriptor index is outside the published bank");
            }
            return descriptorSets[Math.addExact(Math.multiplyExact(slotIndex, descriptorSetsPerSlot), descriptorIndex)];
        }
    }
}
