package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Encodes analytic dynamic primitives into their position and material ABI lanes. */
final class RtDynamicSceneAnalyticPrimitiveWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneAnalyticPrimitiveWriter() {
    }

    static int count(DynamicRenderScene scene, int maximum) {
        Objects.requireNonNull(scene, "scene");
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        int count = 0;
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            if (primitive.usesAnalyticFastPath() && ++count >= maximum) {
                return maximum;
            }
        }
        return count;
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            int primitiveCount,
            int positionRecord,
            int colorRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (primitiveCount < 0 || positionRecord < 0 || colorRecord < 0) {
            throw new IllegalArgumentException("primitive writer arguments must not be negative");
        }
        target.position(positionRecord * RECORD_BYTES);
        for (int index = 0; index < primitiveCount; index++) {
            DynamicRenderScene.DynamicPrimitive primitive = at(scene, index);
            putVec4(target, (float) primitive.x(), (float) primitive.y(), (float) primitive.z(), primitive.radiusX());
        }
        target.position(colorRecord * RECORD_BYTES);
        for (int index = 0; index < primitiveCount; index++) {
            DynamicRenderScene.DynamicPrimitive primitive = at(scene, index);
            putUvec4(
                    target,
                    rgba8(primitive),
                    packKinds(primitive.geometryKind(), primitive.kind()),
                    Float.floatToRawIntBits(primitive.radiusY()),
                    Float.floatToRawIntBits(primitive.radiusZ())
            );
        }
    }

    static int packKinds(
            DynamicRenderScene.PrimitiveGeometryKind geometryKind,
            DynamicRenderScene.PrimitiveKind primitiveKind
    ) {
        Objects.requireNonNull(geometryKind, "geometryKind");
        Objects.requireNonNull(primitiveKind, "primitiveKind");
        return (geometryKind.ordinal() & 0xffff) | ((primitiveKind.ordinal() & 0xffff) << 16);
    }

    static int rgba8(DynamicRenderScene.DynamicPrimitive primitive) {
        Objects.requireNonNull(primitive, "primitive");
        int materialKey = primitive.materialKey();
        if ((materialKey & 0xff000000) != 0) {
            return materialKey;
        }
        int hash = primitive.kind().ordinal() * 0x45d9f3b
                ^ primitive.geometryKind().ordinal() * 0x27d4eb2d
                ^ primitive.textureKey() * 0x165667b1
                ^ materialKey * 0x9e3779b9;
        hash ^= hash >>> 16;
        int red = 96 + (hash & 0x7f);
        int green = 96 + ((hash >>> 8) & 0x7f);
        int blue = 96 + ((hash >>> 16) & 0x7f);
        return red | (green << 8) | (blue << 16) | 0xff000000;
    }

    private static DynamicRenderScene.DynamicPrimitive at(DynamicRenderScene scene, int targetIndex) {
        int index = 0;
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            if (!primitive.usesAnalyticFastPath()) {
                continue;
            }
            if (index++ == targetIndex) {
                return primitive;
            }
        }
        throw new IllegalArgumentException("analytic primitive index outside packed dynamic scene: " + targetIndex);
    }

    private static void putUvec4(ByteBuffer target, int x, int y, int z, int w) {
        target.putInt(x);
        target.putInt(y);
        target.putInt(z);
        target.putInt(w);
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x);
        target.putFloat(y);
        target.putFloat(z);
        target.putFloat(w);
    }
}
