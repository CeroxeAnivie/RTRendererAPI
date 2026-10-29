package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi;

/** Allocation and admission must remain exact even with SPP, many lights and signed priorities. */
public final class CudaPbrMediumAbiSelfTest {
    public static void main(String[] args) {
        var empty=CudaMediumDensityAbi.Scene.empty();
        long minimum=CudaPbrMediumAbi.minimumBytes(31,23,8,2,7,32);
        var tiny=CudaPbrMediumAbi.plan(31,23,8,2,7,32,empty,minimum);
        require(tiny.tilePixels()==1&&tiny.samples()==8&&tiny.connections()==8,"residency must retain all SPP and all lights for one pixel");
        require(tiny.bytes()==minimum,"minimum allocation must not hide extra scratch");
        expectIllegal(()->CudaPbrMediumAbi.plan(31,23,8,2,7,32,empty,minimum-1));
        var full=CudaPbrMediumAbi.plan(1920,1080,1,2,255,32,empty,512L*1024*1024-65536);
        require(full.paths()==1920*1080&&full.tilePixels()>=1&&full.tilePixels()<full.pixels(),"many lights must tile scratch without dropping final pixels");
        require(full.bytes()<=512L*1024*1024-65536,"many-light residency overflow");
        require(full.primaryBytes()==1920L*1080*208,"external primary must retain complete geometry");
        require(full.emissionOffset()==full.transmittanceOffset()+full.paths()*16,"radiance splits overlap");
        var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.02f,0,0),2,1,0);
        var a=new CudaBoundaryMediumAbi.Boundary(7,MediumBoundary.volume(medium).withPriority(-7));
        var b=new CudaBoundaryMediumAbi.Boundary(0x80000003,MediumBoundary.volume(medium).withPriority(Integer.MIN_VALUE));
        var frame=RenderFrameRequest.builder(1,31,23,CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .antiAliasing(AntiAliasingState.multisampled(8)).mediumTransport(MediumTransport.balanced().withMaxEvents(32)).build();
        ByteBuffer packed=ByteBuffer.wrap(CudaPbrMediumAbi.scenePrefix(tiny,frame,List.of(b,a),new int[]{b.slot(),a.slot()},empty)).order(ByteOrder.nativeOrder());
        require(packed.getInt(288)==7&&packed.getInt(352)==b.slot(),"transient boundary IDs must retain unsigned table ordering");
        require(packed.getInt(128)==1&&packed.getInt(132)==0,"camera containment slots were not compacted correctly");
        require(packed.getInt(416)==-7&&packed.getInt(420)==Integer.MIN_VALUE,"signed priorities lost integer precision");
        require(packed.getFloat(304)==.2f&&packed.getFloat(320)==.04f,"medium density must scale coefficients once");
        byte[] before=packed.array().clone();CudaPbrMediumAbi.updateTile(packed,tiny,17,1);
        require(packed.getInt(44)==tiny.transmittanceOffset()+17*8*16&&packed.getInt(48)==tiny.emissionOffset()+17*8*16,
                "tiled final radiance lost global pixel identity");
        require(packed.getInt(264)==8&&packed.getInt(256)==8,"tile samples/light count changed");
        for(int i=288;i<before.length;i++)require(packed.get(i)==before[i],"tile metadata mutated immutable scene data");
        CudaPbrMediumProfile.requireSupported(frame,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true);
        var absorberCaustics=frame.toBuilder().caustics(Caustics.photonMap(0,0,0,3,1024,.1f)).build();
        CudaPbrMediumProfile.requireSupported(absorberCaustics,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true);
        expectUnsupported(()->CudaPbrMediumProfile.requireSupported(frame,new MediumSceneIndex.NativeScene(List.of(),new int[0]),false));
        var scattering=frame.toBuilder().environment(EnvironmentState.builder().cameraMedium(new Medium(Rgb.ZERO,new Rgb(.1f,0,0),Rgb.ZERO,1,1,0)).build()).build();
        expectUnsupported(()->CudaPbrMediumProfile.requireSupported(scattering,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true));
        expectUnsupported(()->CudaPbrMediumProfile.requireSupported(scattering.toBuilder().caustics(Caustics.photonMap(0,0,0,3,1024,.1f)).build(),new MediumSceneIndex.NativeScene(List.of(),new int[0]),true));
        var boundary=new CudaBoundaryMediumAbi.Boundary(3,MediumBoundary.volume(medium));
        expectUnsupported(()->CudaPbrMediumProfile.requireSupported(absorberCaustics,
                new MediumSceneIndex.NativeScene(List.of(new MediumSceneIndex.NativeBoundary(boundary.slot(),boundary.boundary(),AffineTransform.identity())),new int[0]),true));
        System.out.println("CudaPbrMediumAbiSelfTest passed");
    }
    private static void expectIllegal(Runnable r){try{r.run();throw new AssertionError("expected bounded ABI rejection");}catch(IllegalArgumentException expected){}}
    private static void expectUnsupported(Runnable r){try{r.run();throw new AssertionError("expected unsupported native PBR rejection");}catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"wrong rejection reason");}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
