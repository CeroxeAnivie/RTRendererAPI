package demo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** One visible 6x5 exhibit board; native 1080p/1-spp frames are the performance unit. */
final class MediumMicroAllScene {
    static final String ID="micro-all";
    private static final AffineTransform LOCAL_GRID=new AffineTransform(new float[]{
            .5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f});
    private final SceneTransaction.Builder scene=SceneTransaction.builder(1).resetScene();
    private final FramePrimitiveBatch.Builder transientVolumes=FramePrimitiveBatch.builder();
    private long nextId=2000;

    static MediumPerformanceCases.Workload workload() {return new MediumMicroAllScene().build();}

    private MediumPerformanceCases.Workload build() {
        material(100,0xffc8baa4,MaterialAsset.ShadingModel.PHYSICALLY_BASED,false,0,0,.65f);
        material(101,0xff507090,MaterialAsset.ShadingModel.UNLIT,true,0,0,1);
        material(102,0xffc0b090,MaterialAsset.ShadingModel.LIGHTMAP_MODULATED,true,0,0,1);
        material(110,0xffe8f4ff,MaterialAsset.ShadingModel.PHYSICALLY_BASED,true,0,1,.001f);
        material(111,0xffe8f4ff,MaterialAsset.ShadingModel.PHYSICALLY_BASED,true,0,1,.18f);
        material(112,0xffc8e0f0,MaterialAsset.ShadingModel.PHYSICALLY_BASED,true,0,.5f,.06f);
        material(113,0xffe0a050,MaterialAsset.ShadingModel.PHYSICALLY_BASED,true,.2f,0,.12f);
        material(114,0xff80b8e0,MaterialAsset.ShadingModel.PHYSICALLY_BASED,true,1,0,.08f);
        for(int i=0;i<4;i++)scene.upsert(MaterialAsset.builder(120+i).doubleSided(true)
                .shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(i==0||i==3?0x80e09040:0xffe09040)
                .baseColorTextureId(i>=2?10:-1).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build());
        for(int i=0;i<2;i++)scene.upsert(MaterialAsset.builder(130+i).doubleSided(true)
                .shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0x80e06030)
                .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).surfaceOverlay(SurfaceOverlayState.depthEqual(.02f,
                        i==0?SurfaceOverlayState.CompositionMode.ALPHA_OVER:SurfaceOverlayState.CompositionMode.MULTIPLY)).build());
        scene.upsert(TextureAsset.builder(10,2,2).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{-1,-1,-1,64,-1,-1,-1,(byte)192,-1,-1,-1,(byte)192,-1,-1,-1,64}).build());
        byte[] atlas=new byte[8*8*8*4];
        for(int z=0;z<8;z++)for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            int at=((z*8+y)*8+x)*4;atlas[at]=(byte)(40+((x+2*y+z)%8)*25);atlas[at+3]=(byte)255;
        }
        scene.upsert(TextureAsset.builder(50,8,64).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(atlas).build());
        for(long material:new long[]{100,101,110,111,112,113,114})scene.upsert(cube(500+material,material,false));
        scene.upsert(cube(900,101,true));
        for(int cell=0;cell<30;cell++)panel(nextId++,cell,-1.3f,100,false,1,0,1);
        for(int cell=0;cell<6;cell++)solid(cell,new long[]{110,111,112,113,114,101}[cell],.65f);
        panel(nextId++,6,0,102,false,.6f,0,1);
        for(int cell=7;cell<=10;cell++)panel(nextId++,cell,0,120+cell-7,cell==8||cell==10,1,0,1);
        overlay(11,130,1,1,.005f,false);
        overlay(12,131,1,1,.005f,false);
        // IDs 1/2 are the documented mask+depth-rejected pair used by the shared fixture inspector.
        overlay(13,130,2,1,.08f,true);
        overlay(14,131,1,1,.08f,false);
        var uniform=new Medium(new Rgb(.12f,.035f,.015f),new Rgb(.13f,.18f,.25f),Rgb.ZERO,1,1,.45f);
        var density=uniform.withDensityField(MediumDensityField.grid(50,8,LOCAL_GRID));
        volume(15,uniform,.8f,1,false,false,false);
        volume(16,density,.8f,1,false,false,false);
        volume(17,new Medium(new Rgb(.2f,.08f,.03f),Rgb.ZERO,new Rgb(.16f,.035f,.01f),1,1,0),.8f,1,false,false,false);
        volume(18,new Medium(uniform.absorption(),uniform.scattering(),Rgb.ZERO,1,1,-.45f),.8f,1,false,false,false);
        var glass=new Medium(new Rgb(.02f,.04f,.09f),new Rgb(.025f,.04f,.06f),Rgb.ZERO,1,1.5f,.25f);
        volume(19,glass,.8f,4,false,false,true);
        volume(19,new Medium(new Rgb(.08f,.025f,.01f),Rgb.ZERO,Rgb.ZERO,1,1.33f,0),.42f,5,false,false,true);
        volume(20,density,.8f,2,true,false,false);
        volume(21,density,.8f,2,false,true,false);
        volume(22,uniform.withDensity(0),.8f,1,false,false,false);
        volume(23,new Medium(uniform.absorption(),uniform.scattering(),new Rgb(.05f,.01f,.025f),1,1,.1f),.8f,1,false,false,false);
        volume(24,density,.85f,2,false,false,false);solid(24,114,.45f);
        volume(25,density,.85f,2,false,false,false);panel(nextId++,25,0,123,true,1,0,1);
        solid(26,110,.68f);
        volume(27,density,.85f,2,false,false,false);solid(27,110,.5f);
        volume(28,density,.85f,2,false,false,false);solid(28,113,.45f);
        panel(nextId++,29,0,101,false,1,0,1);
        scene.upsert(SceneLight.point(1,-6,6,7).color(1,.72f,.42f).intensity(160).range(40).build());
        scene.upsert(SceneLight.spot(2,6,5,8,-.35f,-.25f,-1).color(.4f,.65f,1).intensity(220)
                .range(40).coneDegrees(18,50).build());
        scene.upsert(SceneLight.directional(3,.1f,-.3f,-1).intensity(.35f).castsShadow(false).build());
        var transaction=scene.build();var batch=transientVolumes.build();
        int[] texels=new int[LightmapState.ENTRY_COUNT];Arrays.fill(texels,0xffb0b0b0);
        var lightmap=new LightmapState(1,texels);
        var data=new ResourceData(ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putLong(0x4d4943524fL).putLong(1).flip());
        var options=MediumGalleryDemo.Options.parse(new String[]{"--fg=false","--animate=false","--controls=false"});
        var config=MediumGalleryDemo.configuration(options).copyBuilder()
                .sceneShaders(SceneShaderProgram.builder().dataBindingCount(1).build()).build();
        return new MediumPerformanceCases.Workload(ID,transaction,config,(sequence,width,height)->RenderFrameRequest.builder(sequence,width,height,
                CameraState.lookAt(0,0,18,0,0,0).verticalFieldOfViewDegrees(36).aspectRatio(width/(double)height).build())
                .minimumSceneRevision(1).antiAliasing(new AntiAliasingState(1)).primitiveBatch(batch).lightmap(lightmap).shaderData(List.of(data))
                .environment(EnvironmentState.builder().skyRadiance(.08f,.1f,.15f).ambientIntensity(.16f)
                        .sunDirection(.09534626f,-.2860388f,-.9534626f).sunIntensity(.6f)
                        .cameraMedium(new Medium(new Rgb(.001f,.0015f,.002f),new Rgb(.002f,.003f,.004f),Rgb.ZERO,1,1,.2f)).build())
                .mediumTransport(new MediumTransport(24,2,8,0,32,1e-4f,2))
                .fog(DistanceFogState.builder().color(.08f,.1f,.15f).opacity(.05f).sphericalRange(12,35).build())
                .caustics(Caustics.photonMap(0,0,-.3f,12,65536,.16f).withMaxEvents(8))
                .depthProjection(DepthProjectionState.vulkanPerspective(.05f,80)).build());
    }

    private void material(long id,int color,MaterialAsset.ShadingModel shading,boolean twoSided,float metal,float transmission,float roughness) {
        scene.upsert(MaterialAsset.builder(id).baseColorRgba8(color).shadingModel(shading).doubleSided(twoSided)
                .metallic(metal).transmission(transmission).roughness(roughness).indexOfRefraction(1.5f).build());
    }
    private static float x(int cell){return (cell%6-2.5f)*3;}
    private static float y(int cell){return (2-cell/6)*2;}
    private void solid(int cell,long material,float scale) {
        scene.upsert(SceneInstance.builder(nextId++,500+material).transform(transform(scale,scale,scale,x(cell),y(cell),0)).build());
    }
    private void volume(int cell,Medium medium,float scale,int priority,boolean inward,boolean transientVolume,boolean surface) {
        long mesh=inward?900:surface?610:601;
        var boundary=new MediumBoundary(medium,priority,inward?MediumBoundary.Orientation.INWARD:MediumBoundary.Orientation.OUTWARD,surface);
        var transform=transform(scale,scale,scale,x(cell),y(cell),0);
        if(transientVolume)transientVolumes.add(PrimitiveInstance.builder(mesh).transform(transform).mediumBoundary(boundary).build());
        else scene.upsert(SceneInstance.builder(nextId++,mesh).transform(transform).mediumBoundary(boundary).build());
    }
    private void overlay(int cell,long material,int receiver,int surface,float gap,boolean inspectedPair) {
        long front=inspectedPair?1:nextId++,back=inspectedPair?2:nextId++;
        panel(front,cell,0,material,false,1,receiver,0);
        panel(back,cell,-gap,101,false,1,0,surface);
    }
    private void panel(long id,int cell,float z,long material,boolean vertexAlpha,float visibility,int receiverMask,int surfaceMask) {
        float x=x(cell),y=y(cell),sx=1.15f,sy=.8f;
        var mesh=MeshAsset.triangles(id,new float[]{x-sx,y-sy,z,x+sx,y-sy,z,x+sx,y+sy,z,x-sx,y+sy,z},new int[]{0,1,2,0,2,3},material)
                .toBuilder().textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).lightmapCoordinates(new float[]{0,0,1,0,1,1,0,1});
        if(vertexAlpha)mesh.vertexColorsRgba8(new int[]{0x80ffffff,0x80ffffff,0x80ffffff,0x80ffffff});
        scene.upsert(mesh.build()).upsert(SceneInstance.builder(id,id).surfaceVisibility(visibility)
                .renderState(InstanceRenderState.builder().overlayReceiverMask(receiverMask).surfaceMask(surfaceMask).build()).build());
    }
    private static MeshAsset cube(long id,long material,boolean inward) {
        int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        if(inward)for(int i=0;i<indices.length;i+=3){int swap=indices[i];indices[i]=indices[i+1];indices[i+1]=swap;}
        return MeshAsset.triangles(id,new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},indices,material);
    }
    private static AffineTransform transform(float x,float y,float z,float tx,float ty,float tz) {
        return new AffineTransform(new float[]{x,0,0,tx,0,y,0,ty,0,0,z,tz});
    }
}
