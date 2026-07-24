package top.ceroxe.mcvulkanrt.renderer.api;

/** Frame-local lighting and camera-medium facts independent of any source engine. */
public record EnvironmentState(
        float skyRed,
        float skyGreen,
        float skyBlue,
        float ambientIntensity,
        float sunDirectionX,
        float sunDirectionY,
        float sunDirectionZ,
        float sunRed,
        float sunGreen,
        float sunBlue,
        float sunIntensity,
        Medium cameraMedium
) {
    public EnvironmentState {
        requireNonNegativeFinite(skyRed, "skyRed");
        requireNonNegativeFinite(skyGreen, "skyGreen");
        requireNonNegativeFinite(skyBlue, "skyBlue");
        requireNonNegativeFinite(ambientIntensity, "ambientIntensity");
        requireDirection(sunDirectionX, sunDirectionY, sunDirectionZ);
        requireNonNegativeFinite(sunRed, "sunRed");
        requireNonNegativeFinite(sunGreen, "sunGreen");
        requireNonNegativeFinite(sunBlue, "sunBlue");
        requireNonNegativeFinite(sunIntensity, "sunIntensity");
        cameraMedium = java.util.Objects.requireNonNull(cameraMedium, "cameraMedium");
    }

    public static EnvironmentState neutral() {
        return new EnvironmentState(
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 1.0F, 1.0F, 0.0F,
                Medium.vacuum()
        );
    }

    public record Medium(
            float extinctionRed,
            float extinctionGreen,
            float extinctionBlue,
            float scatteringRed,
            float scatteringGreen,
            float scatteringBlue,
            float density,
            float indexOfRefraction
    ) {
        public Medium {
            requireNonNegativeFinite(extinctionRed, "extinctionRed");
            requireNonNegativeFinite(extinctionGreen, "extinctionGreen");
            requireNonNegativeFinite(extinctionBlue, "extinctionBlue");
            requireNonNegativeFinite(scatteringRed, "scatteringRed");
            requireNonNegativeFinite(scatteringGreen, "scatteringGreen");
            requireNonNegativeFinite(scatteringBlue, "scatteringBlue");
            requireNonNegativeFinite(density, "density");
            if (!Float.isFinite(indexOfRefraction) || indexOfRefraction < 1.0F) {
                throw new IllegalArgumentException("indexOfRefraction must be finite and at least 1");
            }
        }

        public static Medium vacuum() {
            return new Medium(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
        }
    }

    private static void requireDirection(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("sun direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 2.0E-3F) {
            throw new IllegalArgumentException("sun direction must be normalized");
        }
    }

    private static void requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
