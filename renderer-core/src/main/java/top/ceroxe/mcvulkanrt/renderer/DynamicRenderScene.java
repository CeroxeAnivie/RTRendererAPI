package top.ceroxe.mcvulkanrt.renderer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable non-terrain render state captured at the sourceEngine render boundary.
 *
 * <p>Chunk sections are only one input stream. sourceEngine entities, dropped items,
 * particles, beams, celestial quads, and scene lighting follow separate
 * lifetimes in host and in UE-style renderers. This value object is the
 * renderer-owned handoff point for those streams: no host object, mutable
 * collection, PoseStack, BufferSource, or GL/Vulkan handle may cross this
 * boundary.</p>
 */
public record DynamicRenderScene(
        long revision,
        List<DynamicPrimitive> primitives,
        List<BillboardParticle> particles,
        List<Beam> beams,
        List<CelestialBody> celestialBodies,
        List<SceneLight> lights,
        List<WeatherColumn> weatherColumns,
        EnvironmentState environmentState,
        LightmapPayload lightmapPayload,
        List<DynamicModelInstance> modelInstances,
        List<BlockDecal> blockDecals,
        DynamicModelFrameDelta modelFrameDelta
) {
    public static final int MAX_GPU_CELESTIAL_BODIES = 8;
    public static final int MAX_GPU_PRIMITIVES = 64;
    public static final int MAX_TLAS_MODEL_PRIMITIVES = 16_384;
    public static final int MAX_GPU_PARTICLES = 256;
    public static final int MAX_GPU_BEAMS = 32;
    public static final int MAX_GPU_LIGHTS = 64;
    public static final int MAX_GPU_WEATHER_COLUMNS = 256;
    public static final int MAX_GPU_BLOCK_DECALS = 64;

    private static final DynamicRenderScene EMPTY = new DynamicRenderScene(
            0L,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EnvironmentState.unknown(),
            LightmapPayload.unknown(),
            List.of(),
            List.of()
    );

    public DynamicRenderScene {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        primitives = List.copyOf(primitives == null ? List.of() : primitives);
        particles = List.copyOf(particles == null ? List.of() : particles);
        beams = List.copyOf(beams == null ? List.of() : beams);
        celestialBodies = List.copyOf(celestialBodies == null ? List.of() : celestialBodies);
        lights = List.copyOf(lights == null ? List.of() : lights);
        weatherColumns = List.copyOf(weatherColumns == null ? List.of() : weatherColumns);
        environmentState = environmentState == null ? EnvironmentState.unknown() : environmentState;
        lightmapPayload = lightmapPayload == null ? LightmapPayload.unknown() : lightmapPayload;
        modelInstances = List.copyOf(modelInstances == null ? List.of() : modelInstances);
        blockDecals = List.copyOf(blockDecals == null ? List.of() : blockDecals);
        modelFrameDelta = modelFrameDelta == null ? DynamicModelFrameDelta.none() : modelFrameDelta;
        if (revision == 0L && (totalElements(
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                modelInstances,
                blockDecals
        ) > 0 || modelFrameDelta.activeSlotCount() > 0 || modelFrameDelta.hasUpdates())) {
            throw new IllegalArgumentException("non-empty dynamic scene requires a positive revision");
        }
    }

    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload,
            List<DynamicModelInstance> modelInstances,
            List<BlockDecal> blockDecals
    ) {
        this(
                revision, primitives, particles, beams, celestialBodies, lights, weatherColumns,
                environmentState, lightmapPayload, modelInstances, blockDecals, DynamicModelFrameDelta.none()
        );
    }

    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload,
            List<DynamicModelInstance> modelInstances
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                modelInstances,
                List.of()
        );
    }

    /**
     * Source-compatible constructor for non-model dynamic scene producers.
     * Model capture uses the dedicated stream below so it never has to build a
     * generic DynamicPrimitive plus a nested DynamicMeshInstance per cube.
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                List.of(),
                List.of()
        );
    }

    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                List.of(),
                EnvironmentState.unknown(),
                LightmapPayload.unknown(),
                List.of(),
                List.of()
        );
    }

    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            LightmapPayload lightmapPayload
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                List.of(),
                EnvironmentState.unknown(),
                lightmapPayload,
                List.of(),
                List.of()
        );
    }

    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                LightmapPayload.unknown(),
                List.of(),
                List.of()
        );
    }

    public static DynamicRenderScene empty() {
        return EMPTY;
    }

    public boolean hasRenderContent() {
        return totalElements() > 0;
    }

    public boolean hasTlasGeometryContent() {
        for (DynamicPrimitive primitive : primitives) {
            if (primitive.usesTlasGeometry()) {
                return true;
            }
        }
        return !modelInstances.isEmpty() || modelFrameDelta.activeSlotCount() > 0;
    }

    public boolean hasSceneUpdate() {
        return revision > 0L || hasRenderContent() || lightmapPayload.known();
    }

    public boolean hasSameRenderPayload(DynamicRenderScene other) {
        if (other == null) {
            return false;
        }
        return primitives.equals(other.primitives)
                && particles.equals(other.particles)
                && beams.equals(other.beams)
                && celestialBodies.equals(other.celestialBodies)
                && lights.equals(other.lights)
                && weatherColumns.equals(other.weatherColumns)
                && environmentRenderPayloadEquals(environmentState, other.environmentState)
                && lightmapPayload.equals(other.lightmapPayload)
                && modelInstances.equals(other.modelInstances)
                && modelFrameDelta.equals(other.modelFrameDelta)
                && blockDecals.equals(other.blockDecals);
    }

    public int totalElements() {
        int listElements = totalElements(
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                modelInstances,
                blockDecals
        );
        return listElements + Math.max(0, modelFrameDelta.activeSlotCount() - modelInstances.size());
    }

    public String asLogFragment() {
        return "dynamicScene{revision=" + revision
                + ", primitives=" + primitives.size()
                + ", tlasGeometryPrimitives=" + tlasGeometryPrimitiveCount()
                + ", modelInstances=" + Math.max(modelInstances.size(), modelFrameDelta.activeSlotCount())
                + ", modelDeltaUpdates=" + modelFrameDelta.updateCount()
                + ", particles=" + particles.size()
                + ", beams=" + beams.size()
                + ", celestialBodies=" + celestialBodies.size()
                + ", lights=" + lights.size()
                + ", weatherColumns=" + weatherColumns.size()
                + ", blockDecals=" + blockDecals.size()
                + ", environment=" + environmentState.asLogFragment()
                + ", totalElements=" + totalElements()
                + ", " + lightmapPayload.asLogFragment()
                + "}";
    }

    private int tlasGeometryPrimitiveCount() {
        int count = 0;
        for (DynamicPrimitive primitive : primitives) {
            if (primitive.usesTlasGeometry()) {
                count++;
            }
        }
        return count;
    }

    public DynamicRenderScene withLightmapPayload(LightmapPayload nextLightmapPayload) {
        LightmapPayload effectiveLightmapPayload =
                nextLightmapPayload == null ? LightmapPayload.unknown() : nextLightmapPayload;
        if (lightmapPayload.equals(effectiveLightmapPayload)) {
            return this;
        }
        return new DynamicRenderScene(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                effectiveLightmapPayload,
                modelInstances,
                blockDecals,
                modelFrameDelta
        );
    }

    DynamicRenderScene withRevision(long nextRevision) {
        if (revision == nextRevision) {
            return this;
        }
        return new DynamicRenderScene(
                nextRevision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                modelInstances,
                blockDecals,
                modelFrameDelta
        );
    }

    private static int totalElements(
            List<?> primitives,
            List<?> particles,
            List<?> beams,
            List<?> celestialBodies,
            List<?> lights,
            List<?> weatherColumns,
            EnvironmentState environmentState,
            List<?> modelInstances,
            List<?> blockDecals
    ) {
        return primitives.size()
                + particles.size()
                + beams.size()
                + celestialBodies.size()
                + lights.size()
                + weatherColumns.size()
                + modelInstances.size()
                + blockDecals.size()
                + (environmentState != null && environmentState.hasRenderContent() ? 1 : 0);
    }

    private static boolean environmentRenderPayloadEquals(
            EnvironmentState left,
            EnvironmentState right
    ) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        /*
         * This predicate is the CPU-side contract for persistent dynamic-scene
         * uploads. Environment clock and fog alpha are frame constants, not
         * scene ownership: placing them in the dynamic SSBO would advance the
         * descriptor/TLAS generation on every rendered frame even when no
         * primitive, material, or transform changed.
         */
        return left.fogKnown() == right.fogKnown()
                && Float.compare(left.fogRed(), right.fogRed()) == 0
                && Float.compare(left.fogGreen(), right.fogGreen()) == 0
                && Float.compare(left.fogBlue(), right.fogBlue()) == 0
                && Float.compare(left.environmentalStart(), right.environmentalStart()) == 0
                && Float.compare(left.environmentalEnd(), right.environmentalEnd()) == 0
                && Float.compare(left.renderDistanceStart(), right.renderDistanceStart()) == 0
                && Float.compare(left.renderDistanceEnd(), right.renderDistanceEnd()) == 0
                && Float.compare(left.skyEnd(), right.skyEnd()) == 0
                && Float.compare(left.cloudEnd(), right.cloudEnd()) == 0
                && left.cloudKnown() == right.cloudKnown()
                && left.cloudRgba8() == right.cloudRgba8()
                && Float.compare(left.cloudHeight(), right.cloudHeight()) == 0
                && left.cloudRange() == right.cloudRange()
                && left.cloudStatus() == right.cloudStatus()
                && left.skyVisible() == right.skyVisible();
    }


    public enum PrimitiveKind {
        ENTITY,
        DROPPED_ITEM,
        BLOCK_ENTITY,
        HAND_OR_TOOL
    }

    public enum PrimitiveGeometryKind {
        MODEL,
        BILLBOARD,
        CROSS_PLANE,
        IMPOSTOR
    }

    public enum ParticleKind {
        OPAQUE_BILLBOARD,
        CUTOUT_BILLBOARD,
        TRANSLUCENT_BILLBOARD,
        ADDITIVE_BILLBOARD
    }

    public enum WeatherKind {
        RAIN,
        SNOW
    }

    public enum BeamKind {
        BEACON,
        END_CRYSTAL,
        DRAGON,
        GENERIC
    }

    public enum CelestialKind {
        SUN,
        MOON,
        STARS,
        SKY_DISC
    }

    public enum LightKind {
        SUN,
        MOON,
        SKY,
        BLOCK_EMISSION,
        ENTITY_EMISSION,
        BEAM_EMISSION
    }

    public record DynamicPrimitive(
            long id,
            PrimitiveKind kind,
            PrimitiveGeometryKind geometryKind,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float roll,
            float radiusX,
            float radiusY,
            float radiusZ,
            int materialKey,
            int textureKey,
            int packedLight,
            boolean castsShadow,
            String debugName,
            DynamicMeshInstance meshInstance
    ) {
        public DynamicPrimitive {
            requireNonNegative(id, "id");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(yaw, "yaw");
            requireFinite(pitch, "pitch");
            requireFinite(roll, "roll");
            requirePositive(radiusX, "radiusX");
            requirePositive(radiusY, "radiusY");
            requirePositive(radiusZ, "radiusZ");
            kind = kind == null ? PrimitiveKind.ENTITY : kind;
            geometryKind = geometryKind == null ? PrimitiveGeometryKind.MODEL : geometryKind;
            debugName = sanitizeDebugName(debugName);
            if (meshInstance != null && geometryKind != PrimitiveGeometryKind.MODEL) {
                throw new IllegalArgumentException("dynamic mesh instances must use MODEL geometry");
            }
        }

        public DynamicPrimitive(
                long id,
                PrimitiveKind kind,
                PrimitiveGeometryKind geometryKind,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                float roll,
                float radiusX,
                float radiusY,
                float radiusZ,
                int materialKey,
                int textureKey,
                int packedLight,
                boolean castsShadow,
                String debugName
        ) {
            this(
                    id,
                    kind,
                    geometryKind,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll,
                    radiusX,
                    radiusY,
                    radiusZ,
                    materialKey,
                    textureKey,
                    packedLight,
                    castsShadow,
                    debugName,
                    null
            );
        }

        public DynamicPrimitive(
                long id,
                PrimitiveKind kind,
                PrimitiveGeometryKind geometryKind,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                float roll,
                float radius,
                int materialKey,
                int textureKey,
                int packedLight,
                boolean castsShadow,
                String debugName
        ) {
            this(
                    id,
                    kind,
                    geometryKind,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll,
                    radius,
                    radius,
                    radius,
                    materialKey,
                    textureKey,
                    packedLight,
                    castsShadow,
                    debugName,
                    null
            );
        }

        public float radius() {
            return Math.max(radiusX, Math.max(radiusY, radiusZ));
        }

        /**
         * Full model geometry participates in the world TLAS because it needs
         * hardware triangle traversal, material-table indexing, and frame-lifetime
         * protection. Lightweight impostor/cross-plane/billboard primitives stay
         * in the per-frame analytic stream; sending particle-like motion through
         * BLAS/TLAS would force scene graph rebuilds and stall presentation behind
         * stale-TLAS gates.
         */
        public boolean usesTlasGeometry() {
            return geometryKind == PrimitiveGeometryKind.MODEL;
        }

        public boolean usesAnalyticFastPath() {
            return !usesTlasGeometry();
        }
    }

    /**
     * Immutable scatter publication for collector-owned model slots.
     *
     * <p>Transforms are packed as twelve floats per update so GPU consumers can scatter the lane
     * without scanning the complete model list. Producer-owned deltas also retain the already
     * immutable slot publication: CPU lifecycle consumers install that identity directly instead
     * of rebuilding a second model object from the same transform fact.</p>
     */
    public static final class DynamicModelFrameDelta {
        public static final int ADDED = 1;
        public static final int REMOVED = 1 << 1;
        public static final int TOPOLOGY = 1 << 2;
        public static final int TRANSFORM = 1 << 3;
        public static final int MATERIAL = 1 << 4;
        public static final int LIGHT = 1 << 5;
        private static final int VALID_MASK = ADDED | REMOVED | TOPOLOGY | TRANSFORM | MATERIAL | LIGHT;
        private static final int TRANSFORM_COMPONENTS = 12;
        private static final DynamicModelFrameDelta NONE = new DynamicModelFrameDelta(
                0L, 0L, 0L, 0L, 0L, 0, 0,
                DynamicModelSlotSnapshot.empty(),
                DynamicModelTransformSnapshot.empty(),
                new int[0], new byte[0], new DynamicModelInstance[0], new float[0]
        );

        private final long membershipRevision;
        private final long topologyRevision;
        private final long transformRevision;
        private final long materialRevision;
        private final long lightRevision;
        private final int physicalSlotCount;
        private final int activeSlotCount;
        private final DynamicModelSlotSnapshot membershipSnapshot;
        private final DynamicModelTransformSnapshot transformSnapshot;
        private final int[] slots;
        private final byte[] dirtyMasks;
        private final DynamicModelInstance[] publications;
        private final float[] packedTransforms;

        public DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            this(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    DynamicModelTransformSnapshot.fromFrame(
                            transformRevision, membershipSnapshot, slots, dirtyMasks, packedTransforms
                    ),
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms
            );
        }

        public DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            this(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    transformSnapshot,
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms,
                    false
            );
        }

        /**
         * Transfers fresh collector arrays into one immutable frame publication.
         *
         * <p>Public constructors stay defensive for external/test callers. The collector creates
         * these arrays solely for this publication and never mutates them afterward, so copying
         * the transform scatter a second time only doubles per-animation-frame allocation.</p>
         */
        static DynamicModelFrameDelta takeCollectorOwnership(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            return new DynamicModelFrameDelta(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    transformSnapshot,
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms,
                    true
            );
        }

        private DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms,
                boolean takeOwnership
        ) {
            if (membershipRevision < 0L || topologyRevision < 0L || transformRevision < 0L
                    || materialRevision < 0L || lightRevision < 0L) {
                throw new IllegalArgumentException("dynamic model lane revisions must not be negative");
            }
            if (physicalSlotCount < 0 || activeSlotCount < 0 || activeSlotCount > physicalSlotCount) {
                throw new IllegalArgumentException("dynamic model slot counts are invalid");
            }
            this.membershipSnapshot = Objects.requireNonNull(membershipSnapshot, "membershipSnapshot");
            this.transformSnapshot = Objects.requireNonNull(transformSnapshot, "transformSnapshot");
            if (this.membershipSnapshot.membershipRevision() != membershipRevision
                    || this.membershipSnapshot.physicalSlotCount() != physicalSlotCount
                    || this.membershipSnapshot.activeSlotCount() != activeSlotCount) {
                throw new IllegalArgumentException("dynamic model membership snapshot does not match lane identity");
            }
            if (this.transformSnapshot.revision() != transformRevision
                    || this.transformSnapshot.physicalSlotCount() != physicalSlotCount) {
                throw new IllegalArgumentException("dynamic model transform snapshot does not match lane identity");
            }
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(dirtyMasks, "dirtyMasks");
            Objects.requireNonNull(publications, "publications");
            Objects.requireNonNull(packedTransforms, "packedTransforms");
            this.slots = takeOwnership ? slots : Arrays.copyOf(slots, slots.length);
            this.dirtyMasks = takeOwnership ? dirtyMasks : Arrays.copyOf(dirtyMasks, dirtyMasks.length);
            this.publications = takeOwnership
                    ? publications
                    : Arrays.copyOf(publications, publications.length);
            this.packedTransforms = takeOwnership
                    ? packedTransforms
                    : Arrays.copyOf(packedTransforms, packedTransforms.length);
            if (this.slots.length != this.dirtyMasks.length
                    || this.slots.length != this.publications.length
                    || this.packedTransforms.length != this.slots.length * TRANSFORM_COMPONENTS) {
                throw new IllegalArgumentException("dynamic model delta lanes must have matching update counts");
            }
            int previousSlot = -1;
            for (int update = 0; update < this.slots.length; update++) {
                int slot = this.slots[update];
                int mask = Byte.toUnsignedInt(this.dirtyMasks[update]);
                if (slot <= previousSlot || slot >= physicalSlotCount || mask == 0 || (mask & ~VALID_MASK) != 0) {
                    throw new IllegalArgumentException("dynamic model dirty slots must be sorted, unique, and valid");
                }
                boolean removedOnly = (mask & REMOVED) != 0 && (mask & ADDED) == 0;
                boolean publicationRequired = !removedOnly
                        && (mask & (ADDED | TOPOLOGY | MATERIAL | LIGHT)) != 0;
                if (publicationRequired && this.publications[update] == null) {
                    throw new IllegalArgumentException("non-transform model lane update requires a publication");
                }
                if ((mask & TRANSFORM) != 0) {
                    for (int component = 0; component < TRANSFORM_COMPONENTS; component++) {
                        if (!Float.isFinite(this.packedTransforms[update * TRANSFORM_COMPONENTS + component])) {
                            throw new IllegalArgumentException("packed model transform must be finite");
                        }
                        if (this.publications[update] != null
                                && Float.floatToIntBits(this.publications[update].transform().value(component))
                                != Float.floatToIntBits(
                                        this.packedTransforms[update * TRANSFORM_COMPONENTS + component]
                                )) {
                            throw new IllegalArgumentException(
                                    "model publication transform does not match packed scatter lane"
                            );
                        }
                    }
                }
                previousSlot = slot;
            }
            this.membershipRevision = membershipRevision;
            this.topologyRevision = topologyRevision;
            this.transformRevision = transformRevision;
            this.materialRevision = materialRevision;
            this.lightRevision = lightRevision;
            this.physicalSlotCount = physicalSlotCount;
            this.activeSlotCount = activeSlotCount;
        }

        public static DynamicModelFrameDelta none() {
            return NONE;
        }

        public long membershipRevision() { return membershipRevision; }
        public long topologyRevision() { return topologyRevision; }
        public long transformRevision() { return transformRevision; }
        public long materialRevision() { return materialRevision; }
        public long lightRevision() { return lightRevision; }
        public int physicalSlotCount() { return physicalSlotCount; }
        public int activeSlotCount() { return activeSlotCount; }
        public DynamicModelSlotSnapshot membershipSnapshot() { return membershipSnapshot; }
        public DynamicModelTransformSnapshot transformSnapshot() { return transformSnapshot; }
        public int updateCount() { return slots.length; }
        public boolean hasUpdates() { return slots.length > 0; }
        public boolean isAuthoritative() {
            return membershipRevision > 0L || physicalSlotCount > 0 || activeSlotCount > 0 || hasUpdates();
        }
        public int slotAt(int update) { return slots[update]; }
        public int dirtyMaskAt(int update) { return Byte.toUnsignedInt(dirtyMasks[update]); }
        public DynamicModelInstance publicationAt(int update) { return publications[update]; }
        public float transformAt(int update, int component) {
            if (component < 0 || component >= TRANSFORM_COMPONENTS) {
                throw new IndexOutOfBoundsException(component);
            }
            return packedTransforms[update * TRANSFORM_COMPONENTS + component];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelFrameDelta that
                    && membershipRevision == that.membershipRevision
                    && topologyRevision == that.topologyRevision
                    && transformRevision == that.transformRevision
                    && materialRevision == that.materialRevision
                    && lightRevision == that.lightRevision
                    && physicalSlotCount == that.physicalSlotCount
                    && activeSlotCount == that.activeSlotCount
                    && membershipSnapshot.equals(that.membershipSnapshot)
                    && transformSnapshot.equals(that.transformSnapshot)
                    && Arrays.equals(slots, that.slots)
                    && Arrays.equals(dirtyMasks, that.dirtyMasks)
                    && Arrays.equals(publications, that.publications)
                    && Arrays.equals(packedTransforms, that.packedTransforms);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(
                    membershipRevision, topologyRevision, transformRevision, materialRevision,
                    lightRevision, physicalSlotCount, activeSlotCount, membershipSnapshot, transformSnapshot
            );
            hash = 31 * hash + Arrays.hashCode(slots);
            hash = 31 * hash + Arrays.hashCode(dirtyMasks);
            hash = 31 * hash + Arrays.hashCode(publications);
            return 31 * hash + Arrays.hashCode(packedTransforms);
        }
    }

    /**
     * Immutable owner-generation map from collector slots to model identities.
     *
     * <p>The frame delta is intentionally lossy after publication: a stable owner may emit only a
     * transform lane. Native consumers can begin observing that stream after an earlier compatibility
     * frame, so slot ownership cannot be reconstructed from the current dirty set. This snapshot is
     * rebuilt only when membership changes and is retained by identity across transform/material frames,
     * matching GPUScene's persistent primitive-index publication.</p>
     */
    public static final class DynamicModelSlotSnapshot {
        private static final DynamicModelSlotSnapshot EMPTY = new DynamicModelSlotSnapshot(
                0L, 0, new int[0], new DynamicModelInstance[0]
        );

        private final long membershipRevision;
        private final int physicalSlotCount;
        private final int[] activeSlots;
        private final DynamicModelInstance[] instances;

        public DynamicModelSlotSnapshot(
                long membershipRevision,
                int physicalSlotCount,
                int[] activeSlots,
                DynamicModelInstance[] instances
        ) {
            if (membershipRevision < 0L || physicalSlotCount < 0) {
                throw new IllegalArgumentException("dynamic model membership identity must not be negative");
            }
            this.activeSlots = Arrays.copyOf(Objects.requireNonNull(activeSlots, "activeSlots"), activeSlots.length);
            this.instances = Arrays.copyOf(Objects.requireNonNull(instances, "instances"), instances.length);
            if (this.activeSlots.length != this.instances.length) {
                throw new IllegalArgumentException("dynamic model membership lanes must have matching lengths");
            }
            LongOpenHashSet identities = new LongOpenHashSet(this.instances.length);
            int previousSlot = -1;
            for (int index = 0; index < this.activeSlots.length; index++) {
                int slot = this.activeSlots[index];
                DynamicModelInstance instance = Objects.requireNonNull(
                        this.instances[index], "dynamic model membership instance"
                );
                if (slot <= previousSlot || slot >= physicalSlotCount || !identities.add(instance.id())) {
                    throw new IllegalArgumentException(
                            "dynamic model membership slots and identities must be sorted and unique"
                    );
                }
                previousSlot = slot;
            }
            this.membershipRevision = membershipRevision;
            this.physicalSlotCount = physicalSlotCount;
        }

        public static DynamicModelSlotSnapshot empty() {
            return EMPTY;
        }

        public long membershipRevision() { return membershipRevision; }
        public int physicalSlotCount() { return physicalSlotCount; }
        public int activeSlotCount() { return activeSlots.length; }
        public int slotAt(int index) { return activeSlots[index]; }
        public DynamicModelInstance instanceAt(int index) { return instances[index]; }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelSlotSnapshot that
                    && membershipRevision == that.membershipRevision
                    && physicalSlotCount == that.physicalSlotCount
                    && Arrays.equals(activeSlots, that.activeSlots)
                    && Arrays.equals(instances, that.instances);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(membershipRevision, physicalSlotCount);
            hash = 31 * hash + Arrays.hashCode(activeSlots);
            return 31 * hash + Arrays.hashCode(instances);
        }
    }

    /**
     * Split-lane producer view of one persistent model slot.
     *
     * <p>Capture owns topology/material/light facts separately from the twelve transform
     * components. Implementations may be persistent mutable staging slots consumed synchronously
     * at the renderer boundary; consumers must copy only the lanes they own and must not retain a
     * non-{@link DynamicModelInstance} implementation.</p>
     */
    public interface DynamicModelObservation {
        long id();
        PrimitiveKind kind();
        DynamicMeshAsset asset();
        List<DynamicMeshInstance.FaceMaterial> faceMaterials();
        DynamicRenderLane renderLane();
        int packedLight();
        String debugName();
        float transformValue(int component);

        default DynamicModelInstance materialize() {
            if (this instanceof DynamicModelInstance instance) {
                return instance;
            }
            return new DynamicModelInstance(
                    id(),
                    kind(),
                    asset(),
                    new DynamicMeshInstance.AffineTransform(
                            transformValue(0), transformValue(1), transformValue(2), transformValue(3),
                            transformValue(4), transformValue(5), transformValue(6), transformValue(7),
                            transformValue(8), transformValue(9), transformValue(10), transformValue(11)
                    ),
                    faceMaterials(),
                    packedLight(),
                    debugName(),
                    renderLane()
            );
        }
    }

    /**
     * Persistent-slot payload for captured sourceEngine model cubes.
     *
     * <p>This is intentionally separate from {@link DynamicPrimitive}: model
     * topology is identified by {@code id + asset.id}, transforms are TLAS
     * payload, and light/face materials are material-table payload. Keeping
     * those fields in one compact renderer-owned value lets the RT cache use
     * UE-style dirty lanes without rebuilding generic analytic primitive data
     * for every host submit traversal.</p>
     */
    public static final class DynamicModelInstance implements DynamicModelObservation {
        private final long id;
        private final PrimitiveKind kind;
        private final DynamicMeshAsset asset;
        private final DynamicMeshInstance.AffineTransform transform;
        private final List<DynamicMeshInstance.FaceMaterial> faceMaterials;
        private final DynamicRenderLane renderLane;
        private final int packedLight;
        private final String debugName;
        private final long topologyRevision;
        private final long transformRevision;
        private final long materialRevision;
        private final long lightRevision;
        private final int faceMaterialsHash;
        private final int hashCode;

        public DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName
        ) {
            this(id, kind, asset, transform, faceMaterials, packedLight, debugName, null);
        }

        public DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane
        ) {
            this(
                    id,
                    kind,
                    asset,
                    transform,
                    List.copyOf(Objects.requireNonNull(faceMaterials, "faceMaterials")),
                    packedLight,
                    sanitizeDebugName(debugName),
                    1L,
                    1L,
                    1L,
                    1L,
                    renderLane,
                    publishedFaceMaterialsHash(faceMaterials)
            );
        }

        private DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                DynamicRenderLane retainedRenderLane,
                int retainedFaceMaterialsHash
        ) {
            requireNonNegative(id, "id");
            this.id = id;
            this.kind = kind == null ? PrimitiveKind.ENTITY : kind;
            this.asset = Objects.requireNonNull(asset, "asset");
            this.transform = Objects.requireNonNull(transform, "transform");
            this.faceMaterials = Objects.requireNonNull(faceMaterials, "faceMaterials");
            if (this.faceMaterials.size() != this.asset.faceCount()) {
                throw new IllegalArgumentException("model face materials must exactly match the mesh asset face count");
            }
            this.renderLane = retainedRenderLane == null
                    ? DynamicRenderLane.fromFaceMaterials(this.faceMaterials)
                    : retainedRenderLane;
            this.packedLight = packedLight;
            this.debugName = Objects.requireNonNull(debugName, "debugName");
            this.topologyRevision = requirePositiveRevision(topologyRevision, "topologyRevision");
            this.transformRevision = requirePositiveRevision(transformRevision, "transformRevision");
            this.materialRevision = requirePositiveRevision(materialRevision, "materialRevision");
            this.lightRevision = requirePositiveRevision(lightRevision, "lightRevision");
            this.faceMaterialsHash = retainedFaceMaterialsHash;
            int hash = Long.hashCode(this.id);
            hash = 31 * hash + this.kind.hashCode();
            hash = 31 * hash + this.asset.hashCode();
            hash = 31 * hash + this.transform.hashCode();
            hash = 31 * hash + this.faceMaterialsHash;
            hash = 31 * hash + this.renderLane.hashCode();
            hash = 31 * hash + Integer.hashCode(this.packedLight);
            this.hashCode = 31 * hash + this.debugName.hashCode();
        }

        public long id() {
            return id;
        }

        public PrimitiveKind kind() {
            return kind;
        }

        public DynamicMeshAsset asset() {
            return asset;
        }

        public DynamicMeshInstance.AffineTransform transform() {
            return transform;
        }

        @Override
        public float transformValue(int component) {
            return transform.value(component);
        }

        public List<DynamicMeshInstance.FaceMaterial> faceMaterials() {
            return faceMaterials;
        }

        public DynamicRenderLane renderLane() {
            return renderLane;
        }

        public int packedLight() {
            return packedLight;
        }

        public String debugName() {
            return debugName;
        }

        public long topologyRevision() {
            return topologyRevision;
        }

        public long transformRevision() {
            return transformRevision;
        }

        public long materialRevision() {
            return materialRevision;
        }

        public long lightRevision() {
            return lightRevision;
        }

        public DynamicMeshInstance meshInstance() {
            return new DynamicMeshInstance(asset, transform, faceMaterials);
        }

        /**
         * Produces the next immutable publication while preserving independent GPUScene lanes.
         * Stable observations return this exact object. A transform-only animation therefore
         * reuses topology/material/light state and never recopies the face-material list.
         */
        public DynamicModelInstance withObservation(
                PrimitiveKind nextKind,
                DynamicMeshAsset nextAsset,
                DynamicMeshInstance.AffineTransform nextTransform,
                List<DynamicMeshInstance.FaceMaterial> nextFaceMaterials,
                int nextPackedLight,
                String nextDebugName
        ) {
            return withObservation(
                    nextKind, nextAsset, nextTransform, nextFaceMaterials,
                    nextPackedLight, nextDebugName, null
            );
        }

        public DynamicModelInstance withObservation(
                PrimitiveKind nextKind,
                DynamicMeshAsset nextAsset,
                DynamicMeshInstance.AffineTransform nextTransform,
                List<DynamicMeshInstance.FaceMaterial> nextFaceMaterials,
                int nextPackedLight,
                String nextDebugName,
                DynamicRenderLane nextRenderLane
        ) {
            PrimitiveKind normalizedKind = nextKind == null ? PrimitiveKind.ENTITY : nextKind;
            DynamicMeshAsset normalizedAsset = Objects.requireNonNull(nextAsset, "asset");
            DynamicMeshInstance.AffineTransform normalizedTransform =
                    Objects.requireNonNull(nextTransform, "transform");
            List<DynamicMeshInstance.FaceMaterial> observedMaterials =
                    Objects.requireNonNull(nextFaceMaterials, "faceMaterials");
            String normalizedDebugName = sanitizeDebugName(nextDebugName);

            boolean topologyChanged = kind != normalizedKind
                    || (asset != normalizedAsset && !asset.equals(normalizedAsset))
                    || (debugName != normalizedDebugName && !debugName.equals(normalizedDebugName));
            boolean transformChanged = transform != normalizedTransform
                    && !transform.equals(normalizedTransform);
            boolean materialChanged = faceMaterials != observedMaterials
                    && !faceMaterials.equals(observedMaterials);
            /*
             * Render lane is a material fact. Transform-only animation must
             * not rescan every cube face merely because the model publication
             * advances. Reuse the retained lane whenever the material payload
             * is equal; an explicit producer lane still wins when supplied.
             */
            DynamicRenderLane observedRenderLane = nextRenderLane != null
                    ? nextRenderLane
                    : materialChanged
                    ? DynamicRenderLane.fromFaceMaterials(observedMaterials)
                    : renderLane;
            boolean renderLaneChanged = renderLane != observedRenderLane;
            boolean lightChanged = packedLight != nextPackedLight;
            if (!topologyChanged && !transformChanged && !materialChanged && !renderLaneChanged && !lightChanged) {
                return this;
            }

            List<DynamicMeshInstance.FaceMaterial> publishedMaterials = materialChanged
                    ? List.copyOf(observedMaterials)
                    : faceMaterials;
            return new DynamicModelInstance(
                    id,
                    normalizedKind,
                    normalizedAsset,
                    transformChanged ? normalizedTransform : transform,
                    publishedMaterials,
                    nextPackedLight,
                    normalizedDebugName,
                    topologyChanged ? nextLaneRevision(topologyRevision) : topologyRevision,
                    transformChanged || renderLaneChanged
                            ? nextLaneRevision(transformRevision)
                            : transformRevision,
                    materialChanged ? nextLaneRevision(materialRevision) : materialRevision,
                    lightChanged ? nextLaneRevision(lightRevision) : lightRevision,
                    observedRenderLane,
                    materialChanged ? publishedMaterials.hashCode() : faceMaterialsHash
            );
        }

        /**
         * Producer-side reuse predicate. Capture can preserve this immutable value across frames
         * only when every visible RT field is identical; changing an animation transform, material,
         * light, render lane, or debug identity always produces a new publication value.
         */
        public boolean matches(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName
        ) {
            return this.id == id
                    && this.kind == (kind == null ? PrimitiveKind.ENTITY : kind)
                    && this.asset.equals(Objects.requireNonNull(asset, "asset"))
                    && this.transform.equals(Objects.requireNonNull(transform, "transform"))
                    && this.faceMaterials.equals(Objects.requireNonNull(faceMaterials, "faceMaterials"))
                    && this.packedLight == packedLight
                    && this.debugName.equals(sanitizeDebugName(debugName));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelInstance that
                    && id == that.id
                    && packedLight == that.packedLight
                    && kind == that.kind
                    && asset.equals(that.asset)
                    && transform.equals(that.transform)
                    && faceMaterials.equals(that.faceMaterials)
                    && renderLane == that.renderLane
                    && debugName.equals(that.debugName);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private static long requirePositiveRevision(long revision, String name) {
            if (revision <= 0L) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return revision;
        }

        private static long nextLaneRevision(long revision) {
            return revision == Long.MAX_VALUE ? 1L : revision + 1L;
        }

        private static int publishedFaceMaterialsHash(
                List<DynamicMeshInstance.FaceMaterial> faceMaterials
        ) {
            return Objects.requireNonNull(faceMaterials, "faceMaterials").hashCode();
        }
    }

    public record BillboardParticle(
            long id,
            ParticleKind kind,
            double x,
            double y,
            double z,
            float size,
            int rgba8,
            int textureId,
            int packedLight,
            float ageFraction,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW,
            float u0,
            float u1,
            float v0,
            float v1,
            float lifecycleAlpha
    ) {
        public BillboardParticle {
            requireNonNegative(id, "id");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requirePositive(size, "size");
            requireFinite(ageFraction, "ageFraction");
            if (ageFraction < 0.0F || ageFraction > 1.0F) {
                throw new IllegalArgumentException("ageFraction must be in [0, 1]");
            }
            requireFinite(rotationX, "rotationX");
            requireFinite(rotationY, "rotationY");
            requireFinite(rotationZ, "rotationZ");
            requireFinite(rotationW, "rotationW");
            float rotationLengthSquared = rotationX * rotationX + rotationY * rotationY
                    + rotationZ * rotationZ + rotationW * rotationW;
            if (Math.abs(rotationLengthSquared - 1.0F) > 1.0e-3F) {
                throw new IllegalArgumentException("particle rotation must be normalized");
            }
            requireFinite(u0, "u0");
            requireFinite(u1, "u1");
            requireFinite(v0, "v0");
            requireFinite(v1, "v1");
            /* sourceEngine sprite animation and mirroring may intentionally reverse either UV axis. */
            lifecycleAlpha = clamp01Finite(lifecycleAlpha, "lifecycleAlpha");
            kind = kind == null ? ParticleKind.TRANSLUCENT_BILLBOARD : kind;
        }

        public BillboardParticle(
                long id,
                ParticleKind kind,
                double x,
                double y,
                double z,
                float size,
                int rgba8,
                int textureId,
                int packedLight,
                float ageFraction
        ) {
            this(
                    id, kind, x, y, z, size, rgba8, textureId, packedLight, ageFraction,
                    0.0F, 0.0F, 0.0F, 1.0F,
                    0.0F, 1.0F, 0.0F, 1.0F,
                    ((rgba8 >>> 24) & 0xFF) / 255.0F
            );
        }
    }

    public record WeatherColumn(
            WeatherKind kind,
            double x,
            double z,
            float bottomY,
            float topY,
            float uOffset,
            float vOffset,
            int lightCoords,
            float alpha
    ) {
        public WeatherColumn {
            kind = kind == null ? WeatherKind.RAIN : kind;
            requireFinite(x, "x");
            requireFinite(z, "z");
            requireFinite(bottomY, "bottomY");
            requireFinite(topY, "topY");
            requireFinite(uOffset, "uOffset");
            requireFinite(vOffset, "vOffset");
            requireFinite(alpha, "alpha");
            if (topY <= bottomY) {
                throw new IllegalArgumentException("weather column topY must be greater than bottomY");
            }
            alpha = clamp01(alpha);
        }
    }

    public record Beam(
            long id,
            BeamKind kind,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            float radius,
            int rgba8,
            int textureKey,
            int packedLight,
            boolean additive
    ) {
        public Beam {
            requireNonNegative(id, "id");
            requireFinite(startX, "startX");
            requireFinite(startY, "startY");
            requireFinite(startZ, "startZ");
            requireFinite(endX, "endX");
            requireFinite(endY, "endY");
            requireFinite(endZ, "endZ");
            requirePositive(radius, "radius");
            if (startX == endX && startY == endY && startZ == endZ) {
                throw new IllegalArgumentException("beam endpoints must not be identical");
            }
            kind = kind == null ? BeamKind.GENERIC : kind;
        }
    }

    /** Sparse same-surface terrain decal keyed by exact world block coordinates. */
    public record BlockDecal(
            int blockX,
            int blockY,
            int blockZ,
            float offsetX,
            float offsetY,
            float offsetZ,
            int textureId,
            int progress
    ) {
        public BlockDecal {
            if (blockX < -33_554_432 || blockX > 33_554_431
                    || blockZ < -33_554_432 || blockZ > 33_554_431
                    || blockY < -2_048 || blockY > 2_047) {
                throw new IllegalArgumentException("block decal position must fit BlockPos.asLong encoding");
            }
            requireFinite(offsetX, "blockDecal.offsetX");
            requireFinite(offsetY, "blockDecal.offsetY");
            requireFinite(offsetZ, "blockDecal.offsetZ");
            if (textureId <= 0) {
                throw new IllegalArgumentException("block decal requires a registered texture");
            }
            if (progress < 0 || progress > 9) {
                throw new IllegalArgumentException("block decal progress must be in [0, 9]");
            }
        }

        public long stableId() {
            return ((long) blockX & 0x3FF_FFFFL) << 38
                    | ((long) blockZ & 0x3FF_FFFFL) << 12
                    | ((long) blockY & 0xFFFL);
        }
    }

    public record CelestialBody(
            CelestialKind kind,
            float directionX,
            float directionY,
            float directionZ,
            float angularRadius,
            int rgba8,
            int textureKey,
            float brightness
    ) {
        public CelestialBody {
            kind = kind == null ? CelestialKind.SKY_DISC : kind;
            requireUnitVector(directionX, directionY, directionZ, "direction");
            requirePositive(angularRadius, "angularRadius");
            requireFinite(brightness, "brightness");
            if (brightness < 0.0F) {
                throw new IllegalArgumentException("brightness must not be negative");
            }
        }
    }

    public record SceneLight(
            long id,
            LightKind kind,
            double x,
            double y,
            double z,
            float directionX,
            float directionY,
            float directionZ,
            float radius,
            float intensity,
            int rgb8,
            boolean castsShadow
    ) {
        public SceneLight {
            requireNonNegative(id, "id");
            kind = kind == null ? LightKind.BLOCK_EMISSION : kind;
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            if (kind == LightKind.SUN || kind == LightKind.MOON || kind == LightKind.SKY) {
                requireUnitVector(directionX, directionY, directionZ, "direction");
            } else {
                requireFinite(directionX, "directionX");
                requireFinite(directionY, "directionY");
                requireFinite(directionZ, "directionZ");
            }
            requirePositive(radius, "radius");
            requireFinite(intensity, "intensity");
            if (intensity < 0.0F) {
                throw new IllegalArgumentException("intensity must not be negative");
            }
        }
    }

    public record EnvironmentState(
            boolean fogKnown,
            float fogRed,
            float fogGreen,
            float fogBlue,
            float fogAlpha,
            float environmentalStart,
            float environmentalEnd,
            float renderDistanceStart,
            float renderDistanceEnd,
            float skyEnd,
            float cloudEnd,
            boolean cloudKnown,
            int cloudRgba8,
            float cloudHeight,
            int cloudRange,
            int cloudStatus,
            long gameTime,
            float partialTicks,
            boolean skyVisible
    ) {
        private static final EnvironmentState UNKNOWN = new EnvironmentState(
                false,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                false,
                0,
                0.0F,
                0,
                0,
                0L,
                0.0F,
                false
        );

        public EnvironmentState {
            fogRed = clampColor(fogRed, "fogRed");
            fogGreen = clampColor(fogGreen, "fogGreen");
            fogBlue = clampColor(fogBlue, "fogBlue");
            fogAlpha = clamp01Finite(fogAlpha, "fogAlpha");
            environmentalStart = finiteDistance(environmentalStart, "environmentalStart");
            environmentalEnd = finiteDistance(environmentalEnd, "environmentalEnd");
            renderDistanceStart = finiteDistance(renderDistanceStart, "renderDistanceStart");
            renderDistanceEnd = finiteDistance(renderDistanceEnd, "renderDistanceEnd");
            skyEnd = finiteDistance(skyEnd, "skyEnd");
            cloudEnd = finiteDistance(cloudEnd, "cloudEnd");
            requireFinite(cloudHeight, "cloudHeight");
            cloudRange = Math.max(0, cloudRange);
            cloudStatus = Math.max(0, cloudStatus);
            if (gameTime < 0L) {
                gameTime = 0L;
            }
            partialTicks = clamp01Finite(partialTicks, "partialTicks");
        }

        public static EnvironmentState unknown() {
            return UNKNOWN;
        }

        public boolean hasRenderContent() {
            return fogKnown || cloudKnown || skyVisible;
        }

        public String asLogFragment() {
            return "{fogKnown=" + fogKnown
                    + ", cloudKnown=" + cloudKnown
                    + ", cloudStatus=" + cloudStatus
                    + ", cloudRange=" + cloudRange
                    + ", skyVisible=" + skyVisible
                    + "}";
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requirePositive(float value, String name) {
        requireFinite(value, name);
        if (value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static float finiteDistance(float value, String name) {
        requireFinite(value, name);
        return Math.max(0.0F, Math.min(value, 1_048_576.0F));
    }

    private static float clampColor(float value, String name) {
        requireFinite(value, name);
        return Math.max(0.0F, Math.min(value, 4.0F));
    }

    private static float clamp01Finite(float value, String name) {
        requireFinite(value, name);
        return clamp01(value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static void requireUnitVector(float x, float y, float z, String name) {
        requireFinite(x, name + ".x");
        requireFinite(y, name + ".y");
        requireFinite(z, name + ".z");
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 1.0e-3F) {
            throw new IllegalArgumentException(name + " must be normalized");
        }
    }

    private static String sanitizeDebugName(String debugName) {
        if (debugName == null || debugName.isBlank()) {
            return "";
        }
        return debugName.length() <= 96 ? debugName : debugName.substring(0, 96);
    }
}
