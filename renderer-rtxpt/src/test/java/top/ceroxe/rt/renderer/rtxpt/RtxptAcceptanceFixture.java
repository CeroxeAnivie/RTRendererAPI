package top.ceroxe.rt.renderer.rtxpt;

import java.util.Arrays;
import top.ceroxe.rt.renderer.api.*;

/** Small mixed game scene: opaque, metal, emitter, rough glass and nested absorption. */
final class RtxptAcceptanceFixture {
    static SceneTransaction scene() {
        var tx=SceneTransaction.builder(1).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffb8c8df).roughness(.65f).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffffffff).blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
            .transmission(1).roughness(.02f).indexOfRefraction(1.5f).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffe0bd75).metallic(1).roughness(.14f).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xfff0f0f0).emissive(0xffffe7b0,2).roughness(.3f).build());
        for(int i=1;i<=4;i++)tx.upsert(cube(i,i));
        tx.upsert(SceneInstance.builder(1,1).transform(transform(0,-.15f,0,10,.2f,8)).build());
        tx.upsert(SceneInstance.builder(2,2).transform(transform(-1.2f,.9f,0,1.3f,1.8f,1.3f))
            .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.07f,.55f,.8f),1.5f)).withPriority(2)).build());
        tx.upsert(SceneInstance.builder(3,3).transform(transform(1.3f,.65f,.3f,1.2f,1.3f,1.2f)).build());
        tx.upsert(SceneInstance.builder(4,4).transform(transform(0,2.6f,-1,1.8f,.12f,1)).build());
        tx.upsert(SceneInstance.builder(5,2).transform(transform(-1.2f,1,0,.55f,.75f,.55f))
            .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.7f,.08f,.04f),1.33f)).withPriority(6)).build());
        return tx.build();
    }
    static CameraState camera(){return CameraState.lookAt(4,2.7,6,0,.8,0).verticalFieldOfViewDegrees(52).aspectRatio(16d/9).build();}
    static RenderFrameRequest frame(){return RenderFrameRequest.builder(0,1920,1080,camera()).minimumSceneRevision(1)
        .environment(EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.5f)
            .sunDirection(.57735026f,-.57735026f,-.57735026f).sunIntensity(3).build()).build();}
    static String cameraPose(){var c=camera();return c.x()+","+c.y()+","+c.z()+","+c.forwardX()+","+c.forwardY()+","+c.forwardZ()+","+c.upX()+","+c.upY()+","+c.upZ();}
    private static AffineTransform transform(float x,float y,float z,float sx,float sy,float sz){return new AffineTransform(new float[]{sx,0,0,x,0,sy,0,y,0,0,sz,z});}
    private static MeshAsset cube(long id,long material){
        float[] p={-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f};
        int[] indices={4,5,6,4,6,7,0,2,1,0,3,2,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        long[] materials=new long[12];Arrays.fill(materials,material);return MeshAsset.builder(id,p,indices,materials).build();
    }
}
