package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.ExactProjectionState;
import top.ceroxe.rt.renderer.api.DistanceFogState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.LightmapState;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Encodes immutable public frame facts into the exact GPUScene frame ABI.
 */
final class VulkanFrameUniformPacker {
    static final int BYTE_COUNT = VulkanGpuSceneAbi.FRAME_UNIFORM_WORDS * Integer.BYTES;

    private VulkanFrameUniformPacker() {
    }

    static byte[] pack(
            RenderFrameRequest request,
            int lightSlotUpperBound,
            long activeSceneRevision,
            TemporalHistoryTracker.PreparedFrame temporalFrame,
            TemporalRenderingOptions temporalOptions,
            boolean denoisingActive
    ) {
        return pack(
                request,
                VulkanFrameExtents.identity(request.width(), request.height()),
                lightSlotUpperBound,
                activeSceneRevision,
                temporalFrame,
                temporalOptions,
                denoisingActive,
                false
        );
    }

    static byte[] pack(
            RenderFrameRequest request,
            VulkanFrameExtents extents,
            int lightSlotUpperBound,
            long activeSceneRevision,
            TemporalHistoryTracker.PreparedFrame temporalFrame,
            TemporalRenderingOptions temporalOptions,
            boolean denoisingActive
    ) {
        return pack(
                request, extents, lightSlotUpperBound, activeSceneRevision, temporalFrame,
                temporalOptions, denoisingActive, false
        );
    }

    static byte[] pack(
            RenderFrameRequest request,
            VulkanFrameExtents extents,
            int lightSlotUpperBound,
            long activeSceneRevision,
            TemporalHistoryTracker.PreparedFrame temporalFrame,
            TemporalRenderingOptions temporalOptions,
            boolean denoisingActive,
            boolean reconstructionActive
    ) {
        return pack(
                request, extents, lightSlotUpperBound, activeSceneRevision, temporalFrame,
                temporalOptions, denoisingActive, reconstructionActive,
                Objects.requireNonNull(temporalOptions, "temporalOptions").enabled()
        );
    }

