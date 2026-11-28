package top.ceroxe.rt.renderer.rtxpt;

/** Independent numerical reference for the two-transmission path, not a test of the production shader. */
public final class RtxptBentTransmissionReferenceSelfTest {
    static final double ENTRY_Z = 4, EXIT_Z = 2, HALF_APERTURE = .075, IOR = 1.5;
    static final double PRISM_TILT = Math.toRadians(25), TIR_TILT = Math.toRadians(50);
    static final double BLOCKER_Z = 1, BLOCKER_HALF_WIDTH = .082;
    private static final double RECEIVER_ALBEDO = .6;
    private static final int RAYS = 2048;

    // Coordinates are (x,z), per unit width in y. A unit directional beam reaches
    // the entrance through a strip |x| < HALF_APERTURE; the receiver is z=0.
    public static void main(String[] args) {
        double incident = Math.toRadians(25);
        double glass = Math.asin(Math.sin(incident) / IOR);
        double clearX = ENTRY_Z * Math.tan(incident);
        double slabX = (ENTRY_Z - EXIT_Z) * Math.tan(glass) + EXIT_Z * Math.tan(incident);
        double incidentPower = 2 * HALF_APERTURE * Math.cos(incident);
        double clearRadiance = RECEIVER_ALBEDO * Math.cos(incident) / Math.PI;
        var clear = sample(incident, 0, 1, 0, null);
        near("air footprint center", clear.center(), clearX, 1e-12);
        near("air incident power", clear.power(), incidentPower, 1e-12);
        near("air diffuse radiance", clear.meanRadiance(), clearRadiance, 1e-12);

        var slab = sample(incident, 0, IOR, 0, null);
        // Reciprocity gives identical Fresnel reflectance at the two parallel faces.
        double slabInterface = 1 - angularReflectance(incident, glass);
        double slabThroughput = slabInterface * slabInterface;
        near("slab direction restores incident direction", slab.outgoingAngle(), incident, 1e-12);
        near("slab footprint center", slab.center(), slabX, 1e-12);
        near("slab footprint width", slab.width(), 2 * HALF_APERTURE, 1e-12);
        near("slab incident power", slab.power(), incidentPower * slabThroughput, 1e-12);
        near("slab diffuse radiance", slab.meanRadiance(), clearRadiance * slabThroughput, 1e-12);
        require(slab.right() < clear.left(), "Slab translation must separate the two footprints");

        var prism = sample(0, PRISM_TILT, IOR, 0, null);
        double deflection = prismAngle();
        double footprintScale = 1 - Math.tan(PRISM_TILT) * Math.tan(deflection);
        double prismPower = clearPrismFlux(1);
        near("prism outgoing angle", prism.outgoingAngle(), deflection, 1e-12);
        near("redirected footprint center", prism.center(), prismX(0), 1e-12);
        near("prism footprint width", prism.width(), 2 * HALF_APERTURE * footprintScale, 1e-12);
        require(prism.left() > HALF_APERTURE, "Redirected footprint must not overlap the straight beam");
        near("prism incident power", prism.power(), prismPower, 1e-12);
        near("prism diffuse radiance", prism.meanRadiance(),
                RECEIVER_ALBEDO * prismPower / (Math.PI * 2 * HALF_APERTURE * footprintScale), 1e-12);

        double blockerX = prismX(BLOCKER_Z);
        var blocker = new Blocker(BLOCKER_Z, blockerX - BLOCKER_HALF_WIDTH, blockerX + BLOCKER_HALF_WIDTH);
        require(blocker.left() > HALF_APERTURE && blocker.right() < prism.left(),
                "The blocker must miss both the straight beam and the receiver's vertical sightline");
        near("post-exit blocker suppresses bent path", sample(0, PRISM_TILT, IOR, 0, blocker).power(), 0, 0);
        near("same blocker leaves straight beam intact", sample(0, PRISM_TILT, 1, 0, blocker).power(),
                2 * HALF_APERTURE, 1e-12);
        // Only the selected exit transmission is absent. This says nothing about
        // paths that reflect internally and later escape through a different face.
        near("above-critical exit suppresses two-transmission path", sample(0, TIR_TILT, IOR, 0, null).power(), 0, 0);
        double critical = Math.asin(1 / IOR);
        require(sample(0, critical - .001, IOR, 0, null).power() > 0, "Below-critical exit must transmit");
        near("just-above-critical exit", sample(0, critical + .001, IOR, 0, null).power(), 0, 0);

        // Internal distance varies with entry x. The independent closed-form
        // average of exp(-sigma * distance) over that strip uses sinh(t)/t.
        for (double sigma : new double[]{.2, .45, .85}) {
            double t = sigma * HALF_APERTURE * Math.tan(PRISM_TILT);
            double ratio = Math.exp(-sigma * (ENTRY_Z - EXIT_Z)) * Math.sinh(t) / t;
            var stained = sample(0, PRISM_TILT, IOR, sigma, null);
            near("Beer-Lambert deposited power ratio, sigma=" + sigma, stained.power() / prism.power(), ratio, 1e-8);
            near("absorption does not redirect the footprint, sigma=" + sigma, stained.center(), prism.center(), 1e-12);
        }
        System.out.printf(java.util.Locale.ROOT,
                "BENT_TRANSMISSION_REFERENCE PASS slabX=%.6f prismX=%.6f prismPower=%.6f prismRadiance=%.6f%n",
                slab.center(), prism.center(), prism.power(), prism.meanRadiance());
    }

