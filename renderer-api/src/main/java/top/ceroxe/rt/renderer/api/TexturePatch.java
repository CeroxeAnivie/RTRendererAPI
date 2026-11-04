package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable copied RGBA8 rectangle in one mip of a stable texture identity. */
public final class TexturePatch {
    private final long textureId;
    private final int mipLevel,x,y,width,height,rowPitch;
    private final byte[] pixels;
    /**
     * Copies the supplied position..limit range. Padding after the final row is optional.
     * @param textureId stable texture ID
     * @param mipLevel zero-based mip
     * @param x first texel column
     * @param y first texel row
     * @param width positive rectangle width
     * @param height positive rectangle height
     * @param rowPitch row stride in bytes, at least width times four
     * @param rgba8 copied source pixels; the caller may reuse them after this constructor
     */
    public TexturePatch(long textureId,int mipLevel,int x,int y,int width,int height,int rowPitch,ByteBuffer rgba8){
        MaterialAsset.requireId(textureId,"textureId");Objects.requireNonNull(rgba8,"rgba8");
        if(mipLevel<0||x<0||y<0||width<=0||height<=0||rowPitch<Math.multiplyExact(width,4))throw new IllegalArgumentException("Invalid texture patch rectangle or row pitch");
        long required=Math.addExact(Math.multiplyExact((long)height-1,rowPitch),Math.multiplyExact((long)width,4));
        if(required>rgba8.remaining())throw new IllegalArgumentException("Texture patch input is too short");
        this.textureId=textureId;this.mipLevel=mipLevel;this.x=x;this.y=y;this.width=width;this.height=height;this.rowPitch=rowPitch;
        pixels=new byte[Math.toIntExact(required)];rgba8.duplicate().get(pixels);
    }
    /** @return stable texture identity */
    public long textureId(){return textureId;}
    /** @return zero-based mip */
    public int mipLevel(){return mipLevel;}
    /** @return rectangle x */
    public int x(){return x;}
    /** @return rectangle y */
    public int y(){return y;}
    /** @return rectangle width */
    public int width(){return width;}
    /** @return rectangle height */
    public int height(){return height;}
    /** @return copied source row stride */
    public int rowPitch(){return rowPitch;}
    /** @return read-only pixels, independently positioned */
    public ByteBuffer rgba8(){return ByteBuffer.wrap(pixels).asReadOnlyBuffer();}
}
