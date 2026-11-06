#define RTR_MEMORY_DIAGNOSTICS_SELFTEST
#include "rtxpt_memory_diagnostics.h"
#include <cstring>
#include <cstdio>
#include <optional>

int main(int argc,char** argv) {
    const bool census=argc==2&&std::strcmp(argv[1],"census")==0;
    const bool expected=census||(argc==2 && std::strcmp(argv[1],"enabled")==0);
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_DIAGNOSTICS",expected?L"1":nullptr);
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_RESOURCE_CENSUS",census?L"1":nullptr);
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_LOG",nullptr);
    if(RtrMemoryDiagnostics::Enabled()!=expected)return 1;
    if(RtrMemoryDiagnostics::CensusEnabled()!=census)return 7;
    // Null resources and an unavailable graphics device must be safe in both modes.
    RtrMemoryDiagnostics::Created(nullptr,"test","test",nullptr,nullptr,0);
    RtrMemoryDiagnostics::Released(nullptr);
    RtrMemoryDiagnostics::Checkpoint("selftest");
    RtrMemoryDiagnostics::Capacity("selftest","cpu_payload",16,64,0,48);
    {RtrMemoryDiagnostics::Scope scope("selftest.scope");}
    if(!RtrMemoryDiagnostics::resources.empty())return 2;
    {
        std::optional<RtrMemoryDiagnostics::Scope> old;old.emplace("selftest.expired");
        RtrMemoryDiagnostics::Configure(false);RtrMemoryDiagnostics::Configure(true);
        // A scope from the old recording epoch must not query or write after resume.
        const auto queries=RtrMemoryDiagnostics::processQueries.load(),clocks=RtrMemoryDiagnostics::clockCalls.load(),writes=RtrMemoryDiagnostics::writes.load();
        if(RtrMemoryDiagnostics::Current(RtrMemoryDiagnostics::epoch.load()-2))return 4;
        old.reset();
        if(RtrMemoryDiagnostics::processQueries!=queries||RtrMemoryDiagnostics::clockCalls!=clocks||RtrMemoryDiagnostics::writes!=writes)return 5;
    }
    RtrMemoryDiagnostics::Configure(false);
    const auto queries=RtrMemoryDiagnostics::processQueries.load(),clocks=RtrMemoryDiagnostics::clockCalls.load(),writes=RtrMemoryDiagnostics::writes.load();
    for(size_t i=0;i<100000;++i){
        RtrMemoryDiagnostics::Scope scope("selftest.off");RtrMemoryDiagnostics::Checkpoint("off");
        RtrMemoryDiagnostics::Capacity("selftest.off","cpu_payload",0,64);
        RtrMemoryDiagnostics::Created(nullptr,"test","test",nullptr,nullptr,0);RtrMemoryDiagnostics::Released(nullptr);
    }
    if(RtrMemoryDiagnostics::processQueries!=queries||RtrMemoryDiagnostics::clockCalls!=clocks||RtrMemoryDiagnostics::writes!=writes)return 6;
    RtrMemoryDiagnostics::Configure(true);
    // The opt-in setting is immutable for the lifetime of the process.
    SetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_DIAGNOSTICS",expected?nullptr:L"1");
    if(RtrMemoryDiagnostics::Enabled()!=expected)return 3;
    std::printf("RTXPT_MEMORY_DIAGNOSTICS_SELFTEST PASS optIn=%u offQueries=0 offClocks=0 offWrites=0 staleEpochRejected=true\n",expected?1:0);return 0;
}
