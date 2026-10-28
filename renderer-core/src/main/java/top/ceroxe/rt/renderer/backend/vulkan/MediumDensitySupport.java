package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumDensityField;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** CPU metadata checks and one-time world-to-density composition; never copies atlas pixels. */
final class MediumDensitySupport {
    private MediumDensitySupport() {}

    static String invalidTexture(MediumDensityField field, TextureAsset texture) {
        if (field.isUniform()) return null;
        if (texture == null) return "density field references missing texture " + field.textureAssetId();
        if (texture.colorSpace() != TextureAsset.ColorSpace.LINEAR)
            return "density texture " + texture.id() + " must use LINEAR color space";
        if (texture.height() < field.slices() || texture.height() % field.slices() != 0)
            return "density texture " + texture.id() + " height must be a positive multiple of slices " + field.slices();
        return null;
    }

    static boolean heterogeneous(Medium medium) {
        return medium.participating() && !medium.densityField().isUniform();
    }

    /** Row-major 3x4 map from absolute world coordinates to the API's density UVW domain. */
    static float[] worldToDensity(MediumDensityField field, AffineTransform objectToWorld) {
        var local = field.transform();
        var inverse = MediumMeshAcceleration.inverse(objectToWorld);
        float[] result = new float[12];
        for (int row = 0; row < 3; row++) for (int column = 0; column < 4; column++) {
            double value = column == 3 ? local.element(row * 4 + 3) : 0;
            for (int axis = 0; axis < 3; axis++) value += local.element(row * 4 + axis) * inverse[axis * 4 + column];
            result[row * 4 + column] = (float) value;
            if (!Float.isFinite(result[row * 4 + column]))
                throw new IllegalArgumentException("world-to-density transform exceeds finite GPU float range");
        }
        return result;
    }

    static void pack(int[] words, int textureWord, Medium medium, AffineTransform objectToWorld,
            VulkanGpuSceneAbi.SlotResolver textures) {
        boolean heterogeneous = heterogeneous(medium);
        var field = heterogeneous ? medium.densityField() : MediumDensityField.uniform();
        words[textureWord] = textureSlot(field, textures);
        words[textureWord + 1] = field.slices();
        if (!heterogeneous) {
            for (int index = 0; index < 12; index++)
                words[textureWord + 2 + index] = Float.floatToRawIntBits(index % 5 == 0 ? 1 : 0);
            return;
        }
        float[] matrix = worldToDensity(field, objectToWorld);
        for (int index = 0; index < matrix.length; index++)
            words[textureWord + 2 + index] = Float.floatToRawIntBits(matrix[index]);
    }

    /** Environment fields already map absolute world coordinates; no inverse or temporary matrix is needed. */
    static void packEnvironment(ByteBuffer words, int textureWord, Medium medium,
            VulkanGpuSceneAbi.SlotResolver textures) {
        boolean heterogeneous = heterogeneous(medium);
        var field = heterogeneous ? medium.densityField() : MediumDensityField.uniform();
        words.putInt(textureWord * Integer.BYTES, textureSlot(field, textures));
        words.putInt((textureWord + 1) * Integer.BYTES, field.slices());
        for (int index = 0; index < 12; index++) {
            float value = heterogeneous ? field.transform().element(index) : index % 5 == 0 ? 1 : 0;
            words.putInt((textureWord + 2 + index) * Integer.BYTES, Float.floatToRawIntBits(value));
        }
    }

    private static int textureSlot(MediumDensityField field, VulkanGpuSceneAbi.SlotResolver textures) {
        if (field.isUniform()) return -1;
        int slot = textures.resolve(field.textureAssetId());
        if (slot < 0)
            throw new IllegalArgumentException("density texture " + field.textureAssetId() + " has no resident GPU slot");
        return slot;
    }
}
