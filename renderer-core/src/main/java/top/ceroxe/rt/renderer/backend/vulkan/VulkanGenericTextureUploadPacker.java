package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.WriteTextureCommand;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Converts one portable byte-pitched texture region into Vulkan's tightly-packed copy payload.
 *
 * <p>Vulkan's {@code bufferRowLength} expresses a texel count. The public contract deliberately
 * uses bytes so callers can describe data from arbitrary decoders and foreign buffers. This class
 * is the single boundary that removes that representation difference without rounding or silently
 * constraining a valid API payload.</p>
 */
final class VulkanGenericTextureUploadPacker {
    private VulkanGenericTextureUploadPacker() { }

    static ByteBuffer compact(TextureFormat format, WriteTextureCommand command) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(command, "command");
        long rowBytes;
        long imageBytes;
        long compactBytes;
        try {
            rowBytes = Math.multiplyExact((long) command.extent().width(), bytesPerTexel(format));
            imageBytes = Math.multiplyExact(rowBytes, command.extent().height());
            compactBytes = Math.multiplyExact(imageBytes, command.extent().depth());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("texture upload size overflows long", overflow);
        }
        final int compactLength;
        final int rowLength;
        try {
            compactLength = Math.toIntExact(compactBytes);
            rowLength = Math.toIntExact(rowBytes);
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("texture upload exceeds Java staging-buffer capacity", tooLarge);
        }

        ByteBuffer source = command.data().bytes();
        byte[] compact = new byte[compactLength];
        long destinationOffset = 0L;
        try {
            for (int image = 0; image < command.extent().depth(); image++) {
                long imageOffset = Math.addExact(command.layout().offsetBytes(), Math.multiplyExact(
                        Math.multiplyExact((long) image, command.layout().rowsPerImage()),
                        command.layout().bytesPerRow()
                ));
                for (int row = 0; row < command.extent().height(); row++) {
                    long sourceOffset = Math.addExact(imageOffset,
                            Math.multiplyExact((long) row, command.layout().bytesPerRow()));
                    int sourceIndex = Math.toIntExact(sourceOffset);
                    int destinationIndex = Math.toIntExact(destinationOffset);
                    if (sourceIndex < 0 || rowLength > source.limit() - sourceIndex) {
                        throw new IllegalArgumentException("texture upload layout exceeds its immutable payload");
                    }
                    ByteBuffer sourceRow = source.duplicate();
                    sourceRow.position(sourceIndex).limit(sourceIndex + rowLength);
                    sourceRow.get(compact, destinationIndex, rowLength);
                    destinationOffset = Math.addExact(destinationOffset, rowBytes);
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("texture upload layout overflows Java indexing", overflow);
        }
        return ByteBuffer.wrap(compact).asReadOnlyBuffer();
    }

    private static int bytesPerTexel(TextureFormat format) {
        return switch (format) {
            case R8_UNORM -> 1;
            case RG8_UNORM, R16_FLOAT -> 2;
            case RG16_FLOAT -> 4;
            case RGBA8_UNORM, RGBA8_SRGB, R32_FLOAT, D32_FLOAT, D24_UNORM_S8_UINT -> 4;
            case RGBA16_FLOAT, RG32_FLOAT -> 8;
            case RGBA32_FLOAT -> 16;
        };
    }
}
