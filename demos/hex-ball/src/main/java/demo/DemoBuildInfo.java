package demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Single source for build identity shown by the packaged Demo. */
final class DemoBuildInfo {
    private static final String BUILD_METADATA = "/META-INF/rtrenderer-api-demo.properties";
    private static final String API_VERSION_KEY = "apiVersion";

    private DemoBuildInfo() {
    }

    static String version() {
        String resourceVersion = resourceVersion();
        if (resourceVersion != null) return resourceVersion;

        // Fat and thin JARs retain this established source. It remains a compatibility fallback
        // for third-party repackagers that omit the generated resource.
        String manifestVersion = DemoBuildInfo.class.getPackage().getImplementationVersion();
        return manifestVersion == null || manifestVersion.isBlank() ? "unversioned" : manifestVersion;
    }

    private static String resourceVersion() {
        try (InputStream stream = DemoBuildInfo.class.getResourceAsStream(BUILD_METADATA)) {
            if (stream == null) return null;
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty(API_VERSION_KEY);
            return version == null || version.isBlank() ? null : version.trim();
        } catch (IOException ignored) {
            // The version label is diagnostic only. Rendering must remain available if a custom
            // class loader exposes an unreadable optional metadata resource.
            return null;
        }
    }
}
