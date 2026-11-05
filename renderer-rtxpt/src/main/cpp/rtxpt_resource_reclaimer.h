#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <array>
#include <cstddef>
#include <new>
#include <type_traits>
#include <memory>
#include <mutex>
#include <thread>
#include "rtxpt_diagnostics.h"

// Fixed-slot transfer: the renderer owner never allocates, blocks on the worker
// or destroys a rejected payload. Slots include the active driver destruction.
class RtrResourceReclaimer {
public:
    static constexpr size_t Capacity=32,StorageBytes=512;
    std::atomic<uint64_t> pending{0},released{0},releaseNanos{0};
    std::atomic<uint64_t> pendingBytes{0},peakPending{0},peakBytes{0},enqueueRejected{0};
private:
    struct Slot {
        alignas(std::max_align_t) std::array<std::byte,StorageBytes> storage{};
        std::atomic<bool> ready{false};
        void(*destroy)(void*) noexcept=nullptr;
        uint64_t bytes=0,sequence=0,revision=0;
        std::atomic<uint64_t> admittedTick{0};
    };
    std::array<Slot,Capacity> slots;
    size_t producer=0,consumer=0;
    std::atomic_flag producing=ATOMIC_FLAG_INIT;
    std::atomic<bool> stopping{false};
    std::mutex sleepMutex;
    std::condition_variable changed;
    std::thread worker;
    static void Peak(std::atomic<uint64_t>& target,uint64_t value){
        auto old=target.load(std::memory_order_relaxed);
        while(old<value&&!target.compare_exchange_weak(old,value,std::memory_order_relaxed)){}
    }
    void Run() {
        for(;;) {
            auto& slot=slots[consumer%Capacity];
            if(!slot.ready.load(std::memory_order_acquire)){
                if(stopping.load(std::memory_order_acquire)&&!pending.load(std::memory_order_acquire))return;
                std::unique_lock lock(sleepMutex);
                // Notification never acquires the worker lock. The finite wait
                // also covers a notification between predicate and sleep.
                changed.wait_for(lock,std::chrono::milliseconds(1),[&]{
                    return stopping.load()||slot.ready.load(std::memory_order_acquire);
                });
                continue;
            }
            {
                RtrDiagnostics::Scope release(RtrDiagnostics::DriverRelease,1,slot.bytes,1,0,0,0,0,slot.bytes,slot.sequence,slot.revision);
                const bool collecting=RtrDiagnostics::level.load(std::memory_order_relaxed)!=0;
                auto begin=collecting?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};
                slot.destroy(slot.storage.data());
                if(collecting){releaseNanos.fetch_add(uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count()));++released;}
            }
            pendingBytes.fetch_sub(slot.bytes,std::memory_order_relaxed);
            pending.fetch_sub(1,std::memory_order_release);
            slot.ready.store(false,std::memory_order_release);++consumer;
        }
    }
public:
    RtrResourceReclaimer():worker([this]{Run();}){}
    ~RtrResourceReclaimer(){stopping.store(true,std::memory_order_release);changed.notify_one();worker.join();}
    bool Available() const {return !stopping.load(std::memory_order_acquire)&&pending.load(std::memory_order_acquire)<Capacity&&!slots[producer%Capacity].ready.load(std::memory_order_acquire);}
    uint64_t OldestAge() const{
        if(!RtrDiagnostics::level.load())return 0;
        const auto now=RtrDiagnostics::Now();uint64_t age=0;
        for(const auto& slot:slots)if(slot.ready.load(std::memory_order_acquire)){
            const auto tick=slot.admittedTick.load(std::memory_order_relaxed);if(tick&&tick<=now)age=std::max(age,now-tick);
        }return age;
    }
    template<class T> bool Enqueue(T& value,uint64_t bytes=0) {
        static_assert(sizeof(T)<=StorageBytes&&alignof(T)<=alignof(std::max_align_t),"Retirement payload exceeds fixed slot");
        static_assert(std::is_nothrow_move_constructible_v<T>,"Retirement transfer must not partially move a request");
        if(producing.test_and_set(std::memory_order_acquire)){if(RtrDiagnostics::level.load())++enqueueRejected;return false;}
        auto& slot=slots[producer%Capacity];
        if(stopping.load(std::memory_order_acquire)||slot.ready.load(std::memory_order_acquire)){
            producing.clear(std::memory_order_release);if(RtrDiagnostics::level.load())++enqueueRejected;return false;
        }
        new(slot.storage.data()) T(std::move(value));
        slot.destroy=[](void* p) noexcept {static_cast<T*>(p)->~T();};
        slot.bytes=bytes;slot.sequence=RtrDiagnostics::frame.load();slot.revision=RtrDiagnostics::revision.load();
        slot.admittedTick.store(RtrDiagnostics::level.load()?RtrDiagnostics::Now():0,std::memory_order_relaxed);
        const auto count=pending.fetch_add(1,std::memory_order_acq_rel)+1;
        const auto size=pendingBytes.fetch_add(bytes,std::memory_order_relaxed)+bytes;
        if(RtrDiagnostics::level.load()){Peak(peakPending,count);Peak(peakBytes,size);}
        slot.ready.store(true,std::memory_order_release);++producer;
        producing.clear(std::memory_order_release);changed.notify_one();return true;
    }
};
