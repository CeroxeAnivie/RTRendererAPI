package top.ceroxe.mcvulkanrt.renderer;

/**
 * Stable identity encoding shared by the sourceEngine capture boundary and RT
 * diagnostics.  Keeping the namespace contract here prevents the RT owner
 * from copying bridge-private bit masks when it answers an entity query.
 */
public final class DynamicModelIdentity {
    private static final long ENTITY_MODEL_NAMESPACE = 0x0400_0000_0000_0000L;
    private static final long ENTITY_MODEL_NAMESPACE_MASK = 0xFF00_0000_0000_0000L;
    private static final int MAX_MODELS_PER_OWNER = 256;
    private static final int MAX_CUBES_PER_MODEL = 65_536;

    private DynamicModelIdentity() {
    }

    public static long entityModelPrimitiveId(long entityId, int modelOrdinal, int cubeOrdinal) {
        if (entityId < 0L || entityId > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("entity id must be an unsigned 32-bit value");
        }
        if (modelOrdinal < 0 || modelOrdinal >= MAX_MODELS_PER_OWNER
                || cubeOrdinal < 0 || cubeOrdinal >= MAX_CUBES_PER_MODEL) {
            throw new IllegalArgumentException("model primitive ordinal outside encoded range");
        }
        return ENTITY_MODEL_NAMESPACE | (entityId << 24) | ((long) modelOrdinal << 16) | cubeOrdinal;
    }

    /** Returns the sourceEngine entity id encoded in an entity model primitive, or -1 for other owners. */
    public static long entityIdFromPrimitiveId(long primitiveId) {
        if ((primitiveId & ENTITY_MODEL_NAMESPACE_MASK) != ENTITY_MODEL_NAMESPACE) {
            return -1L;
        }
        return (primitiveId >>> 24) & 0xFFFF_FFFFL;
    }
}
