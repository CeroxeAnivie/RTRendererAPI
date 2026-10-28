package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/** CPU analytical and independent quadrature checks. Opens no renderer or graphics device. */
public final class MediumOpticsSelfTest {
    private MediumOpticsSelfTest() {}

    public static void main(String[] args) {
        homogeneousIntegral();
        partitionInvariance();
        dielectricInterfaces();
        roughDielectricSampling();
        phaseFunction();
        MediumScatteringSelfTest.verify();
        invalidInputs();
        System.out.println("MediumOpticsSelfTest passed: zero/tiny/large optical depth, independent quadrature, "
                + "partition invariance, Fresnel/Snell/TIR, GGX VNDF/PDF weights, HG moments/sampling, "
                + "RGB free-flight weights, bounded scattering-order renewal, heterogeneous density and atlas interpolation");
    }

    private static void homogeneousIntegral() {
        near(MediumOptics.radiance(1, .2, 0, 4), 1.8, 1e-15, "zero-extinction emission");
        near(MediumOptics.transmittance(.4, 4), .2018965179946554, 1e-15, "Beer-Lambert red");
        near(MediumOptics.transmittance(.2, 4), .44932896411722156, 1e-15, "Beer-Lambert green");
        near(MediumOptics.integral(1000, 1000), .001, 1e-15, "saturated optical depth");
        near(MediumOptics.integral(0, 0), 0, 0, "empty segment");
        for (double opticalDepth : new double[]{1e-14, 1e-10, 1e-6, .01, .5, 2, 10}) {
            double independentlyIntegrated = quadrature(t -> Math.exp(-opticalDepth * t), 0, 1, 1e-12);
            near(MediumOptics.integral(opticalDepth, 1), independentlyIntegrated, 2e-11,
                    "integral against quadrature at optical depth " + opticalDepth);
        }
        // A direct (1 - exp(-tau))/tau expression loses this limit in single precision.
        near(MediumOptics.integral(1e-14, 1), 1 - .5e-14, 2e-16, "continuous tiny-depth limit");
    }

    private static void partitionInvariance() {
        var random = new Random(7_000_000);
        for (int sample = 0; sample < 2048; sample++) {
            double extinction = sample % 17 == 0 ? 0 : Math.pow(10, random.nextDouble() * 15 - 12);
            double distance = Math.pow(10, random.nextDouble() * 9 - 6);
            double first = distance * random.nextDouble(), second = distance - first;
            double background = random.nextDouble() * 20, emission = random.nextDouble() * 5;
            double whole = MediumOptics.radiance(background, emission, extinction, distance);
            double split = MediumOptics.radiance(
                    MediumOptics.radiance(background, emission, extinction, first), emission, extinction, second);
            near(split, whole, 2e-12 * Math.max(1, whole), "homogeneous segment partition");
        }
    }

    private static void dielectricInterfaces() {
        near(MediumOptics.fresnel(1, 1.5, 1), .04, 1e-15, "air/glass normal incidence");
        near(MediumOptics.fresnel(1.5, 1, 1), .04, 1e-15, "glass/air normal incidence");
        near(MediumOptics.fresnel(1, 1.5, 0), 1, 0, "grazing reflection");
        near(MediumOptics.fresnel(1.5, 1.5, 0), 0, 0, "equal-IOR grazing interface");
        near(MediumOptics.fresnel(3e38, 2e38, 1), .04, 1e-15, "large finite IOR sum");
        near(MediumOptics.refractedCosine(Float.MAX_VALUE, 1, 1), 1, 0, "large eta normal incidence");
        near(MediumOptics.fresnel(Float.MAX_VALUE, 1, .99), 1, 0, "large eta total reflection");
        near(MediumOptics.refractedCosine(1, 1.5, .8), Math.sqrt(.84), 1e-15, "Snell law");
        require(Double.isNaN(MediumOptics.refractedCosine(1.5, 1, .5)), "TIR has no transmitted ray");
        near(MediumOptics.fresnel(1.5, 1, .5), 1, 0, "TIR reflection");
        double criticalCosine = Math.sqrt(1 - 1 / (1.5 * 1.5));
        require(Double.isNaN(MediumOptics.refractedCosine(1.5, 1, criticalCosine - 1e-7)), "below critical cosine");
        require(Double.isFinite(MediumOptics.refractedCosine(1.5, 1, criticalCosine + 1e-7)), "above critical cosine");
        for (int step = 0; step <= 1000; step++) {
            double cosine = step / 1000.0;
            double reflected = MediumOptics.fresnel(1, 1.5, cosine);
            require(Double.isFinite(reflected) && reflected >= 0 && reflected <= 1, "bounded Fresnel reflectance");
            double transmitted = MediumOptics.refractedCosine(1, 1.5, cosine);
            near(reflected, MediumOptics.fresnel(1.5, 1, transmitted), 2e-7, "reciprocal interface reflectance");
        }
    }

