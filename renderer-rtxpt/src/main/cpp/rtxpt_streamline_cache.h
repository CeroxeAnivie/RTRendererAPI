#pragma once
#include <array>
#include <cstddef>
#include <stdexcept>

// Include after sl.h and before the SDK feature headers. The SDK's inline
// helpers cache plugin addresses forever; embedded sessions unload the plugins.
// Retain its one-null-check hot path and invalidate addresses only at shutdown.
namespace rtr::streamline {
struct FunctionCaches {
    struct Slot { void* address; void (*clear)(void*); };
    inline static std::array<Slot, 64> slots{};
    inline static std::size_t count=0;
    template<class T> static void track(T** address) {
        for(std::size_t i=0;i<count;++i)if(slots[i].address==address)return;
        if(count==slots.size())throw std::runtime_error("Too many Streamline feature function caches");
        slots[count++]={address,[](void* slot){*static_cast<T**>(slot)=nullptr;}};
    }
    static void reset() {
        for(std::size_t i=0;i<count;++i)slots[i].clear(slots[i].address);
        count=0;
    }
};
}

#undef SL_FEATURE_FUN_IMPORT_STATIC
#define SL_FEATURE_FUN_IMPORT_STATIC(feature, func)                       \
    static PFun_##func* s_##func{};                                       \
    if(!s_##func) {                                                       \
        sl::Result res=slGetFeatureFunction(feature,#func,(void*&)s_##func); \
        if(res!=sl::Result::eOk)return res;                               \
        rtr::streamline::FunctionCaches::track(&s_##func);                 \
    }
