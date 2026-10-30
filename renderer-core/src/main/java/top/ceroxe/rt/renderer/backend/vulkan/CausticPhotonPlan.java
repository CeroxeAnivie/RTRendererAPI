package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Immutable CPU publication for one bounded, frame-slot-owned surface and volume photon map. */
final class CausticPhotonPlan {
    static final java.util.concurrent.atomic.LongAdder diagnosticPhotonDispatches = new java.util.concurrent.atomic.LongAdder();
    static final int HEADER_WORDS = 16, EMITTER_WORDS = 36, RECORD_WORDS = 16;
    static final CausticPhotonPlan DISABLED = new CausticPhotonPlan(0, List.of());
    record Emitter(int slot, int[] media) {
        Emitter {
            media = media.clone();
            if (media.length > 32) throw new IllegalArgumentException("photon emitter exceeds 32 media");
        }
    }
    private final int photons, depositsPerPath, recordCapacity, hashCapacity, headsWord, recordsWord;
    private final long bytes;
    private final byte[] header;

    CausticPhotonPlan(int requestedPhotons, List<Emitter> emitters) {
        this(requestedPhotons,emitters,1);
    }

    CausticPhotonPlan(int requestedPhotons,List<Emitter> emitters,int depositsPerPath) {
        photons = emitters.isEmpty() ? 0 : requestedPhotons;
        if (photons == 0) {
            this.depositsPerPath=1;recordCapacity=hashCapacity=headsWord=recordsWord=0;bytes=0;header=new byte[0];return;
        }
        if (photons < 0 || photons > top.ceroxe.rt.renderer.api.Caustics.MAX_PHOTONS)
            throw new IllegalArgumentException("invalid photon count");
        if(depositsPerPath<1||depositsPerPath>32)throw new IllegalArgumentException("invalid photon deposits per path");
        this.depositsPerPath=depositsPerPath;
        recordCapacity=Math.multiplyExact(photons,depositsPerPath);
        long requestedHash=(long)recordCapacity*2;
        if(requestedHash>(1L<<30))throw invalidMap("complete photon map exceeds 32-bit shader indexing");
        hashCapacity = Integer.highestOneBit((int)(requestedHash - 1)) << 1;
        headsWord = Math.addExact(HEADER_WORDS, Math.multiplyExact(emitters.size(), EMITTER_WORDS));
        recordsWord = Math.addExact(headsWord, hashCapacity);
        bytes = Math.multiplyExact((long) recordsWord + (long) recordCapacity * RECORD_WORDS, 4);
        if(bytes>Integer.MAX_VALUE)throw invalidMap("complete photon map exceeds the addressable Vulkan storage-buffer range");
        var data = ByteBuffer.allocate(headsWord * 4).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, emitters.size()).putInt(4, headsWord).putInt(8, recordsWord)
                .putInt(12, hashCapacity - 1).putInt(16, recordCapacity).putInt(20,depositsPerPath);
        for (int i = 0; i < emitters.size(); i++) {
            Emitter emitter = emitters.get(i);
            int offset = (HEADER_WORDS + i * EMITTER_WORDS) * 4;
            data.putInt(offset, emitter.slot).putInt(offset + 4, emitter.media.length);
            for (int j = 0; j < emitter.media.length; j++) data.putInt(offset + 8 + j * 4, emitter.media[j]);
        }
        header = data.array();
    }

    int photons() { return photons; }
    int depositsPerPath(){return depositsPerPath;}
    int recordCapacity(){return recordCapacity;}
    long bytes() { return bytes; }
    int hashCapacity() { return hashCapacity; }
    int headsWord() { return headsWord; }
    int recordsWord() { return recordsWord; }

    private static top.ceroxe.rt.renderer.api.FrameValidationException invalidMap(String detail) {
        return new top.ceroxe.rt.renderer.api.FrameValidationException(
                top.ceroxe.rt.renderer.api.FrameValidationException.Reason.INVALID_CAUSTICS,detail);
    }

    void record(VkCommandBuffer command, MemoryStack stack, RtGpuBuffer buffer,
            GpuSceneRayTracingPipeline pipeline, int slot) {
        if (photons == 0) return;
        initializeMap(command,stack,buffer);
        // Keep both launch axes bounded; the shader discards the final row's surplus invocations.
        pipeline.recordTrace(command, stack, slot, Math.min(photons, 1024), (photons + 1023) / 1024);
        diagnosticPhotonDispatches.increment();
        barrier(command, stack, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    void initializeMap(VkCommandBuffer command,MemoryStack stack,RtGpuBuffer buffer) {
        if(photons==0)return;
        // UpdateBuffer's limit is 64 KiB per command. The emitter table can exceed it.
        for (int offset = 0; offset < header.length; offset += 16384) {
            int count = Math.min(16384, header.length - offset);
            try (var temporary = MemoryStack.stackPush()) {
                var block = temporary.malloc(count);
                block.put(header, offset, count).flip();
                VK10.vkCmdUpdateBuffer(command, buffer.buffer(), offset, block);
            }
        }
        VK10.vkCmdFillBuffer(command, buffer.buffer(), headsWord * 4L, hashCapacity * 4L, -1);
        barrier(command, stack, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, int sourceAccess,
            int destinationAccess, int sourceStage, int destinationStage) {
        var memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        VK10.vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, memory, null, null);
    }
}