    private static void roughDielectricSampling() {
        int sampleCount = 65_536;
        for (double alpha : new double[]{.25, .7, 1}) {
            for (double viewCosine : new double[]{.2, .8, 1}) {
                var view = new MediumOptics.Vector(Math.sqrt(1 - viewCosine * viewCosine), 0, viewCosine);
                double sampledMoment = 0;
                for (int sample = 0; sample < sampleCount; sample++) {
                    double first = (sample + .5) / sampleCount;
                    double second = Integer.toUnsignedLong(Integer.reverse(sample)) * 0x1.0p-32;
                    var half = MediumOptics.sampleVisibleNormal(view, alpha, first, second);
                    require(half.z() >= 0 && view.dot(half) > 0, "VNDF samples visible hemisphere");
                    near(half.dot(half), 1, 2e-14, "unit sampled microfacet");
                    sampledMoment += half.z() / sampleCount;
                    if ((sample & 63) == 0) matchedDielectricWeights(view, half, alpha);
                }
                // Independent midpoint integration over (cos(theta), phi), not the sampling map.
                double normalization = 0, expectedMoment = 0;
                int polar = 1024, azimuth = 256;
                double solidAngle = 2 * Math.PI / (polar * azimuth);
                for (int y = 0; y < polar; y++) {
                    double cosine = (y + .5) / polar, sine = Math.sqrt(1 - cosine * cosine);
                    for (int x = 0; x < azimuth; x++) {
                        double phi = (x + .5) * 2 * Math.PI / azimuth;
                        var half = new MediumOptics.Vector(sine * Math.cos(phi), sine * Math.sin(phi), cosine);
                        double pdf = MediumOptics.ggxVisibleNormalPdf(view, half, alpha);
                        normalization += pdf * solidAngle;
                        expectedMoment += cosine * pdf * solidAngle;
                    }
                }
                near(normalization, 1, .001, "VNDF independent solid-angle normalization");
                near(sampledMoment, expectedMoment, .001, "VNDF sampled angular moment");
            }
        }
        // Smooth radiance transport changes by eta^2; opposite interfaces cancel this factor.
        double eta = 1 / 1.5;
        near(eta * eta * (1 / eta) * (1 / eta), 1, 2e-16, "closed dielectric radiance scaling");
        for (double view : new double[]{1e-38, 1e-8, .01, .5, 1}) {
            for (double outgoing : new double[]{1e-38, 1e-8, .01, .5, 1}) {
                double stable = MediumOptics.ggxContinuationMasking(view, outgoing, .3);
                double divided = MediumOptics.ggxJointMasking(view, outgoing, .3) / MediumOptics.ggxMasking(view, .3);
                near(stable, divided, 3e-15, "stable Smith grazing ratio");
                require(stable >= 0 && stable <= 1, "bounded dielectric continuation weight");
            }
        }
    }

