package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.*;

/** Admission-independent wire, bounded allocation and source assembly contracts. */
public final class CudaDensityScatteringSelfTest {
    public static void main(String[] arguments) {
        byte[] pixels=new byte[32];for(int i=0;i<8;i++){pixels[i*4]=(byte)(31*i);pixels[i*4+3]=(byte)255;}
        var texture=TextureAsset.builder(7,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();
        var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.1f,.05f),new Rgb(.01f,0,0),1.5f,1,.4f)
                .withDensityField(MediumDensityField.grid(7,2,AffineTransform.identity()));
        var frame=RenderFrameRequest.builder(19,37,29,CameraState.lookAt(0,0,6,0,0,0).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).antiAliasing(AntiAliasingState.multisampled(8))
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(13).withLightSamples(0)).build();
        var cache=new CudaMediumDensityAbi.Cache();
        var scene=CudaDensityScatteringAbi.scene(frame,37,29,21,id->texture,cache,512L*1024*1024);
        var plan=scene.plan();
        require(plan.paths()==37*29*8&&plan.tilePixels()==plan.pixels(),"complete frame/all SPP in one bounded tile when capacity permits");
        require(plan.connections()==9&&scene.chunks()==3,"full enumeration over 21 slots plus sun is never truncated");
        require(plan.primaryOffset()==144+scene.density().bytes()&&plan.transmittanceOffset()==plan.primaryOffset()+plan.paths()*208,"density before full primary/T/E arrays");
        require(plan.emissionOffset()==plan.transmittanceOffset()+plan.paths()*16&&plan.rayOffset()==plan.emissionOffset()+plan.paths()*16,"complete immutable final arrays");
        require(plan.sampleOffset()==plan.rayOffset()+plan.tilePaths()*32,"ray and per-interval scratch are disjoint");
        require(plan.connectionOffset()==plan.sampleOffset()+plan.tilePaths()*plan.steps()*32,"light connections follow actual sample states");
        ByteBuffer header=ByteBuffer.wrap(scene.metadata(0,37*29,0,0)).order(ByteOrder.nativeOrder());
        require(header.getInt(0)==CudaDensityScatteringAbi.MAGIC&&header.getInt(12)==13&&header.getInt(108)==19,"identity, interval budget and seed sequence");
        require(header.getInt(116)==144&&header.getInt(128)==0&&header.getInt(136)==21,"density, first connection and full light upper bound");
        require(Math.abs(header.getFloat(48)-.45)<1e-6&&header.getFloat(64)==.3f,"density-scaled extinction/scattering exactly once");
        require(scene.header(0,1,1,2).length==144,"continuations never recopy atlas texels");
        var sampled=frame.toBuilder().mediumTransport(frame.mediumTransport().withLightSamples(1)).build();
        var sampledScene=CudaDensityScatteringAbi.scene(sampled,37,29,21,id->texture,cache,512L*1024*1024);
        require(sampledScene.plan().connections()==2&&sampledScene.chunks()==1,"sample budget reduces scratch, never work within that budget");
        var zeroLights=CudaDensityScatteringAbi.scene(frame,37,29,0,id->texture,cache,512L*1024*1024);
        require(zeroLights.plan().connections()==1&&zeroLights.chunks()==1,"sun-only connection capacity");
        var fullHd=CudaDensityScatteringAbi.scene(frame.toBuilder().antiAliasing(AntiAliasingState.disabled()).build(),1920,1080,21,id->texture,cache,512L*1024*1024);
        require(fullHd.plan().paths()==1920*1080&&fullHd.plan().tilePixels()<1920*1080
                &&fullHd.plan().bytes()+65536<=512L*1024*1024,"full final outputs retained with bounded scratch under the session cap");
        expect(()->CudaDensityScatteringAbi.scene(frame,37,29,21,id->texture,cache,1024));
        String wrapper=CudaDensityScatteringSource.source();
        require(!wrapper.contains(CudaMediumSource.resource("upstream-segment-function.cl")),"runtime alone prepends pinned segment implementation");
        require(wrapper.contains("density_scattering_prepare")&&wrapper.contains("density_scattering_resolve")
                &&wrapper.contains("rtr_density::mass"),"packaged sampling plus per-light density shadow code");
        System.out.println("CudaDensityScatteringSelfTest passed: all SPP/lights, bounded scratch, offsets, density scale, header-only continuation and wrapper assembly");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void expect(Runnable call){try{call.run();throw new AssertionError("oversized density-scattering frame accepted");}catch(IllegalArgumentException expected){}}
}
