package top.ceroxe.rt.renderer.rtxpt;

import java.util.EnumSet;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.GlLightingPacketConsumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Hidden real GL/D3D12 import, timeline handoff and linear composition acceptance. */
public final class RtxptNativeLightingGlSelfTest {
    private static final int WIDTH=144,HEIGHT=96;
    private static NativeLightingPlan plan(int width,int height){return new NativeLightingPlan(width,height,
        EnumSet.allOf(NativeLightingPlan.Signal.class),0,6,.35f,NativeLightingStyle.defaults());}
    private static RenderFrameRequest frame(long sequence){return RenderFrameRequest.builder(sequence,WIDTH,HEIGHT,
        CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio((double)WIDTH/HEIGHT).build())
        .minimumSceneRevision(1).environment(EnvironmentState.builder().sunIntensity(0).build())
        .clock(FrameClock.of(sequence*16_666_667L,16_666_667)).build();}
    private static int texture(){int texture=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(texture,1,GL_RGBA32F,WIDTH,HEIGHT);
        glTextureParameteri(texture,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(texture,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return texture;}
    private static float base(int x,int y){return ((x^y)&1)==0?.2f:.8f;}
    private static void submit(Renderer renderer,RenderFrameRequest request){
        long deadline=System.nanoTime()+5_000_000_000L;
        while(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"GPU consumer completion must release a packet slot");glfwPollEvents();Thread.onSpinWait();
        }
    }
    private static int diagnosticProgram(){
        int vs=glCreateShader(GL_VERTEX_SHADER);glShaderSource(vs,"""
            #version 450 core
            void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}
            """);glCompileShader(vs);
        int fs=glCreateShader(GL_FRAGMENT_SHADER);glShaderSource(fs,"""
            #version 450 core
            layout(binding=3) uniform sampler2D geometry;
            layout(binding=4) uniform usampler2D identity;
            layout(location=0) out vec4 result;
            void main(){ivec2 p=textureSize(geometry,0)/2;vec4 g=texelFetch(geometry,p,0);uvec4 i=texelFetch(identity,p,0);result=vec4(g.z,g.w,float(i.w),float(i.x));}
            """);glCompileShader(fs);
        require(glGetShaderi(vs,GL_COMPILE_STATUS)!=0,"Diagnostic GL vertex shader: "+glGetShaderInfoLog(vs));
        require(glGetShaderi(fs,GL_COMPILE_STATUS)!=0,"Diagnostic GL fragment shader: "+glGetShaderInfoLog(fs));
        int program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);
        require(glGetProgrami(program,GL_LINK_STATUS)!=0,"Diagnostic GL link: "+glGetProgramInfoLog(program));return program;
    }
    public static void main(String[] args){
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();require(glfwInit(),"Initialize GLFW for hidden GL interoperability");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
                window=glfwCreateWindow(WIDTH,HEIGHT,"Hidden native lighting GL acceptance",0,0);require(window!=0,"Create hidden GL context");
                glfwMakeContextCurrent(window);GL.createCapabilities();
                System.out.println("RTXPT_710_GL_DEVICE "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
                int host=texture(),target=texture(),fbo=glCreateFramebuffers(),diagnostic=diagnosticProgram();
                glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,target,0);require(glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Linear HDR target FBO");
                var pixels=MemoryUtil.memAllocFloat(WIDTH*HEIGHT*4);
                try{
                    for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){float value=base(x,y);pixels.put(value).put(value).put(value).put(1);}
                    pixels.flip();glTextureSubImage2D(host,0,0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,pixels);
                }finally{MemoryUtil.memFree(pixels);}
                try(var renderer=RtxptRenderers.openNativeLighting(RtxptLightTreeNativeSelfTest.config(4),plan(49,33))){
                    var control=renderer.extension(NativeLightingControl.class).orElseThrow();
                    renderer.apply(SceneTransaction.builder(1).resetScene()
                        .upsert(MaterialAsset.builder(1).roughness(.8f).build())
                        .upsert(RtxptFramePreparationNativeSelfTest.card(1).toBuilder().lightmapCoordinates(new float[]{.5f,0,.5f,0,.5f,0,.5f,0}).build())
                        .upsert(SceneInstance.builder(1,1).build()).build());
                    try(var consumer=new GlLightingPacketConsumer()){
                        for(int i=0;i<8;i++){
                            if(i>0&&i%2==0)control.nativeLightingPlan(plan(49+i,33+i));submit(renderer,frame(i));
                            var lease=control.acquireLightingFrame().orElseThrow();
                            require(lease.frame().adapterLuid()==consumer.adapterLuid(),"Same GL/D3D12 adapter LUID");
                            require(lease.frame().planes().size()==4,"Atomic packet contains requested HDR, float auxiliary and integer identity");
                            require(lease.frame().sequence()==i&&lease.frame().clock().timeNanos()==i*16_666_667L,"GL packet frame/clock identity");
                            if(i<7)consumer.compose(lease,host,fbo,WIDTH,HEIGHT);
                            else consumer.consume(lease,packet->{
                                consumer.drawComposition(packet,host,fbo,WIDTH,HEIGHT);
                                int normal=packet.texture(NativeLightingPlan.Signal.NORMAL_DISTANCE),identity=packet.texture(NativeLightingPlan.Signal.RECEIVER_IDENTITY);
                                require(glGetTextureLevelParameteri(normal,0,GL_TEXTURE_INTERNAL_FORMAT)==GL_RGBA32F,"Imported RGBA32_FLOAT auxiliary");
                                require(glGetTextureLevelParameteri(identity,0,GL_TEXTURE_INTERNAL_FORMAT)==GL_RGBA32UI,"Imported RGBA32_UINT identity");
                                glBindTextureUnit(3,normal);glBindTextureUnit(4,identity);glUseProgram(diagnostic);glViewport(0,0,1,1);glDrawArrays(GL_TRIANGLES,0,3);
                            });
                        }
                        // One explicit diagnostic readback; the normal consumer never calls this or waits for a GPU fence.
                        var result=MemoryUtil.memAllocFloat(WIDTH*HEIGHT*4);
                        try{
                            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,result);
                            require(Math.abs(result.get(0)-1)<1e-5&&Math.abs(result.get(1)-3)<1e-4&&result.get(2)==1,"Native float normal/distance and uint receiver validity survive GL imports");
                            float maximumError=0;
                            for(int y=HEIGHT/2-4;y<HEIGHT/2+4;y++)for(int x=WIDTH/2-4;x<WIDTH/2+4;x++){
                                float expected=base(x,y)*.625f;
                                for(int c=0;c<3;c++)maximumError=Math.max(maximumError,Math.abs(result.get((y*WIDTH+x)*4+c)-expected));
                            }
                            require(maximumError<2e-5,"Linear gain composition preserves native one-pixel checker: error="+maximumError);
                            require(glGetError()==GL_NO_ERROR,"GL interoperability completed without errors");
                            System.out.println("RTXPT_710_GL_HDR_COMPOSITION PASS frames=8 grids=49x33,51x35,53x37,55x39 host=144x96 formats=3 maxError="+maximumError+" pixelReads=1");
                        }finally{MemoryUtil.memFree(result);}
                        var execution=control.nativeLightingExecution();require(execution.lightingFrames()==8&&execution.fullBeautyFrames()==0&&execution.actualSignalMask()==15,"Actual independent lighting execution, all requested outputs, no beauty");
                    }
                }finally{glDeleteProgram(diagnostic);glDeleteFramebuffers(fbo);glDeleteTextures(host);glDeleteTextures(target);}
                System.out.println("RTXPT_710_GL_NATIVE_ALL PASS");
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();glfwSetErrorCallback(null);}
        }
    }
}
