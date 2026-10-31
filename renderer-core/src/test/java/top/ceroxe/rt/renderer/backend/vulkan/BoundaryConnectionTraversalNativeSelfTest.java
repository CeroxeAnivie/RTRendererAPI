package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Replays the actual micro-all sequence-100024 edge ray against hardware traversal. */
public final class BoundaryConnectionTraversalNativeSelfTest {
    private static final int WIDTH=16,HEIGHT=16;
    private static final String SOURCE="""
            #version 460
            #extension GL_EXT_ray_tracing : require
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
            #include "gpuscene_common.glsl"
            layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
            layout(set=0,binding=GPU_SCENE_BINDING_OUTPUT_IMAGE,rgba8) uniform writeonly image2D outputImage;
            mat4x3 objectToWorld;vec3 rayOrigin,rayDirection;float hitDistance;
            #define GS_BOUNDARY_OBJECT_TO_WORLD objectToWorld
            #define GS_BOUNDARY_RAY_ORIGIN rayOrigin
            #define GS_BOUNDARY_RAY_DIRECTION rayDirection
            #define GS_BOUNDARY_HIT_T hitDistance
            #include "gpuscene_boundary_intersection.glsl"
            void main(){
                uint column=gl_LaunchIDEXT.x,row=gl_LaunchIDEXT.y,value=0u,count=0u;
                rayOrigin=vec3(2.3949990272521973,-3.54087233543396,-1.2999898195266724);
                rayDirection=vec3(-.09534625709056854,.2860387861728668,.9534626007080078);
                rayQueryEXT query;
                rayQueryInitializeEXT(query,sceneTlas,gl_RayFlagsNoOpaqueEXT,255,rayOrigin,0,rayDirection,10);
                while(rayQueryProceedEXT(query)){
                    if(rayQueryGetIntersectionTypeEXT(query,false)!=gl_RayQueryCandidateIntersectionTriangleEXT)continue;
                    if(count++!=row)continue;
                    uint instance=rayQueryGetIntersectionInstanceCustomIndexEXT(query,false);
                    uint mesh=gsMeshBase(gsInstanceMeshSlot(instance));
                    uint primitive=rayQueryGetIntersectionPrimitiveIndexEXT(query,false);
                    vec2 bary=rayQueryGetIntersectionBarycentricsEXT(query,false);
                    hitDistance=rayQueryGetIntersectionTEXT(query,false);
                    objectToWorld=rayQueryGetIntersectionObjectToWorldEXT(query,false);
                    uint repaired;vec2 repairedBary;float repairedT;
                    bool accepted=gsBoundaryConnectionHit(mesh,primitive,bary,0,repaired,repairedBary,repairedT);
                    value=column==0u?primitive:column==1u?floatBitsToUint(hitDistance)
                        :column==2u?floatBitsToUint(bary.x):column==3u?floatBitsToUint(bary.y)
                        :column==4u?uint(accepted):column==5u?repaired:column==6u?floatBitsToUint(repairedT)
                        :column==7u?floatBitsToUint(repairedBary.x):column==8u?floatBitsToUint(repairedBary.y):0u;
                }
                if(column==15u)value=count;
                imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(uvec4(value,value>>8u,value>>16u,value>>24u)&255u)/255.0);
            }
            """;

    public static void main(String[] args)throws Exception {
        var shaders=SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION,SOURCE).build();
        try(var renderer=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true)
                .sceneShaders(shaders).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
            var mesh=MeshAsset.triangles(1,new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,
                    -1,-1,1,1,-1,1,1,1,1,-1,1,1},indices,1);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(mesh).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{
                            .85f,0,0,1.5f,0,.85f,0,-4,0,0,.85f,0})).build()).build());
            var request=RenderFrameRequest.builder(0,WIDTH,HEIGHT,CameraState.lookAt(0,0,6,0,0,0).build())
                    .minimumSceneRevision(1).build();
            long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
            while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                if(System.nanoTime()>deadline)throw new AssertionError("edge replay admission timeout");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            var frame=renderer.awaitFrame(Duration.ofSeconds(45)).orElseThrow();
            var pixels=frame.pixels().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int count=pixels.getInt(15*4);
            double nearest=Double.POSITIVE_INFINITY;
            var report=new StringBuilder("{\"sequence\":100024,\"path\":1600481,\"candidates\":[");
            for(int row=0;row<count;row++) {
                int at=row*frame.rowBytes();
                int primitive=pixels.getInt(at),accepted=pixels.getInt(at+16),repaired=pixels.getInt(at+20);
                float raw=pixels.getFloat(at+4),bx=pixels.getFloat(at+8),by=pixels.getFloat(at+12),distance=pixels.getFloat(at+24);
                if(accepted!=0)nearest=Math.min(nearest,distance);
                if(row!=0)report.append(',');
                report.append(String.format(Locale.ROOT,"{\"primitive\":%d,\"rawT\":%.17g,\"bary\":[%.17g,%.17g],\"accepted\":%s,\"repaired\":%d,\"distance\":%.17g}",
                        primitive,raw,bx,by,accepted!=0,repaired,distance));
            }
            double expected=(1.5+(double).85f-(double)2.3949990272521973f)/(double)-.09534625709056854f;
            report.append(String.format(Locale.ROOT,"],\"nearest\":%.17g,\"slabEntry\":%.17g}%n",nearest,expected));
            var output=Path.of(System.getProperty("top.ceroxe.rt.medium.edgeTraversalOutput","build/medium-edge-traversal.json"));
            Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,report);
            System.out.println(report);
            if(count==0||Math.abs(nearest-expected)>2*Math.ulp((float)expected))
                throw new AssertionError("hardware candidates lost the independently classified volume entry");
        }
    }
}
