package demo;

public final class HexPhysicsSelfTest {
    private static final double TOLERANCE = 1.0E-10;

    private HexPhysicsSelfTest() {
    }

    public static void main(String[] arguments) {
        conservesMomentumAndEnergyInBallCollision();
        preservesMechanicalEnergyWithGravity();
        remainsFiniteAndContained();
        System.out.println("HexPhysicsSelfTest passed");
    }

    private static void conservesMomentumAndEnergyInBallCollision() {
        HexPhysics.Ball first = new HexPhysics.Ball(-0.57, 0.0, 3.0, 0.75);
        HexPhysics.Ball second = new HexPhysics.Ball(0.57, 0.0, -1.25, -0.20);
        double momentumX = first.velocityX + second.velocityX;
        double momentumY = first.velocityY + second.velocityY;
        double energy = first.kineticEnergy() + second.kineticEnergy();

        HexPhysics.resolveBallPair(first, second);

        requireNear(momentumX, first.velocityX + second.velocityX, "x momentum");
        requireNear(momentumY, first.velocityY + second.velocityY, "y momentum");
        requireNear(energy, first.kineticEnergy() + second.kineticEnergy(), "collision energy");
    }

    private static void preservesMechanicalEnergyWithGravity() {
        HexPhysics physics = new HexPhysics();
        double initial = totalEnergy(physics);
        for (int step = 0; step < 100_000; step++) {
            physics.stepForTesting();
        }
        double relativeError = Math.abs(totalEnergy(physics) - initial) / initial;
        if (relativeError > 1.0E-8) {
            throw new AssertionError("mechanical energy drifted by " + relativeError);
        }
    }

    private static void remainsFiniteAndContained() {
        HexPhysics physics = new HexPhysics();
        for (int step = 0; step < 50_000; step++) {
            physics.stepForTesting();
        }
        double centerLimit = HexPhysics.HEX_APOTHEM - HexPhysics.BALL_RADIUS;
        for (HexPhysics.Ball ball : physics.balls()) {
            if (!Double.isFinite(ball.x) || !Double.isFinite(ball.y)
                    || !Double.isFinite(ball.velocityX) || !Double.isFinite(ball.velocityY)) {
                throw new AssertionError("physics state became non-finite");
            }
            for (int side = 0; side < 6; side++) {
                double angle = side * Math.PI / 3.0;
                double distance = ball.x * Math.cos(angle) + ball.y * Math.sin(angle);
                if (distance > centerLimit + 1.0E-9) {
                    throw new AssertionError("ball escaped the hexagon: distance=" + distance);
                }
            }
        }
    }

    private static double totalEnergy(HexPhysics physics) {
        return physics.balls().stream().mapToDouble(HexPhysics.Ball::mechanicalEnergy).sum();
    }

    private static void requireNear(double expected, double actual, String quantity) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError(quantity + " changed: expected=" + expected + ", actual=" + actual);
        }
    }
}