    private static void matchedDielectricWeights(MediumOptics.Vector view, MediumOptics.Vector half, double alpha) {
        double viewHalf = view.dot(half), normalView = view.z();
        double d = MediumOptics.ggxDistribution(half.z(), alpha);
        double g1 = MediumOptics.ggxMasking(normalView, alpha);
        double halfPdf = MediumOptics.ggxVisibleNormalPdf(view, half, alpha);
        var reflected = half.scaled(2 * viewHalf).plus(view.scaled(-1));
        if (reflected.z() > 1e-8) {
            double fresnel = MediumOptics.fresnel(1, 1.5, viewHalf);
            double g2 = MediumOptics.ggxJointMasking(normalView, reflected.z(), alpha);
            double bsdf = fresnel * d * g2 / (4 * normalView * reflected.z());
            double directionPdf = halfPdf / (4 * viewHalf);
            near(bsdf * reflected.z() / directionPdf, fresnel * g2 / g1, 2e-13, "reflection BSDF/PDF cancellation");
        }
        double eta = 1 / 1.5;
        double transmittedCosine = MediumOptics.refractedCosine(1, 1.5, viewHalf);
        var transmitted = view.scaled(-eta).plus(half.scaled(eta * viewHalf - transmittedCosine));
        if (transmitted.z() < -1e-8) {
            double transmittedHalf = transmitted.dot(half), outgoingCosine = -transmitted.z();
            double g2 = MediumOptics.ggxJointMasking(normalView, outgoingCosine, alpha);
            double denominator = transmittedHalf + eta * viewHalf;
            double jacobian = Math.abs(transmittedHalf) / (denominator * denominator);
            double fresnel = MediumOptics.fresnel(1, 1.5, viewHalf);
            double bsdf = (1 - fresnel) * d * g2 * Math.abs(viewHalf * transmittedHalf)
                    / (normalView * outgoingCosine * denominator * denominator) * eta * eta;
            double directionPdf = halfPdf * jacobian;
            near(bsdf * outgoingCosine / directionPdf, (1 - fresnel) * g2 / g1 * eta * eta,
                    2e-13, "transmission BSDF/Jacobian/PDF cancellation");
        }
    }

    private static void phaseFunction() {
        for (double anisotropy : new double[]{-.95, -.5, 0, .5, .95}) {
            double normalization = 2 * Math.PI * quadrature(c -> MediumOptics.phase(c, anisotropy), -1, 1, 1e-11);
            double firstMoment = 2 * Math.PI * quadrature(c -> c * MediumOptics.phase(c, anisotropy), -1, 1, 1e-11);
            near(normalization, 1, 2e-9, "HG solid-angle normalization");
            near(firstMoment, anisotropy, 2e-9, "HG first angular moment");
            for (double cosine : new double[]{-1, -.9, -.1, 0, .2, .9, 1}) {
                near(MediumOptics.phase(cosine, anisotropy), MediumOptics.phase(-cosine, -anisotropy),
                        1e-12, "HG orientation symmetry");
            }
        }
        near(MediumOptics.phase(.7, 0), 1 / (4 * Math.PI), 1e-16, "isotropic phase");
        require(Double.isFinite(MediumOptics.phase(1, .999999)), "finite sharply forward phase");
        require(Double.isFinite(MediumOptics.phase(-1, -.999999)), "finite sharply backward phase");
    }

    private static void invalidInputs() {
        rejects(() -> MediumOptics.integral(-1, 1));
        rejects(() -> MediumOptics.integral(1, Double.POSITIVE_INFINITY));
        rejects(() -> MediumOptics.fresnel(.9, 1.5, 1));
        rejects(() -> MediumOptics.fresnel(1, 1.5, Double.NaN));
        rejects(() -> MediumOptics.phase(0, 1));
        rejects(() -> MediumOptics.phase(0, -1));
    }

    private static double quadrature(DoubleUnaryOperator function, double a, double b, double tolerance) {
        double middle = .5 * (a + b), fa = function.applyAsDouble(a), fm = function.applyAsDouble(middle);
        double fb = function.applyAsDouble(b), whole = (b - a) * (fa + 4 * fm + fb) / 6;
        return adaptive(function, a, b, fa, fm, fb, whole, tolerance, 30);
    }

    private static double adaptive(DoubleUnaryOperator function, double a, double b, double fa, double fm,
            double fb, double whole, double tolerance, int remaining) {
        double middle = .5 * (a + b), leftMiddle = .5 * (a + middle), rightMiddle = .5 * (middle + b);
        double fl = function.applyAsDouble(leftMiddle), fr = function.applyAsDouble(rightMiddle);
        double left = (middle - a) * (fa + 4 * fl + fm) / 6;
        double right = (b - middle) * (fm + 4 * fr + fb) / 6;
        double error = left + right - whole;
        if (Math.abs(error) <= 15 * tolerance) return left + right + error / 15;
        if (remaining == 0) throw new AssertionError("independent quadrature did not converge");
        return adaptive(function, a, middle, fa, fl, fm, left, tolerance / 2, remaining - 1)
                + adaptive(function, middle, b, fm, fr, fb, right, tolerance / 2, remaining - 1);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(message + ": " + actual + " vs " + expected + " tolerance=" + tolerance);
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid optical input was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
