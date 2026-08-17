package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Extensible identity and Java representation of an operating-system handle transport.
 *
 * @param namespace stable owner namespace
 * @param name stable transport name within that namespace
 * @param representation interpretation of {@link OwnedExternalHandle#nativeValue()}
 */
public record ExternalHandleTransport(
        String namespace,
        String name,
        Representation representation
) {
    /** Native handle in the Windows process handle table. */
    public static final ExternalHandleTransport WINDOWS_HANDLE =
            new ExternalHandleTransport("system", "windows-handle", Representation.NATIVE_POINTER);

    /** POSIX integer file descriptor. Descriptor zero remains a valid value. */
    public static final ExternalHandleTransport POSIX_FILE_DESCRIPTOR =
            new ExternalHandleTransport("system", "posix-file-descriptor", Representation.SIGNED_INTEGER);

    /** Mach port name represented as an unsigned native integer. */
    public static final ExternalHandleTransport MACH_PORT =
            new ExternalHandleTransport("system", "mach-port", Representation.UNSIGNED_INTEGER);

    /** Native pointer to an Android hardware-buffer handle. */
    public static final ExternalHandleTransport ANDROID_HARDWARE_BUFFER =
            new ExternalHandleTransport("system", "android-hardware-buffer", Representation.NATIVE_POINTER);

    public ExternalHandleTransport {
        namespace = requireIdentifier(namespace, "namespace");
        name = requireIdentifier(name, "name");
        representation = Objects.requireNonNull(representation, "representation");
    }

    /** Integral representation carried across the Java boundary. */
    public enum Representation {
        SIGNED_INTEGER,
        UNSIGNED_INTEGER,
        NATIVE_POINTER
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(label + " must be a stable ASCII identifier");
        }
        return value;
    }
}
