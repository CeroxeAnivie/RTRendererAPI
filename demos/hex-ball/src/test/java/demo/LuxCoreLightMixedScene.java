package demo;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Small bounded feature cells in a vacuum, rather than full-screen long-distance fog. */
final class LuxCoreLightMixedScene {
    static final List<String> COVERAGE=List.of("absorption","emission","homogeneous single scattering","homogeneous multiple scattering",
        "heterogeneous single scattering","heterogeneous multiple scattering","density grid","zero density","forward HG","backward HG",
        "persistent boundaries","transient boundaries","nested priority","inward boundary","camera inside volume","volume-only boundary",
        "surface+volume","smooth glass","rough glass","partial transmission","IOR","dielectric","partial metal","full metal",
        "material alpha","texture alpha","vertex alpha","combined alpha","alpha mask","surface visibility","base-color texture",
        "normal map","metallic/roughness texture","surface emission","point light","spot light","directional light","environment light",
        "native specular-diffuse transport (caustic paths)","shared geometry instances");
    static MediumPerformanceCases.Workload workload() {
        var tx=SceneTransaction.builder(1).resetScene();
        tx.upsert(TextureAsset.color(10,2,2,new byte[]{(byte)240,(byte)180,90,(byte)255,80,(byte)180,(byte)240,100,
            (byte)180,90,(byte)240,(byte)255,90,(byte)240,(byte)180,100}));
        tx.upsert(TextureAsset.builder(11,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(new byte[]{(byte)128,(byte)128,(byte)255,(byte)255}).build());
        tx.upsert(TextureAsset.builder(12,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(new byte[]{0,80,(byte)200,(byte)255}).build());
        byte[] density=new byte[4*4*4*4];for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++){int p=((z*4+y)*4+x)*4;density[p]=(byte)(64+x*48);density[p+3]=(byte)255;}
        tx.upsert(TextureAsset.builder(50,4,16).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(density).build());
        tx.upsert(MaterialAsset.builder(1).doubleSided(true).roughness(.6f).baseColorRgba8(0xffa8bccd).build());
        tx.upsert(MaterialAsset.builder(2).doubleSided(false).roughness(.25f).baseColorRgba8(0xffdba060).build());
        tx.upsert(MaterialAsset.builder(3).doubleSided(true).transmission(1).roughness(0).indexOfRefraction(1.4f).build());
        tx.upsert(MaterialAsset.builder(4).doubleSided(true).transmission(1).roughness(.18f).indexOfRefraction(1.4f).build());
        tx.upsert(MaterialAsset.builder(5).doubleSided(true).transmission(.45f).roughness(.12f).indexOfRefraction(1.4f).build());
        tx.upsert(MaterialAsset.builder(6).doubleSided(true).metallic(.4f).roughness(.22f).baseColorRgba8(0xffc09050).build());
        tx.upsert(MaterialAsset.builder(7).doubleSided(true).metallic(1).roughness(.08f).baseColorRgba8(0xffc0d0e0).build());
        tx.upsert(MaterialAsset.builder(8).doubleSided(true).baseColorRgba8(0x80b0d0e0).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build());
        tx.upsert(MaterialAsset.builder(9).doubleSided(true).baseColorTextureId(10).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build());
        tx.upsert(MaterialAsset.builder(10).doubleSided(true).baseColorTextureId(10).baseColorRgba8(0xb0ffffff).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build());
        tx.upsert(MaterialAsset.builder(11).doubleSided(true).baseColorTextureId(10).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build());
        tx.upsert(MaterialAsset.builder(12).doubleSided(true).normalTextureId(11).metallicRoughnessTextureId(12).roughness(.7f).metallic(.8f).build());
        tx.upsert(MaterialAsset.builder(13).doubleSided(true).emissive(0xffffb060,3).roughness(.6f).build());
        for(int mat=1;mat<=13;mat++)tx.upsert(cube(mat,mat,mat==10));
        var inward=cube(14,1,false);var winding=inward.triangleIndices();int[] reversed=new int[winding.remaining()];winding.get(reversed);
        for(int i=0;i<reversed.length;i+=3){int swap=reversed[i+1];reversed[i+1]=reversed[i+2];reversed[i+2]=swap;}
        float[] points=new float[inward.positions().remaining()];inward.positions().get(points);tx.upsert(MeshAsset.triangles(14,points,reversed,1));
        var absorb=new Medium(new Rgb(.4f,.15f,.07f),Rgb.ZERO,Rgb.ZERO,1,1.4f,0);
        var scatter=new Medium(new Rgb(.02f,.03f,.04f),new Rgb(.6f,.5f,.4f),Rgb.ZERO,1,1,.35f);
        var backward=new Medium(Rgb.ZERO,new Rgb(.4f,.5f,.6f),Rgb.ZERO,1,1,-.35f);
        var glowing=new Medium(new Rgb(.02f,.02f,.02f),new Rgb(.1f,.1f,.1f),new Rgb(.3f,.15f,.06f),1,1,.1f);
        var field=MediumDensityField.grid(50,4,new AffineTransform(new float[]{1,0,0,.5f,0,1,0,.5f,0,0,1,.5f}));
        var grid=scatter.withDensityField(field);var primitives=FramePrimitiveBatch.builder();
        // Thirty compact cells: every feature is visible, without stacking long transport paths.
        for(int cell=0;cell<30;cell++) {
            int col=cell%10,row=cell/10;float x=(col-4.5f)*.7f,y=(1-row)*.8f;int mat=1+cell%13;
            Medium medium=switch(cell%8){case 0->absorb;case 1->scatter;case 2->backward;case 3->glowing;case 4,5->grid;case 6->scatter.withDensity(0);default->Medium.vacuum();};
            boolean surface=cell%3==0;var boundary=new MediumBoundary(medium,1,MediumBoundary.Orientation.OUTWARD,surface);
            var transform=transform(.28f,x,y,0);
            if(cell>=28)primitives.add(PrimitiveInstance.builder(mat).transform(transform).mediumBoundary(boundary).build());
            else tx.upsert(SceneInstance.builder(100+cell,mat).transform(transform).mediumBoundary(boundary)
                    .surfaceVisibility(cell==15?.7f:1).castsShadow(cell!=16).build());
            // A solid behind each volume makes absorption/refraction/scattering observable.
            tx.upsert(SceneInstance.builder(200+cell,1+(cell+5)%13).transform(transform(.22f,x,y,-.3f)).build());
            if(cell==8)tx.upsert(SceneInstance.builder(400,3).transform(transform(.16f,x,y,0)).mediumBoundary(MediumBoundary.surface(absorb).withPriority(3)).build());
            if(cell==9)tx.upsert(SceneInstance.builder(401,14).transform(transform(.15f,x,y,0)).mediumBoundary(MediumBoundary.volume(backward).withPriority(2).withOrientation(MediumBoundary.Orientation.INWARD)).build());
        }
        // Camera is inside a tiny bounded medium; the rest of the view remains vacuum.
        tx.upsert(SceneInstance.builder(500,1).transform(transform(.004f,0,0,7)).mediumBoundary(MediumBoundary.volume(new Medium(new Rgb(.01f,.02f,.03f),Rgb.ZERO,Rgb.ZERO,1,1,0)).withPriority(10)).build());
        tx.upsert(SceneLight.point(1,-2,2,3).color(1,.8f,.6f).intensity(8).build());
        tx.upsert(SceneLight.spot(2,2,2,3,-.3f,-.3f,-.9055385f).color(.6f,.8f,1).intensity(8).coneDegrees(20,40).build());
        tx.upsert(SceneLight.directional(3,.2f,-.4f,-.8944272f).color(1,.95f,.85f).intensity(.6f).build());
        var transaction=tx.build();var batch=primitives.build();
        return new MediumPerformanceCases.Workload("light-mixed-native",transaction,RendererConfig.recommended(),(sequence,w,h)->
            RenderFrameRequest.builder(sequence,w,h,CameraState.lookAt(0,0,7,0,0,0).verticalFieldOfViewDegrees(30).aspectRatio(w/(double)h).build())
                .minimumSceneRevision(1).antiAliasing(new AntiAliasingState(1)).primitiveBatch(batch)
                .environment(EnvironmentState.builder().skyRadiance(.12f,.16f,.22f).ambientIntensity(1).sunIntensity(0).build())
                .mediumTransport(MediumTransport.balanced().withScatteringBounces(2)).build());
    }
    static AffineTransform transform(float scale,float x,float y,float z){return new AffineTransform(new float[]{scale,0,0,x,0,scale,0,y,0,0,scale,z});}
    static MeshAsset cube(long id,long mat,boolean alpha) {
        var b=MeshAsset.builder(id,new float[]{-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f},
            new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5},new long[]{mat,mat,mat,mat,mat,mat,mat,mat,mat,mat,mat,mat})
            .textureCoordinates(new float[]{0,0,1,0,1,1,0,1,0,0,1,0,1,1,0,1});
        if(alpha)b.vertexColorsRgba8(new int[]{0x80ffffff,-1,0x80ffffff,-1,-1,0x80ffffff,-1,0x80ffffff});return b.build();
    }
}
