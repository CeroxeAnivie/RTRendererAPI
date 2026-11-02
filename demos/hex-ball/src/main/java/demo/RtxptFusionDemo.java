package demo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.*;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;
import top.ceroxe.rt.renderer.rtxpt.RtxptComposedRenderer;

/** RTXPT optics and live Vulkan graphics share camera state and compose entirely on the GPU. */
public final class RtxptFusionDemo {
    private RtxptFusionDemo() { }

    public static void main(String[] args) throws Exception {
        DemoApiControls.parse(args);
        var config=DemoRendererProfile.rtxptMediumInteractive(false).copyBuilder().gpuTimingsEnabled(false).build();
        try(var renderer=RtxptComposedRenderer.openRtxptPresentation(
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,
                "RTRendererAPI 7.0.0 - RTXPT + Vulkan Fusion")) {
            int width=1920,height=1080,mode=0;
            long revision=1,sequence=0,hud=0,report=0,reportFrames=0;
            String scene="mixed";
            boolean paused=false;
            float time=0;
            long previous=System.nanoTime();
            renderer.apply(RtxptMediumScene.create(revision,scene));
            renderer.submit(RtxptMediumDemo.frameRequest(sequence++,revision,width,height));
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            host.setWindowTitle("RTRendererAPI 7.0.0 - RTXPT + Vulkan Fusion");
            host.setOverlayChoices(RtxptMediumScene.LABELS,0);
            var keys=new DemoWindowKeys(host.windowHandle());
            var stats=new RenderStats();
            var effects=new VulkanEffects(renderer);
            while(true) {
                long now=System.nanoTime();
                if(!paused)time+=Math.min(.1f,(now-previous)*1e-9f);
                previous=now;
                String command=keys.pollCommand();
                int selected=host.overlaySelection();
                if(command==null&&selected>=0&&!scene.equals(RtxptMediumScene.PRESETS.get(selected)))
                    command=RtxptMediumScene.PRESETS.get(selected);
                if("quit".equals(command))break;
                if("toggle-vulkan".equals(command))mode=(mode+1)%3;
                if("pause".equals(command))paused=!paused;
                if(command!=null&&RtxptMediumScene.PRESETS.contains(command)) {
                    scene=command;renderer.apply(RtxptMediumScene.create(++revision,scene));
                    host.setOverlayChoices(RtxptMediumScene.LABELS,RtxptMediumScene.PRESETS.indexOf(scene));
                }
                var display=host.presentationStatus();
                width=display.outputWidth();height=display.outputHeight();
                var camera=CameraState.lookAt(4+.35*Math.sin(time*.35),2.7,6,0,.8,0)
                    .verticalFieldOfViewDegrees(52).aspectRatio((double)width/height).build();
                float[] a=project(camera,-1.2,.9,0),b=project(camera,1.3,.65,.3);
                effects.draw(width,height,a,b,time,mode);
                long before=host.bridgeTimings().frames();
                renderer.submit(DemoRendererProfile.frame(sequence++,width,height,camera)
                    .minimumSceneRevision(revision).environment(RtxptMediumScene.environment())
                    .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
                RtxptMediumDemo.recordPresented(host,stats,before);
                if(now-hud>500_000_000L) {
                    var actual=host.runtimeStatus();var diagnostics=renderer.diagnostics();
                    var extent=new DemoConfig(width,height,1,true,false,false,0,0,Duration.ZERO);
                    host.setOverlayText(DemoTechnologyHud.capture(renderer,extent,stats,diagnostics).text()
                        +String.format(Locale.ROOT,"%nRTXPT 3D OPTICS + VULKAN GRAPHICS | GPU COMPOSITION%nINPUT %dx%d -> OUTPUT %dx%d | SHaRC %s%nVULKAN: %s | draws %d%nCYAN / AMBER: WORLD-ANCHORED TRACKING + ADDITIVE PULSE%n[1-6] Optics  [V] Fusion / RTXPT / Vulkan  [P] Pause camera%nSCENE %s | %s",
                            actual.renderWidth(),actual.renderHeight(),width,height,actual.sharc()?"ACTIVE":"OFF",
                            new String[]{"ALPHA + ADDITIVE","HIDDEN","ISOLATED REPLACE"}[mode],effects.draws,
                            scene.toUpperCase(Locale.ROOT),RtxptMediumScene.description(scene)));
                    hud=now;
                }
                if(now-report>1_000_000_000L) {
                    long frames=host.bridgeTimings().frames();
                    if(report!=0)System.out.printf(Locale.ROOT,"FUSION scene=%s mode=%d native=%.2f output=%.2f FPS VulkanDraws=%d FG=%s%n",
                        scene,mode,(frames-reportFrames)*1e9/(now-report),
                        DemoTechnologyHud.presentTotalFps(renderer.diagnostics().frameGenerationEvidence(),(frames-reportFrames)*1e9/(now-report)),
                        effects.draws,renderer.diagnostics().frameGenerationEvidence());
                    report=now;reportFrames=frames;
                }
            }
        } catch(IllegalStateException failure) {
            if(!"RTXPT window closed".equals(failure.getMessage()))throw failure;
        }
    }

    static float[] project(CameraState c,double x,double y,double z) {
        double dx=x-c.x(),dy=y-c.y(),dz=z-c.z();
        double depth=dx*c.forwardX()+dy*c.forwardY()+dz*c.forwardZ();
        return new float[]{(float)(.5+.5*(dx*c.rightX()+dy*c.rightY()+dz*c.rightZ())/(depth*c.tanHalfFovX())),
            (float)(.5-.5*(dx*c.upX()+dy*c.upY()+dz*c.upZ())/(depth*c.tanHalfFovY()))};
    }

    private static final class VulkanEffects {
        final RtxptComposedRenderer renderer;
        final RendererCommandAccess commands;
        final GraphicsPipelineState pipeline;
        TextureResource color,glow;
        int width,height;
        long resourceRevision,draws;
        VulkanEffects(RtxptComposedRenderer renderer) {
            this.renderer=renderer;commands=renderer.vulkanCommands();pipeline=pipeline();
        }
        void draw(int w,int h,float[] a,float[] b,float t,int mode) throws Exception {
            if(w!=width||h!=height) {
                renderer.overlays(List.of());renderer.trimIdleCompositionResources();
                var retired=color==null?List.<ResourceGenerationKey>of():List.of(ResourceGenerationKey.of(color),ResourceGenerationKey.of(glow));
                width=w;height=h;
                color=texture(8000+2*resourceRevision,w,h);glow=texture(8001+2*resourceRevision,w,h);
                var result=commands.submitResources(new RenderResourceTransaction(resourceRevision++,List.of(),List.of(color,glow),retired));
                if(result.outcome()!=ResourceTransactionEvidence.Outcome.ACCEPTED)throw new IllegalStateException("Vulkan effects allocation: "+result);
            }
            for(int layerIndex=0;layerIndex<2;layerIndex++) {
            long commandSequence=draws*2+layerIndex;
            var bytes=ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
            bytes.putFloat(a[0]).putFloat(a[1]).putFloat(b[0]).putFloat(b[1])
                .putFloat(w).putFloat(h).putFloat(t).putFloat(layerIndex).flip();
            var pass=RenderPassDescriptor.color(w,h,List.of(attachment(layerIndex==0?color:glow)));
            var result=commands.submitCommands(new RenderCommandTransaction(commandSequence,List.of(
                new BeginRenderPassCommand(pass),new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0,0,w,h,0,1)),new SetScissorCommand(new ScissorRectangle(0,0,w,h)),
                new SetPushConstantsCommand(Set.of(ShaderStage.FRAGMENT),0,new ResourceData(bytes)),
                new DrawCommand(3,1,0,0),new EndRenderPassCommand())));
            if(result.outcome()!=CommandExecutionEvidence.Outcome.RECORDED)throw new IllegalStateException("Vulkan effects draw: "+result);
            long deadline=System.nanoTime()+10_000_000_000L;
            while(true) {
                var done=commands.commandExecutionEvidence(commandSequence).orElseThrow();
                if(done.outcome().gpuCompleted())break;
                if(done.outcome()!=CommandExecutionEvidence.Outcome.RECORDED||System.nanoTime()>deadline)
                    throw new IllegalStateException("Vulkan effects completion: "+done);
                Thread.onSpinWait();
            }
            }
            var layer=new FrameCompositionPlan.Layer(new ResourceMutationKey(ResourceGenerationKey.of(color),draws*2),
                mode==2?FrameCompositionPlan.Operation.REPLACE:FrameCompositionPlan.Operation.ALPHA_OVER);
            var light=new FrameCompositionPlan.Layer(new ResourceMutationKey(ResourceGenerationKey.of(glow),draws*2+1),FrameCompositionPlan.Operation.ADDITIVE);
            renderer.overlays(mode==1?List.of():List.of(layer,light));draws++;
        }
    }
    private static TextureResource texture(long id,int w,int h) {
        return new TextureResource(new RenderResourceId(id),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
            w,h,1,1,1,1,TextureFormat.RGBA8_UNORM,EnumSet.of(TextureUsage.COLOR_ATTACHMENT,TextureUsage.STORAGE_READ));
    }
    private static RenderAttachment attachment(TextureResource texture) {
        var view=new TextureView(texture,TextureViewDimension.TEXTURE_2D,new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1));
        return RenderAttachment.cleared(view,StoreOp.STORE,new ClearValue.Color(0,0,0,0));
    }
    private static GraphicsPipelineState pipeline() {
        var vertex=module(8100,ShaderStage.VERTEX,"""
            #version 450
            void main(){vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));gl_Position=vec4(p[gl_VertexIndex],0,1);}
            """,0,Shaderc.shaderc_vertex_shader);
        var fragment=module(8101,ShaderStage.FRAGMENT,"""
            #version 450
            layout(push_constant) uniform Params { vec4 anchors; vec4 info; } pc;
            layout(location=0) out vec4 overlayColor;
            float line(vec2 p,vec2 a,vec2 b){vec2 q=p-a,d=b-a;return length(q-d*clamp(dot(q,d)/dot(d,d),0.,1.));}
            void main(){
                vec2 p=gl_FragCoord.xy, size=pc.info.xy;
                vec2 a=pc.anchors.xy*size,b=pc.anchors.zw*size;
                float scale=size.y/1080.,t=pc.info.z;
                float r=64.*scale,d1=length(p-a),d2=length(p-b);
                float c1=1.-smoothstep(1.*scale,2.5*scale,abs(d1-r));
                float c2=1.-smoothstep(1.*scale,2.5*scale,abs(d2-r*.7));
                float path=(1.-smoothstep(.8*scale,1.8*scale,line(p,a,b)))*.6;
                path*=step(.35,fract(length(p-a)/(18.*scale)-t*.3));
                float bracket=(1.-smoothstep(1.2*scale,3.*scale,abs(max(abs(p.x-a.x),abs(p.y-a.y))-r*1.2)));
                bracket*=step(r*.8,min(abs(p.x-a.x),abs(p.y-a.y)));
                float alpha=clamp(max(max(c1,c2),max(path,bracket))*.85,0.,1.);
                vec3 tint=mix(vec3(.04,.85,1.),vec3(1.,.55,.06),step(d2,d1));
                float pulse=exp(-abs(d1-r*(.8+.6*fract(t*.35)))/(3.*scale))*.22;
                overlayColor=pc.info.w<.5?vec4(tint,alpha):vec4(vec3(.02,.65,.95)*pulse,0.);
            }
            """,32,Shaderc.shaderc_fragment_shader);
        var program=new ShaderProgram(new RenderResourceId(8102),ResourceVersion.initial(),ShaderProgram.Kind.GRAPHICS,
            List.of(vertex,fragment),new BindingLayout(List.of()),32);
        return GraphicsPipelineState.builder(program).vertexLayout(VertexLayout.empty())
            .primitiveAssembly(PrimitiveTopology.TRIANGLE_LIST,false)
            .colorTargets(List.of(TextureFormat.RGBA8_UNORM),BlendState.replace(1)).build();
    }
    private static ShaderModule module(long id,ShaderStage stage,String source,int pushBytes,int kind) {
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,source,kind,"fusion.glsl","main",options);
            if(result==0||Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new IllegalStateException("Vulkan fusion shader: "+(result==0?"no result":Shaderc.shaderc_result_get_error_message(result)));
            var outputs=stage==ShaderStage.FRAGMENT?List.of(
                new ShaderInterfaceVariable(0,new ShaderInterfaceType(ShaderInterfaceType.NumericType.FLOATING_POINT,32,4),ShaderInterfaceVariable.Interpolation.SMOOTH)):List.<ShaderInterfaceVariable>of();
            return new ShaderModule(new RenderResourceId(id),ResourceVersion.initial(),stage,"main",
                Shaderc.shaderc_result_get_bytes(result).order(ByteOrder.nativeOrder()),new ShaderReflection(List.of(),pushBytes,List.of(),outputs,List.of()));
        } finally {
            if(result!=0)Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
