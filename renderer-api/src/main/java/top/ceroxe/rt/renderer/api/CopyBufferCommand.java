package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Copies equal-length exact slices between two buffer generations. */
public record CopyBufferCommand(
        ResourceSlice.BufferSlice source,
        ResourceSlice.BufferSlice destination
) implements RenderCommand {
    /** Validates usage, non-empty equal extents, and non-overlapping self-copy semantics. */
    public CopyBufferCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (!source.resource().usage().contains(BufferUsage.COPY_SOURCE)) {
            throw new IllegalArgumentException("source buffer does not declare COPY_SOURCE usage");
        }
        if (!destination.resource().usage().contains(BufferUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("destination buffer does not declare COPY_DESTINATION usage");
        }
        long length = source.range().lengthBytes();
        if (length == 0L || destination.range().lengthBytes() != length) {
            throw new IllegalArgumentException("buffer copy slices must have the same non-zero length");
        }
        boolean sameGeneration = source.resource().id().equals(destination.resource().id())
                && source.resource().version().equals(destination.resource().version());
        if (sameGeneration && overlaps(source.range(), destination.range())) {
            throw new IllegalArgumentException("overlapping copies within one buffer generation are undefined");
        }
    }

    private static boolean overlaps(ByteRange first, ByteRange second) {
        return first.offsetBytes() < second.endExclusive() && second.offsetBytes() < first.endExclusive();
    }
}
