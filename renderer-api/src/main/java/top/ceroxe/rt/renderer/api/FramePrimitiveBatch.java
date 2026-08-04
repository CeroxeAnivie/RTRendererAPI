package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, frame-replaced primitive instance batch.
 *
 * <p>The batch references persistent meshes and never owns geometry. Providers can consequently
 * upload only compact instance records and rebuild/update a frame-slot TLAS while reusing every
 * resident BLAS. Omitting a prior primitive from the next batch removes it automatically.</p>
 */
public final class FramePrimitiveBatch {
    /** Vulkan exposes a 24-bit custom index; one bit is reserved to select this record lane. */
    public static final int MAX_PRIMITIVES = 0x0080_0000;

    private static final FramePrimitiveBatch EMPTY = new FramePrimitiveBatch(List.of());
    private final List<PrimitiveInstance> primitives;

    private FramePrimitiveBatch(List<PrimitiveInstance> primitives) {
        List<PrimitiveInstance> checked = Objects.requireNonNull(primitives, "primitives");
        /* Reject an oversized view before copying it; otherwise a hostile List implementation can
         * force a multi-hundred-megabyte allocation before the public limit is enforced. */
        if (checked.size() > MAX_PRIMITIVES) {
            throw new IllegalArgumentException("frame primitive count exceeds " + MAX_PRIMITIVES);
        }
        this.primitives = List.copyOf(checked);
    }

    /**
     * Returns the shared empty frame batch.
     * @return the shared empty frame batch
     */
    public static FramePrimitiveBatch empty() {
        return EMPTY;
    }

    /**
     * Copies an ordered frame replacement batch.
     * @param primitives non-null primitives, no more than {@link #MAX_PRIMITIVES}
     * @return immutable batch
     */
    public static FramePrimitiveBatch of(List<? extends PrimitiveInstance> primitives) {
        Objects.requireNonNull(primitives, "primitives");
        if (primitives.size() > MAX_PRIMITIVES) {
            throw new IllegalArgumentException("frame primitive count exceeds " + MAX_PRIMITIVES);
        }
        return primitives.isEmpty() ? EMPTY : new FramePrimitiveBatch(List.copyOf(primitives));
    }

    /**
     * Starts a mutable, thread-confined batch builder.
     * @return a mutable batch builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns primitives in TLAS custom-index order.
     * @return immutable primitive list
     */
    public List<PrimitiveInstance> primitives() {
        return primitives;
    }

    /**
     * Returns the number of frame-scoped primitives.
     * @return primitive count
     */
    public int size() {
        return primitives.size();
    }

    /**
     * Reports whether this batch selects no frame-local primitives.
     * @return whether this batch is empty
     */
    public boolean isEmpty() {
        return primitives.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FramePrimitiveBatch batch
                && primitives.equals(batch.primitives);
    }

    @Override
    public int hashCode() {
        return primitives.hashCode();
    }

    @Override
    public String toString() {
        return "FramePrimitiveBatch[size=" + primitives.size() + ']';
    }

    /** Thread-confined builder that enforces the public primitive-count bound. */
    public static final class Builder {
        private final ArrayList<PrimitiveInstance> primitives = new ArrayList<>();

        private Builder() {
        }

        /**
         * Appends one primitive.
         * @param primitive non-null primitive
         * @return this builder
         */
        public Builder add(PrimitiveInstance primitive) {
            if (primitives.size() == MAX_PRIMITIVES) {
                throw new IllegalStateException("frame primitive count exceeds " + MAX_PRIMITIVES);
            }
            primitives.add(Objects.requireNonNull(primitive, "primitive"));
            return this;
        }

        /**
         * Appends primitives in iteration order.
         * @param values non-null primitive source
         * @return this builder
         */
        public Builder addAll(Iterable<? extends PrimitiveInstance> values) {
            Objects.requireNonNull(values, "values").forEach(this::add);
            return this;
        }

        /**
         * Builds an immutable snapshot of appended primitives.
         * @return immutable frame batch
         */
        public FramePrimitiveBatch build() {
            return primitives.isEmpty() ? EMPTY : new FramePrimitiveBatch(primitives);
        }
    }
}
