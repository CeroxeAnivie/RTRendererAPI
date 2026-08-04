package demo;

/** Selects one auditable, process-lifetime feature combination for the Demo. */
enum DemoFeatureProfile {
    GENERATION_ONLY("generation-only"),
    ALL_EXCEPT_MFG("all-except-mfg");

    static final String PROPERTY = "demo.feature-profile";

    private final String propertyValue;

    DemoFeatureProfile(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    static DemoFeatureProfile configured() {
        String configured = System.getProperty(PROPERTY, GENERATION_ONLY.propertyValue).trim();
        for (DemoFeatureProfile profile : values()) {
            if (profile.propertyValue.equals(configured)) return profile;
        }
        throw new IllegalArgumentException(
                PROPERTY + " must be generation-only or all-except-mfg: " + configured
        );
    }
}
