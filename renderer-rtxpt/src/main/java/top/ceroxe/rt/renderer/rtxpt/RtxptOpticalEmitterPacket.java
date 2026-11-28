package top.ceroxe.rt.renderer.rtxpt;

import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.api.OpticalTriangleEmitter;

/** Encodes an immutable source snapshot for the native lighting owner. */
record RtxptOpticalEmitterPacket(float[] records, long[] volumes, boolean containsMedia) {
    private static final int RECORD_FLOATS = 13, VOLUME_SLOTS = 4;

    static RtxptOpticalEmitterPacket pack(List<OpticalTriangleEmitter> sources) {
        var records = new float[Math.multiplyExact(sources.size(), RECORD_FLOATS)];
        var volumes = new long[Math.multiplyExact(sources.size(), VOLUME_SLOTS)];
        Arrays.fill(volumes, -1);
        int r = 0, v = 0;
        boolean containsMedia = false;
        for (var source : sources) {
            records[r++] = (float) source.x();
            records[r++] = (float) source.y();
            records[r++] = (float) source.z();
            records[r++] = source.edgeUx();
            records[r++] = source.edgeUy();
            records[r++] = source.edgeUz();
            records[r++] = source.edgeVx();
            records[r++] = source.edgeVy();
            records[r++] = source.edgeVz();
            records[r++] = source.radiance().red();
            records[r++] = source.radiance().green();
            records[r++] = source.radiance().blue();
            records[r++] = source.reach();
            var inside = source.mediumInstanceIds();
            containsMedia |= !inside.isEmpty();
            for (int i = 0; i < inside.size(); ++i) volumes[v + i] = inside.get(i);
            v += VOLUME_SLOTS;
        }
        return new RtxptOpticalEmitterPacket(records, volumes, containsMedia);
    }

    void validateVolumes(RtxptScene scene) {
        if (!containsMedia) return;
        for (long id : volumes) {
            if (id < 0) continue;
            var instance = scene.instances.get(id);
            if (instance == null || instance.mediumBoundary().isEmpty())
                throw new IllegalArgumentException("Unknown optical emitter medium instance: " + id);
        }
    }
}
