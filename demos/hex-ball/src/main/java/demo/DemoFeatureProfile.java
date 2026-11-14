package demo;

/** Selects one auditable, process-lifetime feature combination for the Demo. */
enum DemoFeatureProfile {
    RECOMMENDED("recommended"),
    RECOMMENDED_NO_NRD("recommended-no-nrd"),
    GENERATION_ONLY("generation-only"),
    GENERATION_AND_SR("generation-and-sr"),
    GENERATION_SR_NRD("generation-sr-nrd"),
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
                PROPERTY + " must be recommended, recommended-no-nrd, generation-only, generation-and-sr, generation-sr-nrd, or all-except-mfg: " + configured
        );
    }
}
