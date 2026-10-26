#include "streamline_frame_support.hpp"
#include "streamline_frame_constants_publisher.hpp"
#include "streamline_frame_generation_executor.hpp"

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

class RollingToken final : public sl::FrameToken {
public:
    uint32_t index = 0;
    operator uint32_t() const override { return index; }
};
std::array<RollingToken, sl::MAX_FRAMES_IN_FLIGHT> rollingTokens;
unsigned tokenCursor = 0;
unsigned tokenRequests = 0;
unsigned clearedFrames = 0;

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

sl::Result nextToken(sl::FrameToken*& result, const uint32_t* index) {
    if (!index) return sl::Result::eErrorMissingInputParameter;
    ++tokenRequests;
    if (rollingTokens[tokenCursor].index != *index) {
        tokenCursor = (tokenCursor + 1) % rollingTokens.size();
        rollingTokens[tokenCursor].index = *index;
    }
    result = &rollingTokens[tokenCursor];
    return sl::Result::eOk;
}

sl::Result acceptConstants(const sl::Constants&, const sl::FrameToken&, const sl::ViewportHandle&) {
    return sl::Result::eOk;
}

sl::Result acceptTags(const sl::FrameToken& token, const sl::ViewportHandle&,
                     const sl::ResourceTag* tags, uint32_t count, sl::CommandBuffer*) {
    require(count == 3, "unexpected generation input count");
    if (tags[0].resource == nullptr) {
        require(static_cast<uint32_t>(token) >= 7, "retired wrong frame token");
        ++clearedFrames;
    }
    return sl::Result::eOk;
}

void verifyHistoricalTagRetirement() {
    using namespace rtrenderer::nvidia;
    StreamlineApiBindings bindings{};
    bindings.getNewFrameToken = nextToken;
    bindings.setConstants = acceptConstants;
    bindings.setTagForFrame = acceptTags;
    StreamlineFrameConstantsPublisher constants;
    constants.bind(bindings);
    StreamlineFrameGenerationExecutor executor;
    executor.bind(bindings, constants, true, false);
    StreamlineFrame frame{};
    frame.inputColor = {1, 2, 3, VK_FORMAT_R8G8B8A8_UNORM, 1280, 720, VK_IMAGE_USAGE_SAMPLED_BIT};
    frame.depth = {4, 5, 6, VK_FORMAT_R32_SFLOAT, 1280, 720, VK_IMAGE_USAGE_SAMPLED_BIT};
    frame.motion = {7, 8, 9, VK_FORMAT_R32G32_SFLOAT, 1280, 720, VK_IMAGE_USAGE_SAMPLED_BIT};
    frame.frameIndex = 7;
    executor.record(1, frame);
    frame.frameIndex = 8;
    executor.record(1, frame);
    auto* current = &rollingTokens[tokenCursor];
    require(tokenRequests == 2, "unexpected initial token allocation");
    executor.retireFrame(7);
    require(tokenRequests == 2 && clearedFrames == 1 && current->index == 8,
            "historical retirement must reuse its original token without advancing the SDK ring");
    executor.retireFrame(8);
    require(tokenRequests == 2 && clearedFrames == 2, "retirement allocated a token");

    frame.frameIndex = 9;
    executor.record(1, frame);
    auto* original = &rollingTokens[tokenCursor];
    for (uint32_t index = 10; index <= 15; ++index) {
        sl::FrameToken* ignored = nullptr;
        nextToken(ignored, &index);
    }
    bool rejected = false;
    try { executor.retireFrame(9); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected && clearedFrames == 2, "recycled token must fail before clearing another frame");
    original->index = 9;
    executor.retireFrame(9);
    executor.close();
    constants.clear();
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
        verifyHistoricalTagRetirement();
        std::cout << "StreamlineFrameSupportTest passed (constants and historical token retirement)\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
