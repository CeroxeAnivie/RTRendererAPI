package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable portable sampling state.
 *
 * <p>All float-valued controls are finite at construction time. Backends must reject unsupported
 * anisotropy or comparison modes during capability negotiation instead of quietly changing this
 * requested sampling meaning.</p>
 */
public final class SamplerState {
    /** Sampling behavior within one mip level. */
    public enum Filter { NEAREST, LINEAR }

    /** Mip-level selection behavior. */
    public enum MipFilter { NEAREST, LINEAR }

    /** Behavior outside normalized texture coordinates. */
    public enum AddressMode { CLAMP_TO_EDGE, REPEAT, MIRRORED_REPEAT }

    /** Optional depth comparison operation. */
    public enum CompareOperation { NEVER, LESS, EQUAL, LESS_OR_EQUAL, GREATER, NOT_EQUAL, GREATER_OR_EQUAL, ALWAYS }

    private final Filter minFilter;
    private final Filter magFilter;
    private final MipFilter mipFilter;
    private final AddressMode addressU;
    private final AddressMode addressV;
    private final AddressMode addressW;
    private final float lodMinClamp;
    private final float lodMaxClamp;
    private final float maximumAnisotropy;
    private final CompareOperation compareOperation;

    private SamplerState(Builder builder) {
        minFilter = builder.minFilter;
        magFilter = builder.magFilter;
        mipFilter = builder.mipFilter;
        addressU = builder.addressU;
        addressV = builder.addressV;
        addressW = builder.addressW;
        lodMinClamp = builder.lodMinClamp;
        lodMaxClamp = builder.lodMaxClamp;
        maximumAnisotropy = builder.maximumAnisotropy;
        compareOperation = builder.compareOperation;
    }

    /** @return a builder using ordinary linear sampled-color defaults */
    public static Builder builder() { return new Builder(); }

    /** @return non-null minification filter */
    public Filter minFilter() { return minFilter; }

    /** @return non-null magnification filter */
    public Filter magFilter() { return magFilter; }

    /** @return non-null mip filter */
    public MipFilter mipFilter() { return mipFilter; }

    /** @return non-null U address mode */
    public AddressMode addressU() { return addressU; }

    /** @return non-null V address mode */
    public AddressMode addressV() { return addressV; }

    /** @return non-null W address mode */
    public AddressMode addressW() { return addressW; }

    /** @return finite minimum selected LOD */
    public float lodMinClamp() { return lodMinClamp; }

    /** @return finite maximum selected LOD */
    public float lodMaxClamp() { return lodMaxClamp; }

    /** @return finite requested anisotropy in the inclusive range [1, 16] */
    public float maximumAnisotropy() { return maximumAnisotropy; }

    /** @return nullable comparison operation; null means ordinary sampling */
    public CompareOperation compareOperation() { return compareOperation; }

    /** Single-thread-confined builder for immutable sampler state. */
    public static final class Builder {
        private Filter minFilter = Filter.LINEAR;
        private Filter magFilter = Filter.LINEAR;
        private MipFilter mipFilter = MipFilter.LINEAR;
        private AddressMode addressU = AddressMode.CLAMP_TO_EDGE;
        private AddressMode addressV = AddressMode.CLAMP_TO_EDGE;
        private AddressMode addressW = AddressMode.CLAMP_TO_EDGE;
        private float lodMinClamp = 0.0F;
        private float lodMaxClamp = 32.0F;
        private float maximumAnisotropy = 1.0F;
        private CompareOperation compareOperation;

        private Builder() { }

        /** @return this builder with a shared address mode for all axes */
        public Builder addressMode(AddressMode value) {
            AddressMode checked = Objects.requireNonNull(value, "addressMode");
            addressU = checked;
            addressV = checked;
            addressW = checked;
            return this;
        }

        /** @return this builder with independent axis modes */
        public Builder addressModes(AddressMode u, AddressMode v, AddressMode w) {
            addressU = Objects.requireNonNull(u, "addressU");
            addressV = Objects.requireNonNull(v, "addressV");
            addressW = Objects.requireNonNull(w, "addressW");
            return this;
        }

        /** @return this builder with selected minification filter */
        public Builder minFilter(Filter value) { minFilter = Objects.requireNonNull(value, "minFilter"); return this; }

        /** @return this builder with selected magnification filter */
        public Builder magFilter(Filter value) { magFilter = Objects.requireNonNull(value, "magFilter"); return this; }

        /** @return this builder with selected mip filter */
        public Builder mipFilter(MipFilter value) { mipFilter = Objects.requireNonNull(value, "mipFilter"); return this; }

        /** @return this builder with a finite inclusive LOD interval */
        public Builder lodClamp(float minimum, float maximum) {
            requireFinite(minimum, "lod minimum");
            requireFinite(maximum, "lod maximum");
            if (minimum > maximum) throw new IllegalArgumentException("lod minimum must not exceed lod maximum");
            lodMinClamp = minimum;
            lodMaxClamp = maximum;
            return this;
        }

        /** @return this builder with portable requested anisotropy */
        public Builder maximumAnisotropy(float value) {
            requireFinite(value, "maximum anisotropy");
            if (value < 1.0F || value > 16.0F) {
                throw new IllegalArgumentException("maximum anisotropy must be in [1, 16]");
            }
            maximumAnisotropy = value;
            return this;
        }

        /** @return this builder with an optional comparison operation */
        public Builder compareOperation(CompareOperation value) { compareOperation = value; return this; }

        /** @return immutable validated sampler state */
        public SamplerState build() { return new SamplerState(this); }

        private static void requireFinite(float value, String name) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
