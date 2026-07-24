package top.ceroxe.mcvulkanrt.renderer.rt.material;

import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
/**
 * Renderer-owned material texture store consumed by the RT closest-hit path.
 *
 * <p>This package owns stable texture ids, immutable snapshots, animation revisions
 * and dirty pixel segments. host atlas and model inspection belongs to
 * {@code bridge.material}; it may register immutable texture facts here but may
 * never leak client objects into this store.</p>
 */
public final class RtTextureCatalog {
    public static final int MISSING_TEXTURE_ID = 0;
    public static final int RENDER_LAYER_SOLID = 0;
    public static final int RENDER_LAYER_CUTOUT = 1;
    public static final int RENDER_LAYER_TRANSLUCENT = 2;
    public static final int INTS_PER_TEXTURE_RECORD = 4;
    static final int TEXTURE_FLAG_HAS_TRANSPARENT_TEXELS = 1;

    private static final Object LOCK = new Object();
    private static final Map<String, Integer> TEXTURE_IDS = new HashMap<>();
    private static final List<TextureEntry> TEXTURES = new ArrayList<>();
    private static final int QUAD_VERTEX_COUNT = 4;
    private static final int POSITION_COMPONENTS = 3;
    private static final int QUAD_POSITION_FLOATS = QUAD_VERTEX_COUNT * POSITION_COMPONENTS;
    private static final float SIMPLE_CUBE_POSITION_EPSILON = 1.0e-4F;
    private static final float[] UNIT_SCALE_CANDIDATES = {1.0F, 1.0F / 16.0F};
    private static final float[][] GENERATED_FACE_VERTICES = {
            {
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F,
                    0.0F, 1.0F, 1.0F,
                    0.0F, 1.0F, 0.0F
            },
            {
                    1.0F, 0.0F, 1.0F,
                    1.0F, 0.0F, 0.0F,
                    1.0F, 1.0F, 0.0F,
                    1.0F, 1.0F, 1.0F
            },
            {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 1.0F,
                    0.0F, 0.0F, 1.0F
            },
            {
                    0.0F, 1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F,
                    1.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
            },
            {
                    1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F,
                    1.0F, 1.0F, 0.0F
            },
            {
                    0.0F, 0.0F, 1.0F,
                    1.0F, 0.0F, 1.0F,
                    1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F, 1.0F
            }
    };
    private static long revision;
    private static long animationTicks;
    /*
     * A texture generation is immutable once its revision is published.  The
     * material, section and far-field lanes may all request that generation in
     * one frame; rebuilding the flattened atlas for every consumer copied the
     * complete pixel set several times before any dirty decision was made.
     */
    private static Snapshot cachedSnapshot;
    private static long snapshotGeneration;
    private static long cachedSnapshotGeneration = -1L;
    /* Texture ids changed since the last published immutable generation. */
    private static final Set<Integer> dirtyTextureIds = new LinkedHashSet<>();
    private static volatile MaterialFactResolver materialFactResolver = MaterialFactResolver.missing();
    private static volatile RtMaterialTelemetrySink materialTelemetry = RtMaterialTelemetrySink.NOOP;
    private static Object materialTelemetryOwner;

    static {
        synchronized (LOCK) {
            addTextureLocked(
                    "mcvulkanrt:missing",
                    2,
                    2,
                    new int[]{
                            rgba8(255, 0, 255, 255),
                            rgba8(0, 0, 0, 255),
                            rgba8(0, 0, 0, 255),
                            rgba8(255, 0, 255, 255)
                    }
            );
        }
    }

    private RtTextureCatalog() {
    }

    /**
     * Installs renderer-lifecycle telemetry for the current static catalog owner.
     * The returned scope must be closed by the same composition root.
     */
    public static TelemetryScope installMaterialTelemetry(RtMaterialTelemetrySink telemetry) {
        RtMaterialTelemetrySink installed = Objects.requireNonNull(telemetry, "telemetry");
        Object owner = new Object();
        long installedRevision;
        synchronized (LOCK) {
            if (materialTelemetryOwner != null) {
                throw new IllegalStateException("RT texture catalog material telemetry is already installed");
            }
            materialTelemetryOwner = owner;
            materialTelemetry = installed;
            installedRevision = revision;
        }
        installed.textureRegistered(
                MISSING_TEXTURE_ID,
                installedRevision,
                1,
                "mcvulkanrt:missing",
                2,
                2,
                4,
                false,
                0
        );
        return new TelemetryScope(owner);
    }

    /**
     * Installs the host-facing extractor.  The extractor may observe
     * client objects while producing these facts, but every returned value is
     * renderer-owned and therefore safe for scene and Vulkan consumers.
     */
    public static void installMaterialFactResolver(MaterialFactResolver resolver) {
        materialFactResolver = Objects.requireNonNull(resolver, "resolver");
    }

    public static FaceTexture resolveFaceTexture(int voxelTypeId, FaceDirection direction) {
        return materialFactResolver.resolveFaceTexture(voxelTypeId, Objects.requireNonNull(direction, "direction"));
    }

    public static FaceTexture resolveFaceTexture(
            int voxelTypeId,
            FaceDirection direction,
            int worldX,
            int worldY,
            int worldZ
    ) {
        return materialFactResolver.resolveFaceTexture(
                voxelTypeId,
                Objects.requireNonNull(direction, "direction"),
                worldX,
                worldY,
                worldZ
        );
    }

    public static FaceTexture resolveFluidTexture(
            int voxelTypeId,
            FaceDirection direction,
            int mediumAmount,
            boolean flowingSurface,
            boolean overlaySide
    ) {
        return materialFactResolver.resolveFluidTexture(
                voxelTypeId,
                Objects.requireNonNull(direction, "direction"),
                mediumAmount,
                flowingSurface,
                overlaySide
        );
    }

    public static ModelQuads resolveModelQuads(int voxelTypeId) {
        return materialFactResolver.resolveModelQuads(voxelTypeId);
    }

    public static ModelQuads resolveModelQuads(int voxelTypeId, int worldX, int worldY, int worldZ) {
        return materialFactResolver.resolveModelQuads(voxelTypeId, worldX, worldY, worldZ);
    }

    public interface MaterialFactResolver {
        FaceTexture resolveFaceTexture(int voxelTypeId, FaceDirection direction);

