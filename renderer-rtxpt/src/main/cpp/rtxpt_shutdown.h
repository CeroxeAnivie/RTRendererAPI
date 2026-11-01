#pragma once
#include <stdexcept>
// Include after the upstream StreamlineIntegration.h (it has no include guard).

// SL 2.14 queues RR destruction functors in sl.common. Retain the module that
// defines them until slShutdown has drained common's queue. No per-frame work.
// The public lookup follows signed NGX plugin updates without hardcoded paths.
class ShutdownModuleReference {
    HMODULE module = nullptr;
public:
    ShutdownModuleReference() {
        void* callback = nullptr;
        if (slGetFeatureFunction(sl::kFeatureDLSS_RR, "slDLSSDGetState", callback) == sl::Result::eOk && callback
                && !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                    reinterpret_cast<LPCWSTR>(callback), &module))
            throw std::runtime_error("Cannot retain Streamline RR module for shutdown");
    }
    ~ShutdownModuleReference() { if (module) FreeLibrary(module); }
    ShutdownModuleReference(const ShutdownModuleReference&) = delete;
    ShutdownModuleReference& operator=(const ShutdownModuleReference&) = delete;
};
