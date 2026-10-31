package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Runs the production predicates on the GPU with fixed independently classified edge rays. */
public final class BoundaryIntersectionPredicatesNativeSelfTest {
    public static void main(String[] args) throws Exception {
        String helper;
        try(var input=BoundaryIntersectionPredicatesNativeSelfTest.class.getClassLoader().getResourceAsStream(
                "assets/rtrenderer/shaders/gpuscene/gpuscene_boundary_intersection.glsl")) {
            helper=new String(java.util.Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        helper=helper.replace("gl_ObjectToWorldEXT","objectToWorld").replace("gl_WorldRayOriginEXT","rayOrigin")
                .replace("gl_WorldRayDirectionEXT","rayDirection").replace("gl_HitTEXT","hitDistance");
        var destination=new BindingLayoutEntry(new BindingKey(0,0),BindingType.READ_WRITE_STORAGE_TEXTURE,1,
                Set.of(ShaderStage.COMPUTE),false);
        String source="""
                #version 460
                layout(local_size_x=1) in;
                layout(set=0,binding=0,rgba8) uniform image2D target;
                mat4x3 objectToWorld; vec3 rayOrigin,rayDirection; float hitDistance;
                const vec3 positions[8]=vec3[8](vec3(-1,-1,-1),vec3(1,-1,-1),vec3(1,1,-1),vec3(-1,1,-1),
                    vec3(-1,-1,1),vec3(1,-1,1),vec3(1,1,1),vec3(-1,1,1));
                const uvec3 indices[12]=uvec3[12](uvec3(0,2,1),uvec3(0,3,2),uvec3(4,5,6),uvec3(4,6,7),
                    uvec3(0,1,5),uvec3(0,5,4),uvec3(3,7,6),uvec3(3,6,2),uvec3(0,4,7),uvec3(0,7,3),uvec3(1,2,6),uvec3(1,6,5));
                uvec3 gsTriangleIndices(uint mesh,uint primitive){return indices[primitive];}
                vec3 gsPosition(uint mesh,uint vertex){return positions[vertex];}
                #define GPU_SCENE_MESH_TRIANGLE_COUNT_WORD 17
                struct MeshWords{uint words[18];}; MeshWords gsMeshes;
                """+helper+"""
                void main(){
                    uint i=gl_GlobalInvocationID.x; bool primary=i!=1u; gsMeshes.words[17]=12u;
                    objectToWorld=mat4x3(.85,0,0,0,.85,0,0,0,.85,primary?-7.5:1.5,-4,0);
                    rayOrigin=primary?vec3(0,0,18):vec3(-2.1338847,-2.7208083,4.065192);
                    rayDirection=primary?vec3(-.33453768,-.15846524,-.92896354):vec3(.475559,-.2623859,-.8396411);
                    hitDistance=primary?19.87817673588675:5.8539205;
                    bool valid=gsBoundaryCandidateValid(0u,primary?7u:1u,vec2(-1e-8,.5));
                    GsBoundaryWide3 a=gsBoundaryRelativePoint(positions[1]),b=gsBoundaryRelativePoint(positions[2]),c=gsBoundaryRelativePoint(positions[6]);
                    vec3 ne=gsBoundaryEdges(a,b,c);
                    GsBoundaryWide3 ca=gsBoundaryRelativePoint(positions[3]),cb=gsBoundaryRelativePoint(positions[6]),cc=gsBoundaryRelativePoint(positions[2]);
                    vec3 ce=gsBoundaryEdges(ca,cb,cc);
                    vec2 cx=gsBoundaryWideDifferenceOfProducts(b.y,c.z,b.z,c.y),cy=gsBoundaryWideDifferenceOfProducts(b.z,c.x,b.x,c.z),cz=gsBoundaryWideDifferenceOfProducts(b.x,c.y,b.y,c.x);
                    vec2 mx=gsBoundaryWideMultiply(cx,vec2(rayDirection.x,0)),my=gsBoundaryWideMultiply(cy,vec2(rayDirection.y,0)),mz=gsBoundaryWideMultiply(cz,vec2(rayDirection.z,0));
                    vec2 total=gsBoundaryWideAdd(gsBoundaryWideAdd(mx,my),mz);
                    float debug[42]=float[42](ce.x,ce.y,ce.z,ne.x,ne.y,ne.z,gsBoundaryDistance(a,b,c,ne),hitDistance,
                        a.x.x,a.x.y,a.y.x,a.y.y,a.z.x,a.z.y,b.x.x,b.x.y,b.y.x,b.y.y,b.z.x,b.z.y,c.x.x,c.x.y,c.y.x,c.y.y,c.z.x,c.z.y,
                        cx.x,cx.y,cy.x,cy.y,cz.x,cz.y,mx.x,mx.y,my.x,my.y,mz.x,mz.y,total.x,total.y,rayDirection.x,rayDirection.y);
                    if(i<2u)imageStore(target,ivec2(i,0),vec4(valid?1:0,gsBoundaryInside(ne)?1:0,0,1));
                    else {uint bits=floatBitsToUint(debug[i-2u]);imageStore(target,ivec2(i,0),vec4(uvec4(bits,bits>>8,bits>>16,bits>>24)&255u)/255.0);}
                }
                """;
        var shader=VulkanGenericLogicOperationNativeSelfTest.module(951001,ShaderStage.COMPUTE,
                Shaderc.shaderc_compute_shader,source,List.of(),List.of(),List.of(destination));
        var evidence=java.nio.file.Path.of("build/medium-edge-predicate");java.nio.file.Files.createDirectories(evidence);
        java.nio.file.Files.writeString(evidence.resolve("predicate.comp"),source,StandardCharsets.UTF_8);
        byte[] spirv=new byte[shader.spirv().remaining()];shader.spirv().get(spirv);java.nio.file.Files.write(evidence.resolve("predicate.spv"),spirv);
        var program=new ShaderProgram(new RenderResourceId(951002),ResourceVersion.initial(),ShaderProgram.Kind.COMPUTE,List.of(shader),
                new BindingLayout(List.of(destination)),0);
        var target=new TextureResource(new RenderResourceId(951003),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
                44,1,1,1,1,1,TextureFormat.RGBA8_UNORM,Set.of(TextureUsage.STORAGE_READ_WRITE,TextureUsage.COLOR_ATTACHMENT,TextureUsage.COPY_DESTINATION));
        var range=new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1);
        var view=new TextureView(target,TextureViewDimension.TEXTURE_2D,range);
        try(var device=VulkanDeviceRuntime.open(VulkanRtCapabilityProbe.capture(),RendererRtDiagnostics.noop(),true,true,
                RendererConfig.builder().build());var session=new VulkanGenericCommandSession(device,1)) {
            require(session.submitResources(new RenderResourceTransaction(0,List.of(),List.of(target),List.of())).outcome()
                    ==ResourceTransactionEvidence.Outcome.ACCEPTED,"predicate resources rejected");
            var bindings=new BindingSet(program.bindingLayout(),Map.of(destination.key(),
                    List.of(new BindingSet.TextureValue(view,BindingType.READ_WRITE_STORAGE_TEXTURE))));
            var submitted=session.submit(new RenderCommandTransaction(1,List.of(new ClearColorCommand(new ResourceSlice.TextureSlice(target,range),new ClearColorValue(0,0,0,1)),new BindComputePipelineCommand(new ComputePipelineState(program)),
                    BindBindingSetCommand.fixed(bindings),new DispatchCommand(44,1,1))));
            require(submitted.outcome().recorded(),"predicate dispatch rejected: "+submitted.reason()+" "+submitted.detail());
            VulkanGenericCommandNativeSelfTest.await(session,1,CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            var pass=RenderPassDescriptor.color(44,1,List.of(RenderAttachment.of(view,LoadOp.LOAD,StoreOp.STORE)));
            require(session.submit(new RenderCommandTransaction(2,List.of(new BeginRenderPassCommand(pass),new EndRenderPassCommand())))
                    .outcome().recorded(),"predicate readback rejected");
            VulkanGenericCommandNativeSelfTest.await(session,2,CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            var pixels=session.captureLatestCpuFrame(1).pixels();
            int entry=Byte.toUnsignedInt(pixels.get(0)),miss=Byte.toUnsignedInt(pixels.get(4));
            System.out.println("Boundary predicate edge results: realEntry="+entry+" emptySlab="+miss);
            for(int index=2;index<44;index++){int at=index*4;int bits=0;for(int lane=0;lane<4;lane++)bits|=Byte.toUnsignedInt(pixels.get(at+lane))<<(lane*8);
                System.out.println("predicate component "+(index-2)+"="+Float.intBitsToFloat(bits));}
            require(entry==255&&miss==0,"GPU predicate disagrees with the independent double-precision slab oracle");
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
