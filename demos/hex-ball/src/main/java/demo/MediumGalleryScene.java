package demo;

import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** One procedural optical gallery, shared by the interactive demo and future GPU comparisons. */
final class MediumGalleryScene {
    private static final int GRID = 32;
    private static final AffineTransform LOCAL_TO_UVW = new AffineTransform(
            new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f});
    private final MediumGalleryDemo.Options options;
    private final SceneTransaction transaction;
    private final FramePrimitiveBatch transientVolumes;

    MediumGalleryScene(MediumGalleryDemo.Options options) {
        this.options = options;
        var tx = SceneTransaction.builder(1).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffd1ccc7).roughness(.7f).doubleSided(true)
                .baseColorTextureId(1).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffffffff).transmission(options.refraction()?1:0)
                .roughness(.001f).doubleSided(true).indexOfRefraction(1.52f).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffffffff).transmission(options.refraction()?1:0)
                .roughness(.12f).doubleSided(true).indexOfRefraction(1.333f).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xff47b8eb).metallic(1).roughness(.08f).doubleSided(true).build());
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

    RenderFrameRequest frame(long sequence,int width,int height,double seconds,double deltaSeconds) {
        double angle=options.animate()?.10*Math.sin(seconds*.22):0;
        var camera=options.inside()?CameraState.lookAt(3.1,1.55,.3,-1,1,0)
                :CameraState.lookAt(Math.sin(angle)*14,5.3,Math.cos(angle)*14,0,1,-.8);
        var environment=EnvironmentState.builder().skyRadiance(.09f,.13f,.2f).ambientIntensity(.18f)
                .sunDirection(-.28221625f,-.94072086f,-.18814418f).sunRadiance(1,.94f,.8f).sunIntensity(1.7f).build();
        return RenderFrameRequest.builder(sequence,width,height,camera.verticalFieldOfViewDegrees(44)
                        .aspectRatio(width/(double)height).build()).clock(FrameClock.of(Math.max(0,(long)(seconds*1e9)),Math.max(0,(long)(deltaSeconds*1e9))))
                .minimumSceneRevision(1).depthProjection(DepthProjectionState.vulkanPerspective(.05f,200))
                .antiAliasing(new AntiAliasingState(options.spp())).environment(environment)
                .primitiveBatch(transientVolumes).mediumTransport(new MediumTransport(16,1,8,4,80,1e-4f,options.bounces()))
                .caustics(options.caustics()?Caustics.photonMap(0,2,0,12,options.photons(),.16f).withMaxEvents(20):Caustics.disabled())
                .build();
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
