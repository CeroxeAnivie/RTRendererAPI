package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Stable identity of a complete native image-import profile.
 *
 * <p>The profile names all backend-specific image creation, binding, ownership, and initial-access
 * rules deliberately omitted from {@link PortableFrameDescriptor}. A consumer may advertise a
 * profile only when it has an adapter that implements the entire profile. A matching memory
 * handle type alone is never sufficient evidence that an image can be imported correctly.</p>
 *
 * @param namespace stable profile owner namespace
 * @param name stable profile name within that namespace
 * @param version positive profile contract version
 */
public record ExternalImageImportProfile(String namespace, String name, int version) {
    public ExternalImageImportProfile {
        namespace = requireIdentifier(namespace, "namespace");
        name = requireIdentifier(name, "name");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(label + " must be a stable ASCII identifier");
        }
        return value;
    }
}
