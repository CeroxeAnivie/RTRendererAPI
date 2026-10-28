package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Random;

/** Probability and convergence fixtures, independent of the runtime GPU implementation. */
final class MediumScatteringSelfTest {
    private MediumScatteringSelfTest() {}

    static void verify() {
        mixtureClosure();
        sampledMixtureWeights();
        sampledPhaseMoments();
        boundedScatteringOrders();
        heterogeneousMass();
        atlasInterpolation();
    }

    private static void mixtureClosure() {
        double[] extinction = {0, .7, 2.1}, scattering = {0, .2, 1.9};
        for (double mass : new double[]{.01, .5, 3, 10}) {
            // Composite midpoint integration is independent of the exponential sampling map.
            int intervals = 65_536;
            double probability = 0;
            double[] source = new double[3];
            for (int interval = 0; interval < intervals; interval++) {
                double x = (interval + .5) * mass / intervals, pdf = 0;
                double[] transmittance = new double[3];
                for (int c = 0; c < 3; c++) {
                    transmittance[c] = Math.exp(-extinction[c] * x);
                    pdf += extinction[c] * transmittance[c] / 3;
                }
                probability += pdf * mass / intervals;
                for (int c = 0; c < 3; c++) {
                    double weight = scattering[c] * transmittance[c] / pdf;
                    require(weight >= 0 && weight <= 3 + 1e-14, "RGB collision weight bounded by three");
                    source[c] += pdf * weight * mass / intervals;
                }
            }
            double survival = 0;
            for (int c = 0; c < 3; c++) {
                survival += Math.exp(-extinction[c] * mass) / 3;
                near(source[c], scattering[c] * MediumOptics.integral(extinction[c], mass),
                        5e-9, "RGB mixture collision expectation");
            }
            near(probability + survival, 1, 5e-9, "collision density plus escape probability closes");
        }
    }

    private static void sampledMixtureWeights() {
        double[] extinction = {0, .7, 2.1}, scattering = {0, .2, 1.9};
        int samples = 32_768;
        for (double mass : new double[]{.01, .5, 3, 10}) {
            double[] collisions = new double[3], escapes = new double[3];
            for (int channel = 0; channel < 3; channel++) {
                for (int sample = 0; sample < samples; sample++) {
                    var flight = MediumScattering.flight(extinction, scattering, mass, channel, (sample + .5) / samples);
                    if (channel == 0) require(!flight.collision(), "zero-extinction channel has infinite free flight");
                    for (int c = 0; c < 3; c++) {
                        double weight = flight.weights()[c];
                        require(Double.isFinite(weight) && weight >= 0 && weight <= 3 + 1e-14,
                                "finite bounded collision and escape weights");
                        (flight.collision() ? collisions : escapes)[c] += weight / (3 * samples);
                    }
                }
            }
            for (int c = 0; c < 3; c++) {
                near(collisions[c], scattering[c] * MediumOptics.integral(extinction[c], mass), .0002,
                        "stratified RGB free-flight collision expectation");
                near(escapes[c], Math.exp(-extinction[c] * mass), .0002,
                        "stratified RGB free-flight escape expectation");
            }
        }
    }

    private static void sampledPhaseMoments() {
        int samples = 131_072;
        for (double g : new double[]{-.999999, -.95, -.5, -1e-9, 0, 1e-9, .5, .95, .999999}) {
            double firstMoment = 0, secondMoment = 0;
            near(MediumScattering.phaseCosine(g, 0), -1, 3e-10, "HG inverse CDF lower endpoint");
            near(MediumScattering.phaseCosine(g, 1), 1, 3e-10, "HG inverse CDF upper endpoint");
            for (int sample = 0; sample < samples; sample++) {
                double u = (sample + .5) / samples, cosine = MediumScattering.phaseCosine(g, u);
                require(Double.isFinite(cosine) && cosine >= -1 && cosine <= 1, "HG sample is on unit sphere");
                near(cosine, -MediumScattering.phaseCosine(-g, 1 - u), 4e-15, "HG inverse CDF sign symmetry");
                firstMoment += cosine / samples;
                secondMoment += cosine * cosine / samples;
            }
            // The sharply peaked endpoints below the sample spacing are deliberately bounded by O(1/N).
            near(firstMoment, g, 3e-5, "HG sampled first moment");
            near(secondMoment, (1 + 2 * g * g) / 3, 3e-5, "HG sampled second moment");
        }
    }

