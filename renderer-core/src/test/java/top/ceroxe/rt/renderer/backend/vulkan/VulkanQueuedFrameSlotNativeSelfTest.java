package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanImportedSemaphore;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;

/** Real queued binary reuse, exact publication, unused-signal drain and resize regression. */
public final class VulkanQueuedFrameSlotNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var capability=VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), "hardware RT required");
        try(var device=VulkanDeviceRuntime.open(capability,RendererRtDiagnostics.noop(),true,false)) {
            var interop=device.externalFrameInterop();
            require(interop.semaphoreExportReady() && interop.semaphoreImportReady(), "binary interop required");
            try(var slot=new VulkanFrameSlot(0,device,VulkanFrameOutput.from(FrameOutputFormat.SDR_RGBA8),
                    interop.dedicatedAllocationRequired(),true,true,RendererRtDiagnostics.noop().stalls(),false);
                var done=VulkanWin32ExternalSemaphoreProbe.exportSemaphore(device.device())) {
                slot.enableBinarySemaphoreDelivery();
                verifyDelayedProducer(slot, device);
                long ready=slot.readyBinarySemaphore(), resource=-1;
                for(int sequence=1;sequence<=20;sequence++) {
                    slot.prepare(sequence<=10?32:48,32,new byte[VulkanFrameUniformPacker.BYTE_COUNT]);
                    require(!slot.queuedPresentable(sequence),"unsubmitted frame became claimable");
                    final int previous=slot.imageLayout();
                    long observedBefore=slot.observedProducerFrameSequence();
                    var submission=device.frameCommands().submitTimedExternalSynchronizedOneTimeAsync(
                            "queuedSlot",0L,ready,0L,0L,0L,0L,(command,stack)->{
                                var barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default()
                                    .oldLayout(previous).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                    .image(slot.outputImage().image());
                                barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                                VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,null,null,barrier);
                                slot.recordExternalOwnershipRelease(command,stack);
                            });
                    device.featureSession().commitFrameSubmission(sequence);
                    slot.submitted(submission,sequence,0,sequence,true,VulkanDeviceRuntime.ManagedPresentationSignal.disabled(),false);
                    require(slot.queuedPresentable(sequence) && !slot.queuedPresentable(sequence+1),"exact publication mismatch");
                    require(slot.observedProducerFrameSequence()==observedBefore,"acquisition observed producer fence");
                    var lease=slot.acquireQueued();
                    require(!slot.queuedPresentable(sequence),"frame claimed twice");
                    require(lease.descriptor().frameSequence()==sequence,"wrong sequence");
                    if(sequence==1) resource=lease.descriptor().resourceId();
                    if(sequence==11) require(resource!=lease.descriptor().resourceId(),"resize reused resource identity");
                    if(sequence%3==0) { lease.close(); }
                    else {
                        var signal=lease.acquireSignal().orElseThrow();
                        var readyCompletion=new GpuFrameLease.ExternalSemaphoreSignal(signal.handle().value(),signal.handle().handleType(),
                                signal.kind(),0L,GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE);
                        try(var imported=VulkanImportedSemaphore.importBinary(device.device(),readyCompletion)) {
                            signal.handle().markImported();
                            if(sequence%2==0) signal.handle().close();
                            try(var consume=device.frameCommands().submitBinarySynchronizedOneTimeAsync(
                                    imported.semaphore(),done.semaphore(),(command,stack)->{})) {
                                lease.release(new GpuFrameLease.ExternalSemaphoreSignal(done.win32Handle(),
                                    new GpuFrameLease.VulkanSemaphoreHandleType(done.handleType()),GpuFrameLease.SemaphoreKind.BINARY,
                                    0L,GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE));
                                require(!slot.writable(),"released but unclosed lease freed slot");
                                lease.close();
                            }
                        }
                    }
                    long until=System.nanoTime()+15_000_000_000L;
                    while(!slot.writable()) {slot.pollProducer(); if(System.nanoTime()>until)throw new AssertionError("slot retirement timed out");Thread.sleep(1);}
                    require(slot.readyBinarySemaphore()==ready,"ready semaphore recreated per frame");
                }
                // Legacy completed acquire consumes an unclaimed ready exactly once.
                slot.prepare(32,32,new byte[VulkanFrameUniformPacker.BYTE_COUNT]);
                var legacy=device.frameCommands().submitTimedExternalSynchronizedOneTimeAsync("legacyReady",0L,ready,
                        0L,0L,0L,0L,(command,stack)->{});
                device.featureSession().commitFrameSubmission(21);
                slot.submitted(legacy,21,0,21,true,VulkanDeviceRuntime.ManagedPresentationSignal.disabled(),false);
                while(!slot.pollProducer()) Thread.sleep(1);
                var oldLease=slot.acquire();
                require(oldLease.acquireSignal().isEmpty(),"legacy poll changed acquire contract");
                oldLease.close();
                require(slot.writable(),"legacy ready drain prevented reuse");
                var discarded=device.frameCommands().submitTimedExternalSynchronizedOneTimeAsync("discardReady",0L,ready,
                        0L,0L,0L,0L,(command,stack)->{});
                device.featureSession().commitFrameSubmission(22);
                slot.submitted(discarded,22,0,22,true,VulkanDeviceRuntime.ManagedPresentationSignal.disabled(),false);
                while(!slot.pollProducer()) Thread.sleep(1);
                slot.discardCompleted();
                while(!slot.writable()){slot.pollProducer();Thread.sleep(1);}
                var finalPending=device.frameCommands().submitTimedExternalSynchronizedOneTimeAsync("closePending",0L,ready,
                        0L,0L,0L,0L,(command,stack)->{});
                device.featureSession().commitFrameSubmission(23);
                slot.submitted(finalPending,23,0,23,true,VulkanDeviceRuntime.ManagedPresentationSignal.disabled(),false);
                // slot.close must safely wait producer and drain its unpublished-to-consumer signal.
            }
        }
        System.out.println("VulkanQueuedFrameSlotNativeSelfTest passed: 20 exact claims, binary reuse, unused leases, imported-handle close, resize and slot close");
    }
    private static void verifyDelayedProducer(VulkanFrameSlot slot,VulkanDeviceRuntime device) throws Exception {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var type=VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            var out=stack.mallocLong(1);
            require(VK10.vkCreateSemaphore(device.device(),VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type),null,out)==0,"create gate");
            long gate=out.get(0);
            try {
                slot.prepare(32,32,new byte[VulkanFrameUniformPacker.BYTE_COUNT]);
                var submission=device.frameCommands().submitTimedExternalSynchronizedOneTimeAsync("delayedQueued",0L,
                        slot.readyBinarySemaphore(),gate,1L,0L,0L,(command,s)->{});
                device.featureSession().commitFrameSubmission(0);
                slot.submitted(submission,0,0,0,true,VulkanDeviceRuntime.ManagedPresentationSignal.disabled(),false);
                long start=System.nanoTime();
                var lease=slot.acquireQueued();
                require(System.nanoTime()-start<500_000_000L,"queued acquisition waited producer fence");
                require(slot.producerPending(),"delayed producer was prematurely retired");
                lease.close();
                require(!slot.writable(),"unused queued lease recycled blocked producer");
                require(VK12.vkSignalSemaphore(device.device(),VkSemaphoreSignalInfo.calloc(stack).sType$Default().semaphore(gate).value(1L))==0,"open gate");
                long until=System.nanoTime()+15_000_000_000L;
                while(!slot.writable()) {slot.pollProducer();if(System.nanoTime()>until)throw new AssertionError("delayed producer drain timed out");Thread.sleep(1);}
            } finally { VK10.vkDestroySemaphore(device.device(),gate,null); }
        }
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
