#include "streamline_frame_constants_publisher.hpp"

#include <stdexcept>
#include <string>

#include "streamline_frame_support.hpp"

namespace rtrenderer::nvidia {

void StreamlineFrameConstantsPublisher::bind(const StreamlineApiBindings& bindings) noexcept {
    clear();
    getNewFrameToken_ = bindings.getNewFrameToken;
    setConstants_ = bindings.setConstants;
}

void StreamlineFrameConstantsPublisher::clear() noexcept {
    publications_.clear();
    getNewFrameToken_ = nullptr;
    setConstants_ = nullptr;
}

sl::FrameToken& StreamlineFrameConstantsPublisher::publish(
        const sl::ViewportHandle& viewport,
        const StreamlineFrame& frame
) {
    if (getNewFrameToken_ == nullptr || setConstants_ == nullptr) {
        throw std::runtime_error("Streamline frame-constants publisher is not device-ready");
    }
    const std::uint32_t viewportId = static_cast<std::uint32_t>(viewport);
    const Snapshot next = Snapshot::from(frame);
    auto existing = publications_.find(viewportId);
    if (existing != publications_.end() && existing->second.published) {
        Publication& previous = existing->second;
        if (frame.frameIndex < previous.frameIndex) {
            throw std::logic_error(
                    "Streamline frame constants moved backwards for viewport "
                            + std::to_string(viewportId) + ": previous="
                            + std::to_string(previous.frameIndex) + ", current="
                            + std::to_string(frame.frameIndex)
            );
        }
        if (frame.frameIndex == previous.frameIndex) {
            if (!(next == previous.snapshot)) {
                throw std::logic_error(
                        "Streamline frame constants changed after publication for viewport "
                                + std::to_string(viewportId) + ", frame="
                                + std::to_string(frame.frameIndex)
                );
            }
            if (previous.token == nullptr
                    || static_cast<std::uint32_t>(*previous.token) != frame.frameIndex) {
                throw std::logic_error("cached Streamline frame token no longer matches its frame index");
            }
            return *previous.token;
        }
    }

    // Allocate a cache node before publishing vendor-visible state. Once slSetConstants succeeds,
    // every remaining assignment is non-throwing, so a retry can never accidentally publish twice.
    const bool inserted = existing == publications_.end();
    if (inserted) existing = publications_.try_emplace(viewportId).first;
    sl::FrameToken* token = nullptr;
    try {
        streamline::requireOk(
                getNewFrameToken_(token, &frame.frameIndex),
                "slGetNewFrameToken(common constants)"
        );
        if (token == nullptr) {
            throw std::runtime_error("slGetNewFrameToken returned a null common-constants token");
        }
        if (static_cast<std::uint32_t>(*token) != frame.frameIndex) {
            throw std::runtime_error("Streamline returned a token for a different frame index");
        }
        streamline::setConstantsForFrame(setConstants_, viewport, *token, frame);
    } catch (...) {
        if (inserted) publications_.erase(existing);
        throw;
    }

    Publication& committed = existing->second;
    committed.frameIndex = frame.frameIndex;
    committed.snapshot = next;
    committed.token = token;
    committed.published = true;
    return *token;
}

StreamlineFrameConstantsPublisher::Snapshot
StreamlineFrameConstantsPublisher::Snapshot::from(const StreamlineFrame& frame) noexcept {
    return {
            frame.viewToClip,
            frame.clipToView,
            frame.clipToPrevious,
            frame.previousToClip,
            frame.position,
            frame.up,
            frame.right,
            frame.forward,
            frame.jitterX,
            frame.jitterY,
            frame.motionScaleX,
            frame.motionScaleY,
            frame.nearPlane,
            frame.farPlane,
            frame.fovRadians,
            frame.aspectRatio,
            frame.reset
    };
}

}
