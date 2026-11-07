#pragma once

// Implemented by the narrowly authorized Donut network-policy variant.
namespace donut::app { bool RtrSetStreamlineSessionEnabled(bool enabled); }

namespace rtr::upstream {
class StreamlineSessionPolicy final {
public:
    explicit StreamlineSessionPolicy(bool enabled)
        : m_previous(donut::app::RtrSetStreamlineSessionEnabled(enabled)) { }
    ~StreamlineSessionPolicy() { donut::app::RtrSetStreamlineSessionEnabled(m_previous); }
    StreamlineSessionPolicy(const StreamlineSessionPolicy&) = delete;
    StreamlineSessionPolicy& operator=(const StreamlineSessionPolicy&) = delete;
private:
    bool m_previous;
};
}
