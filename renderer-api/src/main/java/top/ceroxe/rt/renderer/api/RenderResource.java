package top.ceroxe.rt.renderer.api;

/**
 * Common identity contract for versioned storage resources addressable by render commands.
 *
 * <p>Implementations remain exact resource descriptors. This interface intentionally does not
 * imply residency, initialization, mutability, or backend ownership.</p>
 */
public sealed interface RenderResource permits BufferResource, TextureResource {
    /** @return stable caller-assigned identity */
    RenderResourceId id();

    /** @return exact published resource generation */
    ResourceVersion version();
}
