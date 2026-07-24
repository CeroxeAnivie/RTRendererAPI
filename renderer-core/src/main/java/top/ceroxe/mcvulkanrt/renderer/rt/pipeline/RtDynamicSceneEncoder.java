package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Owns dynamic-scene SSBO encoding order and delegates each independent ABI lane to a focused writer.
 *
 * <p>The layout is injected from the pipeline declaration so the encoder cannot silently redefine
 * shader-visible offsets. This mirrors a render-graph pass boundary: immutable scene input enters,
 * a fully encoded persistent resource leaves, and per-frame particle planning remains explicitly owned.</p>
 */
final class RtDynamicSceneEncoder {
    private RtDynamicSceneEncoder() {
    }

    static void encode(
            DynamicRenderScene scene,
            RendererFrameState frameState,
            byte[] bytes,
            Layout layout,
            RtParticleTilePlanner.Scratch particleTileScratch,
            RtParticleTileTelemetry particleTileTelemetry
    ) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(frameState, "frameState");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(layout, "layout");
        if (bytes.length != layout.bufferBytes()) {
            throw new IllegalArgumentException("dynamic scene staging size does not match the shader ABI");
        }
        if ((particleTileScratch == null) != (particleTileTelemetry == null)) {
            throw new IllegalArgumentException("particle tile scratch and telemetry must be supplied together");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean active = scene.hasSceneUpdate();
        int celestialCount = active ? Math.min(scene.celestialBodies().size(), layout.maxCelestialBodies()) : 0;
        int primitiveCount = active ? RtDynamicSceneAnalyticPrimitiveWriter.count(scene, layout.maxPrimitives()) : 0;
        int particleCount = active ? Math.min(scene.particles().size(), layout.maxParticles()) : 0;
        int beamCount = active ? Math.min(scene.beams().size(), layout.maxBeams()) : 0;
        int localLightCount = active ? RtDynamicSceneBeamLightWriter.localLightCount(scene, layout.maxLights()) : 0;
        int weatherCount = active ? Math.min(scene.weatherColumns().size(), layout.maxWeatherColumns()) : 0;
        int decalCount = active ? Math.min(scene.blockDecals().size(), layout.maxDecals()) : 0;

        RtDynamicSceneHeaderWriter.write(buffer, scene, active, celestialCount, primitiveCount, particleCount,
                beamCount, localLightCount);
        RtDynamicSceneCelestialWriter.write(buffer, scene, celestialCount,
                layout.celestialDirectionRecord(), layout.celestialColorRecord());
        RtDynamicSceneAnalyticPrimitiveWriter.write(buffer, scene, primitiveCount,
                layout.primitivePositionRecord(), layout.primitiveColorRecord());
        RtDynamicSceneParticleWriter.write(buffer, scene, particleCount,
                layout.particlePositionRecord(), layout.particleColorRecord(), layout.particleRotationRecord(),
                layout.particleUvRecord(), layout.particleLifecycleRecord());
        RtDynamicSceneBeamLightWriter.writeBeams(buffer, scene, beamCount,
                layout.beamStartRecord(), layout.beamEndRecord(), layout.beamColorRecord());
        RtDynamicSceneBeamLightWriter.writeLocalLights(buffer, scene, localLightCount,
                layout.localLightPositionRecord(), layout.localLightColorRecord());
        RtDynamicSceneEnvironmentWriter.write(buffer, scene, active, weatherCount,
                layout.environmentRecord(), layout.weatherBoundsRecord(), layout.weatherDataRecord(),
                layout.weatherMetaRecord(), layout.fogKnownFlag(), layout.cloudKnownFlag(), layout.skyVisibleFlag());
        RtDynamicSceneDecalWriter.write(buffer, scene, decalCount, layout.decalTableSlots(),
                layout.decalInfoRecord(), layout.decalBoundsMinRecord(), layout.decalBoundsMaxRecord(),
                layout.decalRecord(), layout.decalOffsetRecord());

        RtParticleTilePlanner.UploadIndex tiles = particleTileScratch == null
                ? RtParticleTilePlanner.UploadIndex.fromPublic(
                        RtParticleTilePlanner.build(scene, frameState, particleCount))
                : particleTileScratch.build(scene, frameState, particleCount);
        if (particleTileTelemetry != null) {
            particleTileTelemetry.record(particleCount, tiles);
        }
        RtDynamicSceneParticleTileWriter.write(buffer, tiles, layout.particleTileColumns(), layout.particleTileRows(),
                layout.particleTileCount(), layout.maxParticleTileReferences(), layout.particleTileInfoRecord(),
                layout.particleTileRangesRecord(), layout.particleTileIndicesRecord());
    }

    record Layout(
            int bufferBytes,
            int maxCelestialBodies,
            int maxPrimitives,
            int maxParticles,
            int maxBeams,
            int maxLights,
            int maxWeatherColumns,
            int maxDecals,
            int decalTableSlots,
            int particleTileColumns,
            int particleTileRows,
            int particleTileCount,
            int maxParticleTileReferences,
            int fogKnownFlag,
            int cloudKnownFlag,
            int skyVisibleFlag,
            int celestialDirectionRecord,
            int celestialColorRecord,
            int primitivePositionRecord,
            int primitiveColorRecord,
            int particlePositionRecord,
            int particleColorRecord,
            int particleRotationRecord,
            int particleUvRecord,
            int particleLifecycleRecord,
            int beamStartRecord,
            int beamEndRecord,
            int beamColorRecord,
            int localLightPositionRecord,
            int localLightColorRecord,
            int environmentRecord,
            int weatherBoundsRecord,
            int weatherDataRecord,
            int weatherMetaRecord,
            int decalInfoRecord,
            int decalBoundsMinRecord,
            int decalBoundsMaxRecord,
            int decalRecord,
            int decalOffsetRecord,
            int particleTileInfoRecord,
            int particleTileRangesRecord,
            int particleTileIndicesRecord
    ) {
        Layout {
            if (bufferBytes <= 0 || maxCelestialBodies < 0 || maxPrimitives < 0 || maxParticles < 0
                    || maxBeams < 0 || maxLights < 0 || maxWeatherColumns < 0 || maxDecals < 0
                    || decalTableSlots <= 0 || Integer.bitCount(decalTableSlots) != 1
                    || particleTileColumns <= 0 || particleTileRows <= 0
                    || particleTileCount != particleTileColumns * particleTileRows || maxParticleTileReferences < 0) {
                throw new IllegalArgumentException("dynamic scene layout capacities are invalid");
            }
        }
    }
}
