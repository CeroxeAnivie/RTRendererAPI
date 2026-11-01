package demo;
import java.util.Arrays;
import top.ceroxe.rt.renderer.api.*;

/** Small game-scale mixed scene covering the supported native optical contract. */
final class RtxptMediumScene {
    static final java.util.List<String> PRESETS = java.util.List.of("mixed", "glass", "nested");

    static String description(String preset) {
        return switch (preset) {
            case "mixed" -> "ABSORPTION + NESTED IOR + ROUGH TRANSMISSION + PBR";
            case "glass" -> "COLORED ABSORPTION + SMOOTH REFLECTION / REFRACTION";
            case "nested" -> "NESTED DIELECTRICS | IOR 1.50 / 1.33 | PRIORITY 2 / 6";
            default -> throw new IllegalArgumentException("scene must be mixed, glass or nested");
        };
    }

    static SceneTransaction create(long revision,String preset) {
        description(preset);
        var tx=SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffb8c8df).roughness(.65f).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffffffff).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1).roughness(.02f).indexOfRefraction(1.5f).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffe0bd75).metallic(1).roughness(.14f).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xfff0f0f0).emissive(0xffffe7b0,2).roughness(.3f).build());
        tx.upsert(cube(1,1));tx.upsert(cube(2,2));tx.upsert(cube(3,3));tx.upsert(cube(4,4));
        tx.upsert(SceneInstance.builder(1,1).transform(transform(0,-.15f,0,10,.2f,8)).build());
        tx.upsert(SceneInstance.builder(2,2).transform(transform(-1.2f,.9f,0,1.3f,1.8f,1.3f))
            .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.07f,.55f,.8f),1.5f)).withPriority(2)).build());
        tx.upsert(SceneInstance.builder(3,3).transform(transform(1.3f,.65f,.3f,1.2f,1.3f,1.2f)).build());
        tx.upsert(SceneInstance.builder(4,4).transform(transform(0,2.6f,-1,1.8f,.12f,1)).build());
        if(preset.equals("nested") || preset.equals("mixed"))tx.upsert(SceneInstance.builder(5,2).transform(transform(-1.2f,1,0,.55f,.75f,.55f))
            .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.7f,.08f,.04f),1.33f)).withPriority(6)).build());
        if(preset.equals("mixed")) {
            tx.upsert(MaterialAsset.builder(5).baseColorRgba8(0xffffffff)
                .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1)
                .roughness(.22f).indexOfRefraction(1.45f).build());
            tx.upsert(cube(5,5));
            tx.upsert(SceneInstance.builder(6,5).transform(transform(.6f,.5f,-1.4f,1,1,1))
                .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.35f,.08f,.25f),1.45f)).withPriority(2)).build());
        }
        return tx.build();
    }
    static CameraState camera(int w,int h){return CameraState.lookAt(4,2.7,6,0,.8,0).verticalFieldOfViewDegrees(52).aspectRatio(w/(float)h).build();}
    static EnvironmentState environment(){return EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.5f).sunDirection(.57735026f,-.57735026f,-.57735026f).sunIntensity(3).build();}
    static AffineTransform transform(float x,float y,float z,float sx,float sy,float sz){return new AffineTransform(new float[]{sx,0,0,x,0,sy,0,y,0,0,sz,z});}
    static MeshAsset cube(long id,long material){
        float[] p={-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f};
        int[] indices={4,5,6,4,6,7,0,2,1,0,3,2,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        long[] materials=new long[12];Arrays.fill(materials,material);return MeshAsset.builder(id,p,indices,materials).build();
    }
}
