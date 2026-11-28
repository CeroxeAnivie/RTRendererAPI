#pragma once

#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <string_view>

namespace rtr::upstream::startup {

enum class State { NotStarted, Running, Ready, Failed };

struct Snapshot {
    std::string stage = "not-started";
    std::string activePso;
    uint64_t elapsedMs = 0;
    uint64_t cacheHits = 0;
    uint64_t cacheMisses = 0;
    std::string terminalState = "not-started";
};

namespace detail {
struct Tracker {
    std::mutex mutex;
    std::chrono::steady_clock::time_point started{};
    std::string stage = "not-started";
    std::string activePso;
    uint64_t cacheHits = 0;
    uint64_t cacheMisses = 0;
    State state = State::NotStarted;
};

inline Tracker& tracker() {
    static Tracker value;
    return value;
}

inline const char* stateName(State state) noexcept {
    switch (state) {
    case State::NotStarted: return "not-started";
    case State::Running: return "running";
    case State::Ready: return "ready";
    case State::Failed: return "failed";
    }
    return "failed";
}
} // namespace detail

inline void beginSession() {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    value.started = std::chrono::steady_clock::now();
    value.stage = "starting";
    value.activePso.clear();
    value.cacheHits = 0;
    value.cacheMisses = 0;
    value.state = State::Running;
}

inline void setStage(std::string_view stage) {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    value.stage.assign(stage);
}

inline void beginPso(std::string_view name) {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    value.stage = "creating-compute-pso";
    value.activePso.assign(name);
}

inline void recordPsoCacheLookup(bool hit) {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    if (hit) ++value.cacheHits;
    else ++value.cacheMisses;
}

inline void finishPso() {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    value.activePso.clear();
}

inline void finishSession(State state) {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    value.state = state;
    if (state == State::Ready) {
        value.stage = "session-ready";
        value.activePso.clear();
    }
}

inline Snapshot snapshot() {
    auto& value = detail::tracker();
    std::lock_guard lock(value.mutex);
    uint64_t elapsedMs = 0;
    if (value.started.time_since_epoch().count() != 0) {
        elapsedMs = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - value.started).count());
    }
    return {value.stage, value.activePso, elapsedMs, value.cacheHits, value.cacheMisses,
        detail::stateName(value.state)};
}

} // namespace rtr::upstream::startup
