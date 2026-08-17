package top.ceroxe.rt.renderer.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, order-stable vertex-buffer and shader-location layout. */
public final class VertexLayout {
    private final List<VertexBufferLayout> buffers;
    private final List<VertexAttribute> attributes;
    private final Map<Integer, VertexBufferLayout> buffersByBinding;
    private final Map<Integer, VertexAttribute> attributesByLocation;

    /**
     * Creates a vertex layout and validates every cross-reference.
     *
     * <p>Attribute overlap is intentionally permitted because multiple shader inputs may legally
     * reinterpret the same bytes. Attribute offsets are not constrained by stride because zero
     * stride and overlapping consecutive fetches are valid semantics on capable backends.</p>
     *
     * @param buffers unique buffer-binding declarations
     * @param attributes unique shader-location declarations
     */
    public VertexLayout(List<VertexBufferLayout> buffers, List<VertexAttribute> attributes) {
        Objects.requireNonNull(buffers, "buffers");
        Objects.requireNonNull(attributes, "attributes");
        LinkedHashMap<Integer, VertexBufferLayout> checkedBuffers = new LinkedHashMap<>();
        for (VertexBufferLayout buffer : buffers) {
            VertexBufferLayout value = Objects.requireNonNull(buffer, "vertex buffer layout");
            if (checkedBuffers.putIfAbsent(value.binding(), value) != null) {
                throw new IllegalArgumentException("duplicate vertex-buffer binding: " + value.binding());
            }
        }
        LinkedHashMap<Integer, VertexAttribute> checkedAttributes = new LinkedHashMap<>();
        for (VertexAttribute attribute : attributes) {
            VertexAttribute value = Objects.requireNonNull(attribute, "vertex attribute");
            if (checkedAttributes.putIfAbsent(value.shaderLocation(), value) != null) {
                throw new IllegalArgumentException("duplicate shader location: " + value.shaderLocation());
            }
            VertexBufferLayout buffer = checkedBuffers.get(value.bufferBinding());
            if (buffer == null) {
                throw new IllegalArgumentException(
                        "vertex attribute references undeclared buffer binding: " + value.bufferBinding());
            }
        }
        this.buffers = List.copyOf(checkedBuffers.values());
        this.attributes = List.copyOf(checkedAttributes.values());
        this.buffersByBinding = Map.copyOf(checkedBuffers);
        this.attributesByLocation = Map.copyOf(checkedAttributes);
    }

    /** Returns an empty vertex layout for procedural vertex generation. */
    public static VertexLayout empty() { return new VertexLayout(List.of(), List.of()); }

    /** @return immutable declaration-ordered buffer layouts */
    public List<VertexBufferLayout> buffers() { return buffers; }

    /** @return immutable declaration-ordered attributes */
    public List<VertexAttribute> attributes() { return attributes; }

    /** @return immutable buffer lookup by binding */
    public Map<Integer, VertexBufferLayout> buffersByBinding() { return buffersByBinding; }

    /** @return immutable attribute lookup by shader location */
    public Map<Integer, VertexAttribute> attributesByLocation() { return attributesByLocation; }

    /** Returns a required buffer declaration. */
    public VertexBufferLayout requireBuffer(int binding) {
        VertexBufferLayout result = buffersByBinding.get(binding);
        if (result == null) throw new IllegalArgumentException("vertex-buffer binding is not declared: " + binding);
        return result;
    }

    /** Returns a required shader-location declaration. */
    public VertexAttribute requireAttribute(int shaderLocation) {
        VertexAttribute result = attributesByLocation.get(shaderLocation);
        if (result == null) throw new IllegalArgumentException("shader location is not declared: " + shaderLocation);
        return result;
    }
}
