package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Bounded lighting scratch and complete primary/T/radiance output in one exported allocation. */
public final class CudaCameraScatteringAbi {
    public static final int MAGIC=0x52534331, VERSION=1, HEADER_BYTES=128;
    public static final int PRIMARY_BYTES=208, COLOR_BYTES=16, SOURCE_BYTES=16;
    public static final int FAILED_OFFSET=112;
    public static final long DEFAULT_RESIDENT_BYTES=512L*1024*1024, ALLOCATION_ALLOWANCE=65_536;
    private CudaCameraScatteringAbi() { }

    public record Plan(int pixels,int samples,int steps,int tilePixels,int primaryOffset,
                       int transmittanceOffset,int emissionOffset,int sourceOffset,long bytes) {
        public int paths() {return Math.multiplyExact(pixels,samples);}
    }

    public static Plan plan(int width,int height,int samples,int steps) {
        return plan(width,height,samples,steps,DEFAULT_RESIDENT_BYTES);
    }
    public static Plan plan(int width,int height,int samples,int steps,long residentLimit) {
        if(width<1||height<1||samples<1||samples>8||steps<1||steps>64)
            throw new IllegalArgumentException("positive extent, 1..8 samples and 1..64 intervals required");
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height));
        long paths=Math.multiplyExact((long)pixels,samples);
        int attenuation=Math.toIntExact(HEADER_BYTES+Math.multiplyExact(paths,PRIMARY_BYTES));
        int emission=Math.toIntExact(attenuation+Math.multiplyExact(paths,COLOR_BYTES));
        int source=Math.toIntExact(emission+Math.multiplyExact(paths,COLOR_BYTES));
        long available=Math.subtractExact(Math.subtractExact(residentLimit,ALLOCATION_ALLOWANCE),source);
        long perPixel=Math.multiplyExact((long)samples*steps,SOURCE_BYTES);
        // The allocation already contains complete frame outputs. Bound scratch by the
        // actual remaining session budget, never an inherited fixed dispatch capacity.
        int tilePixels=Math.toIntExact(Math.min(pixels,available/perPixel));
        if(tilePixels<1)throw new IllegalArgumentException("complete scattering frame exceeds the CUDA resident cap");
        long bytes=Math.addExact(source,Math.multiplyExact((long)tilePixels,perPixel));
        return new Plan(pixels,samples,steps,tilePixels,HEADER_BYTES,attenuation,emission,source,bytes);
    }

    /** The only CPU upload is this frame/tile header; rays, lighting and pixels stay on the GPU. */
    public static byte[] header(Plan plan,RenderFrameRequest frame,int firstPixel,int pixelCount) {
        if(firstPixel<0||pixelCount<1||pixelCount>plan.tilePixels()||(long)firstPixel+pixelCount>plan.pixels()
                ||frame.antiAliasing().samplesPerPixel()!=plan.samples()
                ||frame.mediumTransport().scatteringSteps()!=plan.steps())
            throw new IllegalArgumentException("invalid native scattering tile or frame budgets");
        var medium=frame.environment().cameraMedium();
        if(!medium.densityField().isUniform()||medium.indexOfRefraction()!=1
                ||frame.mediumTransport().scatteringBounces()!=0)
            throw new IllegalArgumentException("camera scattering requires uniform IOR-one direct transport");
        var extinction=medium.extinction().multiply(medium.density());
        var scattering=medium.scattering().multiply(medium.density());
        var emission=medium.emission().multiply(medium.density());
        var environment=frame.environment();
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.nativeOrder())
                .putInt(MAGIC).putInt(VERSION).putInt(pixelCount*plan.samples()).putInt(plan.steps())
                .putInt(firstPixel).putInt(pixelCount).putInt(plan.samples()).putInt(0)
                .putInt(plan.primaryOffset()).putInt(plan.transmittanceOffset())
                .putInt(plan.emissionOffset()).putInt(plan.sourceOffset())
                .putFloat(extinction.red()).putFloat(extinction.green()).putFloat(extinction.blue())
                .putFloat(frame.mediumTransport().maxDistance())
                .putFloat(scattering.red()).putFloat(scattering.green()).putFloat(scattering.blue()).putFloat(medium.anisotropy())
                .putFloat(emission.red()).putFloat(emission.green()).putFloat(emission.blue())
                .putFloat(frame.mediumTransport().transmittanceCutoff())
                .putFloat(environment.skyRed()*environment.ambientIntensity())
                .putFloat(environment.skyGreen()*environment.ambientIntensity())
                .putFloat(environment.skyBlue()*environment.ambientIntensity()).putFloat(0).array();
    }
}
