package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import static java.lang.foreign.ValueLayout.*;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPrimaryEmissionSelfTest.*;

/** Real CUDA evaluation of the packaged production helper against the independent analytic contract. */
public final class CudaPrimaryEmissionNativeSelfTest {
    private static final int COUNT = 513, RECORD_BYTES = 384;
    private static final int CONSTANT_OFFSET = 208, TRANSMITTANCE_OFFSET = 240, EMISSION_OFFSET = 256;
    private static final byte GUARD = 0x5a;
    private static final float[] COEFFICIENTS = {0, Float.MIN_VALUE, Float.MIN_NORMAL, 1e-20f, 1e-6f, .1f, 1000, Float.MAX_VALUE};
    private static final String PROBE = """
        extern "C" __global__ void emission_probe(float *records, int count) {
            const int i = blockIdx.x * blockDim.x + threadIdx.x;
            if (i >= count) return;
            float *r = records + i * 96;
            rtrPrimaryEmissionResolve(r, r + 52, (float4 *)(r + 60), (float4 *)(r + 64));
        }
        """;

    public static void main(String[] arguments) throws Exception {
        var output = Path.of(System.getProperty("top.ceroxe.rt.medium.emissionEvidenceDirectory",
                "build/luxcore-native-emission-20261028"));
        Files.createDirectories(output);
        double maximumScaledError = 0;
        try (var runtime = CudaMediumRuntime.open(0);
             var kernel = runtime.compileBatchKernel(CudaPrimaryEmissionSource.source() + PROBE, "emission_probe");
             var storage = runtime.allocate((long) COUNT * RECORD_BYTES + 32);
             var arena = Arena.ofConfined()) {
            var host = arena.allocate(storage.byteSize(),16);
            byte[] initial = new byte[(int) storage.byteSize()]; Arrays.fill(initial,GUARD);
            host.copyFrom(java.lang.foreign.MemorySegment.ofArray(initial));
            for (int i = 0; i < COUNT; i++) {
                long p = 16L + (long)i * RECORD_BYTES;
                float distance = switch (i % 6) { case 0 -> -2; case 1 -> 0; case 2 -> 1e-5f; case 3 -> 2; case 4 -> 11; default -> 1e8f; };
                host.set(JAVA_FLOAT,p+12,distance);
                host.set(JAVA_INT,p+112,i%3==0?0:1);
                host.set(JAVA_INT,p+116,i%3==0?1:0);
                host.set(JAVA_FLOAT,p+CONSTANT_OFFSET+12,i%11==0?1_000_000:7);
                for (int channel=0;channel<3;channel++) {
                    host.set(JAVA_FLOAT,p+CONSTANT_OFFSET+channel*4L,COEFFICIENTS[(i+channel*3)%COEFFICIENTS.length]);
                    host.set(JAVA_FLOAT,p+CONSTANT_OFFSET+16+channel*4L,
                            i%7==0?0:i%17==0?Float.MAX_VALUE:(channel+1)*.17f);
                }
                host.set(JAVA_INT,p+CONSTANT_OFFSET+28,0);
            }
            byte[] before = host.toArray(JAVA_BYTE);
            runtime.upload(storage.slice(0,storage.byteSize()),host);
            runtime.launchBlocking(kernel,storage.slice(16,(long)COUNT*RECORD_BYTES),COUNT,RECORD_BYTES);
            runtime.download(storage.slice(0,storage.byteSize()),host);
            for (int i=0;i<16;i++) require(host.get(JAVA_BYTE,i)==GUARD
                    &&host.get(JAVA_BYTE,storage.byteSize()-16+i)==GUARD,"emission outer guards");
            for (int i=0;i<COUNT;i++) {
                long p=16L+(long)i*RECORD_BYTES;
                for (int offset=0;offset<RECORD_BYTES;offset++) if(offset<TRANSMITTANCE_OFFSET||offset>=EMISSION_OFFSET+16)
                    require(host.get(JAVA_BYTE,p+offset)==before[(int)p+offset],"emission mutated input or record guard path="+i);
                double maxDistance=host.get(JAVA_FLOAT,p+CONSTANT_OFFSET+12);
                double distance=i%3==0?maxDistance:Math.min(Math.max(host.get(JAVA_FLOAT,p+12),0),maxDistance);
                for (int channel=0;channel<3;channel++) {
                    double absorption=host.get(JAVA_FLOAT,p+CONSTANT_OFFSET+channel*4L);
                    double emission=host.get(JAVA_FLOAT,p+CONSTANT_OFFSET+16+channel*4L);
                    double expectedT=Math.exp(-absorption*distance);
                    double expectedE=Math.min(Float.MAX_VALUE,integral(absorption,emission,distance));
                    float actualT=host.get(JAVA_FLOAT,p+TRANSMITTANCE_OFFSET+channel*4L);
                    float actualE=host.get(JAVA_FLOAT,p+EMISSION_OFFSET+channel*4L);
                    require(Float.isFinite(actualT)&&Float.isFinite(actualE),"nonfinite native emission path="+i);
                    double errorT=Math.abs(actualT-expectedT)/Math.max(1,Math.abs(expectedT));
                    double errorE=Math.abs(actualE-expectedE)/Math.max(1,Math.abs(expectedE));
                    maximumScaledError=Math.max(maximumScaledError,Math.max(errorT,errorE));
                    require(errorT<4e-5&&errorE<4e-5,"native analytic emission path="+i+" channel="+channel
                            +" actual="+actualE+" expected="+expectedE+" relative="+errorE);
                }
                require(host.get(JAVA_FLOAT,p+TRANSMITTANCE_OFFSET+12)==1
                        &&host.get(JAVA_FLOAT,p+EMISSION_OFFSET+12)==0,"emission output alpha contract");
            }
            verifyPrimaryKernel(runtime,arena);
            Files.writeString(output.resolve("component-result.json"),String.format(Locale.ROOT,"""
                    {
                      "passed": true,
                      "scope": "native-primary-emission-component-not-public-renderer",
                      "device": "%s",
                      "paths": %d,
                      "fullKernelPaths": 257,
                      "rgbChannels": %d,
                      "inputAndOutputGuards": true,
                      "zeroAndTinyAbsorption": true,
                      "strongAbsorptionAndLongDistance": true,
                      "maximumScaledError": %.12g
                    }
                    """,runtime.deviceName(),COUNT,(long)COUNT*6,maximumScaledError));
        }
        System.out.printf(Locale.ROOT,"CudaPrimaryEmissionNativeSelfTest passed: paths=%d channels=%d maxScaledError=%.9g; component evidence%n",
                COUNT,(long)COUNT*6,maximumScaledError);
    }

