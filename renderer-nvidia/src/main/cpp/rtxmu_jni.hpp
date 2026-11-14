#pragma once

#include <jni.h>

#include <cstdint>

namespace rtrenderer::nvidia {

class RtxmuSession;

/** Converts Java geometry arrays, records one RTXMU build, and publishes its ownership tuple. */
jlongArray recordRtxmuBuildFromJava(
        JNIEnv* environment,
        RtxmuSession& session,
        std::uint64_t commandBuffer,
        jlongArray positionAddresses,
        jlongArray indexAddresses,
        jintArray vertexCounts,
        jintArray primitiveCounts,
        jbooleanArray opaque
);

/** Records compaction and publishes the updated RTXMU ownership tuple. */
jlongArray recordRtxmuCompactionFromJava(
        JNIEnv* environment,
        RtxmuSession& session,
        std::uint64_t commandBuffer,
        std::uint64_t accelerationStructureId
);

}
