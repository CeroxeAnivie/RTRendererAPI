package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline.Stage.*;


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
 * Retained pairs defer Vulkan ownership acquisition until the next real producer or explicit publication.
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
    private final VkCommandBuffer[] batchCommands=new VkCommandBuffer[8];
    private CudaMediumRuntime.DeviceMemory imported;
    private CudaMediumRuntime.ExternalSemaphore importedToCuda, importedToVulkan;
    private long pairedToCuda,pairedToVulkan;
    private long pairedValue;
    private CudaMediumRuntime.ExternalSemaphore importedPairedToCuda,importedPairedToVulkan;
    private long binarySecondToCuda,binarySecondToVulkan;
    private CudaMediumRuntime.ExternalSemaphore importedBinarySecondToCuda,importedBinarySecondToVulkan;
    private boolean closed, busy, broken, submitted, externalOwned,externalOwnedBinary;

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
     * Queues a CUDA batch and its pinned readbacks before the external signal, then uses
     * the Vulkan consumer fence as the single completion boundary. The native callback
     * must only enqueue work: no host-counter decisions or nested ownership exchanges.
     * Host results are complete on return; the caller must validate before publication.
     */
    public void produceThenExchangeQueuedBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep) {
        exchangeBlocking(Objects.requireNonNull(producer, "producer"), nativeStep, vulkanStep, null, true);
    }

    /**
     * Queues Vulkan producer, CUDA step, Vulkan continuation and CUDA step before one
     * final ownership fence. Callbacks enqueue work only; host results are readable
     * after this method returns. The two steps share the same allocation and stream.
     */
    public void produceThenExchangePairBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> firstStep, Consumer<VkCommandBuffer> continuation,
            Consumer<CudaMediumRuntime.Slice> secondStep) {
        produceThenExchangePairBlocking(producer,firstStep,continuation,secondStep,false);
    }

    /** Retaining external ownership removes the otherwise empty final Vulkan submission.
     * CUDA and both Vulkan producers are complete on return; call acquireForVulkanBlocking
     * before publishing the buffer to an external Vulkan consumer. The next pair acquires
     * in its real producer, with the previous CUDA timeline signal as its dependency. */
    public void produceThenExchangePairBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> firstStep, Consumer<VkCommandBuffer> continuation,
            Consumer<CudaMediumRuntime.Slice> secondStep,boolean retainExternal) {
        produceThenExchangePairBlocking(producer,firstStep,continuation,secondStep,retainExternal,
                Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.opticalBinaryPairs","true")));
    }
    public void produceThenExchangePairBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> firstStep, Consumer<VkCommandBuffer> continuation,
            Consumer<CudaMediumRuntime.Slice> secondStep,boolean retainExternal,boolean binaryChain) {
        int wallScope = OpticalWallTimeline.enter(SHARED_EXCHANGE);
        int wallStep = OpticalWallTimeline.enter(VULKAN_RELEASE_RECORD);
        try {
        checkOpen();Objects.requireNonNull(producer);Objects.requireNonNull(firstStep);
        Objects.requireNonNull(continuation);Objects.requireNonNull(secondStep);
        if(!retainExternal||(externalOwned&&externalOwnedBinary!=binaryChain))acquireForVulkanBlocking();
        if(busy)throw new IllegalStateException("native ray slot is already in an exchange");
        busy=true;boolean profile=OpticalFrameTimings.ENABLED;
        long started=profile?System.nanoTime():0L;
        try(var producerTiming=profile?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalPrimaryPair",new String[]{"start","end"}):null;
            var continuationTiming=profile?runtime.frameCommands().acquireGpuTimestampCapture(
                    "opticalContinuationPair",new String[]{"start","end"}):null;
            MemoryStack s=MemoryStack.stackPush()) {
            if(pairedAcquire==null)pairedAcquire=command(s);
            if(binaryChain){
                if(binarySecondToCuda==0){binarySecondToCuda=semaphore(s);importedBinarySecondToCuda=importSemaphore(binarySecondToCuda,s);}
                if(binarySecondToVulkan==0){binarySecondToVulkan=semaphore(s);importedBinarySecondToVulkan=importSemaphore(binarySecondToVulkan,s);}
            }else{
                if(!runtime.timelineSemaphoreEnabled())throw new IllegalStateException("queued optical exchange requires timeline semaphores");
                if(pairedToCuda==0){pairedToCuda=timelineSemaphore(s);importedPairedToCuda=importTimelineSemaphore(pairedToCuda,s);}
                if(pairedToVulkan==0){pairedToVulkan=timelineSemaphore(s);importedPairedToVulkan=importTimelineSemaphore(pairedToVulkan,s);}
            }
            final long firstToCuda=binaryChain?toCuda:pairedToCuda,firstToVulkan=binaryChain?toVulkan:pairedToVulkan;
            final long secondToCuda=binaryChain?binarySecondToCuda:pairedToCuda,secondToVulkan=binaryChain?binarySecondToVulkan:pairedToVulkan;
            long previousValue=pairedValue;
            long firstValue=Math.addExact(previousValue,1L),secondValue=Math.addExact(previousValue,2L);pairedValue=secondValue;
            if(binaryChain||!retainExternal)check(vkResetFences(device(),fence),"reset paired ray fence");
            var commands=retainExternal?new VkCommandBuffer[]{release,acquire}:new VkCommandBuffer[]{release,acquire,pairedAcquire};
            for(var command:commands) {
                check(vkResetCommandBuffer(command,0),"reset paired ray command");
                check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(s).sType$Default()),"begin paired ray command");
            }
            if(externalOwned)barrier(release,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
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
            if(!retainExternal)barrier(pairedAcquire,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            for(var command:commands)
                check(vkEndCommandBuffer(command),"end paired ray command");
            wallStep = OpticalWallTimeline.next(wallStep, VULKAN_RELEASE_SUBMIT);
            long submitStarted=profile?System.nanoTime():0L;
            var releaseValues=VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pSignalSemaphoreValues(s.longs(firstValue));
            var releaseSubmit=VkSubmitInfo.calloc(1,s).sType$Default()
                    .pCommandBuffers(s.pointers(release.address())).pSignalSemaphores(s.longs(firstToCuda));
            if(!binaryChain)releaseSubmit.pNext(releaseValues.address());
            if(externalOwned){releaseValues.pWaitSemaphoreValues(s.longs(previousValue));
                releaseSubmit.waitSemaphoreCount(1).pWaitSemaphores(s.longs(secondToVulkan))
                        .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));}
            check(VulkanQueueHostSync.submit(runtime.frameQueue(),releaseSubmit,VK_NULL_HANDLE),"release paired rays to CUDA");
            if(profile){long now=System.nanoTime();OpticalFrameTimings.release.add(now-started);
                OpticalFrameTimings.releaseCommands.add(submitStarted-started);OpticalFrameTimings.releaseSubmit.add(now-submitStarted);}
            long cudaStarted=profile?System.nanoTime():0L;long[] middleSubmitNanos={0L},consumerCompleted={0L};
            wallStep = OpticalWallTimeline.next(wallStep, CUDA_EXCHANGE);
            Runnable submitMiddle=()->{
                        int middleTiming = OpticalWallTimeline.enter(VULKAN_CONSUMER_SUBMIT);
                        try {
                        long middleStarted=profile?System.nanoTime():0L;
                        var middle=VkSubmitInfo.calloc(1,s).sType$Default()
                                .waitSemaphoreCount(1).pWaitSemaphores(s.longs(firstToVulkan)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                                .pCommandBuffers(s.pointers(acquire.address())).pSignalSemaphores(s.longs(secondToCuda));
                        if(!binaryChain)middle.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default()
                                    .pWaitSemaphoreValues(s.longs(firstValue)).pSignalSemaphoreValues(s.longs(secondValue)).address());
                        check(VulkanQueueHostSync.submit(runtime.frameQueue(),middle,binaryChain&&retainExternal?fence:VK_NULL_HANDLE),"submit paired Vulkan continuation");
                        if(binaryChain&&retainExternal)submitted=true;
                        if(profile){middleSubmitNanos[0]=System.nanoTime()-middleStarted;
                            OpticalFrameTimings.consumerRecord.add(middleSubmitNanos[0]);
                            OpticalFrameTimings.consumerSubmit.add(middleSubmitNanos[0]);}
                        } finally { OpticalWallTimeline.leave(middleTiming); }
                    };
            Runnable completeConsumer=()->{
                        if(retainExternal){
                            if(profile){long now=System.nanoTime();OpticalFrameTimings.cudaStage.add(now-cudaStarted-middleSubmitNanos[0]);consumerCompleted[0]=now;}
                            return;
                        }
                        int finalTiming = OpticalWallTimeline.enter(VULKAN_CONSUMER_SUBMIT);
                        try {
                        long finalStarted=profile?System.nanoTime():0L;
                        if(profile)OpticalFrameTimings.cudaStage.add(finalStarted-cudaStarted-middleSubmitNanos[0]);
                        var complete=VkSubmitInfo.calloc(1,s).sType$Default()
                                .waitSemaphoreCount(1).pWaitSemaphores(s.longs(secondToVulkan)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                                .pCommandBuffers(s.pointers(pairedAcquire.address()));
                        if(!binaryChain)complete.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pWaitSemaphoreValues(s.longs(secondValue)).address());
                        check(VulkanQueueHostSync.submit(runtime.frameQueue(),complete,fence),"acquire paired rays from CUDA");
                        submitted=true;long waitStarted=profile?System.nanoTime():0L;
                        if(profile){OpticalFrameTimings.consumerRecord.add(waitStarted-finalStarted);
                            OpticalFrameTimings.consumerSubmit.add(waitStarted-finalStarted);}
                        finalTiming = OpticalWallTimeline.next(finalTiming, VULKAN_CONSUMER_FENCE_WAIT);
                        check(vkWaitForFences(device(),fence,true,10_000_000_000L),"paired ray completion");
                        if(profile)OpticalFrameTimings.consumerWait.add(System.nanoTime()-waitStarted);
                        submitted=false;
                        if(profile)consumerCompleted[0]=System.nanoTime();
                        } finally { OpticalWallTimeline.leave(finalTiming); }
                    };
            if(binaryChain)cuda.exchangeExternalBinaryPairWithCompletionBlocking(importedToCuda,importedToVulkan,
                    importedBinarySecondToCuda,importedBinarySecondToVulkan,()->firstStep.accept(imported.slice(0,bytes)),
                    submitMiddle,()->secondStep.accept(imported.slice(0,bytes)),completeConsumer);
            else cuda.exchangeExternalPairWithCompletionBlocking(importedPairedToCuda,importedPairedToVulkan,firstValue,secondValue,
                    ()->firstStep.accept(imported.slice(0,bytes)),submitMiddle,()->secondStep.accept(imported.slice(0,bytes)),completeConsumer);
            if(profile)OpticalFrameTimings.cudaStage.add(System.nanoTime()-consumerCompleted[0]);
            if(retainExternal){
                // CUDA completion proves the external data is ready, but a Vulkan host
                // timeline wait also proves that release/acquire commands are no longer
                // pending before their next reset. The second Vulkan signal precedes
                // the already completed second CUDA step, so this adds no GPU submit.
                wallStep=OpticalWallTimeline.next(wallStep,VULKAN_COMMAND_REUSE_WAIT);
                long reuseStarted=profile?System.nanoTime():0L;
                if(binaryChain){check(vkWaitForFences(device(),fence,true,10_000_000_000L),"complete binary pair commands before reuse");submitted=false;}
                else check(VK12.vkWaitSemaphores(device(),VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
                        .pSemaphores(s.longs(pairedToCuda)).pValues(s.longs(secondValue)),10_000_000_000L),
                        "complete paired ray commands before reuse");
                if(profile)OpticalFrameTimings.consumerWait.add(System.nanoTime()-reuseStarted);
            }
            externalOwned=retainExternal;
            externalOwnedBinary=retainExternal&&binaryChain;
            wallStep = OpticalWallTimeline.next(wallStep, GPU_QUERY_RESOLVE);
            if(producerTiming!=null&&producerTiming.resolve()){OpticalFrameTimings.producerGpu.add(producerTiming.totalNanos());OpticalFrameTimings.recordNamedGpuStage("vulkan.shared.producer",producerTiming.totalNanos());OpticalDetailedProfile.add("producerGpu/totalNanos",producerTiming.totalNanos());}
            if(continuationTiming!=null&&continuationTiming.resolve()){OpticalFrameTimings.consumerGpu.add(continuationTiming.totalNanos());OpticalFrameTimings.recordNamedGpuStage("vulkan.shared.continuation",continuationTiming.totalNanos());OpticalDetailedProfile.add("continuationGpu/totalNanos",continuationTiming.totalNanos());}
            OpticalDetailedProfile.resolveDeferred();
        }catch(RuntimeException|Error failure){broken=true;throw failure;}
        finally{busy=false;if(profile){OpticalFrameTimings.exchange.add(System.nanoTime()-started);OpticalFrameTimings.rounds.add(2);}}

        } finally { OpticalWallTimeline.leave(wallStep); OpticalWallTimeline.leave(wallScope); }
    }

    /**
     * Retained binary batch. Every round has its own command buffer and all callbacks
     * enqueue only. Statistics and buffer contents are complete when this returns.
     * Even length leaves the same second-pair signal for the next producer/publication.
     */
    public void produceThenExchangeBatchBlocking(int rounds,
            java.util.function.BiConsumer<Integer,VkCommandBuffer> producer,
            java.util.function.BiConsumer<Integer,CudaMediumRuntime.Slice> nativeStep) {
        int wallScope=OpticalWallTimeline.enter(SHARED_EXCHANGE);
        int wallStep=OpticalWallTimeline.enter(VULKAN_RELEASE_RECORD);
        try {
            checkOpen();Objects.requireNonNull(producer);Objects.requireNonNull(nativeStep);
            if(rounds<2||rounds>8||(rounds&1)!=0)throw new IllegalArgumentException("even batch of 2..8 rounds required");
            if(externalOwned&&!externalOwnedBinary)acquireForVulkanBlocking();
            if(busy)throw new IllegalStateException("native ray slot is already in an exchange");
            busy=true;boolean profile=OpticalFrameTimings.ENABLED;
            long started=profile?System.nanoTime():0L;
            var captures=profile?new top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool.Capture[rounds]:null;
            try(MemoryStack s=MemoryStack.stackPush()) {
                if(binarySecondToCuda==0){binarySecondToCuda=semaphore(s);importedBinarySecondToCuda=importSemaphore(binarySecondToCuda,s);}
                if(binarySecondToVulkan==0){binarySecondToVulkan=semaphore(s);importedBinarySecondToVulkan=importSemaphore(binarySecondToVulkan,s);}
                check(vkResetFences(device(),fence),"reset optical batch fence");
                for(int round=0;round<rounds;round++) {
                    // No command is reset or re-recorded while an earlier round is pending.
                    if(batchCommands[round]==null)batchCommands[round]=command(s);
                    var command=batchCommands[round];
                    check(vkResetCommandBuffer(command,0),"reset optical batch command");
                    check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(s).sType$Default()),"begin optical batch command");
                    if(round>0||externalOwned)barrier(command,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                            VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
                    barrier(command,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,
                            VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
                    if(captures!=null){
                        captures[round]=runtime.frameCommands().acquireGpuTimestampCapture("opticalBatchRound",new String[]{"start","end"});
                        if(captures[round]!=null)captures[round].begin(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                    }
                    producer.accept(round,command);
                    if(captures!=null&&captures[round]!=null)captures[round].write(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                    barrier(command,runtime.queueFamilyIndex(),VK11.VK_QUEUE_FAMILY_EXTERNAL,
                            VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,0,
                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s);
                    check(vkEndCommandBuffer(command),"end optical batch command");
                }
                if(profile){long elapsed=System.nanoTime()-started;OpticalFrameTimings.releaseCommands.add(elapsed);OpticalFrameTimings.release.add(elapsed);}
                wallStep=OpticalWallTimeline.next(wallStep,CUDA_EXCHANGE);
                long cudaStarted=profile?System.nanoTime():0L;long[] submitNanos={0L};
                var slice=imported.slice(0,bytes);
                cuda.exchangeExternalBinaryBatchBlocking(importedToCuda,importedToVulkan,
                        importedBinarySecondToCuda,importedBinarySecondToVulkan,rounds,round->{
                            int submitTiming=OpticalWallTimeline.enter(round==0?VULKAN_RELEASE_SUBMIT:VULKAN_CONSUMER_SUBMIT);
                            long submitStarted=profile?System.nanoTime():0L;
                            try {
                                try(MemoryStack submitStack=MemoryStack.stackPush()) {
                                    long output=(round&1)==0?toCuda:binarySecondToCuda;
                                    long input=(round&1)==0?binarySecondToVulkan:toVulkan;
                                    var submit=VkSubmitInfo.calloc(1,submitStack).sType$Default()
                                            .pCommandBuffers(submitStack.pointers(batchCommands[round].address()))
                                            .pSignalSemaphores(submitStack.longs(output));
                                    if(round>0||externalOwned)submit.waitSemaphoreCount(1).pWaitSemaphores(submitStack.longs(input))
                                            .pWaitDstStageMask(submitStack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
                                    check(VulkanQueueHostSync.submit(runtime.frameQueue(),submit,round==rounds-1?fence:VK_NULL_HANDLE),
                                            "submit optical batch round");
                                    if(round==rounds-1)submitted=true;
                                }
                            }finally{
                                if(profile){long elapsed=System.nanoTime()-submitStarted;submitNanos[0]+=elapsed;
                                    if(round==0){OpticalFrameTimings.releaseSubmit.add(elapsed);OpticalFrameTimings.release.add(elapsed);}
                                    else{OpticalFrameTimings.consumerRecord.add(elapsed);OpticalFrameTimings.consumerSubmit.add(elapsed);}}
                                OpticalWallTimeline.leave(submitTiming);
                            }
                        },round->nativeStep.accept(round,slice));
                if(profile)OpticalFrameTimings.cudaStage.add(System.nanoTime()-cudaStarted-submitNanos[0]);
                // CUDA completion covers the last transfer; the last real Vulkan fence
                // proves every command buffer is reusable. No empty acquire submission.
                wallStep=OpticalWallTimeline.next(wallStep,VULKAN_COMMAND_REUSE_WAIT);
                long reuseStarted=profile?System.nanoTime():0L;
                check(vkWaitForFences(device(),fence,true,10_000_000_000L),"complete optical batch commands before reuse");
                submitted=false;externalOwned=true;externalOwnedBinary=true;
                if(profile)OpticalFrameTimings.consumerWait.add(System.nanoTime()-reuseStarted);
                wallStep=OpticalWallTimeline.next(wallStep,GPU_QUERY_RESOLVE);
                if(captures!=null)for(int round=0;round<rounds;round++){
                    var capture=captures[round];
                    if(capture!=null&&capture.resolve()){
                        if(round==0)OpticalFrameTimings.producerGpu.add(capture.totalNanos());
                        else OpticalFrameTimings.consumerGpu.add(capture.totalNanos());
                        OpticalFrameTimings.recordNamedGpuStage(round==0?"vulkan.shared.producer":"vulkan.shared.continuation",capture.totalNanos());
                    }
                }
                OpticalDetailedProfile.resolveDeferred();
            }catch(RuntimeException|Error failure){broken=true;throw failure;}
            finally{
                busy=false;
                if(captures!=null)for(var capture:captures)if(capture!=null)capture.close();
                if(profile){OpticalFrameTimings.exchange.add(System.nanoTime()-started);OpticalFrameTimings.rounds.add(rounds);}
            }
        }finally{OpticalWallTimeline.leave(wallStep);OpticalWallTimeline.leave(wallScope);}
    }

    /** Completes the deferred acquire before a Vulkan consumer outside this slot uses its buffer. */
    public void acquireForVulkanBlocking(){
        checkOpen();if(busy)throw new IllegalStateException("cannot acquire an active native ray slot");
        if(!externalOwned)return;
        int wallStep=OpticalWallTimeline.enter(VULKAN_CONSUMER_RECORD);boolean profile=OpticalFrameTimings.ENABLED;
        long started=profile?System.nanoTime():0L;busy=true;
        try(MemoryStack s=MemoryStack.stackPush()){
            if(pairedAcquire==null)pairedAcquire=command(s);
            check(vkResetFences(device(),fence),"reset deferred ray acquire fence");
            check(vkResetCommandBuffer(pairedAcquire,0),"reset deferred ray acquire");
            check(vkBeginCommandBuffer(pairedAcquire,VkCommandBufferBeginInfo.calloc(s).sType$Default()),"begin deferred ray acquire");
            barrier(pairedAcquire,VK11.VK_QUEUE_FAMILY_EXTERNAL,runtime.queueFamilyIndex(),0,
                    VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,s);
            check(vkEndCommandBuffer(pairedAcquire),"end deferred ray acquire");
            long submitStarted=profile?System.nanoTime():0L;
            wallStep=OpticalWallTimeline.next(wallStep,VULKAN_CONSUMER_SUBMIT);
            var deferred=VkSubmitInfo.calloc(1,s).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(s.longs(externalOwnedBinary?binarySecondToVulkan:pairedToVulkan))
                    .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)).pCommandBuffers(s.pointers(pairedAcquire.address()));
            if(!externalOwnedBinary)deferred.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pWaitSemaphoreValues(s.longs(pairedValue)).address());
            check(VulkanQueueHostSync.submit(runtime.frameQueue(),deferred,fence),"submit deferred ray acquire");
            submitted=true;long waitStarted=profile?System.nanoTime():0L;
            if(profile){OpticalFrameTimings.consumerRecord.add(waitStarted-started);OpticalFrameTimings.consumerCommands.add(submitStarted-started);
                OpticalFrameTimings.consumerSubmit.add(waitStarted-submitStarted);}
            wallStep=OpticalWallTimeline.next(wallStep,VULKAN_CONSUMER_FENCE_WAIT);
            check(vkWaitForFences(device(),fence,true,10_000_000_000L),"deferred ray acquire completion");
            if(profile)OpticalFrameTimings.consumerWait.add(System.nanoTime()-waitStarted);
            submitted=false;externalOwned=false;externalOwnedBinary=false;
        }catch(RuntimeException|Error failure){broken=true;throw failure;}
        finally{busy=false;OpticalWallTimeline.leave(wallStep);}
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
        exchangeBlocking(producer, nativeStep, vulkanStep, finalConsumer, false);
    }
    private void exchangeBlocking(Consumer<VkCommandBuffer> producer,
            Consumer<CudaMediumRuntime.Slice> nativeStep, Consumer<VkCommandBuffer> vulkanStep,
            BlockingConsumer finalConsumer, boolean queuedNativeStep) {
        acquireForVulkanBlocking();
        int wallScope = OpticalWallTimeline.enter(SHARED_EXCHANGE);
        int wallStep = OpticalWallTimeline.enter(VULKAN_RELEASE_RECORD);
        try {
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
            wallStep = OpticalWallTimeline.next(wallStep, VULKAN_RELEASE_SUBMIT);
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
            wallStep = OpticalWallTimeline.next(wallStep, CUDA_EXCHANGE);
            Runnable completeConsumer = () -> {
                int wallConsumerStep = OpticalWallTimeline.enter(VULKAN_CONSUMER_RECORD);
                try {
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
                wallConsumerStep = OpticalWallTimeline.next(wallConsumerStep, VULKAN_CONSUMER_SUBMIT);
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
                wallConsumerStep = OpticalWallTimeline.next(wallConsumerStep, VULKAN_CONSUMER_FENCE_WAIT);
                check(vkWaitForFences(device(), fence, true, 10_000_000_000L), "native ray completion");
                if(profile){long waited=System.nanoTime()-waitStarted;OpticalFrameTimings.consumerWait.add(waited);OpticalDetailedProfile.add("wall/consumerFenceNanos",waited);}
                submitted = false;
                } finally { OpticalWallTimeline.leave(wallConsumerStep); }
            };
            final long cudaStarted=profile?System.nanoTime():0L;
            if (finalConsumer == null && !queuedNativeStep) {
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
            wallStep = OpticalWallTimeline.next(wallStep, GPU_QUERY_RESOLVE);
            if(producerTiming!=null&&producerTiming.resolve()){OpticalFrameTimings.producerGpu.add(producerTiming.totalNanos());OpticalFrameTimings.recordNamedGpuStage("vulkan.shared.producer",producerTiming.totalNanos());OpticalDetailedProfile.add("producerGpu/totalNanos",producerTiming.totalNanos());}
            if(consumerTiming!=null&&consumerTiming.resolve()){OpticalFrameTimings.consumerGpu.add(consumerTiming.totalNanos());OpticalFrameTimings.recordNamedGpuStage("vulkan.shared.continuation",consumerTiming.totalNanos());OpticalDetailedProfile.add("continuationGpu/totalNanos",consumerTiming.totalNanos());}
            OpticalDetailedProfile.resolveDeferred();
        } catch (RuntimeException | Error failure) { broken = true; throw failure; }
        finally { busy = false;if(profile){OpticalFrameTimings.exchange.add(System.nanoTime()-exchangeStarted);OpticalFrameTimings.rounds.increment();} }

        } finally { OpticalWallTimeline.leave(wallStep); OpticalWallTimeline.leave(wallScope); }
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
        if(broken&&failure==null)try{OpticalDetailedProfile.discardDeferred();}
        catch(RuntimeException e){failure=e;}
        for (AutoCloseable resource : new AutoCloseable[]{importedBinarySecondToVulkan,importedBinarySecondToCuda,importedPairedToVulkan,importedPairedToCuda,importedToVulkan, importedToCuda, imported}) if (resource != null) try { resource.close(); }
        catch (Exception e) { if (failure == null) failure = new IllegalStateException("native ray CUDA cleanup", e); else failure.addSuppressed(e); }
        if (fence != 0) vkDestroyFence(device(), fence, null);
        if (commandPool != 0) vkDestroyCommandPool(device(), commandPool, null);
        if (toVulkan != 0) vkDestroySemaphore(device(), toVulkan, null);
        if (toCuda != 0) vkDestroySemaphore(device(), toCuda, null);
        if (pairedToVulkan != 0) vkDestroySemaphore(device(), pairedToVulkan, null);
        if (pairedToCuda != 0) vkDestroySemaphore(device(), pairedToCuda, null);
        if (binarySecondToVulkan != 0) vkDestroySemaphore(device(), binarySecondToVulkan, null);
        if (binarySecondToCuda != 0) vkDestroySemaphore(device(), binarySecondToCuda, null);
        if (buffer != 0) vkDestroyBuffer(device(), buffer, null);
        if (memory != 0) vkFreeMemory(device(), memory, null);
        closed = true;
        if (failure != null) throw failure;
    }
}
