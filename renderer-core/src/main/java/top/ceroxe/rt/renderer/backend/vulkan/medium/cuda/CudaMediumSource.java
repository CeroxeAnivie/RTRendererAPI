package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the pinned upstream sources from the distributed core artifact. */
public final class CudaMediumSource {
    public static final String RESOURCE_ROOT = "/assets/rtrenderer/medium/luxcore/";
    private CudaMediumSource() { }
    public static String source() {
        return resource("cudadevice_oclemul_types.cl") + "\n"
                + resource("cudadevice_math.cl") + "\n"
                + resource("cudadevice_oclemul_funcs.cl") + "\n"
                + resource("segment_helpers.cl") + "\n"
                + resource("upstream-segment-function.cl") + "\n"
                + resource("segment_batch.cu");
    }
    public static String resource(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains(".."))
            throw new IllegalArgumentException("resource must be a local source name");
        try (InputStream stream = CudaMediumSource.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream == null) throw new IllegalStateException("Missing packaged CUDA medium resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read packaged CUDA medium resource: " + name, e);
        }
    }
}
