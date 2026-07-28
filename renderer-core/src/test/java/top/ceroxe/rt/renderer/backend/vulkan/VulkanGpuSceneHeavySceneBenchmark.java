package top.ceroxe.rt.renderer.backend.vulkan;

import com.sun.management.ThreadMXBean;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Deterministic heavy-scene CPU/provider pressure lane for incremental GPUScene admission.
 *
 * <p>The workload deliberately keeps a large resident world while mutating one region at a time,
 * then exercises removal and burst churn. It measures only the provider chain after each immutable
 * transaction has been constructed, so asset-builder cost cannot hide a full-world validation,
 * allocation-map copy, or upload-planner amplification.</p>
 */
public final class VulkanGpuSceneHeavySceneBenchmark {
    private static final int RESIDENT_REGIONS = Integer.getInteger(
            "top.ceroxe.rt.gpuSceneHeavyScene.residentRegions", 4_096
    );
    private static final int SINGLE_REGION_UPDATES = Integer.getInteger(
            "top.ceroxe.rt.gpuSceneHeavyScene.singleRegionUpdates", 128
    );
    private static final int SHADING_UPDATES = Integer.getInteger(
            "top.ceroxe.rt.gpuSceneHeavyScene.shadingUpdates", 128
    );
    private static final int BURST_REGIONS = Integer.getInteger(
            "top.ceroxe.rt.gpuSceneHeavyScene.burstRegions", 256
    );
    private static final long MATERIAL_A = 1L;
    private static final long MATERIAL_B = 2L;
    private static final long LIGHT = 3L;
    private static final long MESH_BASE = 10_000L;
    private static final long INSTANCE_BASE = 1_000_000L;
    private static final long BURST_MESH_BASE = 2_000_000L;
    private static final long BURST_INSTANCE_BASE = 3_000_000L;

    private VulkanGpuSceneHeavySceneBenchmark() {
    }

    public static void main(String[] arguments) {
        require(RESIDENT_REGIONS >= 1_024, "heavy-scene benchmark requires at least 1024 resident regions");
        Pipeline pipeline = new Pipeline();
        ArrayList<Sample> measured = new ArrayList<>();
        long revision = 0L;

        Sample bootstrap = pipeline.apply(bootstrap(revision++), Workload.BOOTSTRAP);
        for (int index = 0; index < SINGLE_REGION_UPDATES; index++) {
            int region = index % RESIDENT_REGIONS;
            measured.add(pipeline.apply(
                    SceneTransaction.builder(revision++)
                            .upsert(mesh(MESH_BASE + region, MATERIAL_A, RESIDENT_REGIONS + index + 1.0F))
                            .build(),
                    Workload.GEOMETRY
            ));
        }
        for (int index = 0; index < SHADING_UPDATES; index++) {
            int region = index % RESIDENT_REGIONS;
            long material = (index & 1) == 0 ? MATERIAL_B : MATERIAL_A;
            measured.add(pipeline.apply(
                    SceneTransaction.builder(revision++)
                            .upsert(mesh(
                                    MESH_BASE + region,
                                    material,
                                    RESIDENT_REGIONS + region + 1.0F
                            ))
                            .upsert(light(index))
                            .build(),
                    Workload.SHADING
            ));
        }

        measured.add(pipeline.apply(
                SceneTransaction.builder(revision++)
                        .removeInstance(INSTANCE_BASE)
                        .removeMesh(MESH_BASE)
                        .build(),
                Workload.DIGGING
        ));

        SceneTransaction.Builder load = SceneTransaction.builder(revision++);
        for (int index = 0; index < BURST_REGIONS; index++) {
            load.upsert(mesh(BURST_MESH_BASE + index, MATERIAL_A, index + 0.5F));
            load.upsert(SceneInstance.builder(BURST_INSTANCE_BASE + index, BURST_MESH_BASE + index).build());
        }
        measured.add(pipeline.apply(load.build(), Workload.BURST_LOAD));

        SceneTransaction.Builder unload = SceneTransaction.builder(revision++);
        for (int index = 0; index < BURST_REGIONS; index++) {
            unload.removeInstance(BURST_INSTANCE_BASE + index);
            unload.removeMesh(BURST_MESH_BASE + index);
        }
        measured.add(pipeline.apply(unload.build(), Workload.BURST_UNLOAD));

        Sample cleanup = pipeline.apply(
                SceneTransaction.builder(revision).resetScene().build(),
                Workload.CLEANUP
        );
        Report report = Report.from(bootstrap, measured, cleanup);
        verify(report, pipeline.memory.state());
        System.out.println("VulkanGpuSceneHeavySceneBenchmark passed: " + report);
    }

