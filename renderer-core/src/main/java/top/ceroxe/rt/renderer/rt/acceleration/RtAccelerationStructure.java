package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuWorkLabels;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.rt.renderer.DynamicMeshInstance;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Vulkan acceleration structure wrapper.
 *
 * <p>这个类型负责 acceleration structure handle 与 backing storage buffer 的
 * 生命周期。普通构建的 build input/scratch 属于临时提交；高频 dynamic lane
 * 可显式注入 {@link PersistentTlasBuildInputs}，由上层 cache 统一持有并复用
 * device-local instance/scratch 资源。</p>
 */
public final class RtAccelerationStructure implements AutoCloseable {
    private static final int POSITION_COMPONENTS = 3;
    private static final int SECTION_EDGE_BLOCKS = 16;
    private static final int INSTANCE_MASK_ALL = 0xFF;
    private static final int INSTANCE_MASK_NONE = 0x00;
    private static final int MAX_INSTANCE_CUSTOM_INDEX = 0x00FF_FFFF;
    private static final float[] BOOTSTRAP_TRIANGLE_POSITIONS = {
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
    };
    private static final int[] BOOTSTRAP_TRIANGLE_INDICES = {0, 1, 2, 2, 1, 3};

    private final VkDevice device;
    private final RtGpuBuffer storageBuffer;
    private final long accelerationStructure;
    private final int type;
    private final long deviceAddress;
    private final long buildScratchBytes;
    private final int scratchAlignmentBytes;
    private boolean closed;

    private RtAccelerationStructure(
            VkDevice device,
            RtGpuBuffer storageBuffer,
            long accelerationStructure,
            int type,
            long deviceAddress,
            long buildScratchBytes,
            int scratchAlignmentBytes
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.storageBuffer = Objects.requireNonNull(storageBuffer, "storageBuffer");
        this.accelerationStructure = accelerationStructure;
        this.type = type;
        this.deviceAddress = deviceAddress;
        this.buildScratchBytes = buildScratchBytes;
        this.scratchAlignmentBytes = scratchAlignmentBytes;
    }

