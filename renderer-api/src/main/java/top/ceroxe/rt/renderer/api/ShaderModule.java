package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable SPIR-V shader-module request with an explicit stage and interface declaration.
 *
 * <p>The module stores a defensive copy of complete SPIR-V words. It is not executable evidence:
 * a backend must validate the binary, entry point, capabilities, and reflected interface before
 * accepting a program that contains it.</p>
 */
public final class ShaderModule {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final Pattern ENTRY_POINT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final RenderResourceId id;
    private final ResourceVersion version;
    private final ShaderStage stage;
    private final String entryPoint;
    private final ByteBuffer spirv;
    private final ShaderReflection reflection;

    /**
     * Creates a shader module from complete SPIR-V words in native byte order.
     *
     * @param id stable module identity
     * @param version published module generation
     * @param stage one explicit shader stage
     * @param entryPoint portable identifier of the requested entry point
     * @param spirv remaining bytes containing a complete SPIR-V module
     * @param reflection caller-declared interface to verify against the module
     */
    public ShaderModule(
            RenderResourceId id,
            ResourceVersion version,
            ShaderStage stage,
            String entryPoint,
            ByteBuffer spirv,
            ShaderReflection reflection
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = Objects.requireNonNull(version, "version");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        if (!ENTRY_POINT.matcher(entryPoint).matches()) {
            throw new IllegalArgumentException("shader entry point must be a portable identifier");
        }
        Objects.requireNonNull(spirv, "spirv");
        if (spirv.order() != ByteOrder.nativeOrder()) {
            throw new IllegalArgumentException("SPIR-V byte buffer must use native byte order");
        }
        ByteBuffer source = spirv.slice().order(spirv.order());
        if (source.remaining() < 20 || (source.remaining() & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V module must contain a complete aligned header and words");
        }
        if (source.order(spirv.order()).getInt(0) != SPIRV_MAGIC) {
            throw new IllegalArgumentException("shader module does not begin with the SPIR-V magic word");
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(spirv.order());
        copy.put(source).flip();
        this.spirv = copy.asReadOnlyBuffer().order(spirv.order());
        this.reflection = Objects.requireNonNull(reflection, "reflection");
        for (BindingLayoutEntry entry : reflection.bindings()) {
            if (!entry.visibleStages().contains(stage)) {
                throw new IllegalArgumentException("module reflection contains a binding not visible to its shader stage");
            }
        }
    }

    /** @return stable module identity */
    public RenderResourceId id() { return id; }

    /** @return published module generation */
    public ResourceVersion version() { return version; }

    /** @return explicit shader stage */
    public ShaderStage stage() { return stage; }

    /** @return exact requested entry point */
    public String entryPoint() { return entryPoint; }

    /** @return independent read-only SPIR-V byte view positioned at zero */
    public ByteBuffer spirv() { return spirv.duplicate().order(ByteOrder.nativeOrder()); }

    /** @return declared interface to verify during backend compilation */
    public ShaderReflection reflection() { return reflection; }
}
