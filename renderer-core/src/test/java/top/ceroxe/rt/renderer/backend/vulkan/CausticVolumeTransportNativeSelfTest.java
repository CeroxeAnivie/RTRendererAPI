package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Runs the production photon segment sampler on the GPU against Beer-law integrals. */
public final class CausticVolumeTransportNativeSelfTest {
    private static final int WIDTH=4,HEIGHT=4;
    private static final double[][] EXTINCTION={{.2,.5,1},{.2,0,0},{.001,.002,.003},{3,3,3}};
    private static final double[][] SCATTERING={{.1,.2,.3},{.2,0,0},{.001,.001,.001},{1,2,3}};

    public static void main(String[] args)throws Exception {
        String sampler;
        try(var input=CausticVolumeTransportNativeSelfTest.class.getClassLoader().getResourceAsStream(
                "assets/rtrenderer/shaders/gpuscene/gpuscene_photon_segment.glsl")) {
            if(input==null)throw new IllegalStateException("missing production photon segment sampler");
            sampler=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        String fragment="""
                #version 450
                layout(location=0) out vec4 color;
                """+sampler+"""
                void main(){
                    const vec3 extinction[4]=vec3[](vec3(.2,.5,1),vec3(.2,0,0),vec3(.001,.002,.003),vec3(3));
                    const vec3 scattering[4]=vec3[](vec3(.1,.2,.3),vec3(.2,0,0),vec3(.001),vec3(1,2,3));
                    int test=int(gl_FragCoord.x),row=int(gl_FragCoord.y);
                    vec3 sigma=extinction[test],scatter=scattering[test],sum=vec3(0);
                    if(row==3){
                        GsPhotonSegment s=gsPhotonSegment(vec3(2),vec3(1),.75,.5,.5,true);
                        color=vec4(s.distance,2.0-s.distance,4.0-s.distance,s.collision?1.0:0.0);return;
                    }
                    const int n=32768;
                    for(int channel=0;channel<3;channel++)for(int i=0;i<n;i++){
                        GsPhotonSegment s=gsPhotonSegment(sigma,scatter,2.0,(float(channel)+.5)/3.0,(float(i)+.5)/float(n),true);
                        if(row==0&&s.collision)sum+=s.incidentWeight;
                        if(row==1&&!s.collision)sum+=s.transportWeight;
                        if(row==2&&s.collision)sum+=s.transportWeight;
                    }
                    color=vec4(sum/float(3*n),1);
                }
                """;
        var color=new ShaderInterfaceVariable(0,new ShaderInterfaceType(
                ShaderInterfaceType.NumericType.FLOATING_POINT,32,4),ShaderInterfaceVariable.Interpolation.SMOOTH);
        var vertex=VulkanGenericCommandNativeSelfTest.module(970001,ShaderStage.VERTEX,
                "#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}",
                List.of(),List.of(),List.of(),Shaderc.shaderc_vertex_shader);
        var pixel=VulkanGenericCommandNativeSelfTest.module(970002,ShaderStage.FRAGMENT,fragment,
                List.of(),List.of(color),List.of(),Shaderc.shaderc_fragment_shader);
        var program=new ShaderProgram(new RenderResourceId(970003),ResourceVersion.initial(),ShaderProgram.Kind.GRAPHICS,
                List.of(vertex,pixel),new BindingLayout(List.of()),0);
        var pipeline=GraphicsPipelineState.builder(program).rasterState(new RasterState(false,false,
                RasterState.PolygonMode.FILL,RasterState.CullMode.NONE,RasterState.FrontFace.COUNTER_CLOCKWISE,
                false,0,0,0,1)).colorTargets(List.of(TextureFormat.RGBA32_FLOAT),BlendState.replace(1)).build();
        var output=new TextureResource(new RenderResourceId(970004),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
                WIDTH,HEIGHT,1,1,1,1,TextureFormat.RGBA32_FLOAT,Set.of(TextureUsage.COLOR_ATTACHMENT));
        var view=new TextureView(output,TextureViewDimension.TEXTURE_2D,
                new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1));
        var config=RendererConfig.builder().frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled()).denoising(DenoisingOptions.disabled()).build();
        try(var device=VulkanDeviceRuntime.open(top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe.capture(),
                top.ceroxe.rt.renderer.RendererRtDiagnostics.noop(),true,true,config);
            var session=new VulkanGenericCommandSession(device,1)) {
            require(session.submitResources(new RenderResourceTransaction(0,List.of(),List.of(output),List.of()))
                    .outcome()==ResourceTransactionEvidence.Outcome.ACCEPTED,"photon oracle output rejected");
            var recorded=session.submit(new RenderCommandTransaction(0,List.of(
                    new BeginRenderPassCommand(RenderPassDescriptor.color(WIDTH,HEIGHT,List.of(
                            RenderAttachment.of(view,LoadOp.DISCARD,StoreOp.STORE)))),
                    new BindGraphicsPipelineCommand(pipeline),new SetViewportCommand(new Viewport(0,0,WIDTH,HEIGHT,0,1)),
                    new SetScissorCommand(new ScissorRectangle(0,0,WIDTH,HEIGHT)),new DrawCommand(3,1,0,0),new EndRenderPassCommand())));
            require(recorded.outcome().recorded(),"production photon sampler GPU submission rejected: "+recorded.detail());
            VulkanGenericCommandNativeSelfTest.await(session,0,CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            var frame=session.captureLatestCpuFrame(-1);
            require(frame!=null&&frame.format()==TextureFormat.RGBA32_FLOAT,"missing photon numerical output");
            var data=frame.pixels().order(ByteOrder.nativeOrder());
            double maximumError=0;
            for(int test=0;test<WIDTH;test++)for(int channel=0;channel<3;channel++) {
                double sigma=EXTINCTION[test][channel],integral=sigma==0?2:-Math.expm1(-sigma*2)/sigma;
                for(int row=0;row<3;row++) {
                    double expected=row==0?integral:row==1?Math.exp(-sigma*2):integral*SCATTERING[test][channel];
                    double actual=data.getFloat(row*frame.rowBytes()+test*16+channel*4);
                    double error=Math.abs(actual-expected);maximumError=Math.max(maximumError,error);
                    require(Double.isFinite(actual)&&error<.003,
                            "photon Beer-law mismatch test="+test+" row="+row+" channel="+channel+" actual="+actual+" expected="+expected);
                }
            }
            double collision=Math.log(2)/2;
            near(data.getFloat(3*frame.rowBytes()),collision,"collision distance");
            near(data.getFloat(3*frame.rowBytes()+4),2-collision,"remaining distance charged exactly once");
            near(data.getFloat(3*frame.rowBytes()+8),4-collision,"finite light range charged exactly once");
            near(data.getFloat(3*frame.rowBytes()+12),1,"collision present without a surface hit");
            System.out.println("CausticVolumeTransportNativeSelfTest passed: 36 independent Beer-law comparisons; spectral zero channels, incident/scattered flux, survival, collision budget; maximumError="+maximumError);
        }
    }
    private static void near(double actual,double expected,String name){require(Math.abs(actual-expected)<2e-6,name+": "+actual+" vs "+expected);}
    private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
