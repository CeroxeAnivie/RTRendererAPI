package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;

/** CPU contract test for the private scattering-through-boundaries packet ABI. */
public final class CudaBoundaryScatteringSelfTest {
    private CudaBoundaryScatteringSelfTest() { }
    public static void main(String[] args) {
        Medium environment=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.1f,.05f),Rgb.ZERO,1,1,.3f);
        Medium inside=new Medium(new Rgb(.3f,.1f,.2f),new Rgb(.1f,.4f,.2f),new Rgb(.01f,.02f,.03f),1,1,-.4f)
                .withDensityField(MediumDensityField.uniform());
        var a=new CudaBoundaryMediumAbi.Boundary(11,new MediumBoundary(inside,4,MediumBoundary.Orientation.OUTWARD,false));
        var frame=RenderFrameRequest.builder(7,31,19,CameraState.lookAt(0,0,0,0,0,-1).build())
                .environment(EnvironmentState.builder().cameraMedium(environment).build()).antiAliasing(new AntiAliasingState(2))
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(16).withScatteringBounces(2)
                        .withLightSamples(0)).build();
        var scene=CudaBoundaryScatteringAbi.scene(frame,31,19,29,List.of(a),new int[]{11},id->null,
                new CudaMediumDensityAbi.Cache(),CudaBoundaryScatteringAbi.RESIDENT_CAP,9);
        var plan=scene.plan();require(plan.paths()==1178&&plan.states()==plan.tilePaths()&&plan.steps()==16,
                "one state per sample with API-defined segment integration steps");
        require(plan.packetCapacity()==9&&plan.connections()==9&&scene.chunks()==4,"connection and boundary packet caps are bounded");
        require(CudaBoundaryScatteringAbi.packetBytes(2)==48&&CudaBoundaryScatteringAbi.packetBytes(32)==288,
                "packet stride follows the capacity encoded in GLSL metadata");
        var compactScene=CudaBoundaryScatteringAbi.scene(frame,31,19,29,List.of(a),new int[]{11},id->null,
                new CudaMediumDensityAbi.Cache(),CudaBoundaryScatteringAbi.RESIDENT_CAP,2);
        var compact=compactScene.plan();
        require(compact.packetBytes()==48&&compact.bytes()<plan.bytes(),"compact boundary packets size every packet arena and residency estimate");
        var compactGeometry=compactScene.metadata(0,compact.tilePixels(),CudaBoundaryScatteringAbi.QUERY_GEOMETRY,0);
        require(ByteBuffer.wrap(compactGeometry).order(ByteOrder.nativeOrder()).getInt(36)
                ==compact.geometryPacketOffset()+compact.tilePaths()*compact.packetBytes(),"geometry packets begin after the camera packet arena");
        check(scene.metadata(0,plan.tilePixels(),CudaBoundaryScatteringAbi.QUERY_CAMERA,0),0,plan.tilePaths(),plan,
                plan.geometryPacketOffset(),plan.geometryRayOffset());
        check(scene.metadata(0,plan.tilePixels(),CudaBoundaryScatteringAbi.QUERY_GEOMETRY,0),16,plan.states(),plan,
                plan.geometryPacketOffset()+plan.tilePaths()*plan.packetBytes(),plan.geometryRayOffset());
        check(scene.metadata(0,plan.tilePixels(),CudaBoundaryScatteringAbi.QUERY_LIGHTS,3),32,plan.states()*plan.connections(),plan,
                plan.shadowPacketOffset(),plan.connectionRayOffset());
        require(ByteBuffer.wrap(scene.metadata(0,plan.tilePixels(),CudaBoundaryScatteringAbi.QUERY_LIGHTS,0))
                .order(ByteOrder.nativeOrder()).getFloat(76)==frame.mediumTransport().maxDistance(),
                "light query receives the public shadow transport-distance bound");
        var header=ByteBuffer.wrap(scene.metadata(0,1,0,0)).order(ByteOrder.nativeOrder());
        require(header.getInt(0)==CudaBoundaryScatteringAbi.MAGIC&&header.getInt(52)==512,"private magic and header extent");
        require(header.getFloat(76)==frame.mediumTransport().maxDistance(),"transport max distance is available to camera and light queries");
        require(header.getInt(36)==plan.geometryPacketOffset(),"camera packet offset at byte 36");
        require(header.getInt(256)==plan.geometryRayOffset(),"camera ray-record offset at byte 256");
        require(header.getInt(60)==384&&header.getInt(96)==-1,"initial volume list and absent density descriptor");
        require(header.getInt(140)==0x3f800000||Float.isFinite(header.getFloat(140)),"anisotropy slot is finite");
        reject(()->CudaBoundaryScatteringAbi.scene(frame,31,19,29,List.of(a),new int[]{11,11},id->null,
                new CudaMediumDensityAbi.Cache(),CudaBoundaryScatteringAbi.RESIDENT_CAP,9).metadata(0,1,0,0));
        System.out.println("CudaBoundaryScatteringSelfTest passed: private header, priority table, packet modes, offsets and resident plan");
    }
    private static void check(byte[] bytes,int mode,int queries,CudaBoundaryScatteringAbi.Plan plan,int packetOffset,int rayOffset){var h=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        require(h.getInt(212)==mode&&h.getInt(260)==queries,"mode/query count");require(h.getInt(108)==plan.packetCapacity(),"packet capacity");
        require(h.getInt(216)==plan.packetBytes(),"capacity-specific packet stride");
        require(h.getInt(36)==packetOffset&&h.getInt(256)==rayOffset,"mode-specific packet/ray-record offsets");}
    private static void reject(Runnable task){try{task.run();throw new AssertionError("invalid boundary scattering scene accepted");}catch(IllegalArgumentException expected){}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
