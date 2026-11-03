package top.ceroxe.rt.renderer.rtxpt.examples;

import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import top.ceroxe.rt.renderer.api.HostSurfaceInputLease;
import top.ceroxe.rt.renderer.api.HostSurfacePlan;
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
 * Input-direction GL4.5/D3D12 writer. One writer serves one renderer lifetime.
 * write() owns the lease and lends texture names only inside its GPU-work callback.
 * It changes the current program/image bindings; the host restores its own draw state.
 */
public final class GlHostSurfaceWriter implements AutoCloseable {
    public record Packet(HostSurfaceInputLease.Frame frame,Map<HostSurfacePlan.Plane,Integer> textures){
        public Packet {textures=Map.copyOf(textures);}
        public int texture(HostSurfacePlan.Plane kind){return textures.get(kind);}
    }
    /** Static z=0 card, normal +Z, centered view from (0,0,cameraDistance). */
    public record Card(long instanceId,float cameraDistance,float verticalFovDegrees,float aspectRatio,
            float halfWidth,float halfHeight,float red,float green,float blue,float roughness){
        public Card {
            if(instanceId<=0||!Float.isFinite(cameraDistance)||cameraDistance<=0||!Float.isFinite(verticalFovDegrees)
                ||verticalFovDegrees<=0||verticalFovDegrees>=179||!Float.isFinite(aspectRatio)||aspectRatio<=0
                ||!Float.isFinite(halfWidth)||halfWidth<=0||!Float.isFinite(halfHeight)||halfHeight<=0
                ||!Float.isFinite(red)||red<0||!Float.isFinite(green)||green<0||!Float.isFinite(blue)||blue<0
                ||!Float.isFinite(roughness)||roughness<0||roughness>1)throw new IllegalArgumentException("Invalid static reference card");
        }
        public static Card unit(long instanceId,float aspectRatio){return new Card(instanceId,3,52,aspectRatio,1,1,1,1,1,.8f);}
    }
    /** Explicit constant fixture values; hostLit RGB is the pre-fog lit diffuse layer, never material albedo. */
    public record AugmentationInputs(float localBaseline,float skyBaseline,float visibility,float fog,
            float hostRed,float hostGreen,float hostBlue,float hostAlpha){
        public AugmentationInputs {
            for(float value:new float[]{localBaseline,skyBaseline,visibility,fog,hostAlpha})
                if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Augmentation baselines, visibility, fog and alpha must be in [0,1]");
            for(float value:new float[]{hostRed,hostGreen,hostBlue})
                if(!Float.isFinite(value)||value<0||value>65504)throw new IllegalArgumentException("Host-lit RGB must be finite, nonnegative and representable by RGBA16F");
        }
    }
    private record Key(long generation,long fence,List<HostSurfaceInputLease.Plane> planes) { }
    private static final int[] NO_BUFFERS=new int[0];
    private static final class Imported implements AutoCloseable {
        final int semaphore;final int[] textures,memory,layouts;
        final Map<HostSurfacePlan.Plane,Integer> byKind=new EnumMap<>(HostSurfacePlan.Plane.class);
        Imported(HostSurfaceInputLease.Frame frame){
            int count=frame.planes().size();textures=new int[count];memory=new int[count];layouts=new int[count];semaphore=glGenSemaphoresEXT();
            try{
                glImportSemaphoreWin32HandleEXT(semaphore,GL_HANDLE_TYPE_D3D12_FENCE_EXT,frame.fenceHandle());
                for(int i=0;i<count;i++){
                    var plane=frame.planes().get(i);memory[i]=glCreateMemoryObjectsEXT();
                    glMemoryObjectParameteriEXT(memory[i],GL_DEDICATED_MEMORY_OBJECT_EXT,GL_TRUE);
                    glImportMemoryWin32HandleEXT(memory[i],plane.allocationBytes(),GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,plane.memoryHandle());
                    textures[i]=glCreateTextures(GL_TEXTURE_2D);glTextureParameteri(textures[i],GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                    glTextureParameteri(textures[i],GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTextureParameteri(textures[i],GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                    glTextureParameteri(textures[i],GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    glTextureStorageMem2DEXT(textures[i],1,format(plane.format()),frame.width(),frame.height(),memory[i],0);
                    byKind.put(plane.kind(),textures[i]);layouts[i]=GL_LAYOUT_GENERAL_EXT;
                }
                check("D3D12 host-surface import");
            }catch(RuntimeException|Error failure){close();throw failure;}
        }
        void waitPrevious(long value){glSemaphoreParameterui64EXT(semaphore,GL_D3D12_FENCE_VALUE_EXT,value);
            glWaitSemaphoreEXT(semaphore,NO_BUFFERS,textures,layouts);check("GPU previous-done wait enqueue");}
        void signalReady(long value){glSemaphoreParameterui64EXT(semaphore,GL_D3D12_FENCE_VALUE_EXT,value);
            glSignalSemaphoreEXT(semaphore,NO_BUFFERS,textures,layouts);glFlush();check("GPU host-ready signal enqueue");}
        @Override public void close(){for(int texture:textures)if(texture!=0)glDeleteTextures(texture);
            for(int allocation:memory)if(allocation!=0)glDeleteMemoryObjectsEXT(allocation);if(semaphore!=0)glDeleteSemaphoresEXT(semaphore);}
    }
    private final Thread owner=Thread.currentThread();private final long adapter;
    private final Map<Key,Imported> imports=new LinkedHashMap<>();
    private long newestGeneration,previousGeneration;private int cardProgram,augmentationProgram;private boolean closed;
    public GlHostSurfaceWriter(){
        var caps=GL.getCapabilities();
        if(!caps.OpenGL45||!caps.GL_EXT_memory_object||!caps.GL_EXT_memory_object_win32
            ||!caps.GL_EXT_semaphore||!caps.GL_EXT_semaphore_win32)
            throw new UnsupportedOperationException("OpenGL 4.5 and EXT memory/semaphore Win32 D3D12 interoperability are required");
        try(var stack=MemoryStack.stackPush()){
            var bytes=stack.malloc(GL_LUID_SIZE_EXT).order(ByteOrder.LITTLE_ENDIAN);
            org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytevEXT(GL_DEVICE_LUID_EXT,bytes);check("GL adapter LUID query");adapter=bytes.getLong(0);
        }
    }
    public long adapterLuid(){owner();return adapter;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the current GL/renderer owner thread");if(closed)throw new IllegalStateException("GL surface writer closed");}
    private static int format(HostSurfacePlan.Format format){return switch(format){
        case RGBA16_FLOAT->GL_RGBA16F;case RGBA32_FLOAT->GL_RGBA32F;case RG32_FLOAT->GL_RG32F;case RGBA32_UINT->GL_RGBA32UI;};}
    private static void check(String operation){int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException(operation+" failed: GL error 0x"+Integer.toHexString(error));}
    private Imported imported(HostSurfaceInputLease.Frame frame){
        if(frame.adapterLuid()!=adapter)throw new IllegalArgumentException("GL/D3D12 adapter LUID mismatch");
        if(frame.generation()>newestGeneration){
            previousGeneration=newestGeneration;newestGeneration=frame.generation();
            // Native permits one current and one retired generation. Observing a third
            // proves the oldest is GPU-retired, including its host-ready write commands.
            var entries=imports.entrySet().iterator();
            while(entries.hasNext()){var entry=entries.next();if(entry.getKey().generation()<previousGeneration){entries.remove();entry.getValue().close();}}
        }
        var key=new Key(frame.generation(),frame.fenceHandle(),frame.planes());var result=imports.get(key);
        if(result==null){if(imports.size()>=6)throw new IllegalStateException("Use one GL input writer per renderer lifetime");result=new Imported(frame);imports.put(key,result);}
        return result;
    }
    /** Enqueue host GPU writes and publish exactly this frame; no pixel readback or CPU fence wait. */
    public void write(HostSurfaceInputLease lease,Consumer<Packet> gpuWork){
        owner();Objects.requireNonNull(lease);Objects.requireNonNull(gpuWork);
        try(lease){
            var frame=lease.frame();var imported=imported(frame);imported.waitPrevious(frame.previousDoneValue());lease.markImported();
            try{
                gpuWork.accept(new Packet(frame,imported.byKind));glMemoryBarrier(GL_ALL_BARRIER_BITS);check("Host primary-surface GPU writes");
            }catch(RuntimeException|Error failure){
                try{imported.signalReady(frame.readyValue());lease.abandonSignalEnqueued();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
            imported.signalReady(frame.readyValue());
            try{lease.publish();}catch(RuntimeException|Error failure){
                try{lease.abandonSignalEnqueued();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }
    }
    /** GPU-generated reference card; the resident MeshAsset must use indices 0,1,2,0,2,3. */
    public void writeCard(HostSurfaceInputLease lease,Card card){
        owner();Objects.requireNonNull(lease);Objects.requireNonNull(card);
        if(lease.frame().composition()!=HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING)
            throw new IllegalArgumentException("An augmentation card requires explicit host-lit color and baseline inputs");
        ensureCardProgram();
        write(lease,packet->drawCard(packet,card));
    }
    /** Augmentation fixture; the real host supplies per-pixel values through write(). */
    public void writeCard(HostSurfaceInputLease lease,Card card,AugmentationInputs augmentation){
        owner();Objects.requireNonNull(lease);Objects.requireNonNull(card);Objects.requireNonNull(augmentation);
        if(lease.frame().composition()!=HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION)
            throw new IllegalArgumentException("Augmentation fixture values require an augmentation input reservation");
        ensureCardProgram();ensureAugmentationProgram();
        write(lease,packet->drawCard(packet,card,augmentation));
    }
    /** Fill already-owned GPU targets inside write()'s callback, or independent shader tests. */
    public void drawCard(Packet packet,Card card){
        owner();Objects.requireNonNull(packet);Objects.requireNonNull(card);ensureCardProgram();
        if(packet.frame().composition()!=HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING)
            throw new IllegalArgumentException("An augmentation card requires explicit host-lit color and baseline inputs");
        drawBaseCard(packet,card);
    }
    /** Uses two dispatches so augmentation does not require ten simultaneous GL image units. */
    public void drawCard(Packet packet,Card card,AugmentationInputs augmentation){
        owner();Objects.requireNonNull(packet);Objects.requireNonNull(card);Objects.requireNonNull(augmentation);
        if(packet.frame().composition()!=HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION)
            throw new IllegalArgumentException("Augmentation fixture values require ten input planes");
        ensureCardProgram();ensureAugmentationProgram();drawBaseCard(packet,card);
        glUseProgram(augmentationProgram);
        glBindImageTexture(0,packet.texture(HostSurfacePlan.Plane.HOST_LIGHTING_BASELINE),0,false,0,GL_WRITE_ONLY,GL_RGBA16F);
        glBindImageTexture(1,packet.texture(HostSurfacePlan.Plane.HOST_LIT_COLOR),0,false,0,GL_WRITE_ONLY,GL_RGBA16F);
        glProgramUniform4f(augmentationProgram,0,augmentation.localBaseline(),augmentation.skyBaseline(),augmentation.visibility(),augmentation.fog());
        glProgramUniform4f(augmentationProgram,1,augmentation.hostRed(),augmentation.hostGreen(),augmentation.hostBlue(),augmentation.hostAlpha());
        glDispatchCompute((packet.frame().width()+7)/8,(packet.frame().height()+7)/8,1);
    }
    private void drawBaseCard(Packet packet,Card card){
        glUseProgram(cardProgram);
        for(int i=0;i<HostSurfacePlan.BASE_PLANE_COUNT;i++){
            var plane=HostSurfacePlan.Plane.values()[i];glBindImageTexture(i,packet.texture(plane),0,false,0,GL_WRITE_ONLY,format(plane.format()));
        }
        glProgramUniform2ui(cardProgram,0,(int)card.instanceId(),(int)(card.instanceId()>>>32));
        float viewHalfHeight=card.cameraDistance()*(float)Math.tan(Math.toRadians(card.verticalFovDegrees())*.5);
        glProgramUniform4f(cardProgram,1,viewHalfHeight*card.aspectRatio(),viewHalfHeight,card.halfWidth(),card.halfHeight());
        glProgramUniform4f(cardProgram,2,card.red(),card.green(),card.blue(),card.roughness());
        glDispatchCompute((packet.frame().width()+7)/8,(packet.frame().height()+7)/8,1);
    }
    private void ensureCardProgram(){
        if(cardProgram!=0)return;
        int shader=glCreateShader(GL_COMPUTE_SHADER),program=0;
        try{
            glShaderSource(shader,"""
                #version 450 core
                layout(local_size_x=8,local_size_y=8) in;
                layout(rgba32f,binding=0) uniform writeonly image2D positionFlags;
                layout(rgba16f,binding=1) uniform writeonly image2D geometricCoverage;
                layout(rgba16f,binding=2) uniform writeonly image2D shadingCutoff;
                layout(rgba16f,binding=3) uniform writeonly image2D baseOpacity;
                layout(rgba16f,binding=4) uniform writeonly image2D material;
                layout(rgba16f,binding=5) uniform writeonly image2D emission;
                layout(rg32f,binding=6) uniform writeonly image2D motion;
                layout(rgba32ui,binding=7) uniform writeonly uimage2D identity;
                layout(location=0) uniform uvec2 objectId;
                layout(location=1) uniform vec4 viewAndCardHalfExtent;
                layout(location=2) uniform vec4 baseAndRoughness;
                void main(){
                    ivec2 p=ivec2(gl_GlobalInvocationID.xy),size=imageSize(positionFlags);if(any(greaterThanEqual(p,size)))return;
                    vec2 xy=(2*(vec2(p)+.5)/vec2(size)-1)*viewAndCardHalfExtent.xy;xy.y=-xy.y;
                    bool valid=all(lessThanEqual(abs(xy),viewAndCardHalfExtent.zw));
                    imageStore(positionFlags,p,vec4(xy,0,valid?3:8));
                    imageStore(geometricCoverage,p,vec4(0,0,1,valid?1:0));
                    imageStore(shadingCutoff,p,vec4(0,0,1,.5));
                    imageStore(baseOpacity,p,vec4(baseAndRoughness.rgb,1));
                    imageStore(material,p,vec4(baseAndRoughness.a,0,1.5,0));
                    imageStore(emission,p,vec4(0));imageStore(motion,p,vec4(0));
                    vec2 card=xy/viewAndCardHalfExtent.zw;
                    uint triangle=card.y<=card.x?0u:1u;
                    imageStore(identity,p,valid?uvec4(objectId,triangle,0):uvec4(0));
                }
                """);
            glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new IllegalStateException("GL surface writer compile failed: "+glGetShaderInfoLog(shader));
            program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException("GL surface writer link failed: "+glGetProgramInfoLog(program));
            cardProgram=program;program=0;
        }finally{glDeleteShader(shader);if(program!=0)glDeleteProgram(program);}
    }
    private void ensureAugmentationProgram(){
        if(augmentationProgram!=0)return;
        int shader=glCreateShader(GL_COMPUTE_SHADER),program=0;
        try{
            glShaderSource(shader,"""
                #version 450 core
                layout(local_size_x=8,local_size_y=8) in;
                layout(rgba16f,binding=0) uniform writeonly image2D lightingBaseline;
                layout(rgba16f,binding=1) uniform writeonly image2D hostLitColor;
                layout(location=0) uniform vec4 baseline;
                layout(location=1) uniform vec4 litColor;
                void main(){
                    ivec2 p=ivec2(gl_GlobalInvocationID.xy);
                    if(any(greaterThanEqual(p,imageSize(lightingBaseline))))return;
                    imageStore(lightingBaseline,p,baseline);imageStore(hostLitColor,p,litColor);
                }
                """);
            glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new IllegalStateException("GL augmentation fixture compile failed: "+glGetShaderInfoLog(shader));
            program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException("GL augmentation fixture link failed: "+glGetProgramInfoLog(program));
            augmentationProgram=program;program=0;
        }finally{glDeleteShader(shader);if(program!=0)glDeleteProgram(program);}
    }
    @Override public void close(){if(closed)return;owner();for(var allocation:imports.values())allocation.close();imports.clear();if(cardProgram!=0)glDeleteProgram(cardProgram);if(augmentationProgram!=0)glDeleteProgram(augmentationProgram);closed=true;}
}