    private static void boundedScatteringOrders() {
        // One-state renewal fixture: L_B = T + (1-T)L_(B-1), L_0 = T.
        // It tests RGB path weights and final ballistic termination, not a rendered scene.
        for (double[] extinction : new double[][]{{0, .4, 1.2}, {.2, .65, 1.4}}) {
            double mass = .75;
            for (int bounces : new int[]{0, 1, 2, 4, 8}) {
                var random = new Random(7_100_000L + bounces);
                int samples = 131_072;
                double[] mean = new double[3], squaredDeviations = new double[3];
                for (int sample = 0; sample < samples; sample++) {
                    double[] throughput = {1, 1, 1}, result = new double[3];
                    for (int order = 0; order <= bounces; order++) {
                        if (order == bounces) {
                            for (int c = 0; c < 3; c++) result[c] = throughput[c] * Math.exp(-extinction[c] * mass);
                            break;
                        }
                        var flight = MediumScattering.flight(extinction, extinction, mass, random.nextInt(3), random.nextDouble());
                        for (int c = 0; c < 3; c++) throughput[c] *= flight.weights()[c];
                        if (!flight.collision()) {
                            System.arraycopy(throughput, 0, result, 0, 3);
                            break;
                        }
                    }
                    for (int c = 0; c < 3; c++) {
                        double delta = result[c] - mean[c];
                        mean[c] += delta / (sample + 1);
                        squaredDeviations[c] += delta * (result[c] - mean[c]);
                    }
                }
                for (int c = 0; c < 3; c++) {
                    double t = Math.exp(-extinction[c] * mass), expected = 1 - Math.pow(1 - t, bounces + 1);
                    double standardError = Math.sqrt(squaredDeviations[c] / (samples * (double) (samples - 1)));
                    require(standardError < .01, "renewal fixture retains informative sampling precision");
                    near(mean[c], expected, 7 * standardError + .0002, "finite scattering-order renewal expectation");
                    require(expected >= 0 && expected <= 1, "truncated pure-scattering expectation conserves energy");
                }
            }
        }
    }

    private static void heterogeneousMass() {
        double previousError = Double.NaN;
        for (int steps : new int[]{4, 8, 16, 32, 64}) {
            var flight = MediumScattering.densityFlight(x -> 1 + x * x, 1, steps, .6);
            double error = 4.0 / 3 - flight.mass();
            near(error, 1.0 / (12 * steps * steps), 2e-15, "quadratic density midpoint error");
            if (steps > 4) near(previousError / error, 4, 1e-8, "second-order density refinement");
            previousError = error;
            double x = flight.collisionDistance();
            near(x + x * x * x / 3, .6, 1.0 / (4 * steps * steps), "inverse density flight against analytic mass");
        }
        var slabs = MediumScattering.densityFlight(x -> x < 1 || x >= 2 && x < 3 ? 0 : x < 2 ? 2 : 1, 4, 4, 2.5);
        near(slabs.mass(), 3, 0, "vacuum slabs contribute no density mass");
        near(slabs.collisionDistance(), 3.5, 0, "free flight crosses vacuum slab before collision");
        near(MediumScattering.densityFlight(x -> 0, 4, 4, 0).collisionDistance(), -1, 0, "empty field cannot collide");
        near(MediumScattering.densityFlight(x -> 1, 4, 4, 4).collisionDistance(), -1, 0, "segment endpoint means survival");
    }

    private static void atlasInterpolation() {
        int width = 2, height = 3, slices = 4;
        byte[] rgba = new byte[width * height * slices * 4];
        for (int z = 0; z < slices; z++) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = 4 * (x + width * (y + height * z));
            rgba[index] = (byte) (10 + 20 * x + 15 * y + 25 * z);
            rgba[index + 1] = (byte) 255;
            rgba[index + 3] = (byte) 117;
        }
        for (int z = 0; z < slices; z++) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            near(MediumScattering.atlas(rgba, width, height, slices, (x + .5) / width, (y + .5) / height, (z + .5) / slices),
                    (10 + 20 * x + 15 * y + 25 * z) / 255.0, 1e-15, "texel center and vertical Z slice address");
        near(MediumScattering.atlas(rgba, width, height, slices, .5, .5, .5), 72.5 / 255, 1e-15, "trilinear affine density field");
        near(MediumScattering.atlas(rgba, width, height, slices, 0, 0, 0), 10 / 255.0, 1e-15, "clamped lower domain edge");
        near(MediumScattering.atlas(rgba, width, height, slices, 1, 1, 1), 135 / 255.0, 1e-15, "clamped upper domain edge");
        near(MediumScattering.atlas(rgba, width, height, slices, -.0001, .5, .5), 0, 0, "outside field is vacuum");
        near(MediumScattering.atlas(rgba, width, height, slices, .5, .5, 1.0001), 0, 0, "outside last slice is vacuum");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(message + ": " + actual + " vs " + expected + " tolerance=" + tolerance);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
