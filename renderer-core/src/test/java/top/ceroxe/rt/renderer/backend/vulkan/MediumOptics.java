package top.ceroxe.rt.renderer.backend.vulkan;

/** Double-precision optical reference for deterministic tests; never shipped in the runtime. */
final class MediumOptics {
    private MediumOptics() {}

    static double transmittance(double extinction, double distance) {
        nonNegativeFinite(extinction, "extinction");
        nonNegativeFinite(distance, "distance");
        return Math.exp(-extinction * distance);
    }

    /** Integral of exp(-extinction * t), with its continuous zero-extinction limit. */
    static double integral(double extinction, double distance) {
        nonNegativeFinite(extinction, "extinction");
        nonNegativeFinite(distance, "distance");
        return extinction == 0 ? distance : -Math.expm1(-extinction * distance) / extinction;
    }

    static double radiance(double background, double emission, double extinction, double distance) {
        nonNegativeFinite(background, "background");
        nonNegativeFinite(emission, "emission");
        return background * transmittance(extinction, distance) + emission * integral(extinction, distance);
    }

    static double refractedCosine(double incidentIor, double transmittedIor, double cosine) {
        opticalInterface(incidentIor, transmittedIor, cosine);
        double eta = incidentIor / transmittedIor;
        double sine = eta * Math.sqrt((1 - cosine) * (1 + cosine));
        return sine > 1 ? Double.NaN : Math.sqrt(Math.max(0, (1 - sine) * (1 + sine)));
    }

    static double fresnel(double incidentIor, double transmittedIor, double cosine) {
        opticalInterface(incidentIor, transmittedIor, cosine);
        if (incidentIor == transmittedIor) return 0;
        double transmittedCosine = refractedCosine(incidentIor, transmittedIor, cosine);
        if (Double.isNaN(transmittedCosine) || cosine == 0) return 1;
        double scale = Math.max(incidentIor, transmittedIor);
        double a = incidentIor / scale, b = transmittedIor / scale;
        double s = (a * cosine - b * transmittedCosine) / (a * cosine + b * transmittedCosine);
        double p = (b * cosine - a * transmittedCosine) / (b * cosine + a * transmittedCosine);
        return .5 * (s * s + p * p);
    }

    static double ggxDistribution(double normalHalf, double alpha) {
        double a2 = alpha * alpha;
        double denominator = 1 - normalHalf * normalHalf + a2 * normalHalf * normalHalf;
        return a2 / (Math.PI * denominator * denominator);
    }

    static double ggxMasking(double cosine, double alpha) {
        if (cosine <= 0) return 0;
        return 2 * cosine / (cosine + Math.sqrt(alpha * alpha + (1 - alpha * alpha) * cosine * cosine));
    }

    static double ggxJointMasking(double viewCosine, double outgoingCosine, double alpha) {
        double gv = ggxMasking(viewCosine, alpha), gl = ggxMasking(outgoingCosine, alpha);
        return gv == 0 || gl == 0 ? 0 : 1 / (1 / gv + 1 / gl - 1);
    }

    static double ggxContinuationMasking(double viewCosine, double outgoingCosine, double alpha) {
        double a2 = alpha * alpha;
        double sv = Math.sqrt(a2 + (1 - a2) * viewCosine * viewCosine);
        double sl = Math.sqrt(a2 + (1 - a2) * outgoingCosine * outgoingCosine);
        return outgoingCosine * (viewCosine + sv) / (outgoingCosine * sv + viewCosine * sl);
    }

    static double ggxVisibleNormalPdf(Vector view, Vector half, double alpha) {
        return ggxDistribution(half.z, alpha) * ggxMasking(view.z, alpha) * Math.max(view.dot(half), 0) / view.z;
    }

    static Vector sampleVisibleNormal(Vector view, double alpha, double first, double second) {
        Vector stretched = new Vector(alpha * view.x, alpha * view.y, view.z).normalized();
        double planar = stretched.x * stretched.x + stretched.y * stretched.y;
        Vector t1 = planar > 0 ? new Vector(-stretched.y, stretched.x, 0).scaled(1 / Math.sqrt(planar)) : new Vector(1, 0, 0);
        Vector t2 = stretched.cross(t1);
        double r = Math.sqrt(first), phi = 2 * Math.PI * second;
        double x = r * Math.cos(phi), y = r * Math.sin(phi), blend = .5 * (1 + stretched.z);
        y = (1 - blend) * Math.sqrt(Math.max(0, 1 - x * x)) + blend * y;
        Vector visible = t1.scaled(x).plus(t2.scaled(y))
                .plus(stretched.scaled(Math.sqrt(Math.max(0, 1 - x * x - y * y))));
        return new Vector(alpha * visible.x, alpha * visible.y, Math.max(0, visible.z)).normalized();
    }

    record Vector(double x, double y, double z) {
        double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        Vector plus(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        Vector scaled(double amount) { return new Vector(x * amount, y * amount, z * amount); }
        Vector normalized() { return scaled(1 / Math.sqrt(dot(this))); }
        Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }
    }

    static double phase(double cosine, double anisotropy) {
        if (!Double.isFinite(cosine) || cosine < -1 || cosine > 1)
            throw new IllegalArgumentException("phase cosine must be within [-1, 1]");
        if (!Double.isFinite(anisotropy) || Math.abs(anisotropy) >= 1)
            throw new IllegalArgumentException("anisotropy must be within (-1, 1)");
        // This form avoids subtracting nearly equal values at the forward/backward peak.
        double denominator = anisotropy >= 0
                ? (1 - anisotropy) * (1 - anisotropy) + 2 * anisotropy * (1 - cosine)
                : (1 + anisotropy) * (1 + anisotropy) - 2 * anisotropy * (1 + cosine);
        return (1 - anisotropy) * (1 + anisotropy)
                / (4 * Math.PI * denominator * Math.sqrt(denominator));
    }

    private static void opticalInterface(double incidentIor, double transmittedIor, double cosine) {
        if (!Double.isFinite(incidentIor) || incidentIor < 1
                || !Double.isFinite(transmittedIor) || transmittedIor < 1
                || !Double.isFinite(cosine) || cosine < 0 || cosine > 1)
            throw new IllegalArgumentException("invalid optical interface");
    }

    private static void nonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid " + name);
    }
}
