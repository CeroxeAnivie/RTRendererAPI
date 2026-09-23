#include "streamline_frame_support.hpp"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {
class Token final : public sl::FrameToken {
public:
    operator uint32_t() const override { return 7; }
};

sl::Constants captured;
sl::Result response = sl::Result::eOk;
unsigned calls = 0;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

sl::Result capture(const sl::Constants& constants, const sl::FrameToken& token,
                   const sl::ViewportHandle& viewport) {
    require(static_cast<uint32_t>(token) == 7, "frame token changed");
    require(static_cast<uint32_t>(viewport) == 0, "viewport changed");
    captured = constants;
    ++calls;
    return response;
}
}

int main() {
    using namespace rtrenderer::nvidia;
    try {
        Token token;
        sl::ViewportHandle viewport(0);
        StreamlineFrame frame{};
        frame.jitterX = 0.25F;
        frame.jitterY = -0.5F;
        frame.motionScaleX = 1.0F / 1280;
        frame.motionScaleY = 1.0F / 720;
        frame.nearPlane = 0.1F;
        frame.farPlane = 1000;
        frame.fovRadians = 1;
        frame.aspectRatio = 16.0F / 9;
        for (bool reset : {true, false}) {
            frame.reset = reset;
            streamline::setConstantsForFrame(capture, viewport, token, frame);
            require(captured.cameraPinholeOffset.x == 0.0F
                    && captured.cameraPinholeOffset.y == 0.0F,
                    "centered projection must explicitly initialize pinhole offset");
            require(captured.reset == (reset ? sl::Boolean::eTrue : sl::Boolean::eFalse),
                    "history reset changed");
            require(captured.jitterOffset.x == -0.25F && captured.jitterOffset.y == 0.5F,
                    "inverse jitter changed");
            require(captured.mvecScale.x == frame.motionScaleX
                    && captured.mvecScale.y == frame.motionScaleY, "motion scale changed");
        }
        bool rejected = false;
        try { streamline::setConstantsForFrame(nullptr, viewport, token, frame); }
        catch (const std::runtime_error&) { rejected = true; }
        require(rejected && calls == 2, "missing SDK function must fail before invocation");
        response = sl::Result::eErrorMissingOrInvalidAPI;
        rejected = false;
        try { streamline::setConstantsForFrame(capture, viewport, token, frame); }
        catch (const std::runtime_error& error) {
            rejected = std::string(error.what()).find("slSetConstants") != std::string::npos;
        }
        require(rejected && calls == 3, "SDK failure must propagate with operation context");
        std::cout << "StreamlineFrameSupportTest passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