    private static void verifyPrimaryKernel(CudaMediumRuntime runtime,Arena arena) {
        final int count=257;
        long primaryBytes=CudaPrimaryEmissionAbi.primaryBytes(count),outputBytes=CudaPrimaryEmissionAbi.outputBytes(count);
        var medium=new Medium(new Rgb(0,.13f,1e-20f),Rgb.ZERO,new Rgb(.3f,.17f,.1f),1.6f,1,0);
        var frame=RenderFrameRequest.builder(0,count,1,CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withMaxDistance(7)).build();
        byte[] packed=CudaPrimaryEmissionAbi.constants(frame);
        var source=java.lang.foreign.MemorySegment.ofArray(packed);
        try(var kernel=runtime.compileBatchKernel(CudaPrimaryEmissionSource.source(),"primary_medium_emission_resolve");
            var primary=runtime.allocate(primaryBytes+32);var constants=runtime.allocate(48);var result=runtime.allocate(outputBytes+32)) {
            var records=primary.slice(16,primaryBytes);var c=constants.slice(0,32);var out=result.slice(16,outputBytes);
            var hostPrimary=arena.allocate(primaryBytes,16);
            for(int i=0;i<count;i++) {
                hostPrimary.set(JAVA_FLOAT,(long)i*208+12,i%7==0?-1:i*.07f);
                hostPrimary.set(JAVA_INT,(long)i*208+112,i%3==0?0:1);
            }
            byte[] initial=new byte[(int)result.byteSize()];Arrays.fill(initial,GUARD);
            runtime.upload(records,hostPrimary);runtime.upload(c,arena.allocateFrom(JAVA_BYTE,packed));
            runtime.upload(result.slice(0,result.byteSize()),arena.allocateFrom(JAVA_BYTE,initial));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,0,c,out));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records.slice(0,primaryBytes-1),count,c,out));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,count,c.slice(0,31),out));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,count,c,out.slice(0,outputBytes-1)));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,primary.slice(17,primaryBytes),count,c,out));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,count,constants.slice(1,32),out));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,count,c,result.slice(17,outputBytes)));
            expectIllegal(() -> runtime.launchPrimaryEmissionBlocking(kernel,records,count,c,records.slice(0,outputBytes)));
            runtime.launchPrimaryEmissionBlocking(kernel,records,count,c,out);
            var host=arena.allocate(result.byteSize(),16);runtime.download(result.slice(0,result.byteSize()),host);
            for(int i=0;i<16;i++) require(host.get(JAVA_BYTE,i)==GUARD&&host.get(JAVA_BYTE,result.byteSize()-16+i)==GUARD,
                    "full primary emission output guards");
            for(int i=0;i<count;i++) {
                double distance=i%3==0?7:Math.min(Math.max(hostPrimary.get(JAVA_FLOAT,(long)i*208+12),0),7);
                for(int channel=0;channel<3;channel++) {
                    double absorption=source.get(JAVA_FLOAT_UNALIGNED,channel*4L),emission=source.get(JAVA_FLOAT_UNALIGNED,16+channel*4L);
                    float actualT=host.get(JAVA_FLOAT,16+(long)i*16+channel*4L);
                    float actualE=host.get(JAVA_FLOAT,16+CudaPrimaryEmissionAbi.emissionOffset(count)+(long)i*16+channel*4L);
                    require(Float.isFinite(actualT)&&Math.abs(actualT-Math.exp(-absorption*distance))<4e-5,"full primary transmittance");
                    require(Float.isFinite(actualE)&&Math.abs(actualE-integral(absorption,emission,distance))<4e-5,"full primary analytic emission");
                }
            }
        }
    }

    private static void expectIllegal(Runnable operation) {
        try {operation.run();throw new AssertionError("expected IllegalArgumentException");}
        catch(IllegalArgumentException expected) { }
    }
}
