package top.ceroxe.rt.renderer.api;

/** Package-private shared validation for texture region uploads and copies. */
final class TextureRegionValidation {
    private TextureRegionValidation() { }

    static void requireContained(TextureResource texture, TextureSubresourceRange range,
                                 TextureOrigin origin, TextureExtent extent) {
        if (range.mipLevelCount() != 1) throw new IllegalArgumentException("texture region requires exactly one mip level");
        int mip = range.baseMipLevel();
        int width = mipExtent(texture.width(), mip);
        int height = mipExtent(texture.height(), mip);
        int depth = mipExtent(texture.depth(), mip);
        long layerDepth = texture.dimension() == TextureDimension.TEXTURE_3D
                ? depth : range.arrayLayerCount();
        if ((long) origin.x() + extent.width() > width
                || (long) origin.y() + extent.height() > height
                || (long) origin.z() + extent.depth() > layerDepth) {
            throw new IllegalArgumentException("texture region exceeds its mip and layer/depth extent");
        }
        if (texture.dimension() == TextureDimension.TEXTURE_1D && (origin.y() != 0 || extent.height() != 1)) {
            throw new IllegalArgumentException("one-dimensional texture regions require y=0 and height=1");
        }
        if (texture.dimension() != TextureDimension.TEXTURE_3D && range.arrayLayerCount() == 1
                && (origin.z() != 0 || extent.depth() != 1)) {
            throw new IllegalArgumentException("single-layer texture regions require z=0 and depth=1");
        }
    }

    static int bytesPerTexel(TextureFormat format) {
        return format.bytesPerTexel();
    }

    static long requiredBytes(TextureFormat format, TextureExtent extent, TextureDataLayout layout) {
        long rowBytes;
        try {
            rowBytes = Math.multiplyExact((long) extent.width(), bytesPerTexel(format));
            if (layout.bytesPerRow() < rowBytes || layout.rowsPerImage() < extent.height()) {
                throw new IllegalArgumentException("texture row/image pitch is smaller than the region");
            }
            long imageCount = extent.depth();
            long lastImage = Math.multiplyExact(imageCount - 1L, layout.rowsPerImage());
            long lastRow = Math.multiplyExact((long) extent.height() - 1L, layout.bytesPerRow());
            long imageOffset = Math.multiplyExact(lastImage, layout.bytesPerRow());
            return Math.addExact(layout.offsetBytes(), Math.addExact(imageOffset, Math.addExact(lastRow, rowBytes)));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("texture payload range overflows long", overflow);
        }
    }

    private static int mipExtent(int extent, int mip) {
        return Math.max(1, extent >> Math.min(mip, Integer.SIZE - 1));
    }
}
