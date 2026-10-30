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
        long boundaryFreeMinimum=CudaPbrMediumAbi.minimumBytes(31,23,8,0,7,32);
        var boundaryFree=CudaPbrMediumAbi.plan(31,23,8,0,7,32,empty,boundaryFreeMinimum);
        require(boundaryFree.capacity()==0&&boundaryFree.packetBytes()==16&&boundaryFree.endpointPackets(),
                "a uniform boundary-free packet retains only its endpoint and origin");
        require(boundaryFree.tilePixels()==1&&boundaryFree.bytes()==boundaryFreeMinimum,
                "boundary-free minimum and tile storage disagree");
        var endpointHeader=ByteBuffer.wrap(CudaPbrMediumAbi.scenePrefix(boundaryFree,
                RenderFrameRequest.builder(1,31,23,CameraState.lookAt(0,0,0,0,0,-1).build()).build(),
                List.of(),new int[0],empty)).order(ByteOrder.nativeOrder());
        require(endpointHeader.getInt(108)==CudaPbrMediumAbi.ENDPOINT_PACKET_FLAG,
                "producer and native consumer must select the same endpoint layout");
        for(int steps:new int[]{1,8,64})for(int lights:new int[]{0,8,255}) {
            var combined=CudaPbrMediumAbi.planWithScattering(1920,1080,1,lights,1,steps,512L*1024*1024);
            require(combined.pbr().tilePixels()==combined.scattering().tilePixels(),"combined paths require one coordinated tile budget");
            require(combined.pbr().bytes()+combined.scattering().bytes()+2*65536L<=512L*1024*1024,"combined PBR/scattering residency overflow");
            require(combined.scattering().compactContribution()&&combined.scattering().primaryOffset()==128+1920*1080*32,"combined path retained redundant primary geometry");
        }
        var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.02f,0,0),2,1,0);
        var cached=CudaPbrMediumAbi.planWithCachedPrimary(1920,1080,1,2,16,8,512L*1024*1024);
        require(cached.pbr().compactColors()&&cached.pbr().packetBytes()==0,"cached primaries retained unused packet storage");
        require(cached.direct().tilePixels()==1920*1080&&cached.pbr().tilePixels()==1920*1080/2,
                "default cached scene must retain full scattering and two PBR tiles within the same budget");
        require(cached.residentBytes()<=512L*1024*1024,"independent tiles exceeded coordinated residency");
        for(int spp:new int[]{1,2,8})for(int lights:new int[]{0,2,15})for(int steps:new int[]{1,8,64}) {
            var compact=CudaPbrMediumAbi.planWithCachedPrimary(37,23,spp,lights,16,steps,4L*1024*1024);
            var p=compact.pbr();
            require(compact.residentBytes()<=4L*1024*1024&&p.tilePixels()>0&&compact.direct().tilePixels()>0,
                    "compact low-residency plan dropped paths or exceeded its cap");
            require(p.transmittanceOffset()+(long)p.paths()*16<=p.emissionOffset()
                    &&p.emissionOffset()+(long)p.paths()*16<=p.colorsOffset(),
                    "in-place camera lanes overlap live final specular or light colors");
            require(p.bytes()-p.colorsOffset()==(long)p.tilePaths()*p.connections()*32,
                    "compact scratch must retain every light and SPP without camera duplication");
            var header=ByteBuffer.allocate(CudaPbrMediumAbi.HEADER_BYTES).order(ByteOrder.nativeOrder());
            for(int first=0;first<p.pixels();first+=p.tilePixels()) {
                CudaPbrMediumAbi.updateTile(header,p,first,Math.min(p.tilePixels(),p.pixels()-first));
                require(header.getInt(44)==p.transmittanceOffset()+(long)first*spp*16,
                        "in-place camera alias lost global pixel identity across independent tiles");
            }
        }
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
        var dielectric=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1.3f,0);
        var dielectricFrame=frame.toBuilder().environment(EnvironmentState.builder().cameraMedium(dielectric).build()).build();
        var dielectricPlan=CudaPbrMediumAbi.plan(31,23,8,0,0,1,empty,4L*1024*1024);
        var dielectricPacked=ByteBuffer.wrap(CudaPbrMediumAbi.scenePrefix(dielectricPlan,dielectricFrame,List.of(),new int[0],empty))
                .order(ByteOrder.nativeOrder());
        require(Math.abs(dielectricPacked.getFloat(64)-.1f)<1e-6,
                "interface-free PBR medium must preserve absorption when IOR differs from one");
        var dielectricBoundary=new CudaBoundaryMediumAbi.Boundary(9,MediumBoundary.volume(dielectric));
        var boundaryPlan=CudaPbrMediumAbi.plan(31,23,8,1,0,1,empty,4L*1024*1024);
        CudaPbrMediumAbi.scenePrefix(boundaryPlan,frame,List.of(dielectricBoundary),new int[0],empty);
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
        CudaPbrMediumProfile.requireSupported(scattering,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true,true);
        var scatteringCaustics=scattering.toBuilder().caustics(Caustics.photonMap(0,0,0,3,1024,.1f)).build();
        CudaPbrMediumProfile.requireSupported(scatteringCaustics,
                new MediumSceneIndex.NativeScene(List.of(),new int[0]),true,true);
        CudaPbrMediumProfile.requireScatteringContributionSupported(scatteringCaustics,0);
        var scatteringAbi=CudaPbrMediumAbi.plan(31,23,1,0,0,1,empty,4L*1024*1024);
        var scatteringBuffer=ByteBuffer.wrap(CudaPbrMediumAbi.scenePrefix(scatteringAbi,scattering,List.of(),new int[0],empty)).order(ByteOrder.nativeOrder());
        require(Math.abs(scatteringBuffer.getFloat(64)-.1f)<1e-6,"PBR camera attenuation must include scattering extinction");
        CudaCameraScatteringProfile.requireRadianceContributionSupported(scattering,0);
        var source=new CudaPbrMediumManager.RadianceContribution(1,16,32,true);
        require(source.includesCameraEmission(),"volume-emission ownership lost");
        expectIllegal(()->new CudaPbrMediumManager.RadianceContribution(0,0,16,true));
        expectIllegal(()->new CudaPbrMediumManager.RadianceContribution(1,1,16,true));
        CudaPbrMediumAbi.updateTile(scatteringBuffer,scatteringAbi,0,1,scatteringAbi.emissionOffset(),true);
        require(scatteringBuffer.getInt(280)==1&&scatteringBuffer.getLong(272)==scatteringAbi.emissionOffset(),
                "composed medium emission must have exactly one owner");
        CudaPbrMediumAbi.updateTile(scatteringBuffer,scatteringAbi,0,1);
        require(scatteringBuffer.getInt(280)==0&&scatteringBuffer.getLong(272)==0,"tile reuse retained stale contribution metadata");
        for(boolean dense:new boolean[]{false,true})for(int bounces:new int[]{0,3}) {
            var m=scattering.environment().cameraMedium();
            if(dense)m=m.withDensityField(MediumDensityField.grid(50,2,AffineTransform.identity()));
            var f=scattering.toBuilder().environment(EnvironmentState.builder().cameraMedium(m).build())
                    .mediumTransport(scattering.mediumTransport().withScatteringBounces(bounces).withScatteringSteps(64)).build();
            byte[] texels=new byte[32];for(int i=0;i<8;i++){texels[i*4]=(byte)255;texels[i*4+3]=(byte)255;}
            var texture=TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(texels).build();
            var d=dense?CudaMediumDensityAbi.prepare(m,List.of(),id->texture):empty;
            var combined=CudaPbrMediumAbi.planWithScattering(f,31,23,29,1,d,16L*1024*1024);
            require(combined.pbr().endpointPackets()==!dense&&combined.pbr().packetBytes()==(dense?32:16),
                    "density integration must retain direction even without interfaces");
            require(combined.pbr().tilePixels()==combined.scatteringTilePixels(),"combined integrator scratch did not share one tile");
            require(combined.residentBytes()<=16L*1024*1024,"combined scattering allocation exceeded the complete-frame cap");
            var kind=dense?(bounces==0?CudaPbrMediumAbi.ScatteringKind.DIRECT_DENSITY:CudaPbrMediumAbi.ScatteringKind.MULTIPLE_DENSITY)
                    :(bounces==0?CudaPbrMediumAbi.ScatteringKind.DIRECT_UNIFORM:CudaPbrMediumAbi.ScatteringKind.MULTIPLE_UNIFORM);
            require(combined.kind()==kind,"wrong PBR scattering transport selected");
            CudaPbrMediumProfile.requireScatteringContributionSupported(f,0);
            long minimumCombined=combined.residentBytes()-(combined.pbr().tilePixels()-1L)
                    *((combined.pbr().bytes()-combined.pbr().sceneBytes()-combined.pbr().paths()*32L)/combined.pbr().tilePixels()
                    +(combined.scatteringBytes()-65536-(dense?(bounces==0?144:224):bounces==0?128:192)-d.bytes()
                    -combined.pbr().paths()*32L)/combined.scatteringTilePixels());
            var bounded=CudaPbrMediumAbi.planWithScattering(f,31,23,29,1,d,minimumCombined);
            require(bounded.pbr().tilePixels()==1&&bounded.scatteringTilePixels()==1,"one-pixel cap dropped an integrator output");
            expectIllegal(()->CudaPbrMediumAbi.planWithScattering(f,31,23,29,1,d,minimumCombined-1));
            var hdFrame=f.toBuilder().antiAliasing(AntiAliasingState.disabled()).build();
            var hd=CudaPbrMediumAbi.planWithScattering(hdFrame,1920,1080,29,1,d,512L*1024*1024);
            require(hd.pbr().paths()==1920*1080&&hd.residentBytes()<=512L*1024*1024,
                    "FullHD compact contribution lost outputs or exceeded the unchanged cap");
            if(hd.tileLocalPrimary()) {
                require(hd.primaryOffset()>hd.pbr().paths()*32L,"tile-local primary overlaps complete radiance outputs");
                require(hd.primaryTileBytes()==hd.pbr().tilePaths()*208L,"compact primary scratch was not charged exactly");
                require(hd.primaryTileBytes()<hd.pbr().primaryBytes(),"FullHD compact contribution retained a redundant complete primary");
            }
        }
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
