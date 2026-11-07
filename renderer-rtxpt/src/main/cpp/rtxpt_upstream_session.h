#pragma once
#include "rtxpt_upstream_host.h"
#include "rtxpt_shutdown.h"
#include <optional>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <functional>
#include <future>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace rtr::upstream {

// All upstream objects stay on one native owner thread. The upstream message
// loop remains responsible for BeginFrame/Animate/Render/Present and shutdown.
// Requests are consumed at its public beforeFrame callback; no private stepping
// function, fabricated DeviceManager cast, or source modification is needed.
class Session final {
public:
    using Action = std::function<void(Application&)>;
    using Completed = std::function<void(Application&)>;
    class ClosedError final : public std::runtime_error {
    public:
        ClosedError() : std::runtime_error("The original RTXPT renderer is closed") { }
    };
    struct Statistics {
        uint64_t requests = 0, ownerIdleNanoseconds = 0, callerWaitNanoseconds = 0;
        uint64_t updateNanoseconds = 0, renderedNanoseconds = 0;
        bool vsyncEnabled = false;
    };

    explicit Session(std::vector<std::string> options, bool managedScene = false) {
        if (options.empty()) options.emplace_back("rtrenderer-upstream");
        m_thread = std::thread([this, options = std::move(options), managedScene] { Run(options, managedScene); });
        std::unique_lock lock(m_mutex);
        m_condition.wait(lock, [this] { return m_initialized || m_stopped; });
        if (m_failure) {
            lock.unlock();
            m_thread.join();
            std::rethrow_exception(m_failure);
        }
    }
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    ~Session() { Close(); }

    void Enqueue(Action action) {
        std::lock_guard lock(m_mutex);
        Check();
        if (m_pending.size() >= 4096) throw std::runtime_error("External request queue is full");
        m_pending.push_back(std::move(action));
    }

    // Queries and lease retirement must run on the same native owner without
    // manufacturing an extra render request or reusing a previous frame.
    void Invoke(Action action) {
        auto completion = std::make_shared<std::promise<void>>();
        auto result = completion->get_future();
        {
            std::lock_guard lock(m_mutex);
            Check();
            if (m_calls.size() >= 4096) throw std::runtime_error("External call queue is full");
            m_calls.push_back({std::move(action), completion});
            m_condition.notify_all();
        }
        // The queued call owns completion until it executes or is cancelled.
        completion.reset();
        result.get();
    }

    // The Java submit lane has one owner. This call waits for the exact batch,
    // including asynchronous original scene/shader loading, rather than returning
    // a cached previous frame under the new request's identity.
    bool Frame(Completed completed = {}) {
        const auto waitBegin = std::chrono::steady_clock::now();
        std::unique_lock lock(m_mutex);
        Check();
        if (m_requested != m_completed) throw std::logic_error("Concurrent upstream frame submission");
        m_requestActions.swap(m_pending);
        m_afterRender = std::move(completed);
        const auto request = ++m_requested;
        m_condition.notify_all();
        m_condition.wait(lock, [this, request] { return m_completed >= request || m_stopped; });
        m_statistics.callerWaitNanoseconds += Nanoseconds(waitBegin);
        if (m_failure) std::rethrow_exception(m_failure);
        return m_completed >= request;
    }

    Statistics Snapshot() { std::lock_guard lock(m_mutex); return m_statistics; }

    bool StoppedNormally() {
        std::lock_guard lock(m_mutex);
        return m_stopped && !m_failure;
    }
    bool Stopped() {
        std::lock_guard lock(m_mutex);
        return m_stopped;
    }

