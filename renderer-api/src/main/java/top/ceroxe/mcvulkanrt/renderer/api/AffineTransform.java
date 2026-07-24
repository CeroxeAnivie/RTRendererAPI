package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.FloatBuffer;
import java.util.Arrays;

/** Immutable row-major 3x4 affine transform used by persistent scene instances. */
public final class AffineTransform {
    private static final int ELEMENTS = 12;
    private static final AffineTransform IDENTITY = new AffineTransform(new float[]{
            1.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 0.0F,
            0.0F, 0.0F, 1.0F, 0.0F
    });

    private final float[] elements;

    public AffineTransform(float[] elements) {
        if (elements == null || elements.length != ELEMENTS) {
            throw new IllegalArgumentException("affine transform requires exactly 12 elements");
        }
        this.elements = elements.clone();
        for (float element : this.elements) {
            if (!Float.isFinite(element)) {
                throw new IllegalArgumentException("affine transform elements must be finite");
            }
        }
        float determinant = this.elements[0] * (this.elements[5] * this.elements[10] - this.elements[6] * this.elements[9])
                - this.elements[1] * (this.elements[4] * this.elements[10] - this.elements[6] * this.elements[8])
                + this.elements[2] * (this.elements[4] * this.elements[9] - this.elements[5] * this.elements[8]);
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-8F) {
            throw new IllegalArgumentException("affine transform must be invertible");
        }
    }

    public static AffineTransform identity() {
        return IDENTITY;
    }

    public FloatBuffer elements() {
        return FloatBuffer.wrap(elements).asReadOnlyBuffer();
    }

    public float element(int index) {
        return elements[index];
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof AffineTransform transform
                && Arrays.equals(elements, transform.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elements);
    }

    @Override
    public String toString() {
        return "AffineTransform" + Arrays.toString(elements);
    }
}
