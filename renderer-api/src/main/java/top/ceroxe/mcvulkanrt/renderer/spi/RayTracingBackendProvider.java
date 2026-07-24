package top.ceroxe.mcvulkanrt.renderer.spi;

import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRenderer;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;

import java.util.Objects;

/** Service-provider boundary implemented by a renderer backend module. */
public interface RayTracingBackendProvider {
    int API_MAJOR = 1;

    Descriptor descriptor();

    /** Read-only configuration-aware probe; it must not retain native resources. */
    ProbeResult probe(RayTracingRendererConfig configuration);

    RayTracingRenderer open(RayTracingRendererConfig configuration);

    record Descriptor(String id, int priority, int apiMajor, int apiMinor) {
        public Descriptor {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("backend provider id must not be blank");
            }
            if (apiMajor <= 0 || apiMinor < 0) {
                throw new IllegalArgumentException("backend API version must be positive");
            }
        }
    }

    record ProbeResult(Compatibility compatibility, String reason) {
        public ProbeResult {
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            reason = Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("backend probe reason must not be blank");
            }
        }

        public static ProbeResult compatible(String reason) {
            return new ProbeResult(Compatibility.COMPATIBLE, reason);
        }

        public static ProbeResult unsupported(String reason) {
            return new ProbeResult(Compatibility.UNSUPPORTED, reason);
        }

        public static ProbeResult incompatible(String reason) {
            return new ProbeResult(Compatibility.INCOMPATIBLE, reason);
        }
    }

    enum Compatibility {
        COMPATIBLE,
        UNSUPPORTED,
        INCOMPATIBLE
    }
}
