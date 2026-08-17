package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable exact resource bindings for one {@link BindingLayout}.
 *
 * <p>Every declared slot and fixed array element must be present. Resource descriptors retain
 * both identity and version, so later replacement cannot silently alter an accepted binding set.</p>
 */
public final class BindingSet {
    /** One typed value accepted by a binding slot. */
    public sealed interface Value permits BufferValue, TextureValue, SamplerValue, CombinedImageSamplerValue,
            AccelerationStructureValue {
        /** @return exact binding category represented by this value */
        BindingType type();
    }

    /** Versioned non-empty buffer range bound to one buffer category. */
    public record BufferValue(BufferResource buffer, ByteRange range, BindingType type) implements Value {
        /** Validates the buffer range and category-specific usage. */
        public BufferValue {
            Objects.requireNonNull(buffer, "buffer");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(type, "type");
            buffer.requireContained(range);
            if (range.lengthBytes() == 0) throw new IllegalArgumentException("bound buffer range must not be empty");
            BufferUsage required = switch (type) {
                case UNIFORM_BUFFER -> BufferUsage.UNIFORM;
                case READ_ONLY_STORAGE_BUFFER -> BufferUsage.STORAGE_READ;
                case READ_WRITE_STORAGE_BUFFER -> BufferUsage.STORAGE_READ_WRITE;
                default -> throw new IllegalArgumentException("buffer value requires a buffer binding type");
            };
            if (!buffer.usage().contains(required)) {
                throw new IllegalArgumentException("buffer does not declare required usage: " + required);
            }
        }
    }

    /** Versioned texture view bound to one texture category. */
    public record TextureValue(TextureView view, BindingType type) implements Value {
        /** Validates the texture category-specific usage. */
        public TextureValue {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(type, "type");
            TextureUsage required = switch (type) {
                case SAMPLED_TEXTURE -> TextureUsage.SAMPLED;
                case READ_ONLY_STORAGE_TEXTURE -> TextureUsage.STORAGE_READ;
                case READ_WRITE_STORAGE_TEXTURE -> TextureUsage.STORAGE_READ_WRITE;
                default -> throw new IllegalArgumentException("texture value requires a texture binding type");
            };
            if (!view.texture().usage().contains(required)) {
                throw new IllegalArgumentException("texture does not declare required usage: " + required);
            }
        }
    }

    /** Immutable ordinary or comparison sampler binding. */
    public record SamplerValue(SamplerState sampler, BindingType type) implements Value {
        /** Validates sampler comparison semantics against the selected binding category. */
        public SamplerValue {
            Objects.requireNonNull(sampler, "sampler");
            Objects.requireNonNull(type, "type");
            if (type != BindingType.SAMPLER && type != BindingType.COMPARISON_SAMPLER) {
                throw new IllegalArgumentException("sampler value requires a sampler binding type");
            }
            boolean comparison = sampler.compareOperation() != null;
            if (comparison != (type == BindingType.COMPARISON_SAMPLER)) {
                throw new IllegalArgumentException("sampler comparison state does not match its binding type");
            }
        }
    }

    /**
     * Immutable sampled texture and sampler pair for one combined-image-sampler descriptor.
     *
     * <p>The pair is one value because native combined descriptors occupy one binding and one array
     * element. Splitting it into two values would alter the shader's reflected descriptor layout.</p>
     */
    public record CombinedImageSamplerValue(TextureView view, SamplerState sampler) implements Value {
        /** Validates sampled texture usage and both immutable pair members. */
        public CombinedImageSamplerValue {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
            if (!view.texture().usage().contains(TextureUsage.SAMPLED)) {
                throw new IllegalArgumentException("combined image sampler view requires SAMPLED texture usage");
            }
        }

        /** @return the one exact descriptor category represented by this pair */
        @Override
        public BindingType type() {
            return BindingType.COMBINED_IMAGE_SAMPLER;
        }
    }

    /** Exact top-level acceleration structure bound to a ray-tracing shader slot. */
    public record AccelerationStructureValue(AccelerationStructureResource accelerationStructure) implements Value {
        /** Rejects bottom-level structures because shader-visible trace roots must be top-level. */
        public AccelerationStructureValue {
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
            if (accelerationStructure.kind() != AccelerationStructureKind.TOP_LEVEL) {
                throw new IllegalArgumentException("shader acceleration-structure bindings require a TOP_LEVEL resource");
            }
        }

        /** @return the exact shader-visible descriptor category */
        @Override
        public BindingType type() {
            return BindingType.ACCELERATION_STRUCTURE;
        }
    }

    private final BindingLayout layout;
    private final Map<BindingKey, List<Value>> values;

    /**
     * Creates and fully validates exact bindings.
     *
     * @param layout non-null expected layout
     * @param values exact value arrays keyed by declared locations
     */
    public BindingSet(BindingLayout layout, Map<BindingKey, ? extends List<? extends Value>> values) {
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(values, "values");
        if (!values.keySet().equals(layout.entriesByKey().keySet())) {
            throw new IllegalArgumentException("binding set keys must exactly match its layout");
        }
        LinkedHashMap<BindingKey, List<Value>> checked = new LinkedHashMap<>();
        for (BindingLayoutEntry entry : layout.entries()) {
            List<? extends Value> supplied = Objects.requireNonNull(values.get(entry.key()), "binding value array");
            if (supplied.size() != entry.arrayCount()) {
                throw new IllegalArgumentException("binding value count does not match layout at " + entry.key());
            }
            ArrayList<Value> copied = new ArrayList<>(supplied.size());
            for (Value value : supplied) {
                Value checkedValue = Objects.requireNonNull(value, "binding value");
                if (checkedValue.type() != entry.type()) {
                    throw new IllegalArgumentException("binding value type does not match layout at " + entry.key());
                }
                copied.add(checkedValue);
            }
            checked.put(entry.key(), List.copyOf(copied));
        }
        this.values = Collections.unmodifiableMap(checked);
    }

    /** @return non-null immutable layout */
    public BindingLayout layout() { return layout; }

    /** @return immutable binding arrays keyed by exact locations */
    public Map<BindingKey, List<Value>> values() { return values; }
}
