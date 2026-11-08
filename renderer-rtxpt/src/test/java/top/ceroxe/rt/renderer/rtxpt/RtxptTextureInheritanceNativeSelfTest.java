package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Compact texture generations keep exact pixels and ordered asynchronous parent contracts. */
public final class RtxptTextureInheritanceNativeSelfTest {
    private static final int SIZE=64;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"build/reports/texture-inheritance-contract":args[0]).toAbsolutePath();Files.createDirectories(output);
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var packets=new RtxptMemoryResources.PacketPool();var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var base=texture(opaque(),false);var material=MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.MASKED).doubleSided(true).baseColorTextureId(1).build();
            var initial=SceneTransaction.builder(1).resetScene().upsert(base).upsert(material).upsert(quad()).upsert(SceneInstance.builder(1,1).build()).build();
            renderer.apply(initial);render(renderer,access,lighting,1);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            pixels(handle,output,1,true);var mirror=new RtxptScene();mirror.retain(initial);
            var before=RtxptNative.resourceWorkState(handle);

            // Real distinct native queue entries share one Frame. Each entry
            // inherits the generation committed by the preceding queue entry.
            var same=texture(opaque(),false);mirror=queue(handle,mirror,base,same,2,2,packets);
            var sampler=texture(opaque(),true);mirror=queue(handle,mirror,same,sampler,2,2,packets);
            var transparent=sampler.patched(new TexturePatch(1,0,1,1,2,2,8,ByteBuffer.wrap(new byte[16])));
            mirror=queue(handle,mirror,sampler,transparent,2,1,packets);
            var finalGeneration=texture(pixels(transparent),true);mirror=queue(handle,mirror,transparent,finalGeneration,2,2,packets);
            renderer.apply(SceneTransaction.builder(2).build());render(renderer,access,lighting,2);pixels(handle,output,2,false);
            var after=RtxptNative.resourceWorkState(handle);
            require(after[0]==before[0]&&after[1]==before[1],"Inherited/no-op/sampler packets uploaded a complete image");
            require(after[2]-before[2]==1&&after[3]-before[3]==16,"Only the real local rectangle should upload pixel bytes");
            require(after[5]-before[5]==2&&after[6]-before[6]==1,"Distinct queued no-op and sampler generations were lost");
            var surface=RtxptNative.lightingSurfaceState(handle,"mesh1_v3f800000:0",0);
            require(surface[1]==1,"Inherited surface sampler did not retain clamp/linear policy");

            byte[] restoreRectangle=new byte[16];Arrays.fill(restoreRectangle,(byte)-1);
            var restored=finalGeneration.patched(new TexturePatch(1,0,1,1,2,2,8,ByteBuffer.wrap(restoreRectangle)));
            mirror=queue(handle,mirror,finalGeneration,restored,3,1,packets);
            renderer.apply(SceneTransaction.builder(3).build());render(renderer,access,lighting,3);pixels(handle,output,3,true);
            var restoredWork=RtxptNative.resourceWorkState(handle);
            require(restoredWork[2]-after[2]==1&&restoredWork[3]-after[3]==16,"A patch after inheritance lost its exact image shadow or generation");

            // A forged stale reference must fail at the native boundary rather
            // than silently reusing pixels from the current identity.
            var next=texture(opaque(),true);var target=new RtxptScene(mirror);target.retain(SceneTransaction.builder(4).upsert(next).build());
            var valid=RtxptHighFramerateResourcesSelfTest.packet(mirror,target,Map.of(1L,restored),packets);
            require(valid.getInt(52)==2,"Stale-base fixture did not select image inheritance");
            var invalid=ByteBuffer.allocateDirect(valid.remaining()).order(ByteOrder.LITTLE_ENDIAN);invalid.put(valid).flip();invalid.putLong(56,base.generation());
            RtxptNative.resourcesMemory(handle,4,invalid);renderer.apply(SceneTransaction.builder(4).build());
            boolean rejected=false;try{render(renderer,access,lighting,4);}catch(IllegalStateException expected){rejected=String.valueOf(expected.getMessage()).contains("Stale or invalid texture image inheritance");}
            require(rejected,"The native queue accepted stale image inheritance or hid its specific error");
        }
        System.out.println("TEXTURE_INHERITANCE_NATIVE PASS freshFrames=3 noOpGeneration=true samplerMetadata=true laterPatch=true multipleQueuedPackets=true exactPixels=true staleBaseRejected=true");
    }
    private static RtxptScene queue(long handle,RtxptScene previous,TextureAsset base,TextureAsset texture,long revision,int mode,RtxptMemoryResources.PacketPool packets){
        var next=new RtxptScene(previous);next.retain(SceneTransaction.builder(revision).upsert(texture).build());
        var packet=RtxptHighFramerateResourcesSelfTest.packet(previous,next,Map.of(1L,base),packets);
        require(packet.getInt(52)==mode&&packet.getLong(56)==base.generation(),"Texture packet lost its prepared transport or exact base generation");
        try(var borrowed=packets.borrow(packet)){RtxptNative.resourcesMemory(handle,revision,borrowed.buffer());}return next;
    }
    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence){
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(1)),List.of(),null,null));
        var receipt=renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());access.awaitIdle();
        require(receipt.frameSequence()==sequence&&receipt.scheduledSceneRevision()==sequence,"Texture frame receipt changed");
        try(var lease=lighting.acquireLightingFrame().orElseThrow()){
            var frame=lease.frame();require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,"Texture packet output was stale");
            require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2&&frame.readyValue()<frame.completionValue(),"Texture packet output contract changed");
        }
    }
    private static TextureAsset texture(byte[] pixels,boolean alternate){return TextureAsset.builder(1,4,4)
            .addressModes(alternate?TextureAsset.AddressMode.CLAMP_TO_EDGE:TextureAsset.AddressMode.REPEAT,TextureAsset.AddressMode.REPEAT)
            .filter(alternate?TextureAsset.Filter.LINEAR:TextureAsset.Filter.NEAREST).pixelsRgba8(pixels).build();}
    private static byte[] opaque(){byte[] pixels=new byte[64];Arrays.fill(pixels,(byte)-1);return pixels;}
    private static byte[] pixels(TextureAsset texture){byte[] pixels=new byte[texture.rgba8().remaining()];texture.rgba8().get(pixels);return pixels;}
    private static MeshAsset quad(){return MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},new int[]{0,1,2,0,2,3},new long[]{1,1})
            .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();}
    private static void pixels(long handle,Path output,long sequence,boolean visible)throws Exception {
        Path path=output.resolve(sequence+"-normal.pfm");RtxptNative.nativeLightingCapture(handle,path.toString(),2);
        byte[] bytes=Files.readAllBytes(path);int offset=0;for(int line=0;line<3;line++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        require(new String(bytes,0,offset,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")&&bytes.length-offset==SIZE*SIZE*12,"Invalid texture contract pixel capture");
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=31;y<=32;y++)for(int x=31;x<=32;x++){
            float z=data.getFloat(offset+((SIZE-1-y)*SIZE+x)*12+8);require(Float.isFinite(z)&&(visible?z>.9f:Math.abs(z)<1e-6f),"Inherited/patch texture alpha changed visible geometry incorrectly");
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
