package top.ceroxe.rt.renderer.rtxpt.examples;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import top.ceroxe.rt.renderer.api.LightingFrameLease;
import top.ceroxe.rt.renderer.api.NativeLightingPlan;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_LUID_SIZE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;

/**
 * Owner-thread GPU-only D3D12 packet consumer for a current GL 4.5+ context.
 * One consumer serves one renderer's three-slot lifetime; destroy it before closing the renderer.
 * consume() owns/closes the lease. Borrowed GL texture names live only inside its callback.
 * compose() draws a linear host texture at full host resolution and owns the GL draw state.
 * The host must restore its draw state before drawing later UI/passes.
 */
public final class GlLightingPacketConsumer implements AutoCloseable {
    public record Packet(LightingFrameLease.Frame frame,Map<NativeLightingPlan.Signal,Integer> textures) {
        public Packet {textures=Map.copyOf(textures);}
        public int texture(NativeLightingPlan.Signal signal){return textures.getOrDefault(signal,0);}
    }
    private record Key(long generation,long fence,List<LightingFrameLease.Plane> planes) { }
    private static final int[] NO_BUFFERS=new int[0];
    private static final class Imported implements AutoCloseable {
        final int semaphore;final int[] textures,memory,layouts;
        final Map<NativeLightingPlan.Signal,Integer> bySignal=new EnumMap<>(NativeLightingPlan.Signal.class);
        Imported(LightingFrameLease.Frame frame) {
            int count=frame.planes().size();textures=new int[count];memory=new int[count];layouts=new int[count];
            semaphore=glGenSemaphoresEXT();
            try {
                glImportSemaphoreWin32HandleEXT(semaphore,GL_HANDLE_TYPE_D3D12_FENCE_EXT,frame.fenceHandle());
                for(int i=0;i<count;i++) {
                    var plane=frame.planes().get(i);int format=switch(plane.format()) {
                        case RGBA16_FLOAT->GL_RGBA16F;case RGBA32_FLOAT->GL_RGBA32F;case RGBA32_UINT->GL_RGBA32UI;case RGBA8_UNORM->GL_RGBA8;
                    };
                    int pixelBytes=switch(plane.format()){case RGBA8_UNORM->4;case RGBA16_FLOAT->8;case RGBA32_FLOAT,RGBA32_UINT->16;};
                    long minimum=(long)frame.width()*frame.height()*pixelBytes;
                    if(plane.memoryHandle()==0||plane.allocationBytes()<minimum)throw new IllegalArgumentException("Invalid shared signal allocation");
                    memory[i]=glCreateMemoryObjectsEXT();glMemoryObjectParameteriEXT(memory[i],GL_DEDICATED_MEMORY_OBJECT_EXT,GL_TRUE);
                    glImportMemoryWin32HandleEXT(memory[i],plane.allocationBytes(),GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,plane.memoryHandle());
                    textures[i]=glCreateTextures(GL_TEXTURE_2D);
                    glTextureParameteri(textures[i],GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(textures[i],GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    glTextureParameteri(textures[i],GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTextureParameteri(textures[i],GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    glTextureStorageMem2DEXT(textures[i],1,format,frame.width(),frame.height(),memory[i],0);
                    if(bySignal.put(plane.signal(),textures[i])!=null)throw new IllegalArgumentException("Duplicate lighting signal");
                    // The D3D12 producer publishes Common; General is the external handoff layout.
                    layouts[i]=GL_LAYOUT_GENERAL_EXT;
                }
                check("D3D12 HDR import");
            }catch(RuntimeException|Error failure){close();throw failure;}
        }
        void waitReady(long value){glSemaphoreParameterui64EXT(semaphore,GL_D3D12_FENCE_VALUE_EXT,value);
            glWaitSemaphoreEXT(semaphore,NO_BUFFERS,textures,layouts);check("GPU ready wait enqueue");}
        void signalDone(long value){glSemaphoreParameterui64EXT(semaphore,GL_D3D12_FENCE_VALUE_EXT,value);
            glSignalSemaphoreEXT(semaphore,NO_BUFFERS,textures,layouts);glFlush();check("GPU completion signal enqueue");}
        @Override public void close(){
            for(int texture:textures)if(texture!=0)glDeleteTextures(texture);
            for(int allocation:memory)if(allocation!=0)glDeleteMemoryObjectsEXT(allocation);
            if(semaphore!=0)glDeleteSemaphoresEXT(semaphore);
        }
    }
    private final Thread owner=Thread.currentThread();private final long adapter;
    private final LinkedHashMap<Key,Imported> imports=new LinkedHashMap<>(4,.75f,true);
    private int program,vao;private boolean closed;
    public GlLightingPacketConsumer() {
        var caps=GL.getCapabilities();
        if(!caps.OpenGL45||!caps.GL_EXT_memory_object||!caps.GL_EXT_memory_object_win32
                ||!caps.GL_EXT_semaphore||!caps.GL_EXT_semaphore_win32)
            throw new UnsupportedOperationException("OpenGL 4.5 and EXT memory/semaphore Win32 D3D12 interoperability are required");
        try(var stack=MemoryStack.stackPush()){
            var bytes=stack.malloc(GL_LUID_SIZE_EXT).order(ByteOrder.LITTLE_ENDIAN);
            org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytevEXT(GL_DEVICE_LUID_EXT,bytes);check("GL adapter LUID query");adapter=bytes.getLong(0);
        }
        try {ensureProgram();check("GL compositor initialization");}
        catch(RuntimeException|Error failure){close();throw failure;}
    }
    public long adapterLuid(){owner();return adapter;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the current GL/renderer owner thread");if(closed)throw new IllegalStateException("GL lighting consumer closed");}
    private static void check(String action){int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException(action+" failed: GL error 0x"+Integer.toHexString(error));}
    /** Enqueue waits, callback GPU work and completion; never wait for GPU completion on the CPU. */
    public void consume(LightingFrameLease lease,Consumer<Packet> work) {
        owner();Objects.requireNonNull(lease);Objects.requireNonNull(work);
        try(lease){
            var frame=lease.frame();
            if(frame.adapterLuid()!=adapter)throw new IllegalArgumentException("GL/D3D12 adapter LUID mismatch: GL="+Long.toUnsignedString(adapter,16)+" D3D12="+Long.toUnsignedString(frame.adapterLuid(),16));
            var key=new Key(frame.generation(),frame.fenceHandle(),frame.planes());var imported=imports.get(key);
            if(imported==null){
                // A producer reuses a slot's fence only after the preceding consumer
                // completion. Therefore an old allocation on that same fence is retired
                // without polling GL or deleting another slot's in-flight imports.
                var previous=imports.entrySet().iterator();
                while(previous.hasNext()){var entry=previous.next();if(entry.getKey().fence()==frame.fenceHandle()){
                    previous.remove();entry.getValue().close();}}
                if(imports.size()>=3)throw new IllegalStateException("Use a new GL consumer for a replacement renderer session");
                imported=new Imported(frame);imports.put(key,imported);
            }
            imported.waitReady(frame.readyValue());lease.markImported();
            try {work.accept(new Packet(frame,imported.bySignal));}
            finally {
                int workError=glGetError();imported.signalDone(frame.completionValue());lease.completionSignalEnqueued();
                if(workError!=GL_NO_ERROR)throw new IllegalStateException("GL consumer draw failed: 0x"+Integer.toHexString(workError));
            }
        }
    }
    /**
     * Compose linear host RGB * direct gain + independent linear indirect contribution.
     * The host texture stays at native resolution; native D3D rows are flipped to GL UVs.
     * This small reference uses nearest signal sampling, without edge-aware reconstruction.
     */
    public void compose(LightingFrameLease lease,int linearHostTexture,int targetFramebuffer,int width,int height) {
        owner();if(linearHostTexture<=0||width<1||height<1)throw new IllegalArgumentException("Valid host texture and target extent required");
        consume(lease,packet->drawComposition(packet,linearHostTexture,targetFramebuffer,width,height));
    }
    /** Draw within consume()'s callback, allowing additional host GPU work before completion. */
    public void drawComposition(Packet packet,int linearHostTexture,int targetFramebuffer,int width,int height) {
        owner();if(linearHostTexture<=0||width<1||height<1)throw new IllegalArgumentException("Valid host texture and target extent required");
        glBindFramebuffer(GL_FRAMEBUFFER,targetFramebuffer);glViewport(0,0,width,height);
        glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);glDisable(GL_FRAMEBUFFER_SRGB);
        glUseProgram(program);glBindVertexArray(vao);glBindTextureUnit(0,linearHostTexture);
        int direct=packet.texture(NativeLightingPlan.Signal.DIRECT_GAIN),indirect=packet.texture(NativeLightingPlan.Signal.INDIRECT_DIFFUSE);
        glBindTextureUnit(1,direct);glBindTextureUnit(2,indirect);glProgramUniform1i(program,0,direct!=0?1:0);glProgramUniform1i(program,1,indirect!=0?1:0);
        glProgramUniform1i(program,2,packet.frame().encoding().ordinal());
        glProgramUniform1f(program,3,packet.frame().maximumGi());
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    private void ensureProgram() {
        if(program!=0)return;
        String vertex="""
            #version 450 core
            out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}
            """;
        String fragment="""
            #version 450 core
            layout(binding=0) uniform sampler2D hostColor;
            layout(binding=1) uniform sampler2D directGain;
            layout(binding=2) uniform sampler2D indirectContribution;
            layout(location=0) uniform bool hasDirect;
            layout(location=1) uniform bool hasIndirect;
            layout(location=2) uniform int compactEncoding;
            layout(location=3) uniform float maximumGi;
            in vec2 uv;layout(location=0) out vec4 color;
            void main(){
                vec3 base=texture(hostColor,uv).rgb;
                vec2 signalUv=vec2(uv.x,1-uv.y);
                vec4 d=hasDirect?texture(directGain,signalUv):vec4(1,1,1,0);
                vec4 i=hasIndirect?texture(indirectContribution,signalUv):vec4(0);
                if(compactEncoding!=0){
                    if(hasDirect){vec3 code=round(d.rgb*255);float slope=compactEncoding==2?log2(255.0)/127:1.0/32;d.rgb=mix(code/128,exp2((code-128)*slope),greaterThan(code,vec3(128)));}
                    if(hasIndirect)i.rgb=i.rgb*i.rgb*maximumGi;
                }
                color=vec4(base*mix(vec3(1),d.rgb,clamp(d.a,0,1))+i.rgb*clamp(i.a,0,1),1);
            }
            """;
        int vs=compile(GL_VERTEX_SHADER,vertex),fs=0,next=0;
        try {
            fs=compile(GL_FRAGMENT_SHADER,fragment);next=glCreateProgram();glAttachShader(next,vs);glAttachShader(next,fs);glLinkProgram(next);
            if(glGetProgrami(next,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException("GL compositor link failed: "+glGetProgramInfoLog(next));
            vao=glCreateVertexArrays();program=next;next=0;
        }finally{glDeleteShader(vs);if(fs!=0)glDeleteShader(fs);if(next!=0)glDeleteProgram(next);}
    }
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE){String log=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException("GL compositor compile failed: "+log);}return shader;}
    /** Explicit shutdown-only wait. The frame consumption path never calls it. */
    public boolean awaitCompletionForShutdown(Duration timeout){
        owner();Objects.requireNonNull(timeout);if(timeout.isNegative())throw new IllegalArgumentException("Negative shutdown timeout");
        long sync=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(sync==0)throw new IllegalStateException("Could not create shutdown GL fence");
        try{glFlush();int result=glClientWaitSync(sync,GL_SYNC_FLUSH_COMMANDS_BIT,timeout.toNanos());
            if(result==GL_WAIT_FAILED)throw new IllegalStateException("GL shutdown fence failed");return result==GL_ALREADY_SIGNALED||result==GL_CONDITION_SATISFIED;
        }finally{glDeleteSync(sync);}
    }
    @Override public void close(){
        if(closed)return;owner();for(var packet:imports.values())packet.close();imports.clear();if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);closed=true;
    }
}
