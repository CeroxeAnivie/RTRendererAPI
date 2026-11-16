#pragma once
#include "rtxpt_memory_resources.h"
#include <algorithm>
#include <memory>
#include <new>
#include <type_traits>

namespace rtr {
// Only a complete upcoming run is admitted. Every element's final value is
// constructed once, before writeBuffer reads it; old scratch values are unused.
template<class T> class ReceiverUploadStorage {
    static_assert(std::is_trivially_destructible_v<T>);
    T* values=nullptr;size_t allocated=0;
public:
    ReceiverUploadStorage()=default;
    ReceiverUploadStorage(const ReceiverUploadStorage&)=delete;
    ReceiverUploadStorage& operator=(const ReceiverUploadStorage&)=delete;
    ~ReceiverUploadStorage(){if(values)std::allocator<T>{}.deallocate(values,allocated);}
    void reserve(size_t count){
        if(count<=allocated)return;
        const size_t next=std::max(count,allocated<=SIZE_MAX/2?allocated*2:count);
        auto* replacement=std::allocator<T>{}.allocate(next);
        if(values)std::allocator<T>{}.deallocate(values,allocated);
        values=replacement;allocated=next;
    }
    T* data(){return values;}size_t capacity()const{return allocated;}
};
// Decode has validated canonical indices and channels; public overrides have
// the exact corner extent. Padding and omitted channels are always initialized.
template<class T> void PackReceiverUpload(T* target,size_t count,RtrInputSpan<uint32_t> indices,
        RtrInputSpan<float> baseline,RtrInputSpan<uint32_t> colors,
        const float* baselineOverride,const uint32_t* colorOverride){
    for(size_t i=0;i<count;++i){
        const size_t vertex=indices.empty()?i:indices[i];
        const float x=baselineOverride?baselineOverride[i*2]:baseline.empty()?0:baseline[vertex*2];
        const float y=baselineOverride?baselineOverride[i*2+1]:baseline.empty()?0:baseline[vertex*2+1];
        const uint32_t color=colorOverride?colorOverride[i]:colors.empty()?0xffffffff:colors[vertex];
        ::new(static_cast<void*>(target+i)) T{{x,y},color,0};
    }
}
}
