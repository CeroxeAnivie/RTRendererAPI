package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;

/** Verifies the Java/JNI cadence request contract before it reaches Streamline state query. */
public final class NvidiaStreamlineAdaptiveFrameGenerationSelfTest {
    private NvidiaStreamlineAdaptiveFrameGenerationSelfTest() {
    }

    public static void main(String[] arguments) {
        require(request(FrameGenerationOptions.recommended()) == 1,
                "ordinary production policy must request FG 2x without automatically selecting MFG");
        require(request(explicit(FrameGenerationOptions.Mode.FRAME_GENERATION,
                FrameGenerationOptions.Multiplier.TWO_X)) == 1, "explicit 2x request changed");
        require(request(explicit(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION,
                FrameGenerationOptions.Multiplier.THREE_X)) == 2, "explicit 3x request changed");
        require(request(explicit(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION,
                FrameGenerationOptions.Multiplier.FOUR_X)) == 3, "explicit 4x request changed");
        require(request(explicit(FrameGenerationOptions.Mode.ADAPTIVE,
                FrameGenerationOptions.Multiplier.THREE_X)) == -2,
                "expert adaptive 3x request changed");
        require(request(explicit(FrameGenerationOptions.Mode.ADAPTIVE,
                FrameGenerationOptions.Multiplier.FOUR_X)) == -3,
                "expert adaptive 4x request changed");
        require(request(FrameGenerationOptions.disabled()) == 0, "disabled generation must remain zero");
        System.out.println("NvidiaStreamlineAdaptiveFrameGenerationSelfTest passed");
    }

    private static FrameGenerationOptions explicit(
            FrameGenerationOptions.Mode mode,
            FrameGenerationOptions.Multiplier multiplier
    ) {
        return FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(mode)
                .multiplier(multiplier)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
    }

    private static int request(FrameGenerationOptions options) {
        return new NvidiaStreamlineSwapchainInterceptor(options).requestedGeneratedFrames();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
