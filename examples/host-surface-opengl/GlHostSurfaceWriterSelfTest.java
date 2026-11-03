package top.ceroxe.rt.renderer.rtxpt.examples;

import java.util.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import top.ceroxe.rt.renderer.api.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Hidden shader-only validation; shared-resource/fence acceptance runs in the integration test. */
public final class GlHostSurfaceWriterSelfTest {
    private static final int WIDTH=17,HEIGHT=11;
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int format(HostSurfacePlan.Format format){return switch(format){
        case RGBA16_FLOAT->GL_RGBA16F;case RGBA32_FLOAT->GL_RGBA32F;case RG32_FLOAT->GL_RG32F;case RGBA32_UINT->GL_RGBA32UI;};}
    public static void main(String[] arguments){
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();require(glfwInit(),"Initialize hidden GL context");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
                window=glfwCreateWindow(WIDTH,HEIGHT,"Hidden host-surface shader validation",0,0);require(window!=0,"Create hidden GL window");
                glfwMakeContextCurrent(window);GL.createCapabilities();
                try(var writer=new GlHostSurfaceWriter()){
                    runCase(writer,new HostSurfacePlan(WIDTH,HEIGHT));
                    runCase(writer,HostSurfacePlan.augmentLitBase(WIDTH,HEIGHT,31));
                }
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();glfwSetErrorCallback(null);}
        }
    }
    private static void runCase(GlHostSurfaceWriter writer,HostSurfacePlan plan){
        var textures=new EnumMap<HostSurfacePlan.Plane,Integer>(HostSurfacePlan.Plane.class);
        var descriptors=new ArrayList<HostSurfaceInputLease.Plane>();
        var augmentation=new GlHostSurfaceWriter.AugmentationInputs(.125f,.25f,.5f,.75f,2,.5f,.25f,.625f);
        try{
                    for(var plane:plan.planes()){
                        int texture=glCreateTextures(GL_TEXTURE_2D);textures.put(plane,texture);
                        glTextureStorage2D(texture,1,format(plane.format()),WIDTH,HEIGHT);
                        descriptors.add(new HostSurfaceInputLease.Plane(plane,plane.format(),(long)WIDTH*HEIGHT*plane.format().bytesPerPixel(),100+plane.ordinal()));
                    }
                    var frame=new HostSurfaceInputLease.Frame(1,2,3,4,writer.adapterLuid(),WIDTH,HEIGHT,7,0,1,2,FrameClock.of(0,0),descriptors);
                    long object=0x1234567887654321L;
                    var packet=new GlHostSurfaceWriter.Packet(frame,textures);var card=GlHostSurfaceWriter.Card.unit(object,(float)WIDTH/HEIGHT);
                    if(plan.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION){
                        boolean rejected=false;try{writer.drawCard(packet,card);}catch(IllegalArgumentException expected){rejected=true;}
                        require(rejected,"Augmentation must not silently substitute material albedo for a host-lit input");
                        writer.drawCard(packet,card,augmentation);
                    }else{
                        boolean rejected=false;try{writer.drawCard(packet,card,augmentation);}catch(IllegalArgumentException expected){rejected=true;}
                        require(rejected,"Split input does not allocate augmentation targets");
                        writer.drawCard(packet,card);
                    }
                    glMemoryBarrier(GL_ALL_BARRIER_BITS);
                    // Explicit diagnostic readbacks are confined to this test, never the writer.
                    float[] positions=new float[WIDTH*HEIGHT*4];int[] identities=new int[WIDTH*HEIGHT*4];
                    glGetTextureImage(textures.get(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS),0,GL_RGBA,GL_FLOAT,positions);
                    glGetTextureImage(textures.get(HostSurfacePlan.Plane.IDENTITY),0,GL_RGBA_INTEGER,GL_UNSIGNED_INT,identities);
                    int supplied=0,background=0;
                    for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
                        int offset=(y*WIDTH+x)*4;float wx=positions[offset],wy=positions[offset+1],flags=positions[offset+3];
                        require(positions[offset+2]==0,"Reference card remains at z=0");
                        if(Math.abs(wx)<=1&&Math.abs(wy)<=1){
                            supplied++;require(flags==3,"Flags must be numeric VALID|OPAQUE, never float bitcasts");
                            require(identities[offset]==(int)object&&identities[offset+1]==(int)(object>>>32),"Stable public 64-bit instance ID survived GPU writes");
                            require(identities[offset+2]==(wy<=wx?0:1)&&identities[offset+3]==0,"Original triangle ordinal follows the published mesh");
                        }else{background++;require(flags==8,"Outside-card pixels are explicit background");}
                    }
                    for(var plane:plan.planes()){
                        int texture=textures.get(plane);require(glGetTextureLevelParameteri(texture,0,GL_TEXTURE_INTERNAL_FORMAT)==format(plane.format()),"Exact surface-plane format");
                        if(plane==HostSurfacePlan.Plane.IDENTITY||plane==HostSurfacePlan.Plane.WORLD_POSITION_FLAGS)continue;
                        int channels=plane==HostSurfacePlan.Plane.MOTION?2:4;float[] pixels=new float[WIDTH*HEIGHT*channels];
                        glGetTextureImage(texture,0,channels==2?GL_RG:GL_RGBA,GL_FLOAT,pixels);int center=(HEIGHT/2*WIDTH+WIDTH/2)*channels;
                        switch(plane){
                            case GEOMETRIC_NORMAL_COVERAGE->require(pixels[center+2]==1&&pixels[center+3]==1,"Geometric normal/coverage");
                            case SHADING_NORMAL_ALPHA_CUTOFF->require(pixels[center+2]==1&&pixels[center+3]==.5f,"Shading normal/cutout");
                            case BASE_COLOR_OPACITY->require(pixels[center]==1&&pixels[center+3]==1,"Linear base/opacity");
                            case MATERIAL->require(Math.abs(pixels[center]-.8f)<.001f&&pixels[center+2]==1.5f,"Material roughness/IOR");
                            case EMISSION,MOTION->{for(float pixel:pixels)require(pixel==0,"Static card has zero emission/motion");}
                            case HOST_LIGHTING_BASELINE->require(pixels[center]==.125f&&pixels[center+1]==.25f
                                &&pixels[center+2]==.5f&&pixels[center+3]==.75f,"Explicit independent local/sky/visibility/fog inputs");
                            case HOST_LIT_COLOR->require(pixels[center]==2&&pixels[center+1]==.5f
                                &&pixels[center+2]==.25f&&pixels[center+3]==.625f,"Already-lit color and straight alpha remain distinct from albedo");
                            default->throw new AssertionError("Unexpected surface plane");
                        }
                    }
                    require(supplied>0&&background>0&&glGetError()==GL_NO_ERROR,"Both supplied and background regions execute without GL errors");
                    System.out.println("HOST_SURFACE_GL_WRITER_SELFTEST PASS grid=17x11 composition="+plan.composition()+" planes="+plan.planes().size()+" formats=4 supplied="+supplied+" background="+background+" pixelReadbacks="+plan.planes().size()+" sharedInterop=notTested");
        }finally{for(int texture:textures.values())glDeleteTextures(texture);}
    }
}
