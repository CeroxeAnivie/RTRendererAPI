package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.CameraRayMath;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;

/** Pure CPU spatial index planner for analytic particle traversal. */
final class RtParticleTilePlanner {
    static final int COLUMNS = 32;
    static final int ROWS = 18;
    static final int TILE_COUNT = COLUMNS * ROWS;
    static final int MAX_REFERENCES = 16_384;

    private RtParticleTilePlanner() {
    }

    static RtRayTracingPipeline.ParticleTileIndex build(
            DynamicRenderScene scene,
            RendererFrameState frameState,
            int particleCount
    ) {
        if (particleCount <= 0) {
            return RtRayTracingPipeline.ParticleTileIndex.empty();
        }
        if (!frameState.valid()) {
            return RtRayTracingPipeline.ParticleTileIndex.fullScan(
                    RtRayTracingPipeline.ParticleTileFallback.INVALID_FRAME
            );
        }
        CameraRayMath.RayScale scale = CameraRayMath.rayScale(
                frameState,
                frameState.targetWidth(),
                frameState.targetHeight()
        );
        int[][] particleBounds = new int[particleCount][4];
        int[] counts = new int[TILE_COUNT];
        long referenceCount = 0L;
        for (int particleIndex = 0; particleIndex < particleCount; particleIndex++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(particleIndex);
            double rx = particle.x() - frameState.cameraX();
            double ry = particle.y() - frameState.cameraY();
            double rz = particle.z() - frameState.cameraZ();
            double depth = rx * frameState.cameraForwardX()
                    + ry * frameState.cameraForwardY()
                    + rz * frameState.cameraForwardZ();
            // A view-axis-rotated square needs its half diagonal as the conservative bound.
            double radius = particle.size() * 0.5 * Math.sqrt(2.0);
            if (depth <= radius + 0.001) {
                return RtRayTracingPipeline.ParticleTileIndex.fullScan(
                        RtRayTracingPipeline.ParticleTileFallback.NEAR_PLANE_INTERSECTION
                );
            }
            double centerX = (rx * frameState.cameraRightX()
                    + ry * frameState.cameraRightY()
                    + rz * frameState.cameraRightZ()) / (depth * scale.horizontalTan());
            double centerY = (rx * frameState.cameraUpX()
                    + ry * frameState.cameraUpY()
                    + rz * frameState.cameraUpZ()) / (depth * scale.verticalTan());
            double radiusX = radius / ((depth - radius) * scale.horizontalTan());
            double radiusY = radius / ((depth - radius) * scale.verticalTan());
            int minX = tileCoordinate(centerX - radiusX, COLUMNS);
            int maxX = tileCoordinate(centerX + radiusX, COLUMNS);
            int minY = tileCoordinate(-centerY - radiusY, ROWS);
            int maxY = tileCoordinate(-centerY + radiusY, ROWS);
            if (maxX < 0 || minX >= COLUMNS || maxY < 0 || minY >= ROWS) {
                particleBounds[particleIndex] = new int[]{0, -1, 0, -1};
                continue;
            }
            minX = Math.max(0, minX);
            maxX = Math.min(COLUMNS - 1, maxX);
            minY = Math.max(0, minY);
            maxY = Math.min(ROWS - 1, maxY);
            particleBounds[particleIndex] = new int[]{minX, maxX, minY, maxY};
            referenceCount += (long) (maxX - minX + 1) * (maxY - minY + 1);
            if (referenceCount > MAX_REFERENCES) {
                return RtRayTracingPipeline.ParticleTileIndex.fullScan(
                        RtRayTracingPipeline.ParticleTileFallback.REFERENCE_CAPACITY
                );
            }
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    counts[y * COLUMNS + x]++;
                }
            }
        }
        int[] offsets = new int[TILE_COUNT];
        int cursor = 0;
        for (int tile = 0; tile < TILE_COUNT; tile++) {
            offsets[tile] = cursor;
            cursor += counts[tile];
        }
        int[] tileCursors = offsets.clone();
        int[] references = new int[cursor];
        for (int particleIndex = 0; particleIndex < particleCount; particleIndex++) {
            int[] bounds = particleBounds[particleIndex];
            for (int y = bounds[2]; y <= bounds[3]; y++) {
                for (int x = bounds[0]; x <= bounds[1]; x++) {
                    int tile = y * COLUMNS + x;
                    references[tileCursors[tile]++] = particleIndex;
                }
            }
        }
        return new RtRayTracingPipeline.ParticleTileIndex(
                offsets,
                counts,
                references,
                RtRayTracingPipeline.ParticleTileFallback.NONE
        );
    }

    static int tileCoordinate(double ndc, int tileCount) {
        return (int) Math.floor((ndc * 0.5 + 0.5) * tileCount);
    }

    interface Metrics {
        int[] counts();
        int referenceCount();
        RtRayTracingPipeline.ParticleTileFallback fallback();

        default boolean fallbackToFullScan() {
            return fallback() != RtRayTracingPipeline.ParticleTileFallback.NONE;
        }
    }

    record UploadIndex(
            int[] offsets,
            int[] counts,
            int[] references,
            int referenceCount,
            RtRayTracingPipeline.ParticleTileFallback fallback
    ) implements Metrics {
        UploadIndex {
            if (offsets == null || counts == null || references == null || fallback == null) {
                throw new NullPointerException("particle tile upload index fields must not be null");
            }
            if (referenceCount < 0 || referenceCount > references.length) {
                throw new IllegalArgumentException("particle reference count exceeds scratch capacity");
            }
        }

        static UploadIndex fromPublic(RtRayTracingPipeline.ParticleTileIndex index) {
            return new UploadIndex(
                    index.offsets(),
                    index.counts(),
                    index.references(),
                    index.referenceCount(),
                    index.fallback()
            );
        }
    }

    /** Single-owner reusable workspace used synchronously by one pipeline dispatch. */
    static final class Scratch {
        private int[] particleBounds = new int[0];
        private final int[] counts = new int[TILE_COUNT];
        private final int[] offsets = new int[TILE_COUNT];
        private final int[] cursors = new int[TILE_COUNT];
        private final int[] references = new int[MAX_REFERENCES];

        UploadIndex build(DynamicRenderScene scene, RendererFrameState frameState, int particleCount) {
            java.util.Arrays.fill(counts, 0);
            java.util.Arrays.fill(offsets, 0);
            if (particleCount <= 0) {
                return publication(0, RtRayTracingPipeline.ParticleTileFallback.NONE);
            }
            if (!frameState.valid()) {
                return publication(0, RtRayTracingPipeline.ParticleTileFallback.INVALID_FRAME);
            }
            ensureParticleCapacity(particleCount);
            CameraRayMath.RayScale scale = CameraRayMath.rayScale(
                    frameState,
                    frameState.targetWidth(),
                    frameState.targetHeight()
            );
            int referenceCount = 0;
            for (int particleIndex = 0; particleIndex < particleCount; particleIndex++) {
                DynamicRenderScene.BillboardParticle particle = scene.particles().get(particleIndex);
                double rx = particle.x() - frameState.cameraX();
                double ry = particle.y() - frameState.cameraY();
                double rz = particle.z() - frameState.cameraZ();
                double depth = rx * frameState.cameraForwardX()
                        + ry * frameState.cameraForwardY()
                        + rz * frameState.cameraForwardZ();
                double radius = particle.size() * 0.5 * Math.sqrt(2.0);
                if (depth <= radius + 0.001) {
                    return fallback(RtRayTracingPipeline.ParticleTileFallback.NEAR_PLANE_INTERSECTION);
                }
                double centerX = (rx * frameState.cameraRightX()
                        + ry * frameState.cameraRightY()
                        + rz * frameState.cameraRightZ()) / (depth * scale.horizontalTan());
                double centerY = (rx * frameState.cameraUpX()
                        + ry * frameState.cameraUpY()
                        + rz * frameState.cameraUpZ()) / (depth * scale.verticalTan());
                double radiusX = radius / ((depth - radius) * scale.horizontalTan());
                double radiusY = radius / ((depth - radius) * scale.verticalTan());
                int minX = tileCoordinate(centerX - radiusX, COLUMNS);
                int maxX = tileCoordinate(centerX + radiusX, COLUMNS);
                int minY = tileCoordinate(-centerY - radiusY, ROWS);
                int maxY = tileCoordinate(-centerY + radiusY, ROWS);
                int bounds = particleIndex * 4;
                if (maxX < 0 || minX >= COLUMNS || maxY < 0 || minY >= ROWS) {
                    particleBounds[bounds] = 0;
                    particleBounds[bounds + 1] = -1;
                    particleBounds[bounds + 2] = 0;
                    particleBounds[bounds + 3] = -1;
                    continue;
                }
                minX = Math.max(0, minX);
                maxX = Math.min(COLUMNS - 1, maxX);
                minY = Math.max(0, minY);
                maxY = Math.min(ROWS - 1, maxY);
                particleBounds[bounds] = minX;
                particleBounds[bounds + 1] = maxX;
                particleBounds[bounds + 2] = minY;
                particleBounds[bounds + 3] = maxY;
                referenceCount = Math.addExact(
                        referenceCount,
                        (maxX - minX + 1) * (maxY - minY + 1)
                );
                if (referenceCount > MAX_REFERENCES) {
                    return fallback(RtRayTracingPipeline.ParticleTileFallback.REFERENCE_CAPACITY);
                }
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        counts[y * COLUMNS + x]++;
                    }
                }
            }
            int cursor = 0;
            for (int tile = 0; tile < TILE_COUNT; tile++) {
                offsets[tile] = cursor;
                cursor += counts[tile];
                cursors[tile] = offsets[tile];
            }
            if (cursor != referenceCount) {
                throw new IllegalStateException("particle tile reference count changed during publication");
            }
            for (int particleIndex = 0; particleIndex < particleCount; particleIndex++) {
                int bounds = particleIndex * 4;
                for (int y = particleBounds[bounds + 2]; y <= particleBounds[bounds + 3]; y++) {
                    for (int x = particleBounds[bounds]; x <= particleBounds[bounds + 1]; x++) {
                        int tile = y * COLUMNS + x;
                        references[cursors[tile]++] = particleIndex;
                    }
                }
            }
            return publication(referenceCount, RtRayTracingPipeline.ParticleTileFallback.NONE);
        }

        private UploadIndex fallback(RtRayTracingPipeline.ParticleTileFallback fallback) {
            java.util.Arrays.fill(counts, 0);
            java.util.Arrays.fill(offsets, 0);
            return publication(0, fallback);
        }

        private UploadIndex publication(
                int referenceCount,
                RtRayTracingPipeline.ParticleTileFallback fallback
        ) {
            return new UploadIndex(offsets, counts, references, referenceCount, fallback);
        }

        private void ensureParticleCapacity(int particleCount) {
            int required = Math.multiplyExact(particleCount, 4);
            if (required <= particleBounds.length) {
                return;
            }
            int capacity = Math.max(16, particleBounds.length);
            while (capacity < required) {
                capacity = Math.multiplyExact(capacity, 2);
            }
            particleBounds = new int[capacity];
        }
    }
}
