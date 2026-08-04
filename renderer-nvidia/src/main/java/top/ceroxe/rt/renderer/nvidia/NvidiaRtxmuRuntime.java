package top.ceroxe.rt.renderer.nvidia;

/** Owns the Java-side translation of RTX Memory Utility build results. */
final class NvidiaRtxmuRuntime {
    private NvidiaRtxmuRuntime() {
    }

    static Build recordBuild(
            long session,
            long commandBuffer,
            long[] positionAddresses,
            long[] indexAddresses,
            int[] vertexCounts,
            int[] primitiveCounts,
            boolean[] opaque
    ) {
        return Build.from(NvidiaNativeBridge.nativeRtxmuRecordBuild(
                session, commandBuffer, positionAddresses, indexAddresses,
                vertexCounts, primitiveCounts, opaque
        ));
    }

    static Build recordCompaction(long session, long commandBuffer, long id) {
        return Build.from(NvidiaNativeBridge.nativeRtxmuRecordCompaction(session, commandBuffer, id));
    }

    static void garbageCollect(long session, long id) {
        NvidiaNativeBridge.nativeRtxmuGarbageCollect(session, id);
    }

    static void remove(long session, long id) {
        NvidiaNativeBridge.nativeRtxmuRemove(session, id);
    }

    record Build(
            long id,
            long accelerationStructure,
            long deviceAddress,
            long storageBytes,
            long scratchBytes
    ) {
        Build {
            if (id <= 0L || accelerationStructure == 0L || deviceAddress == 0L
                    || storageBytes <= 0L || scratchBytes <= 0L) {
                throw new IllegalArgumentException("RTXMU returned invalid build ownership metadata");
            }
        }

        private static Build from(long[] values) {
            if (values == null || values.length != 5) {
                throw new IllegalStateException("RTXMU native result must contain five values");
            }
            return new Build(values[0], values[1], values[2], values[3], values[4]);
        }
    }
}
