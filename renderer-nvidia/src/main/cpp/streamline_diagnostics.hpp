#pragma once

#include <string>

#include <sl.h>

namespace rtrenderer::nvidia {

std::string streamlineResultName(sl::Result result);
void captureStreamlineDiagnostic(sl::LogType type, const char* message);
void clearStreamlineDiagnostic();
std::string currentStreamlineDiagnostic();

}
