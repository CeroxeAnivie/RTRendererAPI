package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RtBuildTelemetrySink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns reusable dynamic-scene encoding scratch and candidate upload-range publication. */
final class RtDynamicSceneUploadEncoder {
    private final RtDynamicSceneEncoder.Layout layout;
    private final RtParticleTilePlanner.Scratch particleTileScratch = new RtParticleTilePlanner.Scratch();
    private final RtParticleTileTelemetry particleTileTelemetry;

    RtDynamicSceneUploadEncoder(RtDynamicSceneEncoder.Layout layout, RtBuildTelemetrySink telemetry) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.particleTileTelemetry = new RtParticleTileTelemetry(telemetry);
    }

    Packet encode(DynamicRenderScene scene, RendererFrameState frameState, byte[] staging) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(frameState, "frameState");
        Objects.requireNonNull(staging, "staging");
        RtDynamicSceneEncoder.encode(
                scene,
                frameState,
                staging,
                layout,
                particleTileScratch,
                particleTileTelemetry
        );

        boolean active = scene.hasSceneUpdate();
        int celestialCount = active ? Math.min(scene.celestialBodies().size(), layout.maxCelestialBodies()) : 0;
        int primitiveCount = active
                ? RtDynamicSceneAnalyticPrimitiveWriter.count(scene, layout.maxPrimitives())
                : 0;
        int particleCount = active ? Math.min(scene.particles().size(), layout.maxParticles()) : 0;
        int beamCount = active ? Math.min(scene.beams().size(), layout.maxBeams()) : 0;
        int lightCount = active ? RtDynamicSceneBeamLightWriter.localLightCount(scene, layout.maxLights()) : 0;
        int weatherCount = active ? Math.min(scene.weatherColumns().size(), layout.maxWeatherColumns()) : 0;
        int particleTileReferenceCount = ByteBuffer.wrap(staging)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt(layout.particleTileInfoRecord() * 16 + 8);

        ArrayList<RtRayTracingPipeline.UploadRange> ranges = new ArrayList<>();
        addRecordRange(ranges, 0, layout.celestialDirectionRecord());
        addRecordRange(ranges, layout.celestialDirectionRecord(), celestialCount);
        addRecordRange(ranges, layout.celestialColorRecord(), celestialCount);
        addRecordRange(ranges, layout.primitivePositionRecord(), primitiveCount);
        addRecordRange(ranges, layout.primitiveColorRecord(), primitiveCount);
        addRecordRange(ranges, layout.particlePositionRecord(), particleCount);
        addRecordRange(ranges, layout.particleColorRecord(), particleCount);
        addRecordRange(ranges, layout.particleRotationRecord(), particleCount);
        addRecordRange(ranges, layout.particleUvRecord(), particleCount);
        addRecordRange(ranges, layout.particleLifecycleRecord(), particleCount);
        addRecordRange(ranges, layout.beamStartRecord(), beamCount);
        addRecordRange(ranges, layout.beamEndRecord(), beamCount);
        addRecordRange(ranges, layout.beamColorRecord(), beamCount);
        addRecordRange(ranges, layout.localLightPositionRecord(), lightCount);
        addRecordRange(ranges, layout.localLightColorRecord(), lightCount);
        addRecordRange(ranges, layout.environmentRecord(), 5);
        addRecordRange(ranges, layout.weatherBoundsRecord(), weatherCount);
        addRecordRange(ranges, layout.weatherDataRecord(), weatherCount);
        addRecordRange(ranges, layout.weatherMetaRecord(), weatherCount);
        addRecordRange(
                ranges,
                layout.decalInfoRecord(),
                layout.decalOffsetRecord() + layout.decalTableSlots() - layout.decalInfoRecord()
        );
        addRecordRange(ranges, layout.particleTileInfoRecord(), 1 + layout.particleTileCount());
        addRecordRange(ranges, layout.particleTileIndicesRecord(), (particleTileReferenceCount + 3) / 4);
        return new Packet(staging, RtDynamicSceneUploadPlanner.merge(ranges));
    }

    private static void addRecordRange(
            List<RtRayTracingPipeline.UploadRange> ranges,
            int firstRecord,
            int recordCount
    ) {
        if (recordCount > 0) {
            ranges.add(new RtRayTracingPipeline.UploadRange(
                    Math.multiplyExact(firstRecord, 16),
                    Math.multiplyExact(recordCount, 16)
            ));
        }
    }

    record Packet(byte[] bytes, List<RtRayTracingPipeline.UploadRange> candidateRanges) {
        Packet {
            bytes = Objects.requireNonNull(bytes, "bytes");
            candidateRanges = List.copyOf(Objects.requireNonNull(candidateRanges, "candidateRanges"));
        }
    }
}
