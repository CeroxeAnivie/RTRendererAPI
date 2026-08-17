package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies one explicit synchronization boundary to immutable buffer and texture slice lists. */
public record ResourceBarrierCommand(
        List<BufferBarrier> bufferBarriers,
        List<TextureBarrier> textureBarriers
) implements RenderCommand {
    /** Defensively copies barriers and rejects a semantically empty boundary. */
    public ResourceBarrierCommand {
        bufferBarriers = immutable(bufferBarriers, "bufferBarriers");
        textureBarriers = immutable(textureBarriers, "textureBarriers");
        if (bufferBarriers.isEmpty() && textureBarriers.isEmpty()) {
            throw new IllegalArgumentException("resource barrier command must contain at least one barrier");
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " element"));
        return List.copyOf(copy);
    }
}
