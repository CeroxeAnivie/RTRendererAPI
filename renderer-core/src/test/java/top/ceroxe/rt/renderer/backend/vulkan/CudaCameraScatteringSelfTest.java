package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumSource;

/** Budget/admission checks require no native library or Vulkan device. */
public final class CudaCameraScatteringSelfTest {
    public static void main(String[] arguments) {
        // compileBatchKernel prepends the compatibility/base kernel source. A wrapper must
        // not supply it again: duplicate definitions only surface when NVRTC is invoked.
        String source=CudaMediumSource.source()+"\n"+CudaCameraScatteringSource.source();
        require(occurrences(source,"OPENCL_FORCE_INLINE float HomogeneousVolume_SegmentScatter(")==1,
                "pinned segment must be defined exactly once in the assembled CUDA source");
        require(occurrences(source,"__global__ void rtr_luxcore_segment(")==1,
                "runtime base kernel must be defined exactly once");
        require(occurrences(source,"__device__ float rtrApiIntegratedEmission(")==1
                        &&occurrences(source,"__global__ void camera_scattering_resolve(")==1,
                "scattering and analytic-emission wrappers must be defined exactly once");
        var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.3f,.2f,.1f),new Rgb(.1f,0,.2f),2,1,.6f);
        var frame=RenderFrameRequest.builder(1,17,13,CameraState.lookAt(0,0,0,0,0,-1).build())
                .antiAliasing(AntiAliasingState.multisampled(8))
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).build();
        CudaCameraScatteringProfile.requireSupported(frame,0,true);
        var plan=CudaCameraScatteringAbi.plan(17,13,8,8);
        require(plan.paths()==1768&&plan.tilePixels()==221,"all primary spatial samples retained");
        require(plan.transmittanceOffset()==128+1768*208&&plan.emissionOffset()==128+1768*224,"disjoint full-frame output");
        var h=ByteBuffer.wrap(CudaCameraScatteringAbi.header(plan,frame,0,221)).order(ByteOrder.nativeOrder());
        require(h.getInt(8)==1768&&h.getInt(12)==8&&h.getInt(24)==8,"exact integer work dimensions");
        require(Math.abs(h.getFloat(48)-.8f)<1e-6&&h.getFloat(64)==.6f&&h.getFloat(80)==.2f,"density-scaled extinction/scattering/source");
        var contribution=ByteBuffer.wrap(CudaCameraScatteringAbi.header(plan,frame,0,221,false)).order(ByteOrder.nativeOrder());
        require(contribution.getFloat(80)==0f&&contribution.getFloat(84)==0f&&contribution.getFloat(88)==0f,
                "composed scattering output must not duplicate medium emission");
        for(int steps:new int[]{1,8,64}) {
            var hd=CudaCameraScatteringAbi.plan(1920,1080,1,steps);
            require(hd.bytes()+CudaCameraScatteringAbi.ALLOCATION_ALLOWANCE<=512L*1024*1024,"1080p scratch respects shared cap");
            require(hd.paths()==2073600&&hd.tilePixels()>0,"1080p complete frame with bounded light scratch");
        }
        long contributionLimit=4L*1024*1024;
        var compact=CudaCameraScatteringAbi.contributionPlan(17,13,8,8,contributionLimit);
        require(compact.compactContribution()&&compact.transmittanceOffset()==128,"contribution must not retain redundant full primary records");
        require(compact.primaryOffset()==compact.emissionOffset()+compact.paths()*16,"tile primary must follow complete radiance outputs");
        require(compact.sourceOffset()==compact.primaryOffset()+compact.tilePixels()*compact.samples()*16,"compact primary and light source scratch overlap");
        var compactHeader=ByteBuffer.wrap(CudaCameraScatteringAbi.header(compact,frame,0,221,false)).order(ByteOrder.nativeOrder());
        require(compactHeader.getInt(28)==1,"compact contribution layout flag missing");
        reject(()->CudaCameraScatteringProfile.requireSupported(frame,1,true));
        reject(()->CudaCameraScatteringProfile.requireSupported(frame,0,false));
        reject(()->CudaCameraScatteringProfile.requireSupported(frame.toBuilder()
                .mediumTransport(frame.mediumTransport().withScatteringBounces(1)).build(),0,true));
        illegal(()->CudaCameraScatteringAbi.plan(1920,1080,8,64));
        illegal(()->CudaCameraScatteringAbi.plan(17,13,1,0));
        illegal(()->CudaCameraScatteringAbi.header(plan,frame,1,221));
        System.out.println("CudaCameraScatteringSelfTest passed: full-frame budget, bounded scratch, density and strict admission");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int occurrences(String source,String token){int count=0;
        for(int offset=0;(offset=source.indexOf(token,offset))>=0;offset+=token.length())count++;
        return count;}
    private static void reject(Runnable operation){try{operation.run();throw new AssertionError("expected unsupported frame");}
        catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable unsupported category");}}
    private static void illegal(Runnable operation){try{operation.run();throw new AssertionError("expected invalid layout");}
        catch(IllegalArgumentException|ArithmeticException expected){}}
}
