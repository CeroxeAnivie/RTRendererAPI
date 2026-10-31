package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.*;
import java.util.concurrent.atomic.AtomicReference;
import static java.lang.foreign.ValueLayout.*;

/** Runs the packaged production runtime. Requires a real CUDA device and NVRTC. */
public final class CudaMediumRuntimeNativeSelfTest {
    public static void main(String[] args) throws Exception {
        verifyExternalSemaphoreParams();
        if (java.util.Arrays.asList(args).contains("--params-only")) {
            System.out.println("CudaMediumRuntimeNativeSelfTest external semaphore params passed");
            return;
        }
        int segments = 256;
        long checked = 0;
        for (int session = 0; session < 3; session++) {
            try (CudaMediumRuntime runtime = CudaMediumRuntime.open(0); Arena arena = Arena.ofConfined()) {
                verifySharedCompilation(runtime);
                verifySharedModuleLifetime(runtime);
                MemorySegment input = arena.allocate(CudaMediumAbi.inputBytes(segments));
                MemorySegment output = arena.allocate(CudaMediumAbi.outputBytes(segments));
                for (int i = 0; i < segments; i++) {
                    int p = i * 12, type = i % 8;
                    input.setAtIndex(JAVA_FLOAT, p, type == 4 ? .000001f : type == 5 ? .99999f : .25f);
                    input.setAtIndex(JAVA_FLOAT, p + 1, type >= 3 ? 1f : 0f);
                    input.setAtIndex(JAVA_FLOAT, p + 2, type == 6 ? 0f : type == 7 ? 32f : 1f);
                    for (int c = 0; c < 3; c++) {
                        input.setAtIndex(JAVA_FLOAT, p + 3 + c, type == 0 || type == 2 ? 0 : .1f * (c + 1));
                        input.setAtIndex(JAVA_FLOAT, p + 6 + c, type >= 3 ? .2f * c : 0);
                        input.setAtIndex(JAVA_FLOAT, p + 9 + c, type == 2 || type == 7 ? .1f * (c + 1) : 0);
                    }
                }
                try (var memory = runtime.allocate(input.byteSize() + output.byteSize())) {
                    if (runtime.residentBytes() != input.byteSize() + output.byteSize())
                        throw new AssertionError("device allocation accounting");
                    var in = memory.slice(0, input.byteSize());
                    var out = memory.slice(input.byteSize(), output.byteSize());
                    runtime.upload(in, input);
                    for (int repeat = 0; repeat < 4; repeat++) runtime.evaluateSegmentsBlocking(in, out, segments);
                    runtime.download(out, output);
                    int scatter = 0, escape = 0;
                    for (int i = 0; i < segments; i++) {
                        double[] reference = reference(input, i);
                        if (output.getAtIndex(JAVA_FLOAT, i * 7L) >= 0) scatter++; else escape++;
                        for (int c = 0; c < 7; c++) {
                            float value = output.getAtIndex(JAVA_FLOAT, i * 7L + c);
                            if (!Float.isFinite(value) || Math.abs(value - reference[c]) > 4e-5 * (1 + Math.abs(reference[c])))
                                throw new AssertionError("segment=" + i + " channel=" + c + " expected=" + reference[c] + " actual=" + value);
                            checked++;
                        }
                    }
                    if (scatter == 0 || escape == 0) throw new AssertionError("scatter/escape coverage");
                    AtomicReference<Throwable> ownership = new AtomicReference<>();
                    Thread thread = new Thread(() -> { try { runtime.deviceName(); } catch (Throwable e) { ownership.set(e); } });
                    thread.start(); thread.join();
                    if (!(ownership.get() instanceof IllegalStateException)) throw new AssertionError("thread ownership");
                    System.out.println("session=" + session + " device=" + runtime.deviceName() + " compute="
                            + runtime.computeCapability() + " scatter=" + scatter + " escape=" + escape);
                }
                if (runtime.residentBytes() != 0) throw new AssertionError("device accounting after close");
                try { runtime.allocate(runtime.maxResidentBytes() + 1); throw new AssertionError("budget was not enforced"); }
                catch (IllegalStateException expected) { }
                runtime.close(); // Deliberately verify idempotence before try-with-resources closes again.
                try { runtime.allocate(1); throw new AssertionError("closed runtime accepted allocation"); }
                catch (IllegalStateException expected) { }
            }
        }
        System.out.println("CudaMediumRuntimeNativeSelfTest passed; outputChannels=" + checked);
    }
    private static void verifyExternalSemaphoreParams() {
        long value = 0x0102030405060708L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = CudaMediumRuntime.externalSemaphoreParams(arena, value);
            if (params.byteSize() != CudaMediumRuntime.EXTERNAL_SEMAPHORE_PARAMS_BYTES
                    || params.get(JAVA_LONG, 0) != value)
                throw new AssertionError("external semaphore timeline value layout");
            for (long offset = Long.BYTES; offset < params.byteSize(); offset++) {
                if (params.get(JAVA_BYTE, offset) != 0)
                    throw new AssertionError("external semaphore non-value byte is not zero: offset=" + offset);
            }
        }
    }
    private static void verifySharedCompilation(CudaMediumRuntime runtime) {
        String wrapper = "extern \"C\" __global__ void first(int *p,int n){if(threadIdx.x==0&&blockIdx.x==0)p[0]+=n;}\n"
                + "extern \"C\" __global__ void second(int *p,int n){if(threadIdx.x==0&&blockIdx.x==0)p[0]*=n;}";
        int before = runtime.diagnosticCompilationCount;
        int modulesBefore = runtime.diagnosticBatchModuleLoads;
        long started = System.nanoTime();
        try (var first = runtime.compileBatchKernel(wrapper, "first")) {
            long firstNanos = System.nanoTime() - started;
            started = System.nanoTime();
            try (var second = runtime.compileBatchKernel(wrapper, "second");
                 var memory = runtime.allocate(4); Arena arena = Arena.ofConfined()) {
                long reuseNanos = System.nanoTime() - started;
                MemorySegment value = arena.allocateFrom(JAVA_INT, 3);
                runtime.upload(memory.slice(0, 4), value);
                runtime.launchBlocking(first, memory.slice(0, 4), 2, 1);
                runtime.launchBlocking(second, memory.slice(0, 4), 2, 1);
                runtime.download(memory.slice(0, 4), value);
                if (value.get(JAVA_INT, 0) != 10) throw new AssertionError("cached PTX entry point behavior");
                if (runtime.diagnosticCompilationCount != before + 1) throw new AssertionError("identical source recompiled for another entry point");
                if (runtime.diagnosticBatchModuleLoads != modulesBefore + 1 || runtime.diagnosticLiveBatchModules != 1)
                    throw new AssertionError("identical source loaded another native module");
                System.out.printf(java.util.Locale.ROOT,"NVRTC first entry ms=%.3f reused entry ms=%.3f compiles=1 moduleLoads=1%n", firstNanos/1e6, reuseNanos/1e6);
            }
        }
        if (runtime.diagnosticLiveBatchModules != 0) throw new AssertionError("shared module retained after last kernel close");
    }
    private static void verifySharedModuleLifetime(CudaMediumRuntime runtime) {
        String wrapper = "__device__ int sharedValue=3;\n"
                + "extern \"C\" __global__ void store(int *p,int n){if(threadIdx.x==0&&blockIdx.x==0){sharedValue+=n;p[0]=sharedValue;}}\n"
                + "extern \"C\" __global__ void read(int *p,int n){if(threadIdx.x==0&&blockIdx.x==0)p[0]=sharedValue*n;}";
        try (var memory = runtime.allocate(4); Arena arena = Arena.ofConfined();
             var store = runtime.compileBatchKernel(wrapper, "store");
             var read = runtime.compileBatchKernel(wrapper, "read")) {
            var value = arena.allocateFrom(JAVA_INT, 0);
            runtime.launchBlocking(store, memory.slice(0, 4), 2, 1);
            runtime.launchBlocking(read, memory.slice(0, 4), 2, 1);
            runtime.download(memory.slice(0, 4), value);
            if (value.get(JAVA_INT, 0) != 10) throw new AssertionError("entry points did not share module globals");
            store.close();
            try {
                runtime.compileBatchKernel(wrapper, "missingEntry");
                throw new AssertionError("missing entry was accepted");
            } catch (IllegalStateException expected) { }
            if (runtime.diagnosticLiveBatchModules != 1) throw new AssertionError("failed lookup retired a live sibling");
            try (var different = runtime.compileBatchKernel(wrapper.replace("sharedValue=3", "sharedValue=7"), "read")) {
                runtime.launchBlocking(different, memory.slice(0, 4), 3, 1);
                runtime.download(memory.slice(0, 4), value);
                if (value.get(JAVA_INT, 0) != 21 || runtime.diagnosticLiveBatchModules != 2)
                    throw new AssertionError("different sources aliased native modules");
            }
            runtime.launchBlocking(read, memory.slice(0, 4), 3, 1);
            runtime.download(memory.slice(0, 4), value);
            if (value.get(JAVA_INT, 0) != 15) throw new AssertionError("creator close or failed lookup invalidated sibling");
        }
        if (runtime.diagnosticLiveBatchModules != 0) throw new AssertionError("module references leaked");
        try (var fresh = runtime.compileBatchKernel(wrapper, "read"); var memory = runtime.allocate(4); Arena arena = Arena.ofConfined()) {
            var value = arena.allocate(JAVA_INT);
            runtime.launchBlocking(fresh, memory.slice(0, 4), 2, 1);
            runtime.download(memory.slice(0, 4), value);
            if (value.get(JAVA_INT, 0) != 6) throw new AssertionError("new module retained retired global state");
        }
        if (runtime.diagnosticLiveBatchModules != 0) throw new AssertionError("reopened module leaked");
        System.out.println("CUDA shared module lifetime passed: sibling close, missing entry, source isolation, reload");
    }
    private static double[] reference(MemorySegment data, int index) {
        long p = index * 12L;
        float u = data.getAtIndex(JAVA_FLOAT, p), allowed = data.getAtIndex(JAVA_FLOAT, p + 1);
        double distance = data.getAtIndex(JAVA_FLOAT, p + 2), t = distance, pdf = 1;
        float s = ((data.getAtIndex(JAVA_FLOAT, p + 6) + data.getAtIndex(JAVA_FLOAT, p + 7))
                + data.getAtIndex(JAVA_FLOAT, p + 8)) * .33333333f;
        boolean scattered = false;
        if (allowed != 0 && s > 0) {
            double sampled = -Math.log(1 - (double) u) / s;
            scattered = sampled < distance; t = scattered ? sampled : distance;
            pdf = Math.exp(-t * s) * (scattered ? s : 1);
        }
        double[] result = new double[7]; result[0] = scattered ? t : -1;
        for (int c = 0; c < 3; c++) {
            double sigmaT = (float) (data.getAtIndex(JAVA_FLOAT, p + 3 + c) + data.getAtIndex(JAVA_FLOAT, p + 6 + c));
            double tr = Math.exp(-t * sigmaT) * (scattered ? sigmaT : 1) / pdf;
            result[c + 1] = tr; result[c + 4] = tr * t * data.getAtIndex(JAVA_FLOAT, p + 9 + c);
        }
        return result;
    }
}