    void Close() noexcept {
        {
            std::lock_guard lock(m_mutex);
            m_stop = true;
            CancelCalls(m_calls, m_failure ? m_failure : m_closedFailure);
            m_condition.notify_all();
        }
        if (m_thread.joinable()) m_thread.join();
    }

private:
    struct Call {
        Action action;
        std::shared_ptr<std::promise<void>> completion;
    };
    static void CancelCalls(std::vector<Call>& calls, const std::exception_ptr& failure) {
        for (auto& call : calls) {
            auto completion = std::move(call.completion);
            if (completion) completion->set_exception(failure);
        }
        calls.clear();
    }
    static uint64_t Nanoseconds(std::chrono::steady_clock::time_point begin) {
        return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - begin).count());
    }
    void Check() const {
        if (m_failure) std::rethrow_exception(m_failure);
        if (m_stop || m_stopped) std::rethrow_exception(m_closedFailure);
    }

    void Run(const std::vector<std::string>& options, bool managedScene) noexcept {
        try {
            // The original sample owns process-global UI and logging state. It
            // does not offer isolated concurrent application instances.
            std::unique_lock lifecycleLock(s_lifecycleMutex, std::try_to_lock);
            if (!lifecycleLock.owns_lock())
                throw std::runtime_error("The original RTXPT sample permits one live application per process");
            struct LogCallbackGuard {
                donut::log::Callback previous = donut::log::GetCallback();
                ~LogCallbackGuard() { donut::log::SetCallback(previous); }
            } logCallbackGuard;
            // Public Donut logging configuration keeps the embedding API free
            // of blocking native message boxes and preserves real error text.
            donut::log::ConsoleApplicationMode();
            HelpersSetNonInteractive();
            InstallPrecompiledShaders(donut::app::GetDirectoryWithExecutable());
            std::vector<const char*> argv;
            for (const auto& option : options) argv.push_back(option.c_str());
            // The guard outlives Application, whose original destructor calls
            // DeviceManager::Shutdown and drains Streamline destruction callbacks.
            std::optional<ShutdownModuleReference> retainedModules;
            Application application(options, managedScene);
            // The pinned sample reads the CRT arguments instead of Init's parameters.
            // Scope the embedding arguments to its synchronous initialization and restore
            // the host immediately. The lifecycle mutex above excludes another sample.
            auto initialize = [&] {
                struct CrtArguments {
                    int previousCount = __argc;
                    char** previousValues = __argv;
                    std::vector<std::string> values;
                    std::vector<char*> pointers;
                    explicit CrtArguments(const std::vector<std::string>& requested) : values(requested) {
                        for (auto& value : values) pointers.push_back(value.data());
                        pointers.push_back(nullptr);
                        __argc = static_cast<int>(values.size());
                        __argv = pointers.data();
                    }
                    ~CrtArguments() { __argc = previousCount; __argv = previousValues; }
                } arguments(options);
                return application.Init(static_cast<int>(argv.size()), argv.data());
            };
            const auto status = initialize();
            if (status != SampleBaseApp::InitReturnCodes::Success)
                throw std::runtime_error("Original RTXPT initialization failed: "
                    + std::to_string(static_cast<int>(status)));
            // Official DeviceManager applies this requested public setting before
            // the next render. There is no extra frame-rate timer in the adapter.
            retainedModules.emplace();
            application.DeviceManager().SetVsyncEnabled(false);
            bool active = false;
            bool actionsApplied = false;
            std::vector<Action> actions;
            std::chrono::steady_clock::time_point renderBegin;
            application.ObserveFrames([&](auto& manager, auto) {
                {
                    std::lock_guard lock(m_mutex);
                    if (m_stop) {
                        glfwSetWindowShouldClose(manager.GetWindow(), GLFW_TRUE);
                        return;
                    }
                }
                while (!active) {
                    std::unique_lock lock(m_mutex);
                    if (m_stop || glfwWindowShouldClose(manager.GetWindow())) {
                        glfwSetWindowShouldClose(manager.GetWindow(), GLFW_TRUE);
                        return;
                    }
                    if (!m_calls.empty()) {
                        m_activeCalls.swap(m_calls);
                        lock.unlock();
                        for (auto& call : m_activeCalls) {
                            std::exception_ptr cancellation;
                            {
                                std::lock_guard callLock(m_mutex);
                                if (m_stop || glfwWindowShouldClose(manager.GetWindow()))
                                    cancellation = m_failure ? m_failure : m_closedFailure;
                            }
                            auto completion = std::move(call.completion);
                            if (cancellation) completion->set_exception(cancellation);
                            else {
                                try { call.action(application); completion->set_value(); }
                                catch (...) { completion->set_exception(std::current_exception()); }
                            }
                        }
                        m_activeCalls.clear();
                        continue;
                    }
                    if (m_requested > m_completed) {
                        actions.swap(m_requestActions);
                        active = true;
                        actionsApplied = false;
                        renderBegin = std::chrono::steady_clock::now();
                        break;
                    }
                    // Only the owner thread polls GLFW. Waiting for a host request
                    // must still let the ordinary window process its close event.
                    const auto idleBegin = std::chrono::steady_clock::now();
                    m_condition.wait_for(lock, std::chrono::milliseconds(8));
                    m_statistics.ownerIdleNanoseconds += Nanoseconds(idleBegin);
                    lock.unlock();
                    glfwPollEvents();
                }
                manager.RenderNextFrameWhileUnfocused();
                // SceneLoaded sets the sample's async flag, which its next
                // Render clears. Inputs must not wait on that render: a first
                // managed resource packet is what makes a non-empty frame.
                // Only completion below waits for all original async work.
                if (actionsApplied || !application.Renderer().IsSceneLoaded()) return;
                const auto updateBegin = std::chrono::steady_clock::now();
                for (auto& action : actions) action(application);
                actions.clear();
                actionsApplied = true;
                {
                    std::lock_guard lock(m_mutex);
                    m_statistics.updateNanoseconds += Nanoseconds(updateBegin);
                }
            }, [&](auto&, auto) {
                if (!active || !actionsApplied || !application.Renderer().IsSceneLoaded() ||
                    application.Renderer().HasAsyncLoadingInProgress()) return;
                if (m_afterRender) m_afterRender(application);
                std::lock_guard lock(m_mutex);
                ++m_statistics.requests;
                m_statistics.renderedNanoseconds += Nanoseconds(renderBegin);
                m_statistics.vsyncEnabled = application.DeviceManager().IsVsyncEnabled();
                m_afterRender = {};
                m_completed = m_requested;
                active = false;
                m_condition.notify_all();
            });
            {
                std::lock_guard lock(m_mutex);
                m_initialized = true;
                m_condition.notify_all();
            }
            try {
                application.RunMainLoop();
            } catch (...) {
                application.End();
                throw;
            }
            application.End();
        } catch (...) {
            std::lock_guard lock(m_mutex);
            m_failure = std::current_exception();
        }
        std::lock_guard lock(m_mutex);
        m_stopped = true;
        const auto failure = m_failure ? m_failure : m_closedFailure;
        CancelCalls(m_activeCalls, failure);
        CancelCalls(m_calls, failure);
        m_condition.notify_all();
    }

    std::mutex m_mutex;
    inline static std::mutex s_lifecycleMutex;
    std::condition_variable m_condition;
    std::thread m_thread;
    std::vector<Action> m_pending, m_requestActions;
    std::vector<Call> m_calls, m_activeCalls;
    Completed m_afterRender;
    Statistics m_statistics;
    std::exception_ptr m_failure;
    const std::exception_ptr m_closedFailure = std::make_exception_ptr(ClosedError());
    uint64_t m_requested = 0, m_completed = 0;
    bool m_initialized = false, m_stop = false, m_stopped = false;
};

} // namespace rtr::upstream
