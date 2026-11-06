package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Isolated synthetic guide fixtures drawn by the real OpenGL reconstruction shader. */
public final class GlGuidedLightingComposerSelfTest {
    static final int W=19,H=11;
    static int texture(int w,int h,int format){int t=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(t,1,format,w,h);glTextureParameteri(t,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(t,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return t;}
    static int floats(int w,int h,float[] data){int t=texture(w,h,GL_RGBA32F);var b=MemoryUtil.memAllocFloat(data.length);try{b.put(data).flip();glTextureSubImage2D(t,0,0,0,w,h,GL_RGBA,GL_FLOAT,b);}finally{MemoryUtil.memFree(b);}return t;}
    static int integers(int w,int h,int[] data){int t=texture(w,h,GL_RGBA32UI);var b=MemoryUtil.memAllocInt(data.length);try{b.put(data).flip();glTextureSubImage2D(t,0,0,0,w,h,GL_RGBA_INTEGER,GL_UNSIGNED_INT,b);}finally{MemoryUtil.memFree(b);}return t;}
    public static void main(String[] args){
        require(glfwInit(),"Initialize GL");long window=0;
        try{glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(W,H,"Guided lighting fixtures",0,0);require(window!=0,"Create GL context");glfwMakeContextCurrent(window);GL.createCapabilities();
            for(int width:new int[]{8,9})run(width,width==8?4:5);require(glGetError()==GL_NO_ERROR,"GL reconstruction has no API error");
            System.out.println("GL_GUIDED_RECONSTRUCTION PASS synthetic=true grids=8x4,9x5 host=19x11 assertions=3762 identity,depth,normal,thin,invalid,coverage,checker compactAndHdr=true wideResponse=true");
        }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();}
    }
    static void run(int lw,int lh){
        for(var encoding:NativeLightingPlan.Encoding.values()){
            boolean compact=encoding!=NativeLightingPlan.Encoding.HDR_FLOAT16;
            float[] base=new float[W*H*4],hg=new float[base.length],lg=new float[lw*lh*4],direct=new float[lg.length],gi=new float[lg.length];int[] hi=new int[base.length],li=new int[lg.length];
            var style=NativeLightingStyle.defaults();
            for(int y=0;y<H;y++)for(int x=0;x<W;x++){int k=(y*W+x)*4;float b=((x^y)&1)==0?.2f:.8f;Arrays.fill(base,k,k+3,b);base[k+3]=1;
                hg[k+2]=y==3?-1:1;hg[k+3]=y==2?100:x<W/2?2:4;hi[k]=x==W/2?99:x<W/2?0:1;hi[k+3]=y==4?0:1;}
            for(int y=0;y<lh;y++)for(int x=0;x<lw;x++){int k=(y*lw+x)*4,id=x<lw/2?0:1;lg[k+2]=1;lg[k+3]=id==0?2:4;li[k]=id;li[k+3]=1;
                float gain=id==0?.5f:2,indirect=id==0?.1f:.2f;Arrays.fill(direct,k,k+3,compact?style.encodeGain8(gain,encoding)/255f:gain);direct[k+3]=.25f;
                Arrays.fill(gi,k,k+3,compact?style.encodeGi8(indirect)/255f:indirect);gi[k+3]=.25f;}
            int host=floats(W,H,base),hostG=floats(W,H,hg),hostI=integers(W,H,hi),lowG=floats(lw,lh,lg),lowI=integers(lw,lh,li),d=floats(lw,lh,direct),i=floats(lw,lh,gi),target=texture(W,H,GL_RGBA32F),fbo=glCreateFramebuffers();
            try(var composer=new GlGuidedLightingComposer()){
                glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,target,0);require(glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Complete GL fixture framebuffer");
                var frame=new LightingFrameLease.Frame(1,0,1,0,lw,lh,0,0,0,0,FrameClock.of(0,0),List.of(),encoding,1);
                var textures=Map.of(NativeLightingPlan.Signal.DIRECT_GAIN,d,NativeLightingPlan.Signal.INDIRECT_DIFFUSE,i,NativeLightingPlan.Signal.NORMAL_DISTANCE,lowG,NativeLightingPlan.Signal.RECEIVER_IDENTITY,lowI);
                composer.draw(new GlLightingPacketConsumer.Packet(frame,textures),host,hostG,hostI,fbo,W,H,.1f,.9f);
                var pixels=MemoryUtil.memAllocFloat(base.length);
                try{glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,pixels);
                    for(int y=0;y<H;y++)for(int x=0;x<W;x++){int k=(y*W+x)*4;boolean fallback=x==W/2||y==2||y==3||y==4;int id=x<W/2?0:1;
                        float gain=id==0?.5f:2,indirect=id==0?.1f:.2f;if(compact){gain=style.decodeGain8(style.encodeGain8(gain,encoding),encoding);indirect=style.decodeGi8(style.encodeGi8(indirect));}
                        float expected=fallback?base[k]:base[k]*(1+.25f*(gain-1))+.25f*indirect;
                        for(int c=0;c<3;c++)require(Math.abs(pixels.get(k+c)-expected)<1e-5,"Guide oracle x="+x+" y="+y+" grid="+lw+" compact="+compact+" expected="+expected+" actual="+pixels.get(k+c));}
                }finally{MemoryUtil.memFree(pixels);}
            }finally{glDeleteFramebuffers(fbo);for(int t:new int[]{host,hostG,hostI,lowG,lowI,d,i,target})glDeleteTextures(t);}
        }
    }
}
