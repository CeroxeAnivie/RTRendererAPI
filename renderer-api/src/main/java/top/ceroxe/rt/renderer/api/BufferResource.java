package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable descriptor of one versioned generic buffer resource.
 *
 * <p>This value describes identity, extent, and allowed roles only. Byte uploads are represented
 * by explicit commands so an allocation cannot be mistaken for initialized content.</p>
 */
public final class BufferResource implements RenderResource {
    private final RenderResourceId id;
    private final ResourceVersion version;
    private final long byteSize;
    private final Set<BufferUsage> usage;

    /**
     * Creates a buffer descriptor.
     *
     * @param id       non-null stable resource identity
     * @param version  non-null published content version
     * @param byteSize positive buffer extent in bytes
     * @param usage    non-empty declared access roles
     */
    public BufferResource(
            RenderResourceId id,
            ResourceVersion version,
            long byteSize,
            Set<BufferUsage> usage
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = Objects.requireNonNull(version, "version");
        if (byteSize <= 0L) {
            throw new IllegalArgumentException("buffer byte size must be positive");
        }
        this.byteSize = byteSize;
        Objects.requireNonNull(usage, "usage");
        if (usage.isEmpty()) {
            throw new IllegalArgumentException("buffer usage must not be empty");
        }
        EnumSet<BufferUsage> checked = EnumSet.noneOf(BufferUsage.class);
        for (BufferUsage role : usage) {
            checked.add(Objects.requireNonNull(role, "buffer usage element"));
        }
        this.usage = Collections.unmodifiableSet(checked);
    }

    /**
     * Returns the stable resource identity.
     *
     * @return non-null resource identity
     */
    public RenderResourceId id() {
        return id;
    }

    /**
     * Returns the published content version.
     *
     * @return non-null resource version
     */
    public ResourceVersion version() {
        return version;
    }

    /**
     * Returns the exact resource extent in bytes.
     *
     * @return positive byte size
     */
    public long byteSize() {
        return byteSize;
    }

    /**
     * Returns immutable declared access roles.
     *
     * @return non-empty immutable usage set
     */
    public Set<BufferUsage> usage() {
        return usage;
    }

    /**
     * Validates that a range is contained by this buffer.
     *
     * @param range non-null range to validate
     * @return the same validated range
     */
    public ByteRange requireContained(ByteRange range) {
        ByteRange checked = Objects.requireNonNull(range, "range");
        if (!checked.fitsWithin(byteSize)) {
            throw new IllegalArgumentException("buffer range exceeds resource extent");
        }
        return checked;
    }
}
