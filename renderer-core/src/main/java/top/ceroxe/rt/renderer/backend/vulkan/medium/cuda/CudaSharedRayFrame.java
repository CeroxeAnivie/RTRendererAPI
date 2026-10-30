package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.*;
import top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile;
import java.nio.*;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One reusable, thread-confined CUDA/Vulkan ray-exchange slot. Every exchange completes before returning.
 * Dedicated exported allocations cannot use an ordinary VMA suballocation: both the Vulkan heap budget
 * and the CUDA runtime registration cap are checked. This class does not own the device or CUDA runtime.
 */
public final class CudaSharedRayFrame implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final VulkanDeviceRuntime runtime;
    private final CudaMediumRuntime cuda;
    private final int capacity;
    private final long bytes;
    private long buffer, memory, allocationBytes, toCuda, toVulkan, commandPool, fence;
    private VkCommandBuffer release, acquire, pairedAcquire;
    private CudaMediumRuntime.DeviceMemory imported;
    private CudaMediumRuntime.ExternalSemaphore importedToCuda, importedToVulkan;
    private long pairedToCuda,pairedToVulkan;
    private long pairedValue;
    private CudaMediumRuntime.ExternalSemaphore importedPairedToCuda,importedPairedToVulkan;
    private boolean closed, busy, broken, submitted;

    public CudaSharedRayFrame(VulkanDeviceRuntime runtime, CudaMediumRuntime cuda, int capacity, long maxSlotBytes) {
        this(runtime, cuda, capacity, MediumRayQueryAbi.bytes(capacity), maxSlotBytes);
    }

    /** A shared output allocation with no ray-query capacity; bytes are not rounded to the ray ABI. */
    public static CudaSharedRayFrame storage(VulkanDeviceRuntime runtime, CudaMediumRuntime cuda, long bytes, long maxSlotBytes) {
        if (bytes < 1) throw new IllegalArgumentException("positive shared storage extent required");
        return new CudaSharedRayFrame(runtime, cuda, 0, bytes, maxSlotBytes);
    }

    private CudaSharedRayFrame(VulkanDeviceRuntime runtime, CudaMediumRuntime cuda, int capacity, long storageBytes, long maxSlotBytes) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.cuda = Objects.requireNonNull(cuda, "cuda");
        this.capacity = capacity;
        bytes = storageBytes;
        if (bytes > maxSlotBytes) throw new IllegalArgumentException("native ray slot exceeds its explicit budget");
        if (!runtime.enabledExtensions().contains("VK_KHR_external_memory_win32")
                || !runtime.enabledExtensions().contains("VK_KHR_external_semaphore_win32"))
            throw new IllegalStateException("native medium requires Win32 external memory and semaphores");
        try (MemoryStack s = MemoryStack.stackPush()) {
            var id = VkPhysicalDeviceIDProperties.calloc(s).sType$Default();
            VK11.vkGetPhysicalDeviceProperties2(runtime.physicalDevice(), VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(id.address()));
            byte[] uuid = cuda.deviceUuid();
            for (int i = 0; i < 16; i++) if (id.deviceUUID(i) != uuid[i])
                throw new IllegalArgumentException("Vulkan and CUDA physical devices differ");
            LongBuffer out = s.mallocLong(1);
            var external = VkExternalMemoryBufferCreateInfo.calloc(s).sType$Default()
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            check(vkCreateBuffer(device(), VkBufferCreateInfo.calloc(s).sType$Default().pNext(external.address())
                    .size(bytes).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, out), "create shared ray buffer");
            buffer = out.get(0);
            var requirements = VkMemoryRequirements.malloc(s);
            vkGetBufferMemoryRequirements(device(), buffer, requirements);
            allocationBytes = requirements.size();
            if (allocationBytes > maxSlotBytes) throw new IllegalArgumentException("dedicated allocation exceeds slot budget");
            var budget = runtime.memoryBudget();
            if (budget.deviceLocalBudgetBytes() > 0 && allocationBytes > budget.deviceLocalAvailableBytes())
                throw new IllegalStateException("native ray allocation exceeds available Vulkan memory budget");
            int memoryType = memoryType(requirements.memoryTypeBits(), s);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(s).sType$Default().buffer(buffer);
            var export = VkExportMemoryAllocateInfo.calloc(s).sType$Default()
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT).pNext(dedicated.address());
            var addressFlags = VkMemoryAllocateFlagsInfo.calloc(s).sType$Default()
                    .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).pNext(export.address());
            check(vkAllocateMemory(device(), VkMemoryAllocateInfo.calloc(s).sType$Default()
                    .pNext(addressFlags.address()).allocationSize(allocationBytes).memoryTypeIndex(memoryType), null, out), "allocate shared ray buffer");
            memory = out.get(0);
            check(vkBindBufferMemory(device(), buffer, memory, 0), "bind shared ray buffer");
            PointerBuffer handle = s.mallocPointer(1);
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device(), VkMemoryGetWin32HandleInfoKHR.calloc(s).sType$Default()
                    .memory(memory).handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT), handle), "export shared memory");
            long exported = handle.get(0);
            try { imported = cuda.importWin32Memory(exported, allocationBytes, 0, bytes, true); }
            finally { closeWin32(exported); }
            toCuda = semaphore(s); toVulkan = semaphore(s);
            importedToCuda = importSemaphore(toCuda, s); importedToVulkan = importSemaphore(toVulkan, s);
            check(vkCreateCommandPool(device(), VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .queueFamilyIndex(runtime.queueFamilyIndex()).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, out), "create native ray command pool");
            commandPool = out.get(0);
            check(vkCreateFence(device(), VkFenceCreateInfo.calloc(s).sType$Default(), null, out), "create native ray fence");
            fence = out.get(0);
            release = command(s); acquire = command(s);
            check(vkBeginCommandBuffer(release, VkCommandBufferBeginInfo.calloc(s).sType$Default()), "begin release");
            barrier(release, runtime.queueFamilyIndex(), VK11.VK_QUEUE_FAMILY_EXTERNAL,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, 0,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s);
            check(vkEndCommandBuffer(release), "end release");
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public VkDevice device() { return runtime.device(); }
    public int capacity() { checkOpen(); return capacity; }
    public long byteSize() { checkOpen(); return bytes; }
    public long allocationBytes() { checkOpen(); return allocationBytes; }
    public long buffer() { checkOpen(); return buffer; }
    public long deviceAddress() {
        checkOpen();
        try (MemoryStack s = MemoryStack.stackPush()) {
            long address = VK12.vkGetBufferDeviceAddress(device(), VkBufferDeviceAddressInfo.calloc(s).sType$Default().buffer(buffer));
            if (address == 0) throw new IllegalStateException("shared storage has no device address");
            return address;
        }
    }

    /**
     * Releases Vulkan ownership, executes one blocking CUDA batch, acquires ownership and records Vulkan work.
     * The callback must use this slot only and must not retain the slice/command for asynchronous host use.
     * The final fence covers the Vulkan work, so another slot can safely be exchanged afterwards.
     */
    public void exchangeBlocking(Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep) {
        exchangeBlocking(null, nativeStep, vulkanStep);
    }

    /**
     * Produces Vulkan data before the same ownership release consumed by CUDA. All slices in
     * this allocation share one handoff, so disjoint primary and result ranges need no nested exchange.
     * Prior Vulkan consumers complete their buffer accesses before the producer overwrites them.
     */
    public void produceThenExchangeBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep) {
        exchangeBlocking(Objects.requireNonNull(producer, "producer"), nativeStep, vulkanStep);
    }

    /**
     * Queues Vulkan producer, CUDA step, Vulkan continuation and CUDA step before one
     * final ownership fence. Callbacks enqueue work only; host results are readable
     * after this method returns. The two steps share the same allocation and stream.
     */
    public void produceThenExchangePairBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> firstStep, Consumer<VkCommandBuffer> continuation,
            Consumer<CudaMediumRuntime.Slice> secondStep) {
        checkOpen();Objects.requireNonNull(producer);Objects.requireNonNull(firstStep);
        Objects.requireNonNull(continuation);Objects.requireNonNull(secondStep);
        if(busy)throw new IllegalStateException("native ray slot is already in an exchange");
        busy=true;boolean profile=OpticalFrameTimings.ENABLED;
        long started=profile?System.nanoTime():0L;
        try(var producerTiming=profile?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalPrimaryPair",new String[]{"start","end"}):null;
            var continuationTiming=profile?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalContinuationPair",new String[]{"start","end"}):null;
            MemoryStack s=MemoryStack.stackPush()) {
            if(pairedAcquire==null)pairedAcquire=command(s);
            if(!runtime.timelineSemaphoreEnabled())throw new IllegalStateException("queued optical exchange requires timeline semaphores");
            if(pairedToCuda==0){pairedToCuda=timelineSemaphore(s);importedPairedToCuda=importTimelineSemaphore(pairedToCuda,s);}
            if(pairedToVulkan==0){pairedToVulkan=timelineSemaphore(s);importedPairedToVulkan=importTimelineSemaphore(pairedToVulkan,s);}
            long firstValue=Math.addExact(pairedValue,1L),secondValue=Math.addExact(pairedValue,2L);pairedValue=secondValue;
            check(vkResetFences(device(),fence),"reset paired ray fence");
            for(var command:new VkCommandBuffer[]{release,acquire,pairedAcquire}) {
                check(vkResetCommandBuffer(command,0),"reset paired ray command");
                check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(s).sType$Default()),"begin paired ray command");
            }
            barrier(release,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            if(producerTiming!=null)producerTiming.begin(release,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            producer.accept(release);
            if(producerTiming!=null)producerTiming.write(release,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            barrier(release,runtime.queueFamilyIndex(),VK11.VK_QUEUE_FAMILY_EXTERNAL,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,0,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s);
            barrier(acquire,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            barrier(acquire,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            if(continuationTiming!=null)continuationTiming.begin(acquire,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            continuation.accept(acquire);
            if(continuationTiming!=null)continuationTiming.write(acquire,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            barrier(acquire,runtime.queueFamilyIndex(),VK11.VK_QUEUE_FAMILY_EXTERNAL,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,0,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s);
            barrier(pairedAcquire,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            for(var command:new VkCommandBuffer[]{release,acquire,pairedAcquire})
                check(vkEndCommandBuffer(command),"end paired ray command");
            long submitStarted=profile?System.nanoTime():0L;
            check(VulkanQueueHostSync.submit(runtime.frameQueue(),VkSubmitInfo.calloc(1,s).sType$Default()
                    .pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pSignalSemaphoreValues(s.longs(firstValue)).address())
                    .pCommandBuffers(s.pointers(release.address())).pSignalSemaphores(s.longs(pairedToCuda)),VK_NULL_HANDLE),"release paired rays to CUDA");
            if(profile){long now=System.nanoTime();OpticalFrameTimings.release.add(now-started);
                OpticalFrameTimings.releaseCommands.add(submitStarted-started);OpticalFrameTimings.releaseSubmit.add(now-submitStarted);}
            long cudaStarted=profile?System.nanoTime():0L;long[] middleSubmitNanos={0L},consumerCompleted={0L};
            cuda.exchangeExternalPairWithCompletionBlocking(importedPairedToCuda,importedPairedToVulkan,firstValue,secondValue,
                    ()->firstStep.accept(imported.slice(0,bytes)),()->{
                        long middleStarted=profile?System.nanoTime():0L;
                        check(VulkanQueueHostSync.submit(runtime.frameQueue(),VkSubmitInfo.calloc(1,s).sType$Default()
                                .pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default()
                                    .pWaitSemaphoreValues(s.longs(firstValue)).pSignalSemaphoreValues(s.longs(secondValue)).address())
                                .waitSemaphoreCount(1).pWaitSemaphores(s.longs(pairedToVulkan)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                                .pCommandBuffers(s.pointers(acquire.address())).pSignalSemaphores(s.longs(pairedToCuda)),VK_NULL_HANDLE),"submit paired Vulkan continuation");
                        if(profile){middleSubmitNanos[0]=System.nanoTime()-middleStarted;
                            OpticalFrameTimings.consumerRecord.add(middleSubmitNanos[0]);
                            OpticalFrameTimings.consumerSubmit.add(middleSubmitNanos[0]);}
                    },()->secondStep.accept(imported.slice(0,bytes)),()->{
                        long finalStarted=profile?System.nanoTime():0L;
                        if(profile)OpticalFrameTimings.cudaStage.add(finalStarted-cudaStarted-middleSubmitNanos[0]);
                        check(VulkanQueueHostSync.submit(runtime.frameQueue(),VkSubmitInfo.calloc(1,s).sType$Default()
                                .pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pWaitSemaphoreValues(s.longs(secondValue)).address())
                                .waitSemaphoreCount(1).pWaitSemaphores(s.longs(pairedToVulkan)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                                .pCommandBuffers(s.pointers(pairedAcquire.address())),fence),"acquire paired rays from CUDA");
                        submitted=true;long waitStarted=profile?System.nanoTime():0L;
                        if(profile){OpticalFrameTimings.consumerRecord.add(waitStarted-finalStarted);
                            OpticalFrameTimings.consumerSubmit.add(waitStarted-finalStarted);}
                        check(vkWaitForFences(device(),fence,true,10_000_000_000L),"paired ray completion");
                        if(profile)OpticalFrameTimings.consumerWait.add(System.nanoTime()-waitStarted);
                        submitted=false;
                        if(profile)consumerCompleted[0]=System.nanoTime();
                    });
            if(profile)OpticalFrameTimings.cudaStage.add(System.nanoTime()-consumerCompleted[0]);
            if(producerTiming!=null&&producerTiming.resolve()){OpticalFrameTimings.producerGpu.add(producerTiming.totalNanos());OpticalDetailedProfile.add("producerGpu/totalNanos",producerTiming.totalNanos());}
            if(continuationTiming!=null&&continuationTiming.resolve()){OpticalFrameTimings.consumerGpu.add(continuationTiming.totalNanos());OpticalDetailedProfile.add("continuationGpu/totalNanos",continuationTiming.totalNanos());}
            OpticalDetailedProfile.resolveDeferred();
        }catch(RuntimeException|Error failure){broken=true;throw failure;}
        finally{busy=false;if(profile){OpticalFrameTimings.exchange.add(System.nanoTime()-started);OpticalFrameTimings.rounds.add(2);}}
    }

    /** The final frame submission consumes the semaphore and records acquire before its work.
     * It must wait for that submission's fence before returning; no ownership escapes this call. */
    @FunctionalInterface
    public interface BlockingConsumer {
        void submitAndWait(long semaphore, Consumer<VkCommandBuffer> acquireOwnership);
    }

    public void produceThenConsumeBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, BlockingConsumer consumer) {
        exchangeBlocking(Objects.requireNonNull(producer, "producer"), nativeStep,
                command -> {}, Objects.requireNonNull(consumer, "consumer"));
    }

    private void exchangeBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep) {
        exchangeBlocking(producer, nativeStep, vulkanStep, null);
    }

    private void exchangeBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep,
            BlockingConsumer finalConsumer) {
        checkOpen(); Objects.requireNonNull(nativeStep); Objects.requireNonNull(vulkanStep);
        if (busy) throw new IllegalStateException("native ray slot is already in an exchange");
        busy = true;
        final boolean profile=OpticalFrameTimings.ENABLED;
        final long exchangeStarted=profile?System.nanoTime():0L;
        long detailedStarted=OpticalDetailedProfile.start();
        try (var producerTiming=profile&&producer!=null?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalPrimary",new String[]{"start","end"}):null;
             var consumerTiming=profile&&finalConsumer==null?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalContinuation",new String[]{"start","end"}):null;
             MemoryStack s = MemoryStack.stackPush()) {
            final long releaseCommandsStarted=profile?System.nanoTime():0L;
            check(vkResetFences(device(), fence), "reset native ray fence");
            check(vkResetCommandBuffer(release, 0), "reset native ray release");
            check(vkBeginCommandBuffer(release, VkCommandBufferBeginInfo.calloc(s).sType$Default()), "begin native ray release");
            if (producer != null) {
                barrier(release, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, s);
                if(producerTiming!=null)producerTiming.begin(release,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                producer.accept(release);
                if(producerTiming!=null)producerTiming.write(release,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            }
            barrier(release, runtime.queueFamilyIndex(), VK11.VK_QUEUE_FAMILY_EXTERNAL,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, 0,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s);
            check(vkEndCommandBuffer(release), "end native ray release");
            if(OpticalDetailedProfile.ENABLED)OpticalDetailedProfile.elapsed("wall/releaseRecordNanos",detailedStarted);
            final long releaseSubmitStarted=profile?System.nanoTime():0L;
            check(VulkanQueueHostSync.submit(runtime.frameQueue(), VkSubmitInfo.calloc(1, s).sType$Default()
                    .pCommandBuffers(s.pointers(release.address())).pSignalSemaphores(s.longs(toCuda)), VK_NULL_HANDLE), "release to CUDA");
            if(profile){
                long now=System.nanoTime();
                OpticalFrameTimings.release.add(now-exchangeStarted);
                OpticalFrameTimings.releaseCommands.add(releaseSubmitStarted-releaseCommandsStarted);
                OpticalFrameTimings.releaseSubmit.add(now-releaseSubmitStarted);
                OpticalDetailedProfile.add("wall/releaseSubmitNanos",now-releaseSubmitStarted);
            }
            Runnable completeConsumer = () -> {
                final long consumerStarted=profile?System.nanoTime():0L;
                if (finalConsumer != null) {
                    finalConsumer.submitAndWait(toVulkan, command -> barrier(command,
                            VK11.VK_QUEUE_FAMILY_EXTERNAL, runtime.queueFamilyIndex(), 0,
                            VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, s));
                    return;
                }
                check(vkResetCommandBuffer(acquire, 0), "reset acquire");
                check(vkBeginCommandBuffer(acquire, VkCommandBufferBeginInfo.calloc(s).sType$Default()), "begin acquire");
                barrier(acquire, VK11.VK_QUEUE_FAMILY_EXTERNAL, runtime.queueFamilyIndex(), 0,
                        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, s);
                if(consumerTiming!=null)consumerTiming.begin(acquire,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                vulkanStep.accept(acquire);
                if(consumerTiming!=null)consumerTiming.write(acquire,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                check(vkEndCommandBuffer(acquire), "end acquire");
                final long consumerSubmitStarted=profile?System.nanoTime():0L;
                check(VulkanQueueHostSync.submit(runtime.frameQueue(), VkSubmitInfo.calloc(1, s).sType$Default()
                        .waitSemaphoreCount(1).pWaitSemaphores(s.longs(toVulkan)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                        .pCommandBuffers(s.pointers(acquire.address())), fence), "acquire from CUDA");
                submitted = true;
                long waitStarted=profile?System.nanoTime():0L;
                if(profile){
                    OpticalFrameTimings.consumerRecord.add(waitStarted-consumerStarted);
                    OpticalFrameTimings.consumerCommands.add(consumerSubmitStarted-consumerStarted);
                    OpticalFrameTimings.consumerSubmit.add(waitStarted-consumerSubmitStarted);
                    OpticalDetailedProfile.add("wall/consumerRecordNanos",consumerSubmitStarted-consumerStarted);
                    OpticalDetailedProfile.add("wall/consumerSubmitNanos",waitStarted-consumerSubmitStarted);
                }
                check(vkWaitForFences(device(), fence, true, 10_000_000_000L), "native ray completion");
                if(profile){long waited=System.nanoTime()-waitStarted;OpticalFrameTimings.consumerWait.add(waited);OpticalDetailedProfile.add("wall/consumerFenceNanos",waited);}
                submitted = false;
            };
            final long cudaStarted=profile?System.nanoTime():0L;
            if (finalConsumer == null) {
                // General continuations may nest exchanges and read host counters. Preserve
                // their established blocking CUDA boundary before the next Vulkan query.
                cuda.exchangeExternalBlocking(importedToCuda, importedToVulkan,
                        () -> nativeStep.accept(imported.slice(0, bytes)));
                if(profile){long elapsed=System.nanoTime()-cudaStarted;OpticalFrameTimings.cudaStage.add(elapsed);OpticalDetailedProfile.add("wall/cudaStageNanos",elapsed);}
                completeConsumer.run();
            } else {
                cuda.exchangeExternalWithCompletionBlocking(importedToCuda, importedToVulkan,
                        () -> nativeStep.accept(imported.slice(0, bytes)), ()->{
                            if(profile){long elapsed=System.nanoTime()-cudaStarted;OpticalFrameTimings.cudaStage.add(elapsed);OpticalDetailedProfile.add("wall/cudaStageNanos",elapsed);}
                            completeConsumer.run();
                        });
            }
            if(producerTiming!=null&&producerTiming.resolve()){OpticalFrameTimings.producerGpu.add(producerTiming.totalNanos());OpticalDetailedProfile.add("producerGpu/totalNanos",producerTiming.totalNanos());}
            if(consumerTiming!=null&&consumerTiming.resolve()){OpticalFrameTimings.consumerGpu.add(consumerTiming.totalNanos());OpticalDetailedProfile.add("continuationGpu/totalNanos",consumerTiming.totalNanos());}
            OpticalDetailedProfile.resolveDeferred();
        } catch (RuntimeException | Error failure) { broken = true; throw failure; }
        finally { busy = false;if(profile){OpticalFrameTimings.exchange.add(System.nanoTime()-exchangeStarted);OpticalFrameTimings.rounds.increment();} }
    }
    public void nativeOnlyBlocking(Consumer<CudaMediumRuntime.Slice> nativeStep) { exchangeBlocking(nativeStep, command -> { }); }

    private int memoryType(int bits, MemoryStack s) {
        var properties = VkPhysicalDeviceMemoryProperties.malloc(s);
        vkGetPhysicalDeviceMemoryProperties(runtime.physicalDevice(), properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) return i;
        throw new IllegalStateException("no device-local memory type for exported ray buffer");
    }
    private long semaphore(MemoryStack s) {
        LongBuffer out = s.mallocLong(1);
        var export = VkExportSemaphoreCreateInfo.calloc(s).sType$Default().handleTypes(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        check(vkCreateSemaphore(device(), VkSemaphoreCreateInfo.calloc(s).sType$Default().pNext(export.address()), null, out), "create external ray semaphore");
        return out.get(0);
    }
    private long timelineSemaphore(MemoryStack s) {
        LongBuffer out=s.mallocLong(1);
        var export=VkExportSemaphoreCreateInfo.calloc(s).sType$Default().handleTypes(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var type=VkSemaphoreTypeCreateInfo.calloc(s).sType$Default().pNext(export.address()).semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
        check(vkCreateSemaphore(device(),VkSemaphoreCreateInfo.calloc(s).sType$Default().pNext(type.address()),null,out),"create timeline ray semaphore");
        return out.get(0);
    }
    private CudaMediumRuntime.ExternalSemaphore importTimelineSemaphore(long semaphore,MemoryStack s) {
        PointerBuffer out=s.mallocPointer(1);
        check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device(),VkSemaphoreGetWin32HandleInfoKHR.calloc(s).sType$Default()
                .semaphore(semaphore).handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT),out),"export timeline ray semaphore");
        long handle=out.get(0);
        try{return cuda.importWin32TimelineSemaphore(handle);}finally{closeWin32(handle);}
    }
    private CudaMediumRuntime.ExternalSemaphore importSemaphore(long semaphore, MemoryStack s) {
        PointerBuffer out = s.mallocPointer(1);
        check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device(), VkSemaphoreGetWin32HandleInfoKHR.calloc(s).sType$Default()
                .semaphore(semaphore).handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT), out), "export ray semaphore");
        long handle = out.get(0);
        try { return cuda.importWin32Semaphore(handle); } finally { closeWin32(handle); }
    }
    private VkCommandBuffer command(MemoryStack s) {
        PointerBuffer out = s.mallocPointer(1);
        check(vkAllocateCommandBuffers(device(), VkCommandBufferAllocateInfo.calloc(s).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), out), "allocate ray command");
        return new VkCommandBuffer(out.get(0), device());
    }
    private void barrier(VkCommandBuffer command, int from, int to, int sourceAccess, int destinationAccess,
                         int sourceStage, int destinationStage, MemoryStack s) {
        var barrier = VkBufferMemoryBarrier.calloc(1, s).sType$Default().srcQueueFamilyIndex(from).dstQueueFamilyIndex(to)
                .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess).buffer(buffer).offset(0).size(bytes);
        vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, barrier, null);
    }
    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("native ray slot belongs to another thread");
        if (closed || broken) throw new IllegalStateException("native ray slot is closed or failed");
    }
    private static void check(int result, String stage) {
        if (result != VK_SUCCESS) throw new IllegalStateException(stage + ": VkResult=" + result);
    }
    private static void closeWin32(long handle) {
        try (Arena arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup("kernel32.dll", arena);
            var close = Linker.nativeLinker().downcallHandle(lookup.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            int result = (int) close.invokeExact(MemorySegment.ofAddress(handle));
            if (result == 0) throw new IllegalStateException("CloseHandle failed for external ray resource");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException("cannot close exported Win32 handle", e); }
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("native ray slot belongs to another thread");
        if (closed) return;
        if (busy) throw new IllegalStateException("cannot close an active native ray slot");
        RuntimeException failure = null;
        if (submitted) try { check(vkWaitForFences(device(), fence, true, 10_000_000_000L), "drain native ray slot"); }
        catch (RuntimeException e) { failure = e; }
        // A failed callback may leave the release submission outstanding; it never queues an unfulfilled Vulkan wait.
        if (broken) try { check(VulkanQueueHostSync.waitIdle(runtime.frameQueue()), "drain failed native ray release"); }
        catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        for (AutoCloseable resource : new AutoCloseable[]{importedPairedToVulkan,importedPairedToCuda,importedToVulkan, importedToCuda, imported}) if (resource != null) try { resource.close(); }
        catch (Exception e) { if (failure == null) failure = new IllegalStateException("native ray CUDA cleanup", e); else failure.addSuppressed(e); }
        if (fence != 0) vkDestroyFence(device(), fence, null);
        if (commandPool != 0) vkDestroyCommandPool(device(), commandPool, null);
        if (toVulkan != 0) vkDestroySemaphore(device(), toVulkan, null);
        if (toCuda != 0) vkDestroySemaphore(device(), toCuda, null);
        if (pairedToVulkan != 0) vkDestroySemaphore(device(), pairedToVulkan, null);
        if (pairedToCuda != 0) vkDestroySemaphore(device(), pairedToCuda, null);
        if (buffer != 0) vkDestroyBuffer(device(), buffer, null);
        if (memory != 0) vkFreeMemory(device(), memory, null);
        closed = true;
        if (failure != null) throw failure;
    }
}
