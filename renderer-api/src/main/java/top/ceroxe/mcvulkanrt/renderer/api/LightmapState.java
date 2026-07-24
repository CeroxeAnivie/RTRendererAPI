package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable frame-local two-axis irradiance lookup table.
 *
 * <p>Mesh lightmap coordinates address this linear RGBA8 table independently from vertex color
 * and material textures. Replacing the table therefore changes lighting without replacing mesh
 * assets or rebuilding acceleration structures.</p>
 */
public final class LightmapState {
    public static final int AXIS_SIZE = 16;
    public static final int ENTRY_COUNT = AXIS_SIZE * AXIS_SIZE;

    private static final LightmapState FULL_INTENSITY = new LightmapState(
            0L,
            fullIntensityTexels(),
            true
    );

    private final long revision;
    private final int[] texelsRgba8;

    public LightmapState(long revision, int[] texelsRgba8) {
        this(revision, texelsRgba8, false);
    }

    private LightmapState(long revision, int[] texelsRgba8, boolean trustedOwnership) {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        int[] checked = Objects.requireNonNull(texelsRgba8, "texelsRgba8");
        if (checked.length != ENTRY_COUNT) {
            throw new IllegalArgumentException(
                    "lightmap must contain exactly " + ENTRY_COUNT + " RGBA8 texels"
            );
        }
        this.revision = revision;
        this.texelsRgba8 = trustedOwnership ? checked : checked.clone();
    }

    /** Neutral lookup used by clients that do not provide vertex lightmap semantics. */
    public static LightmapState fullIntensity() {
        return FULL_INTENSITY;
    }

    public long revision() {
        return revision;
    }

    public IntBuffer texelsRgba8() {
        return IntBuffer.wrap(texelsRgba8).asReadOnlyBuffer();
    }

    private static int[] fullIntensityTexels() {
        int[] texels = new int[ENTRY_COUNT];
        Arrays.fill(texels, 0xffff_ffff);
        return texels;
    }
}
