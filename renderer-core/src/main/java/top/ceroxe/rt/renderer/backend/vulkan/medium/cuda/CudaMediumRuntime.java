package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline.Stage.*;


import java.lang.foreign.*;
import java.nio.file.Path;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaNativeApi.args;

/**
 * Thread-confined native medium context. Every evaluation completes before returning.
 * Owns no Vulkan objects; externally exported handles remain the caller's responsibility.
 * This is the GPU execution foundation, not the renderer's full medium-path integrator.
 */
public final class CudaMediumRuntime implements AutoCloseable {
    static final int EXTERNAL_SEMAPHORE_PARAMS_BYTES = 144;
    private final Thread owner = Thread.currentThread();
    private final Arena libraryArena = Arena.ofConfined();
    private final CudaNativeApi api;
    private final int deviceOrdinal;
    private int nativeDevice;
    private final long maxResidentBytes;
    private long residentBytes;
    private final List<NativeResource> resources = new ArrayList<>();
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment stream = MemorySegment.NULL;
    private static final int PROFILE_SLOTS=8;
    private final MemorySegment[] profileStartEvents=new MemorySegment[PROFILE_SLOTS];
    private final MemorySegment[] profileEndEvents=new MemorySegment[PROFILE_SLOTS];
    private final String[] pendingProfileStages=new String[PROFILE_SLOTS];
    private int pendingProfileCount;
    private MemorySegment module = MemorySegment.NULL;
    private MemorySegment segmentFunction = MemorySegment.NULL;
    private boolean closed;
    private boolean externalBatch;
    private String deviceName;
    private int computeMajor, computeMinor;
    // Entry points from one continuation source share PTX. Keep only the last successful
    // compilation, bounded to 8 MiB, within this thread-confined runtime/device/compiler.
    private String lastCompiledSource;
    private byte[] lastCompiledPtx;
    int diagnosticCompilationCount;
    // A continuation has several entry points. Keep its code module alive exactly while those
    // kernels are alive, instead of loading/JITing the same PTX once per entry point.
    private final Map<String, BatchModule> batchModules = new HashMap<>();
    int diagnosticBatchModuleLoads;
    int diagnosticLiveBatchModules;

