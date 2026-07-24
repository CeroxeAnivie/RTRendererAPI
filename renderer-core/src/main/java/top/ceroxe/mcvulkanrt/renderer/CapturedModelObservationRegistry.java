package top.ceroxe.mcvulkanrt.renderer;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Objects;

/**
 * Owns the short-lived producer cache used while Minecraft captures dynamic
 * model submissions.
 *
 * <p>This is deliberately separate from {@link DynamicRenderSceneCollector}.
 * The collector owns renderer-frame publication, while this registry owns only
 * the bridge-facing capture identity needed to avoid rematerializing unchanged
 * model values. A world transition is a hard ownership boundary: a late
 * capture from the retired world must be discarded instead of being allowed to
 * reuse an entity ID, asset, or material from that world.</p>
 */
final class CapturedModelObservationRegistry {
    private static final int MAX_RETAINED_CAPTURE_MODEL_INSTANCES = 32_768;

    private final Object lock = new Object();
    private final Long2ObjectLinkedOpenHashMap<CaptureModelSlot> slots =
            new Long2ObjectLinkedOpenHashMap<>();
    private long frameEpoch;
    private long lifecycleEpoch = 1L;

    void beginFrame(long nextFrameEpoch) {
        requireFrameEpoch(nextFrameEpoch);
        synchronized (lock) {
            if (nextFrameEpoch <= frameEpoch) {
                slots.clear();
            }
            frameEpoch = nextFrameEpoch;
            long oldestRetainedEpoch = Math.max(0L, nextFrameEpoch - 1L);
            var iterator = slots.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().seenEpoch() < oldestRetainedEpoch) {
                    iterator.remove();
                }
            }
        }
    }

    /** Invalidates every producer slot from the retired renderer world. */
    void resetForWorld() {
        synchronized (lock) {
            slots.clear();
            frameEpoch = 0L;
            lifecycleEpoch = nextEpoch(lifecycleEpoch, "captured model lifecycle epoch");
        }
    }

    int retainedCount() {
        synchronized (lock) {
            return slots.size();
        }
    }

    DynamicRenderScene.DynamicModelObservation stage(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            DynamicMeshInstance.AffineTransform transform,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            DynamicRenderLane renderLane,
            long captureFrameEpoch
    ) {
        requireFrameEpoch(captureFrameEpoch);
        Objects.requireNonNull(transform, "transform");
        synchronized (lock) {
            CaptureModelSlot slot = captureSlot(id, captureFrameEpoch);
            if (slot == null) {
                return new DynamicRenderScene.DynamicModelInstance(
                        id, kind, asset, transform, faceMaterials, packedLight, debugName, renderLane
                );
            }
            return slot.stage(
                    kind, asset, transform, faceMaterials, packedLight, debugName, renderLane, captureFrameEpoch
            );
        }
    }

    DynamicRenderScene.DynamicModelObservation stage(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            Matrix4fc objectToWorld,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            long captureFrameEpoch
    ) {
        requireFrameEpoch(captureFrameEpoch);
        Objects.requireNonNull(objectToWorld, "objectToWorld");
        synchronized (lock) {
            CaptureModelSlot slot = captureSlot(id, captureFrameEpoch);
            if (slot == null) {
                return new DynamicRenderScene.DynamicModelInstance(
                        id, kind, asset, affineTransform(objectToWorld), faceMaterials, packedLight, debugName
                );
            }
            return slot.stage(
                    kind, asset, objectToWorld, faceMaterials, packedLight, debugName, null, captureFrameEpoch
            );
        }
    }

    DynamicRenderScene.DynamicModelInstance retain(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            DynamicMeshInstance.AffineTransform transform,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            DynamicRenderLane renderLane,
            long captureFrameEpoch
    ) {
        requireFrameEpoch(captureFrameEpoch);
        synchronized (lock) {
            CaptureModelSlot slot = slots.get(id);
            if (slot != null) {
                return slot.observe(kind, asset, transform, faceMaterials, packedLight, debugName, renderLane, captureFrameEpoch);
            }
            DynamicRenderScene.DynamicModelInstance created = new DynamicRenderScene.DynamicModelInstance(
                    id, kind, asset, transform, faceMaterials, packedLight, debugName, renderLane
            );
            if (slots.size() < MAX_RETAINED_CAPTURE_MODEL_INSTANCES) {
                slots.put(id, new CaptureModelSlot(created, captureFrameEpoch, lifecycleEpoch));
            }
            return created;
        }
    }

    DynamicRenderScene.DynamicModelInstance retain(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            Matrix4fc objectToWorld,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            long captureFrameEpoch
    ) {
        requireFrameEpoch(captureFrameEpoch);
        Objects.requireNonNull(objectToWorld, "objectToWorld");
        synchronized (lock) {
            CaptureModelSlot slot = slots.get(id);
            if (slot != null) {
                DynamicMeshInstance.AffineTransform transform = transformMatches(slot.instance().transform(), objectToWorld)
                        ? slot.instance().transform()
                        : affineTransform(objectToWorld);
                return slot.observe(kind, asset, transform, faceMaterials, packedLight, debugName, null, captureFrameEpoch);
            }
            DynamicRenderScene.DynamicModelInstance created = new DynamicRenderScene.DynamicModelInstance(
                    id, kind, asset, affineTransform(objectToWorld), faceMaterials, packedLight, debugName
            );
            if (slots.size() < MAX_RETAINED_CAPTURE_MODEL_INSTANCES) {
                slots.put(id, new CaptureModelSlot(created, captureFrameEpoch, lifecycleEpoch));
            }
            return created;
        }
    }

    void commit(DynamicRenderScene.DynamicModelObservation observation) {
        if (!(observation instanceof CaptureModelSlot slot)) {
            return;
        }
        synchronized (lock) {
            if (slot.belongsTo(lifecycleEpoch)) {
                slot.commitStaged();
            } else {
                slot.discardStaged();
            }
        }
    }

    void discard(DynamicRenderScene.DynamicModelObservation observation) {
        if (observation instanceof CaptureModelSlot slot) {
            synchronized (lock) {
                slot.discardStaged();
            }
        }
    }

    /** Rejects an observation that was staged by a retired world lifecycle. */
    boolean accepts(DynamicRenderScene.DynamicModelObservation observation) {
        if (!(observation instanceof CaptureModelSlot slot)) {
            return true;
        }
        synchronized (lock) {
            return slot.belongsTo(lifecycleEpoch);
        }
    }

    private CaptureModelSlot captureSlot(long id, long captureFrameEpoch) {
        CaptureModelSlot slot = slots.get(id);
        if (slot != null) {
            return slot;
        }
        if (slots.size() >= MAX_RETAINED_CAPTURE_MODEL_INSTANCES) {
            return null;
        }
        slot = new CaptureModelSlot(id, Math.max(0L, captureFrameEpoch - 2L), lifecycleEpoch);
        slots.put(id, slot);
        return slot;
    }

    private static void requireFrameEpoch(long epoch) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("captured model frame epoch must not be negative");
        }
    }

    private static long nextEpoch(long epoch, String name) {
        if (epoch == Long.MAX_VALUE) {
            throw new IllegalStateException(name + " exhausted");
        }
        return epoch + 1L;
    }

    private static DynamicMeshInstance.AffineTransform affineTransform(Matrix4fc matrix) {
        return new DynamicMeshInstance.AffineTransform(
                matrix.m00(), matrix.m10(), matrix.m20(), matrix.m30(),
                matrix.m01(), matrix.m11(), matrix.m21(), matrix.m31(),
                matrix.m02(), matrix.m12(), matrix.m22(), matrix.m32()
        );
    }

    private static boolean transformMatches(DynamicMeshInstance.AffineTransform transform, Matrix4fc matrix) {
        return Float.floatToIntBits(transform.m00()) == Float.floatToIntBits(matrix.m00())
                && Float.floatToIntBits(transform.m01()) == Float.floatToIntBits(matrix.m10())
                && Float.floatToIntBits(transform.m02()) == Float.floatToIntBits(matrix.m20())
                && Float.floatToIntBits(transform.m03()) == Float.floatToIntBits(matrix.m30())
                && Float.floatToIntBits(transform.m10()) == Float.floatToIntBits(matrix.m01())
                && Float.floatToIntBits(transform.m11()) == Float.floatToIntBits(matrix.m11())
                && Float.floatToIntBits(transform.m12()) == Float.floatToIntBits(matrix.m21())
                && Float.floatToIntBits(transform.m13()) == Float.floatToIntBits(matrix.m31())
                && Float.floatToIntBits(transform.m20()) == Float.floatToIntBits(matrix.m02())
                && Float.floatToIntBits(transform.m21()) == Float.floatToIntBits(matrix.m12())
                && Float.floatToIntBits(transform.m22()) == Float.floatToIntBits(matrix.m22())
                && Float.floatToIntBits(transform.m23()) == Float.floatToIntBits(matrix.m32());
    }

    private static final class CaptureModelSlot implements DynamicRenderScene.DynamicModelObservation {
        private final long id;
        private final long lifecycleEpoch;
        private DynamicRenderScene.DynamicModelInstance instance;
        private final float[] committedTransform = new float[12];
        private final float[] stagedTransform = new float[12];
        private long seenEpoch;
        private boolean staged;
        private long stagedEpoch;
        private DynamicRenderScene.PrimitiveKind stagedKind;
        private DynamicMeshAsset stagedAsset;
        private List<DynamicMeshInstance.FaceMaterial> stagedMaterials;
        private int stagedPackedLight;
        private String stagedDebugName;
        private DynamicRenderLane stagedRenderLane;

        private CaptureModelSlot(long id, long seenEpoch, long lifecycleEpoch) {
            if (id < 0L || seenEpoch < 0L || lifecycleEpoch <= 0L) {
                throw new IllegalArgumentException("capture model identity and epochs must be valid");
            }
            this.id = id;
            this.seenEpoch = seenEpoch;
            this.lifecycleEpoch = lifecycleEpoch;
        }

        private CaptureModelSlot(DynamicRenderScene.DynamicModelInstance instance, long seenEpoch, long lifecycleEpoch) {
            this(Objects.requireNonNull(instance, "instance").id(), seenEpoch, lifecycleEpoch);
            this.instance = instance;
            for (int component = 0; component < committedTransform.length; component++) {
                committedTransform[component] = instance.transformValue(component);
            }
        }

        private boolean belongsTo(long activeLifecycleEpoch) {
            return lifecycleEpoch == activeLifecycleEpoch;
        }

        private DynamicRenderScene.DynamicModelInstance instance() {
            if (instance != null) return instance;
            if (staged) return materialize();
            throw new IllegalStateException("capture model slot has no committed publication");
        }

        private long seenEpoch() { return seenEpoch; }
        private void seen(long epoch) { seenEpoch = epoch; }

        private DynamicRenderScene.DynamicModelInstance observe(
                DynamicRenderScene.PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane,
                long epoch
        ) {
            Objects.requireNonNull(transform, "transform");
            instance = instance == null
                    ? new DynamicRenderScene.DynamicModelInstance(id, kind, asset, transform, faceMaterials, packedLight, debugName, renderLane)
                    : instance.withObservation(kind, asset, transform, faceMaterials, packedLight, debugName, renderLane);
            for (int component = 0; component < committedTransform.length; component++) {
                committedTransform[component] = instance.transformValue(component);
            }
            seen(epoch);
            return instance;
        }

        private DynamicRenderScene.DynamicModelObservation stage(
                DynamicRenderScene.PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane,
                long epoch
        ) {
            Objects.requireNonNull(transform, "transform");
            beginStage(kind, asset, faceMaterials, packedLight, debugName, renderLane, epoch);
            for (int component = 0; component < stagedTransform.length; component++) {
                stagedTransform[component] = transform.value(component);
            }
            return this;
        }

        private DynamicRenderScene.DynamicModelObservation stage(
                DynamicRenderScene.PrimitiveKind kind,
                DynamicMeshAsset asset,
                Matrix4fc transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane,
                long epoch
        ) {
            Objects.requireNonNull(transform, "transform");
            beginStage(kind, asset, faceMaterials, packedLight, debugName, renderLane, epoch);
            stagedTransform[0] = transform.m00(); stagedTransform[1] = transform.m10();
            stagedTransform[2] = transform.m20(); stagedTransform[3] = transform.m30();
            stagedTransform[4] = transform.m01(); stagedTransform[5] = transform.m11();
            stagedTransform[6] = transform.m21(); stagedTransform[7] = transform.m31();
            stagedTransform[8] = transform.m02(); stagedTransform[9] = transform.m12();
            stagedTransform[10] = transform.m22(); stagedTransform[11] = transform.m32();
            return this;
        }

        private void beginStage(
                DynamicRenderScene.PrimitiveKind kind,
                DynamicMeshAsset asset,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane,
                long epoch
        ) {
            if (staged) {
                throw new IllegalStateException("capture model identity was staged twice before commit: " + id);
            }
            stagedKind = kind == null ? DynamicRenderScene.PrimitiveKind.ENTITY : kind;
            stagedAsset = Objects.requireNonNull(asset, "asset");
            stagedMaterials = Objects.requireNonNull(faceMaterials, "faceMaterials");
            if (stagedMaterials.size() != stagedAsset.faceCount()) {
                throw new IllegalArgumentException("captured model materials must match asset face count");
            }
            stagedPackedLight = packedLight;
            stagedDebugName = debugName == null ? "dynamic" : debugName;
            stagedRenderLane = renderLane == null ? DynamicRenderLane.fromFaceMaterials(stagedMaterials) : renderLane;
            stagedEpoch = epoch;
            staged = true;
        }

        private void commitStaged() {
            if (!staged) return;
            boolean publicationChanged = instance == null
                    || instance.kind() != stagedKind
                    || (instance.asset() != stagedAsset && !instance.asset().equals(stagedAsset))
                    || (instance.faceMaterials() != stagedMaterials && !instance.faceMaterials().equals(stagedMaterials))
                    || instance.packedLight() != stagedPackedLight
                    || instance.renderLane() != stagedRenderLane
                    || !instance.debugName().equals(stagedDebugName);
            if (publicationChanged) instance = materialize();
            System.arraycopy(stagedTransform, 0, committedTransform, 0, committedTransform.length);
            seen(stagedEpoch);
            discardStaged();
        }

        private void discardStaged() {
            staged = false;
            stagedEpoch = 0L;
            stagedKind = null;
            stagedAsset = null;
            stagedMaterials = null;
            stagedDebugName = null;
            stagedRenderLane = null;
        }

        @Override public long id() { return id; }
        @Override public DynamicRenderScene.PrimitiveKind kind() { return staged ? stagedKind : instance().kind(); }
        @Override public DynamicMeshAsset asset() { return staged ? stagedAsset : instance().asset(); }
        @Override public List<DynamicMeshInstance.FaceMaterial> faceMaterials() { return staged ? stagedMaterials : instance().faceMaterials(); }
        @Override public DynamicRenderLane renderLane() { return staged ? stagedRenderLane : instance().renderLane(); }
        @Override public int packedLight() { return staged ? stagedPackedLight : instance().packedLight(); }
        @Override public String debugName() { return staged ? stagedDebugName : instance().debugName(); }

        @Override
        public float transformValue(int component) {
            Objects.checkIndex(component, stagedTransform.length);
            return staged ? stagedTransform[component] : committedTransform[component];
        }
    }
}
