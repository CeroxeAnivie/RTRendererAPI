package demo;

/** Single source for build identity shown by the packaged Demo. */
final class DemoBuildInfo {
    private DemoBuildInfo() {
    }

    static String version() {
        String version = DemoBuildInfo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
}
