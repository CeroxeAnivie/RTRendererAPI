#pragma once

#include <NRD.h>

namespace rtrenderer::nvidia {

/**
 * Central production tuning for the renderer's deterministic two-sample radiance signal.
 *
 * NRD's broad defaults target much noisier one-ray inputs. Applying their 30/50-pixel prepass
 * and 30-pixel spatial radius to this signal destroys material edges before DLSS sees them. This
 * policy retains temporal stabilization and a small specular tracking prepass while bounding all
 * spatial reuse to the scale actually justified by the input.
 */
class NrdReblurPolicy final {
public:
    static nrd::ReblurSettings production() {
        nrd::ReblurSettings settings = {};
        settings.maxAccumulatedFrameNum = 12;
        settings.maxFastAccumulatedFrameNum = 3;
        settings.maxStabilizedFrameNum = 8;
        settings.historyFixFrameNum = 2;
        settings.diffusePrepassBlurRadius = 0.0f;
        settings.specularPrepassBlurRadius = 4.0f;
        settings.minBlurRadius = 0.5f;
        settings.maxBlurRadius = 8.0f;
        settings.usePrepassOnlyForSpecularMotionEstimation = true;
        settings.enableAntiFirefly = true;
        return settings;
    }

    NrdReblurPolicy() = delete;
};

}