    /**
     * 构建用于引导光线追踪资源初始化的固定三角形几何 BLAS。
     *
     * <p>构建命令通过给定命令上下文提交；调用方获得返回值后负责关闭该加速结构。</p>
     *
     * @param device                Vulkan 逻辑设备
     * @param allocator             VMA 分配器句柄
     * @param commandContext        用于记录并提交构建命令的上下文
     * @param scratchAlignmentBytes 构建暂存地址所需的字节对齐值
     * @return 已构建的引导 BLAS
     */
    public static RtAccelerationStructure buildBootstrapTriangleBlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes
    ) {
        return buildTriangleBlas(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                BOOTSTRAP_TRIANGLE_POSITIONS,
                BOOTSTRAP_TRIANGLE_INDICES,
                "bootstrap BLAS"
        );
    }

    /**
     * 为引导 BLAS 构建包含一个单位变换实例的顶层加速结构。
     *
     * <p>构建命令通过给定命令上下文提交；调用方获得返回值后负责关闭该加速结构。</p>
     *
     * @param device                Vulkan 逻辑设备
     * @param allocator             VMA 分配器句柄
     * @param commandContext        用于记录并提交构建命令的上下文
     * @param scratchAlignmentBytes 构建暂存地址所需的字节对齐值
     * @param bootstrapBlas         作为唯一实例引用的引导 BLAS
     * @return 已构建的引导 TLAS
     * @throws NullPointerException 如果 {@code bootstrapBlas} 为 {@code null}
     */
    public static RtAccelerationStructure buildBootstrapTlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtAccelerationStructure bootstrapBlas
    ) {
        Objects.requireNonNull(bootstrapBlas, "bootstrapBlas");
        return buildTlas(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                List.of(TlasInstance.identity(bootstrapBlas.deviceAddress())),
                "bootstrap TLAS",
                commandContext.stallTelemetry()
        );
    }

    static RtAccelerationStructure buildSectionBlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            SectionTriangleMesh mesh
    ) {
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException("section mesh must contain at least one triangle");
        }
        return buildTriangleBlas(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                mesh.vertexPositionsAsFloats(),
                mesh.indices(),
                "section BLAS " + mesh.key()
        );
    }

    static DynamicBlasBuildSubmission submitDynamicBlasAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtDynamicTriangleMesh mesh
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException("dynamic mesh must contain at least one triangle");
        }

        PreparedTriangleBlasBuild preparedBuild = null;
        try {
            preparedBuild = prepareTriangleBlas(
                    device,
                    allocator,
                    scratchAlignmentBytes,
                    mesh.vertexPositions(),
                    mesh.indices(),
                    "dynamic BLAS revision " + mesh.revision(),
                    null,
                    null,
                    commandContext.stallTelemetry()
            );
            PreparedTriangleBlasBuild submittedBuild = preparedBuild;
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.DYNAMIC_BLAS,
                    submittedBuild::record
            );
            preparedBuild = null;
            return new DynamicBlasBuildSubmission(submission, submittedBuild, mesh, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, preparedBuild);
            throw ex;
        }
    }

    static FarFieldBlasBuildSubmission submitFarFieldBlasAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtFarFieldProxyMeshBuilder.ProxyMesh mesh
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException("far-field proxy mesh must contain at least one triangle");
        }

        PreparedTriangleBlasBuild preparedBuild = null;
        try {
            preparedBuild = prepareTriangleBlas(
                    device,
                    allocator,
                    scratchAlignmentBytes,
                    mesh.vertexPositions(),
                    mesh.indices(),
                    "far-field BLAS " + mesh.cellKey(),
                    null,
                    mesh,
                    commandContext.stallTelemetry()
            );
            PreparedTriangleBlasBuild submittedBuild = preparedBuild;
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.FAR_FIELD_BLAS,
                    submittedBuild::record
            );
            preparedBuild = null;
            return new FarFieldBlasBuildSubmission(submission, submittedBuild, mesh, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, preparedBuild);
            throw ex;
        }
    }

    static List<SectionBlasBuildResult> buildSectionBlasBatch(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            List<SectionTriangleMesh> meshes
    ) {
        Objects.requireNonNull(meshes, "meshes");
        if (meshes.isEmpty()) {
            return List.of();
        }

        List<PreparedTriangleBlasBuild> preparedBuilds = new ArrayList<>(meshes.size());
        List<SectionBlasBuildResult> results = new ArrayList<>(meshes.size());
        try {
            preparedBuilds.addAll(prepareSectionBlasBatch(
                    device, allocator, scratchAlignmentBytes, meshes, commandContext.stallTelemetry()));

            commandContext.submitOneTime((commandBuffer, commandStack) ->
                    recordPreparedSectionBlasBatch(commandBuffer, commandStack, preparedBuilds));

            results.addAll(releaseSectionBlasResults(preparedBuilds));
            closePreparedBuilds(preparedBuilds);
            return List.copyOf(results);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeReleasedBlasesSuppressing(ex, results);
            closePreparedBuildsSuppressing(ex, preparedBuilds);
            throw ex;
        }
    }

    static SectionBlasBuildSubmission submitSectionBlasBatchAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            List<SectionTriangleMesh> meshes
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(meshes, "meshes");
        if (meshes.isEmpty()) {
            throw new IllegalArgumentException("async section BLAS batch must not be empty");
        }

        List<PreparedTriangleBlasBuild> preparedBuilds = new ArrayList<>(meshes.size());
        try {
            preparedBuilds.addAll(prepareSectionBlasBatch(
                    device, allocator, scratchAlignmentBytes, meshes, commandContext.stallTelemetry()));
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.SECTION_BLAS,
                    (commandBuffer, commandStack) ->
                            recordPreparedSectionBlasBatch(commandBuffer, commandStack, preparedBuilds)
            );
            return new SectionBlasBuildSubmission(submission, preparedBuilds, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closePreparedBuildsSuppressing(ex, preparedBuilds);
            throw ex;
        }
    }

    static RecordedSectionBlasBuild recordSectionBlasBatch(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            List<SectionTriangleMesh> meshes,
            RtEdgeSink edges
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(meshes, "meshes");
        Objects.requireNonNull(edges, "edges");
        if (meshes.isEmpty()) {
            throw new IllegalArgumentException("recorded section BLAS batch must not be empty");
        }
        List<PreparedTriangleBlasBuild> preparedBuilds = new ArrayList<>(meshes.size());
        long batchStartNanos = System.nanoTime();
        long prepareCompleteNanos = batchStartNanos;
        long slowestSectionNanos = 0L;
        SectionKey slowestSectionKey = null;
        long triangles = 0L;
        long meshBytes = 0L;
        try {
            for (SectionTriangleMesh mesh : meshes) {
                long sectionStartNanos = System.nanoTime();
                preparedBuilds.add(prepareSectionBlas(
                        device,
                        allocator,
                        scratchAlignmentBytes,
                        mesh,
                        commandContext.stallTelemetry()
                ));
                long sectionNanos = System.nanoTime() - sectionStartNanos;
                if (sectionNanos > slowestSectionNanos) {
                    slowestSectionNanos = sectionNanos;
                    slowestSectionKey = mesh.key();
                }
                triangles += mesh.triangleCount();
                meshBytes += mesh.estimatedBytes();
            }
            prepareCompleteNanos = System.nanoTime();
            RtCommandContext.RecordedCommandBuffer recording = commandContext.recordTimedOneTime(
                    RtGpuWorkLabels.SECTION_BLAS,
                    (commandBuffer, commandStack) -> recordPreparedSectionBlasBatch(commandBuffer, commandStack, preparedBuilds)
            );
            long recordCompleteNanos = System.nanoTime();
            /*
             * Per-batch text output is not valid benchmark telemetry: a 32
             * chunk bootstrap can submit thousands of batches and make host
             * I/O the dominant frame cost.  The caller retains the same
             * counters and timings in its native summary; opt into this
             * forensic detail only when explicitly requesting verbose smoke
             * I/O.
             */
            if (edges.verboseIoEnabled()) {
                top.ceroxe.rt.renderer.RendererLog.info(
                        "rt section BLAS batch recorded: sections={}, triangles={}, meshBytes={}, prepareMs={}, commandRecordMs={}, totalMs={}, slowestSection={}, slowestSectionMs={}",
                        meshes.size(),
                        triangles,
                        meshBytes,
                        nanosToMillis(prepareCompleteNanos - batchStartNanos),
                        nanosToMillis(recordCompleteNanos - prepareCompleteNanos),
                        nanosToMillis(recordCompleteNanos - batchStartNanos),
                        slowestSectionKey,
                        nanosToMillis(slowestSectionNanos)
                );
            }
            return new RecordedSectionBlasBuild(recording, preparedBuilds);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closePreparedBuildsSuppressing(ex, preparedBuilds);
            throw ex;
        }
    }

    static RtAccelerationStructure buildWorldTlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            Collection<TlasInstance> instances
    ) {
        return buildTlas(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                instances,
                "world TLAS",
                commandContext.stallTelemetry()
        );
    }

    static WorldTlasBuildSubmission submitWorldTlasAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            Collection<TlasInstance> instances
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        PreparedTlasBuild preparedBuild = prepareTlas(
                device,
                allocator,
                scratchAlignmentBytes,
                instances,
                "world TLAS",
                commandContext.stallTelemetry()
        );
        try {
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.WORLD_TLAS,
                    preparedBuild::record
            );
            return new WorldTlasBuildSubmission(submission, preparedBuild, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, preparedBuild);
            throw ex;
        }
    }

    static WorldTlasBuildSubmission submitWorldTlasUpdateAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtAccelerationStructure sourceTlas,
            Collection<TlasInstance> instances
    ) {
        return submitWorldTlasUpdateAsync(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                sourceTlas,
                null,
                instances
        );
    }

    /**
     * Records an update into a descriptor-safe recycled TLAS slot when one is
     * available.  Keeping source and destination separate preserves the
     * renderer's failure-atomic descriptor swap while avoiding per-update AS
     * allocation once the bounded world slot ring is warm.
     */
    static WorldTlasBuildSubmission submitWorldTlasUpdateAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtAccelerationStructure sourceTlas,
            RtAccelerationStructure reusableDestinationTlas,
            Collection<TlasInstance> instances
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(sourceTlas, "sourceTlas");
        PreparedTlasBuild preparedBuild = prepareTlas(
                device,
                allocator,
                scratchAlignmentBytes,
                instances,
                "world TLAS update",
                sourceTlas,
                true,
                reusableDestinationTlas,
                commandContext.stallTelemetry()
        );
        try {
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.WORLD_TLAS,
                    preparedBuild::record
            );
            return new WorldTlasBuildSubmission(submission, preparedBuild, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, preparedBuild);
            throw ex;
        }
    }

    static WorldTlasBuildSubmission submitPersistentWorldTlasAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            Collection<TlasInstance> instances,
            int[] dirtyInstanceSlots,
            PersistentTlasBuildInputs persistentInputs
    ) {
        return submitPersistentWorldTlasAsync(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                null,
                null,
                instances,
                dirtyInstanceSlots,
                persistentInputs
        );
    }

    static WorldTlasBuildSubmission submitPersistentWorldTlasUpdateAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtAccelerationStructure sourceTlas,
            RtAccelerationStructure reusableDestinationTlas,
            Collection<TlasInstance> instances,
            int[] dirtyInstanceSlots,
            PersistentTlasBuildInputs persistentInputs
    ) {
        return submitPersistentWorldTlasAsync(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                Objects.requireNonNull(sourceTlas, "sourceTlas"),
                reusableDestinationTlas,
                instances,
                dirtyInstanceSlots,
                persistentInputs
        );
    }

    private static WorldTlasBuildSubmission submitPersistentWorldTlasAsync(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtAccelerationStructure sourceTlas,
            RtAccelerationStructure reusableDestinationTlas,
            Collection<TlasInstance> instances,
            int[] dirtyInstanceSlots,
            PersistentTlasBuildInputs persistentInputs
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(persistentInputs, "persistentInputs");
        boolean update = sourceTlas != null;
        PreparedTlasBuild preparedBuild = null;
        try {
            preparedBuild = prepareTlas(
                    device,
                    allocator,
                    scratchAlignmentBytes,
                    instances,
                    update ? "persistent world TLAS update" : "persistent world TLAS",
                    sourceTlas,
                    update,
                    reusableDestinationTlas,
                    commandContext.stallTelemetry(),
                    persistentInputs,
                    dirtyInstanceSlots
            );
            PreparedTlasBuild recordedBuild = preparedBuild;
            RtCommandContext.AsyncSubmission submission = commandContext.submitTimedOneTimeAsync(
                    RtGpuWorkLabels.DYNAMIC_TLAS,
                    recordedBuild::record
            );
            return new WorldTlasBuildSubmission(submission, recordedBuild, System.nanoTime());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            if (preparedBuild != null) {
                closeSuppressing(ex, preparedBuild);
            } else {
                closeSuppressing(ex, reusableDestinationTlas);
            }
            throw ex;
        }
    }

    private static RtAccelerationStructure buildTriangleBlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            float[] vertexPositions,
            int[] indices,
            String label
    ) {
        PreparedTriangleBlasBuild preparedBuild = prepareTriangleBlas(
                device,
                allocator,
                scratchAlignmentBytes,
                vertexPositions,
                indices,
                label,
                null,
                null,
                commandContext.stallTelemetry()
        );
        try (preparedBuild) {
            commandContext.submitOneTime(preparedBuild::record);
            return preparedBuild.releaseAccelerationStructure();
        }
    }

    private static PreparedTriangleBlasBuild prepareTriangleBlas(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            float[] vertexPositions,
            int[] indices,
            String label,
            SectionTriangleMesh mesh,
            RtFarFieldProxyMeshBuilder.ProxyMesh farFieldProxyMesh,
            RtStallTelemetrySink stalls
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(stalls, "stalls");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        if (mesh == null) {
            Objects.requireNonNull(vertexPositions, "vertexPositions");
            if (vertexPositions.length == 0 || vertexPositions.length % POSITION_COMPONENTS != 0) {
                throw new IllegalArgumentException(label + " vertex positions must contain XYZ float triples");
            }
            int rawVertexCount = vertexPositions.length / POSITION_COMPONENTS;
            RtAccelerationStructureBuildSupport.validateAndCountTriangles(indices, rawVertexCount, label);
        } else if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException(label + " must contain at least one triangle");
        }

        List<PreparedTriangleGeometry> geometries = new ArrayList<>();
        RtGpuBuffer scratchBuffer = null;
        RtAccelerationStructure blas = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            geometries.addAll(prepareTriangleGeometries(
                    device,
                    allocator,
                    vertexPositions,
                    indices,
                    mesh,
                    farFieldProxyMesh,
                    stalls
            ));
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    PreparedTriangleBlasBuild.nativeGeometries(stack, geometries);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuffer = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometry.remaining())
                    .pGeometries(geometry);

            IntBuffer maxPrimitiveCounts = stack.mallocInt(geometries.size());
            for (int geometryIndex = 0; geometryIndex < geometries.size(); geometryIndex++) {
                maxPrimitiveCounts.put(geometryIndex, geometries.get(geometryIndex).primitiveCount());
            }
            VkAccelerationStructureBuildSizesInfoKHR buildSizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    maxPrimitiveCounts,
                    buildSizes
            );
            RtAccelerationStructureBuildSupport.validateBuildSizes(buildSizes);

            blas = createBottomLevel(
                    stack,
                    device,
                    allocator,
                    buildSizes.accelerationStructureSize(),
                    buildSizes.buildScratchSize(),
                    scratchAlignmentBytes,
                    stalls
            );
            scratchBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    scratchBufferBytes(buildSizes.buildScratchSize(), scratchAlignmentBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    stalls
            );
            long scratchAddress = alignedScratchAddress(scratchBuffer, scratchAlignmentBytes, buildSizes.buildScratchSize());
            PreparedTriangleBlasBuild preparedBuild = new PreparedTriangleBlasBuild(
                    geometries,
                    scratchBuffer,
                    blas,
                    scratchAddress,
                    mesh
            );
            geometries = List.of();
            scratchBuffer = null;
            blas = null;
            return preparedBuild;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, scratchBuffer);
            closeTriangleGeometriesSuppressing(ex, geometries);
            closeSuppressing(ex, blas);
            throw ex;
        }
    }

    private static List<PreparedTriangleGeometry> prepareTriangleGeometries(
            VkDevice device,
            long allocator,
            float[] vertexPositions,
            int[] indices,
            SectionTriangleMesh mesh,
            RtFarFieldProxyMeshBuilder.ProxyMesh farFieldProxyMesh,
            RtStallTelemetrySink stalls
    ) {
        if (mesh != null && farFieldProxyMesh != null) {
            throw new IllegalArgumentException("triangle BLAS cannot use section and far-field geometry together");
        }
        if (farFieldProxyMesh != null) {
            List<PreparedTriangleGeometry> geometries = new ArrayList<>();
            try {
                for (RtFarFieldProxyMeshBuilder.GeometryPart geometryPart : farFieldProxyMesh.geometryParts()) {
                    geometries.add(prepareTriangleGeometry(
                            device,
                            allocator,
                            geometryPart.vertexPositions(),
                            geometryPart.indices(),
                            geometryPart.opaque(),
                            stalls
                    ));
                }
                return List.copyOf(geometries);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeTriangleGeometriesSuppressing(ex, geometries);
                throw ex;
            }
        }
        if (mesh == null) {
            return List.of(prepareTriangleGeometry(
                    device, allocator, vertexPositions, indices, true, stalls));
        }

        List<PreparedTriangleGeometry> geometries = new ArrayList<>(2);
        try {
            int alphaFaces = mesh.alphaCutoutFaceCount();
            if (alphaFaces == 0 || alphaFaces == mesh.faceCount()) {
                geometries.add(prepareSectionTriangleGeometry(
                        device,
                        allocator,
                        mesh,
                        false,
                        alphaFaces != 0,
                        mesh.faceCount(),
                        alphaFaces == 0,
                        stalls
                ));
            } else {
                geometries.add(prepareSectionTriangleGeometry(
                        device,
                        allocator,
                        mesh,
                        true,
                        false,
                        mesh.faceCount() - alphaFaces,
                        true,
                        stalls
                ));
                geometries.add(prepareSectionTriangleGeometry(
                        device,
                        allocator,
                        mesh,
                        true,
                        true,
                        alphaFaces,
                        false,
                        stalls
                ));
            }
            return List.copyOf(geometries);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeTriangleGeometriesSuppressing(ex, geometries);
            throw ex;
        }
    }

    private static PreparedTriangleGeometry prepareSectionTriangleGeometry(
            VkDevice device,
            long allocator,
            SectionTriangleMesh mesh,
            boolean filterByAlphaCutout,
            boolean alphaCutout,
            int faceCount,
            boolean opaqueGeometry,
            RtStallTelemetrySink stalls
    ) {
        if (faceCount <= 0) {
            throw new IllegalArgumentException("section BLAS partition must contain faces");
        }
        int vertexCount = Math.multiplyExact(faceCount, 4);
        int indexCount = Math.multiplyExact(faceCount, 6);
        RtGpuBuffer vertexBuffer = null;
        RtGpuBuffer indexBuffer = null;
        try {
            vertexBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    RtAccelerationStructureBuildSupport.checkedMultiply(
                            RtAccelerationStructureBuildSupport.checkedMultiply(vertexCount, POSITION_COMPONENTS),
                            Float.BYTES
                    ),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    , stalls
            );
            indexBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    RtAccelerationStructureBuildSupport.checkedMultiply(indexCount, Integer.BYTES),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    , stalls
            );
            PreparedTriangleGeometry geometry = new PreparedTriangleGeometry(
                    vertexBuffer,
                    indexBuffer,
                    mesh,
                    filterByAlphaCutout,
                    alphaCutout,
                    vertexCount,
                    Math.multiplyExact(faceCount, 2),
                    opaqueGeometry
            );
            vertexBuffer = null;
            indexBuffer = null;
            return geometry;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, indexBuffer);
            closeSuppressing(ex, vertexBuffer);
            throw ex;
        }
    }

    private static PreparedTriangleGeometry prepareTriangleGeometry(
            VkDevice device,
            long allocator,
            float[] vertexPositions,
            int[] indices,
            boolean opaqueGeometry,
            RtStallTelemetrySink stalls
    ) {
        int vertexCount = vertexPositions.length / POSITION_COMPONENTS;
        int primitiveCount = RtAccelerationStructureBuildSupport.validateAndCountTriangles(
                indices, vertexCount, "section geometry"
        );
        RtGpuBuffer vertexBuffer = null;
        RtGpuBuffer indexBuffer = null;
        try {
            vertexBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    RtAccelerationStructureBuildSupport.checkedMultiply(vertexPositions.length, Float.BYTES),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    , stalls
            );
            indexBuffer = createIndexBuffer(device, allocator, indices, stalls);
            PreparedTriangleGeometry geometry = new PreparedTriangleGeometry(
                    vertexBuffer,
                    indexBuffer,
                    vertexPositions,
                    indices,
                    vertexCount,
                    primitiveCount,
                    opaqueGeometry
            );
            vertexBuffer = null;
            indexBuffer = null;
            return geometry;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, indexBuffer);
            closeSuppressing(ex, vertexBuffer);
            throw ex;
        }
    }

    private static List<PreparedTriangleBlasBuild> prepareSectionBlasBatch(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            List<SectionTriangleMesh> meshes,
            RtStallTelemetrySink stalls
    ) {
        List<PreparedTriangleBlasBuild> preparedBuilds = new ArrayList<>(meshes.size());
        try {
            for (SectionTriangleMesh mesh : meshes) {
                preparedBuilds.add(prepareSectionBlas(
                        device, allocator, scratchAlignmentBytes, mesh, stalls));
            }
            return preparedBuilds;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closePreparedBuildsSuppressing(ex, preparedBuilds);
            throw ex;
        }
    }

    private static PreparedTriangleBlasBuild prepareSectionBlas(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            SectionTriangleMesh mesh,
            RtStallTelemetrySink stalls
    ) {
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException("section mesh must contain at least one triangle");
        }
        return prepareTriangleBlas(
                device,
                allocator,
                scratchAlignmentBytes,
                null,
                null,
                "section BLAS " + mesh.key(),
                mesh,
                null,
                stalls
        );
    }

    private static String nanosToMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0L, nanos) / 1_000_000.0D);
    }

    private static void recordPreparedSectionBlasBatch(
            VkCommandBuffer commandBuffer,
            MemoryStack commandStack,
            List<PreparedTriangleBlasBuild> preparedBuilds
    ) {
        for (PreparedTriangleBlasBuild preparedBuild : preparedBuilds) {
            preparedBuild.recordUpload(commandBuffer);
        }
        RtAccelerationStructureCommandRecorder.recordInputUploadBarrier(commandBuffer, commandStack);
        for (PreparedTriangleBlasBuild preparedBuild : preparedBuilds) {
            preparedBuild.recordBuild(commandBuffer);
        }
        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, commandStack);
    }

    private static List<SectionBlasBuildResult> releaseSectionBlasResults(
            List<PreparedTriangleBlasBuild> preparedBuilds
    ) {
        List<SectionBlasBuildResult> results = new ArrayList<>(preparedBuilds.size());
        for (PreparedTriangleBlasBuild preparedBuild : preparedBuilds) {
            results.add(new SectionBlasBuildResult(
                    preparedBuild.mesh(),
                    preparedBuild.releaseAccelerationStructure()
            ));
        }
        return results;
    }

    private static void closePreparedBuilds(List<PreparedTriangleBlasBuild> preparedBuilds) {
        RuntimeException failure = closePreparedBuildsCollecting(null, preparedBuilds);
        if (failure != null) {
            throw failure;
        }
    }

    private static void closePreparedBuildsSuppressing(Throwable failure, List<PreparedTriangleBlasBuild> preparedBuilds) {
        for (int index = preparedBuilds.size() - 1; index >= 0; index--) {
            closeSuppressing(failure, preparedBuilds.get(index));
        }
    }

    private static RuntimeException closePreparedBuildsCollecting(
            RuntimeException failure,
            List<PreparedTriangleBlasBuild> preparedBuilds
    ) {
        for (int index = preparedBuilds.size() - 1; index >= 0; index--) {
            failure = closeCollecting(failure, preparedBuilds.get(index));
        }
        return failure;
    }

    private static void closeReleasedBlasesSuppressing(Throwable failure, List<SectionBlasBuildResult> results) {
        for (int index = results.size() - 1; index >= 0; index--) {
            closeSuppressing(failure, results.get(index).blas());
        }
    }

    private static void closeTriangleGeometriesSuppressing(
            Throwable failure,
            List<PreparedTriangleGeometry> geometries
    ) {
        for (int index = geometries.size() - 1; index >= 0; index--) {
            closeSuppressing(failure, geometries.get(index));
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
            return failure;
        } catch (Exception ex) {
            RuntimeException wrapped = ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("failed to close RT resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            failure.addSuppressed(ex);
        }
    }

    private static RtAccelerationStructure buildTlas(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            Collection<TlasInstance> sourceInstances,
            String label,
            RtStallTelemetrySink stalls
    ) {
        PreparedTlasBuild preparedBuild = prepareTlas(
                device,
                allocator,
                scratchAlignmentBytes,
                sourceInstances,
                label,
                stalls
        );
        try (preparedBuild) {
            commandContext.submitOneTime(preparedBuild::record);
            return preparedBuild.releaseAccelerationStructure();
        }
    }

    private static PreparedTlasBuild prepareTlas(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            Collection<TlasInstance> sourceInstances,
            String label,
            RtStallTelemetrySink stalls
    ) {
        return prepareTlas(
                device,
                allocator,
                scratchAlignmentBytes,
                sourceInstances,
                label,
                null,
                false,
                null,
                stalls,
                null,
                new int[0]
        );
    }

    private static PreparedTlasBuild prepareTlas(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            Collection<TlasInstance> sourceInstances,
            String label,
            RtAccelerationStructure sourceTlas,
            boolean update,
            RtAccelerationStructure reusableDestinationTlas,
            RtStallTelemetrySink stalls
    ) {
        return prepareTlas(
                device,
                allocator,
                scratchAlignmentBytes,
                sourceInstances,
                label,
                sourceTlas,
                update,
                reusableDestinationTlas,
                stalls,
                null,
                new int[0]
        );
    }

    private static PreparedTlasBuild prepareTlas(
            VkDevice device,
            long allocator,
            int scratchAlignmentBytes,
            Collection<TlasInstance> sourceInstances,
            String label,
            RtAccelerationStructure sourceTlas,
            boolean update,
            RtAccelerationStructure reusableDestinationTlas,
            RtStallTelemetrySink stalls,
            PersistentTlasBuildInputs persistentInputs,
            int[] dirtyInstanceSlots
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(sourceInstances, "sourceInstances");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(stalls, "stalls");
        if (update && (sourceTlas == null
                || sourceTlas.type != KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)) {
            throw new IllegalArgumentException("TLAS update requires a live top-level source acceleration structure");
        }
        if (reusableDestinationTlas != null
                && reusableDestinationTlas.type != KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR) {
            throw new IllegalArgumentException("reusable TLAS destination must be top-level");
        }
        if (reusableDestinationTlas != null && reusableDestinationTlas == sourceTlas) {
            throw new IllegalArgumentException("reusable TLAS destination must not alias its update source");
        }
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        List<TlasInstance> instances = RtTlasInstanceEncoder.freeze(sourceInstances);
        if (instances.isEmpty()) {
            throw new IllegalArgumentException(label + " must contain at least one instance");
        }
        int instanceCount = instances.size();
        long instanceBufferBytes = RtAccelerationStructureBuildSupport.checkedMultiply(
                instanceCount, VkAccelerationStructureInstanceKHR.SIZEOF
        );

        RtGpuBuffer instanceBuffer = null;
        RtGpuBuffer scratchBuffer = null;
        /* Passing a reusable destination transfers ownership to this prepare transaction. */
        RtAccelerationStructure tlas = reusableDestinationTlas;
        boolean fullInstanceUpload = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (persistentInputs == null) {
                instanceBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                        device,
                        allocator,
                        instanceBufferBytes,
                        VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        stalls
                );
            } else {
                fullInstanceUpload = persistentInputs.ensureInstanceCapacity(instanceBufferBytes);
                instanceBuffer = persistentInputs.instanceBuffer();
            }
            long requiredScratchBytes;
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    PreparedTlasBuild.instanceGeometry(stack, instanceBuffer);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuffer = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometry.remaining())
                    .pGeometries(geometry);

            IntBuffer maxPrimitiveCounts = stack.ints(instanceCount);
            VkAccelerationStructureBuildSizesInfoKHR buildSizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    maxPrimitiveCounts,
                    buildSizes
            );
            RtAccelerationStructureBuildSupport.validateBuildSizes(buildSizes);

            boolean recycledDestination = tlas != null
                    && tlas.storageBytes() >= buildSizes.accelerationStructureSize();
            if (!recycledDestination) {
                if (tlas != null) {
                    tlas.close();
                    tlas = null;
                }
                tlas = createTopLevel(
                        stack,
                        device,
                        allocator,
                        buildSizes.accelerationStructureSize(),
                        buildSizes.buildScratchSize(),
                        scratchAlignmentBytes,
                        stalls
                );
            }
            requiredScratchBytes = update ? buildSizes.updateScratchSize() : buildSizes.buildScratchSize();
            long persistentScratchBytes = scratchBufferBytes(requiredScratchBytes, scratchAlignmentBytes);
            if (persistentInputs != null) {
                persistentInputs.ensureScratchCapacity(persistentScratchBytes);
                scratchBuffer = persistentInputs.scratchBuffer();
            } else {
                scratchBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                        device,
                        allocator,
                        persistentScratchBytes,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        stalls
                );
            }
            long scratchAddress = alignedScratchAddress(scratchBuffer, scratchAlignmentBytes, requiredScratchBytes);
            PreparedTlasBuild preparedBuild = new PreparedTlasBuild(
                    instanceBuffer,
                    scratchBuffer,
                    tlas,
                    scratchAddress,
                    instances,
                    sourceTlas,
                    update,
                    recycledDestination,
                    persistentInputs,
                    dirtyInstanceSlots,
                    fullInstanceUpload
            );
            instanceBuffer = null;
            scratchBuffer = null;
            tlas = null;
            return preparedBuild;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            if (persistentInputs == null) {
                closeSuppressing(ex, scratchBuffer);
                closeSuppressing(ex, instanceBuffer);
            }
            closeSuppressing(ex, tlas);
            throw ex;
        }
    }

    static RtAccelerationStructure createBottomLevel(
            MemoryStack stack,
            VkDevice device,
            long allocator,
            long storageBytes,
            long buildScratchBytes,
            int scratchAlignmentBytes,
            RtStallTelemetrySink stalls
    ) {
        return createAccelerationStructure(
                stack,
                device,
                allocator,
                storageBytes,
                buildScratchBytes,
                scratchAlignmentBytes,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                "bottom-level acceleration structure",
                stalls
        );
    }

    private static RtAccelerationStructure createTopLevel(
            MemoryStack stack,
            VkDevice device,
            long allocator,
            long storageBytes,
            long buildScratchBytes,
            int scratchAlignmentBytes,
            RtStallTelemetrySink stalls
    ) {
        return createAccelerationStructure(
                stack,
                device,
                allocator,
                storageBytes,
                buildScratchBytes,
                scratchAlignmentBytes,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                "top-level acceleration structure",
                stalls
        );
    }

    private static RtAccelerationStructure createAccelerationStructure(
            MemoryStack stack,
            VkDevice device,
            long allocator,
            long storageBytes,
            long buildScratchBytes,
            int scratchAlignmentBytes,
            int type,
            String label,
            RtStallTelemetrySink stalls
    ) {
        RtGpuBuffer storageBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                storageBytes,
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                stalls
        );
        long handle = 0L;
        try {
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .buffer(storageBuffer.buffer())
                    .offset(0L)
                    .size(storageBytes)
                    .type(type);

            LongBuffer handleBuffer = stack.longs(0L);
            checkVk(
                    KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, createInfo, null, handleBuffer),
                    "vkCreateAccelerationStructureKHR"
            );
            handle = handleBuffer.get(0);

            long deviceAddress = queryDeviceAddress(stack, device, handle);
            if (deviceAddress == 0L) {
                throw new IllegalStateException("vkGetAccelerationStructureDeviceAddressKHR returned null for " + label);
            }

            return new RtAccelerationStructure(
                    device,
                    storageBuffer,
                    handle,
                    type,
                    deviceAddress,
                    buildScratchBytes,
                    scratchAlignmentBytes
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            if (handle != 0L) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, handle, null);
            }
            storageBuffer.close();
            throw ex;
        }
    }

    private static RtGpuBuffer createIndexBuffer(
            VkDevice device,
            long allocator,
            int[] indices,
            RtStallTelemetrySink stalls
    ) {
        if (indices == null) {
            return null;
        }
        return RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                RtAccelerationStructureBuildSupport.checkedMultiply(indices.length, Integer.BYTES),
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                stalls
        );
    }

    private static long queryDeviceAddress(MemoryStack stack, VkDevice device, long accelerationStructure) {
        VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default()
                .accelerationStructure(accelerationStructure);
        return KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, addressInfo);
    }

    private static long scratchBufferBytes(long buildScratchBytes, int scratchAlignmentBytes) {
        return RtAccelerationStructureBuildSupport.checkedAdd(buildScratchBytes, scratchAlignmentBytes);
    }

    private static long alignedScratchAddress(
            RtGpuBuffer scratchBuffer,
            int scratchAlignmentBytes,
            long buildScratchBytes
    ) {
        long base = scratchBuffer.deviceAddress();
        long aligned = RtAccelerationStructureBuildSupport.alignUp(base, scratchAlignmentBytes);
        long padding = aligned - base;
        if (padding < 0L
                || RtAccelerationStructureBuildSupport.checkedAdd(padding, buildScratchBytes)
                > scratchBuffer.sizeBytes()) {
            throw new IllegalStateException("aligned scratch range exceeds scratch buffer");
        }
        return aligned;
    }

    private static void checkVk(int result, String stage) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            default -> Integer.toString(result);
        };
    }

    private static String typeName(int type) {
        return switch (type) {
            case KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR -> "bottomLevel";
            case KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR -> "topLevel";
            default -> Integer.toString(type);
        };
    }

    /**
     * 返回可写入加速结构实例或着色器资源的 Vulkan 设备地址。
     *
     * @return 此加速结构的设备地址
     */
    public long deviceAddress() {
        return deviceAddress;
    }

    /**
     * 返回底层 Vulkan 加速结构句柄。
     *
     * @return Vulkan 加速结构句柄
     */
    public long handle() {
        return accelerationStructure;
    }

    long storageBytes() {
        return storageBuffer.sizeBytes();
    }

    long buildScratchBytes() {
        return buildScratchBytes;
    }

    /**
     * 生成包含句柄、类型、存储容量和构建暂存需求的诊断摘要。
     *
     * @param name 摘要中使用的加速结构名称
     * @return 单行诊断摘要
     */
    public String summary(String name) {
        return name
                + "{handle=0x" + Long.toHexString(accelerationStructure)
                + ", type=" + typeName(type)
                + ", storageBytes=" + storageBuffer.sizeBytes()
                + ", buildScratchBytes=" + buildScratchBytes
                + ", scratchAlign=" + scratchAlignmentBytes
                + ", deviceAddress=0x" + Long.toHexString(deviceAddress)
                + "}";
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, accelerationStructure, null);
        storageBuffer.close();
    }

    /**
     * Cache-owned TLAS build inputs for a serialized high-churn instance lane.
     *
     * <p>The buffer is not descriptor-visible and a TLAS does not retain it after
     * its build fence completes. RtDynamicTlasCache serializes submissions, so the
     * same device-local records, scratch allocation, and 64 KiB command-upload
     * arena can be reused safely across generations. Capacity growth is
     * failure-atomic: replacements are allocated before the previous resources
     * are released.</p>
     */
    static final class PersistentTlasBuildInputs implements AutoCloseable {
        private final VkDevice device;
        private final long allocator;
        private final RtStallTelemetrySink stalls;
        private final ByteBuffer uploadArena;
        private RtGpuBuffer instanceBuffer;
        private RtGpuBuffer scratchBuffer;
        private long fullUploads;
        private long dirtyUploads;
        private long uploadedRecords;
        private long uploadRanges;
        private boolean closed;

        PersistentTlasBuildInputs(VkDevice device, long allocator, RtStallTelemetrySink stalls) {
            this.device = Objects.requireNonNull(device, "device");
            if (allocator == 0L) {
                throw new IllegalArgumentException("allocator must not be null");
            }
            this.allocator = allocator;
            this.stalls = Objects.requireNonNull(stalls, "stalls");
            this.uploadArena = MemoryUtil.memAlloc(RtAccelerationStructureCommandRecorder.MAX_UPDATE_BYTES);
        }

        boolean ensureInstanceCapacity(long instanceBytes) {
            if (closed) {
                throw new IllegalStateException("persistent TLAS build inputs are closed");
            }
            if (instanceBytes <= 0L) {
                throw new IllegalArgumentException("persistent TLAS instance buffer size must be positive");
            }
            boolean replaceInstances = instanceBuffer == null || instanceBuffer.sizeBytes() < instanceBytes;
            if (!replaceInstances) {
                return false;
            }
            RtGpuBuffer nextInstances;
            try {
                nextInstances = RtGpuBuffer.createDeviceAddressBuffer(
                        device,
                        allocator,
                        instanceBytes,
                        VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | KHRAccelerationStructure
                                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        stalls
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                throw ex;
            }
            RtGpuBuffer previousInstances = instanceBuffer;
            instanceBuffer = nextInstances;
            if (previousInstances != null) {
                previousInstances.close();
            }
            return true;
        }

        void ensureScratchCapacity(long scratchBytes) {
            if (closed) {
                throw new IllegalStateException("persistent TLAS build inputs are closed");
            }
            if (scratchBytes <= 0L) {
                throw new IllegalArgumentException("persistent TLAS scratch buffer size must be positive");
            }
            if (scratchBuffer != null && scratchBuffer.sizeBytes() >= scratchBytes) {
                return;
            }
            RtGpuBuffer nextScratch = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    scratchBytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    stalls
            );
            RtGpuBuffer previousScratch = scratchBuffer;
            scratchBuffer = nextScratch;
            if (previousScratch != null) {
                previousScratch.close();
            }
        }

        RtGpuBuffer instanceBuffer() {
            if (closed || instanceBuffer == null) {
                throw new IllegalStateException("persistent TLAS instance buffer is unavailable");
            }
            return instanceBuffer;
        }

        RtGpuBuffer scratchBuffer() {
            if (closed || scratchBuffer == null) {
                throw new IllegalStateException("persistent TLAS scratch buffer is unavailable");
            }
            return scratchBuffer;
        }

        boolean recordInstanceUpload(
                VkCommandBuffer commandBuffer,
                List<TlasInstance> instances,
                int[] dirtySlots,
                boolean fullUpload
        ) {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(instances, "instances");
            RtTlasInstanceEncoder.validateDirtySlots(dirtySlots, instances.size());
            if (RtAccelerationStructureBuildSupport.checkedMultiply(
                    instances.size(), VkAccelerationStructureInstanceKHR.SIZEOF)
                    > instanceBuffer().sizeBytes()) {
                throw new IllegalStateException("persistent TLAS instance table exceeds its GPU buffer");
            }
            if (fullUpload) {
                uploadRanges += recordInstanceRange(commandBuffer, instances, 0, instances.size());
                fullUploads++;
                uploadedRecords += instances.size();
                return true;
            }
            if (dirtySlots.length == 0) {
                return false;
            }
            int rangeStart = dirtySlots[0];
            int previous = rangeStart;
            for (int index = 1; index <= dirtySlots.length; index++) {
                int next = index == dirtySlots.length ? -1 : dirtySlots[index];
                if (next == previous + 1) {
                    previous = next;
                    continue;
                }
                uploadRanges += recordInstanceRange(commandBuffer, instances, rangeStart, previous + 1);
                if (next >= 0) {
                    rangeStart = next;
                    previous = next;
                }
            }
            dirtyUploads++;
            uploadedRecords += dirtySlots.length;
            return true;
        }

        private int recordInstanceRange(
                VkCommandBuffer commandBuffer,
                List<TlasInstance> instances,
                int rangeStart,
                int rangeEnd
        ) {
            int maxRecords = RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(
                    VkAccelerationStructureInstanceKHR.SIZEOF
            );
            int ranges = 0;
            for (int start = rangeStart; start < rangeEnd; start += maxRecords) {
                int count = Math.min(maxRecords, rangeEnd - start);
                int byteCount = Math.multiplyExact(count, VkAccelerationStructureInstanceKHR.SIZEOF);
                uploadArena.clear();
                uploadArena.limit(byteCount);
                VkAccelerationStructureInstanceKHR.Buffer encoded =
                        new VkAccelerationStructureInstanceKHR.Buffer(uploadArena.slice());
                for (int offset = 0; offset < count; offset++) {
                    RtTlasInstanceEncoder.write(encoded.get(offset), instances.get(start + offset));
                }
                ByteBuffer upload = uploadArena.duplicate();
                upload.position(0);
                upload.limit(byteCount);
                RtAccelerationStructureCommandRecorder.recordByteUpload(
                        commandBuffer,
                        instanceBuffer().buffer(),
                        RtAccelerationStructureBuildSupport.checkedMultiply(
                                start, VkAccelerationStructureInstanceKHR.SIZEOF
                        ),
                        upload.slice(),
                        VkAccelerationStructureInstanceKHR.SIZEOF
                );
                ranges++;
            }
            return ranges;
        }

        String summary() {
            return "persistentInputs{instanceBytes=" + (instanceBuffer == null ? 0L : instanceBuffer.sizeBytes())
                    + ", scratchBytes=" + (scratchBuffer == null ? 0L : scratchBuffer.sizeBytes())
                    + ", fullUploads=" + fullUploads
                    + ", dirtyUploads=" + dirtyUploads
                    + ", uploadedRecords=" + uploadedRecords
                    + ", uploadRanges=" + uploadRanges + '}';
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            failure = closeCollecting(failure, scratchBuffer);
            scratchBuffer = null;
            failure = closeCollecting(failure, instanceBuffer);
            instanceBuffer = null;
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(uploadArena));
            if (failure != null) {
                throw failure;
            }
        }
    }

    record SectionBlasBuildResult(SectionTriangleMesh mesh, RtAccelerationStructure blas) {
        SectionBlasBuildResult {
            mesh = Objects.requireNonNull(mesh, "mesh");
            blas = Objects.requireNonNull(blas, "blas");
        }
    }

    static final class SectionBlasBuildSubmission implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final List<PreparedTriangleBlasBuild> preparedBuilds;
        private final int sectionCount;
        private final long triangleCount;
        private final long submittedNanos;
        private boolean closed;
        private boolean resultsReleased;

        private SectionBlasBuildSubmission(
                RtCommandContext.AsyncSubmission submission,
                List<PreparedTriangleBlasBuild> preparedBuilds,
                long submittedNanos
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.preparedBuilds = List.copyOf(preparedBuilds);
            this.submittedNanos = submittedNanos;
            int sections = 0;
            long triangles = 0L;
            for (PreparedTriangleBlasBuild preparedBuild : preparedBuilds) {
                SectionTriangleMesh mesh = preparedBuild.mesh();
                sections++;
                triangles += mesh.triangleCount();
            }
            this.sectionCount = sections;
            this.triangleCount = triangles;
        }

        int sectionCount() {
            return sectionCount;
        }

        long triangleCount() {
            return triangleCount;
        }

        CompletedSectionBlasBuild completeIfReady() {
            if (closed) {
                throw new IllegalStateException("section BLAS build submission is already closed");
            }
            if (!submission.pollComplete()) {
                return null;
            }

            List<SectionBlasBuildResult> results = new ArrayList<>(preparedBuilds.size());
            try {
                RtCommandContext.Timing completionTiming = submission.timing();
                results.addAll(releaseSectionBlasResults(preparedBuilds));
                resultsReleased = true;
                closePreparedBuilds(preparedBuilds);
                closed = true;
                return new CompletedSectionBlasBuild(
                        List.copyOf(results),
                        sectionCount,
                        triangleCount,
                        System.nanoTime() - submittedNanos,
                        completionTiming.gpuWorkNanos(),
                        completionTiming.lastNotReadyToObservationNanos(),
                        completionTiming.notReadyPolls()
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeReleasedBlasesSuppressing(ex, results);
                closePreparedBuildsSuppressing(ex, preparedBuilds);
                closed = true;
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                submission.close();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            if (!resultsReleased) {
                failure = closePreparedBuildsCollecting(failure, preparedBuilds);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class RecordedSectionBlasBuild implements AutoCloseable {
        private final RtCommandContext.RecordedCommandBuffer recording;
        private final List<PreparedTriangleBlasBuild> preparedBuilds;
        private boolean submitted;
        private boolean closed;

        private RecordedSectionBlasBuild(
                RtCommandContext.RecordedCommandBuffer recording,
                List<PreparedTriangleBlasBuild> preparedBuilds
        ) {
            this.recording = Objects.requireNonNull(recording, "recording");
            this.preparedBuilds = List.copyOf(preparedBuilds);
        }

        SectionBlasBuildSubmission submit(RtCommandContext commandContext) {
            if (closed || submitted) {
                throw new IllegalStateException("recorded section BLAS build is no longer submit-ready");
            }
            try {
                RtCommandContext.AsyncSubmission submission = commandContext.submitRecordedAsync(List.of(recording));
                submitted = true;
                return new SectionBlasBuildSubmission(submission, preparedBuilds, System.nanoTime());
            } catch (RuntimeException | Error ex) {
                close();
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            if (!submitted) {
                try {
                    recording.close();
                } catch (RuntimeException ex) {
                    failure = ex;
                }
            }
            failure = closePreparedBuildsCollecting(failure, preparedBuilds);
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class FarFieldBlasBuildSubmission implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final PreparedTriangleBlasBuild preparedBuild;
        private final RtFarFieldProxyMeshBuilder.ProxyMesh mesh;
        private final long submittedNanos;
        private boolean closed;
        private boolean resultReleased;

        private FarFieldBlasBuildSubmission(
                RtCommandContext.AsyncSubmission submission,
                PreparedTriangleBlasBuild preparedBuild,
                RtFarFieldProxyMeshBuilder.ProxyMesh mesh,
                long submittedNanos
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.preparedBuild = Objects.requireNonNull(preparedBuild, "preparedBuild");
            this.mesh = Objects.requireNonNull(mesh, "mesh");
            this.submittedNanos = submittedNanos;
        }

        CompletedFarFieldBlasBuild completeIfReady() {
            if (closed) {
                throw new IllegalStateException("far-field BLAS build submission is already closed");
            }
            if (!submission.pollComplete()) {
                return null;
            }

            RtAccelerationStructure blas = null;
            try {
                blas = preparedBuild.releaseAccelerationStructure();
                resultReleased = true;
                preparedBuild.close();
                closed = true;
                return new CompletedFarFieldBlasBuild(blas, mesh, System.nanoTime() - submittedNanos);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeSuppressing(ex, blas);
                closeSuppressing(ex, preparedBuild);
                closed = true;
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                submission.close();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            if (!resultReleased) {
                failure = closeCollecting(failure, preparedBuild);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class DynamicBlasBuildSubmission implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final PreparedTriangleBlasBuild preparedBuild;
        private final RtDynamicTriangleMesh mesh;
        private final long submittedNanos;
        private boolean closed;
        private boolean resultReleased;

        private DynamicBlasBuildSubmission(
                RtCommandContext.AsyncSubmission submission,
                PreparedTriangleBlasBuild preparedBuild,
                RtDynamicTriangleMesh mesh,
                long submittedNanos
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.preparedBuild = Objects.requireNonNull(preparedBuild, "preparedBuild");
            this.mesh = Objects.requireNonNull(mesh, "mesh");
            this.submittedNanos = submittedNanos;
        }

        RtDynamicTriangleMesh mesh() {
            return mesh;
        }

        CompletedDynamicBlasBuild completeIfReady() {
            if (closed) {
                throw new IllegalStateException("dynamic BLAS build submission is already closed");
            }
            if (!submission.pollComplete()) {
                return null;
            }

            RtAccelerationStructure blas = null;
            try {
                blas = preparedBuild.releaseAccelerationStructure();
                resultReleased = true;
                preparedBuild.close();
                closed = true;
                return new CompletedDynamicBlasBuild(blas, mesh, System.nanoTime() - submittedNanos);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeSuppressing(ex, blas);
                closeSuppressing(ex, preparedBuild);
                closed = true;
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                submission.close();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            if (!resultReleased) {
                failure = closeCollecting(failure, preparedBuild);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class WorldTlasBuildSubmission implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final PreparedTlasBuild preparedBuild;
        private final long submittedNanos;
        private boolean closed;
        private boolean resultReleased;

        private WorldTlasBuildSubmission(
                RtCommandContext.AsyncSubmission submission,
                PreparedTlasBuild preparedBuild,
                long submittedNanos
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.preparedBuild = Objects.requireNonNull(preparedBuild, "preparedBuild");
            this.submittedNanos = submittedNanos;
        }

        CompletedWorldTlasBuild completeIfReady() {
            if (closed) {
                throw new IllegalStateException("world TLAS build submission is already closed");
            }
            if (!submission.pollComplete()) {
                return null;
            }

            RtAccelerationStructure result = null;
            try {
                int instanceCount = preparedBuild.instanceCount();
                boolean update = preparedBuild.update();
                long sourceHandle = preparedBuild.sourceHandle();
                long instanceBufferBytes = preparedBuild.instanceBufferBytes();
                long scratchBufferBytes = preparedBuild.scratchBufferBytes();
                boolean recycledDestination = preparedBuild.recycledDestination();
                result = preparedBuild.releaseAccelerationStructure();
                resultReleased = true;
                preparedBuild.close();
                closed = true;
                return new CompletedWorldTlasBuild(
                        result,
                        instanceCount,
                        update,
                        sourceHandle,
                        instanceBufferBytes,
                        scratchBufferBytes,
                        recycledDestination,
                        System.nanoTime() - submittedNanos
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeSuppressing(ex, result);
                closeSuppressing(ex, preparedBuild);
                closed = true;
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                submission.close();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            if (!resultReleased) {
                failure = closeCollecting(failure, preparedBuild);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    record CompletedWorldTlasBuild(
            RtAccelerationStructure accelerationStructure,
            int instanceCount,
            boolean update,
            long sourceHandle,
            long instanceBufferBytes,
            long scratchBufferBytes,
            boolean recycledDestination,
            long elapsedNanos
    ) {
        CompletedWorldTlasBuild {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            if (instanceCount <= 0) {
                throw new IllegalArgumentException("completed TLAS instance count must be positive");
            }
            if (update != (sourceHandle != 0L)) {
                throw new IllegalArgumentException("completed TLAS update source must match update mode");
            }
            if (instanceBufferBytes <= 0L || scratchBufferBytes <= 0L) {
                throw new IllegalArgumentException("completed TLAS transient buffers must be positive");
            }
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("completed TLAS elapsed time must not be negative");
            }
        }
    }

    record CompletedSectionBlasBuild(
            List<SectionBlasBuildResult> results,
            int sectionCount,
            long triangleCount,
            long elapsedNanos,
            long gpuExecutionNanos,
            long lastNotReadyToObservationNanos,
            long notReadyPolls
    ) {
        CompletedSectionBlasBuild {
            results = List.copyOf(results);
            if (sectionCount <= 0) {
                throw new IllegalArgumentException("completed BLAS section count must be positive");
            }
            if (triangleCount <= 0L) {
                throw new IllegalArgumentException("completed BLAS triangle count must be positive");
            }
            if (elapsedNanos < 0L || gpuExecutionNanos < -1L
                    || lastNotReadyToObservationNanos < 0L || notReadyPolls < 0L) {
                throw new IllegalArgumentException("completed BLAS timings must be valid");
            }
        }
    }

    record CompletedDynamicBlasBuild(
            RtAccelerationStructure accelerationStructure,
            RtDynamicTriangleMesh mesh,
            long elapsedNanos
    ) {
        CompletedDynamicBlasBuild {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            mesh = Objects.requireNonNull(mesh, "mesh");
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("completed dynamic BLAS elapsed time must not be negative");
            }
        }
    }

    record CompletedFarFieldBlasBuild(
            RtAccelerationStructure accelerationStructure,
            RtFarFieldProxyMeshBuilder.ProxyMesh mesh,
            long elapsedNanos
    ) {
        CompletedFarFieldBlasBuild {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            mesh = Objects.requireNonNull(mesh, "mesh");
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("completed far-field BLAS elapsed time must not be negative");
            }
        }
    }

    /**
     * 描述一个可直接编码为 Vulkan TLAS 实例记录的 BLAS 实例。
     *
     * <p>变换按三行四列的行主序保存；自定义索引和可见性掩码分别受 Vulkan
     * 实例记录的 24 位与 8 位字段宽度约束。</p>
     *
     * @param blasDeviceAddress 被引用 BLAS 的非零设备地址
     * @param m00               变换矩阵第零行第零列
     * @param m01               变换矩阵第零行第一列
     * @param m02               变换矩阵第零行第二列
     * @param m03               变换矩阵第零行平移分量
     * @param m10               变换矩阵第一行第零列
     * @param m11               变换矩阵第一行第一列
     * @param m12               变换矩阵第一行第二列
     * @param m13               变换矩阵第一行平移分量
     * @param m20               变换矩阵第二行第零列
     * @param m21               变换矩阵第二行第一列
     * @param m22               变换矩阵第二行第二列
     * @param m23               变换矩阵第二行平移分量
     * @param customIndex       可供着色器读取的 24 位无符号实例索引
     * @param visibilityMask    用于光线实例筛选的 8 位无符号掩码
     */
    public record TlasInstance(
            long blasDeviceAddress,
            float m00, float m01, float m02, float m03,
            float m10, float m11, float m12, float m13,
            float m20, float m21, float m22, float m23,
            int customIndex,
            int visibilityMask
    ) {
        /**
         * 校验 BLAS 地址、变换矩阵以及 Vulkan 位域范围。
         *
         * @throws IllegalArgumentException 如果 BLAS 地址为零、任一矩阵分量非有限，
         *                                  或索引、掩码超出各自位域范围
         */
        public TlasInstance {
            if (blasDeviceAddress == 0L) {
                throw new IllegalArgumentException("TLAS instance BLAS device address must not be null");
            }
            if (!Float.isFinite(m00) || !Float.isFinite(m01) || !Float.isFinite(m02) || !Float.isFinite(m03)
                    || !Float.isFinite(m10) || !Float.isFinite(m11) || !Float.isFinite(m12) || !Float.isFinite(m13)
                    || !Float.isFinite(m20) || !Float.isFinite(m21) || !Float.isFinite(m22) || !Float.isFinite(m23)) {
                throw new IllegalArgumentException("TLAS instance transform must be finite");
            }
            if (customIndex < 0 || customIndex > MAX_INSTANCE_CUSTOM_INDEX) {
                throw new IllegalArgumentException("TLAS instance custom index outside 24-bit range: " + customIndex);
            }
            if (visibilityMask < INSTANCE_MASK_NONE || visibilityMask > INSTANCE_MASK_ALL) {
                throw new IllegalArgumentException("TLAS instance visibility mask outside 8-bit range: " + visibilityMask);
            }
        }

        TlasInstance(
                long blasDeviceAddress,
                DynamicMeshInstance.AffineTransform transform,
                int customIndex,
                int visibilityMask
        ) {
            this(
                    blasDeviceAddress,
                    Objects.requireNonNull(transform, "transform").m00(), transform.m01(), transform.m02(), transform.m03(),
                    transform.m10(), transform.m11(), transform.m12(), transform.m13(),
                    transform.m20(), transform.m21(), transform.m22(), transform.m23(),
                    customIndex,
                    visibilityMask
            );
        }

        TlasInstance(
                long blasDeviceAddress,
                DynamicMeshInstance.AffineTransform transform,
                int customIndex
        ) {
            this(blasDeviceAddress, transform, customIndex, INSTANCE_MASK_ALL);
        }

        TlasInstance(
                long blasDeviceAddress,
                float translateX,
                float translateY,
                float translateZ,
                int customIndex
        ) {
            this(
                    blasDeviceAddress,
                    1.0F, 0.0F, 0.0F, translateX,
                    0.0F, 1.0F, 0.0F, translateY,
                    0.0F, 0.0F, 1.0F, translateZ,
                    customIndex,
                    INSTANCE_MASK_ALL
            );
        }

        /**
         * 创建使用单位变换、默认自定义索引和全可见掩码的 TLAS 实例。
         *
         * @param blasDeviceAddress 被引用 BLAS 的非零设备地址
         * @return 单位变换 TLAS 实例
         * @throws IllegalArgumentException 如果 {@code blasDeviceAddress} 为零
         */
        public static TlasInstance identity(long blasDeviceAddress) {
            return new TlasInstance(blasDeviceAddress, 0.0f, 0.0f, 0.0f, 0);
        }

        static TlasInstance inactive(long placeholderBlasDeviceAddress) {
            return new TlasInstance(
                    placeholderBlasDeviceAddress,
                    1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F, 0.0F,
                    0,
                    INSTANCE_MASK_NONE
            );
        }

        static TlasInstance section(SectionKey key, long blasDeviceAddress, int customIndex) {
            Objects.requireNonNull(key, "key");
            return new TlasInstance(
                    blasDeviceAddress,
                    key.x() * (float) SECTION_EDGE_BLOCKS,
                    key.y() * (float) SECTION_EDGE_BLOCKS,
                    key.z() * (float) SECTION_EDGE_BLOCKS,
                    customIndex
            );
        }

        float translateX() {
            return m03;
        }

        float translateY() {
            return m13;
        }

        float translateZ() {
            return m23;
        }

        /**
         * 按行主序读取三行四列实例变换的一个分量。
         *
         * @param index 变换分量索引，范围为 {@code 0} 至 {@code 11}
         * @return 指定索引处的变换分量
         * @throws IndexOutOfBoundsException 如果索引不在 {@code [0, 11]} 内
         */
        public float transformValue(int index) {
            return switch (index) {
                case 0 -> m00;
                case 1 -> m01;
                case 2 -> m02;
                case 3 -> m03;
                case 4 -> m10;
                case 5 -> m11;
                case 6 -> m12;
                case 7 -> m13;
                case 8 -> m20;
                case 9 -> m21;
                case 10 -> m22;
                case 11 -> m23;
                default -> throw new IndexOutOfBoundsException(index);
            };
        }

        /**
         * 返回供诊断与测试使用的仿射变换兼容视图。
         *
         * <p>GPU 上传路径直接读取本记录的原始矩阵分量，不依赖此临时对象。</p>
         *
         * @return 由当前矩阵分量构造的仿射变换
         */
        public DynamicMeshInstance.AffineTransform transform() {
            return new DynamicMeshInstance.AffineTransform(
                    m00, m01, m02, m03,
                    m10, m11, m12, m13,
                    m20, m21, m22, m23
            );
        }
    }
}
