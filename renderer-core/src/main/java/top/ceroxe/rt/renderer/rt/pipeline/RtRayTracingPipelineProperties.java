package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;

import java.util.Arrays;
import java.util.Objects;

/**
 * Stable copy of the ray tracing pipeline limits needed before SBT creation.
 *
 * <p>Vulkan reports these values through a pNext property chain tied to the
 * physical device query. Keeping a small immutable copy prevents later pipeline
 * code from re-querying native structs or accidentally mixing the SBT alignment
 * rules with acceleration-structure scratch alignment.</p>
 *
 * @param shaderGroupHandleSize      shader group handle size in bytes
 * @param shaderGroupHandleAlignment shader group handle alignment in bytes
 * @param shaderGroupBaseAlignment   shader binding table base alignment in bytes
 * @param maxShaderGroupStride       maximum shader binding table record stride
 * @param maxRayRecursionDepth       maximum supported recursion depth
 */
public record RtRayTracingPipelineProperties(
        int shaderGroupHandleSize,
        int shaderGroupHandleAlignment,
        int shaderGroupBaseAlignment,
        int maxShaderGroupStride,
        int maxRayRecursionDepth
) {
    /**
     * Validates all queried device limits as positive values.
     */
    public RtRayTracingPipelineProperties {
        if (shaderGroupHandleSize <= 0) {
            throw new IllegalArgumentException("shaderGroupHandleSize must be positive");
        }
        if (shaderGroupHandleAlignment <= 0) {
            throw new IllegalArgumentException("shaderGroupHandleAlignment must be positive");
        }
        if (shaderGroupBaseAlignment <= 0) {
            throw new IllegalArgumentException("shaderGroupBaseAlignment must be positive");
        }
        if (maxShaderGroupStride <= 0) {
            throw new IllegalArgumentException("maxShaderGroupStride must be positive");
        }
        if (maxRayRecursionDepth <= 0) {
            throw new IllegalArgumentException("maxRayRecursionDepth must be positive");
        }
    }

    /**
     * Queries the ray-tracing pipeline property chain into an immutable Java value.
     *
     * @param stack          caller-owned native scratch scope
     * @param physicalDevice physical device to query
     * @return validated copied limits
     */
    public static RtRayTracingPipelineProperties query(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(physicalDevice, "physicalDevice");

        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingProperties =
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                        .sType$Default();
        VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                .sType$Default()
                .pNext(rayTracingProperties);

        VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
        return new RtRayTracingPipelineProperties(
                rayTracingProperties.shaderGroupHandleSize(),
                rayTracingProperties.shaderGroupHandleAlignment(),
                rayTracingProperties.shaderGroupBaseAlignment(),
                rayTracingProperties.maxShaderGroupStride(),
                rayTracingProperties.maxRayRecursionDepth()
        );
    }

    private static long alignUp(long value, int alignment) {
        long remainder = value % alignment;
        if (remainder == 0L) {
            return value;
        }
        return checkedAdd(value, alignment - remainder);
    }

    private static long checkedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static long checkedMultiply(long left, long right) {
        long result = left * right;
        if (left != 0L && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static int checkedToInt(long value, String label) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " outside int range: " + value);
        }
        return (int) value;
    }

    private static void validateGroupCount(String name, int groups) {
        if (groups < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static byte[] copyBytes(byte[] source, String name) {
        Objects.requireNonNull(source, name);
        return Arrays.copyOf(source, source.length);
    }

    ShaderBindingTableLayout shaderBindingTableLayout(
            int raygenGroups,
            int missGroups,
            int hitGroups,
            int callableGroups
    ) {
        validateGroupCount("raygenGroups", raygenGroups);
        validateGroupCount("missGroups", missGroups);
        validateGroupCount("hitGroups", hitGroups);
        validateGroupCount("callableGroups", callableGroups);
        if (raygenGroups == 0) {
            throw new IllegalArgumentException("SBT layout requires at least one raygen group");
        }

        int stride = checkedToInt(alignUp(shaderGroupHandleSize, shaderGroupHandleAlignment), "SBT record stride");
        if (stride > maxShaderGroupStride) {
            throw new IllegalStateException(
                    "SBT record stride exceeds device maxShaderGroupStride: "
                            + stride
                            + " > "
                            + maxShaderGroupStride
            );
        }
        long offset = 0L;

        Region raygen = regionAt(offset, raygenGroups, stride);
        offset = raygen.endOffset();
        Region miss = regionAt(offset, missGroups, stride);
        offset = miss.endOffset();
        Region hit = regionAt(offset, hitGroups, stride);
        offset = hit.endOffset();
        Region callable = regionAt(offset, callableGroups, stride);
        offset = callable.endOffset();

        long totalBytes = alignUp(offset, shaderGroupBaseAlignment);
        return new ShaderBindingTableLayout(
                stride,
                raygen,
                miss,
                hit,
                callable,
                checkedToInt(totalBytes, "SBT total bytes"),
                shaderGroupBaseAlignment
        );
    }

    ShaderBindingTableData packShaderGroupHandles(
            byte[] shaderGroupHandles,
            int raygenGroups,
            int missGroups,
            int hitGroups,
            int callableGroups
    ) {
        Objects.requireNonNull(shaderGroupHandles, "shaderGroupHandles");
        ShaderBindingTableLayout layout = shaderBindingTableLayout(
                raygenGroups,
                missGroups,
                hitGroups,
                callableGroups
        );
        int totalGroups = checkedToInt(
                checkedAdd(checkedAdd(raygenGroups, missGroups), checkedAdd(hitGroups, callableGroups)),
                "SBT shader group count"
        );
        int expectedHandleBytes = checkedToInt(
                checkedMultiply(totalGroups, shaderGroupHandleSize),
                "SBT shader group handle bytes"
        );
        if (shaderGroupHandles.length != expectedHandleBytes) {
            throw new IllegalArgumentException(
                    "shaderGroupHandles length must be " + expectedHandleBytes + ", got " + shaderGroupHandles.length
            );
        }

        byte[] tableBytes = new byte[layout.totalBytes()];
        int sourceGroup = 0;
        sourceGroup = copyRegionHandles(shaderGroupHandles, sourceGroup, tableBytes, layout.raygen());
        sourceGroup = copyRegionHandles(shaderGroupHandles, sourceGroup, tableBytes, layout.miss());
        sourceGroup = copyRegionHandles(shaderGroupHandles, sourceGroup, tableBytes, layout.hit());
        copyRegionHandles(shaderGroupHandles, sourceGroup, tableBytes, layout.callable());
        return new ShaderBindingTableData(layout, tableBytes);
    }

    /**
     * Builds a bounded device-limit summary.
     *
     * @param name diagnostic label
     * @return summary text
     */
    public String summary(String name) {
        return name
                + "{shaderGroupHandleSize=" + shaderGroupHandleSize
                + ", shaderGroupHandleAlignment=" + shaderGroupHandleAlignment
                + ", shaderGroupBaseAlignment=" + shaderGroupBaseAlignment
                + ", maxShaderGroupStride=" + maxShaderGroupStride
                + ", maxRayRecursionDepth=" + maxRayRecursionDepth
                + "}";
    }

    private Region regionAt(long currentOffset, int groups, int strideBytes) {
        long offset = alignUp(currentOffset, shaderGroupBaseAlignment);
        long size = checkedMultiply(groups, strideBytes);
        return new Region(
                checkedToInt(offset, "SBT region offset"),
                checkedToInt(size, "SBT region size"),
                strideBytes,
                groups
        );
    }

    private int copyRegionHandles(
            byte[] shaderGroupHandles,
            int sourceGroup,
            byte[] tableBytes,
            Region region
    ) {
        for (int group = 0; group < region.groups(); group++) {
            int sourceOffset = checkedToInt(
                    checkedMultiply(sourceGroup + group, shaderGroupHandleSize),
                    "SBT source handle offset"
            );
            int targetOffset = checkedToInt(
                    checkedAdd(region.offsetBytes(), checkedMultiply(group, region.strideBytes())),
                    "SBT target record offset"
            );
            System.arraycopy(shaderGroupHandles, sourceOffset, tableBytes, targetOffset, shaderGroupHandleSize);
        }
        return sourceGroup + region.groups();
    }

    /**
     * Immutable aligned offsets and sizes for every shader binding table region.
     *
     * @param strideBytes        common record stride
     * @param raygen             ray-generation region
     * @param miss               miss region
     * @param hit                hit-group region
     * @param callable           callable region
     * @param totalBytes         total allocation size
     * @param baseAlignmentBytes device base alignment
     */
    public record ShaderBindingTableLayout(
            int strideBytes,
            Region raygen,
            Region miss,
            Region hit,
            Region callable,
            int totalBytes,
            int baseAlignmentBytes
    ) {
        /**
         * Validates alignment, ownership-independent regions, and total table size.
         */
        public ShaderBindingTableLayout {
            Objects.requireNonNull(raygen, "raygen");
            Objects.requireNonNull(miss, "miss");
            Objects.requireNonNull(hit, "hit");
            Objects.requireNonNull(callable, "callable");
            if (strideBytes <= 0) {
                throw new IllegalArgumentException("strideBytes must be positive");
            }
            if (totalBytes < 0) {
                throw new IllegalArgumentException("totalBytes must not be negative");
            }
            if (baseAlignmentBytes <= 0) {
                throw new IllegalArgumentException("baseAlignmentBytes must be positive");
            }
            requireBaseAligned("raygen", raygen, baseAlignmentBytes);
            requireBaseAligned("miss", miss, baseAlignmentBytes);
            requireBaseAligned("hit", hit, baseAlignmentBytes);
            requireBaseAligned("callable", callable, baseAlignmentBytes);
            if (totalBytes % baseAlignmentBytes != 0) {
                throw new IllegalArgumentException("SBT totalBytes must be aligned to baseAlignmentBytes");
            }
        }

        private static void requireBaseAligned(String name, Region region, int baseAlignmentBytes) {
            if (region.offsetBytes() % baseAlignmentBytes != 0) {
                throw new IllegalArgumentException(name + " SBT region offset must be base-aligned");
            }
        }
    }

    /**
     * Packed shader group handles and the layout required to address them.
     *
     * @param layout immutable table layout
     * @param bytes  packed record bytes
     */
    public record ShaderBindingTableData(ShaderBindingTableLayout layout, byte[] bytes) {
        /**
         * Validates the layout and defensively captures packed bytes.
         */
        public ShaderBindingTableData {
            layout = Objects.requireNonNull(layout, "layout");
            bytes = copyBytes(bytes, "bytes");
            if (bytes.length != layout.totalBytes()) {
                throw new IllegalArgumentException("SBT byte array length must match layout totalBytes");
            }
        }

        /**
         * Returns packed data without exposing mutable record state.
         *
         * @return a defensive copy of packed shader binding table bytes
         */
        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    /**
     * One aligned shader binding table region.
     *
     * @param offsetBytes byte offset from table base
     * @param sizeBytes   total region size
     * @param strideBytes record stride
     * @param groups      number of shader groups
     */
    public record Region(int offsetBytes, int sizeBytes, int strideBytes, int groups) {
        /**
         * Validates non-negative extent and positive stride.
         */
        public Region {
            if (offsetBytes < 0) {
                throw new IllegalArgumentException("offsetBytes must not be negative");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if (strideBytes <= 0) {
                throw new IllegalArgumentException("strideBytes must be positive");
            }
            if (groups < 0) {
                throw new IllegalArgumentException("groups must not be negative");
            }
        }

        int endOffset() {
            long end = (long) offsetBytes + sizeBytes;
            if (end > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("SBT region end outside int range: " + end);
            }
            return (int) end;
        }
    }
}
