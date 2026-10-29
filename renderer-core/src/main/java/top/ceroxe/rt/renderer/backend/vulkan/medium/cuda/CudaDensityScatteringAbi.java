package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.LongFunction;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** One resident allocation; complete final outputs and bounded density/light scratch. */
public final class CudaDensityScatteringAbi {
    public static final int MAGIC=0x52445331,VERSION=1,HEADER_BYTES=144;
    public static final int PRIMARY_BYTES=208,COLOR_BYTES=16,RAY_BYTES=32,SAMPLE_BYTES=32,CONNECTION_BYTES=32;
    public static final int CONNECTIONS=9,FAILED_OFFSET=112;
    public static final long DEFAULT_RESIDENT_BYTES=512L*1024*1024,ALLOCATION_ALLOWANCE=65_536;
    private CudaDensityScatteringAbi() { }
    public record Plan(int pixels,int samples,int steps,int tilePixels,int densityOffset,int primaryOffset,
            int transmittanceOffset,int emissionOffset,int rayOffset,int sampleOffset,int connectionOffset,int connections,long bytes) {
        public int paths() {return Math.multiplyExact(pixels,samples);}
        public int tilePaths() {return Math.multiplyExact(tilePixels,samples);}
    }
    public static long minimumBytes(int width,int height,int samples,int steps) {
        return minimumBytes(width,height,samples,steps,CONNECTIONS);
    }
    private static long minimumBytes(int width,int height,int samples,int steps,int connections) {
        check(width,height,samples,steps);
        long finalBytes=Math.multiplyExact(Math.multiplyExact((long)width*height,samples),PRIMARY_BYTES+2*COLOR_BYTES);
        return Math.addExact(HEADER_BYTES+finalBytes,Math.multiplyExact((long)samples,scratchPerPath(steps,connections)));
    }
    public static Plan plan(int width,int height,int samples,int steps,CudaMediumDensityAbi.Scene density,int connections,long residentLimit) {
        if(connections<1||connections>CONNECTIONS)throw new IllegalArgumentException("bounded 1..9 light-connection chunk required");
        check(width,height,samples,steps);int pixels=Math.toIntExact((long)width*height);
        long paths=Math.multiplyExact((long)pixels,samples);
        int primary=Math.addExact(HEADER_BYTES,density.bytes());
        int tr=Math.toIntExact(primary+paths*PRIMARY_BYTES),em=Math.toIntExact(tr+paths*COLOR_BYTES);
        int rays=Math.toIntExact(em+paths*COLOR_BYTES);
        long available=residentLimit-ALLOCATION_ALLOWANCE-rays,perPixel=Math.multiplyExact((long)samples,scratchPerPath(steps,connections));
        int tile=Math.toIntExact(Math.min(pixels,Math.max(0,available)/perPixel));
        if(tile<1)throw new IllegalArgumentException("complete density-scattering output exceeds the CUDA resident cap");
        int sample=Math.toIntExact(rays+(long)tile*samples*RAY_BYTES);
        int connection=Math.toIntExact(sample+(long)tile*samples*steps*SAMPLE_BYTES);
        long bytes=Math.addExact(connection,(long)tile*samples*steps*connections*CONNECTION_BYTES);
        return new Plan(pixels,samples,steps,tile,HEADER_BYTES,primary,tr,em,rays,sample,connection,connections,bytes);
    }
    private static long scratchPerPath(int steps,int connections) {return RAY_BYTES+(long)steps*(SAMPLE_BYTES+connections*CONNECTION_BYTES);}
    private static void check(int width,int height,int samples,int steps) {
        if(width<1||height<1||samples<1||samples>8||steps<1||steps>64)
            throw new IllegalArgumentException("positive extent, 1..8 SPP, 1..64 clipped intervals required");
    }
    public record FrameScene(RenderFrameRequest frame,Plan plan,CudaMediumDensityAbi.Scene density,int lightUpperBound) {
        public int chunks() {
            long count=frame.mediumTransport().lightSamples()==0?(long)lightUpperBound+1:Math.min(lightUpperBound,frame.mediumTransport().lightSamples())+1;
            return Math.toIntExact((count+plan.connections()-1)/plan.connections());
        }
        public byte[] metadata(int firstPixel,int pixelCount,int phase,int chunk) {
            return pack(firstPixel,pixelCount,phase,chunk,true);
        }
        public byte[] header(int firstPixel,int pixelCount,int phase,int chunk) {
            return pack(firstPixel,pixelCount,phase,chunk,false);
        }
        private byte[] pack(int firstPixel,int pixelCount,int phase,int chunk,boolean includeDensity) {
            if(firstPixel<0||pixelCount<1||pixelCount>plan.tilePixels()||(long)firstPixel+pixelCount>plan.pixels()
                    ||phase<0||phase>1||chunk<0||chunk>=chunks())throw new IllegalArgumentException("invalid density-scattering tile");
            var medium=frame.environment().cameraMedium();var a=medium.extinction().multiply(medium.density());
            var s=medium.scattering().multiply(medium.density());var e=medium.emission().multiply(medium.density());var env=frame.environment();
            ByteBuffer out=ByteBuffer.allocate(includeDensity?plan.primaryOffset():HEADER_BYTES).order(ByteOrder.nativeOrder());
            out.putInt(MAGIC).putInt(VERSION).putInt(pixelCount*plan.samples()).putInt(plan.steps())
                    .putInt(firstPixel).putInt(pixelCount).putInt(plan.samples()).putInt(phase)
                    .putInt(plan.primaryOffset()).putInt(plan.transmittanceOffset()).putInt(plan.emissionOffset()).putInt(plan.rayOffset())
                    .putFloat(a.red()).putFloat(a.green()).putFloat(a.blue()).putFloat(frame.mediumTransport().maxDistance())
                    .putFloat(s.red()).putFloat(s.green()).putFloat(s.blue()).putFloat(medium.anisotropy())
                    .putFloat(e.red()).putFloat(e.green()).putFloat(e.blue()).putFloat(frame.mediumTransport().transmittanceCutoff())
                    .putFloat(env.skyRed()*env.ambientIntensity()).putFloat(env.skyGreen()*env.ambientIntensity())
                    .putFloat(env.skyBlue()*env.ambientIntensity()).putInt((int)frame.sequence())
                    .putInt(0).putInt(plan.densityOffset()+density.environmentOffset()).putInt(plan.sampleOffset()).putInt(plan.connectionOffset())
                    .putInt(chunk*plan.connections()).putInt(plan.connections()).putInt(lightUpperBound).putInt(frame.mediumTransport().lightSamples());
            if(includeDensity)density.write(out,plan.densityOffset());return out.array();
        }
    }
    public static FrameScene scene(RenderFrameRequest frame,int width,int height,int lightUpperBound,
            LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache,long residentLimit) {
        var medium=frame.environment().cameraMedium();
        if(lightUpperBound<0||medium.densityField().isUniform()||medium.scattering().isZero()||medium.indexOfRefraction()!=1
                ||frame.mediumTransport().scatteringBounces()!=0)throw new IllegalArgumentException("density-scattering requires heterogeneous IOR-one direct transport");
        int samples=frame.antiAliasing().samplesPerPixel(),steps=frame.mediumTransport().scatteringSteps();
        int connections=(int)Math.min(CONNECTIONS,1L+(frame.mediumTransport().lightSamples()==0?lightUpperBound:Math.min(lightUpperBound,frame.mediumTransport().lightSamples())));
        long limit=residentLimit-ALLOCATION_ALLOWANCE-minimumBytes(width,height,samples,steps,connections);
        var descriptorMedium=medium.participating()?medium:medium.withDensity(1);
        var density=cache==null?CudaMediumDensityAbi.prepare(descriptorMedium,List.of(),textures,limit):cache.prepare(descriptorMedium,List.of(),textures,limit);
        var plan=plan(width,height,samples,steps,density,connections,residentLimit);
        return new FrameScene(frame,plan,density,lightUpperBound);
    }
}
