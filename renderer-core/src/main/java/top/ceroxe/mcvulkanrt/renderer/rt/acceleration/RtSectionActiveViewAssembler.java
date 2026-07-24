package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes one immutable active Base/FarField view from already-admitted resources.
 *
 * <p>This owner deliberately does not know about scene revisions, admission policy, fences, or
 * publication.  Its only mutable state is allocation-throttling caches for stable section keys,
 * BLAS addresses, and material slots.  Keeping those caches here makes the ownership boundary
 * explicit: {@link RtSectionBlasCache} decides <em>what</em> is admitted, while this class decides
 * <em>how</em> that admission becomes a deterministic TLAS instance array.</p>
 */
final class RtSectionActiveViewAssembler {
    private static final int MAX_CACHE_ENTRIES = 65_536;

    private final PackedSectionMembership.Builder coverageBuilder = PackedSectionMembership.builder(0);
    private ActiveInstanceIdentity[] activeIdentities = new ActiveInstanceIdentity[0];
    private final Map<Object, ActiveInstanceIdentity> identityCache = new HashMap<>();
    private final Map<SectionKey, Map.Entry<SectionKey, RtAccelerationStructure>> baseEntryCache =
            new HashMap<>();
    private final Map<ActiveInstanceIdentity, RtAccelerationStructure.TlasInstance> tlasInstanceCache =
            new LinkedHashMap<>();
    @SuppressWarnings("unchecked")
    private Map.Entry<SectionKey, RtAccelerationStructure>[] baseEntriesByMaterialSlot =
            (Map.Entry<SectionKey, RtAccelerationStructure>[]) new Map.Entry<?, ?>[0];
    private int[] touchedMaterialSlots = new int[0];
    private int touchedMaterialSlotCount;

    Assembly assemble(
            Collection<SectionKey> admittedBaseSections,
            Map<SectionKey, RtAccelerationStructure> sectionBlases,
            RtSectionMaterialPublicationState materialState,
            Collection<RtFarFieldBlasCache.ActiveCell> activeFarFieldCells,
            Collection<RtSectionInstanceAdmission.FarFieldCell> admittedFarFieldCells,
            PackedSectionMembership previousCoverage
    ) {
        Objects.requireNonNull(admittedBaseSections, "admittedBaseSections");
        Objects.requireNonNull(sectionBlases, "sectionBlases");
        Objects.requireNonNull(materialState, "materialState");
        Objects.requireNonNull(activeFarFieldCells, "activeFarFieldCells");
        Objects.requireNonNull(admittedFarFieldCells, "admittedFarFieldCells");
        Objects.requireNonNull(previousCoverage, "previousCoverage");

        long collectStart = System.nanoTime();
        clearTouchedSlots();
        ensureSlotCapacity(materialState.slotCount(), admittedBaseSections.size());
        for (SectionKey key : admittedBaseSections) {
            Objects.requireNonNull(key, "admitted base section");
            RtAccelerationStructure blas = sectionBlases.get(key);
            if (blas == null) {
                continue;
            }
            Integer materialSlot = materialState.slotFor(key);
            if (materialSlot == null) {
                throw new IllegalStateException("missing RT section material slot for " + key);
            }
            if (baseEntriesByMaterialSlot[materialSlot] != null) {
                throw new IllegalStateException("multiple active sections share material slot " + materialSlot);
            }
            baseEntriesByMaterialSlot[materialSlot] = cachedBaseEntry(key, blas);
            touchedMaterialSlots[touchedMaterialSlotCount++] = materialSlot;
        }
        List<Map.Entry<SectionKey, RtAccelerationStructure>> baseEntries = new ArrayList<>(
                touchedMaterialSlotCount
        );
        for (int materialSlot = 0; materialSlot < materialState.slotCount(); materialSlot++) {
            Map.Entry<SectionKey, RtAccelerationStructure> entry = baseEntriesByMaterialSlot[materialSlot];
            if (entry != null) {
                baseEntries.add(entry);
            }
        }

        List<RtFarFieldBlasCache.ActiveCell> farFieldCells = new ArrayList<>(activeFarFieldCells);
        farFieldCells.sort(Comparator.comparingInt(RtFarFieldBlasCache.ActiveCell::materialSlot));
        int instanceCount = Math.addExact(baseEntries.size(), farFieldCells.size());
        coverageBuilder.reset(instanceCount);
        ActiveInstanceIdentity[] nextIdentities = new ActiveInstanceIdentity[instanceCount];
        int identityIndex = 0;
        for (int materialSlot = 0; materialSlot < materialState.slotCount(); materialSlot++) {
            Map.Entry<SectionKey, RtAccelerationStructure> entry = baseEntriesByMaterialSlot[materialSlot];
            if (entry == null) {
                continue;
            }
            nextIdentities[identityIndex++] = cachedIdentity(
                    entry.getKey(), entry.getValue().deviceAddress(), materialSlot
            );
        }
        int farFieldMaterialSlotOffset = materialState.slotCount();
        for (RtFarFieldBlasCache.ActiveCell farFieldCell : farFieldCells) {
            nextIdentities[identityIndex++] = cachedIdentity(
                    farFieldCell.cell().key(),
                    farFieldCell.blas().deviceAddress(),
                    Math.addExact(farFieldMaterialSlotOffset, farFieldCell.materialSlot())
            );
        }
        if (identityIndex != nextIdentities.length) {
            throw new IllegalStateException("active instance identity count must match Base/FarField entries");
        }
        long collectSortNanos = System.nanoTime() - collectStart;

        long coverageStart = System.nanoTime();
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : baseEntries) {
            addCoverage(entry.getKey().packed());
        }
        for (RtFarFieldBlasCache.ActiveCell farFieldCell : farFieldCells) {
            for (SectionKey sourceSection : farFieldCell.cell().sourceSections()) {
                addCoverage(sourceSection.packed());
            }
        }
        PackedSectionMembership coveredSections = coverageBuilder.buildCanonical(previousCoverage);
        long coverageNanos = System.nanoTime() - coverageStart;