        default FaceTexture resolveFaceTexture(
                int voxelTypeId,
                FaceDirection direction,
                int worldX,
                int worldY,
                int worldZ
        ) {
            return resolveFaceTexture(voxelTypeId, direction);
        }

        FaceTexture resolveFluidTexture(
                int voxelTypeId,
                FaceDirection direction,
                int mediumAmount,
                boolean flowingSurface,
                boolean overlaySide
        );

        ModelQuads resolveModelQuads(int voxelTypeId);

        default ModelQuads resolveModelQuads(int voxelTypeId, int worldX, int worldY, int worldZ) {
            return resolveModelQuads(voxelTypeId);
        }

        static MaterialFactResolver missing() {
            return new MaterialFactResolver() {
                @Override
                public FaceTexture resolveFaceTexture(int voxelTypeId, FaceDirection direction) {
                    return FaceTexture.missing();
                }

                @Override
                public FaceTexture resolveFluidTexture(
                        int voxelTypeId,
                        FaceDirection direction,
                        int mediumAmount,
                        boolean flowingSurface,
                        boolean overlaySide
                ) {
                    return FaceTexture.missing();
                }

                @Override
                public ModelQuads resolveModelQuads(int voxelTypeId) {
                    return ModelQuads.generatedFaceFallback();
                }
            };
        }
    }

    public static Snapshot snapshot() {
        Snapshot snapshot;
        synchronized (LOCK) {
            if (cachedSnapshot != null && cachedSnapshotGeneration == snapshotGeneration) {
                return cachedSnapshot;
            }
            int[] textureRecords = new int[Math.multiplyExact(TEXTURES.size(), INTS_PER_TEXTURE_RECORD)];
            int[][] pixelSegments = new int[TEXTURES.size()][];
            int totalPixels = 0;
            for (TextureEntry texture : TEXTURES) {
                totalPixels = Math.addExact(totalPixels, texture.pixels.length);
            }
            int pixelCursor = 0;
            for (int index = 0; index < TEXTURES.size(); index++) {
                TextureEntry texture = TEXTURES.get(index);
                int recordOffset = index * INTS_PER_TEXTURE_RECORD;
                textureRecords[recordOffset] = pixelCursor;
                textureRecords[recordOffset + 1] = texture.width();
                textureRecords[recordOffset + 2] = texture.height();
                textureRecords[recordOffset + 3] = texture.hasTransparentTexels() ? TEXTURE_FLAG_HAS_TRANSPARENT_TEXELS : 0;
                int[] texturePixels = texture.pixels;
                pixelSegments[index] = texturePixels;
                pixelCursor = Math.addExact(pixelCursor, texturePixels.length);
            }
            snapshot = Snapshot.fromOwnedSegments(
                    textureRecords,
                    pixelSegments,
                    totalPixels,
                    revision,
                    cachedSnapshot,
                    Set.copyOf(dirtyTextureIds)
            );
            cachedSnapshot = snapshot;
            cachedSnapshotGeneration = snapshotGeneration;
            dirtyTextureIds.clear();
        }
        materialTelemetry.textureCatalogSnapshot(
                snapshot.revision(),
                snapshot.textureCount(),
                snapshot.texturePixelCount(),
                snapshot.estimatedBytes()
        );
        return snapshot;
    }

    public static long revision() {
        synchronized (LOCK) {
            return revision;
        }
    }

    /**
     * Registers immutable RGBA8 pixels supplied by the bridge and returns the
     * stable shader texture id. Existing registrations retain their id so a
     * resource reload cannot silently remap section material records.
     */
    public static RegisteredTexture registerTexture(String name, int width, int height, int[] rgba8Pixels) {
        Objects.requireNonNull(name, "name");
        TextureEntry registered;
        int textureId;
        long catalogRevision;
        int textureCount;
        synchronized (LOCK) {
            Integer existing = TEXTURE_IDS.get(name);
            if (existing != null) {
                registered = TEXTURES.get(existing);
                return new RegisteredTexture(existing, registered.hasTransparentTexels());
            }
            textureId = addTextureLocked(name, width, height, rgba8Pixels);
            registered = TEXTURES.get(textureId);
            catalogRevision = revision;
            textureCount = TEXTURES.size();
        }
        logTextureRegistered(textureId, catalogRevision, textureCount, registered);
        return new RegisteredTexture(textureId, registered.hasTransparentTexels());
    }

    public static RegisteredTexture texture(int textureId) {
        synchronized (LOCK) {
            if (textureId < 0 || textureId >= TEXTURES.size()) {
                throw new IllegalArgumentException("unknown material texture id: " + textureId);
            }
            TextureEntry texture = TEXTURES.get(textureId);
            return new RegisteredTexture(textureId, texture.hasTransparentTexels());
        }
    }

    /** Canonical BakedQuad material fact used by the renderer-neutral Oracle trace. */
    public static boolean textureAnimated(int textureId) {
        synchronized (LOCK) {
            if (textureId < 0 || textureId >= TEXTURES.size()) {
                return false;
            }
            return TEXTURES.get(textureId).animation() != null;
        }
    }

    /**
     * Replaces one complete immutable texture generation without changing its stable id.
     * Unlike a frame update, this operation permits a resource reload to change dimensions.
     */
    public static void replaceTexture(int textureId, int width, int height, int[] rgba8Pixels) {
        Objects.requireNonNull(rgba8Pixels, "rgba8Pixels");
        synchronized (LOCK) {
            if (textureId <= MISSING_TEXTURE_ID || textureId >= TEXTURES.size()) {
                throw new IllegalArgumentException("unknown material texture id: " + textureId);
            }
            TextureEntry current = TEXTURES.get(textureId);
            TextureEntry replacement = new TextureEntry(
                    current.name(), width, height, rgba8Pixels, null
            );
            TEXTURES.set(textureId, replacement);
            revision++;
            snapshotGeneration++;
            dirtyTextureIds.add(textureId);
        }
    }

