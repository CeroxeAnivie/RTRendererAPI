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
        var inputBinding=new BindingLayoutEntry(new BindingKey(0,1),BindingType.UNIFORM_BUFFER,1,
                Set.of(ShaderStage.COMPUTE),false);
        String source="""
                #version 460
                layout(local_size_x=1) in;
                layout(set=0,binding=0,rgba8) uniform image2D target;
                layout(set=0,binding=1) uniform Inputs {vec4 bias;} inputs;
                mat4x3 objectToWorld; vec3 rayOrigin,rayDirection; float hitDistance;
                const vec3 positions[8]=vec3[8](vec3(-1,-1,-1),vec3(1,-1,-1),vec3(1,1,-1),vec3(-1,1,-1),
                    vec3(-1,-1,1),vec3(1,-1,1),vec3(1,1,1),vec3(-1,1,1));
                const uvec3 indices[12]=uvec3[12](uvec3(0,2,1),uvec3(0,3,2),uvec3(4,5,6),uvec3(4,6,7),
                    uvec3(0,1,5),uvec3(0,5,4),uvec3(3,7,6),uvec3(3,6,2),uvec3(0,4,7),uvec3(0,7,3),uvec3(1,2,6),uvec3(1,6,5));
                bool inward=false;
                uvec3 gsTriangleIndices(uint mesh,uint primitive){return inward?indices[primitive].xzy:indices[primitive];}
                vec3 gsPosition(uint mesh,uint vertex){return positions[vertex];}
                #define GPU_SCENE_MESH_TRIANGLE_COUNT_WORD 17
                struct MeshWords{uint words[18];}; MeshWords gsMeshes;
                """+helper+"""
                void main(){
                    uint i=gl_GlobalInvocationID.x; gsMeshes.words[17]=12u;
                    if(i>=56u){
                        inward=true;
                        objectToWorld=mat4x3(.8,0,0,0,.8,0,0,0,.8,-1.5,-2,0);
                        rayOrigin=vec3(-3.5619245,-2.1426425,-1.2999898);
                        rayDirection=vec3(.6319559,.47206348,.61464447);hitDistance=1.9968551;
                        rayOrigin+=inputs.bias.xyz;rayDirection+=inputs.bias.xyz;
                        uint firstPrimitive,secondPrimitive,lastPrimitive;vec2 firstBary,secondBary,lastBary;float firstT,secondT,lastT;
                        bool first=gsBoundaryConnectionHit(0u,6u,vec2(.613,.0000001),0,firstPrimitive,firstBary,firstT);
                        bool second=gsBoundaryConnectionHit(0u,6u,vec2(.613,.0000001),uintBitsToFloat(floatBitsToUint(firstT)+1u),secondPrimitive,secondBary,secondT);
                        bool last=gsBoundaryConnectionHit(0u,6u,vec2(.613,.0000001),uintBitsToFloat(floatBitsToUint(secondT)+1u),lastPrimitive,lastBary,lastT);
                        vec2 directBary;float directT;
                        gsBoundaryTriangleHit(0u,i==61u?9u:6u,directBary,directT);
                        if(i==56u||i==58u||i==60u)imageStore(target,ivec2(i,0),vec4((i==56u?first:i==58u?second:last)?1:0,float(i==56u?firstPrimitive:secondPrimitive)/255.0,0,1));
                        else {uint bits=floatBitsToUint(i==57u?firstT:i==59u?secondT:directT);imageStore(target,ivec2(i,0),vec4(uvec4(bits,bits>>8,bits>>16,bits>>24)&255u)/255.0);}
                        return;
                    }
                    if(i>=50u){
                        objectToWorld=mat4x3(.85,0,0,0,.85,0,0,0,.85,4.5,-4,0);
                        rayOrigin=vec3(4.7887306,-6.3649364,-4.199793);
                        rayDirection=vec3(-.0953462571,.286038786,.953462601);hitDistance=5.2962623;
                        rayOrigin+=inputs.bias.xyz;rayDirection+=inputs.bias.xyz;
                        uint firstPrimitive,repeatPrimitive,exitPrimitive;vec2 firstBary,repeatBary,exitBary;float firstT,repeatT,exitT;
                        bool first=gsBoundaryConnectionHit(0u,5u,vec2(.37,.629997),0,firstPrimitive,firstBary,firstT);
                        float minimum=uintBitsToFloat(floatBitsToUint(firstT)+1u);
                        bool repeat=gsBoundaryConnectionHit(0u,5u,vec2(.37,.629997),minimum,repeatPrimitive,repeatBary,repeatT);
                        hitDistance=5.2962674;
                        bool exit=gsBoundaryConnectionHit(0u,2u,vec2(.37,.000003),minimum,exitPrimitive,exitBary,exitT);
                        if(i==50u||i==52u||i==53u||i==55u)imageStore(target,ivec2(i,0),vec4((i==50u?first:i==52u?repeat:exit)?1:0,0,0,1));
                        else {uint bits=floatBitsToUint(i==51u?firstT:exitT);imageStore(target,ivec2(i,0),vec4(uvec4(bits,bits>>8,bits>>16,bits>>24)&255u)/255.0);}
                        return;
                    }
                    if(i>=44u){
                        objectToWorld=mat4x3(.42,0,0,0,.42,0,0,0,.42,-4.5,-2,0);
                        rayOrigin=vec3(-4.15694094,-1.84399712,-1.29998982);
                        rayDirection=vec3(-.0953462571,.286038786,.953462601);hitDistance=.922941208;
                        rayOrigin+=inputs.bias.xyz;rayDirection+=inputs.bias.xyz;
                        uint firstPrimitive,secondPrimitive,lastPrimitive;vec2 firstBary,secondBary,lastBary;float firstT,secondT,lastT;
                        bool first=gsBoundaryConnectionHit(0u,7u,vec2(0,.803),0,firstPrimitive,firstBary,firstT);
                        bool second=gsBoundaryConnectionHit(0u,7u,vec2(0,.803),uintBitsToFloat(floatBitsToUint(firstT)+1u),secondPrimitive,secondBary,secondT);
                        bool last=gsBoundaryConnectionHit(0u,7u,vec2(0,.803),uintBitsToFloat(floatBitsToUint(secondT)+1u),lastPrimitive,lastBary,lastT);
                        if(i==44u)imageStore(target,ivec2(i,0),vec4(first?1:0,float(firstPrimitive)/255.0,0,1));
                        else if(i==46u)imageStore(target,ivec2(i,0),vec4(second?1:0,float(secondPrimitive)/255.0,0,1));
                        else if(i==48u)imageStore(target,ivec2(i,0),vec4(last?1:0,0,0,1));
                        else {float value=i==45u?firstT:i==47u?secondT:secondT-firstT;uint bits=floatBitsToUint(value);
                            imageStore(target,ivec2(i,0),vec4(uvec4(bits,bits>>8,bits>>16,bits>>24)&255u)/255.0);}
                        return;
                    }
                    bool primary=i!=1u;
                    objectToWorld=mat4x3(.85,0,0,0,.85,0,0,0,.85,primary?-7.5:1.5,-4,0);
                    rayOrigin=primary?vec3(0,0,18):vec3(-2.1338847,-2.7208083,4.065192);
                    rayDirection=primary?vec3(-.33453768,-.15846524,-.92896354):vec3(.475559,-.2623859,-.8396411);
                    hitDistance=primary?19.87817673588675:5.8539205;
                    rayOrigin+=inputs.bias.xyz;rayDirection+=inputs.bias.xyz;
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
                Shaderc.shaderc_compute_shader,source,List.of(),List.of(),List.of(destination,inputBinding));
        var evidence=java.nio.file.Path.of("build/medium-edge-predicate");java.nio.file.Files.createDirectories(evidence);
        java.nio.file.Files.writeString(evidence.resolve("predicate.comp"),source,StandardCharsets.UTF_8);
        byte[] spirv=new byte[shader.spirv().remaining()];shader.spirv().get(spirv);java.nio.file.Files.write(evidence.resolve("predicate.spv"),spirv);
        var program=new ShaderProgram(new RenderResourceId(951002),ResourceVersion.initial(),ShaderProgram.Kind.COMPUTE,List.of(shader),
                new BindingLayout(List.of(destination,inputBinding)),0);
        var input=new BufferResource(new RenderResourceId(951004),ResourceVersion.initial(),16,
                Set.of(BufferUsage.UNIFORM,BufferUsage.COPY_DESTINATION));
        var target=new TextureResource(new RenderResourceId(951003),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
                64,1,1,1,1,1,TextureFormat.RGBA8_UNORM,Set.of(TextureUsage.STORAGE_READ_WRITE,TextureUsage.COLOR_ATTACHMENT,TextureUsage.COPY_DESTINATION));
        var range=new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1);
        var view=new TextureView(target,TextureViewDimension.TEXTURE_2D,range);
        try(var device=VulkanDeviceRuntime.open(VulkanRtCapabilityProbe.capture(),RendererRtDiagnostics.noop(),true,true,
                RendererConfig.builder().build());var session=new VulkanGenericCommandSession(device,1)) {
            require(session.submitResources(new RenderResourceTransaction(0,List.of(input),List.of(target),List.of())).outcome()
                    ==ResourceTransactionEvidence.Outcome.ACCEPTED,"predicate resources rejected");
            var bindings=new BindingSet(program.bindingLayout(),Map.of(destination.key(),
                    List.of(new BindingSet.TextureValue(view,BindingType.READ_WRITE_STORAGE_TEXTURE)),inputBinding.key(),
                    List.of(new BindingSet.BufferValue(input,new ByteRange(0,16),BindingType.UNIFORM_BUFFER))));
            var submitted=session.submit(new RenderCommandTransaction(1,List.of(new WriteBufferCommand(
                    new ResourceSlice.BufferSlice(input,new ByteRange(0,16)),new ResourceData(java.nio.ByteBuffer.allocateDirect(16))),
                    new ClearColorCommand(new ResourceSlice.TextureSlice(target,range),new ClearColorValue(0,0,0,1)),new BindComputePipelineCommand(new ComputePipelineState(program)),
                    BindBindingSetCommand.fixed(bindings),new DispatchCommand(64,1,1))));
            require(submitted.outcome().recorded(),"predicate dispatch rejected: "+submitted.reason()+" "+submitted.detail());
            VulkanGenericCommandNativeSelfTest.await(session,1,CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            var pass=RenderPassDescriptor.color(64,1,List.of(RenderAttachment.of(view,LoadOp.LOAD,StoreOp.STORE)));
            require(session.submit(new RenderCommandTransaction(2,List.of(new BeginRenderPassCommand(pass),new EndRenderPassCommand())))
                    .outcome().recorded(),"predicate readback rejected");
            VulkanGenericCommandNativeSelfTest.await(session,2,CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            var pixels=session.captureLatestCpuFrame(1).pixels();
            int entry=Byte.toUnsignedInt(pixels.get(0)),miss=Byte.toUnsignedInt(pixels.get(4));
            System.out.println("Boundary predicate edge results: realEntry="+entry+" emptySlab="+miss);
            if(Boolean.getBoolean("top.ceroxe.rt.medium.boundaryPredicateDump"))for(int index=2;index<44;index++){int at=index*4;int bits=0;for(int lane=0;lane<4;lane++)bits|=Byte.toUnsignedInt(pixels.get(at+lane))<<(lane*8);
                System.out.println("predicate component "+(index-2)+"="+Float.intBitsToFloat(bits));}
            require(entry==255&&miss==0,"GPU predicate disagrees with the independent double-precision slab oracle");
            int entryPrimitive=Byte.toUnsignedInt(pixels.get(44*4+1)),exitPrimitive=Byte.toUnsignedInt(pixels.get(46*4+1));
            float entryT=unpack(pixels,45),exitT=unpack(pixels,47);
            double expectedEntry=(-((double).42f)-(-1.29998982f))/(double).953462601f;
            double expectedExit=(-2.0+(double).42f-(-1.84399712f))/(double).286038786f;
            System.out.println("Thin light connection: entryPrimitive="+entryPrimitive+" entryT="+entryT
                    +" exitPrimitive="+exitPrimitive+" exitT="+exitT+" oracle=["+expectedEntry+","+expectedExit+"]");
            require(Byte.toUnsignedInt(pixels.get(44*4))==255&&Byte.toUnsignedInt(pixels.get(46*4))==255
                    &&pixels.get(48*4)==0,"thin connection lost or repeated an event");
            require(entryPrimitive<=1&&exitPrimitive>=6&&exitPrimitive<=7&&entryT<exitT,"thin connection entry/exit order changed");
            require(Math.abs(entryT-expectedEntry)<=2*Math.ulp((float)expectedEntry)
                    &&Math.abs(exitT-expectedExit)<=2*Math.ulp((float)expectedExit),"thin connection t disagrees with independent slab oracle");
            float repeatedEntryT=unpack(pixels,51),repeatedExitT=unpack(pixels,54);
            double secondEntry=(-4.0-(double).85f-(-6.3649364f))/(double).286038786f;
            double secondExit=((double).85f-(-4.199793f))/(double).953462601f;
            require(Byte.toUnsignedInt(pixels.get(50*4))==255&&pixels.get(52*4)==0&&Byte.toUnsignedInt(pixels.get(53*4))==255,
                    "corrected connection re-consumed a hardware approximation of the same entry");
            require(repeatedEntryT<repeatedExitT&&Math.abs(repeatedEntryT-secondEntry)<=2*Math.ulp((float)secondEntry)
                    &&Math.abs(repeatedExitT-secondExit)<=2*Math.ulp((float)secondExit),"repeat-entry regression disagrees with independent slab oracle");
            System.out.println("Repeated-entry rejection passed: entryT="+repeatedEntryT+" exitT="+repeatedExitT);
            double inwardEntry=(-1.5-(double).8f-(-3.5619245f))/(double).6319559f;
            double inwardExit=(-2.0+(double).8f-(-2.1426425f))/(double).47206348f;
            float inwardEntryT=unpack(pixels,57),inwardExitT=unpack(pixels,59);
            int inwardFirst=Byte.toUnsignedInt(pixels.get(56*4+1)),inwardSecond=Byte.toUnsignedInt(pixels.get(58*4+1));
            System.out.println("Inward two-ULP interval: first="+inwardFirst+" at "+inwardEntryT+" second="+inwardSecond+" at "+inwardExitT
                    +" directEntry="+unpack(pixels,61)+" directExit="+unpack(pixels,62)+" oracle=["+inwardEntry+","+inwardExit+"]");
            require(Byte.toUnsignedInt(pixels.get(56*4))==255&&Byte.toUnsignedInt(pixels.get(58*4))==255&&pixels.get(60*4)==0
                    &&inwardFirst==9&&inwardSecond==6&&inwardEntryT<inwardExitT,"inward thin connection lost, repeated or reordered an event");
            require(inwardEntryT==(float)inwardEntry&&inwardExitT==(float)inwardExit,"inward connection distance differs from independently rounded slab oracle");
        }
    }
    private static float unpack(java.nio.ByteBuffer pixels,int pixel){int bits=0;for(int lane=0;lane<4;lane++)bits|=Byte.toUnsignedInt(pixels.get(pixel*4+lane))<<(lane*8);return Float.intBitsToFloat(bits);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
