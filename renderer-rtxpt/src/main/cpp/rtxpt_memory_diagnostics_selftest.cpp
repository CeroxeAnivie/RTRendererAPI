#include "rtxpt_memory_diagnostics.h"
#include <cstring>

int main(int argc,char** argv) {
    const bool expected=argc==2 && std::strcmp(argv[1],"enabled")==0;
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_DIAGNOSTICS",expected?L"1":nullptr);
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_LOG",nullptr);
    if(RtrMemoryDiagnostics::Enabled()!=expected)return 1;
    // Null resources and an unavailable graphics device must be safe in both modes.
    RtrMemoryDiagnostics::Created(nullptr,"test","test",nullptr,nullptr,0);
    RtrMemoryDiagnostics::Released(nullptr);
    RtrMemoryDiagnostics::Checkpoint("selftest");
    {RtrMemoryDiagnostics::Scope scope("selftest.scope");}
    if(!RtrMemoryDiagnostics::resources.empty())return 2;
    // The opt-in setting is immutable for the lifetime of the process.
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_DIAGNOSTICS",expected?nullptr:L"1");
    return RtrMemoryDiagnostics::Enabled()==expected?0:3;
}
