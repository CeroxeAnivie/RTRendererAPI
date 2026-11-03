package top.ceroxe.rt.renderer.rtxpt.examples;

import top.ceroxe.rt.renderer.api.NativeLightingPlan;
import static org.lwjgl.opengl.GL45C.*;

/** Optional spatial reconstruction. Draw inside GlLightingPacketConsumer.consume().
 * Host color is sampled at native resolution. Guides are normal.xyz and positive
 * camera-ray distance in w, plus the producer's uint4 instance/geometry/primitive/valid
 * identity, at host resolution with GL bottom-left rows. No temporal reuse or RR. */
public final class GlGuidedLightingComposer implements AutoCloseable {
    private int program,vao;
    public void draw(GlLightingPacketConsumer.Packet packet,int hostColor,int hostNormalDistance,int hostIdentity,
            int targetFramebuffer,int width,int height,float relativeDepthTolerance,float minimumNormalDot){
        if(hostColor<=0||hostNormalDistance<=0||hostIdentity<=0||width<1||height<1
            ||!Float.isFinite(relativeDepthTolerance)||relativeDepthTolerance<=0
            ||!Float.isFinite(minimumNormalDot)||minimumNormalDot<0||minimumNormalDot>1)throw new IllegalArgumentException("Valid full-resolution guides and thresholds required");
        int lowNormal=packet.texture(NativeLightingPlan.Signal.NORMAL_DISTANCE),lowIdentity=packet.texture(NativeLightingPlan.Signal.RECEIVER_IDENTITY);
        if(lowNormal==0||lowIdentity==0)throw new IllegalArgumentException("Guided reconstruction requires optional NORMAL_DISTANCE and IDENTITY signals");
        prepare();glBindFramebuffer(GL_FRAMEBUFFER,targetFramebuffer);glViewport(0,0,width,height);
        glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);glDisable(GL_FRAMEBUFFER_SRGB);
        glUseProgram(program);glBindVertexArray(vao);
        int direct=packet.texture(NativeLightingPlan.Signal.DIRECT_GAIN),indirect=packet.texture(NativeLightingPlan.Signal.INDIRECT_DIFFUSE);
        int[] textures={hostColor,direct,indirect,hostNormalDistance,hostIdentity,lowNormal,lowIdentity};
        for(int i=0;i<textures.length;i++)glBindTextureUnit(i,textures[i]);
        glProgramUniform1i(program,0,direct!=0?1:0);glProgramUniform1i(program,1,indirect!=0?1:0);
        glProgramUniform1i(program,2,packet.frame().encoding()==NativeLightingPlan.Encoding.COMPACT_UNORM8?1:0);
        glProgramUniform1f(program,3,packet.frame().maximumGi());glProgramUniform1f(program,4,relativeDepthTolerance);glProgramUniform1f(program,5,minimumNormalDot);
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    private void prepare(){
        if(program!=0)return;
        String vertex="""
            #version 450 core
            out vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}
            """;
        String fragment="""
            #version 450 core
            layout(binding=0) uniform sampler2D hostColor;
            layout(binding=1) uniform sampler2D directGain;
            layout(binding=2) uniform sampler2D indirectContribution;
            layout(binding=3) uniform sampler2D hostGeometry;
            layout(binding=4) uniform usampler2D hostIdentity;
            layout(binding=5) uniform sampler2D lowGeometry;
            layout(binding=6) uniform usampler2D lowIdentity;
            layout(location=0) uniform bool hasDirect;
            layout(location=1) uniform bool hasIndirect;
            layout(location=2) uniform bool compactEncoding;
            layout(location=3) uniform float maximumGi;
            layout(location=4) uniform float depthTolerance;
            layout(location=5) uniform float minimumNormalDot;
            in vec2 uv;layout(location=0) out vec4 color;
            vec4 decodeDirect(vec4 d){if(compactEncoding){vec3 code=round(d.rgb*255);d.rgb=mix(code/128,exp2((code-128)/32),greaterThan(code,vec3(128)));}return d;}
            vec4 decodeGi(vec4 i){if(compactEncoding)i.rgb=i.rgb*i.rgb*maximumGi;return i;}
            void main(){
                vec3 base=texture(hostColor,uv).rgb;vec4 hg=texture(hostGeometry,uv);uvec4 hi=texture(hostIdentity,uv);
                if(hi.w==0u||hg.w<=0||dot(hg.xyz,hg.xyz)<1e-10){color=vec4(base,1);return;}
                ivec2 size=textureSize(lowGeometry,0);vec2 p=vec2(uv.x,1-uv.y)*vec2(size)-.5;ivec2 center=ivec2(floor(p+.5));
                vec3 gain=vec3(0),gi=vec3(0);float total=0;
                for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
                    ivec2 q=center+ivec2(x,y);if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,size)))continue;
                    uvec4 li=texelFetch(lowIdentity,q,0);if(li.w==0u||any(notEqual(li.xyz,hi.xyz)))continue;
                    vec4 lg=texelFetch(lowGeometry,q,0);if(lg.w<=0||dot(lg.xyz,lg.xyz)<1e-10)continue;
                    float normalWeight=dot(normalize(hg.xyz),normalize(lg.xyz));if(normalWeight<minimumNormalDot)continue;
                    float depthError=abs(lg.w-hg.w)/max(hg.w,1e-4);if(depthError>depthTolerance)continue;
                    vec2 delta=abs(vec2(q)-p);float w=max(0,1.5-delta.x)*max(0,1.5-delta.y)*pow(max(normalWeight,0),16)*(1-depthError/depthTolerance);
                    if(w<=0)continue;
                    vec4 d=hasDirect?decodeDirect(texelFetch(directGain,q,0)):vec4(1,1,1,0);
                    vec4 i=hasIndirect?decodeGi(texelFetch(indirectContribution,q,0)):vec4(0);
                    gain+=mix(vec3(1),d.rgb,clamp(d.a,0,1))*w;gi+=i.rgb*clamp(i.a,0,1)*w;total+=w;
                }
                // An unsampled/mismatched surface retains the host; never borrow a different surface's light.
                color=vec4(total>0?base*(gain/total)+gi/total:base,1);
            }
            """;
        int vs=compile(GL_VERTEX_SHADER,vertex),fs=0,next=0;
        try{fs=compile(GL_FRAGMENT_SHADER,fragment);next=glCreateProgram();glAttachShader(next,vs);glAttachShader(next,fs);glLinkProgram(next);
            if(glGetProgrami(next,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(next));
            vao=glCreateVertexArrays();program=next;next=0;
        }finally{glDeleteShader(vs);if(fs!=0)glDeleteShader(fs);if(next!=0)glDeleteProgram(next);}
    }
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE){String error=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException(error);}return shader;}
    @Override public void close(){if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);program=vao=0;}
}
