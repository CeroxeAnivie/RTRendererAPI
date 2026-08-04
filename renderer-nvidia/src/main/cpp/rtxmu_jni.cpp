#include "rtxmu_jni.hpp"

#include <array>
#include <cstddef>
#include <stdexcept>
#include <vector>

#include "rtxmu_session.hpp"

namespace rtrenderer::nvidia {
namespace {

jlongArray publishResult(JNIEnv* environment, const RtxmuSession::BuildResult& result) {
    const std::array<jlong, 5> values{
            static_cast<jlong>(result.id),
            static_cast<jlong>(result.accelerationStructure),
            static_cast<jlong>(result.deviceAddress),
            static_cast<jlong>(result.storageBytes),
            static_cast<jlong>(result.scratchBytes)
    };
    jlongArray output = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    environment->SetLongArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    return environment->ExceptionCheck() ? nullptr : output;
}

}

jlongArray recordRtxmuBuildFromJava(
        JNIEnv* environment,
        RtxmuSession& session,
        std::uint64_t commandBuffer,
        jlongArray positionAddresses,
        jlongArray indexAddresses,
        jintArray vertexCounts,
        jintArray primitiveCounts,
        jbooleanArray opaque
) {
    if (environment == nullptr || positionAddresses == nullptr || indexAddresses == nullptr
            || vertexCounts == nullptr || primitiveCounts == nullptr || opaque == nullptr) {
        throw std::invalid_argument("RTXMU build received null JNI geometry arrays");
    }
    const jsize count = environment->GetArrayLength(positionAddresses);
    if (count <= 0 || environment->GetArrayLength(indexAddresses) != count
            || environment->GetArrayLength(vertexCounts) != count
            || environment->GetArrayLength(primitiveCounts) != count
            || environment->GetArrayLength(opaque) != count) {
        throw std::invalid_argument("RTXMU geometry arrays must be non-empty and equal length");
    }

    std::vector<jlong> rawPositions(static_cast<std::size_t>(count));
    std::vector<jlong> rawIndices(static_cast<std::size_t>(count));
    std::vector<jint> rawVertexCounts(static_cast<std::size_t>(count));
    std::vector<jint> rawPrimitiveCounts(static_cast<std::size_t>(count));
    std::vector<jboolean> rawOpaque(static_cast<std::size_t>(count));
    environment->GetLongArrayRegion(positionAddresses, 0, count, rawPositions.data());
    environment->GetLongArrayRegion(indexAddresses, 0, count, rawIndices.data());
    environment->GetIntArrayRegion(vertexCounts, 0, count, rawVertexCounts.data());
    environment->GetIntArrayRegion(primitiveCounts, 0, count, rawPrimitiveCounts.data());
    environment->GetBooleanArrayRegion(opaque, 0, count, rawOpaque.data());
    if (environment->ExceptionCheck()) return nullptr;

    std::vector<std::uint64_t> positions(static_cast<std::size_t>(count));
    std::vector<std::uint64_t> indices(static_cast<std::size_t>(count));
    std::vector<std::uint32_t> vertices(static_cast<std::size_t>(count));
    std::vector<std::uint32_t> primitives(static_cast<std::size_t>(count));
    std::vector<bool> geometryOpaque(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        if (rawPositions[index] <= 0 || rawIndices[index] <= 0) {
            throw std::invalid_argument("RTXMU geometry device addresses must be positive");
        }
        if (rawVertexCounts[index] <= 0 || rawPrimitiveCounts[index] <= 0) {
            throw std::invalid_argument("RTXMU geometry counts must be positive");
        }
        positions[index] = static_cast<std::uint64_t>(rawPositions[index]);
        indices[index] = static_cast<std::uint64_t>(rawIndices[index]);
        vertices[index] = static_cast<std::uint32_t>(rawVertexCounts[index]);
        primitives[index] = static_cast<std::uint32_t>(rawPrimitiveCounts[index]);
        geometryOpaque[index] = rawOpaque[index] == JNI_TRUE;
    }

    const RtxmuSession::BuildResult result = session.recordBuild(
            commandBuffer, positions, indices, vertices, primitives, geometryOpaque
    );
    jlongArray output = publishResult(environment, result);
    if (output != nullptr) return output;

    // Java could not take ownership of the returned id. Reclaim it here so allocation failure or
    // a pending JNI exception cannot orphan native acceleration-structure storage.
    try {
        session.remove(result.id);
    } catch (...) {
        // Preserve the pending JVM exception. The session destructor remains the final owner.
    }
    return nullptr;
}

jlongArray recordRtxmuCompactionFromJava(
        JNIEnv* environment,
        RtxmuSession& session,
        std::uint64_t commandBuffer,
        std::uint64_t accelerationStructureId
) {
    if (environment == nullptr) throw std::invalid_argument("RTXMU compaction received a null JNI environment");
    const RtxmuSession::BuildResult result =
            session.recordCompaction(commandBuffer, accelerationStructureId);
    return publishResult(environment, result);
}

}
