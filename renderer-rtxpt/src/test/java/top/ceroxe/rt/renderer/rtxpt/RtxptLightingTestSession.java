package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** One real window for compatible lighting cases; all identities remain monotonic. */
final class RtxptLightingTestSession implements AutoCloseable {
    final RtxptRenderer renderer;
    private long nextSequence, lastRevision, lastGeneration;

    RtxptLightingTestSession() {
        renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);
    }

    Case begin(String name) {
        renderer.awaitIdle();
        renderer.nativeLightingPlan(RtxptNativeLightingMatrixNativeSelfTest.directPlan(
            RtxptNativeLightingMatrixNativeSelfTest.SUN_OFF));
        renderer.lightingWorkStatisticsEnabled(false);
        renderer.lightingDiagnosticDecompositionEnabled(false);
        renderer.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);
        // Null lookup retains the previous table, so explicitly restore its defaults.
        float[] lookup=new float[512];
        for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++) {
            int i=(sky*16+local)*2;lookup[i]=local/15f;lookup[i+1]=sky/15f;
        }
        renderer.publishLightingAttributes(new NativeLightingPublication(++lastGeneration,
            List.of(),List.of(),lookup,ByteBuffer.allocate(0)));
        System.out.println("RTXPT_SHARED_WINDOW CASE_BEGIN "+name);
        return new Case(nextSequence,lastRevision,lastGeneration,
            renderer.nativeLightingExecution().lightingFrames());
    }

    final class Case {
        final RtxptRenderer renderer=RtxptLightingTestSession.this.renderer;
        private final long sequenceBase,revisionBase,generationBase,framesBefore;
        Case(long sequenceBase,long revisionBase,long generationBase,long framesBefore) {
            this.sequenceBase=sequenceBase;this.revisionBase=revisionBase;
            this.generationBase=generationBase;this.framesBefore=framesBefore;
        }
        private long sequence(long value) {
            long mapped=Math.addExact(sequenceBase,value);
            nextSequence=Math.max(nextSequence,Math.addExact(mapped,1));return mapped;
        }
        private long revision(long value) {
            long mapped=Math.addExact(revisionBase,value);
            lastRevision=Math.max(lastRevision,mapped);return mapped;
        }
        private long generation(long value) {
            long mapped=Math.addExact(generationBase,value);
            lastGeneration=Math.max(lastGeneration,mapped);return mapped;
        }
        SceneTransaction.Builder scene(long value) {return SceneTransaction.builder(revision(value));}
        RenderFrameRequest frame(long sequence,long revision) {
            return RtxptNativeLightingMatrixNativeSelfTest.frame(sequence(sequence),revision(revision));
        }
        RenderFrameRequest frame(long sequence,long revision,CameraState camera) {
            return RtxptNativeLightingMatrixNativeSelfTest.frame(sequence(sequence),revision(revision),camera);
        }
        RenderFrameRequest.Builder request(long sequence,long revision,int width,int height,CameraState camera) {
            return RenderFrameRequest.builder(sequence(sequence),width,height,camera)
                .minimumSceneRevision(revision(revision));
        }
        void publication(long generation,NativeLightingPublication.Instance... instances) {
            publish(new NativeLightingPublication(generation,List.of(instances),List.of(),null,null));
        }
        void publish(NativeLightingPublication value) {
            renderer.publishLightingAttributes(new NativeLightingPublication(generation(value.generation()),
                value.instances(),value.baselines(),value.baselineLookup(),value.applicationData(),value.hostResponse()));
        }
        void patch(NativeLightingApplicationPatch value) {
            renderer.patchLightingApplicationData(new NativeLightingApplicationPatch(
                generation(value.generation()),value.offset(),value.data()));
        }
        void packet(long sequence,long revision,FrameClock clock) {
            RtxptNativeLightingMatrixNativeSelfTest.packet(renderer,sequence(sequence),revision(revision),clock);
        }
        long lightingFrames() {return renderer.nativeLightingExecution().lightingFrames()-framesBefore;}
    }
    @Override public void close() {renderer.close();}
}
