package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import static java.lang.foreign.ValueLayout.*;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumTransportSelfTest.require;

/** Real CUDA compatibility tests. This does not certify a complete renderer transport path. */
public final class CudaMediumTransportNativeSelfTest {
    private static final int STACK_CASES = 257, STACK_RECORD_BYTES = 512;
    private static final int PHASE_SAMPLES = 65536, PHASE_RECORD_BYTES = 64;
    private static final float[] ASYMMETRIES = {-.9f, -.5f, -.000001f, 0, .000001f, .5f, .9f,
            -Math.nextDown(1f), Math.nextDown(1f)};
    private static final String STACK_PROBE = """
        extern "C" __global__ void stack_probe(unsigned int *records, int count) {
            int i = blockIdx.x * blockDim.x + threadIdx.x; if (i >= count) return;
            unsigned int *r = records + i * 128;
            rtr_medium::Material mats[33];
            for (int j = 0; j < 33; ++j) mats[j].volume.priority = ((int *)r)[j];
            rtr_medium::PathVolumeInfo state; rtr_medium::PathVolumeInfo_Init(&state);
            r[40] = sizeof(state);
            r[41] = (unsigned int)((char *)&state.volumeIndexListSize - (char *)&state);
            r[42] = (unsigned int)((char *)&state.scatteredStart - (char *)&state);
            r[45] = 0;
            for (unsigned int j = 0; j < 32; ++j)
                r[45] += rtr_medium::add(&state, j, mats, 33) != rtr_medium::OK;
            r[43] = state.volumeIndexListSize; r[44] = state.currentVolumeIndex;
            for (int j = 0; j < 32; ++j) r[64 + j] = state.volumeIndexList[j];
            r[46] = rtr_medium::add(&state, 32, mats, 33);
            r[47] = state.volumeIndexListSize; r[48] = state.currentVolumeIndex;
            r[49] = rtr_medium::remove(&state, 32, mats, 33);
            r[50] = state.volumeIndexListSize; r[51] = state.currentVolumeIndex;
            const unsigned int selected = state.currentVolumeIndex;
            r[52] = rtr_medium::PathVolumeInfo_SimulateRemoveVolume(&state, selected, mats);
            r[53] = rtr_medium::remove(&state, selected, mats, 33);
            r[54] = state.currentVolumeIndex; r[55] = state.volumeIndexListSize;
            r[56] = rtr_medium::add(&state, 0xffffffffu, mats, 33);
            r[57] = state.volumeIndexListSize;
            rtr_medium::PathVolumeInfo corrupt = state; corrupt.volumeIndexListSize = 33;
            r[58] = rtr_medium::add(&corrupt, 0, mats, 33);
            r[96] = 0;
            for (int j = 31; j >= 0; --j) if ((unsigned int)j != selected)
                r[96] += rtr_medium::remove(&state, (unsigned int)j, mats, 33) != rtr_medium::OK;
            r[59] = rtr_medium::remove(&state, 0, mats, 33);
            r[60] = state.volumeIndexListSize; r[61] = state.currentVolumeIndex;
            rtr_medium::PathVolumeInfo_StartVolume(&state, 0);
            r[62] = state.currentVolumeIndex; r[63] = state.scatteredStart;
            r[97] = rtr_medium::PathVolumeInfo_SimulateAddVolume(&state, 32, mats);
            r[98] = rtr_medium::add(&state, 33, mats, 33);
        }
        """;
    private static final String PHASE_PROBE = """
        extern "C" __global__ void phase_probe(float *records, int count) {
            int i = blockIdx.x * blockDim.x + threadIdx.x; if (i >= count) return;
            float *r = records + i * 16, pdf;
            float3 incident = make_float3(r[3], r[4], r[5]);
            float3 direction = rtr_medium::hgSample(incident, r[0], r[1], r[2], &pdf);
            r[6] = direction.x; r[7] = direction.y; r[8] = direction.z; r[9] = pdf;
            r[10] = rtr_medium::hgEvaluate(dot(normalize(incident), direction), r[0]);
            r[11] = (float)rtr_medium::hgCosine(r[0], r[1]);
            r[12] = rtr_medium::hgEvaluate(r[13], r[0]);
            r[14] = rtr_medium::hgEvaluate(-1.f, r[0]);
            r[15] = rtr_medium::hgEvaluate(1.f, r[0]);
            if (rtr_medium::hgCosine(r[0], 0.f) != -1.0 || rtr_medium::hgCosine(r[0], 1.f) != 1.0)
                r[15] = -1.f;
        }
        """;

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(System.getProperty("top.ceroxe.rt.medium.transportEvidenceDirectory",
                "build/luxcore-native-semantics-20261028"));
        Files.createDirectories(output);
        double maximumNormError = 0, maximumPhaseError = 0;
        StringBuilder means = new StringBuilder();
        try (var runtime = CudaMediumRuntime.open(0);
             var stackKernel = runtime.compileBatchKernel(CudaMediumTransportSource.source() + STACK_PROBE, "stack_probe");
             var phaseKernel = runtime.compileBatchKernel(CudaMediumTransportSource.source() + PHASE_PROBE, "phase_probe");
             var arena = Arena.ofConfined()) {
            var stackHost = arena.allocate((long) STACK_CASES * STACK_RECORD_BYTES, 16);
            for (int i = 0; i < STACK_CASES; i++) for (int j = 0; j < 33; j++)
                stackHost.set(JAVA_INT, (long)i * STACK_RECORD_BYTES + j * 4L, ((j * 17 + i) % 19) - 9);
            try (var storage = runtime.allocate(stackHost.byteSize())) {
                var slice = storage.slice(0, stackHost.byteSize());
                runtime.upload(slice, stackHost);
                runtime.launchBlocking(stackKernel, slice, STACK_CASES, STACK_RECORD_BYTES);
                runtime.download(slice, stackHost);
            }
            for (int i = 0; i < STACK_CASES; i++) verifyStack(stackHost, i);
            var phaseHost = arena.allocate((long) PHASE_SAMPLES * PHASE_RECORD_BYTES, 16);
            try (var storage = runtime.allocate(phaseHost.byteSize())) {
                var slice = storage.slice(0, phaseHost.byteSize());
                for (int type = 0; type < ASYMMETRIES.length; type++) {
                    float g = ASYMMETRIES[type];
                    for (int i = 0; i < PHASE_SAMPLES; i++) {
                        long p = (long)i * PHASE_RECORD_BYTES;
                        phaseHost.set(JAVA_FLOAT, p, g);
                        phaseHost.set(JAVA_FLOAT, p + 4, (i + .5f) / PHASE_SAMPLES);
                        phaseHost.set(JAVA_FLOAT, p + 8, ((i * 1777) & 65535) / 65536f);
                        phaseHost.set(JAVA_FLOAT, p + 12, i % 2 == 0 ? 0 : .6f);
                        phaseHost.set(JAVA_FLOAT, p + 16, 0);
                        phaseHost.set(JAVA_FLOAT, p + 20, i % 2 == 0 ? 1 : .8f);
                        phaseHost.set(JAVA_FLOAT, p + 52, (i + .5f) / PHASE_SAMPLES * 2 - 1);
                    }
                    runtime.upload(slice, phaseHost);
                    runtime.launchBlocking(phaseKernel, slice, PHASE_SAMPLES, PHASE_RECORD_BYTES);
                    runtime.download(slice, phaseHost);
                    double mean = 0;
                    for (int i = 0; i < PHASE_SAMPLES; i++) {
                        long p = (long)i * PHASE_RECORD_BYTES;
                        double x = phaseHost.get(JAVA_FLOAT,p+24), y = phaseHost.get(JAVA_FLOAT,p+28), z = phaseHost.get(JAVA_FLOAT,p+32);
                        double normError = Math.abs(Math.sqrt(x*x+y*y+z*z)-1);
                        maximumNormError = Math.max(maximumNormError,normError);
                        require(normError < 4e-7,"HG sampled unit direction g=" + g);
                        float pdf = phaseHost.get(JAVA_FLOAT,p+36), evaluated = phaseHost.get(JAVA_FLOAT,p+40);
                        require(Float.isFinite(pdf) && pdf > 0 && pdf == evaluated,"HG sample/evaluate pdf agreement g="+g);
                        double cosine = phaseHost.get(JAVA_FLOAT,p+44);
                        require(Double.isFinite(cosine) && cosine >= -1 && cosine <= 1,"HG finite cosine g="+g);
                        double referenceCosine = CudaMediumTransportSelfTest.inverse(phaseHost.get(JAVA_FLOAT,p+4),g);
                        require(Math.abs(cosine-referenceCosine)<2e-6,"HG inverse CDF g="+g+" sample="+i);
                        double dot = i%2 == 0 ? z : .6*x+.8*z;
                        require(Math.abs(dot-cosine)<5e-7,"HG forward convention/rotated basis g="+g);
                        mean += dot;
                        double reference = stablePhase(phaseHost.get(JAVA_FLOAT,p+52),g);
                        double actual = phaseHost.get(JAVA_FLOAT,p+48);
                        double relative = Math.abs(actual-reference)/reference;
                        maximumPhaseError = Math.max(maximumPhaseError,relative);
                        require(Double.isFinite(actual)&&actual>0&&relative<3e-6,"HG independent phase reference g="+g);
                        require(Float.isFinite(phaseHost.get(JAVA_FLOAT,p+56))&&phaseHost.get(JAVA_FLOAT,p+56)>0
                                &&Float.isFinite(phaseHost.get(JAVA_FLOAT,p+60))&&phaseHost.get(JAVA_FLOAT,p+60)>0,
                                "HG finite poles and exact inverse-CDF endpoints g="+g);
                    }
                    mean /= PHASE_SAMPLES;
                    require(Math.abs(mean-g)<3e-5,"HG sampled mean must equal API asymmetry g="+g+" mean="+mean);
                    if(type!=0) means.append(",\n");
                    means.append(String.format(Locale.ROOT,"    {\"g\":%.9g,\"meanCosine\":%.12g}",g,mean));
                    System.out.printf(Locale.ROOT,"HG g=%.9g samples=%d meanCosine=%.12g%n",g,PHASE_SAMPLES,mean);
                }
            }
            require(runtime.residentBytes()==0,"native semantic test leaked tracked allocation");
            Files.writeString(output.resolve("result.json"),String.format(Locale.ROOT,"""
                    {
                      "passed": true,
                      "scope": "native-semantic-components-not-public-full-renderer",
                      "device": "%s",
                      "stackCases": %d,
                      "nestedBoundaries": 32,
                      "overflowExplicit": true,
                      "missingRemoveUnchanged": true,
                      "phaseSamples": %d,
                      "maximumDirectionNormError": %.12g,
                      "maximumPhaseRelativeError": %.12g,
                      "means": [
                    %s
                      ]
                    }
                    """,runtime.deviceName(),STACK_CASES,(long)PHASE_SAMPLES*ASYMMETRIES.length,
                    maximumNormError,maximumPhaseError,means));
        }
        System.out.println("CudaMediumTransportNativeSelfTest passed: 32 boundaries, explicit failures, exact HG components");
    }

    private static void verifyStack(MemorySegment host,int path) {
        long base=(long)path*STACK_RECORD_BYTES;
        int selected=-1,second=-1;
        for(int j=0;j<32;j++) if(selected<0||host.get(JAVA_INT,base+j*4L)>=host.get(JAVA_INT,base+selected*4L)) selected=j;
        for(int j=0;j<32;j++) if(j!=selected&&(second<0||host.get(JAVA_INT,base+j*4L)>=host.get(JAVA_INT,base+second*4L))) second=j;
        int[] expected={144,132,136,32,selected,0,1,32,selected,2,32,selected,second,0,second,31,3,31,4,2,0,-1,0,0};
        for(int word=40;word<=63;word++)
            require(host.get(JAVA_INT,base+word*4L)==expected[word-40],"stack case="+path+" word="+word+" actual="+host.get(JAVA_INT,base+word*4L)+" expected="+expected[word-40]);
        for(int j=0;j<32;j++) require(host.get(JAVA_INT,base+(64+j)*4L)==j,"all 32 boundaries retained");
        require(host.get(JAVA_INT,base+96*4L)==0,"all remaining removals succeeded");
        int simAdded=host.get(JAVA_INT,base)>=host.get(JAVA_INT,base+32*4L)?0:32;
        if(host.get(JAVA_INT,base)==host.get(JAVA_INT,base+32*4L)) simAdded=32;
        require(host.get(JAVA_INT,base+97*4L)==simAdded,"simulate add priority and last equal wins");
        require(host.get(JAVA_INT,base+98*4L)==3,"invalid boundary index explicit");
    }

    private static double stablePhase(double cosine,double g) {
        double gap=1-Math.abs(g), d=gap*gap+2*Math.abs(g)*(1-(g<0?-cosine:cosine));
        return ((1-g)*(1+g))/(4*Math.PI*d*Math.sqrt(d));
    }
}
