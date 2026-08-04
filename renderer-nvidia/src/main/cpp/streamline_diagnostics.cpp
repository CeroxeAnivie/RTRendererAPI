#include "streamline_diagnostics.hpp"

#include <mutex>

#include <sl_helpers.h>

namespace rtrenderer::nvidia {
namespace {

std::mutex diagnosticMutex;
std::string diagnostic;

}

std::string streamlineResultName(sl::Result result) {
    return std::string(sl::getResultAsStr(result)) + "("
            + std::to_string(static_cast<int>(result)) + ")";
}

void captureStreamlineDiagnostic(sl::LogType type, const char* message) {
    if (message == nullptr || (type != sl::LogType::eWarn && type != sl::LogType::eError)) return;
    std::scoped_lock lock(diagnosticMutex);
    if (!diagnostic.empty()) diagnostic += '\n';
    diagnostic += message;
    constexpr std::size_t maximumDiagnosticLength = 16U * 1024U;
    if (diagnostic.length() > maximumDiagnosticLength) {
        diagnostic.erase(0, diagnostic.length() - maximumDiagnosticLength);
    }
}

void clearStreamlineDiagnostic() {
    std::scoped_lock lock(diagnosticMutex);
    diagnostic.clear();
}

std::string currentStreamlineDiagnostic() {
    std::scoped_lock lock(diagnosticMutex);
    return diagnostic.empty() ? "no Streamline warning or error was reported" : diagnostic;
}

}
