package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import top.ceroxe.rt.renderer.api.*;
import static java.lang.foreign.ValueLayout.*;

/** Real packaged CUDA integration against an analytic source, with immutable-input guards. */
public final class CudaCameraScatteringNativeSelfTest {
    private static final int COUNT=257;
    public static void main(String[] arguments) throws Exception {
        Path output=Path.of(System.getProperty("top.ceroxe.rt.medium.scatteringComponentDirectory",
                "build/luxcore-native-scattering-20261029/component"));
        Files.createDirectories(output);
        double maximum=0;
        try(var runtime=CudaMediumRuntime.open(0);
            var kernel=runtime.compileBatchKernel(CudaCameraScatteringSource.source(),"camera_scattering_resolve");
            var arena=Arena.ofConfined()) {
            for(int steps:new int[]{1,8,64}) {
                var medium=new Medium(new Rgb(.1f,.1f,.1f),new Rgb(.2f,.2f,.2f),new Rgb(.03f,.07f,.01f),1,1,.6f);
                var frame=RenderFrameRequest.builder(1,COUNT,1,CameraState.lookAt(0,0,0,0,0,-1).build())
                        .environment(EnvironmentState.builder().cameraMedium(medium).skyRadiance(.1f,.2f,.3f).ambientIntensity(.4f).build())
                        .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withMaxDistance(7).withTransmittanceCutoff(0)).build();
                var plan=CudaCameraScatteringAbi.plan(COUNT,1,1,steps);
                byte[] initial=new byte[Math.toIntExact(plan.bytes()+32)];Arrays.fill(initial,(byte)0x5a);
                ByteBuffer packed=ByteBuffer.wrap(initial).order(ByteOrder.nativeOrder());
                packed.position(16).put(CudaCameraScatteringAbi.header(plan,frame,0,COUNT));
                for(int i=0;i<COUNT;i++) {
                    float distance=i%3==0?7:Math.min(i*.071f,7);
                    int primary=16+plan.primaryOffset()+i*208;
                    packed.putFloat(primary+12,i*.071f).putInt(primary+112,i%3==0?0:1);
                    for(int j=0;j<steps;j++) {
                        int source=16+plan.sourceOffset()+(i*steps+j)*16;
                        packed.putFloat(source,.4f).putFloat(source+4,.2f).putFloat(source+8,.1f)
                                .putFloat(source+12,distance/steps*.37f);
                    }
                }
                try(var storage=runtime.allocate(initial.length)) {
                    var host=arena.allocateFrom(JAVA_BYTE,initial);
                    runtime.upload(storage.slice(0,storage.byteSize()),host);
                    runtime.launchBlocking(kernel,storage.slice(16,plan.bytes()),COUNT,1);
                    runtime.download(storage.slice(0,storage.byteSize()),host);
                    require(host.get(JAVA_INT,16+CudaCameraScatteringAbi.FAILED_OFFSET)==0,"valid source rejected");
                    for(int i=0;i<initial.length;i++) {
                        boolean result=i>=16+plan.transmittanceOffset()&&i<16+plan.sourceOffset();
                        if(!result)require(host.get(JAVA_BYTE,i)==initial[i],"native scattering mutated header, input or outer guard byte="+i);
                    }
                    for(int i=0;i<COUNT;i++) {
                        double d=i%3==0?7:Math.min(i*.071f,7),sigma=(float)(.1f+.2f);
                        double tr=Math.exp(-sigma*d),integral=-Math.expm1(-sigma*d)/sigma;
                        for(int c=0;c<3;c++) {
                            double emission=c==0?.03f:c==1?.07f:.01f,ambient=(c+1)*.1f*.4f,incoming=c==0?.4f:c==1?.2f:.1f;
                            double expected=(emission+.2f*(ambient+incoming))*integral;
                            float actualT=host.get(JAVA_FLOAT,16+plan.transmittanceOffset()+i*16L+c*4L);
                            float actualE=host.get(JAVA_FLOAT,16+plan.emissionOffset()+i*16L+c*4L);
                            double error=Math.max(Math.abs(actualT-tr),Math.abs(actualE-expected));maximum=Math.max(maximum,error);
                            require(Float.isFinite(actualT)&&Float.isFinite(actualE)&&error<4e-5,"analytic scattering mismatch path="+i+" steps="+steps+" channel="+c+" error="+error);
                        }
                    }
                    host.set(JAVA_FLOAT,16+plan.sourceOffset(),Float.NaN);
                    runtime.upload(storage.slice(0,storage.byteSize()),host);
                    runtime.launchBlocking(kernel,storage.slice(16,plan.bytes()),COUNT,1);
                    runtime.download(storage.slice(0,storage.byteSize()),host);
                    require(host.get(JAVA_INT,16+CudaCameraScatteringAbi.FAILED_OFFSET)==1,"invalid lighting source must report failure");
                }
            }
        }
        Files.writeString(output.resolve("result.json"),"{\"passed\":true,\"scope\":\"packaged-native-direct-scattering-component\",\"pathsPerBudget\":"+COUNT+",\"intervalBudgets\":[1,8,64],\"maximumAbsoluteError\":"+maximum+"}\n");
        System.out.println("CudaCameraScatteringNativeSelfTest passed: analytic source, budgets, input/outer guards and invalid-source rejection; maxAbsolute="+maximum);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
