package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import top.ceroxe.rt.renderer.api.*;

/** Replays sequence 172 through the production any-hit and closest-hit stages. */
public final class BoundaryRadianceTraversalNativeSelfTest {
    private static final int WIDTH=6, HEIGHT=4;
    private static final String SOURCE="""
            #version 460
            #extension GL_EXT_ray_tracing : require
            #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
            #include "gpuscene_common.glsl"
            layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
            layout(set=0,binding=GPU_SCENE_BINDING_OUTPUT_IMAGE,rgba8) uniform writeonly image2D outputImage;
            layout(location=0) rayPayloadEXT GpuScenePayload payload;
            void main(){
                uint column=gl_LaunchIDEXT.x,row=gl_LaunchIDEXT.y;
                uint query=column%3u==0u?GS_PAYLOAD_RADIANCE_QUERY:
                    column%3u==1u?GS_PAYLOAD_PHOTON_QUERY:GS_PAYLOAD_OPTICAL_CONNECTION_QUERY;
                payload.state=uvec4(0,query,0,0);payload.mediumBoundary=uvec4(0);
                payload.launchInfo=uvec4(gl_LaunchIDEXT.xy,gl_LaunchSizeEXT.xy);
                vec3 origin=gsFrameCameraPosition(),direction=gsFrameVec3(GPU_SCENE_FRAME_CAMERA_FORWARD_WORD);
                traceRayEXT(sceneTlas,column<3u?gl_RayFlagsNoOpaqueEXT:gl_RayFlagsOpaqueEXT,
                    255,0,0,0,origin,0,direction,20,0);
                uint value=row==0u?(payload.state.x==0u?99u:payload.state.y):
                    row==1u?floatBitsToUint(payload.worldNormalAndRoughness.x):
                    row==2u?floatBitsToUint(payload.worldPositionAndDistance.w):payload.mediumBoundary.w;
                imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(uvec4(value,value>>8u,value>>16u,value>>24u)&255u)/255.0);
            }
            """;

    public static void main(String[] args)throws Exception {
        var shaders=SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION,SOURCE).build();
        float dx=.6562535762786865f,dy=-.2838139832019806f,dz=-.6991286277770996f;
        float length=(float)Math.sqrt(dx*dx+dz*dz),rx=-dz/length,rz=dx/length;
        var camera=CameraState.explicitBasis(-3.2866134643554688,-1.8500804901123047,7.664741039276123)
                .forward(dx,dy,dz).right(rx,0,rz).up(-rz*dy,rz*dx-rx*dz,rx*dy).projectionTangents(1,1).build();
        int[][] permutations={{0,1,2},{1,2,0},{2,0,1},{0,2,1},{2,1,0},{1,0,2}};
        int failures=0,checks=0;long revision=0;
        var report=new StringBuilder("{\"sequence\":172,\"path\":1607471,\"cases\":[");
        try(var renderer=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true)
                .sceneShaders(shaders).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            for(int surface=0;surface<2;surface++)for(int mirror=0;mirror<2;mirror++)for(int p=0;p<6;p++) {
                var base=MediumTransportSelfTest.cube(1,1,1);
                int[] original=new int[base.triangleIndices().remaining()];base.triangleIndices().get(original);
                int[] indices=original.clone();
                float[] positions=new float[base.positions().remaining()];base.positions().get(positions);
                for(int i=0;i<indices.length;i+=3)for(int j=0;j<3;j++)indices[i+j]=original[i+permutations[p][j]];
                var medium=new Medium(new Rgb(.1f,.1f,.1f),Rgb.ZERO,Rgb.ZERO,1,1,0);
                var boundary=new MediumBoundary(medium,2,p<3?MediumBoundary.Orientation.OUTWARD:
                        MediumBoundary.Orientation.INWARD,surface!=0);
                renderer.apply(SceneTransaction.builder(++revision).resetScene()
                        .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                        .upsert(MeshAsset.triangles(1,positions,indices,1))
                        .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{
                                mirror==0?.85f:-.85f,0,0,4.5f,0,.85f,0,-4,0,0,.85f,0}))
                                .mediumBoundary(boundary).build()).build());
                var request=RenderFrameRequest.builder(revision,WIDTH,HEIGHT,camera).minimumSceneRevision(revision).build();
                long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
                while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                    if(System.nanoTime()>deadline)throw new AssertionError("edge replay admission timeout");
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                }
                var frame=renderer.awaitFrame(Duration.ofSeconds(45)).orElseThrow();
                var pixels=frame.pixels().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                for(int column=0;column<WIDTH;column++) {
                    int side=pixels.getInt(column*4),flags=pixels.getInt(3*frame.rowBytes()+column*4);
                    float normal=pixels.getFloat(frame.rowBytes()+column*4),t=pixels.getFloat(2*frame.rowBytes()+column*4);
                    // The independent slab oracle enters through x=4.5-float(.85), before the y exit.
                    double entry=(4.5-(double).85f-camera.x())/(double)dx;
                    boolean passed=side==(p<3?0:2)&&normal<-.99f&&flags!=0&&Math.abs(t-entry)<2*Math.ulp((float)entry);
                    if(checks++!=0)report.append(',');
                    report.append("{\"surface\":").append(surface).append(",\"mirror\":").append(mirror)
                            .append(",\"permutation\":").append(p).append(",\"query\":").append(column%3)
                            .append(",\"opaque\":").append(column>=3).append(",\"side\":").append(side)
                            .append(",\"normalX\":").append(normal).append(",\"t\":").append(t)
                            .append(",\"passed\":").append(passed).append('}');
                    if(!passed)failures++;
                }
            }
        }
        report.append("],\"checks\":").append(checks).append(",\"failures\":").append(failures).append("}\n");
        var output=Path.of(System.getProperty("top.ceroxe.rt.medium.radianceTraversalOutput","build/medium-radiance-traversal.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,report);
        System.out.println("BoundaryRadianceTraversalNativeSelfTest: checks="+checks+", failures="+failures);
        if(failures!=0)throw new AssertionError("production traversal returned an exit before the slab entry: "+failures);
    }
}
