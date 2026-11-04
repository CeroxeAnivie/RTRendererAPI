#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <d3d12.h>
#include <dxgi.h>
#include <stdexcept>
#include <string>
#include <atomic>
#include "rtxpt_protocol_trace.h"
inline std::atomic<int64_t> RtrDeviceFailure{0};
inline void RtrLatchDeviceFailure(HRESULT reason){int64_t expected=0;RtrDeviceFailure.compare_exchange_strong(expected,int64_t(uint32_t(reason)));}
inline void RtrRequireHealthy(){if(auto reason=RtrDeviceFailure.load())throw std::runtime_error("D3D12 session failed: "+std::to_string(reason));}

// The reader is injectable so device-error paths can be checked without a TDR.
template<class Reason> uint64_t RtrFenceObservation(uint64_t value,Reason&& readReason){
    if(value==UINT64_MAX) {
        const HRESULT reason=readReason();
        RtrLatchDeviceFailure(FAILED(reason)?reason:DXGI_ERROR_DEVICE_REMOVED);
        RtrProtocolTrace::Record(4,0,0,0,0,0,0,0,0,value,0,uint32_t(reason));RtrRequireHealthy();
    }
    return value;
}
// UINT64_MAX is a device error, never evidence that a reader has finished.
inline uint64_t RtrCompletedFence(ID3D12Fence* fence) {
    return RtrFenceObservation(fence->GetCompletedValue(),[&]{
        ID3D12Device* device=nullptr;HRESULT reason=DXGI_ERROR_DEVICE_REMOVED;
        if(SUCCEEDED(fence->GetDevice(IID_PPV_ARGS(&device)))){reason=device->GetDeviceRemovedReason();device->Release();}
        return reason;
    });
}
inline bool RtrDeviceRemoved(ID3D12Device* device){return device&&FAILED(device->GetDeviceRemovedReason());}
