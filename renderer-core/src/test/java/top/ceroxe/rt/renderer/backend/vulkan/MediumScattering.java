package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.function.DoubleUnaryOperator;

/** Test-only double-precision reference; neither loads a shader nor opens a graphics device. */
final class MediumScattering {
    private MediumScattering() {}

    static double phaseCosine(double g, double uniform) {
        double a = Math.abs(g), u = g >= 0 ? uniform : 1 - uniform, e = 1 - a;
        double ratio = e / (e + 2 * a * u);
        double cosine = 1 - 2 * ratio * ratio * (1 - u) * (1 + a * u);
        return g >= 0 ? cosine : -cosine;
    }

    /** Samples an equal mixture of three exponential distributions in density-mass units. */
    static Flight flight(double[] extinction, double[] scattering, double mass, int channel, double uniform) {
        double sampledMass = extinction[channel] == 0 ? Double.POSITIVE_INFINITY
                : -Math.log1p(-uniform) / extinction[channel];
        boolean collision = sampledMass < mass;
        double position = collision ? sampledMass : mass;
        double[] weights = new double[3];
        double pdf = 0;
        for (int c = 0; c < 3; c++) {
            weights[c] = Math.exp(-extinction[c] * position);
            pdf += (collision ? extinction[c] : 1) * weights[c] / 3;
        }
        for (int c = 0; c < 3; c++) weights[c] *= (collision ? scattering[c] : 1) / pdf;
        return new Flight(collision, position, weights);
    }

    record Flight(boolean collision, double mass, double[] weights) {}

    /** Midpoint piecewise-constant density, including the inverse mass-to-distance map. */
    static DensityFlight densityFlight(DoubleUnaryOperator density, double distance, int steps, double targetMass) {
        double width = distance / steps, mass = 0, collision = -1;
        for (int step = 0; step < steps; step++) {
            double start = step * width, value = density.applyAsDouble(start + .5 * width);
            double intervalMass = value * width;
            if (collision < 0 && value > 0 && targetMass < mass + intervalMass)
                collision = start + (targetMass - mass) / value;
            mass += intervalMass;
        }
        return new DensityFlight(mass, collision);
    }

    record DensityFlight(double mass, double collisionDistance) {}

    /** Independent eight-corner weighted sum over the red channel of the base-level atlas. */
    static double atlas(byte[] rgba, int width, int height, int slices, double u, double v, double w) {
        if (u < 0 || u > 1 || v < 0 || v > 1 || w < 0 || w > 1) return 0;
        double[] point = {u * width - .5, v * height - .5, w * slices - .5};
        int[] lower = {(int) Math.floor(point[0]), (int) Math.floor(point[1]), (int) Math.floor(point[2])};
        int[] extent = {width, height, slices};
        double result = 0;
        for (int corner = 0; corner < 8; corner++) {
            int[] voxel = new int[3];
            double weight = 1;
            for (int axis = 0; axis < 3; axis++) {
                int upper = (corner >>> axis) & 1;
                double fraction = point[axis] - lower[axis];
                weight *= upper == 0 ? 1 - fraction : fraction;
                voxel[axis] = Math.max(0, Math.min(extent[axis] - 1, lower[axis] + upper));
            }
            int index = 4 * (voxel[0] + width * (voxel[1] + height * voxel[2]));
            result += weight * Byte.toUnsignedInt(rgba[index]) / 255;
        }
        return result;
    }
}