    public static CudaMediumRuntime open(int deviceOrdinal) {
        return new CudaMediumRuntime(deviceOrdinal, CudaNativeApi.configuredNvrtcLibrary(), configuredLimit());
    }
    /** Explicit native library selection supports isolated application deployment and tests. */
    public static CudaMediumRuntime open(int deviceOrdinal, Path nvrtcLibrary) {
        return new CudaMediumRuntime(deviceOrdinal, Objects.requireNonNull(nvrtcLibrary, "nvrtcLibrary"), configuredLimit());
    }
    /** Per-session allocation/import registration cap; the frame owner should supply its device budget. */
    public static CudaMediumRuntime open(int deviceOrdinal, Path nvrtcLibrary, long maxResidentBytes) {
        return new CudaMediumRuntime(deviceOrdinal, Objects.requireNonNull(nvrtcLibrary, "nvrtcLibrary"), maxResidentBytes);
    }
    private static long configuredLimit() {
        return Long.parseLong(System.getProperty("top.ceroxe.rt.medium.cuda.maxResidentBytes", "536870912"));
    }
    private CudaMediumRuntime(int ordinal, Path nvrtcLibrary, long limit) {
        if (ordinal < 0) throw new IllegalArgumentException("CUDA device ordinal must be nonnegative");
        if (limit < 1) throw new IllegalArgumentException("CUDA resident byte limit must be positive");
        deviceOrdinal = ordinal;
        maxResidentBytes = limit;
        CudaNativeApi loaded;
        try { loaded = new CudaNativeApi(libraryArena, nvrtcLibrary); }
        catch (RuntimeException | Error e) { libraryArena.close(); throw e; }
        api = loaded;
        try (Arena scratch = Arena.ofConfined()) {
            api.checked("cuInit", args(JAVA_INT), 0);
            MemorySegment device = scratch.allocate(JAVA_INT);
            api.checked("cuDeviceGet", args(ADDRESS, JAVA_INT), device, ordinal);
            nativeDevice = device.get(JAVA_INT, 0);
            MemorySegment name = scratch.allocate(256);
            api.checked("cuDeviceGetName", args(ADDRESS, JAVA_INT, JAVA_INT), name, 256, nativeDevice);
            deviceName = name.getString(0);
            computeMajor = attribute(nativeDevice, 75, scratch);
            computeMinor = attribute(nativeDevice, 76, scratch);
            MemorySegment output = scratch.allocate(ADDRESS);
            api.checked("cuDevicePrimaryCtxRetain", args(ADDRESS, JAVA_INT), output, nativeDevice);
            context = output.get(ADDRESS, 0);
            inContext(() -> {
                api.checked("cuStreamCreate", args(ADDRESS, JAVA_INT), output, 1);
                stream = output.get(ADDRESS, 0);
                if (OpticalFrameTimings.ENABLED) {
                    MemorySegment event = scratch.allocate(ADDRESS);
                    for(int i=0;i<PROFILE_SLOTS;i++) {
                        api.checked("cuEventCreate", args(ADDRESS, JAVA_INT), event, 0);
                        profileStartEvents[i]=event.get(ADDRESS,0);
                        api.checked("cuEventCreate", args(ADDRESS, JAVA_INT), event, 0);
                        profileEndEvents[i]=event.get(ADDRESS,0);
                    }
                }
                byte[] ptx = compile(CudaMediumSource.source());
                MemorySegment image = scratch.allocateFrom(JAVA_BYTE, ptx);
                api.checked("cuModuleLoadData", args(ADDRESS, ADDRESS), output, image);
                module = output.get(ADDRESS, 0);
                api.checked("cuModuleGetFunction", args(ADDRESS, ADDRESS, ADDRESS), output, module,
                        scratch.allocateFrom("rtr_luxcore_segment"));
                segmentFunction = output.get(ADDRESS, 0);
            });
        } catch (RuntimeException | Error e) {
            try { close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private int attribute(int device, int attribute, Arena arena) {
        MemorySegment output = arena.allocate(JAVA_INT);
        api.checked("cuDeviceGetAttribute", args(ADDRESS, JAVA_INT, JAVA_INT), output, attribute, device);
        return output.get(JAVA_INT, 0);
    }
    public String deviceName() { checkOpen(); return deviceName; }
    public String computeCapability() { checkOpen(); return computeMajor + "." + computeMinor; }
    public long residentBytes() { checkOpen(); return residentBytes; }
    public long maxResidentBytes() { checkOpen(); return maxResidentBytes; }
    public byte[] deviceUuid() {
        checkOpen();
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment uuid = scratch.allocate(16);
            api.checked("cuDeviceGetUuid_v2", args(ADDRESS, JAVA_INT), uuid, nativeDevice);
            return uuid.toArray(JAVA_BYTE);
        }
    }
    private byte[] compile(String source) {
        if (source.equals(lastCompiledSource)) return lastCompiledPtx;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment programSlot = scratch.allocate(ADDRESS);
            api.checked("nvrtcCreateProgram", args(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    programSlot, scratch.allocateFrom(source), scratch.allocateFrom("rtr_luxcore_segment.cu"),
                    0, MemorySegment.NULL, MemorySegment.NULL);
            try {
                MemorySegment program = programSlot.get(ADDRESS, 0);
                String[] options = {"--gpu-architecture=compute_" + computeMajor + computeMinor,
                        "--std=c++17", "--device-as-default-execution-space", "--fmad=false"};
                MemorySegment optionPointers = scratch.allocate(ADDRESS, options.length);
                for (int i = 0; i < options.length; i++)
                    optionPointers.setAtIndex(ADDRESS, i, scratch.allocateFrom(options[i]));
                int status = api.call("nvrtcCompileProgram", args(ADDRESS, JAVA_INT, ADDRESS),
                        program, options.length, optionPointers);
                diagnosticCompilationCount++;
                if (status != 0) {
                    MemorySegment size = scratch.allocate(JAVA_LONG);
                    api.checked("nvrtcGetProgramLogSize", args(ADDRESS, ADDRESS), program, size);
                    MemorySegment log = scratch.allocate(Math.max(1, size.get(JAVA_LONG, 0)));
                    api.checked("nvrtcGetProgramLog", args(ADDRESS, ADDRESS), program, log);
                    throw new IllegalStateException("NVRTC medium compilation failed (" + status + "): " + log.getString(0));
                }
                MemorySegment size = scratch.allocate(JAVA_LONG);
                api.checked("nvrtcGetPTXSize", args(ADDRESS, ADDRESS), program, size);
                MemorySegment image = scratch.allocate(size.get(JAVA_LONG, 0));
                api.checked("nvrtcGetPTX", args(ADDRESS, ADDRESS), program, image);
                byte[] ptx = image.toArray(JAVA_BYTE);
                if ((long) source.length() * 2 + ptx.length <= 8L * 1024 * 1024) {
                    lastCompiledSource = source;
                    lastCompiledPtx = ptx;
                } else {
                    lastCompiledSource = null;
                    lastCompiledPtx = null;
                }
                return ptx;
            } finally { api.checked("nvrtcDestroyProgram", args(ADDRESS), programSlot); }
        }
    }

    /** Device-local storage. Host transfer methods are for asset upload/readback, not per-ray dispatch. */
    public DeviceMemory allocate(long bytes) {
        checkOpen(); if (bytes < 1) throw new IllegalArgumentException("allocation must be positive");
        checkBudget(bytes);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment output = scratch.allocate(JAVA_LONG);
            inContext(() -> api.checked("cuMemAlloc_v2", args(ADDRESS, JAVA_LONG), output, bytes));
            residentBytes += bytes;
            return own(new DeviceMemory(output.get(JAVA_LONG, 0), bytes, MemorySegment.NULL));
        }
    }
    /** Imports an OPAQUE_WIN32 Vulkan allocation; does not close the supplied Win32 handle. */
    public DeviceMemory importWin32Memory(long handle, long allocationBytes, long offset, long bytes, boolean dedicated) {
        checkOpen();
        if (handle == 0 || allocationBytes < 1 || offset < 0 || bytes < 1
                || Math.addExact(offset, bytes) > allocationBytes)
            throw new IllegalArgumentException("invalid external memory range");
        checkBudget(allocationBytes);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment desc = scratch.allocate(104, 8);
            desc.set(JAVA_INT, 0, 2); desc.set(JAVA_LONG, 8, handle);
            desc.set(JAVA_LONG, 24, allocationBytes); desc.set(JAVA_INT, 32, dedicated ? 1 : 0);
            MemorySegment imported = scratch.allocate(ADDRESS), pointer = scratch.allocate(JAVA_LONG);
            MemorySegment map = scratch.allocate(88, 8);
            map.set(JAVA_LONG, 0, offset); map.set(JAVA_LONG, 8, bytes);
            inContext(() -> {
                api.checked("cuImportExternalMemory", args(ADDRESS, ADDRESS), imported, desc);
                try {
                    api.checked("cuExternalMemoryGetMappedBuffer", args(ADDRESS, ADDRESS, ADDRESS),
                            pointer, imported.get(ADDRESS, 0), map);
                } catch (RuntimeException e) {
                    try { api.checked("cuDestroyExternalMemory", args(ADDRESS), imported.get(ADDRESS, 0)); }
                    catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
                    throw e;
                }
            });
            residentBytes += allocationBytes;
            return own(new DeviceMemory(pointer.get(JAVA_LONG, 0), bytes, imported.get(ADDRESS, 0), allocationBytes));
        }
    }
    public ExternalSemaphore importWin32Semaphore(long handle) {
        return importWin32Semaphore(handle,false);
    }
    public ExternalSemaphore importWin32TimelineSemaphore(long handle) {
        return importWin32Semaphore(handle,true);
    }
    private ExternalSemaphore importWin32Semaphore(long handle,boolean timeline) {
        checkOpen(); if (handle == 0) throw new IllegalArgumentException("external semaphore handle must be nonzero");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment desc = scratch.allocate(96, 8), output = scratch.allocate(ADDRESS);
            desc.set(JAVA_INT, 0, timeline?10:2); desc.set(JAVA_LONG, 8, handle);
            inContext(() -> api.checked("cuImportExternalSemaphore", args(ADDRESS, ADDRESS), output, desc));
            return own(new ExternalSemaphore(output.get(ADDRESS, 0)));
        }
    }
    /** Vulkan must submit its matching signal before this blocking wait is called. */
    public void waitExternalBlocking(ExternalSemaphore semaphore) { external(semaphore, false); }
    /** Completes CUDA work and the external signal before Vulkan consumes the allocation. */
    public void signalExternalBlocking(ExternalSemaphore semaphore) { external(semaphore, true); }
    /**
     * One blocking ownership cycle on the owned stream. Stream ordering connects the external
     * wait, kernels and signal; their intermediate host waits are redundant. A download still
     * completes queued work before exposing host data. Success and failure both drain before
     * returning, so shared storage and native scratch never outlive the blocking caller.
     */
    public void exchangeExternalBlocking(ExternalSemaphore wait, ExternalSemaphore signal, Runnable nativeStep) {
        exchangeExternalWithCompletionBlocking(wait, signal, nativeStep,
                () -> inContext(this::synchronizeStream));
    }
    /**
     * The shared-frame owner supplies a blocking Vulkan fence wait whose submission consumes
     * signal. That fence already proves completion of this stream's preceding CUDA work, so
     * a separate host stream wait before submitting the consumer would serialize it twice.
     * On callback failure, drain CUDA before the caller can release imported storage.
     */
    void exchangeExternalWithCompletionBlocking(ExternalSemaphore wait, ExternalSemaphore signal,
            Runnable nativeStep, Runnable completeConsumer) {
        int wallScope = OpticalWallTimeline.enter(CUDA_EXCHANGE);
        try {
        checkOpen(); Objects.requireNonNull(nativeStep, "nativeStep");
        Objects.requireNonNull(completeConsumer, "completeConsumer");
        requireOwned(wait); requireOwned(signal); wait.checkAlive(); signal.checkAlive();
        boolean enclosingBatch = externalBatch;
        // A legacy callback may exchange a second allocation. Complete the outer ownership
        // wait before that nested Vulkan submission, then restore its batching scope.
        if (enclosingBatch) inContext(this::synchronizeStream);
        externalBatch = true;
        try {
            waitExternalBlocking(wait);
            int callbackTiming = OpticalWallTimeline.enter(CUDA_NATIVE_CALLBACK);
            try { nativeStep.run(); } finally { OpticalWallTimeline.leave(callbackTiming); }
            signalExternalBlocking(signal);
            int completionTiming = OpticalWallTimeline.enter(CUDA_COMPLETION_CALLBACK);
            try { completeConsumer.run(); } finally { OpticalWallTimeline.leave(completionTiming); }
            resolveProfiledKernel();
        } catch (RuntimeException | Error failure) {
            try { inContext(this::synchronizeStream); }
            catch (RuntimeException | Error drainFailure) { failure.addSuppressed(drainFailure); }
            pendingProfileCount = 0;
            throw failure;
        } finally {
            externalBatch = enclosingBatch;
        }

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    private void synchronizeStream() {
        int wallScope = OpticalWallTimeline.enter(CUDA_STREAM_SYNCHRONIZE);
        try { api.checked("cuStreamSynchronize", args(ADDRESS), stream);
        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    private void resolveProfiledKernel() {
        int wallScope = OpticalWallTimeline.enter(CUDA_KERNEL_EVENT_RESOLVE);
        try {
        int count=pendingProfileCount;pendingProfileCount=0;
        if(count==0)return;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment elapsedMillis = scratch.allocate(JAVA_FLOAT);
            for(int i=0;i<count;i++) {
            final int profileReadIndex=i;
            inContext(() -> api.checked("cuEventElapsedTime", args(ADDRESS, ADDRESS, ADDRESS),
                    elapsedMillis, profileStartEvents[profileReadIndex], profileEndEvents[profileReadIndex]));
            float millis = elapsedMillis.get(JAVA_FLOAT, 0);
            if (!Float.isFinite(millis) || millis < 0) throw new IllegalStateException("CUDA event returned invalid duration");
            OpticalFrameTimings.recordCudaKernel(pendingProfileStages[i], Math.round(millis * 1_000_000.0));
            pendingProfileStages[i]=null;
            }
        }

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    /** Two ownership cycles without an intermediate host counter decision or Vulkan fence. */
    void exchangeExternalPairWithCompletionBlocking(ExternalSemaphore wait, ExternalSemaphore signal,
            long firstValue,long secondValue,
            Runnable firstStep, Runnable submitMiddle, Runnable secondStep, Runnable completeConsumer) {
        int wallScope = OpticalWallTimeline.enter(CUDA_EXCHANGE);
        try {
        checkOpen();requireOwned(wait);requireOwned(signal);wait.checkAlive();signal.checkAlive();
        if(firstValue<1||secondValue<=firstValue)throw new IllegalArgumentException("ordered timeline values required");
        Objects.requireNonNull(firstStep);Objects.requireNonNull(submitMiddle);
        Objects.requireNonNull(secondStep);Objects.requireNonNull(completeConsumer);
        if(externalBatch)throw new IllegalStateException("queued ownership pairs cannot nest");
        externalBatch=true;
        try {
            external(wait,false,firstValue);firstStep.run();external(signal,true,firstValue);
            // Native RT epoch tests require completion before the Vulkan continuation is
            // submitted on this interop path. Keep that proven boundary; batch the host
            // statistics and eliminate the intervening Vulkan completion submission.
            inContext(this::synchronizeStream);
            submitMiddle.run();
            external(wait,false,secondValue);secondStep.run();external(signal,true,secondValue);
            completeConsumer.run();
            // Complete pinned host transfers before reading batch statistics.
            inContext(this::synchronizeStream);
            resolveProfiledKernel();
        }catch(RuntimeException|Error failure){
            try{inContext(this::synchronizeStream);}catch(RuntimeException|Error drain){failure.addSuppressed(drain);}
            pendingProfileCount=0;
            throw failure;
        }finally{externalBatch=false;}

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    /** NVIDIA's binary external-semaphore chain, with one completion boundary for both rounds. */
    void exchangeExternalBinaryPairWithCompletionBlocking(ExternalSemaphore firstWait,ExternalSemaphore firstSignal,
            ExternalSemaphore secondWait,ExternalSemaphore secondSignal,
            Runnable firstStep,Runnable submitMiddle,Runnable secondStep,Runnable completeConsumer) {
        int wallScope=OpticalWallTimeline.enter(CUDA_EXCHANGE);
        try {
            checkOpen();
            requireOwned(firstWait);firstWait.checkAlive();requireOwned(firstSignal);firstSignal.checkAlive();
            requireOwned(secondWait);secondWait.checkAlive();requireOwned(secondSignal);secondSignal.checkAlive();
            Objects.requireNonNull(firstStep);Objects.requireNonNull(submitMiddle);
            Objects.requireNonNull(secondStep);Objects.requireNonNull(completeConsumer);
            if(externalBatch)throw new IllegalStateException("queued ownership pairs cannot nest");
            externalBatch=true;
            try {
                external(firstWait,false);firstStep.run();external(firstSignal,true);
                submitMiddle.run();
                external(secondWait,false);secondStep.run();external(secondSignal,true);
                completeConsumer.run();
                inContext(this::synchronizeStream);
                resolveProfiledKernel();
            }catch(RuntimeException|Error failure){
                try{inContext(this::synchronizeStream);}catch(RuntimeException|Error drain){failure.addSuppressed(drain);}
                pendingProfileCount=0;throw failure;
            }finally{externalBatch=false;}
        }finally{OpticalWallTimeline.leave(wallScope);}
    }
    private void synchronizeUnlessBatched() { if (!externalBatch) synchronizeStream(); }
    private void external(ExternalSemaphore semaphore, boolean signal) {
        external(semaphore,signal,0L);
    }
    static MemorySegment externalSemaphoreParams(Arena scratch, long value) {
        MemorySegment params = scratch.allocate(EXTERNAL_SEMAPHORE_PARAMS_BYTES, 8);
        params.fill((byte) 0);
        params.set(JAVA_LONG, 0, value);
        return params;
    }
    private void external(ExternalSemaphore semaphore, boolean signal,long value) {
        int wallScope = OpticalWallTimeline.enter(signal ? CUDA_SEMAPHORE_SIGNAL_ENQUEUE : CUDA_SEMAPHORE_WAIT_ENQUEUE);
        try {
        checkOpen(); requireOwned(semaphore); semaphore.checkAlive();
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment handles = scratch.allocateFrom(ADDRESS, semaphore.handle);
            MemorySegment params = externalSemaphoreParams(scratch, value);
            inContext(() -> {
                api.checked(signal ? "cuSignalExternalSemaphoresAsync" : "cuWaitExternalSemaphoresAsync",
                        args(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), handles, params, 1, stream);
                synchronizeUnlessBatched();
            });
        }

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    public void evaluateSegmentsBlocking(Slice input, Slice output, int count) {
        checkOpen(); requireSlice(input, CudaMediumAbi.inputBytes(count));
        requireSlice(output, CudaMediumAbi.outputBytes(count));
        long inputEnd = Math.addExact(input.address(), CudaMediumAbi.inputBytes(count));
        long outputEnd = Math.addExact(output.address(), CudaMediumAbi.outputBytes(count));
        if (input.address() < outputEnd && output.address() < inputEnd)
            throw new IllegalArgumentException("segment input and output must not overlap");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment parameters = scratch.allocate(ADDRESS, 3);
            parameters.setAtIndex(ADDRESS, 0, scratch.allocateFrom(JAVA_LONG, input.address()));
            parameters.setAtIndex(ADDRESS, 1, scratch.allocateFrom(JAVA_LONG, output.address()));
            parameters.setAtIndex(ADDRESS, 2, scratch.allocateFrom(JAVA_INT, count));
            inContext(() -> {
                api.checked("cuLaunchKernel", args(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                        segmentFunction, (count - 1) / 256 + 1, 1, 1, 256, 1, 1, 0,
                        stream, parameters, MemorySegment.NULL);
                synchronizeUnlessBatched();
            });
        }
    }
    /** Compiles an internal continuation wrapper together with the pinned upstream medium implementation. */
    public BatchKernel compileBatchKernel(String wrapper, String entryPoint) {
        checkOpen(); Objects.requireNonNull(wrapper, "wrapper");
        if (entryPoint == null || !entryPoint.matches("[A-Za-z_][A-Za-z_0-9]*"))
            throw new IllegalArgumentException("invalid kernel entry point");
        String source = CudaMediumSource.source() + "\n" + wrapper;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment output = scratch.allocate(ADDRESS);
            BatchModule existing = batchModules.get(source);
            final BatchModule[] selected = {existing};
            final MemorySegment[] function = new MemorySegment[1];
            byte[] ptx = existing == null ? compile(source) : null;
            inContext(() -> {
                if (selected[0] == null) {
                    api.checked("cuModuleLoadData", args(ADDRESS, ADDRESS), output, scratch.allocateFrom(JAVA_BYTE, ptx));
                    selected[0] = new BatchModule(source, output.get(ADDRESS, 0));
                    diagnosticBatchModuleLoads++;
                }
                try {
                    api.checked("cuModuleGetFunction", args(ADDRESS, ADDRESS, ADDRESS), output, selected[0].handle,
                            scratch.allocateFrom(entryPoint));
                    function[0] = output.get(ADDRESS, 0);
                } catch (RuntimeException | Error e) {
                    // A failed lookup cannot retire another entry point's live code.
                    if (existing == null) try { api.checked("cuModuleUnload", args(ADDRESS), selected[0].handle); }
                    catch (RuntimeException | Error cleanup) { e.addSuppressed(cleanup); }
                    throw e;
                }
            });
            BatchModule module = selected[0];
            if (existing == null) {
                batchModules.put(source, module);
                diagnosticLiveBatchModules++;
            }
            module.references++;
            return own(new BatchKernel(module, function[0],"entry."+entryPoint));
        }
    }
    /** Internal batch ABI is (record pointer, count); completion includes the owning CUDA stream. */
    public void launchBlocking(BatchKernel kernel, Slice records, int count, int recordBytes) {
        launchBlocking(kernel, records, count, recordBytes, OpticalFrameTimings.ENABLED?kernel.profileStage:null);
    }
    public void launchBlocking(BatchKernel kernel, Slice records, int count, int recordBytes, String profileStage) {
        int wallScope = OpticalWallTimeline.enter(CUDA_KERNEL_ENQUEUE);
        try {
        checkOpen(); requireOwned(kernel); kernel.checkAlive();
        if (count < 1 || recordBytes < 1) throw new IllegalArgumentException("positive batch dimensions required");
        requireSlice(records, Math.multiplyExact((long) count, recordBytes));
        boolean profile = OpticalFrameTimings.ENABLED && profileStage != null;
        if (profile && (profileStage.isBlank() || pendingProfileCount==PROFILE_SLOTS))
            throw new IllegalStateException("CUDA profile event slots are full or its stage name is blank");
        final int profileSlot=pendingProfileCount;
        if (profile) {pendingProfileStages[profileSlot]=profileStage;pendingProfileCount++;}
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment parameters = scratch.allocate(ADDRESS, 2);
            parameters.setAtIndex(ADDRESS, 0, scratch.allocateFrom(JAVA_LONG, records.address()));
            parameters.setAtIndex(ADDRESS, 1, scratch.allocateFrom(JAVA_INT, count));
            inContext(() -> {
                if (profile) api.checked("cuEventRecord", args(ADDRESS, ADDRESS), profileStartEvents[profileSlot], stream);
                api.checked("cuLaunchKernel", args(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                        kernel.function, (count - 1) / 256 + 1, 1, 1, 256, 1, 1, 0,
                        stream, parameters, MemorySegment.NULL);
                if (profile) api.checked("cuEventRecord", args(ADDRESS, ADDRESS), profileEndEvents[profileSlot], stream);
                synchronizeUnlessBatched();
            });
            if (profile && !externalBatch) resolveProfiledKernel();
        } catch (RuntimeException | Error failure) {
            if (profile) pendingProfileCount=0;
            throw failure;
        }

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    /**
     * Internal camera batch ABI: (records, count, immutable constants, tile output).
     * The output slice starts at the tile's first pixel and contains count RGBA values.
     */
    public void launchBlocking(BatchKernel kernel, Slice records, int count, int recordBytes,
                               Slice constants, Slice output) {
        checkOpen(); requireOwned(kernel); kernel.checkAlive();
        if (count < 1 || recordBytes != MediumRayQueryAbi.RECORD_BYTES)
            throw new IllegalArgumentException("camera batch requires positive count and the ray-query record ABI");
        requireSlice(records, Math.multiplyExact((long) count, recordBytes));
        requireSlice(constants, CudaCameraMediumAbi.CONSTANT_BYTES);
        requireSlice(output, CudaCameraMediumAbi.outputBytes(count));
        if ((records.address() & 15) != 0 || (constants.address() & 3) != 0 || (output.address() & 15) != 0)
            throw new IllegalArgumentException("camera records/output require 16-byte alignment and constants require 4-byte alignment");
        requireDisjoint(records, constants); requireDisjoint(records, output); requireDisjoint(constants, output);
        launchFourArgumentBlocking(kernel, records, count, constants, output);
    }
    /** Resolves native attenuation from the exact 208-byte production primary payload, without a second trace. */
    public void launchPrimaryBlocking(BatchKernel kernel, Slice primary, int count, Slice constants, Slice output) {
        checkOpen(); requireOwned(kernel); kernel.checkAlive();
        requireSlice(primary, CudaPrimaryMediumAbi.primaryBytes(count));
        requireSlice(constants, CudaPrimaryMediumAbi.CONSTANT_BYTES);
        requireSlice(output, CudaPrimaryMediumAbi.outputBytes(count));
        if ((primary.address() & 15) != 0 || (constants.address() & 3) != 0 || (output.address() & 15) != 0)
            throw new IllegalArgumentException("primary/output require 16-byte alignment and constants require 4-byte alignment");
        requireDisjoint(primary, constants); requireDisjoint(primary, output); requireDisjoint(constants, output);
        launchFourArgumentBlocking(kernel, primary, count, constants, output);
    }
    /** Produces transmittance and API-analytic emitted radiance from the same production primary payload. */
    public void launchPrimaryEmissionBlocking(BatchKernel kernel, Slice primary, int count, Slice constants, Slice output) {
        checkOpen(); requireOwned(kernel); kernel.checkAlive();
        requireSlice(primary, CudaPrimaryEmissionAbi.primaryBytes(count));
        requireSlice(constants, CudaPrimaryEmissionAbi.CONSTANT_BYTES);
        requireSlice(output, CudaPrimaryEmissionAbi.outputBytes(count));
        if ((primary.address() & 15) != 0 || (constants.address() & 3) != 0 || (output.address() & 15) != 0)
            throw new IllegalArgumentException("primary emission/output require 16-byte alignment and constants require 4-byte alignment");
        requireDisjoint(primary, constants); requireDisjoint(primary, output); requireDisjoint(constants, output);
        launchFourArgumentBlocking(kernel, primary, count, constants, output);
    }
    private void launchFourArgumentBlocking(BatchKernel kernel, Slice records, int count, Slice constants, Slice output) {
        boolean profile=OpticalFrameTimings.ENABLED;
        if(profile&&pendingProfileCount==PROFILE_SLOTS)throw new IllegalStateException("CUDA profile event slots are full");
        int wallScope=OpticalWallTimeline.enter(CUDA_KERNEL_ENQUEUE);
        final int profileSlot=pendingProfileCount;
        if(profile){pendingProfileStages[profileSlot]=kernel.profileStage;pendingProfileCount++;}
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment parameters = scratch.allocate(ADDRESS, 4);
            parameters.setAtIndex(ADDRESS, 0, scratch.allocateFrom(JAVA_LONG, records.address()));
            parameters.setAtIndex(ADDRESS, 1, scratch.allocateFrom(JAVA_INT, count));
            parameters.setAtIndex(ADDRESS, 2, scratch.allocateFrom(JAVA_LONG, constants.address()));
            parameters.setAtIndex(ADDRESS, 3, scratch.allocateFrom(JAVA_LONG, output.address()));
            inContext(() -> {
                if(profile)api.checked("cuEventRecord",args(ADDRESS,ADDRESS),profileStartEvents[profileSlot],stream);
                api.checked("cuLaunchKernel", args(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                        kernel.function, (count - 1) / 256 + 1, 1, 1, 256, 1, 1, 0,
                        stream, parameters, MemorySegment.NULL);
                if(profile)api.checked("cuEventRecord",args(ADDRESS,ADDRESS),profileEndEvents[profileSlot],stream);
                synchronizeUnlessBatched();
            });
            if(profile&&!externalBatch)resolveProfiledKernel();
        }catch(RuntimeException|Error failure){if(profile)pendingProfileCount=0;throw failure;}
        finally{OpticalWallTimeline.leave(wallScope);}
    }
    private static void requireDisjoint(Slice first, Slice second) {
        if (first.address() < Math.addExact(second.address(), second.bytes)
                && second.address() < Math.addExact(first.address(), first.bytes))
            throw new IllegalArgumentException("batch records, constants and output must not overlap");
    }
    public void upload(Slice destination, MemorySegment source) {
        int wallScope = OpticalWallTimeline.enter(CUDA_UPLOAD);
        try {
        checkOpen(); Objects.requireNonNull(source); requireSlice(destination, source.byteSize());
        if (!source.isNative()) throw new IllegalArgumentException("upload source must be native memory");
        inContext(() -> {
            if (externalBatch) synchronizeStream();
            api.checked("cuMemcpyHtoD_v2", args(JAVA_LONG, ADDRESS, JAVA_LONG),
                    destination.address(), source, source.byteSize());
            // The synchronous copy uses the default stream, whereas kernels use our
            // nonblocking stream. Waiting on the latter does not complete the upload.
            int contextTiming = OpticalWallTimeline.enter(CUDA_CONTEXT_SYNCHRONIZE);
            try { api.checked("cuCtxSynchronize", args()); }
            finally { OpticalWallTimeline.leave(contextTiming); }
        });

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    /** Page-locked host transfer target; its contents are readable only after the owning stream completes. */
    public HostMemory allocateHost(long bytes) {
        checkOpen();if(bytes<1)throw new IllegalArgumentException("positive pinned host size required");
        try(Arena scratch=Arena.ofConfined()) {
            var out=scratch.allocate(ADDRESS);
            inContext(()->api.checked("cuMemHostAlloc",args(ADDRESS,JAVA_LONG,JAVA_INT),out,bytes,0));
            return own(new HostMemory(out.get(ADDRESS,0),bytes));
        }
    }

    /** Enqueues the transfer before the external completion semaphore, without a CPU stream wait. */
    public void downloadAsync(Slice source,HostMemory destination) {
        int wallScope = OpticalWallTimeline.enter(CUDA_DOWNLOAD_ASYNC_ENQUEUE);
        try {
        checkOpen();requireOwned(destination);destination.checkAlive();requireSlice(source,destination.bytes);
        if(!externalBatch)throw new IllegalStateException("asynchronous readback requires an external completion batch");
        inContext(()->api.checked("cuMemcpyDtoHAsync_v2",args(ADDRESS,JAVA_LONG,JAVA_LONG,ADDRESS),
                destination.pointer,source.address(),destination.bytes,stream));

        } finally { OpticalWallTimeline.leave(wallScope); }
    }

    public void download(Slice source, MemorySegment destination) {
        int wallScope = OpticalWallTimeline.enter(CUDA_DOWNLOAD);
        try {
        checkOpen(); Objects.requireNonNull(destination); requireSlice(source, destination.byteSize());
        if (!destination.isNative() || destination.isReadOnly())
            throw new IllegalArgumentException("readback destination must be writable native memory");
        inContext(() -> {
            if (externalBatch) synchronizeStream();
            api.checked("cuMemcpyDtoH_v2", args(ADDRESS, JAVA_LONG, JAVA_LONG),
                    destination, source.address(), destination.byteSize());
        });

        } finally { OpticalWallTimeline.leave(wallScope); }
    }
    private void requireSlice(Slice slice, long bytes) {
        Objects.requireNonNull(slice); requireOwned(slice.memory); slice.memory.checkAlive();
        if (bytes < 1 || slice.bytes < bytes) throw new IllegalArgumentException("device slice is too small");
    }
    private void requireOwned(NativeResource resource) {
        if (resource == null || resource.runtime() != this) throw new IllegalArgumentException("resource belongs to another CUDA runtime");
    }
    private <T extends NativeResource> T own(T resource) { resources.add(resource); return resource; }
    private void checkBudget(long bytes) {
        if (bytes > maxResidentBytes - residentBytes)
            throw new IllegalStateException("CUDA medium device budget exceeded: resident=" + residentBytes
                    + ", requested=" + bytes + ", limit=" + maxResidentBytes);
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("CUDA medium runtime must be used by its owning thread");
    }
    private void checkOpen() { checkOwner(); if (closed) throw new IllegalStateException("CUDA medium runtime is closed"); }
    private void inContext(Runnable action) {
        checkOwner();
        int pushTiming = OpticalWallTimeline.enter(CUDA_CONTEXT_PUSH);
        try { api.checked("cuCtxPushCurrent_v2", args(ADDRESS), context); }
        finally { OpticalWallTimeline.leave(pushTiming); }
        try { action.run(); }
        finally {
            try (Arena scratch = Arena.ofConfined()) {
                int popTiming = OpticalWallTimeline.enter(CUDA_CONTEXT_POP);
                try { api.checked("cuCtxPopCurrent_v2", args(ADDRESS), scratch.allocate(ADDRESS)); }
                finally { OpticalWallTimeline.leave(popTiming); }
            }
        }
    }
    @Override public void close() {
        checkOwner(); if (closed) return;
        if (externalBatch) throw new IllegalStateException("cannot close a CUDA runtime during an ownership batch");
        RuntimeException failure = null;
        if (!context.equals(MemorySegment.NULL)) {
            try { inContext(() -> api.checked("cuCtxSynchronize", args())); }
            catch (RuntimeException e) { failure = e; }
            for (int i = resources.size() - 1; i >= 0; i--) {
                try { resources.get(i).close(); }
                catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            try { inContext(() -> {
                RuntimeException nativeFailure = null;
                for(int i=0;i<PROFILE_SLOTS;i++)for(MemorySegment event:new MemorySegment[]{profileEndEvents[i],profileStartEvents[i]}) {
                    if(event!=null&&!event.equals(MemorySegment.NULL))try {api.checked("cuEventDestroy_v2",args(ADDRESS),event);}
                    catch(RuntimeException e){if(nativeFailure==null)nativeFailure=e;else nativeFailure.addSuppressed(e);}
                }
                if (!module.equals(MemorySegment.NULL)) try { api.checked("cuModuleUnload", args(ADDRESS), module); }
                catch (RuntimeException e) { if (nativeFailure == null) nativeFailure = e; else nativeFailure.addSuppressed(e); }
                if (!stream.equals(MemorySegment.NULL)) try { api.checked("cuStreamDestroy_v2", args(ADDRESS), stream); }
                catch (RuntimeException e) { if (nativeFailure == null) nativeFailure = e; else nativeFailure.addSuppressed(e); }
                if (nativeFailure != null) throw nativeFailure;
            }); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            try { api.checked("cuDevicePrimaryCtxRelease_v2", args(JAVA_INT), nativeDevice); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        closed = true;
        lastCompiledSource = null;
        lastCompiledPtx = null;
        try { libraryArena.close(); }
        catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
    private abstract class NativeResource implements AutoCloseable {
        boolean released;
        CudaMediumRuntime runtime() { return CudaMediumRuntime.this; }
        void checkAlive() { checkOpen(); if (released) throw new IllegalStateException("CUDA resource is closed"); }
        abstract void destroy();
        @Override public final void close() {
            checkOwner(); if (released) return;
            if (externalBatch) inContext(CudaMediumRuntime.this::synchronizeStream);
            released = true;
            resources.remove(this);
            inContext(this::destroy);
        }
    }
    public final class DeviceMemory extends NativeResource {
        private final long address, bytes, chargedBytes;
        private final MemorySegment imported;
        private DeviceMemory(long address, long bytes, MemorySegment imported) {
            this(address, bytes, imported, bytes);
        }
        private DeviceMemory(long address, long bytes, MemorySegment imported, long chargedBytes) {
            this.address = address; this.bytes = bytes; this.imported = imported; this.chargedBytes = chargedBytes;
        }
        public long byteSize() { checkAlive(); return bytes; }
        public Slice slice(long offset, long length) {
            checkAlive();
            if (offset < 0 || length < 1 || Math.addExact(offset, length) > bytes)
                throw new IllegalArgumentException("device slice out of bounds");
            return new Slice(this, offset, length);
        }
        @Override void destroy() {
            residentBytes -= chargedBytes;
            RuntimeException failure = null;
            try { api.checked("cuMemFree_v2", args(JAVA_LONG), address); }
            catch (RuntimeException e) { failure = e; }
            if (!imported.equals(MemorySegment.NULL)) try { api.checked("cuDestroyExternalMemory", args(ADDRESS), imported); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw failure;
        }
    }
    public static final class Slice {
        private final DeviceMemory memory;
        private final long offset, bytes;
        private Slice(DeviceMemory memory, long offset, long bytes) { this.memory = memory; this.offset = offset; this.bytes = bytes; }
        public long deviceAddress() { memory.checkAlive(); return address(); }
        public long byteSize() { memory.checkAlive(); return bytes; }
        /** Bounded view for a camera output tile inside a shared full-frame allocation. */
        public Slice slice(long relativeOffset, long length) {
            memory.checkAlive();
            if (relativeOffset < 0 || length < 1 || relativeOffset > bytes - length)
                throw new IllegalArgumentException("device subslice out of bounds");
            return new Slice(memory, Math.addExact(offset, relativeOffset), length);
        }
        private long address() { return Math.addExact(memory.address, offset); }
    }
    public final class HostMemory extends NativeResource {
        private final MemorySegment pointer,segment;
        private final long bytes;
        private HostMemory(MemorySegment pointer,long bytes) {
            this.pointer=pointer;this.bytes=bytes;segment=pointer.reinterpret(bytes);
        }
        public int getInt(long offset) {checkAlive();return segment.get(JAVA_INT,offset);}
        public long byteSize(){checkAlive();return bytes;}
        public MemorySegment segment(){checkAlive();return segment.asReadOnly();}
        @Override void destroy() {api.checked("cuMemFreeHost",args(ADDRESS),pointer);}
    }
    public final class ExternalSemaphore extends NativeResource {
        private final MemorySegment handle;
        private ExternalSemaphore(MemorySegment handle) { this.handle = handle; }
        @Override void destroy() { api.checked("cuDestroyExternalSemaphore", args(ADDRESS), handle); }
    }
    public final class BatchKernel extends NativeResource {
        private final BatchModule module;
        private final MemorySegment function;
        private final String profileStage;
        private BatchKernel(BatchModule module, MemorySegment function,String profileStage) {
            this.module = module; this.function = function;this.profileStage=profileStage;
        }
        @Override void destroy() {
            if (--module.references == 0) {
                batchModules.remove(module.source, module);
                diagnosticLiveBatchModules--;
                api.checked("cuModuleUnload", args(ADDRESS), module.handle);
            }
        }
    }
    private static final class BatchModule {
        private final String source;
        private final MemorySegment handle;
        private int references;
        private BatchModule(String source, MemorySegment handle) { this.source = source; this.handle = handle; }
    }
}
