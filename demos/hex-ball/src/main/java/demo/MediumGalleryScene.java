package demo;

import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** One procedural optical gallery, shared by the interactive demo and future GPU comparisons. */
final class MediumGalleryScene {
    enum Preset {
        PBR_DIRECT_UNIFORM("pbr-direct-uniform", "琥珀穹厅 · PBR 均匀直接散射", false, false),
        PBR_DIRECT_DENSITY("pbr-direct-density", "云纹穹厅 · PBR 密度直接散射", true, false),
        PBR_MULTIPLE_UNIFORM("pbr-multiple-uniform", "蓝雾回廊 · PBR 均匀多次散射", false, true),
        PBR_MULTIPLE_DENSITY("pbr-multiple-density", "星云回廊 · PBR 密度多次散射", true, true),
        BOUNDARY_OPTICAL_SCATTER("boundary-optical-scatter", "水晶温室 · 显式边界折射与散射", false, false),
        ABSORPTION_EMISSION("absorption-emission", "余烬灯箱 · 吸收与自发光", false, false),
        GLASS_SCATTER_CAUSTICS("glass-scatter-caustics", "棱镜焦散池 · 玻璃、均匀散射与焦散", false, false),
        GLASS_DENSITY_CAUSTICS("glass-density-caustics", "云海光井 · 玻璃、密度介质与体积焦散", true, true),
        BOUNDARY_DENSITY_CAUSTICS("boundary-density-caustics", "水晶云盒 · 折射边界、密度与体积焦散", true, true),
        GALLERY("gallery", "组合实验馆 · 自定义参数", true, true);
        final String id, label;
        final boolean density, multiple;
        Preset(String id, String label, boolean density, boolean multiple) {
            this.id=id;this.label=label;this.density=density;this.multiple=multiple;
        }
        static Preset parse(String id) {
            for(var preset:values())if(preset.id.equals(id))return preset;
            throw new IllegalArgumentException("未知介质场景: "+id);
        }
        boolean pbr() {return ordinal()<=PBR_MULTIPLE_DENSITY.ordinal();}
        boolean caustic() {return this==GLASS_SCATTER_CAUSTICS||this==GLASS_DENSITY_CAUSTICS||this==BOUNDARY_DENSITY_CAUSTICS;}
        @Override public String toString(){return label;}
    }
    private static final int GRID = 32;
    private static final AffineTransform LOCAL_TO_UVW = new AffineTransform(
            new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f});
    private final MediumGalleryDemo.Options options;
    private final SceneTransaction transaction;
    private final FramePrimitiveBatch transientVolumes;
    private final long revision;
    private final Preset preset;

    MediumGalleryScene(MediumGalleryDemo.Options options) {
        this(options, options.scene(), 1);
    }

    MediumGalleryScene(MediumGalleryDemo.Options options, Preset preset, long revision) {
        this.options = options;
        this.preset=preset;this.revision=revision;
        if(preset!=Preset.GALLERY) {
            transaction=preset.caustic()?buildGlassScatterCaustics(revision):buildPreset(revision);
            transientVolumes=FramePrimitiveBatch.empty();
            return;
        }
        var tx = SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffd1ccc7).roughness(.7f).doubleSided(true)
                .baseColorTextureId(1).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffffffff).transmission(options.refraction()?1:0)
                .roughness(.001f).doubleSided(true).indexOfRefraction(1.52f).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffffffff).transmission(options.refraction()?1:0)
                .roughness(.12f).doubleSided(true).indexOfRefraction(1.333f).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xff47b8eb).metallic(0).roughness(.08f).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(5).baseColorRgba8(0xff19120d).roughness(.8f).doubleSided(true).build());
        byte[] checker = new byte[64*64*4];
        for (int y=0;y<64;y++) for (int x=0;x<64;x++) {
            int value=((x/8+y/8)&1)==0?235:65,at=(y*64+x)*4;
            checker[at]=checker[at+1]=checker[at+2]=(byte)value;checker[at+3]=(byte)255;
        }
        tx.upsert(TextureAsset.builder(1,64,64).pixelsRgba8(checker).colorSpace(TextureAsset.ColorSpace.SRGB).build());
        tx.upsert(TextureAsset.builder(2,GRID,GRID*GRID).pixelsRgba8(densityAtlas())
                .colorSpace(TextureAsset.ColorSpace.LINEAR).build());
        tx.upsert(box(1,1,false)); tx.upsert(box(2,3,options.inward()));
        tx.upsert(box(3,5,false)); tx.upsert(sphere(4,2,64,32)); tx.upsert(sphere(5,4,48,24));
        tx.upsert(SceneInstance.builder(1,1).transform(transform(10,.1f,7,0,-.1f,0)).build());
        tx.upsert(SceneInstance.builder(2,3).transform(transform(10,4,.1f,0,3.8f,-5)).build());
        tx.upsert(SceneInstance.builder(3,5).transform(transform(.8f,.8f,.8f,-.3f,.8f,2.8f)).build());
        var orientation=options.inward()?MediumBoundary.Orientation.INWARD:MediumBoundary.Orientation.OUTWARD;
        var glass=dielectric(options.refraction()?1.52f:1,options.absorption()?new Rgb(.035f,.012f,.003f):Rgb.ZERO);
        tx.upsert(SceneInstance.builder(10,4).transform(transform(1.25f,1.25f,1.25f,-3,1.3f,0))
                .mediumBoundary(new MediumBoundary(glass,5,MediumBoundary.Orientation.OUTWARD,true)).build());
        if (options.nested()) tx.upsert(SceneInstance.builder(11,4).transform(transform(.95f,.95f,.95f,-3,1.3f,0))
                .mediumBoundary(new MediumBoundary(dielectric(options.refraction()?1.333f:1,options.absorption()?new Rgb(.18f,.025f,.008f):Rgb.ZERO),6,MediumBoundary.Orientation.OUTWARD,true)).build());
        tx.upsert(SceneInstance.builder(12,2).transform(transform(.8f,1,.8f,-.1f,1.05f,-.4f))
                .mediumBoundary(new MediumBoundary(dielectric(options.refraction()?1.333f:1,options.absorption()?new Rgb(.03f,.25f,.38f):Rgb.ZERO),4,orientation,true)).build());
        var field=options.heterogeneous()?MediumDensityField.grid(2,GRID,LOCAL_TO_UVW):MediumDensityField.uniform();
        var fog=new Medium(options.absorption()?new Rgb(.025f,.012f,.006f):Rgb.ZERO,
                options.scattering()?new Rgb(.45f,.5f,.6f):Rgb.ZERO,Rgb.ZERO,1,1,.55f,field);
        var glow=new Medium(options.absorption()?new Rgb(.04f,.12f,.2f):Rgb.ZERO,
                options.scattering()?new Rgb(.08f,.07f,.04f):Rgb.ZERO,
                options.emission()?new Rgb(.9f,.28f,.06f):Rgb.ZERO,1,1,-.25f,field);
        var frame = FramePrimitiveBatch.builder();
        addVolume(tx,frame,20,transform(1.6f,1.5f,1.3f,3.1f,1.55f,-.6f),fog,1,orientation);
        addVolume(tx,frame,21,transform(.8f,.6f,.8f,.3f,.7f,-3),glow,2,orientation);
        transientVolumes=frame.build();
        tx.upsert(SceneLight.point(1,-4,6,3).color(1,.87f,.65f).intensity(190).range(30).build());
        tx.upsert(SceneLight.spot(2,4,5,3,-.1f,-.65f,-1).color(.32f,.62f,1).intensity(240)
                .range(25).coneDegrees(12,32).build());
        transaction=tx.build();
    }

    private void addVolume(SceneTransaction.Builder tx,FramePrimitiveBatch.Builder batch,long id,AffineTransform transform,
            Medium medium,int priority,MediumBoundary.Orientation orientation) {
        var boundary=new MediumBoundary(medium,priority,orientation,false);
        if (options.transientVolumes()) batch.add(PrimitiveInstance.builder(2).transform(transform).mediumBoundary(boundary).build());
        else tx.upsert(SceneInstance.builder(id,2).transform(transform).mediumBoundary(boundary).build());
    }

    SceneTransaction transaction() { return transaction; }
    FramePrimitiveBatch transientVolumes() { return transientVolumes; }
    Preset preset() {return preset;}
    boolean causticsEnabled(){return preset.caustic()&&(options.caustics()||!options.scene().caustic());}

    private SceneTransaction buildPreset(long revision) {
        var tx=SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffb6a795).roughness(.72f).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffffffff).transmission(1).roughness(.001f)
                .indexOfRefraction(1.48f).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffaf5938).roughness(.48f).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xff427bc8).roughness(.28f).doubleSided(true).build());
        tx.upsert(box(1,1,false));tx.upsert(box(2,1,false));
        tx.upsert(sphere(3,3,40,24));tx.upsert(sphere(4,4,40,24));tx.upsert(sphere(5,2,48,28));
        tx.upsert(SceneInstance.builder(1,1).transform(transform(7,.12f,6,0,-.12f,-1)).build());
        tx.upsert(SceneInstance.builder(2,1).transform(transform(7,3,.12f,0,3,-5)).build());
        tx.upsert(SceneInstance.builder(3,3).transform(transform(1.2f,1.2f,1.2f,-1.8f,1.25f,0)).build());
        tx.upsert(SceneInstance.builder(4,4).transform(transform(.9f,1.7f,.9f,1.8f,1.75f,-1)).build());
        if(preset.density)tx.upsert(TextureAsset.builder(2,GRID,GRID*GRID).pixelsRgba8(densityAtlas())
                .colorSpace(TextureAsset.ColorSpace.LINEAR).build());
        if(preset==Preset.BOUNDARY_OPTICAL_SCATTER) {
            var medium=new Medium(new Rgb(.018f,.028f,.055f),new Rgb(.12f,.18f,.26f),Rgb.ZERO,1,1.48f,.4f);
            tx.upsert(SceneInstance.builder(20,5).transform(transform(1.45f,1.45f,1.45f,0,1.5f,1))
                    .mediumBoundary(new MediumBoundary(medium,4,MediumBoundary.Orientation.OUTWARD,true)).build());
        }
        tx.upsert(SceneLight.point(1,-3,4,4).color(1,.68f,.38f).intensity(90).range(25).build());
        tx.upsert(SceneLight.spot(2,3,4,3,-.4f,-.5f,-1).color(.28f,.56f,1).intensity(140)
                .range(25).coneDegrees(10,36).build());
        return tx.build();
    }

    private Medium cameraMedium() {
        if(preset==Preset.BOUNDARY_DENSITY_CAUSTICS)return Medium.vacuum();
        if(preset==Preset.GLASS_DENSITY_CAUSTICS)return causticMedium(false);
        if(preset==Preset.BOUNDARY_OPTICAL_SCATTER||preset==Preset.GALLERY)return Medium.vacuum();
        var field=preset.density?MediumDensityField.grid(2,GRID,new AffineTransform(new float[]{
                1f/18,0,0,.5f,0,1f/14,0,.3f,0,0,1f/28,.5f})):MediumDensityField.uniform();
        if(preset==Preset.ABSORPTION_EMISSION)return new Medium(new Rgb(.07f,.025f,.012f),Rgb.ZERO,
                new Rgb(.015f,.003f,.001f),1,1,0,field);
        return new Medium(new Rgb(.009f,.014f,.025f),new Rgb(.065f,.085f,.12f),Rgb.ZERO,1,1,.4f,field);
    }

    RenderFrameRequest frame(long sequence,int width,int height,double seconds,double deltaSeconds) {
        double angle=options.animate()?.10*Math.sin(seconds*.22):0;
        var camera=options.inside()?CameraState.lookAt(3.1,1.55,.3,-1,1,0)
                :preset==Preset.GALLERY?CameraState.lookAt(Math.sin(angle)*14,5.3,Math.cos(angle)*14,0,1,-.8)
                :preset.caustic()?CameraState.lookAt(0,0,8,0,0,-4)
                :CameraState.lookAt(Math.sin(angle)*10,3.2,Math.cos(angle)*10,0,1.2,-.6);
        var environment=EnvironmentState.builder().skyRadiance(.09f,.13f,.2f).ambientIntensity(.18f)
                .sunDirection(-.28221625f,-.94072086f,-.18814418f).sunRadiance(1,.94f,.8f).sunIntensity(1.7f)
                .cameraMedium(preset==Preset.GLASS_SCATTER_CAUSTICS
                        ?new Medium(Rgb.ZERO,new Rgb(.2f,.12f,.06f),Rgb.ZERO,1,1,0):cameraMedium()).build();
        return RenderFrameRequest.builder(sequence,width,height,camera.verticalFieldOfViewDegrees(44)
                        .aspectRatio(width/(double)height).build()).clock(FrameClock.of(Math.max(0,(long)(seconds*1e9)),Math.max(0,(long)(deltaSeconds*1e9))))
                .minimumSceneRevision(revision).depthProjection(DepthProjectionState.vulkanPerspective(.05f,200))
                .antiAliasing(new AntiAliasingState(options.spp())).environment(environment)
                .primitiveBatch(transientVolumes).mediumTransport(new MediumTransport(16,1,8,4,40,1e-4f,
                        preset==Preset.GALLERY?options.bounces():preset.multiple?Math.max(1,options.bounces()):0))
                .caustics(causticsEnabled()
                        ?Caustics.photonMap(0,0,-1,6,options.photons(),.18f).withMaxEvents(8)
                        :Caustics.disabled())
                .build();
    }

    private SceneTransaction buildGlassScatterCaustics(long revision) {
        var tx=SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.PHYSICALLY_BASED)
                .baseColorRgba8(0xff808080).roughness(1).metallic(0).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(2).roughness(0).metallic(0).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(3).transmission(1).roughness(0).indexOfRefraction(1.5f).doubleSided(true).build());
        tx.upsert(plane(1,1,-4,false));tx.upsert(causticMirror());
        tx.upsert(plane(3,3,4.5f,false));tx.upsert(plane(4,3,4.4f,true));
        for(int id=1;id<=4;id++)tx.upsert(SceneInstance.builder(id,id).build());
        tx.upsert(SceneLight.directional(1,0,0,-1).color(1,1,1).intensity(1).castsShadow(false).build());
        tx.upsert(SceneLight.point(2,-2,3,2).color(1,.55f,.3f).intensity(35).range(20).build());
        if(preset.density) {
            tx.upsert(TextureAsset.builder(2,GRID,GRID*GRID).pixelsRgba8(densityAtlas()).colorSpace(TextureAsset.ColorSpace.LINEAR).build());
            if(preset==Preset.BOUNDARY_DENSITY_CAUSTICS) {
                tx.upsert(box(5,3,false));
                tx.upsert(SceneInstance.builder(5,5).transform(transform(2,2,2,0,0,0))
                        .mediumBoundary(MediumBoundary.surface(causticMedium(true)).withPriority(5)).build());
            }
        }
        return tx.build();
    }
    private Medium causticMedium(boolean local) {
        var mapping=local?LOCAL_TO_UVW:new AffineTransform(new float[]{.08f,0,0,.5f,0,.08f,0,.5f,0,0,.08f,.5f});
        return new Medium(new Rgb(.01f,.015f,.025f),new Rgb(.2f,.12f,.06f),Rgb.ZERO,1,local?1.33f:1,.2f)
                .withDensityField(MediumDensityField.grid(2,GRID,mapping));
    }

    private static MeshAsset causticMirror() {
        double ox=-1/Math.sqrt(5),oz=-2/Math.sqrt(5),nx=ox,nz=1+oz,length=Math.hypot(nx,nz);nx/=length;nz/=length;
        float[] positions=new float[12];int at=0;
        for(double[] corner:new double[][]{{-1,-1.2},{1,-1.2},{1,1.2},{-1,1.2}}) {
            positions[at++]=(float)(2+nz*corner[0]);positions[at++]=(float)corner[1];positions[at++]=(float)(-nx*corner[0]);
        }
        return MeshAsset.triangles(2,positions,new int[]{0,1,2,0,2,3},2);
    }

    private static MeshAsset plane(long id,long material,float z,boolean reverse) {
        int[] indices=reverse?new int[]{0,2,1,0,3,2}:new int[]{0,1,2,2,3,0};
        return MeshAsset.triangles(id,new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},indices,material);
    }

    private static Medium dielectric(float ior,Rgb absorption) { return new Medium(absorption,Rgb.ZERO,Rgb.ZERO,1,ior,0); }

    private static AffineTransform transform(float x,float y,float z,float px,float py,float pz) {
        return new AffineTransform(new float[]{x,0,0,px,0,y,0,py,0,0,z,pz});
    }

    private static byte[] densityAtlas() {
        var bytes=new byte[GRID*GRID*GRID*4];
        for (int z=0;z<GRID;z++) for (int y=0;y<GRID;y++) for (int x=0;x<GRID;x++) {
            double u=(x+.5)/GRID*2-1,v=(y+.5)/GRID*2-1,w=(z+.5)/GRID*2-1;
            double shell=Math.max(0,1-u*u-v*v-w*w);
            double waves=.5+.23*Math.sin(u*11+w*5)*Math.cos(v*9-u*3)+.2*Math.sin(w*17+v*6);
            int at=((z*GRID+y)*GRID+x)*4;
            bytes[at]=(byte)Math.clamp(Math.round(shell*waves*255),0,255);bytes[at+3]=(byte)255;
        }
        return bytes;
    }

    private static MeshAsset box(long id,long material,boolean inward) {
        int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        if (inward) for (int i=0;i<indices.length;i+=3) {int value=indices[i];indices[i]=indices[i+1];indices[i+1]=value;}
        var positions=new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        long[] materials=new long[12];java.util.Arrays.fill(materials,material);
        return MeshAsset.builder(id,positions,indices,materials)
                .textureCoordinates(new float[]{0,0,8,0,8,8,0,8,0,0,8,0,8,8,0,8}).build();
    }

    /** Welded poles and rings make a closed manifold without coincident seam vertices. */
    private static MeshAsset sphere(long id,long material,int segments,int stacks) {
        int vertices=2+(stacks-1)*segments;float[] positions=new float[vertices*3];positions[1]=1;
        for (int ring=1;ring<stacks;ring++) for (int i=0;i<segments;i++) {
            double theta=Math.PI*ring/stacks,phi=2*Math.PI*i/segments;
            int at=(1+(ring-1)*segments+i)*3;
            positions[at]=(float)(Math.sin(theta)*Math.cos(phi));positions[at+1]=(float)Math.cos(theta);
            positions[at+2]=(float)(Math.sin(theta)*Math.sin(phi));
        }
        int bottom=vertices-1;positions[bottom*3+1]=-1;
        List<Integer> triangles=new ArrayList<>();
        for (int i=0;i<segments;i++) {
            int a=1+i,b=1+(i+1)%segments;
            java.util.Collections.addAll(triangles,0,b,a);
            for (int ring=0;ring<stacks-2;ring++) {
                int c=a+ring*segments,d=b+ring*segments;
                java.util.Collections.addAll(triangles,c,d,d+segments,c,d+segments,c+segments);
            }
            java.util.Collections.addAll(triangles,bottom,a+(stacks-2)*segments,b+(stacks-2)*segments);
        }
        int[] indices=triangles.stream().mapToInt(Integer::intValue).toArray();
        long[] materials=new long[indices.length/3];java.util.Arrays.fill(materials,material);
        return MeshAsset.builder(id,positions,indices,materials).normals(positions).build();
    }
}