    private static SceneTransaction bootstrap(long revision) {
        SceneTransaction.Builder builder = SceneTransaction.builder(revision)
                .resetScene()
                .upsert(material(MATERIAL_A, 0xffb0b0b0))
                .upsert(material(MATERIAL_B, 0xff60a0ff))
                .upsert(light(0));
        for (int index = 0; index < RESIDENT_REGIONS; index++) {
            long meshId = MESH_BASE + index;
            builder.upsert(mesh(meshId, MATERIAL_A, index + 1.0F));
            builder.upsert(SceneInstance.builder(INSTANCE_BASE + index, meshId).build());
        }
        return builder.build();
    }

    private static MaterialAsset material(long id, int rgba8) {
        return MaterialAsset.builder(id).baseColorRgba8(rgba8).roughness(0.8F).build();
    }

    private static SceneLight light(int generation) {
        return SceneLight.point(LIGHT, generation & 31, 12.0, generation >>> 5)
                .color(1.0F, 0.85F, 0.65F)
                .intensity(8.0F + generation)
                .range(24.0F)
                .build();
    }

    private static MeshAsset mesh(long id, long materialId, float offset) {
        float x = offset * 0.001F;
        return MeshAsset.builder(
                id,
                new float[]{x, 0.0F, 0.0F, x + 1.0F, 0.0F, 0.0F, x, 1.0F, 0.0F, x + 1.0F, 1.0F, 0.0F},
                new int[]{0, 1, 2, 2, 1, 3},
                new long[]{materialId, materialId}
        ).normals(new float[]{
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F
        }).build();
    }

    private static void verify(Report report, VulkanGpuSceneMemory.State memory) {
        require(report.samples > 0, "heavy-scene benchmark recorded no incremental transactions");
        require(report.maxDirtyMeshes <= BURST_REGIONS, "incremental workload expanded mesh dirty scope");
        require(report.maxBlasBuilds <= BURST_REGIONS, "shading work was amplified into BLAS work");
        require(report.shadingBlasBuilds == 0L, "shading-only transactions requested BLAS builds");
        require(report.maxIncrementalUploadRanges <= 32,
                "single-delta upload ranges did not coalesce: " + report.maxIncrementalUploadRanges);
        require(report.maxAllocatedBytesPerTransaction < 32L * 1024L * 1024L,
                "one incremental transaction allocated more than 32 MiB: " + report.maxAllocatedBytesPerTransaction);
        require(report.p95Millis < 100.0, "incremental apply P95 exceeded 100 ms: " + report.p95Millis);
        require(memory.positions().liveBytes() == 0L && memory.indices().liveBytes() == 0L
                        && memory.triangleMaterialSlots().liveBytes() == 0L,
                "cleanup left live geometry allocations");
        require(memory.positions().pendingRetiredBytes() == 0L && memory.indices().pendingRetiredBytes() == 0L
                        && memory.triangleMaterialSlots().pendingRetiredBytes() == 0L,
                "cleanup left deferred geometry retirement debt");
    }

    private enum Workload {
        BOOTSTRAP,
        GEOMETRY,
        SHADING,
        DIGGING,
        BURST_LOAD,
        BURST_UNLOAD,
        CLEANUP
    }

    private record Sample(
            Workload workload,
            long nanos,
            long allocatedBytes,
            long uploadBytes,
            int uploadRanges,
            int meshWrites,
            int materialWrites,
            int instanceWrites,
            int blasBuilds
    ) {
    }

