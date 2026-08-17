package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Collision-resistant identity of an external synchronization-handle import contract.
 *
 * @param transport operating-system transport
 * @param namespace stable owner namespace of the import contract
 * @param name stable handle-type name within that namespace
 */
public record ExternalSynchronizationHandleType(
        ExternalHandleTransport transport,
        String namespace,
        String name
) {
    public ExternalSynchronizationHandleType {
        transport = Objects.requireNonNull(transport, "transport");
        namespace = requireIdentifier(namespace, "namespace");
        name = requireIdentifier(name, "name");
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(label + " must be a stable ASCII identifier");
        }
        return value;
    }
}