    static byte[] pack(
            RenderFrameRequest request,
            VulkanFrameExtents extents,
            int lightSlotUpperBound,
            long activeSceneRevision,
            TemporalHistoryTracker.PreparedFrame temporalFrame,
            TemporalRenderingOptions temporalOptions,
            boolean denoisingActive,
            boolean reconstructionActive,
            boolean jitterActive
    ) {
        RenderFrameRequest frame = Objects.requireNonNull(request, "request");
        VulkanFrameExtents frameExtents = Objects.requireNonNull(extents, "extents");
        if (frameExtents.outputWidth() != frame.width() || frameExtents.outputHeight() != frame.height()) {
            throw new IllegalArgumentException("frame output extent must match the public frame request");
        }
        TemporalHistoryTracker.PreparedFrame temporal = Objects.requireNonNull(
                temporalFrame, "temporalFrame"
        );
        TemporalRenderingOptions temporalPolicy = Objects.requireNonNull(
                temporalOptions, "temporalOptions"
        );
        if (!temporal.request().equals(frame)) {
            throw new IllegalArgumentException("temporal state belongs to a different frame request");
        }
        if (temporal.sceneRevision() != activeSceneRevision) {
            throw new IllegalArgumentException("temporal state belongs to a different scene revision");
        }
        if (temporal.historyValid() && !temporal.provenanceTracked()) {
            throw new IllegalArgumentException(
                    "valid temporal history must come from a tracked provenance source"
            );
        }
        if (lightSlotUpperBound < 0) {
            throw new IllegalArgumentException("lightSlotUpperBound must not be negative");
        }
        if (activeSceneRevision < frame.minimumSceneRevision()) {
            throw new IllegalArgumentException(
                    "active scene revision " + activeSceneRevision
                            + " does not satisfy frame minimum " + frame.minimumSceneRevision()
            );
        }

        ByteBuffer words = ByteBuffer.allocate(BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        putInt(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD, frameExtents.renderWidth());
        putInt(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD + 1, frameExtents.renderHeight());
        putLong(words, VulkanGpuSceneAbi.FRAME_SEQUENCE_WORD, frame.sequence());

        CameraState camera = frame.camera();
        putCamera(words, camera,
                VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD,
                VulkanGpuSceneAbi.FRAME_CAMERA_FORWARD_WORD,
                VulkanGpuSceneAbi.FRAME_CAMERA_RIGHT_WORD,
                VulkanGpuSceneAbi.FRAME_CAMERA_UP_WORD,
                VulkanGpuSceneAbi.FRAME_FOV_WORD);

        EnvironmentState environment = frame.environment();
        putVec3(words, VulkanGpuSceneAbi.FRAME_SKY_COLOR_WORD,
                environment.skyRed(), environment.skyGreen(), environment.skyBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_AMBIENT_INTENSITY_WORD, environment.ambientIntensity());
        putVec3(words, VulkanGpuSceneAbi.FRAME_SUN_DIRECTION_WORD,
                environment.sunDirectionX(), environment.sunDirectionY(), environment.sunDirectionZ());
        putVec3(words, VulkanGpuSceneAbi.FRAME_SUN_COLOR_WORD,
                environment.sunRed(), environment.sunGreen(), environment.sunBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_SUN_INTENSITY_WORD, environment.sunIntensity());

        EnvironmentState.Medium medium = environment.cameraMedium();
        putVec3(words, VulkanGpuSceneAbi.FRAME_MEDIUM_EXTINCTION_WORD,
                medium.extinctionRed(), medium.extinctionGreen(), medium.extinctionBlue());
        putVec3(words, VulkanGpuSceneAbi.FRAME_MEDIUM_SCATTERING_WORD,
                medium.scatteringRed(), medium.scatteringGreen(), medium.scatteringBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_MEDIUM_DENSITY_WORD, medium.density());
        putFloat(words, VulkanGpuSceneAbi.FRAME_MEDIUM_IOR_WORD, medium.indexOfRefraction());
        putInt(words, VulkanGpuSceneAbi.FRAME_LIGHT_SLOT_UPPER_BOUND_WORD, lightSlotUpperBound);
        putLong(words, VulkanGpuSceneAbi.FRAME_SCENE_REVISION_WORD, activeSceneRevision);
        DistanceFogState fog = frame.fog();
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD, fog.red());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 1, fog.green());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 2, fog.blue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 3, fog.opacity());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_SPHERICAL_START_WORD, fog.sphericalStart());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_SPHERICAL_END_WORD, fog.sphericalEnd());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_CYLINDRICAL_START_WORD, fog.cylindricalStart());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_CYLINDRICAL_END_WORD, fog.cylindricalEnd());
        putInt(words, VulkanGpuSceneAbi.FRAME_TEXTURE_MINIFICATION_MODE_WORD,
                frame.textureSampling().minificationMode().ordinal());
        putInt(words, VulkanGpuSceneAbi.FRAME_MAX_ANISOTROPY_WORD,
                frame.textureSampling().maxAnisotropy());
        putInt(words, VulkanGpuSceneAbi.FRAME_SAMPLE_COUNT_WORD,
                frame.antiAliasing().samplesPerPixel());
        java.nio.IntBuffer lightmap = frame.lightmap().texelsRgba8();
        if (lightmap.remaining() != LightmapState.ENTRY_COUNT) {
            throw new IllegalStateException("validated frame lightmap changed entry count");
        }
        for (int index = 0; lightmap.hasRemaining(); index++) {
            putInt(words, VulkanGpuSceneAbi.FRAME_LIGHTMAP_WORD + index, lightmap.get());
        }

        CameraState previousCamera = temporal.previousCamera();
        putCamera(words, previousCamera,
                VulkanGpuSceneAbi.FRAME_PREVIOUS_CAMERA_POSITION_WORD,
                VulkanGpuSceneAbi.FRAME_PREVIOUS_CAMERA_FORWARD_WORD,
                VulkanGpuSceneAbi.FRAME_PREVIOUS_CAMERA_RIGHT_WORD,
                VulkanGpuSceneAbi.FRAME_PREVIOUS_CAMERA_UP_WORD,
                VulkanGpuSceneAbi.FRAME_PREVIOUS_FOV_WORD);
        putLong(words, VulkanGpuSceneAbi.FRAME_PREVIOUS_SEQUENCE_WORD, temporal.previousSequence());
        boolean exactProjection = camera.hasExactProjection();
        /* The legacy temporal reprojection shader is FOV/basis based. Keep exact primary rays
         * correct by failing closed for that secondary history path until it has an exact inverse
         * projection contract of its own. */
        int temporalFlags = !exactProjection && temporalPolicy.enabled()
                ? VulkanGpuSceneAbi.TEMPORAL_FLAG_ENABLED : 0;
        if (!exactProjection && temporal.historyValid()) {
            temporalFlags |= VulkanGpuSceneAbi.TEMPORAL_FLAG_HISTORY_VALID;
        }
        putInt(words, VulkanGpuSceneAbi.FRAME_TEMPORAL_FLAGS_WORD, temporalFlags);
        putInt(words, VulkanGpuSceneAbi.FRAME_MAX_HISTORY_FRAMES_WORD,
                temporalPolicy.maxHistoryFrames());
        float[] currentJitter = temporalJitter(frame.sequence(), jitterActive);
        float[] previousJitter = temporalJitter(temporal.previousSequence(), jitterActive);
        putFloat(words, VulkanGpuSceneAbi.FRAME_CURRENT_JITTER_WORD, currentJitter[0]);
        putFloat(words, VulkanGpuSceneAbi.FRAME_CURRENT_JITTER_WORD + 1, currentJitter[1]);
        putFloat(words, VulkanGpuSceneAbi.FRAME_PREVIOUS_JITTER_WORD, previousJitter[0]);
        putFloat(words, VulkanGpuSceneAbi.FRAME_PREVIOUS_JITTER_WORD + 1, previousJitter[1]);
        putLong(words, VulkanGpuSceneAbi.FRAME_HISTORY_GENERATION_WORD, temporal.generation());
        int invalidationMask = 0;
        for (top.ceroxe.rt.renderer.api.HistoryInvalidationReason reason
                : temporal.invalidations()) {
            if (reason.ordinal() >= Integer.SIZE) {
                throw new IllegalStateException("history invalidation mask exceeds 32-bit ABI");
            }
            invalidationMask |= 1 << reason.ordinal();
        }
        putInt(words, VulkanGpuSceneAbi.FRAME_HISTORY_INVALIDATION_MASK_WORD, invalidationMask);
        putLong(words, VulkanGpuSceneAbi.FRAME_PREVIOUS_SCENE_REVISION_WORD,
                temporal.previousSceneRevision());
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_DELTA_WORD,
                frame.camera().x() - previousCamera.x());
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_DELTA_WORD + 2,
                frame.camera().y() - previousCamera.y());
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_DELTA_WORD + 4,
                frame.camera().z() - previousCamera.z());
        int featureFlags = denoisingActive ? VulkanGpuSceneAbi.FEATURE_FLAG_DENOISING_ACTIVE : 0;
        if (reconstructionActive && !exactProjection) {
            featureFlags |= VulkanGpuSceneAbi.FEATURE_FLAG_RECONSTRUCTION_ACTIVE;
        }
        putInt(words, VulkanGpuSceneAbi.FRAME_FEATURE_FLAGS_WORD, featureFlags);
        boolean projectionKnown = frame.depthProjection().known();
        putFloat(words, VulkanGpuSceneAbi.FRAME_RECONSTRUCTION_NEAR_PLANE_WORD,
                projectionKnown ? frame.depthProjection().nearPlane() : 0.0F);
        putFloat(words, VulkanGpuSceneAbi.FRAME_RECONSTRUCTION_FAR_PLANE_WORD,
                projectionKnown ? frame.depthProjection().farPlane() : 0.0F);
        putInt(words, VulkanGpuSceneAbi.FRAME_RECONSTRUCTION_PROJECTION_KNOWN_WORD,
                projectionKnown ? 1 : 0);
        putExactProjection(words, camera, frameExtents);
        return words.array();
    }

    private static void putExactProjection(
            ByteBuffer target,
            CameraState camera,
            VulkanFrameExtents extents
    ) {
        putInt(target, VulkanGpuSceneAbi.FRAME_PROJECTION_PATH_WORD,
                camera.hasExactProjection()
                        ? VulkanGpuSceneAbi.PROJECTION_PATH_EXACT_CLIP
                        : VulkanGpuSceneAbi.PROJECTION_PATH_BASIS_FOV);
        if (!camera.hasExactProjection()) return;

        ExactProjectionState exact = camera.exactProjection();
        if (exact.depthConvention() != ExactProjectionState.DepthConvention.ZERO_TO_ONE) {
            throw new IllegalArgumentException(
                    "current Vulkan depth attachment requires ZERO_TO_ONE exact depth convention"
            );
        }
        if (exact.viewportWidth() != extents.outputWidth()
                || exact.viewportHeight() != extents.outputHeight()) {
            throw new IllegalArgumentException(
                    "exact projection viewport must match the public frame extent"
            );
        }
        putInt(target, VulkanGpuSceneAbi.FRAME_EXACT_VIEWPORT_WIDTH_WORD, exact.viewportWidth());
        putInt(target, VulkanGpuSceneAbi.FRAME_EXACT_VIEWPORT_HEIGHT_WORD, exact.viewportHeight());
        putInt(target, VulkanGpuSceneAbi.FRAME_EXACT_DEPTH_CONVENTION_WORD, exact.depthConvention().ordinal());
        putInt(target, VulkanGpuSceneAbi.FRAME_EXACT_JITTER_CONVENTION_WORD, exact.jitterConvention().ordinal());
        putFloat(target, VulkanGpuSceneAbi.FRAME_EXACT_JITTER_WORD, exact.jitterXAsFloat());
        putFloat(target, VulkanGpuSceneAbi.FRAME_EXACT_JITTER_WORD + 1, exact.jitterYAsFloat());
        putInt(target, VulkanGpuSceneAbi.FRAME_EXACT_COORDINATE_SYSTEM_WORD, exact.coordinateSystem().ordinal());

        double[] inverse = exact.inverseClipFromView();
        for (int index = 0; index < inverse.length; index++) {
            putFloat(target, VulkanGpuSceneAbi.FRAME_EXACT_INVERSE_CLIP_FROM_VIEW_WORD + index,
                    exactFloat(inverse[index], "inverse clip matrix"));
        }
        /* Translation stays in the existing lossless double camera-position words. */
        double[] transform = exact.cameraToWorld();
        int base = VulkanGpuSceneAbi.FRAME_EXACT_CAMERA_TO_WORLD_WORD;
        int[] rotation = {0, 1, 2, 4, 5, 6, 8, 9, 10};
        for (int index = 0; index < rotation.length; index++) {
            putFloat(target, base + index, exactFloat(transform[rotation[index]], "camera rotation"));
        }
        for (int index = 9; index < 15; index++) putFloat(target, base + index, 0.0F);
        putFloat(target, base + 15, 1.0F);
    }

    private static float exactFloat(double value, String label) {
        if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException(label + " is outside the shader float ABI");
        }
        return (float) value;
    }

    private static void putCamera(
            ByteBuffer target,
            CameraState camera,
            int positionWord,
            int forwardWord,
            int rightWord,
            int upWord,
            int fovWord
    ) {
        putDouble(target, positionWord, camera.x());
        putDouble(target, positionWord + 2, camera.y());
        putDouble(target, positionWord + 4, camera.z());
        putVec3(target, forwardWord, camera.forwardX(), camera.forwardY(), camera.forwardZ());
        putVec3(target, rightWord, camera.rightX(), camera.rightY(), camera.rightZ());
        putVec3(target, upWord, camera.upX(), camera.upY(), camera.upZ());
        putFloat(target, fovWord, camera.tanHalfFovX());
        putFloat(target, fovWord + 1, camera.tanHalfFovY());
    }

    /**
     * Returns the renderer's canonical pixel-centered temporal jitter for a submitted sequence.
     *
     * <p>Both the GPU uniform ABI and Streamline's native constants consume these exact values.
     * Keeping the sequence here prevents a feature integration from introducing a second Halton
     * implementation that diverges after an otherwise harmless renderer-side change.</p>
     */
    static float[] temporalJitter(long sequence, boolean enabled) {
        if (sequence < 0L) {
            throw new IllegalArgumentException("temporal jitter sequence must not be negative");
        }
        if (!enabled) return new float[]{0.0F, 0.0F};
        int index = (int) (sequence % 1_024L) + 1;
        return new float[]{
                (float) (radicalInverse(index, 2) - 0.5D),
                (float) (radicalInverse(index, 3) - 0.5D)
        };
    }

    private static double radicalInverse(int index, int base) {
        double factor = 1.0D / base;
        double value = 0.0D;
        int remaining = index;
        while (remaining != 0) {
            value += (remaining % base) * factor;
            remaining /= base;
            factor /= base;
        }
        return value;
    }

    private static void putVec3(ByteBuffer target, int word, float x, float y, float z) {
        putFloat(target, word, x);
        putFloat(target, word + 1, y);
        putFloat(target, word + 2, z);
    }

    private static void putFloat(ByteBuffer target, int word, float value) {
        putInt(target, word, Float.floatToRawIntBits(value));
    }

    private static void putDouble(ByteBuffer target, int word, double value) {
        putLong(target, word, Double.doubleToRawLongBits(value));
    }

    private static void putLong(ByteBuffer target, int word, long value) {
        target.putLong(Math.multiplyExact(word, Integer.BYTES), value);
    }

    private static void putInt(ByteBuffer target, int word, int value) {
        target.putInt(Math.multiplyExact(word, Integer.BYTES), value);
    }
}
