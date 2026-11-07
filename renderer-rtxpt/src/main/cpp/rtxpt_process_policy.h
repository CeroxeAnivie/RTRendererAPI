#pragma once
#include <Windows.h>
#include <stdexcept>
#include <string>

namespace rtr {
// The dedicated rendering process consumes build-time binaries. The OS denies
// child creation, including the upstream sample's external DXC fallback. This
// changes no NVIDIA code and does not report a failed compilation as successful.
inline void RequirePrecompiledProcess() {
    PROCESS_MITIGATION_CHILD_PROCESS_POLICY policy{};
    policy.NoChildProcessCreation = 1;
    if (!SetProcessMitigationPolicy(ProcessChildProcessPolicy, &policy, sizeof(policy)))
        throw std::runtime_error("Cannot prohibit runtime compiler processes; Win32=" + std::to_string(GetLastError()));
    policy = {};
    if (!GetProcessMitigationPolicy(GetCurrentProcess(), ProcessChildProcessPolicy, &policy, sizeof(policy))
            || !policy.NoChildProcessCreation)
        throw std::runtime_error("Runtime compiler process prohibition is not active");
}
}
