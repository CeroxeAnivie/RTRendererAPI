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
            verifyCompactContribution(runtime,kernel,arena);
            verifyJointCameraLane(runtime,kernel,arena);
            verifyPreweightedDistances(runtime,kernel,arena);
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
                        putConstantSource(packed,source,distance,steps,j);
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
    private static void verifyCompactContribution(CudaMediumRuntime runtime,CudaMediumRuntime.BatchKernel kernel,Arena arena) {
        var medium=new Medium(new Rgb(.1f,.1f,.1f),new Rgb(.2f,.2f,.2f),new Rgb(.03f,.07f,.01f),1,1,0);
        var frame=RenderFrameRequest.builder(7,37,1,CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(8).withMaxDistance(7).withTransmittanceCutoff(0)).build();
        long limit=65536+128+37*32+5*16*9;
        var original=CudaCameraScatteringAbi.contributionPlan(37,1,1,8,limit);
        require(original.tilePixels()==5,"compact contribution fixture must exercise partial final tile");
        for(boolean planar:new boolean[]{false,true}) {
        var plan=planar?new CudaCameraScatteringAbi.Plan(original.pixels(),original.samples(),original.steps(),original.tilePixels(),
                original.transmittanceOffset(),original.transmittanceOffset(),original.emissionOffset(),original.sourceOffset(),original.bytes(),true):original;
        try(var storage=runtime.allocate(plan.bytes())) {
            var host=arena.allocate(plan.bytes(),16);host.fill((byte)0x5a);
            for(int first=0;first<37;first+=plan.tilePixels()) {
                int count=Math.min(plan.tilePixels(),37-first);
                host.asByteBuffer().put(CudaCameraScatteringAbi.header(plan,frame,first,count,false));
                for(int i=0;i<count;i++) {
                    float distance=(first+i)*.13f;long primary=plan.primaryOffset()+i*16L;
                    host.set(JAVA_FLOAT,primary,distance);host.set(JAVA_INT,primary+4,1);
                    for(int j=0;j<8;j++)putConstantSource(host.asByteBuffer().order(ByteOrder.nativeOrder()),
                            Math.toIntExact(plan.sourceOffset()+(planar?j*(long)count+i:i*8L+j)*16),distance,8,j);
                }
                runtime.upload(storage.slice(0,plan.bytes()),host);runtime.launchBlocking(kernel,storage.slice(0,plan.bytes()),count,1);
                runtime.download(storage.slice(0,plan.bytes()),host);
                require(host.get(JAVA_INT,112)==0,"compact scattering input rejected");
                for(int i=0;i<count;i++)for(int c=0;c<3;c++) {
                    double d=(float)((first+i)*.13f),sigma=(float)(.1f+.2f),integral=-Math.expm1(-sigma*d)/sigma;
                    double expected=.2f*(c==0?.4f:c==1?.2f:.1f)*integral;
                    double actual=host.get(JAVA_FLOAT,plan.emissionOffset()+(first+i)*16L+c*4);
                    require(Math.abs(actual-expected)<4e-5,"compact tiled scattering/emission-ownership mismatch path="+(first+i)+" channel="+c);
                }
            }
        }
    }
    }
    /** Analytic constant integrand fixture, independent of the producer's choice of proposal. */
    private static void verifyJointCameraLane(CudaMediumRuntime runtime,CudaMediumRuntime.BatchKernel kernel,Arena arena) {
        for(int steps:new int[]{1,8,64}) {
            var medium=new Medium(new Rgb(.1f,.1f,.1f),new Rgb(.2f,.2f,.2f),Rgb.ZERO,1,1,0);
            var frame=RenderFrameRequest.builder(7,COUNT,1,CameraState.lookAt(0,0,0,0,0,-1).build())
                    .environment(EnvironmentState.builder().cameraMedium(medium).build())
                    .antiAliasing(AntiAliasingState.multisampled(2))
                    .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withMaxDistance(7).withTransmittanceCutoff(0)).build();
            var transport=CudaPbrMediumAbi.planWithCachedPrimary(COUNT,1,2,2,16,steps,4L*1024*1024);
            require(transport.jointDirectBatch(),"joint component fixture must retain both complete producers");
            var plan=transport.direct();int paths=plan.paths(),bytes=Math.toIntExact(plan.bytes());
            var host=arena.allocate(bytes+32,16);host.fill((byte)0x5a);
            host.asSlice(16).asByteBuffer().put(CudaCameraScatteringAbi.header(plan,frame,0,COUNT,false));
            host.set(JAVA_INT,16+28,3);
            for(int i=0;i<paths;i++) {
                float distance=i%3==0?7:Math.min(i*.017f,7);
                long primary=16+plan.primaryOffset()+i*16L;
                host.set(JAVA_FLOAT,primary,distance);host.set(JAVA_INT,primary+4,i%3==0?0:1);
                for(int j=0;j<steps;j++)putConstantSource(host.asByteBuffer().order(ByteOrder.nativeOrder()),
                        Math.toIntExact(16+plan.sourceOffset()+(j*(long)paths+i)*16),distance,steps,j);
            }
            try(var storage=runtime.allocate(host.byteSize())) {
                runtime.upload(storage.slice(0,host.byteSize()),host);
                runtime.launchBlocking(kernel,storage.slice(16,bytes),paths,1);
                runtime.download(storage.slice(0,host.byteSize()),host);
                require(host.get(JAVA_INT,16+112)==0,"planar reference rejected");
                byte[] reference=host.asSlice(16+plan.emissionOffset(),paths*16L).toArray(JAVA_BYTE);
                host.set(JAVA_INT,16+28,7);
                for(int i=0;i<paths;i++) {
                    long at=16+plan.primaryOffset()+i*16L;
                    host.set(JAVA_FLOAT,at,.13f);host.set(JAVA_FLOAT,at+4,.27f);host.set(JAVA_FLOAT,at+8,.39f);
                    host.set(JAVA_FLOAT,at+12,i%3==0?-1:Math.min(i*.017f,7));
                }
                host.asSlice(16+plan.emissionOffset(),paths*16L).fill((byte)0x5a);
                byte[] immutable=host.toArray(JAVA_BYTE);
                runtime.upload(storage.slice(0,host.byteSize()),host);
                runtime.launchBlocking(kernel,storage.slice(16,bytes),paths,1);
                runtime.download(storage.slice(0,host.byteSize()),host);
                require(host.get(JAVA_INT,16+112)==0,"joint camera source rejected");
                require(Arrays.equals(reference,host.asSlice(16+plan.emissionOffset(),paths*16L).toArray(JAVA_BYTE)),
                        "joint camera-distance source changed radiance");
                for(int i=0;i<immutable.length;i++)if(i<16+plan.emissionOffset()||i>=16+plan.emissionOffset()+paths*16)
                    require(host.get(JAVA_BYTE,i)==immutable[i],"joint kernel overwrote camera color, source, header or outer guard");
                for(int flags:new int[]{2,4,5,6,8}) {
                    host.copyFrom(java.lang.foreign.MemorySegment.ofArray(immutable));
                    host.set(JAVA_INT,16+28,flags);
                    runtime.upload(storage.slice(0,host.byteSize()),host);
                    runtime.launchBlocking(kernel,storage.slice(16,bytes),paths,1);
                    runtime.download(storage.slice(0,host.byteSize()),host);
                    require(host.get(JAVA_INT,16+112)==paths,"invalid joint flags accepted: "+flags);
                }
            }
        }
    }
    private static void putConstantSource(ByteBuffer packed,int at,float distance,int steps,int j) {
        double sigma=(float)(.1f+.2f),t=distance*(j+.37)/steps;
        double weight=(-Math.expm1(-sigma*distance)/sigma)/steps*Math.exp(sigma*t);
        packed.putFloat(at,(float)(.4f*weight)).putFloat(at+4,(float)(.2f*weight))
                .putFloat(at+8,(float)(.1f*weight)).putFloat(at+12,(float)t);
    }
    private static void verifyPreweightedDistances(CudaMediumRuntime runtime,CudaMediumRuntime.BatchKernel kernel,Arena arena) {
        int steps=8;float distance=6;
        var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.1f,.05f),new Rgb(0,0,0),1,1,0);
        var frame=RenderFrameRequest.builder(1,1,1,CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withMaxDistance(distance)
                        .withTransmittanceCutoff(.9f)).build();
        var plan=CudaCameraScatteringAbi.plan(1,1,1,steps);
        var host=arena.allocate(plan.bytes(),16);host.fill((byte)0);
        host.asByteBuffer().put(CudaCameraScatteringAbi.header(plan,frame,0,1));
        host.set(JAVA_FLOAT,plan.primaryOffset()+12,distance);host.set(JAVA_INT,plan.primaryOffset()+112,1);
        double[] expected=new double[3];
        // Absolute locations deliberately arrive out of order and outside their old interval.
        for(int j=0;j<steps;j++) {
            float t=distance*((j*5)%steps+.31f)/steps,inversePdf=(j+1)*.17f;
            long at=plan.sourceOffset()+j*16L;host.set(JAVA_FLOAT,at+12,t);
            for(int c=0;c<3;c++) {
                float incoming=(c+1)*.13f*inversePdf;host.set(JAVA_FLOAT,at+c*4L,incoming);
                float sigma=c==0?.1f+.2f:c==1?.2f+.1f:.3f+.05f,scatter=c==0?.2f:c==1?.1f:.05f;
                expected[c]+=Math.exp(-sigma*t)*scatter*incoming;
            }
        }
        try(var storage=runtime.allocate(plan.bytes())) {
            runtime.upload(storage.slice(0,plan.bytes()),host);runtime.launchBlocking(kernel,storage.slice(0,plan.bytes()),1,1);
            runtime.download(storage.slice(0,plan.bytes()),host);
            require(host.get(JAVA_INT,CudaCameraScatteringAbi.FAILED_OFFSET)==0,"absolute unordered source rejected");
            for(int c=0;c<3;c++)require(Math.abs(host.get(JAVA_FLOAT,plan.emissionOffset()+c*4L)-expected[c])<2e-6,
                    "preweighted source was integrated with an extra PDF, prefix cutoff or interval offset");
            byte[] valid=host.toArray(JAVA_BYTE);
            for(int invalid=0;invalid<6;invalid++) {
                host.copyFrom(java.lang.foreign.MemorySegment.ofArray(valid));
                if(invalid==0)host.set(JAVA_INT,4,1);
                else if(invalid==1)host.set(JAVA_FLOAT,plan.sourceOffset()+12,-.1f);
                else if(invalid==2)host.set(JAVA_FLOAT,plan.sourceOffset()+12,distance+.1f);
                else if(invalid==3)host.set(JAVA_FLOAT,plan.sourceOffset()+12,Float.NaN);
                else if(invalid==4)host.set(JAVA_FLOAT,plan.sourceOffset(),Float.POSITIVE_INFINITY);
                else host.set(JAVA_FLOAT,plan.sourceOffset()+4,Float.NaN);
                runtime.upload(storage.slice(0,plan.bytes()),host);runtime.launchBlocking(kernel,storage.slice(0,plan.bytes()),1,1);
                runtime.download(storage.slice(0,plan.bytes()),host);
                require(host.get(JAVA_INT,CudaCameraScatteringAbi.FAILED_OFFSET)==1,"malformed preweighted packet accepted case="+invalid);
            }
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
