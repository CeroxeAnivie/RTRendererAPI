package demo;

/** Selects one auditable, process-lifetime feature combination for the Demo. */
enum DemoFeatureProfile {
    RECOMMENDED("recommended"),
    GENERATION_ONLY("generation-only"),
    ALL_EXCEPT_MFG("all-except-mfg");

    static final String PROPERTY = "demo.feature-profile";

    private final String propertyValue;

    DemoFeatureProfile(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    static DemoFeatureProfile configured() {
        String configured = System.getProperty(PROPERTY, RECOMMENDED.propertyValue).trim();
        for (DemoFeatureProfile profile : values()) {
            if (profile.propertyValue.equals(configured)) return profile;
        }
        throw new IllegalArgumentException(
                PROPERTY + " must be recommended, generation-only, or all-except-mfg: " + configured
        );
    }
}
