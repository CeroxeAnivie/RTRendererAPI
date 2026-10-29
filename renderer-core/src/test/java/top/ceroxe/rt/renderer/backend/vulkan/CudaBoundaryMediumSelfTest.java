package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;

/** Provider-free bounds, scene projection and public admission for native closed boundaries. */
public final class CudaBoundaryMediumSelfTest {
    public static void main(String[] args) {
        PacketCapacitySelfTest.main(new String[0]);
        var medium = new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.02f,.01f,0),2,1,0);
        var camera = CameraState.lookAt(0,0,0,0,0,-1).build();
        var frame = RenderFrameRequest.builder(1,137,101,camera)
                .antiAliasing(AntiAliasingState.multisampled(8))
                .mediumTransport(MediumTransport.balanced().withMaxDistance(13).withMaxEvents(32))
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).build();
        var a = new CudaBoundaryMediumAbi.Boundary(7,MediumBoundary.volume(medium).withPriority(-7));
        var b = new CudaBoundaryMediumAbi.Boundary(VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT|3,
                MediumBoundary.volume(medium).withPriority(Integer.MIN_VALUE));
        var plan = CudaBoundaryMediumAbi.plan(137,101,8,2);
        require(plan.paths()==110696 && plan.tilePaths()==65536,"all samples retained across bounded tiles");
        require(plan.primaryOffset()>=plan.stateOffset()+plan.tilePaths()*272,"continuation scratch is disjoint");
        require(plan.transmittanceOffset()==plan.primaryOffset()+plan.paths()*208,"complete primary payload storage");
        require(plan.emissionOffset()==plan.transmittanceOffset()+plan.paths()*16,"independent T/E arrays");
        require(plan.bytes()==(long)plan.emissionOffset()+plan.paths()*16L,"complete final output storage");
        var adaptive=CudaBoundaryMediumAbi.plan(137,101,8,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),512L*1024*1024-65536);
        require(adaptive.tilePixels()==adaptive.pixels(),"small complete frame uses one continuation tile");
        var fullHd=CudaBoundaryMediumAbi.plan(1920,1080,1,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),512L*1024*1024-65536);
        require(fullHd.tilePaths()>65536 && fullHd.tilePaths()<fullHd.paths() && fullHd.bytes()<=512L*1024*1024-65536,
                "large frame uses available resident scratch without truncating final arrays");
        long minimum=CudaBoundaryMediumAbi.minimumBytes(137,101,8,2);
        var onePixel=CudaBoundaryMediumAbi.plan(137,101,8,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),minimum);
        require(onePixel.tilePixels()==1 && onePixel.paths()==110696 && onePixel.bytes()==minimum,
                "a reduced resident cap changes scratch only");
        expectIllegal(()->CudaBoundaryMediumAbi.plan(137,101,8,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),minimum-1));
        var data = ByteBuffer.wrap(CudaBoundaryMediumAbi.scene(plan,frame,List.of(b,a),
                new int[]{b.slot(),a.slot()},8192,137*101-8192)).order(ByteOrder.nativeOrder());
        require(data.getInt(0)==CudaBoundaryMediumAbi.MAGIC && data.getInt(8)==(137*101-8192)*8,"header identity and final tile");
        require(data.getInt(16)==8192 && data.getInt(24)==32 && data.getInt(32)==2,"tile/event/containment budgets");
        require(data.getInt(40)==plan.tilePrimaryOffset(8192),"final primary offsets retain global pixel identity");
        require(data.getInt(256)==7 && data.getInt(320)==b.slot(),"unsigned compact slot ordering");
        require(data.getInt(plan.priorityOffset())==-7 && data.getInt(plan.priorityOffset()+4)==Integer.MIN_VALUE,
                "signed priority is not rounded through float");
        require(data.getInt(128)==1 && data.getInt(132)==0,"initial containment remaps wire slots to compact entries");
        require(data.getFloat(272)==.2f && data.getFloat(288)==.04f,"density scales absorption and emission once");
        require(data.getInt(304)==-1 && data.getFloat(76)==13,"uniform descriptor sentinel and distance");
        byte[] sceneBefore=data.array().clone();
        CudaBoundaryMediumAbi.updateTile(data,plan,0,plan.tilePixels());
        require(data.getInt(8)==plan.tilePaths() && data.getInt(16)==0 && data.getInt(40)==plan.primaryOffset(),
                "only tile header changes between continuation blocks");
        for(int index=CudaBoundaryMediumAbi.HEADER_BYTES;index<sceneBefore.length;index++)
            require(data.get(index)==sceneBefore[index],"tile update modified immutable boundary/density assets");
        var packetFrame=RenderFrameRequest.builder(2,137,101,camera)
                .antiAliasing(AntiAliasingState.multisampled(8))
                .mediumTransport(MediumTransport.balanced().withMaxDistance(13).withMaxEvents(32))
                .environment(EnvironmentState.builder().cameraMedium(Medium.vacuum()).build()).build();
        var packetMedium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0);
        var packetBoundaries=new java.util.ArrayList<CudaBoundaryMediumAbi.Boundary>();
        int[] packetSlots=new int[32];
        for(int index=0;index<packetSlots.length;index++) {
            packetSlots[index]=100+index;
            packetBoundaries.add(new CudaBoundaryMediumAbi.Boundary(packetSlots[index],
                    MediumBoundary.volume(packetMedium).withPriority(index)));
        }
        var packetPlan=CudaBoundaryMediumAbi.planPacket(137,101,8,32,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),512L*1024*1024-65536);
        var packetFullHd=CudaBoundaryMediumAbi.planPacket(1920,1080,1,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),512L*1024*1024-65536);
        require(packetFullHd.tilePixels()<packetFullHd.pixels()
                        &&packetFullHd.bytes()<=512L*1024*1024-65536,
                "packet tiles use the full packet-state stride and keep all frame output within residency");
        long packetMinimum=CudaBoundaryMediumAbi.minimumPacketBytes(137,101,8,2);
        var packetOnePixel=CudaBoundaryMediumAbi.planPacket(137,101,8,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),packetMinimum);
        require(packetOnePixel.tilePixels()==1&&packetOnePixel.bytes()==packetMinimum,
                "packet minimum retains one complete pixel with every sample and the full final output");
        expectIllegal(()->CudaBoundaryMediumAbi.planPacket(137,101,8,2,
                top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Scene.empty(),packetMinimum-1));
        var packetData=ByteBuffer.wrap(CudaBoundaryMediumAbi.scene(packetPlan,packetFrame,packetBoundaries,
                packetSlots,0,packetPlan.pixels())).order(ByteOrder.nativeOrder());
        for(int index=0;index<packetSlots.length;index++)
            require(packetData.getInt(CudaBoundaryMediumAbi.INITIAL_VOLUMES_OFFSET+index*Integer.BYTES)==index,
                    "packet frame metadata preserves all 32 compact initial volume identities");
        CudaBoundaryMediumAbi.updateTile(packetData,packetPlan,0,packetPlan.tilePixels());
        for(int index=0;index<packetSlots.length;index++)
            require(packetData.getInt(CudaBoundaryMediumAbi.INITIAL_VOLUMES_OFFSET+index*Integer.BYTES)==index,
                    "packet per-tile update cannot overwrite any initial volume identity");
        var textured=a.boundary().withMedium(medium.withDensityField(MediumDensityField.grid(50,2,AffineTransform.identity())));
        var texturedBoundary=new CudaBoundaryMediumAbi.Boundary(7,textured);
        var texture=TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(new byte[32]).build();
        var density=top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.prepare(
                frame.environment().cameraMedium(),List.of(texturedBoundary),identity->texture);
        var densityPlan=CudaBoundaryMediumAbi.plan(137,101,8,1,density);
        byte[] complete=CudaBoundaryMediumAbi.scene(densityPlan,frame,List.of(texturedBoundary),new int[0],0,1,density);
        byte[] prefix=CudaBoundaryMediumAbi.scenePrefix(densityPlan,frame,List.of(texturedBoundary),new int[0],0,1,density);
        require(prefix.length+density.bytes()==complete.length && prefix.length<complete.length,
                "production frame prefix excludes immutable density atlas bytes");
        var joined=ByteBuffer.allocate(complete.length).order(ByteOrder.nativeOrder());joined.put(prefix);
        density.write(joined,prefix.length);
        require(java.util.Arrays.equals(joined.array(),complete),"split asset upload preserves absolute descriptor relocation");
        expectIllegal(()->CudaBoundaryMediumAbi.plan(1,1,9,0));
        expectIllegal(()->CudaBoundaryMediumAbi.scene(plan,frame,List.of(a,a),new int[0],0,1));
        expectIllegal(()->CudaBoundaryMediumAbi.scene(plan,frame,List.of(a,b),new int[]{99},0,1));
        expectIllegal(()->CudaBoundaryMediumAbi.scene(plan,frame,List.of(a,b),new int[]{7,7},0,1));
        expectIllegal(()->CudaBoundaryMediumAbi.scene(plan,frame,List.of(a,b),new int[33],0,1));
        var nativeScene = new MediumSceneIndex.NativeScene(List.of(
                new MediumSceneIndex.NativeBoundary(7,a.boundary(),AffineTransform.identity())),new int[]{7});
        CudaBoundaryMediumProfile.requireSupported(frame,nativeScene,true);
        expectUnsupported(()->CudaBoundaryMediumProfile.requireSupported(frame,nativeScene,false));
        var surfaceScene = new MediumSceneIndex.NativeScene(List.of(new MediumSceneIndex.NativeBoundary(7,
                MediumBoundary.surface(medium),AffineTransform.identity())),new int[0]);
        expectUnsupported(()->CudaBoundaryMediumProfile.requireSupported(frame,surfaceScene,true));
        System.out.println("CudaBoundaryMediumSelfTest passed: complete outputs, bounded scratch, compact slots, signed priorities, containment, admission");
    }
    private static void require(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
    private static void expectIllegal(Runnable action) {
        try {action.run();throw new AssertionError("expected invalid boundary ABI");}
        catch(IllegalArgumentException expected) { }
    }
    private static void expectUnsupported(Runnable action) {
        try {action.run();throw new AssertionError("expected unsupported boundary frame");}
        catch(FrameValidationException expected) {
            require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable category");
        }
    }
}