    /** Updates one already-registered texture without changing its stable id. */
    public static void replaceTexturePixels(int textureId, int[] rgba8Pixels) {
        Objects.requireNonNull(rgba8Pixels, "rgba8Pixels");
        synchronized (LOCK) {
            if (textureId <= MISSING_TEXTURE_ID || textureId >= TEXTURES.size()) {
                throw new IllegalArgumentException("unknown material texture id: " + textureId);
            }
            TextureEntry current = TEXTURES.get(textureId);
            TEXTURES.set(textureId, current.withCopiedPixels(rgba8Pixels));
            revision++;
            snapshotGeneration++;
            dirtyTextureIds.add(textureId);
        }
    }

    /**
     * Publishes an animation frame whose array is permanently owned by the immutable animation.
     * External texture mirrors must use {@link #replaceTexturePixels(int, int[])} instead.
     */
    public static void replaceTexturePixelsSharedImmutable(int textureId, int[] rgba8Pixels) {
        Objects.requireNonNull(rgba8Pixels, "rgba8Pixels");
        synchronized (LOCK) {
            if (textureId <= MISSING_TEXTURE_ID || textureId >= TEXTURES.size()) {
                throw new IllegalArgumentException("unknown material texture id: " + textureId);
            }
            TextureEntry current = TEXTURES.get(textureId);
            TEXTURES.set(textureId, current.withSharedImmutablePixels(rgba8Pixels));
            revision++;
            snapshotGeneration++;
            dirtyTextureIds.add(textureId);
        }
    }

    public static AnimationUpdate advanceAnimations() {
        AnimationUpdate update;
        synchronized (LOCK) {
            animationTicks++;
            int animatedTextures = 0;
            int changedTextures = 0;
            int changedPixels = 0;
            for (int textureId = 0; textureId < TEXTURES.size(); textureId++) {
                TextureEntry texture = TEXTURES.get(textureId);
                TextureAnimation animation = texture.animation();
                if (animation == null) {
                    continue;
                }
                animatedTextures++;
                int[] nextPixels = animation.advanceFramePixels();
                if (nextPixels == null || Arrays.equals(nextPixels, texture.pixels)) {
                    continue;
                }
                TEXTURES.set(textureId, texture.withSharedImmutablePixels(nextPixels));
                dirtyTextureIds.add(textureId);
                changedTextures++;
                changedPixels = Math.addExact(changedPixels, nextPixels.length);
            }
            if (changedTextures > 0) {
                revision++;
                snapshotGeneration++;
            }
            update = new AnimationUpdate(animationTicks, revision, animatedTextures, changedTextures, changedPixels);
        }
        materialTelemetry.textureAnimationAdvanced(
                update.animationTicks(),
                update.revision(),
                update.animatedTextures(),
                update.changedTextures(),
                update.changedPixels()
        );
        return update;
    }

    public static TestTextureScope installTestTexturesForSelfTest(List<TestTexture> testTextures) {
        Objects.requireNonNull(testTextures, "testTextures");
        synchronized (LOCK) {
            Map<String, Integer> previousTextureIds = new HashMap<>(TEXTURE_IDS);
            List<TextureEntry> previousTextures = new ArrayList<>(TEXTURES);
            long previousRevision = revision;
            long previousAnimationTicks = animationTicks;
            for (TestTexture texture : testTextures) {
                TestTexture checked = Objects.requireNonNull(texture, "test texture");
                if (TEXTURE_IDS.containsKey(checked.name())) {
                    throw new IllegalArgumentException("test texture name already exists: " + checked.name());
                }
                addTextureLocked(checked.name(), checked.width(), checked.height(), checked.rgba8Pixels());
            }
            return new TestTextureScope(
                    previousTextureIds,
                    previousTextures,
                    previousRevision,
                    previousAnimationTicks
            );
        }
    }

    public static TestTextureScope installAnimatedTestTexturesForSelfTest(List<TestAnimatedTexture> testTextures) {
        Objects.requireNonNull(testTextures, "testTextures");
        synchronized (LOCK) {
            Map<String, Integer> previousTextureIds = new HashMap<>(TEXTURE_IDS);
            List<TextureEntry> previousTextures = new ArrayList<>(TEXTURES);
            long previousRevision = revision;
            long previousAnimationTicks = animationTicks;
            for (TestAnimatedTexture texture : testTextures) {
                TestAnimatedTexture checked = Objects.requireNonNull(texture, "test animated texture");
                if (TEXTURE_IDS.containsKey(checked.name())) {
                    throw new IllegalArgumentException("test texture name already exists: " + checked.name());
                }
                TextureAnimation animation = testAnimation(checked);
                addTextureLocked(
                        checked.name(),
                        checked.width(),
                        checked.height(),
                        animation.currentFramePixels(),
                        animation
                );
            }
            return new TestTextureScope(
                    previousTextureIds,
                    previousTextures,
                    previousRevision,
                    previousAnimationTicks
            );
        }
    }

    static TestTextureScope installLoadedAnimatedTestTexturesForSelfTest(List<TestAnimatedTexture> testTextures) {
        Objects.requireNonNull(testTextures, "testTextures");
        synchronized (LOCK) {
            Map<String, Integer> previousTextureIds = new HashMap<>(TEXTURE_IDS);
            List<TextureEntry> previousTextures = new ArrayList<>(TEXTURES);
            long previousRevision = revision;
            long previousAnimationTicks = animationTicks;
            for (TestAnimatedTexture texture : testTextures) {
                TestAnimatedTexture checked = Objects.requireNonNull(texture, "test loaded animated texture");
                if (TEXTURE_IDS.containsKey(checked.name())) {
                    throw new IllegalArgumentException("test texture name already exists: " + checked.name());
                }
                TextureAnimation animation = testAnimation(checked);
                addTextureEntryLocked(new TextureEntry(
                        checked.name(),
                        checked.width(),
                        checked.height(),
                        animation.currentFramePixels(),
                        animation.hasTransparentTexels(),
                        animation
                ));
            }
            return new TestTextureScope(
                    previousTextureIds,
                    previousTextures,
                    previousRevision,
                    previousAnimationTicks
            );
        }
    }

    private static int addTextureLocked(String name, int width, int height, int[] pixels) {
        return addTextureLocked(name, width, height, pixels, null);
    }

    private static int addTextureLocked(String name, int width, int height, int[] pixels, TextureAnimation animation) {
        return addTextureEntryLocked(new TextureEntry(name, width, height, pixels, animation));
    }