    private static double prismAngle() {
        return Math.asin(IOR * Math.sin(PRISM_TILT)) - PRISM_TILT;
    }

    static double prismX(double z) {
        return (EXIT_Z - z) * Math.tan(prismAngle());
    }

    /** Flux on the receiver at unit incident irradiance, for a prism strip of the given y length. */
    static double clearPrismFlux(double yLength) {
        double normalReflectionAmplitude = (IOR - 1) / (IOR + 1);
        double entryTransmission = 1 - normalReflectionAmplitude * normalReflectionAmplitude;
        double exitTransmission = 1 - angularReflectance(PRISM_TILT, PRISM_TILT + prismAngle());
        return (2 * HALF_APERTURE) * yLength * entryTransmission * exitTransmission;
    }

    // Angle-only Fresnel oracle, independent of the vector trace's cosine form.
    private static double angularReflectance(double incident, double transmitted) {
        double s = Math.sin(incident - transmitted) / Math.sin(incident + transmitted);
        double p = Math.tan(incident - transmitted) / Math.tan(incident + transmitted);
        return (s * s + p * p) / 2;
    }

    private record Vector(double x, double z) {
        Vector plus(Vector b) { return new Vector(x + b.x, z + b.z); }
        Vector scale(double k) { return new Vector(x * k, z * k); }
        double dot(Vector b) { return x * b.x + z * b.z; }
    }

    private record Refraction(Vector direction, double throughput) {}
    private record Blocker(double z, double left, double right) {}
    private record Path(double receiverX, double outgoingAngle, double throughput) {}
    private record Footprint(double left, double right, double outgoingAngle, double power) {
        double center() { return (left + right) * .5; }
        double width() { return right - left; }
        double meanRadiance() { return RECEIVER_ALBEDO * power / (Math.PI * width()); }
    }

    private static Footprint sample(double incident, double tilt, double index, double sigma, Blocker blocker) {
        Vector incoming = new Vector(Math.sin(incident), -Math.cos(incident));
        Vector exitNormal = new Vector(Math.sin(tilt), Math.cos(tilt));
        Refraction atEntry = refract(incoming, new Vector(0, 1), 1, index);
        Refraction atExit = refract(atEntry.direction(), exitNormal, index, 1);
        if (atExit == null) return new Footprint(0, 0, 0, 0);

        Path first = trace(-HALF_APERTURE, exitNormal, atEntry, atExit, sigma, null);
        Path last = trace(HALF_APERTURE, exitNormal, atEntry, atExit, sigma, null);
        double step = 2 * HALF_APERTURE / RAYS;
        double fluxPerRay = step * -incoming.z();
        double power = 0;
        for (int ray = 0; ray < RAYS; ray++) {
            double entryX = -HALF_APERTURE + (ray + .5) * step;
            power += fluxPerRay * trace(entryX, exitNormal, atEntry, atExit, sigma, blocker).throughput();
        }
        return new Footprint(first.receiverX(), last.receiverX(), first.outgoingAngle(), power);
    }

    private static Path trace(double x, Vector exitNormal, Refraction enter, Refraction leave, double sigma, Blocker blocker) {
        Vector entry = new Vector(x, ENTRY_Z);
        double distanceInGlass = exitNormal.dot(new Vector(0, EXIT_Z).plus(entry.scale(-1)))
                / exitNormal.dot(enter.direction());
        Vector exit = entry.plus(enter.direction().scale(distanceInGlass));
        Vector outgoing = leave.direction();
        double receiverX = exit.x() - exit.z() * outgoing.x() / outgoing.z();
        double throughput = enter.throughput() * leave.throughput() * Math.exp(-sigma * distanceInGlass);
        if (blocker != null && blocker.z() > 0 && blocker.z() < exit.z()) {
            double hitX = exit.x() + (blocker.z() - exit.z()) * outgoing.x() / outgoing.z();
            if (hitX >= blocker.left() && hitX <= blocker.right()) throughput = 0;
        }
        return new Path(receiverX, Math.atan2(outgoing.x(), -outgoing.z()), throughput);
    }

    private static Refraction refract(Vector incoming, Vector normal, double nIn, double nOut) {
        if (incoming.dot(normal) > 0) normal = normal.scale(-1);
        double cosIn = -incoming.dot(normal);
        Vector tangent = incoming.plus(normal.scale(cosIn)).scale(nIn / nOut);
        double sinSquared = tangent.dot(tangent);
        if (sinSquared >= 1) return null;
        double cosOut = Math.sqrt(1 - sinSquared);
        return new Refraction(tangent.plus(normal.scale(-cosOut)), 1 - reflectance(cosIn, cosOut, nIn, nOut));
    }

    private static double reflectance(double cosIn, double cosOut, double nIn, double nOut) {
        double s = (nIn * cosIn - nOut * cosOut) / (nIn * cosIn + nOut * cosOut);
        double p = (nOut * cosIn - nIn * cosOut) / (nOut * cosIn + nIn * cosOut);
        return (s * s + p * p) / 2;
    }

    private static void near(String name, double actual, double expected, double tolerance) {
        require(Math.abs(actual - expected) <= tolerance, name + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
