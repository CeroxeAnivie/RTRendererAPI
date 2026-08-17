package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable synchronization dependency for one exact versioned texture subresource range. */
public record TextureBarrier(
        ResourceSlice.TextureSlice slice,
        Set<RenderPipelineStage> sourceStages,
        Set<RenderResourceAccess> sourceAccess,
        Set<RenderPipelineStage> destinationStages,
        Set<RenderResourceAccess> destinationAccess
) {
    /** Defensively copies synchronization masks and validates the addressed subresources. */
    public TextureBarrier {
        Objects.requireNonNull(slice, "slice");
        sourceStages = immutableNonEmpty(sourceStages, RenderPipelineStage.class, "sourceStages");
        destinationStages = immutableNonEmpty(destinationStages, RenderPipelineStage.class, "destinationStages");
        sourceAccess = immutable(sourceAccess, RenderResourceAccess.class, "sourceAccess");
        destinationAccess = immutable(destinationAccess, RenderResourceAccess.class, "destinationAccess");
        RenderBarrierValidator.validate(sourceStages, sourceAccess, destinationStages, destinationAccess);
        RenderBarrierValidator.validateTextureUsage(slice.resource(), sourceAccess);
        RenderBarrierValidator.validateTextureUsage(slice.resource(), destinationAccess);
    }

    private static <E extends Enum<E>> Set<E> immutableNonEmpty(Set<E> values, Class<E> type, String name) {
        Set<E> copy = immutable(values, type, name);
        if (copy.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return copy;
    }

    private static <E extends Enum<E>> Set<E> immutable(Set<E> values, Class<E> type, String name) {
        Objects.requireNonNull(values, name);
        EnumSet<E> copy = EnumSet.noneOf(type);
        for (E value : values) copy.add(Objects.requireNonNull(value, name + " element"));
        return Collections.unmodifiableSet(copy);
    }
}
