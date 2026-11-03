package top.ceroxe.rt.renderer.rtxpt.examples;

import java.nio.LongBuffer;
import java.time.Duration;
import java.util.List;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptRenderers;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Full-resolution GL raster base plus independent low-resolution RTXPT direct/GI signals. */
public final class NativeLightingGlExample {
    private static final int WIDTH=640,HEIGHT=360;
    private static SceneTransaction scene(){
        var receiver=MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},new int[]{0,1,2,0,2,3},new long[]{1,1})
            .lightmapCoordinates(new float[]{.5f,0,.5f,0,.5f,0,.5f,0}).build();
        var bounce=MeshAsset.builder(2,new float[]{-2,1,0,-2,1,4,2,1,4,2,1,0},new int[]{0,1,2,0,2,3},new long[]{2,2}).build();
        return SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
            .upsert(MaterialAsset.builder(2).baseColorRgba8(0xff5080ff).roughness(.8f).build())
            .upsert(receiver).upsert(bounce).upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build();
    }
    private static SparseLightSource source(int frame){return SparseLightSource.point(1,-.7+frame*.2,0,1,.25f,.6f,1,2,6)
        .withLayer(SparseLightSource.Layer.DYNAMIC).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0);}
    private static RenderFrameRequest request(int sequence){return RenderFrameRequest.builder(sequence,WIDTH,HEIGHT,
        CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio((double)WIDTH/HEIGHT).build())
        .minimumSceneRevision(sequence+1).environment(EnvironmentState.builder().sunIntensity(0).build())
        .clock(FrameClock.of(sequence*16_666_667L,16_666_667)).build();}
    private static int texture(){int texture=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(texture,1,GL_RGBA16F,WIDTH,HEIGHT);
        glTextureParameteri(texture,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(texture,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return texture;}
    private static int framebuffer(int texture){int framebuffer=glCreateFramebuffers();glNamedFramebufferTexture(framebuffer,GL_COLOR_ATTACHMENT0,texture,0);
        if(glCheckNamedFramebufferStatus(framebuffer,GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete host HDR framebuffer");return framebuffer;}
    private static int shader(int stage,String source){int program=glCreateShaderProgramv(stage,source);
        if(glGetProgrami(program,GL_LINK_STATUS)==0){String log=glGetProgramInfoLog(program);glDeleteProgram(program);throw new IllegalStateException(log);}return program;}
    public static void main(String[] args){
        boolean visible=List.of(args).contains("--visible");
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();if(!glfwInit())throw new IllegalStateException("GLFW initialization failed");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,visible?GLFW_TRUE:GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_SRGB_CAPABLE,GLFW_TRUE);
                window=glfwCreateWindow(WIDTH,HEIGHT,"Native base + RTXPT lighting",0,0);if(window==0)throw new IllegalStateException("GL context creation failed");
                glfwMakeContextCurrent(window);glfwSwapInterval(0);GL.createCapabilities();
                int base=texture(),composed=texture(),baseFbo=framebuffer(base),composedFbo=framebuffer(composed),vao=glCreateVertexArrays();
                int vertex=shader(GL_VERTEX_SHADER,"""
                    #version 450 core
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}
                    """);
                int raster=shader(GL_FRAGMENT_SHADER,"""
                    #version 450 core
                    layout(location=0) out vec4 color;
                    void main(){float checker=mod(floor(gl_FragCoord.x)+floor(gl_FragCoord.y),2);color=vec4(vec3(.2+.55*checker),1);}
                    """);
                int display=shader(GL_FRAGMENT_SHADER,"""
                    #version 450 core
                    layout(binding=0) uniform sampler2D hdr;
                    layout(location=0) out vec4 color;
                    void main(){vec3 linear=texelFetch(hdr,ivec2(gl_FragCoord.xy),0).rgb;color=vec4(linear/(1+linear),1);}
                    """);
                int pipeline=glCreateProgramPipelines();glUseProgramStages(pipeline,GL_VERTEX_SHADER_BIT,vertex);
                try(var renderer=RtxptRenderers.openNativeLighting(NativeLightingPlan.defaults(96,54));var consumer=new GlLightingPacketConsumer()){
                    var nativeControl=renderer.extension(NativeLightingControl.class).orElseThrow();var sparse=renderer.extension(SparseLightingControl.class).orElseThrow();
                    sparse.apply(scene(),List.of(source(0)),LongBuffer.allocate(0));
                    nativeControl.publishLightingAttributes(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.receiver(1),
                        new NativeLightingPublication.Instance(2,6,1,0,1,1,0,0)),List.of(),null,null));
                    for(int frame=0;frame<8&&!glfwWindowShouldClose(window);frame++){
                        if(frame>0)sparse.apply(SceneTransaction.builder(frame+1).build(),List.of(source(frame)),LongBuffer.allocate(0));
                        var pending=request(frame);long deadline=System.nanoTime()+10_000_000_000L;
                        while(renderer.trySubmit(pending) instanceof Renderer.FrameSubmissionDeferred){
                            if(System.nanoTime()>deadline)throw new IllegalStateException("Native packet consumer stopped progressing");glfwPollEvents();
                        }
                        // Raster bottom image stays at 640x360. No CPU pixel upload/readback.
                        glUseProgram(0);glBindProgramPipeline(pipeline);glUseProgramStages(pipeline,GL_FRAGMENT_SHADER_BIT,raster);glBindVertexArray(vao);
                        glBindFramebuffer(GL_FRAMEBUFFER,baseFbo);glViewport(0,0,WIDTH,HEIGHT);glDisable(GL_FRAMEBUFFER_SRGB);glDrawArrays(GL_TRIANGLES,0,3);
                        consumer.compose(nativeControl.acquireLightingFrame().orElseThrow(),base,composedFbo,WIDTH,HEIGHT);
                        // The host owns final display mapping and window presentation.
                        glUseProgram(0);glBindProgramPipeline(pipeline);glUseProgramStages(pipeline,GL_FRAGMENT_SHADER_BIT,display);glBindTextureUnit(0,composed);glBindVertexArray(vao);
                        glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(0,0,WIDTH,HEIGHT);glEnable(GL_FRAMEBUFFER_SRGB);glDrawArrays(GL_TRIANGLES,0,3);glfwSwapBuffers(window);glfwPollEvents();
                    }
                    // Shutdown is the only CPU/GPU wait; ordinary frame consumption is asynchronous.
                    if(!consumer.awaitCompletionForShutdown(Duration.ofSeconds(5)))throw new IllegalStateException("GL shutdown completion timed out");
                    System.out.println("Native GL example completed: "+nativeControl.nativeLightingExecution());
                }finally{glDeleteProgramPipelines(pipeline);glDeleteProgram(vertex);glDeleteProgram(raster);glDeleteProgram(display);glDeleteVertexArrays(vao);
                    glDeleteFramebuffers(baseFbo);glDeleteFramebuffers(composedFbo);glDeleteTextures(base);glDeleteTextures(composed);}
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();glfwSetErrorCallback(null);}
        }
    }
}
