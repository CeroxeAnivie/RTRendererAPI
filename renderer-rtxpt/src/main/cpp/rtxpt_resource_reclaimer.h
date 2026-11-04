#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include "rtxpt_diagnostics.h"

// Only GPU-completed objects enter this queue. Release outside the queue lock;
// neither admission nor a snapshot executes a driver destruction chain.
class RtrResourceReclaimer {
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<std::shared_ptr<void>> objects;
    bool stopping=false;
    std::thread worker;
    void Run() {
        for(;;) {
            std::shared_ptr<void> object;
            {std::unique_lock lock(mutex);changed.wait(lock,[&]{return stopping||!objects.empty();});
                if(objects.empty())return;
                object=std::move(objects.front());objects.pop_front();}
            const bool collecting=RtrDiagnostics::level.load(std::memory_order_relaxed)!=0;
            auto begin=collecting?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};object.reset();
            if(collecting){releaseNanos.fetch_add(uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count()));++released;}
            --pending;
        }
    }
public:
    static constexpr size_t Capacity=32;
    std::atomic<uint64_t> pending{0},released{0},releaseNanos{0};
    RtrResourceReclaimer():worker([this]{Run();}){}
    ~RtrResourceReclaimer(){ {std::lock_guard lock(mutex);stopping=true;}changed.notify_one();worker.join(); }
    bool Available() const {return pending.load()<Capacity;}
    template<class T> bool Enqueue(T& value) {
        std::lock_guard lock(mutex);if(stopping||pending.load()>=Capacity)return false;
        objects.push_back(std::make_shared<T>(std::move(value)));++pending;changed.notify_one();return true;
    }
};
