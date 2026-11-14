package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.ResourceSlice;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureDataLayout;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureExtent;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureOrigin;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.api.WriteTextureCommand;

import java.nio.ByteBuffer;
import java.util.Set;

/** Executable regression coverage for portable byte-pitch compaction before Vulkan submission. */
public final class VulkanGenericTextureUploadPackerSelfTest {
    private VulkanGenericTextureUploadPackerSelfTest() { }

    public static void main(String[] args) {
        preservesRowsAcrossNonTexelAlignedPitch();
        preservesOffsetAndImagePitchAcrossDepthSlices();
    }

    private static void preservesRowsAcrossNonTexelAlignedPitch() {
        WriteTextureCommand command = write(twoDimensionalTexture(), new TextureExtent(1, 2, 1),
                new TextureDataLayout(0, 5, 2), bytes(1, 2, 3, 4, 99, 5, 6, 7, 8));
        ByteBuffer packed = VulkanGenericTextureUploadPacker.compact(TextureFormat.RGBA8_UNORM, command);
        requireBytes(packed, 1, 2, 3, 4, 5, 6, 7, 8);
    }

    private static void preservesOffsetAndImagePitchAcrossDepthSlices() {
        byte[] source = new byte[30];
        for (int index = 0; index < source.length; index++) source[index] = (byte) index;
        WriteTextureCommand command = write(threeDimensionalTexture(), new TextureExtent(1, 2, 2),
                new TextureDataLayout(2, 6, 3), source);
        ByteBuffer packed = VulkanGenericTextureUploadPacker.compact(TextureFormat.RGBA8_UNORM, command);
        requireBytes(packed, 2, 3, 4, 5, 8, 9, 10, 11, 20, 21, 22, 23, 26, 27, 28, 29);
    }

    private static WriteTextureCommand write(
            TextureResource texture, TextureExtent extent, TextureDataLayout layout, byte... payload
    ) {
        return new WriteTextureCommand(
                new ResourceSlice.TextureSlice(texture, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), extent, layout, new ResourceData(ByteBuffer.wrap(payload))
        );
    }

    private static TextureResource twoDimensionalTexture() {
        return new TextureResource(new RenderResourceId(900L), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                1, 2, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COPY_DESTINATION));
    }

    private static TextureResource threeDimensionalTexture() {
        return new TextureResource(new RenderResourceId(901L), ResourceVersion.initial(), TextureDimension.TEXTURE_3D,
                1, 2, 2, 1, 1, 1, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COPY_DESTINATION));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            if (values[index] < 0 || values[index] > 255) {
                throw new IllegalArgumentException("test byte value is outside the unsigned byte range");
            }
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static void requireBytes(ByteBuffer value, int... expected) {
        require(value.remaining() == expected.length, "packed texture size differs from the tight region size");
        for (int index = 0; index < expected.length; index++) {
            require(Byte.toUnsignedInt(value.get(index)) == expected[index], "packed texture byte mismatch at " + index);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