    private static int addTextureEntryLocked(TextureEntry texture) {
        texture = Objects.requireNonNull(texture, "texture");
        if (TEXTURE_IDS.containsKey(texture.name())) {
            throw new IllegalArgumentException("texture name already exists: " + texture.name());
        }
        int textureId = TEXTURES.size();
        TEXTURES.add(texture);
        TEXTURE_IDS.put(texture.name(), textureId);
        revision++;
        snapshotGeneration++;
        dirtyTextureIds.add(textureId);
        return textureId;
    }

    private static TextureEntry missingTextureEntry(String requestedName) {
        TextureEntry missing = TEXTURES.get(MISSING_TEXTURE_ID);
        return new TextureEntry(
                requestedName,
                missing.width(),
                missing.height(),
                missing.pixels(),
                missing.hasTransparentTexels(),
                null
        );
    }

    private static void logTextureRegistered(
            int textureId,
            long catalogRevision,
            int catalogTextureCount,
            TextureEntry texture
    ) {
        materialTelemetry.textureRegistered(
                textureId,
                catalogRevision,
                catalogTextureCount,
                texture.name(),
                texture.width(),
                texture.height(),
                texture.pixelCount(),
                texture.hasTransparentTexels(),
                animationFrameCount(texture.animation())
        );
    }

    private static int animationFrameCount(TextureAnimation animation) {
        return animation == null ? 0 : animation.frameCount();
    }

    private static TextureAnimation testAnimation(TestAnimatedTexture texture) {
        List<int[]> frames = texture.rgba8Frames();
        int[][] framePixels = new int[frames.size()][];
        List<TextureAnimation.FrameTiming> timings = new ArrayList<>(frames.size());
        boolean hasTransparentTexels = false;
        for (int index = 0; index < frames.size(); index++) {
            int[] pixels = frames.get(index);
            framePixels[index] = pixels;
            hasTransparentTexels |= hasTransparentTexels(pixels);
            timings.add(new TextureAnimation.FrameTiming(index, texture.frameDurationTicks()));
        }
        return new TextureAnimation(
                texture.name(),
                texture.width(),
                texture.height(),
                framePixels,
                timings,
                hasTransparentTexels
        );
    }

    private static boolean textureHasTransparentTexels(int textureId) {
        synchronized (LOCK) {
            return textureId >= 0 && textureId < TEXTURES.size() && TEXTURES.get(textureId).hasTransparentTexels();
        }
    }

    public static boolean materialAlphaCutout(String layerName, boolean hasTransparentTexels) {
        if (!hasTransparentTexels || layerName == null) {
            return false;
        }
        return "CUTOUT".equals(layerName) || "CUTOUT_MIPPED".equals(layerName);
    }

    public static boolean textureRegionHasAlphaCutoutTexels(int textureId, int uv0, int uv1, int uv2, int uv3) {
        synchronized (LOCK) {
            return textureId >= 0
                    && textureId < TEXTURES.size()
                    && TEXTURES.get(textureId).regionHasAlphaCutoutTexels(uv0, uv1, uv2, uv3);
        }
    }

    private static boolean hasTransparentTexels(int[] pixels) {
        for (int pixel : pixels) {
            if (((pixel >>> 24) & 0xFF) < 255) {
                return true;
            }
        }
        return false;
    }

    public static int packUv16(float u, float v) {
        int packedU = Math.round(clamp01(u) * 65535.0F) & 0xFFFF;
        int packedV = Math.round(clamp01(v) * 65535.0F) & 0xFFFF;
        return packedU | (packedV << 16);
    }

    private static float unpackUv16U(int packedUv) {
        return (packedUv & 0xFFFF) / 65535.0F;
    }

    private static float unpackUv16V(int packedUv) {
        return ((packedUv >>> 16) & 0xFFFF) / 65535.0F;
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int argbToRgba8(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return rgba8(red, green, blue, alpha);
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return (red & 0xFF)
                | ((green & 0xFF) << 8)
                | ((blue & 0xFF) << 16)
                | ((alpha & 0xFF) << 24);
    }

    private record UvAssignment(int[] orderedUvs, float totalDistanceSquared) {
        private UvAssignment {
            orderedUvs = Arrays.copyOf(Objects.requireNonNull(orderedUvs, "orderedUvs"), orderedUvs.length);
            if (orderedUvs.length != QUAD_VERTEX_COUNT) {
                throw new IllegalArgumentException("orderedUvs must contain four UV values");
            }
            if (Float.isNaN(totalDistanceSquared) || totalDistanceSquared < 0.0F) {
                throw new IllegalArgumentException("totalDistanceSquared must be non-negative");
            }
        }

        private static UvAssignment invalid(int[] fallbackUvs) {
            return new UvAssignment(fallbackUvs, Float.POSITIVE_INFINITY);
        }

        @Override
        public int[] orderedUvs() {
            return Arrays.copyOf(orderedUvs, orderedUvs.length);
        }
    }

    private record TextureEntry(
            String name,
            int width,
            int height,
            int[] pixels,
            boolean hasTransparentTexels,
            TextureAnimation animation
    ) {
        private TextureEntry {
            name = Objects.requireNonNull(name, "name");
            pixels = Objects.requireNonNull(pixels, "pixels");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("texture dimensions must be positive");
            }
            if (pixels.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("texture pixel count does not match dimensions");
            }
        }

        private TextureEntry(String name, int width, int height, int[] pixels, TextureAnimation animation) {
            this(
                    name,
                    width,
                    height,
                    Arrays.copyOf(Objects.requireNonNull(pixels, "pixels"), pixels.length),
                    animation == null ? RtTextureCatalog.hasTransparentTexels(pixels) : animation.hasTransparentTexels(),
                    animation
            );
        }

        @Override
        public int[] pixels() {
            return Arrays.copyOf(pixels, pixels.length);
        }

        private TextureEntry withCopiedPixels(int[] replacementPixels) {
            Objects.requireNonNull(replacementPixels, "replacementPixels");
            return withSharedImmutablePixels(Arrays.copyOf(replacementPixels, replacementPixels.length));
        }

        private TextureEntry withSharedImmutablePixels(int[] replacementPixels) {
            Objects.requireNonNull(replacementPixels, "replacementPixels");
            boolean replacementHasTransparentTexels =
                    animation == null ? RtTextureCatalog.hasTransparentTexels(replacementPixels) : hasTransparentTexels;
            return new TextureEntry(name, width, height, replacementPixels, replacementHasTransparentTexels, animation);
        }

        private int pixelCount() {
            return pixels.length;
        }

        private boolean regionHasAlphaCutoutTexels(int uv0, int uv1, int uv2, int uv3) {
            if (!hasTransparentTexels) {
                return false;
            }
            TextureRegion region = TextureRegion.fromPackedUvs(width, height, uv0, uv1, uv2, uv3);
            if (animation != null) {
                return animation.regionHasAlphaCutoutTexels(region);
            }
            return RtTextureCatalog.regionHasAlphaCutoutTexels(pixels, width, region);
        }
    }

