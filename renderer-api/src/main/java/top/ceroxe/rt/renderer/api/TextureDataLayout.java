package top.ceroxe.rt.renderer.api;

/** Explicit row/image pitch for a tightly or custom-packed texture upload payload. */
public record TextureDataLayout(long offsetBytes, long bytesPerRow, long rowsPerImage) {
    public TextureDataLayout {
        if (offsetBytes < 0 || bytesPerRow <= 0 || rowsPerImage <= 0) {
            throw new IllegalArgumentException("texture data offset must be non-negative and pitches positive");
        }
    }
}
