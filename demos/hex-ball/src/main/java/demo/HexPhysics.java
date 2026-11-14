package demo;

import java.util.List;

/** Deterministic equal-mass physics for three balls constrained by a regular hexagon. */
final class HexPhysics {
    static final double BALL_RADIUS = 0.58;
    static final double HEX_APOTHEM = 4.35;
    static final double FIXED_STEP_SECONDS = 1.0 / 240.0;
    static final double GRAVITY = 9.81;

    private static final double MAX_ACCUMULATED_SECONDS = 0.05;
    private static final double MIN_DISTANCE_SQUARED = 1.0E-18;

    private final List<Ball> balls = List.of(
            new Ball(-1.75, 2.15, 2.85, 0.40),
            new Ball(1.20, 0.35, -2.20, 2.35),
            new Ball(0.30, -1.65, 1.15, 3.80)
    );

    private long lastUpdateNanos = System.nanoTime();
    private double accumulatorSeconds;

    List<Ball> balls() {
        return balls;
    }

    void advanceTo(long nowNanos) {
        long elapsedNanos = Math.max(0L, nowNanos - lastUpdateNanos);
        lastUpdateNanos = nowNanos;
        accumulatorSeconds = Math.min(
                accumulatorSeconds + elapsedNanos * 1.0E-9,
                MAX_ACCUMULATED_SECONDS
        );
        while (accumulatorSeconds >= FIXED_STEP_SECONDS) {
            step();
            accumulatorSeconds -= FIXED_STEP_SECONDS;
        }
    }

    void stepForTesting() {
        step();
    }

    private void step() {
        for (Ball ball : balls) {
            ball.x += ball.velocityX * FIXED_STEP_SECONDS;
            ball.y += ball.velocityY * FIXED_STEP_SECONDS
                    - 0.5 * GRAVITY * FIXED_STEP_SECONDS * FIXED_STEP_SECONDS;
            ball.velocityY -= GRAVITY * FIXED_STEP_SECONDS;
            constrainToHexagon(ball);
        }

        // Repeated pair resolution removes rare three-body overlap without changing the
        // impulse equation. Every impulse remains exactly equal and opposite.
        for (int pass = 0; pass < 3; pass++) {
            for (int first = 0; first < balls.size(); first++) {
                for (int second = first + 1; second < balls.size(); second++) {
                    resolveBallPair(balls.get(first), balls.get(second));
                }
            }
            for (Ball ball : balls) {
                constrainToHexagon(ball);
            }
        }
    }

    static void resolveBallPair(Ball first, Ball second) {
        double deltaX = second.x - first.x;
        double deltaY = second.y - first.y;
        double distanceSquared = deltaX * deltaX + deltaY * deltaY;
        double diameter = BALL_RADIUS * 2.0;
        if (distanceSquared >= diameter * diameter) {
            return;
        }

        double normalX;
        double normalY;
        double distance;
        if (distanceSquared <= MIN_DISTANCE_SQUARED) {
            // A deterministic axis prevents NaNs while preserving equal and opposite correction.
            normalX = 1.0;
            normalY = 0.0;
            distance = 0.0;
        } else {
            distance = Math.sqrt(distanceSquared);
            normalX = deltaX / distance;
            normalY = deltaY / distance;
        }

        double correction = (diameter - distance) * 0.5;
        first.x -= normalX * correction;
        first.y -= normalY * correction;
        second.x += normalX * correction;
        second.y += normalY * correction;

        double relativeNormalVelocity = (second.velocityX - first.velocityX) * normalX
                + (second.velocityY - first.velocityY) * normalY;
        if (relativeNormalVelocity >= 0.0) {
            return;
        }

        // For equal masses and restitution 1, exchanging normal velocity components is
        // algebraically equivalent to an impulse that conserves momentum and kinetic energy.
        first.velocityX += relativeNormalVelocity * normalX;
        first.velocityY += relativeNormalVelocity * normalY;
        second.velocityX -= relativeNormalVelocity * normalX;
        second.velocityY -= relativeNormalVelocity * normalY;
    }

    private static void constrainToHexagon(Ball ball) {
        double centerLimit = HEX_APOTHEM - BALL_RADIUS;
        for (int side = 0; side < 6; side++) {
            double angle = side * Math.PI / 3.0;
            double normalX = Math.cos(angle);
            double normalY = Math.sin(angle);
            double penetration = ball.x * normalX + ball.y * normalY - centerLimit;
            if (penetration <= 0.0) {
                continue;
            }
            double energyBeforeCorrection = ball.mechanicalEnergy();
            ball.x -= penetration * normalX;
            ball.y -= penetration * normalY;
            double outwardVelocity = ball.velocityX * normalX + ball.velocityY * normalY;
            if (outwardVelocity > 0.0) {
                ball.velocityX -= 2.0 * outwardVelocity * normalX;
                ball.velocityY -= 2.0 * outwardVelocity * normalY;
            }
            restoreMechanicalEnergy(ball, energyBeforeCorrection);
        }
    }

    private static void restoreMechanicalEnergy(Ball ball, double targetEnergy) {
        double targetKinetic = Math.max(0.0, targetEnergy - GRAVITY * ball.y);
        double currentKinetic = ball.kineticEnergy();
        if (currentKinetic <= MIN_DISTANCE_SQUARED) return;
        double scale = Math.sqrt(targetKinetic / currentKinetic);
        ball.velocityX *= scale;
        ball.velocityY *= scale;
    }

    static final class Ball {
        double x;
        double y;
        double velocityX;
        double velocityY;

        Ball(double x, double y, double velocityX, double velocityY) {
            this.x = x;
            this.y = y;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
        }

        double kineticEnergy() {
            return 0.5 * (velocityX * velocityX + velocityY * velocityY);
        }

        double mechanicalEnergy() {
            return kineticEnergy() + GRAVITY * y;
        }
    }
}
