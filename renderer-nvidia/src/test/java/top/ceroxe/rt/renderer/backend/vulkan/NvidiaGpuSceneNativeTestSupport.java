package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.HistoryResetReason;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;

/** Shared real-GPU fixture for NVIDIA feature execution gates. */
final class NvidiaGpuSceneNativeTestSupport {
    static final int OUTPUT_WIDTH = 640;
    static final int OUTPUT_HEIGHT = 360;
    private static final long TIMEOUT_NANOS = 20_000_000_000L;

    private NvidiaGpuSceneNativeTestSupport() {
    }

    static SceneTransaction scene() {
        MaterialAsset material = MaterialAsset.builder(1L)
                .baseColorRgba8(0xFF40A0FF)
                .roughness(0.65F)
                .doubleSided(true)
                .build();
        MeshAsset quad = MeshAsset.builder(
                2L,
                new float[]{-2.5F, -1.5F, 0.0F, 2.5F, -1.5F, 0.0F,
                        2.5F, 1.5F, 0.0F, -2.5F, 1.5F, 0.0F},
                new int[]{0, 1, 2, 0, 2, 3},
                new long[]{material.id(), material.id()}
        ).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).build();
        SceneInstance instance = SceneInstance.builder(3L, quad.id()).mobility(Mobility.DYNAMIC).build();
        return SceneTransaction.builder(0L)
                .resetScene()
                .upsert(material)
                .upsert(quad)
                .upsert(instance)
                .build();
    }

    static SceneTransaction moveInstance(long revision, float translateX) {
        SceneInstance moved = SceneInstance.builder(3L, 2L)
                .transform(new AffineTransform(new float[]{
                        1.0F, 0.0F, 0.0F, translateX,
                        0.0F, 1.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, 1.0F, 0.0F
                }))
                .mobility(Mobility.DYNAMIC)
                .build();
        return SceneTransaction.builder(revision).upsert(moved).build();
    }

    static RenderFrameRequest frame() {
        return frame(0L);
    }

    static RenderFrameRequest frame(long sequence) {
        return frame(sequence, OUTPUT_WIDTH, OUTPUT_HEIGHT, camera(0.0, OUTPUT_WIDTH, OUTPUT_HEIGHT));
    }

    static CameraState camera(double x, int width, int height) {
        return camera(x, 0.0, width, height);
    }

    static CameraState camera(double x, double yawRadians, int width, int height) {
        float sin = (float) Math.sin(yawRadians);
        float cos = (float) Math.cos(yawRadians);
        return CameraState.explicitBasis(x, 0.0, 4.0)
                .forward(sin, 0.0F, -cos)
                .right(cos, 0.0F, sin)
                .up(0.0F, 1.0F, 0.0F)
                .projectionTangents(0.5625F * width / height, 0.5625F)
                .build();
    }

    static RenderFrameRequest frame(
            long sequence,
            int width,
            int height,
            CameraState camera,
            HistoryResetReason... resets
    ) {
        EnvironmentState environment = EnvironmentState.builder()
                .skyRadiance(0.2F, 0.3F, 0.5F)
                .ambientIntensity(1.0F)
                .sunDirection(-0.30304575F, -0.80812204F, -0.5050763F)
                .sunRadiance(1.0F, 0.9F, 0.75F)
                .sunIntensity(2.0F)
                .build();
        RenderFrameRequest.Builder builder = RenderFrameRequest.builder(sequence, width, height, camera)
                .minimumSceneRevision(0L)
                .environment(environment)
                .depthProjection(DepthProjectionState.vulkanPerspective(0.1F, 1_000.0F));
        for (HistoryResetReason reset : resets) builder.resetTemporalHistory(reset);
        return builder.build();
    }

    static void awaitFrameAdmission(VulkanRendererHost renderer, RenderFrameRequest frame, String featureName)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (true) {
            try {
                renderer.submit(frame);
                return;
            } catch (SubmissionRejectedException converging) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(
                            "GPUScene did not converge before " + featureName + " frame admission", converging
                    );
                }
                renderer.diagnostics();
                Thread.sleep(1L);
            }
        }
    }

    static VulkanGpuSceneRenderingSession.DiagnosticFrame awaitCompletedFrame(
            VulkanGpuSceneRenderingSession session,
            String featureName
    ) throws InterruptedException {
        return awaitCompletedFrame(session, -1L, featureName);
    }

    static DiagnosticFramePair awaitCompletedFramePair(
            VulkanGpuSceneRenderingSession session,
            long expectedSequence,
            String featureName
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            VulkanGpuSceneRenderingSession.DiagnosticFrame output = session.captureLatestForAcceptance();
            if (output != null) {
                try {
                    if (output.sequence() > expectedSequence) {
                        throw new AssertionError(featureName + " completed past expected sequence " + expectedSequence);
                    }
                    if (output.sequence() == expectedSequence) {
                        VulkanGpuSceneRenderingSession.DiagnosticFrame trace =
                                session.captureLatestTraceForAcceptance();
                        return new DiagnosticFramePair(output, trace);
                    }
                } finally {
                    session.discardCompletedForAcceptance();
                }
            }
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(featureName + " frame " + expectedSequence
                + " did not complete before the native gate timeout");
    }

    static VulkanGpuSceneRenderingSession.DiagnosticFrame awaitCompletedFrame(
            VulkanGpuSceneRenderingSession session,
            long expectedSequence,
            String featureName
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            VulkanGpuSceneRenderingSession.DiagnosticFrame frame = session.captureLatestForAcceptance();
            if (frame != null && (expectedSequence < 0L || frame.sequence() == expectedSequence)) return frame;
            if (frame != null && expectedSequence >= 0L && frame.sequence() > expectedSequence) {
                throw new AssertionError(featureName + " completed past expected sequence " + expectedSequence);
            }
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(featureName + " frame " + expectedSequence
                + " did not complete before the native gate timeout");
    }

    static long nonBlackPixels(byte[] rgba8) {
        long result = 0L;
        for (int offset = 0; offset < rgba8.length; offset += 4) {
            if (rgba8[offset] != 0 || rgba8[offset + 1] != 0 || rgba8[offset + 2] != 0) result++;
        }
        return result;
    }

    static double meanAbsoluteRgbDelta(byte[] left, byte[] right) {
        if (left.length != right.length || left.length % 4 != 0) {
            throw new IllegalArgumentException("RGBA8 images must have equal complete pixel payloads");
        }
        long delta = 0L;
        for (int offset = 0; offset < left.length; offset += 4) {
            for (int channel = 0; channel < 3; channel++) {
                delta += Math.abs(Byte.toUnsignedInt(left[offset + channel])
                        - Byte.toUnsignedInt(right[offset + channel]));
            }
        }
        return delta / (double) (left.length / 4 * 3L);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    record DiagnosticFramePair(
            VulkanGpuSceneRenderingSession.DiagnosticFrame output,
            VulkanGpuSceneRenderingSession.DiagnosticFrame trace
    ) {
        DiagnosticFramePair {
            if (output == null || trace == null
                    || output.sequence() != trace.sequence()
                    || output.sceneRevision() != trace.sceneRevision()) {
                throw new IllegalArgumentException("diagnostic output and trace identities do not match");
            }
        }
    }
}
