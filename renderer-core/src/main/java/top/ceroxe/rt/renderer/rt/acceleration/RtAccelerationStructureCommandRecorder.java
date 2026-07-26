package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

/**
 * Records bounded transfer uploads and synchronization required by BLAS/TLAS builds.
 */
final class RtAccelerationStructureCommandRecorder {
    static final int MAX_UPDATE_BYTES = 65_536;

    private static final int POSITION_COMPONENTS = 3;
    private static final int VULKAN_UPDATE_ALIGNMENT_BYTES = 4;
    private static final int ACCELERATION_STRUCTURE_CONSUMER_STAGES =
            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                    | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;

    private RtAccelerationStructureCommandRecorder() {
    }

    static void recordInputUploadBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        VkMemoryBarrier.Buffer uploadBarrier = VkMemoryBarrier.calloc(1, stack);
        uploadBarrier.get(0)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                uploadBarrier,
                null,
                null
        );
    }

    static void recordBuildBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        VkMemoryBarrier.Buffer buildBarrier = VkMemoryBarrier.calloc(1, stack);
        buildBarrier.get(0)
                .sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                ACCELERATION_STRUCTURE_CONSUMER_STAGES,
                0,
                buildBarrier,
                null,
                null
        );
    }

    static void recordFloatUpload(VkCommandBuffer commandBuffer, long buffer, float[] values) {
        requireTarget(commandBuffer, buffer);
        Objects.requireNonNull(values, "values");
        int maxValues = maximumElementsPerUpdate(Float.BYTES);
        FloatBuffer uploadChunk = MemoryUtil.memAllocFloat(maxValues);
        try {
            for (int offset = 0; offset < values.length; offset += maxValues) {
                int count = Math.min(maxValues, values.length - offset);
                uploadChunk.clear();
                uploadChunk.put(values, offset, count);
                uploadChunk.flip();
                VK10.vkCmdUpdateBuffer(commandBuffer, buffer, (long) offset * Float.BYTES, uploadChunk);
            }
        } finally {
            // LWJGL 3.3.3 does not expose the typed-buffer memFree overloads available in newer
            // releases. Always free the allocation base, regardless of the buffer's position.
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(uploadChunk));
        }
    }

    static void recordIntUpload(VkCommandBuffer commandBuffer, long buffer, int[] values) {
        requireTarget(commandBuffer, buffer);
        Objects.requireNonNull(values, "values");
        int maxValues = maximumElementsPerUpdate(Integer.BYTES);
        IntBuffer uploadChunk = MemoryUtil.memAllocInt(maxValues);
        try {
            for (int offset = 0; offset < values.length; offset += maxValues) {
                int count = Math.min(maxValues, values.length - offset);
                uploadChunk.clear();
                uploadChunk.put(values, offset, count);
                uploadChunk.flip();
                VK10.vkCmdUpdateBuffer(commandBuffer, buffer, (long) offset * Integer.BYTES, uploadChunk);
            }
        } finally {
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(uploadChunk));
        }
    }

    static void recordByteUpload(
            VkCommandBuffer commandBuffer,
            long buffer,
            ByteBuffer values,
            int elementStrideBytes
    ) {
        recordByteUpload(commandBuffer, buffer, 0L, values, elementStrideBytes);
    }

    static void recordByteUpload(
            VkCommandBuffer commandBuffer,
            long buffer,
            long destinationOffsetBytes,
            ByteBuffer values,
            int elementStrideBytes
    ) {
        requireTarget(commandBuffer, buffer);
        validateDestinationOffset(destinationOffsetBytes);
        Objects.requireNonNull(values, "values");
        int maxElements = maximumElementsPerUpdate(elementStrideBytes);
        int remainingBytes = values.remaining();
        validatePayloadLayout(remainingBytes, elementStrideBytes);
        int maxBytes = maxElements * elementStrideBytes;
        int sourceStart = values.position();
        for (int relativeOffset = 0; relativeOffset < remainingBytes; relativeOffset += maxBytes) {
            int count = Math.min(maxBytes, remainingBytes - relativeOffset);
            ByteBuffer chunk = values.duplicate();
            chunk.position(sourceStart + relativeOffset);
            chunk.limit(sourceStart + relativeOffset + count);
            VK10.vkCmdUpdateBuffer(
                    commandBuffer,
                    buffer,
                    RtAccelerationStructureBuildSupport.checkedAdd(destinationOffsetBytes, relativeOffset),
                    chunk.slice()
            );
        }
    }

    /**
     * Converts fixed-point section vertices and rebases per-face indices without
     * allocating a heap payload proportional to the section size.
     */
    static void recordSectionMeshUpload(
            VkCommandBuffer commandBuffer,
            long vertexBuffer,
            long indexBuffer,
            SectionTriangleMesh mesh,
            boolean filterByAlphaCutout,
            boolean alphaCutout,
            int expectedFaceCount
    ) {
        requireTarget(commandBuffer, vertexBuffer);
        if (indexBuffer == 0L) {
            throw new IllegalArgumentException("indexBuffer must not be VK_NULL_HANDLE");
        }
        Objects.requireNonNull(mesh, "mesh");
        if (expectedFaceCount <= 0) {
            throw new IllegalArgumentException("expectedFaceCount must be positive");
        }
        final int positionValuesPerFace = 4 * POSITION_COMPONENTS;
        final int indexValuesPerFace = 6;
        int maxFaces = Math.min(
                maximumElementsPerUpdate(positionValuesPerFace * Float.BYTES),
                maximumElementsPerUpdate(indexValuesPerFace * Integer.BYTES)
        );
        FloatBuffer vertexChunk = MemoryUtil.memAllocFloat(maxFaces * positionValuesPerFace);
        IntBuffer indexChunk = null;
        try {
            indexChunk = MemoryUtil.memAllocInt(maxFaces * indexValuesPerFace);
            int sourceFace = 0;
            int uploadedFaces = 0;
            while (sourceFace < mesh.faceCount()) {
                vertexChunk.clear();
                indexChunk.clear();
                int nextSourceFace = mesh.writeBlasGeometryFaces(
                        filterByAlphaCutout,
                        alphaCutout,
                        sourceFace,
                        maxFaces,
                        uploadedFaces,
                        vertexChunk,
                        indexChunk
                );
                int selectedFaces = vertexChunk.position() / positionValuesPerFace;
                if (vertexChunk.position() != selectedFaces * positionValuesPerFace
                        || indexChunk.position() != selectedFaces * indexValuesPerFace) {
                    throw new IllegalStateException("section BLAS writer produced misaligned face payload");
                }
                if (nextSourceFace <= sourceFace) {
                    throw new IllegalStateException("section BLAS writer did not advance its source cursor");
                }
                sourceFace = nextSourceFace;
                if (selectedFaces == 0) {
                    continue;
                }
                vertexChunk.flip();
                indexChunk.flip();
                VK10.vkCmdUpdateBuffer(
                        commandBuffer,
                        vertexBuffer,
                        (long) uploadedFaces * positionValuesPerFace * Float.BYTES,
                        vertexChunk
                );
                VK10.vkCmdUpdateBuffer(
                        commandBuffer,
                        indexBuffer,
                        (long) uploadedFaces * indexValuesPerFace * Integer.BYTES,
                        indexChunk
                );
                uploadedFaces += selectedFaces;
            }
            if (uploadedFaces != expectedFaceCount) {
                throw new IllegalStateException("section BLAS partition face count changed during upload");
            }
        } finally {
            if (indexChunk != null) {
                MemoryUtil.nmemFree(MemoryUtil.memAddress0(indexChunk));
            }
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(vertexChunk));
        }
    }

    static int maximumElementsPerUpdate(int elementStrideBytes) {
        if (elementStrideBytes <= 0
                || elementStrideBytes > MAX_UPDATE_BYTES
                || elementStrideBytes % VULKAN_UPDATE_ALIGNMENT_BYTES != 0) {
            throw new IllegalArgumentException("invalid Vulkan update element stride: " + elementStrideBytes);
        }
        return MAX_UPDATE_BYTES / elementStrideBytes;
    }

    static void validatePayloadLayout(int payloadBytes, int elementStrideBytes) {
        maximumElementsPerUpdate(elementStrideBytes);
        if (payloadBytes < 0 || payloadBytes % elementStrideBytes != 0) {
            throw new IllegalArgumentException("update payload is not aligned to its element stride");
        }
    }

    static void validateDestinationOffset(long destinationOffsetBytes) {
        if (destinationOffsetBytes < 0L
                || destinationOffsetBytes % VULKAN_UPDATE_ALIGNMENT_BYTES != 0L) {
            throw new IllegalArgumentException(
                    "Vulkan update destination offset must be non-negative and 4-byte aligned"
            );
        }
    }

    private static void requireTarget(VkCommandBuffer commandBuffer, long buffer) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (buffer == 0L) {
            throw new IllegalArgumentException("buffer must not be VK_NULL_HANDLE");
        }
    }
}