    private record Report(
            int samples,
            double p50Millis,
            double p95Millis,
            double secondMaxMillis,
            double maxMillis,
            long medianAllocatedBytes,
            long maxAllocatedBytesPerTransaction,
            long totalUploadBytes,
            int maxUploadRanges,
            int maxIncrementalUploadRanges,
            int maxDirtyMeshes,
            int maxDirtyMaterials,
            int maxDirtyInstances,
            int maxBlasBuilds,
            long totalBlasBuilds,
            long shadingBlasBuilds,
            long cleanupUploadBytes
    ) {
        private static Report from(Sample bootstrap, List<Sample> measured, Sample cleanup) {
            long[] latencies = measured.stream().mapToLong(Sample::nanos).sorted().toArray();
            long[] allocations = measured.stream().mapToLong(Sample::allocatedBytes).sorted().toArray();
            long totalUploadBytes = bootstrap.uploadBytes + cleanup.uploadBytes;
            int maxRanges = 0;
            int maxIncrementalRanges = 0;
            int maxMeshes = 0;
            int maxMaterials = 0;
            int maxInstances = 0;
            int maxBlasBuilds = 0;
            long blasBuilds = 0L;
            long shadingBlasBuilds = 0L;
            for (Sample sample : measured) {
                totalUploadBytes += sample.uploadBytes;
                maxRanges = Math.max(maxRanges, sample.uploadRanges);
                if (sample.workload != Workload.BURST_LOAD && sample.workload != Workload.BURST_UNLOAD) {
                    maxIncrementalRanges = Math.max(maxIncrementalRanges, sample.uploadRanges);
                }
                maxMeshes = Math.max(maxMeshes, sample.meshWrites);
                maxMaterials = Math.max(maxMaterials, sample.materialWrites);
                maxInstances = Math.max(maxInstances, sample.instanceWrites);
                maxBlasBuilds = Math.max(maxBlasBuilds, sample.blasBuilds);
                blasBuilds += sample.blasBuilds;
                if (sample.workload == Workload.SHADING) shadingBlasBuilds += sample.blasBuilds;
            }
            return new Report(
                    measured.size(), millis(percentile(latencies, 0.50)), millis(percentile(latencies, 0.95)),
                    millis(latencies[Math.max(0, latencies.length - 2)]), millis(latencies[latencies.length - 1]),
                    percentile(allocations, 0.50), allocations[allocations.length - 1], totalUploadBytes,
                    maxRanges, maxIncrementalRanges, maxMeshes, maxMaterials, maxInstances, maxBlasBuilds,
                    blasBuilds, shadingBlasBuilds,
                    cleanup.uploadBytes
            );
        }

        private static long percentile(long[] sorted, double percentile) {
            int rank = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
            return sorted[rank];
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private static final class Pipeline {
        private final PersistentSceneRegistry registry = new PersistentSceneRegistry();
        private final VulkanSceneResidency residency = new VulkanSceneResidency();
        private final VulkanGpuSceneMemory memory = new VulkanGpuSceneMemory();
        private final VulkanGpuSceneIdentityIndex identities = new VulkanGpuSceneIdentityIndex();
        private final EnumMap<VulkanGpuSceneUploadPlanner.Target, Long> capacities =
                new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
        private final ThreadMXBean allocationBean;
        private final long threadId = Thread.currentThread().threadId();

        private Pipeline() {
            java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
            allocationBean = platform instanceof ThreadMXBean bean ? bean : null;
            if (allocationBean != null && allocationBean.isThreadAllocatedMemorySupported()
                    && !allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
        }

        private Sample apply(SceneTransaction transaction, Workload workload) {
            long allocatedBefore = allocatedBytes();
            long started = System.nanoTime();
            PersistentSceneRegistry.PreparedMutation registryUpdate = registry.prepare(transaction);
            VulkanSceneResidency.PreparedUpdate residentUpdate = residency.prepare(transaction);
            VulkanSceneResidency.SceneChangeSet changes = residentUpdate.changeSet();
            VulkanGpuSceneMemory.Prepared memoryUpdate = memory.prepare(changes);
            VulkanGpuSceneIdentityIndex.Prepared identityUpdate = identities.prepare(changes);
            VulkanGpuSceneUploadPlanner.Plan upload = VulkanGpuSceneUploadPlanner.plan(
                    changes, memoryUpdate, identityUpdate
            );
            VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(
                    upload, target -> capacities.getOrDefault(target, 0L)
            );

            registry.validate(registryUpdate);
            residency.validate(residentUpdate);
            memory.validate(memoryUpdate, transaction.revision());
            identities.validate(identityUpdate);
            memory.commitValidated(memoryUpdate, transaction.revision());
            identities.commitValidated(identityUpdate);
            residency.commitValidated(residentUpdate);
            registry.commitValidated(registryUpdate);
            memory.releaseThrough(transaction.revision());
            for (VulkanGpuSceneTransferPlan.TargetCapacity target : transfer.targets()) {
                capacities.put(target.target(), target.capacityBytes());
            }
            long nanos = System.nanoTime() - started;
            long allocated = Math.max(0L, allocatedBytes() - allocatedBefore);
            return new Sample(
                    workload, nanos, allocated, upload.uploadBytes(), upload.chunks().size(),
                    changes.meshes().statistics().writes(), changes.materials().statistics().writes(),
                    changes.instances().statistics().writes(), changes.meshUpdates().blasDirtyCount()
            );
        }

        private long allocatedBytes() {
            return allocationBean == null || !allocationBean.isThreadAllocatedMemoryEnabled()
                    ? 0L
                    : allocationBean.getThreadAllocatedBytes(threadId);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