    private record TextureRegion(int x0, int y0, int x1, int y1) {
        private TextureRegion {
            if (x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0) {
                throw new IllegalArgumentException("texture region must be non-empty and positive");
            }
        }

        private static TextureRegion fromPackedUvs(int width, int height, int uv0, int uv1, int uv2, int uv3) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("texture dimensions must be positive");
            }
            float minU = Math.min(Math.min(unpackUv16U(uv0), unpackUv16U(uv1)), Math.min(unpackUv16U(uv2), unpackUv16U(uv3)));
            float minV = Math.min(Math.min(unpackUv16V(uv0), unpackUv16V(uv1)), Math.min(unpackUv16V(uv2), unpackUv16V(uv3)));
            float maxU = Math.max(Math.max(unpackUv16U(uv0), unpackUv16U(uv1)), Math.max(unpackUv16U(uv2), unpackUv16U(uv3)));
            float maxV = Math.max(Math.max(unpackUv16V(uv0), unpackUv16V(uv1)), Math.max(unpackUv16V(uv2), unpackUv16V(uv3)));
            int x0 = Math.max(0, Math.min(width - 1, (int) Math.floor(minU * width)));
            int y0 = Math.max(0, Math.min(height - 1, (int) Math.floor(minV * height)));
            int x1 = Math.max(x0 + 1, Math.min(width, (int) Math.ceil(maxU * width)));
            int y1 = Math.max(y0 + 1, Math.min(height, (int) Math.ceil(maxV * height)));
            return new TextureRegion(x0, y0, x1, y1);
        }
    }

    private static boolean regionHasAlphaCutoutTexels(int[] pixels, int width, TextureRegion region) {
        for (int y = region.y0(); y < region.y1(); y++) {
            int row = y * width;
            for (int x = region.x0(); x < region.x1(); x++) {
                if (((pixels[row + x] >>> 24) & 0xFF) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class TextureAnimation {
        private final String textureName;
        private final int frameWidth;
        private final int frameHeight;
        private final int[][] framePixels;
        private final List<FrameTiming> frames;
        private final boolean hasTransparentTexels;
        private int frameCursor;
        private int subFrameTicks;

        private TextureAnimation(
                String textureName,
                int frameWidth,
                int frameHeight,
                int[][] framePixels,
                List<FrameTiming> frames,
                boolean hasTransparentTexels
        ) {
            this.textureName = Objects.requireNonNull(textureName, "textureName");
            if (frameWidth <= 0 || frameHeight <= 0) {
                throw new IllegalArgumentException("animation frame dimensions must be positive");
            }
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.framePixels = Arrays.copyOf(Objects.requireNonNull(framePixels, "framePixels"), framePixels.length);
            this.frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            if (this.frames.size() <= 1) {
                throw new IllegalArgumentException("animated texture requires at least two frames");
            }
            for (FrameTiming frame : this.frames) {
                if (frame.index() >= this.framePixels.length || this.framePixels[frame.index()] == null) {
                    throw new IllegalArgumentException("animated texture frame is missing pixels: " + textureName);
                }
            }
            this.hasTransparentTexels = hasTransparentTexels;
        }

        private boolean hasTransparentTexels() {
            return hasTransparentTexels;
        }

        private int frameCount() {
            return frames.size();
        }

        private int[] currentFramePixels() {
            return framePixels[frames.get(frameCursor).index()];
        }

        private int[] advanceFramePixels() {
            FrameTiming current = frames.get(frameCursor);
            subFrameTicks++;
            if (subFrameTicks < current.durationTicks()) {
                return null;
            }
            subFrameTicks = 0;
            frameCursor = (frameCursor + 1) % frames.size();
            return currentFramePixels();
        }

        private boolean regionHasAlphaCutoutTexels(TextureRegion region) {
            if (!hasTransparentTexels) {
                return false;
            }
            for (FrameTiming frame : frames) {
                if (RtTextureCatalog.regionHasAlphaCutoutTexels(framePixels[frame.index()], frameWidth, region)) {
                    return true;
                }
            }
            return false;
        }

        private record FrameTiming(int index, int durationTicks) {
            private FrameTiming {
                if (index < 0) {
                    throw new IllegalArgumentException("animation frame index must not be negative");
                }
                if (durationTicks <= 0) {
                    throw new IllegalArgumentException("animation frame duration must be positive");
                }
            }
        }
    }

    /**
     * Describes how a source renderer stores atlas UVs before this renderer
     * normalizes them into a texture-local coordinate.  Identity is the common
     * renderer-core path; atlasRoundTrip preserves host vertex quantization
     * without exposing host types or atlas ownership to the core.
     */
    public record TextureCoordinateMapping(
            float sourceMinU,
            float sourceMaxU,
            float sourceMinV,
            float sourceMaxV,
            int sourceQuantizationLevels
    ) {
        private static final TextureCoordinateMapping IDENTITY =
                new TextureCoordinateMapping(0.0F, 1.0F, 0.0F, 1.0F, 0);

        public TextureCoordinateMapping {
            if (!Float.isFinite(sourceMinU) || !Float.isFinite(sourceMaxU)
                    || !Float.isFinite(sourceMinV) || !Float.isFinite(sourceMaxV)) {
                throw new IllegalArgumentException("texture coordinate mapping bounds must be finite");
            }
            if (!(sourceMaxU > sourceMinU) || !(sourceMaxV > sourceMinV)) {
                throw new IllegalArgumentException("texture coordinate mapping extents must be positive");
            }
            if (sourceQuantizationLevels < 0) {
                throw new IllegalArgumentException("texture coordinate quantization levels must not be negative");
            }
        }

        public static TextureCoordinateMapping identity() {
            return IDENTITY;
        }

        public static TextureCoordinateMapping atlasRoundTrip(
                float sourceMinU,
                float sourceMaxU,
                float sourceMinV,
                float sourceMaxV
        ) {
            return new TextureCoordinateMapping(
                    sourceMinU, sourceMaxU, sourceMinV, sourceMaxV, 65535);
        }

        public int packLocalUv(float localU, float localV) {
            if (sourceQuantizationLevels == 0) {
                return RtTextureCatalog.packUv16(localU, localV);
            }
            int packedU = packRoundTripped(localU, sourceMinU, sourceMaxU);
            int packedV = packRoundTripped(localV, sourceMinV, sourceMaxV);
            return packedU | (packedV << 16);
        }

        private int packRoundTripped(float local, float sourceMin, float sourceMax) {
            float clampedLocal = clamp01(local);
            float source = sourceMin + (sourceMax - sourceMin) * clampedLocal;
            int quantizedSource = Math.round(clamp01(source) * sourceQuantizationLevels);
            double storedSource = quantizedSource / (double) sourceQuantizationLevels;
            double normalized = (storedSource - sourceMin) / (sourceMax - sourceMin);
            return (int) Math.round(Math.max(0.0D, Math.min(1.0D, normalized)) * 65535.0D) & 0xFFFF;
        }
    }

    public record FaceTexture(
            int textureId,
            int uv0,
            int uv1,
            int uv2,
            int uv3,
            boolean tinted,
            int tintIndex,
            boolean alphaCutout,
            boolean shade,
            int renderLayer,
            TextureCoordinateMapping coordinateMapping
    ) {
        public FaceTexture(
                int textureId,
                int uv0,
                int uv1,
                int uv2,
                int uv3,
                boolean tinted,
                int tintIndex,
                boolean alphaCutout,
                boolean shade,
                int renderLayer
        ) {
            this(textureId, uv0, uv1, uv2, uv3, tinted, tintIndex, alphaCutout, shade,
                    renderLayer, TextureCoordinateMapping.identity());
        }

        public FaceTexture(
                int textureId,
                int uv0,
                int uv1,
                int uv2,
                int uv3,
                boolean tinted,
                int tintIndex,
                boolean alphaCutout,
                boolean shade
        ) {
            this(
                    textureId, uv0, uv1, uv2, uv3, tinted, tintIndex, alphaCutout, shade,
                    alphaCutout ? RENDER_LAYER_CUTOUT : RENDER_LAYER_SOLID,
                    TextureCoordinateMapping.identity()
            );
        }

        public FaceTexture(int textureId, int uv0, int uv1, int uv2, int uv3, boolean tinted, boolean alphaCutout, boolean shade) {
            this(textureId, uv0, uv1, uv2, uv3, tinted, tinted ? 0 : -1, alphaCutout, shade);
        }

        public FaceTexture {
            coordinateMapping = Objects.requireNonNull(coordinateMapping, "coordinateMapping");
            if (!tinted) {
                tintIndex = -1;
            } else if (tintIndex < 0) {
                tintIndex = 0;
            }
            if (renderLayer < RENDER_LAYER_SOLID || renderLayer > RENDER_LAYER_TRANSLUCENT) {
                throw new IllegalArgumentException("renderLayer must be SOLID, CUTOUT, or TRANSLUCENT");
            }
            if (alphaCutout && renderLayer != RENDER_LAYER_CUTOUT) {
                throw new IllegalArgumentException("alpha-cutout texture must use the CUTOUT render layer");
            }
        }

        public static FaceTexture missing() {
            return new FaceTexture(
                    MISSING_TEXTURE_ID,
                    packUv16(0.0F, 0.0F),
                    packUv16(1.0F, 0.0F),
                    packUv16(1.0F, 1.0F),
                    packUv16(0.0F, 1.0F),
                    false,
                    -1,
                    false,
                    true,
                    RENDER_LAYER_SOLID
            );
        }
    }

    public record ModelQuads(boolean simpleCube, List<ModelQuad> quads) {
        public ModelQuads {
            quads = List.copyOf(Objects.requireNonNull(quads, "quads"));
        }

        public static ModelQuads generatedFaceFallback() {
            return new ModelQuads(true, List.of());
        }

        public boolean usesBakedGeometry() {
            return !simpleCube && !quads.isEmpty();
        }
    }

    public record ModelQuad(float[] positions, FaceDirection direction, FaceTexture texture, boolean directionalCull) {
        public ModelQuad {
            positions = Arrays.copyOf(Objects.requireNonNull(positions, "positions"), positions.length);
            if (positions.length != QUAD_POSITION_FLOATS) {
                throw new IllegalArgumentException("model quad positions must contain four xyz vertices");
            }
            direction = Objects.requireNonNull(direction, "direction");
            texture = Objects.requireNonNull(texture, "texture");
        }

        @Override
        public float[] positions() {
            return Arrays.copyOf(positions, positions.length);
        }
    }

    public static final class Snapshot {
        private final int[] textureRecords;
        private final int[][] texturePixelSegments;
        private final int texturePixelCount;
        private final long revision;
        private final int hashCode;
        private final Snapshot incrementalBase;
        private final int[] dirtyTextureIds;
        private volatile int[] flattenedTexturePixels;

        public Snapshot(int[] textureRecords, int[] texturePixels, long revision) {
            this(textureRecords, new int[][]{texturePixels}, texturePixels.length, revision, null, new int[]{0}, false);
        }

        private Snapshot(
                int[] textureRecords,
                int[][] texturePixelSegments,
                int texturePixelCount,
                long revision,
                Snapshot incrementalBase,
                int[] dirtyTextureIds,
                boolean ownsArrays
        ) {
            Objects.requireNonNull(textureRecords, "textureRecords");
            Objects.requireNonNull(texturePixelSegments, "texturePixelSegments");
            int[] checkedRecords = ownsArrays ? textureRecords : Arrays.copyOf(textureRecords, textureRecords.length);
            if (checkedRecords.length == 0 || checkedRecords.length % INTS_PER_TEXTURE_RECORD != 0) {
                throw new IllegalArgumentException("textureRecords must contain std430 uvec4 records");
            }
            if (texturePixelSegments.length != checkedRecords.length / INTS_PER_TEXTURE_RECORD || texturePixelCount <= 0) {
                throw new IllegalArgumentException("texturePixels must not be empty");
            }
            if (revision < 0L) {
                throw new IllegalArgumentException("texture revision must not be negative");
            }
            this.textureRecords = checkedRecords;
            this.texturePixelSegments = ownsArrays ? texturePixelSegments : copyPixelSegments(texturePixelSegments);
            this.texturePixelCount = texturePixelCount;
            this.revision = revision;
            this.incrementalBase = incrementalBase;
            this.dirtyTextureIds = Arrays.copyOf(Objects.requireNonNull(dirtyTextureIds, "dirtyTextureIds"), dirtyTextureIds.length);
            this.hashCode = 31 * Arrays.hashCode(checkedRecords) + Long.hashCode(revision);
        }

        private static Snapshot fromOwnedSegments(
                int[] textureRecords,
                int[][] texturePixelSegments,
                int texturePixelCount,
                long revision,
                Snapshot previousSnapshot,
                Set<Integer> dirtyTextureIds
        ) {
            int[] dirtyIds = dirtyTextureIds.stream().mapToInt(Integer::intValue).toArray();
            boolean sameLayout = previousSnapshot != null
                    && previousSnapshot.texturePixelSegments.length == texturePixelSegments.length
                    && previousSnapshot.texturePixelCount == texturePixelCount;
            return new Snapshot(
                    textureRecords,
                    texturePixelSegments,
                    texturePixelCount,
                    revision,
                    sameLayout ? previousSnapshot : null,
                    dirtyIds,
                    true
            );
        }

        public long revision() {
            return revision;
        }

        public int textureCount() {
            return textureRecords.length / INTS_PER_TEXTURE_RECORD;
        }

        public int texturePixelCount() {
            return texturePixelCount;
        }

        public int estimatedBytes() {
            return Math.multiplyExact(textureRecords.length + texturePixelCount, Integer.BYTES);
        }

        public int[] textureRecords() {
            return Arrays.copyOf(textureRecords, textureRecords.length);
        }

        public int[] texturePixels() {
            return Arrays.copyOf(flattenedTexturePixels(), texturePixelCount);
        }

        /**
         * Returns a read-only zero-copy view of this immutable generation.
         * Position and limit belong to the returned view, so consumers may
         * slice it without mutating either the catalog or another consumer.
         */
        public IntBuffer textureRecordBuffer() {
            return IntBuffer.wrap(textureRecords).asReadOnlyBuffer();
        }

        /** See {@link #textureRecordBuffer()} for the ownership contract. */
        public IntBuffer texturePixelBuffer() {
            return IntBuffer.wrap(flattenedTexturePixels()).asReadOnlyBuffer();
        }

        /**
         * Returns immutable texture segments for a GPU upload.  Incremental
         * successors expose only replacement texture arrays; a non-adjacent
         * consumer must request a full generation to preserve correctness.
         */
        public List<PixelSegment> pixelSegmentsForUpload(Snapshot previousSnapshot, boolean forceFullUpload) {
            boolean incremental = !forceFullUpload && incrementalBase == previousSnapshot;
            int[] ids = incremental ? dirtyTextureIds : allTextureIds();
            List<PixelSegment> segments = new ArrayList<>(ids.length);
            for (int textureId : ids) {
                if (textureId < 0 || textureId >= texturePixelSegments.length) {
                    throw new IllegalStateException("dirty texture id is outside this snapshot: " + textureId);
                }
                segments.add(new PixelSegment(
                        IntBuffer.wrap(texturePixelSegments[textureId]).asReadOnlyBuffer(),
                        textureRecords[textureId * INTS_PER_TEXTURE_RECORD]
                ));
            }
            return List.copyOf(segments);
        }

        private int[] flattenedTexturePixels() {
            int[] flattened = flattenedTexturePixels;
            if (flattened != null) {
                return flattened;
            }
            int[] built = new int[texturePixelCount];
            for (int textureId = 0; textureId < texturePixelSegments.length; textureId++) {
                System.arraycopy(texturePixelSegments[textureId], 0, built,
                        textureRecords[textureId * INTS_PER_TEXTURE_RECORD], texturePixelSegments[textureId].length);
            }
            flattenedTexturePixels = built;
            return built;
        }

        private int[] allTextureIds() {
            int[] ids = new int[texturePixelSegments.length];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = index;
            }
            return ids;
        }

        private static int[][] copyPixelSegments(int[][] segments) {
            int[][] copy = new int[segments.length][];
            int pixelCount = 0;
            for (int index = 0; index < segments.length; index++) {
                copy[index] = Arrays.copyOf(Objects.requireNonNull(segments[index], "texture pixel segment"), segments[index].length);
                pixelCount = Math.addExact(pixelCount, copy[index].length);
            }
            return copy;
        }

        public record PixelSegment(IntBuffer pixels, int targetOffsetInts) {
            public PixelSegment {
                pixels = Objects.requireNonNull(pixels, "pixels").asReadOnlyBuffer();
                if (targetOffsetInts < 0) {
                    throw new IllegalArgumentException("targetOffsetInts must not be negative");
                }
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Snapshot that
                    && revision == that.revision
                    && Arrays.equals(textureRecords, that.textureRecords)
                    && texturePixelCount == that.texturePixelCount;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    public record AnimationUpdate(
            long animationTicks,
            long revision,
            int animatedTextures,
            int changedTextures,
            int changedPixels
    ) {
        public AnimationUpdate {
            if (animationTicks < 0L || revision < 0L
                    || animatedTextures < 0
                    || changedTextures < 0
                    || changedPixels < 0) {
                throw new IllegalArgumentException("animation update counters must not be negative");
            }
            if (changedTextures > animatedTextures) {
                throw new IllegalArgumentException("changed texture count cannot exceed animated texture count");
            }
        }
    }

    /** Stable material-store identifier returned after a bridge texture copy. */
    public record RegisteredTexture(int textureId, boolean hasTransparentTexels) {
        public RegisteredTexture {
            if (textureId < 0) {
                throw new IllegalArgumentException("registered texture id must not be negative");
            }
        }
    }

    public record TestTexture(String name, int width, int height, int[] rgba8Pixels) {
        public TestTexture {
            name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("test texture name must not be blank");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("test texture dimensions must be positive");
            }
            rgba8Pixels = Arrays.copyOf(Objects.requireNonNull(rgba8Pixels, "rgba8Pixels"), rgba8Pixels.length);
            if (rgba8Pixels.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("test texture pixel count does not match dimensions");
            }
        }

        @Override
        public int[] rgba8Pixels() {
            return Arrays.copyOf(rgba8Pixels, rgba8Pixels.length);
        }
    }

    public record TestAnimatedTexture(
            String name,
            int width,
            int height,
            List<int[]> rgba8Frames,
            int frameDurationTicks
    ) {
        public TestAnimatedTexture {
            name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("test texture name must not be blank");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("test texture dimensions must be positive");
            }
            if (frameDurationTicks <= 0) {
                throw new IllegalArgumentException("test animation frame duration must be positive");
            }
            List<int[]> copiedFrames = new ArrayList<>(Objects.requireNonNull(rgba8Frames, "rgba8Frames").size());
            int expectedPixels = Math.multiplyExact(width, height);
            for (int[] frame : rgba8Frames) {
                int[] copied = Arrays.copyOf(Objects.requireNonNull(frame, "animation frame"), frame.length);
                if (copied.length != expectedPixels) {
                    throw new IllegalArgumentException("test animation frame pixel count does not match dimensions");
                }
                copiedFrames.add(copied);
            }
            if (copiedFrames.size() <= 1) {
                throw new IllegalArgumentException("test animated texture requires at least two frames");
            }
            rgba8Frames = List.copyOf(copiedFrames);
        }

        @Override
        public List<int[]> rgba8Frames() {
            List<int[]> copy = new ArrayList<>(rgba8Frames.size());
            for (int[] frame : rgba8Frames) {
                copy.add(Arrays.copyOf(frame, frame.length));
            }
            return List.copyOf(copy);
        }
    }

    public static final class KeyedSingleFlight<K> {
        private final ConcurrentHashMap<K, Lease> keyLeases = new ConcurrentHashMap<>();

        public <T> T run(K key, Supplier<T> action) {
            K checkedKey = Objects.requireNonNull(key, "single-flight key");
            Objects.requireNonNull(action, "single-flight action");
            Lease lease = keyLeases.compute(checkedKey, (ignored, current) -> {
                Lease selected = current == null ? new Lease() : current;
                selected.participants++;
                return selected;
            });
            try {
                synchronized (lease.monitor) {
                    return action.get();
                }
            } finally {
                keyLeases.compute(checkedKey, (ignored, current) -> {
                    if (current != lease) {
                        throw new IllegalStateException("single-flight lease ownership changed before release");
                    }
                    lease.participants--;
                    if (lease.participants < 0) {
                        throw new IllegalStateException("single-flight lease released more than acquired");
                    }
                    /*
                     * A waiter has already captured this monitor before it
                     * blocks. Do not remove the mapping until every such
                     * participant has completed, otherwise a later caller can
                     * install a second monitor and run the same load in parallel.
                     */
                    return lease.participants == 0 ? null : lease;
                });
            }
        }

        public int inFlightKeys() {
            return keyLeases.size();
        }

        private static final class Lease {
            private final Object monitor = new Object();
            /* Accessed only inside ConcurrentHashMap.compute for this key. */
            private int participants;
        }
    }

    public static final class TestTextureScope implements AutoCloseable {
        private final Map<String, Integer> previousTextureIds;
        private final List<TextureEntry> previousTextures;
        private final long previousRevision;
        private final long previousAnimationTicks;
        private boolean closed;

        private TestTextureScope(
                Map<String, Integer> previousTextureIds,
                List<TextureEntry> previousTextures,
                long previousRevision,
                long previousAnimationTicks
        ) {
            this.previousTextureIds = Map.copyOf(previousTextureIds);
            this.previousTextures = List.copyOf(previousTextures);
            this.previousRevision = previousRevision;
            this.previousAnimationTicks = previousAnimationTicks;
        }

        public int textureId(String name) {
            Objects.requireNonNull(name, "name");
            synchronized (LOCK) {
                Integer textureId = TEXTURE_IDS.get(name);
                if (textureId == null) {
                    throw new IllegalArgumentException("unknown scoped test texture: " + name);
                }
                return textureId;
            }
        }

        public void replaceTexturePixels(int textureId, int[] rgba8Pixels) {
            Objects.requireNonNull(rgba8Pixels, "rgba8Pixels");
            synchronized (LOCK) {
                if (closed) {
                    throw new IllegalStateException("test texture scope is already closed");
                }
                if (textureId <= MISSING_TEXTURE_ID || textureId >= TEXTURES.size()) {
                    throw new IllegalArgumentException("unknown scoped test texture id: " + textureId);
                }
                TextureEntry current = TEXTURES.get(textureId);
                TEXTURES.set(textureId, current.withCopiedPixels(rgba8Pixels));
                revision++;
                snapshotGeneration++;
                dirtyTextureIds.add(textureId);
            }
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) {
                    return;
                }
                closed = true;
                TEXTURE_IDS.clear();
                TEXTURE_IDS.putAll(previousTextureIds);
                TEXTURES.clear();
                TEXTURES.addAll(previousTextures);
                revision = previousRevision;
                animationTicks = previousAnimationTicks;
                snapshotGeneration++;
                dirtyTextureIds.clear();
                for (int textureId = 0; textureId < TEXTURES.size(); textureId++) {
                    dirtyTextureIds.add(textureId);
                }
            }
        }
    }

    public static final class TelemetryScope implements AutoCloseable {
        private final Object owner;
        private boolean closed;

        private TelemetryScope(Object owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) {
                    return;
                }
                if (materialTelemetryOwner != owner) {
                    throw new IllegalStateException("RT texture catalog telemetry owner changed before close");
                }
                closed = true;
                materialTelemetry = RtMaterialTelemetrySink.NOOP;
                materialTelemetryOwner = null;
            }
        }
    }
}
