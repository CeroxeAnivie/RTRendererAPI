#pragma once
#include <array>
#include <stdexcept>
#include <string>
// Include after the upstream StreamlineIntegration.h (it has no include guard).

// SL 2.14 queues SR and RR destruction functors in sl.common after resource
// replacement. Retain both defining modules until slShutdown drains that queue.
// The public lookup follows signed NGX plugin updates without hardcoded paths.
class ShutdownModuleReference {
    struct Module {
        HMODULE handle = nullptr;
        ~Module() { if(handle)FreeLibrary(handle); }
    };
    std::array<Module,2> modules;
public:
    ShutdownModuleReference() {
        auto retain=[](auto feature,const char* name,Module& module) {
            void* callback = nullptr;
            if(slGetFeatureFunction(feature,name,callback)==sl::Result::eOk && callback
                    && !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                        reinterpret_cast<LPCWSTR>(callback),&module.handle))
                throw std::runtime_error(std::string("Cannot retain Streamline module for shutdown: ")+name);
        };
        retain(sl::kFeatureDLSS,"slDLSSGetState",modules[0]);
        retain(sl::kFeatureDLSS_RR,"slDLSSDGetState",modules[1]);
    }
    ShutdownModuleReference(const ShutdownModuleReference&) = delete;
    ShutdownModuleReference& operator=(const ShutdownModuleReference&) = delete;
};
