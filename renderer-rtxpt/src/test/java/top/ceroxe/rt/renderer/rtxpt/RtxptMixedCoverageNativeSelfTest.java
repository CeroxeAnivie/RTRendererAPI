package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Mixed opaque/cutout geometry shares one mesh, as permitted by the public contract. */
public final class RtxptMixedCoverageNativeSelfTest {
    public static void main(String[] args) throws Exception {
        int hostWidth=args.length>0?Integer.parseInt(args[0]):64;
        int hostHeight=args.length>1?Integer.parseInt(args[1]):64;
        int gridWidth=args.length>2?Integer.parseInt(args[2]):64;
        int gridHeight=args.length>3?Integer.parseInt(args[3]):64;
        var plan = new NativeLightingPlan(gridWidth, gridHeight, Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),
                0, 6, 0, NativeLightingStyle.defaults());
        try (var renderer = RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(), plan)) {
            var access = renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var texture = TextureAsset.color(7, 2, 1,
                    new byte[]{-1, -1, -1, 0, -1, -1, -1, -1});
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(texture)
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED)
                            .baseColorTextureId(7).doubleSided(true).build())
                    .upsert(mesh(0)).upsert(SceneInstance.builder(1, 1).build()).build());
            lighting.publishLightingAttributes(new NativeLightingPublication(1,
                    List.of(NativeLightingPublication.Instance.receiver(1)), List.of(), null, null));
            var camera = CameraState.lookAt(0, 0, 3, 0, 0, 0).build();
            var handleField=RtxptRenderer.class.getDeclaredField("handle");
            handleField.setAccessible(true);
            float[] previous=null;double[] meshBefore=null;RtxptDiagnostics.NativeMonitor nativeMonitor=null;boolean sawBlas=false,sawAttributes=false;
            for (long sequence = 1; sequence <= 48; sequence++) {
                if(sequence==1||sequence==3||sequence==9||sequence==16||sequence==40)
                    System.out.println("MIXED_COVERAGE_NATIVE sequence="+sequence);
                long revision=sequence;
                var transform=transform((int)sequence);
                var update=SceneTransaction.builder(revision)
                        .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(transform)).build());
                if(sequence==3) update.upsert(mesh(.1f));
                if(sequence==9) update.upsert(mesh(.1f,.2f));
                if(sequence>1)renderer.apply(update.build());
                renderer.submit(RenderFrameRequest.builder(sequence, hostWidth, hostHeight, camera)
                        .minimumSceneRevision(revision).build());
                try { access.awaitIdle(); }
                catch (RuntimeException failure) {
                    throw new IllegalStateException("Native contract failed at sequence="+sequence,failure);
                }
                if(sequence==1)nativeMonitor=access.nativeDiagnosticMonitor();
                else for(var event:nativeMonitor.poll().events()){
                    sawBlas|=event.stage().equals("BlasBuild");sawAttributes|=event.stage().equals("AttributePreparation");
                }
                if(sequence==8)meshBefore=RtxptNative.meshResourceState(handleField.getLong(renderer),"i1");
                if(sequence==9){
                    var after=RtxptNative.meshResourceState(handleField.getLong(renderer),"i1");
                    if(meshBefore[0]!=after[0]||meshBefore[1]==after[1])throw new AssertionError("Attribute-only update did not retain BLAS and replace the immutable native vertex buffer");
                }
                var actual=RtxptNative.instanceTransformState(handleField.getLong(renderer),"i1");
                var expected=sequence==1?new float[]{1,0,0,0,0,1,0,0,0,0,1,0}:transform;
                for(int k=0;k<12;k++){
                    if(Math.abs(actual[k]-expected[k])>1e-5*Math.max(1,Math.abs(expected[k])))
                        throw new AssertionError("Transform mismatch sequence="+sequence+" element="+k+" actual="+actual[k]+" expected="+expected[k]);
                    if(previous!=null&&Math.abs(actual[12+k]-previous[k])>1e-5*Math.max(1,Math.abs(previous[k])))
                        throw new AssertionError("Previous transform was consumed early");
                }
                previous=expected;
                try (var lease = lighting.acquireLightingFrame().orElseThrow()) {
                    var frame = lease.frame();
                    if (frame.sequence() != sequence || frame.sceneRevision() != revision
                            || frame.planes().size() != 1 || frame.width() != gridWidth || frame.height() != gridHeight)
                        throw new AssertionError("Mixed-coverage frame identity was not preserved");
                }
            }
            if(!sawBlas||!sawAttributes)throw new AssertionError("Native boundary stages missing");
            if (lighting.nativeLightingExecution().fullBeautyFrames() != 0)
                throw new AssertionError("Direct consumer unexpectedly executed full beauty");
            // Internal malformed transport deliberately fails on the native
            // owner after a valid frame. Public close must still retire output
            // leases and destroy the failed session without a second exception.
            RtxptNative.resourcesMemory(handleField.getLong(renderer),49,java.nio.ByteBuffer.allocateDirect(4));
            boolean failed=false;
            try {renderer.submit(RenderFrameRequest.builder(49,hostWidth,hostHeight,camera)
                    .minimumSceneRevision(48).build());}
            catch(IllegalStateException expected){
                failed=true;
                if(expected.getSuppressed().length!=0)throw new AssertionError("Failed-session cleanup rethrew the render failure",expected);
            }
            if(!failed||renderer.status()!=Renderer.Status.CLOSED)
                throw new AssertionError("Failed native owner did not close cleanly");
        }
        System.out.println("MIXED_COVERAGE_NATIVE PASS frames=48 failedOwnerClose=true nearHalfTurns=true shear=true attributesRetainBlas=true nativeBoundaryStages=true signedNonuniformScale=true previousPose=true opaqueAndCutout=true topologyUpdate=true host="
                +hostWidth+"x"+hostHeight+" grid="+gridWidth+"x"+gridHeight);
    }
    private static float[] transform(int sequence){
        double angle=Math.PI+(sequence-24)*1e-5,x=.3,y=.7,z=.2;
        double norm=Math.sqrt(x*x+y*y+z*z),s=Math.sin(angle/2)/norm;
        x*=s;y*=s;z*=s;double w=Math.cos(angle/2);
        double sx=sequence%2==0?-1.25:1.25,sy=.8,sz=2;
        var result=new float[]{(float)((1-2*(y*y+z*z))*sx),(float)(2*(x*y-z*w)*sy),(float)(2*(x*z+y*w)*sz),.1f,
            (float)(2*(x*y+z*w)*sx),(float)((1-2*(x*x+z*z))*sy),(float)(2*(y*z-x*w)*sz),.2f,
            (float)(2*(x*z-y*w)*sx),(float)(2*(y*z+x*w)*sy),(float)((1-2*(x*x+y*y))*sz),.3f};
        if(sequence>=16&&sequence<=39){result[1]+=.35f;result[6]-=.2f;result[8]+=.17f;}
        return result;
    }

    private static MeshAsset mesh(float z) {return mesh(z,0);}
    private static MeshAsset mesh(float z,float tilt) {
        return MeshAsset.builder(1, new float[]{-1,-1,z, 1,-1,z, 1,1,z, -1,1,z},
                new int[]{0,1,2, 0,2,3}, new long[]{1,2})
                .normals(new float[]{tilt,0,1, tilt,0,1, tilt,0,1, tilt,0,1})
                .textureCoordinates(new float[]{0,0, 1,0, 1,1, 0,1}).build();
    }
}