        List<RtAccelerationStructure.TlasInstance> instances = new ArrayList<>(instanceCount);
        identityIndex = 0;
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : baseEntries) {
            ActiveInstanceIdentity identity = nextIdentities[identityIndex++];
            instances.add(cachedSectionInstance(identity, entry.getKey(), entry.getValue().deviceAddress()));
        }
        for (RtFarFieldBlasCache.ActiveCell farFieldCell : farFieldCells) {
            ActiveInstanceIdentity identity = nextIdentities[identityIndex++];
            RtFarFieldProxyMeshBuilder.ProxyMesh proxyMesh = farFieldCell.proxyMesh();
            instances.add(cachedFarFieldInstance(
                    identity,
                    farFieldCell.blas().deviceAddress(),
                    proxyMesh.originBlockX(),
                    proxyMesh.originBlockY(),
                    proxyMesh.originBlockZ()
            ));
        }
        if (identityIndex != nextIdentities.length) {
            throw new IllegalStateException("active TLAS identity count must match emitted instances");
        }

        trimCachesIfNeeded();
        boolean identityChanged = !Arrays.equals(nextIdentities, activeIdentities);
        int added = identityChanged ? differenceCount(nextIdentities, activeIdentities) : 0;
        int removed = identityChanged ? differenceCount(activeIdentities, nextIdentities) : 0;
        activeIdentities = nextIdentities;
        Snapshot snapshot = Snapshot.freeze(
                baseEntries,
                farFieldCells,
                admittedFarFieldCells,
                coveredSections,
                instances,
                sectionInstanceLayoutHash(instances, coveredSections),
                0
        );
        return new Assembly(snapshot, identityChanged, added, removed, collectSortNanos, coverageNanos);
    }

    void removeSection(SectionKey key) {
        baseEntryCache.remove(Objects.requireNonNull(key, "key"));
    }

    void clear() {
        clearTouchedSlots();
        identityCache.clear();
        baseEntryCache.clear();
        tlasInstanceCache.clear();
        activeIdentities = new ActiveInstanceIdentity[0];
    }

    private void addCoverage(long packedSection) {
        coverageBuilder.addPacked(packedSection);
    }

    private void clearTouchedSlots() {
        for (int index = 0; index < touchedMaterialSlotCount; index++) {
            baseEntriesByMaterialSlot[touchedMaterialSlots[index]] = null;
        }
        touchedMaterialSlotCount = 0;
    }

    private void ensureSlotCapacity(int materialSlotCount, int admittedSectionCount) {
        if (baseEntriesByMaterialSlot.length < materialSlotCount) {
            baseEntriesByMaterialSlot = Arrays.copyOf(
                    baseEntriesByMaterialSlot,
                    Math.max(materialSlotCount, Math.max(1, baseEntriesByMaterialSlot.length << 1))
            );
        }
        if (touchedMaterialSlots.length < admittedSectionCount) {
            touchedMaterialSlots = Arrays.copyOf(
                    touchedMaterialSlots,
                    Math.max(admittedSectionCount, Math.max(1, touchedMaterialSlots.length << 1))
            );
        }
    }

    private ActiveInstanceIdentity cachedIdentity(Object key, long blasDeviceAddress, int materialSlot) {
        ActiveInstanceIdentity cached = identityCache.get(key);
        if (cached != null
                && cached.blasDeviceAddress() == blasDeviceAddress
                && cached.materialSlot() == materialSlot) {
            return cached;
        }
        ActiveInstanceIdentity created = new ActiveInstanceIdentity(key, blasDeviceAddress, materialSlot);
        identityCache.put(key, created);
        return created;
    }

    private Map.Entry<SectionKey, RtAccelerationStructure> cachedBaseEntry(
            SectionKey key,
            RtAccelerationStructure blas
    ) {
        Map.Entry<SectionKey, RtAccelerationStructure> cached = baseEntryCache.get(key);
        if (cached != null && cached.getValue() == blas) {
            return cached;
        }
        Map.Entry<SectionKey, RtAccelerationStructure> created = Map.entry(key, blas);
        baseEntryCache.put(key, created);
        return created;
    }

    private RtAccelerationStructure.TlasInstance cachedSectionInstance(
            ActiveInstanceIdentity identity,
            SectionKey key,
            long blasDeviceAddress
    ) {
        RtAccelerationStructure.TlasInstance cached = tlasInstanceCache.get(identity);
        if (cached != null) {
            return cached;
        }
        RtAccelerationStructure.TlasInstance created = RtAccelerationStructure.TlasInstance.section(
                key, blasDeviceAddress, identity.materialSlot()
        );
        tlasInstanceCache.put(identity, created);
        return created;
    }

    private RtAccelerationStructure.TlasInstance cachedFarFieldInstance(
            ActiveInstanceIdentity identity,
            long blasDeviceAddress,
            float originBlockX,
            float originBlockY,
            float originBlockZ
    ) {
        RtAccelerationStructure.TlasInstance cached = tlasInstanceCache.get(identity);
        if (cached != null) {
            return cached;
        }
        RtAccelerationStructure.TlasInstance created = new RtAccelerationStructure.TlasInstance(
                blasDeviceAddress,
                originBlockX,
                originBlockY,
                originBlockZ,
                identity.materialSlot()
        );
        tlasInstanceCache.put(identity, created);
        return created;
    }

    private void trimCachesIfNeeded() {
        if (tlasInstanceCache.size() > MAX_CACHE_ENTRIES
                || identityCache.size() > MAX_CACHE_ENTRIES
                || baseEntryCache.size() > MAX_CACHE_ENTRIES) {
            tlasInstanceCache.clear();
            identityCache.clear();
            baseEntryCache.clear();
            clearTouchedSlots();
        }
    }

    private static int differenceCount(ActiveInstanceIdentity[] left, ActiveInstanceIdentity[] right) {
        int count = 0;
        int rightIndex = 0;
        for (ActiveInstanceIdentity leftIdentity : left) {
            while (rightIndex < right.length
                    && right[rightIndex].materialSlot() < leftIdentity.materialSlot()) {
                rightIndex++;
            }
            if (rightIndex >= right.length
                    || right[rightIndex].materialSlot() != leftIdentity.materialSlot()
                    || !right[rightIndex].equals(leftIdentity)) {
                count++;
            }
        }
        return count;
    }

    private static int sectionInstanceLayoutHash(
            List<RtAccelerationStructure.TlasInstance> instances,
            Collection<SectionKey> coveredSectionKeys
    ) {
        int hash = 1;
        for (RtAccelerationStructure.TlasInstance instance : instances) {
            hash = 31 * hash + instance.customIndex();
            hash = 31 * hash + Float.floatToIntBits(instance.translateX());
            hash = 31 * hash + Float.floatToIntBits(instance.translateY());
            hash = 31 * hash + Float.floatToIntBits(instance.translateZ());
        }
        for (SectionKey key : coveredSectionKeys) {
            hash = 31 * hash + key.x();
            hash = 31 * hash + key.y();
            hash = 31 * hash + key.z();
        }
        return hash;
    }

    record Assembly(
            Snapshot snapshot,
            boolean identityChanged,
            int identityAdded,
            int identityRemoved,
            long collectSortNanos,
            long coverageNanos
    ) {
        Assembly {
            Objects.requireNonNull(snapshot, "snapshot");
            if (identityAdded < 0 || identityRemoved < 0 || collectSortNanos < 0 || coverageNanos < 0) {
                throw new IllegalArgumentException("active view assembly counters must not be negative");
            }
        }
    }

    record ActiveInstanceIdentity(Object key, long blasDeviceAddress, int materialSlot) {
        ActiveInstanceIdentity {
            key = Objects.requireNonNull(key, "key");
            if (blasDeviceAddress == 0L) {
                throw new IllegalArgumentException("active instance BLAS address must not be null");
            }
            if (materialSlot < 0) {
                throw new IllegalArgumentException("active instance material slot must not be negative");
            }
        }
    }

    static final class Snapshot {
        private final List<Map.Entry<SectionKey, RtAccelerationStructure>> baseEntries;
        private final List<RtFarFieldBlasCache.ActiveCell> farFieldCells;
        private final List<RtSectionInstanceAdmission.FarFieldCell> admittedFarFieldCells;
        private final PackedSectionMembership coveredSections;
        private final List<RtAccelerationStructure.TlasInstance> instances;
        private final int instanceLayoutHash;
        private final int uncoveredSections;

        private Snapshot(
                List<Map.Entry<SectionKey, RtAccelerationStructure>> baseEntries,
                List<RtFarFieldBlasCache.ActiveCell> farFieldCells,
                List<RtSectionInstanceAdmission.FarFieldCell> admittedFarFieldCells,
                PackedSectionMembership coveredSections,
                List<RtAccelerationStructure.TlasInstance> instances,
                int instanceLayoutHash,
                int uncoveredSections
        ) {
            this.baseEntries = baseEntries;
            this.farFieldCells = farFieldCells;
            this.admittedFarFieldCells = admittedFarFieldCells;
            this.coveredSections = coveredSections;
            this.instances = instances;
            this.instanceLayoutHash = instanceLayoutHash;
            this.uncoveredSections = uncoveredSections;
            validate();
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), PackedSectionMembership.empty(), List.of(), 0, 0);
        }

        Snapshot withUncoveredSections(int uncoveredSections) {
            if (uncoveredSections == this.uncoveredSections) {
                return this;
            }
            return new Snapshot(
                    baseEntries, farFieldCells, admittedFarFieldCells, coveredSections, instances,
                    instanceLayoutHash, uncoveredSections
            );
        }

        private static Snapshot freeze(
                Collection<Map.Entry<SectionKey, RtAccelerationStructure>> baseEntries,
                Collection<RtFarFieldBlasCache.ActiveCell> farFieldCells,
                Collection<RtSectionInstanceAdmission.FarFieldCell> admittedFarFieldCells,
                PackedSectionMembership coveredSections,
                Collection<RtAccelerationStructure.TlasInstance> instances,
                int instanceLayoutHash,
                int uncoveredSections
        ) {
            return new Snapshot(
                    List.copyOf(baseEntries),
                    List.copyOf(farFieldCells),
                    List.copyOf(admittedFarFieldCells),
                    Objects.requireNonNull(coveredSections, "coveredSections"),
                    List.copyOf(instances),
                    instanceLayoutHash,
                    uncoveredSections
            );
        }

        private void validate() {
            if (!coveredSections.canonicalOrder()) {
                throw new IllegalArgumentException("active Base/FarField coverage must be canonical and duplicate-free");
            }
            if (instances.size() != baseEntries.size() + farFieldCells.size()) {
                throw new IllegalArgumentException("active TLAS instance count must match Base/FarField entries");
            }
            if (uncoveredSections < 0) {
                throw new IllegalArgumentException("active view uncovered section count must not be negative");
            }
        }

        List<Map.Entry<SectionKey, RtAccelerationStructure>> baseEntries() {
            return baseEntries;
        }

        List<RtFarFieldBlasCache.ActiveCell> farFieldCells() {
            return farFieldCells;
        }

        List<RtSectionInstanceAdmission.FarFieldCell> admittedFarFieldCells() {
            return admittedFarFieldCells;
        }

        PackedSectionMembership coveredSections() {
            return coveredSections;
        }

        List<RtAccelerationStructure.TlasInstance> instances() {
            return instances;
        }

        int instanceLayoutHash() {
            return instanceLayoutHash;
        }

        int uncoveredSections() {
            return uncoveredSections;
        }
    }
}
