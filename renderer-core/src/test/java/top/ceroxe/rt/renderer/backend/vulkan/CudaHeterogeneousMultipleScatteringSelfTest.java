package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;

/** Provider-free complete-output budgeting, generation cache and continuation layout contracts. */
public final class CudaHeterogeneousMultipleScatteringSelfTest {
    private CudaHeterogeneousMultipleScatteringSelfTest() { }
    public static void main(String[] args) {
        var texture=texture(255);var reads=new AtomicInteger();var cache=new CudaMediumDensityAbi.Cache();
        var medium=new Medium(new Rgb(.2f,.2f,.2f),new Rgb(.5f,.5f,.5f),Rgb.ZERO,1,1,.6f)
                .withDensityField(MediumDensityField.grid(50,2,AffineTransform.identity()));
        var frame=frame(medium,8,64);long cap=CudaMultipleScatteringAbi.RESIDENT_CAP;
        var scene=CudaHeterogeneousMultipleScatteringAbi.scene(frame,17,13,29,id->{reads.incrementAndGet();return texture;},cache,cap);
        var plan=scene.plan();require(plan.paths()==1768&&plan.secondaryPaths(221)==113152,"SPP and interval states retained");
        require(scene.chunks()==4&&plan.connections()==9,"all 29 lights and sun use bounded connection chunks");
        var header=ByteBuffer.wrap(scene.metadata(0,221,1,3)).order(ByteOrder.nativeOrder());
        require(header.getInt()==CudaHeterogeneousMultipleScatteringAbi.MAGIC&&header.getInt(128)==144,"continuation ABI identity and stride");
        require(header.getInt(28)==8&&header.getInt(136)==12&&header.getInt(184)==27&&header.getInt(188)==1,"public budgets and chunk identity");
        require(header.getInt(160)==224&&header.getInt(224)==288,"density descriptor and atlas relocate together");
        require(plan.primaryOffset()==320&&plan.transmittanceOffset()==320+1768*208,"complete primary output precedes scratch");
        require(plan.emissionOffset()==plan.transmittanceOffset()+1768*16&&plan.stateOffset()==plan.emissionOffset()+1768*16,"complete RGB output ranges do not alias");
        require(plan.connectionOffset()==plan.stateOffset()+plan.secondaryPaths(221)*144,"connections follow all live states");
        var again=CudaHeterogeneousMultipleScatteringAbi.scene(frame,17,13,29,id->texture,cache,cap);
        require(scene.density()==again.density(),"unchanged texture generation reuses the immutable converted atlas");
        var changed=CudaHeterogeneousMultipleScatteringAbi.scene(frame,17,13,29,id->texture(127),cache,cap);
        require(scene.density()!=changed.density(),"texture generation replacement invalidates the atlas");
        long minimal=320+1768L*240+8L*64*(144+9*32)+CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE;
        var tiled=CudaHeterogeneousMultipleScatteringAbi.scene(frame,17,13,29,id->texture,cache,minimal);
        require(tiled.plan().tilePixels()==1&&tiled.plan().bytes()+CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE==minimal,"one-pixel scratch budget keeps every final output");
        invalid(()->CudaHeterogeneousMultipleScatteringAbi.scene(frame,17,13,29,id->texture,cache,minimal-1));
        invalid(()->CudaHeterogeneousMultipleScatteringAbi.scene(frame,Integer.MAX_VALUE,2,29,id->texture,cache,cap));
        invalid(()->scene.header(221,1,0,0));invalid(()->scene.header(0,1,0,4));
        CudaMultipleScatteringProfile.requireSupported(frame,0,true);
        var sampled=frame.toBuilder().mediumTransport(frame.mediumTransport().withLightSamples(8)).build();
        require(CudaHeterogeneousMultipleScatteringAbi.scene(sampled,17,13,29,id->texture,cache,cap).chunks()==1,"sampled lights fit one connection exchange");
        String source=CudaMediumSource.source()+"\n"+CudaHeterogeneousMultipleScatteringSource.source();
        require(occurrences(source,"OPENCL_FORCE_INLINE float HomogeneousVolume_SegmentScatter(")==1,"pinned segment defined once");
        require(source.contains("const float optical=HomogeneousVolume_SegmentScatter(")&&source.contains("collisionMass=s->weight.w"),"pinned free-path sampler retains sampled optical mass");
        require(reads.get()==1,"one immutable texture snapshot per preparation");
        System.out.println("CudaHeterogeneousMultipleScatteringSelfTest passed: ABI, complete outputs, bounded chunks, cache generations and resident limits");
    }
    private static RenderFrameRequest frame(Medium medium,int samples,int steps){return RenderFrameRequest.builder(1,17,13,
            CameraState.lookAt(0,0,0,0,0,-1).build()).antiAliasing(new AntiAliasingState(samples))
            .environment(EnvironmentState.builder().cameraMedium(medium).build())
            .mediumTransport(MediumTransport.balanced().withScatteringBounces(8).withScatteringSteps(steps).withLightSamples(0)).build();}
    private static TextureAsset texture(int red){byte[] pixels=new byte[32];for(int i=0;i<8;i++){pixels[i*4]=(byte)red;pixels[i*4+3]=(byte)255;}
        return TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();}
    private static int occurrences(String source,String token){int count=0;for(int at=0;(at=source.indexOf(token,at))>=0;at+=token.length())count++;return count;}
    private static void invalid(Runnable operation){try{operation.run();throw new AssertionError("invalid resident/extent accepted");}catch(IllegalArgumentException|ArithmeticException expected){}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
