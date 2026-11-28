#pragma once
#include <d3d12.h>
#include <windows.h>
#include <wrl/client.h>
#include <filesystem>
#include <cstdio>
#include <stdexcept>
#include <string>

namespace rtr::upstream {

inline void CheckAgility(HRESULT result, const char* operation) {
    if (SUCCEEDED(result)) return;
    char error[12]{};
    std::snprintf(error, sizeof(error), "0x%08lX", static_cast<unsigned long>(result));
    throw std::runtime_error(std::string(operation) + " failed (" + error + ")");
}

inline Microsoft::WRL::ComPtr<ID3D12DeviceFactory> OpenAgilityFactory(const std::filesystem::path& runtimeBin) {
    // Resolve the Windows loader explicitly because Donut's ordinary DX12 entry
    // imports can point at Streamline's interposer.
    static const HMODULE loader = LoadLibraryExW(L"d3d12.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    const auto getInterface = loader ? reinterpret_cast<decltype(&D3D12GetInterface)>(
        GetProcAddress(loader, "D3D12GetInterface")) : nullptr;
    if (!getInterface) throw std::runtime_error("Windows D3D12 device factory is unavailable");

    constexpr GUID sdkConfiguration{0x7cda6aca, 0xa03e, 0x49c8, {0x94, 0x58, 0x03, 0x34, 0xd2, 0x0e, 0x07, 0xce}};
    Microsoft::WRL::ComPtr<ID3D12SDKConfiguration1> config;
    CheckAgility(getInterface(sdkConfiguration, IID_PPV_ARGS(config.GetAddressOf())), "Get D3D12 SDK configuration");
    const auto path = (runtimeBin / L"D3D12").u8string();
    Microsoft::WRL::ComPtr<ID3D12DeviceFactory> factory;
    CheckAgility(config->CreateDeviceFactory(RTXPT_D3D_AGILITY_SDK_VERSION,
        reinterpret_cast<const char*>(path.c_str()), IID_PPV_ARGS(factory.GetAddressOf())), "Open packaged D3D12 Agility SDK");
    CheckAgility(factory->SetFlags(D3D12_DEVICE_FACTORY_FLAG_DISALLOW_STORING_NEW_DEVICE_AS_SINGLETON),
        "Isolate D3D12 device factory");
    return factory;
}

} // namespace rtr::upstream
