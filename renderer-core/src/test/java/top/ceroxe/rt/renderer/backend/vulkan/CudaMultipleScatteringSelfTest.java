package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;

/** Provider-free state layout, full-frame budget and composition checks. */
public final class CudaMultipleScatteringSelfTest {
    public static void main(String[] arguments) {
        var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.1f,.1f),Rgb.ZERO,2,1,.6f);
        var frame=RenderFrameRequest.builder(1,17,13,CameraState.lookAt(0,0,0,0,0,-1).build())
                .antiAliasing(new AntiAliasingState(8)).environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withScatteringBounces(8)).build();
        CudaMultipleScatteringProfile.requireSupported(frame,0,true);
        var plan=CudaMultipleScatteringAbi.plan(17,13,8,8);
        var header=ByteBuffer.wrap(CudaMultipleScatteringAbi.header(plan,frame,0,221)).order(ByteOrder.nativeOrder());
        require(plan.paths()==1768&&plan.secondaryPaths(221)==14144,"every spatial sample and interval owns an independent continuation");
        require(header.getInt(28)==8&&header.getInt(128)==144&&header.getInt(136)==12,"bounce, state and event budgets retained");
        require(header.getFloat(96)==0&&header.getFloat(100)==0&&header.getFloat(104)==0,"multiple scattering replaces direct ambient approximation");
        for(int steps:new int[]{1,8,64}){var hd=CudaMultipleScatteringAbi.plan(1920,1080,1,steps);
            require(hd.paths()==2073600&&hd.bytes()+65536<=512L*1024*1024,"complete HD outputs fit shared cap with bounded continuation scratch");}
        String source=CudaMediumSource.source()+"\n"+CudaMultipleScatteringSource.source();
        require(occurrences(source,"OPENCL_FORCE_INLINE float HomogeneousVolume_SegmentScatter(")==1,"pinned segment defined once");
        require(source.contains("const float optical=HomogeneousVolume_SegmentScatter(")&&source.contains("rtr_medium::hgSample"),"pinned sampling and API HG retained");
        reject(()->CudaMultipleScatteringProfile.requireSupported(frame,1,true));
        reject(()->CudaMultipleScatteringProfile.requireSupported(frame,0,false));
        reject(()->CudaMultipleScatteringProfile.requireSupported(frame.toBuilder().mediumTransport(MediumTransport.balanced()).build(),0,true));
        System.out.println("CudaMultipleScatteringSelfTest passed: state layout, sample/bounce budgets, source composition and admission");
    }
    private static int occurrences(String source,String token){int count=0;for(int i=0;(i=source.indexOf(token,i))>=0;i+=token.length())count++;return count;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Runnable operation){try{operation.run();throw new AssertionError("expected unsupported frame");}
        catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable unsupported transport");}}
}
