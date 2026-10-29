package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Complete final outputs plus budgeted, GPU-owned secondary scattering continuations. */
public final class CudaMultipleScatteringAbi {
    public static final int MAGIC=0x524d5331,VERSION=1,HEADER_BYTES=192,STATE_BYTES=144;
    public static final int PRIMARY_BYTES=208,COLOR_BYTES=16,SOURCE_BYTES=16;
    public static final int STATISTICS_OFFSET=112,STATISTICS_BYTES=16;
    public static final int ACTIVE=0,FAILED=4,COMPLETED=8,COLLISIONS=12;
    public static final long ALLOCATION_ALLOWANCE=65_536,RESIDENT_CAP=512L*1024*1024;
    private CudaMultipleScatteringAbi() { }

    public record Plan(int pixels,int samples,int steps,int tilePixels,int primaryOffset,
            int transmittanceOffset,int emissionOffset,int sourceOffset,int stateOffset,long bytes) {
        public int paths(){return Math.multiplyExact(pixels,samples);}
        public int secondaryPaths(int pixels){return Math.multiplyExact(Math.multiplyExact(pixels,samples),steps);}
    }
    public static Plan plan(int width,int height,int samples,int steps){return plan(width,height,samples,steps,RESIDENT_CAP);}
    public static Plan plan(int width,int height,int samples,int steps,long limit) {
        if(width<1||height<1||samples<1||samples>8||steps<1||steps>64)
            throw new IllegalArgumentException("positive extent, 1..8 spatial samples and 1..64 intervals required");
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height));
        int paths=Math.multiplyExact(pixels,samples);
        int attenuation=Math.toIntExact(HEADER_BYTES+Math.multiplyExact((long)paths,PRIMARY_BYTES));
        int emission=Math.toIntExact(attenuation+Math.multiplyExact((long)paths,COLOR_BYTES));
        int source=Math.toIntExact(emission+Math.multiplyExact((long)paths,COLOR_BYTES));
        long available=Math.min(limit,RESIDENT_CAP)-ALLOCATION_ALLOWANCE-source;
        long perPixel=Math.multiplyExact((long)samples*steps,STATE_BYTES+SOURCE_BYTES);
        int tilePixels=Math.toIntExact(Math.min(pixels,available/perPixel));
        if(tilePixels<1)throw new IllegalArgumentException("complete multiple-scattering frame exceeds the CUDA resident cap");
        int state=Math.toIntExact(source+Math.multiplyExact((long)tilePixels*samples*steps,SOURCE_BYTES));
        long bytes=Math.addExact(state,Math.multiplyExact((long)tilePixels*samples*steps,STATE_BYTES));
        return new Plan(pixels,samples,steps,tilePixels,HEADER_BYTES,attenuation,emission,source,state,bytes);
    }
    public static byte[] header(Plan plan,RenderFrameRequest frame,int firstPixel,int pixels) {
        if(firstPixel<0||pixels<1||pixels>plan.tilePixels()||(long)firstPixel+pixels>plan.pixels()
                ||frame.antiAliasing().samplesPerPixel()!=plan.samples()||frame.mediumTransport().scatteringSteps()!=plan.steps())
            throw new IllegalArgumentException("invalid multiple-scattering tile or frame budgets");
        var medium=frame.environment().cameraMedium();var budget=frame.mediumTransport();
        if(!medium.densityField().isUniform()||medium.indexOfRefraction()!=1||budget.scatteringBounces()<1)
            throw new IllegalArgumentException("multiple scattering requires uniform IOR-one transport with additional bounces");
        var sigma=medium.extinction().multiply(medium.density());var scattering=medium.scattering().multiply(medium.density());
        var emission=medium.emission().multiply(medium.density());var environment=frame.environment();
        var result=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.nativeOrder());
        result.putInt(MAGIC).putInt(VERSION).putInt(pixels*plan.samples()).putInt(plan.steps())
                .putInt(firstPixel).putInt(pixels).putInt(plan.samples()).putInt(budget.scatteringBounces())
                .putInt(plan.primaryOffset()).putInt(plan.transmittanceOffset()).putInt(plan.emissionOffset()).putInt(plan.sourceOffset())
                .putFloat(sigma.red()).putFloat(sigma.green()).putFloat(sigma.blue()).putFloat(budget.maxDistance())
                .putFloat(scattering.red()).putFloat(scattering.green()).putFloat(scattering.blue()).putFloat(medium.anisotropy())
                .putFloat(emission.red()).putFloat(emission.green()).putFloat(emission.blue()).putFloat(budget.transmittanceCutoff())
                // The original multi-scattering estimator replaces the direct ambient approximation.
                .putFloat(0).putFloat(0).putFloat(0).putInt(plan.stateOffset());
        result.putInt(128,STATE_BYTES).putInt(132,(int)frame.sequence()).putInt(136,budget.maxEvents())
                .putInt(140,plan.secondaryPaths(pixels));
        result.putFloat(144,environment.skyRed()).putFloat(148,environment.skyGreen()).putFloat(152,environment.skyBlue())
                .putFloat(156,Math.max(environment.ambientIntensity(),.05f));
        return result.array();
    }
}
