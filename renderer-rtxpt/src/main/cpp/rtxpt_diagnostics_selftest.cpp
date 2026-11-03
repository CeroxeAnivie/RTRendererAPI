#define RTR_DIAGNOSTICS_SELFTEST
#include "rtxpt_diagnostics.h"
#include "rtxpt_host_resources.h"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <future>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

static std::atomic<uint64_t> allocations{0};
void* operator new(std::size_t size){++allocations;if(void* p=std::malloc(size?size:1))return p;throw std::bad_alloc();}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete(void* p,std::size_t) noexcept{std::free(p);}
using namespace RtrDiagnostics;
static void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
static Event eventAt(const std::vector<uint64_t>& snapshot,size_t index){
    Event event{};std::copy_n(snapshot.begin()+13+index*Width,Width,event.begin());return event;
}
static void off(){
    Reset();const auto clocks=clockCalls.load(),ids=nextId.load(),allocated=allocations.load();
    for(uint64_t i=0;i<100000;++i){BeginSubmit(i,1,0);{Scope scope(Frame);Scope child(Lighting);}EndSubmit(false);}
    require(clockCalls==clocks,"OFF collected clock values");require(nextId==ids,"OFF allocated diagnostic IDs");
    require(allocations==allocated,"OFF allocated memory");
    const auto snapshot=Snapshot();require(snapshot[3]==0&&snapshot[7]==0&&snapshot[9]==0,"OFF recorded samples");
    require(clockCalls==clocks,"OFF snapshot collected clock values");
    std::puts("RTXPT_DIAGNOSTICS_OFF PASS frames=100000 clocks=0 allocations=0 events=0");
}
static void eventPoll(){
    Reset();const auto clocks=clockCalls.load();
    for(size_t i=0;i<100000;++i)RtrHostResources::NoteEventPoll();
    require(clockCalls==clocks,"OFF event polls collected clocks");
    Configure(1);RtrHostResources::NoteEventPoll();
    require(clockCalls==clocks+1&&RtrHostResources::lastPollNanos!=0,"Enabled event polls do not timestamp");
    Configure(0);Configure(1);RtrHostResources::maximumPollIntervalNanos=0;RtrHostResources::NoteEventPoll();
    require(RtrHostResources::maximumPollIntervalNanos==0,"Event poll interval includes the OFF gap");
    Configure(0);RtrHostResources::NoteEventPoll();require(RtrHostResources::lastPollNanos==0,"OFF event poll retained active baseline");
    std::puts("RTXPT_DIAGNOSTICS_EVENT_POLL PASS offClocks=0 offGapExcluded=true");
}
static void hierarchy(){
    Reset();Configure(2);const auto allocated=allocations.load();
    BeginSubmit(7,19,0);SubmitStage(SubmitValidation,0,1,3,0);
    {Scope frameScope(Frame);{Scope dispatch(Dispatch);{Scope caller(Caller,3,1,2,dispatch.event[2]);}}}
    EndSubmit(false);require(allocations==allocated,"Recording allocated memory");
    const auto snapshot=Snapshot();require(snapshot[3]==5&&snapshot[9]==1,"Hierarchy event count");
    for(size_t i=0;i<snapshot[3];++i){auto event=eventAt(snapshot,i);uint64_t children=0;
        require(event[12]!=0&&event[7]<=event[6],"Invalid unique ID or self duration");
        for(size_t j=0;j<snapshot[3];++j){const auto child=eventAt(snapshot,j);
            if(child[13]==event[12]){require(child[3]==event[3],"Cross-thread child subtracted from CPU self");children+=child[6];}}
        require(event[7]==event[6]-std::min(event[6],children),"CPU self does not subtract only direct children");
    }
    std::puts("RTXPT_DIAGNOSTICS_HIERARCHY PASS uniqueScopes=5 recordingAllocations=0");
}
static void workersAndCancellation(){
    Reset();Configure(2);BeginSubmit(1,1,0);uint64_t callerId=0,workerId=0;
    {Scope dispatch(Dispatch,64,2);callerId=dispatch.event[12];const auto batch=dispatch.event[2];
        std::thread worker([&]{Scope work(Worker,32,1,2,batch);workerId=work.event[12];});
        {Scope caller(Caller,32,1,2,batch);}worker.join();}
    EndSubmit(false);
    auto snapshot=Snapshot();bool found=false;
    for(size_t i=0;i<snapshot[3];++i){const auto event=eventAt(snapshot,i);
        if(event[12]==workerId){found=true;require(event[13]==0&&event[12]!=callerId,"Worker batch ID became cross-thread parent");}}
    require(found,"Worker evidence missing");
    BeginSubmit(2,1,0);BeginSubmit(3,1,0);EndSubmit(false,true);
    snapshot=Snapshot();size_t offset=13+size_t(snapshot[3]+snapshot[5]+snapshot[7])*Width;
    unsigned cancelled=0;for(size_t i=0;i<snapshot[9];++i,offset+=6)cancelled+=(snapshot[offset+3]&Cancelled)!=0;
    require(cancelled==2,"Interrupted and explicit cancellation not retained");
    std::puts("RTXPT_DIAGNOSTICS_WORKERS PASS sharedBatch uniqueScopes noCrossThreadSelf cancellation=2");
}
static void activeAndFailure(){
    Reset();Configure(2);const auto token=MonitorToken();
    std::promise<void> entered,released;auto release=released.get_future();
    std::thread owner([&]{BeginSubmit(21,31,60'000'000);
        try{Scope lighting(Lighting,160000,18);entered.set_value();release.wait();throw std::runtime_error("Expected failure before frame");}
        catch(const std::runtime_error&){EndSubmit(true);}
    });
    entered.get_future().wait();const auto snapshot=Snapshot(token);
    require(snapshot[7]==2,"Watchdog cannot observe active submit and lighting scopes");
    size_t offset=13+size_t(snapshot[3]+snapshot[5])*Width;bool lighting=false,submit=false;
    for(size_t i=0;i<snapshot[7];++i,offset+=Width){require(snapshot[offset+11]&Active,"Live sample not marked incomplete");
        require(snapshot[offset+7]==0,"Live sample exposed speculative CPU self");
        lighting|=snapshot[offset+4]==Lighting;submit|=snapshot[offset+4]==Submit;
        require(snapshot[offset]==21&&snapshot[offset+1]==31,"Watchdog lost frame/revision context");}
    require(lighting&&submit,"Active stage names missing");released.set_value();owner.join();
    const auto done=Snapshot(token);require(done[7]==0&&done[9]==1&&done[4]>=1,"Failure recording missing");
    const size_t sample=13+size_t(done[3]+done[5])*Width;
    require((done[sample+3]&Failed)&&!(done[sample+3]&EnteredNativeFrame),"Failure before native FRAME was misrepresented");
    require(done[5]>=2,"Slow/failed submit did not retain lighting before frame");
    std::puts("RTXPT_DIAGNOSTICS_WATCHDOG PASS liveSubmitAndLighting=2 failedBeforeFrame=1");
}
static void switchAndReset(){
    Reset();Configure(1);auto token=MonitorToken();BeginSubmit(2,2,0);
    {Scope lighting(Lighting);Configure(0);const auto clocks=clockCalls.load();
        require(Snapshot(token)[7]==0,"OFF left active samples");
        require(clockCalls==clocks,"OFF snapshot continued clock collection");
        Configure(1);require(MonitorToken()==token,"Level change expired the session monitor");
    }
    EndSubmit(true);auto snapshot=Snapshot(token);require(snapshot[9]==1,"Close lost live submit");
    const size_t sample=13+size_t(snapshot[3]+snapshot[5]+snapshot[7])*Width;
    require((snapshot[sample+3]&(Failed|Truncated))==(Failed|Truncated),"Close/finally lost failure or truncation");
    require(snapshot[4]>=1,"Failed close did not retain diagnostic evidence");
    {Scope old(Lighting);Reset();Configure(1);{Scope current(Frame);}const auto clocks=clockCalls.load();
        require(Snapshot()[3]==1,"Reset exposed stale scope events");require(clockCalls==clocks,"Completed snapshot read a clock");}
    require(Snapshot()[3]==1,"Old scope destructor repopulated new session");
    bool expired=false;try{Snapshot(token);}catch(const std::runtime_error&){expired=true;}require(expired,"Old session monitor was accepted");
    std::puts("RTXPT_DIAGNOSTICS_SWITCH PASS offDrain truncation failureAnnotation resetGeneration");
}
static std::vector<uint64_t> categories(){
    Reset();Configure(2);
    BeginSubmit(0,1,0);{Scope frameScope(Frame);}EndSubmit(false);
    BeginSubmit(1,2,0);{Scope update(Resources);}EndSubmit(false);
    BeginSubmit(2,2,0);{Scope resize(Resize);Scope frameScope(Frame);}EndSubmit(false);
    BeginSubmit(3,2,0);{Scope wait(TaskWait,0,1);}EndSubmit(false);
    BeginSubmit(4,2,0);{Scope frameScope(Frame);}EndSubmit(false);
    BeginSubmit(5,3,70'000'000);{Scope update(Lighting);}EndSubmit(true);
    const auto snapshot=Snapshot();require(snapshot[9]==6,"Missing independently retained submit samples");
    size_t offset=13+size_t(snapshot[3]+snapshot[5]+snapshot[7])*Width;
    const uint64_t expected[]={Initial,Update,Resized,Waited,Steady,Update};
    for(size_t i=0;i<6;++i,offset+=6)require(snapshot[offset+4]==expected[i],"Submit work category mismatch");
    std::puts("RTXPT_DIAGNOSTICS_CATEGORIES PASS initial update resize wait steady failure");return snapshot;
}
static void bounded(){
    Reset();Configure(1);BeginSubmit(1,1,60'000'000);{Scope lighting(Lighting);}EndSubmit(false);
    for(size_t i=0;i<Capacity+100;++i){Scope scope(Frame);}
    auto snapshot=Snapshot();require(snapshot[3]==Capacity&&snapshot[2]>0&&snapshot[5]>=2,"Rolling window lost slow evidence");
    Reset();Configure(1);
    for(size_t i=0;i<SubmissionCapacity+100;++i){BeginSubmit(i,1,0);EndSubmit(false);}
    snapshot=Snapshot();require(snapshot[9]==SubmissionCapacity&&snapshot[10]==100,"Submit sample window is not bounded");
    Reset();Configure(1);std::vector<std::unique_ptr<Scope>> active;
    for(size_t i=0;i<ActiveCapacity+1;++i)active.push_back(std::make_unique<Scope>(Lighting));
    snapshot=Snapshot();require(snapshot[7]==ActiveCapacity&&snapshot[8]==1,"Activity table overflow was not reported");
    while(!active.empty())active.pop_back();require(Snapshot()[7]==0,"Active slot leaked");
    std::puts("RTXPT_DIAGNOSTICS_BOUNDS PASS rolling=8192 live=512 submissions=1024 overflowReported");
}
static void benchmark(){
    for(unsigned mode:{0u,1u,2u}){Reset();Configure(mode);std::array<double,9> samples{};
        const auto beforeClocks=clockCalls.load();
        for(auto& sample:samples){auto begin=std::chrono::steady_clock::now();
            for(uint64_t i=0;i<20000;++i){BeginSubmit(i,1,0);{Scope frameScope(Frame);Scope lighting(Lighting);}EndSubmit(false);}
            sample=std::chrono::duration<double,std::nano>(std::chrono::steady_clock::now()-begin).count()/20000;}
        std::sort(samples.begin(),samples.end());
        std::printf("RTXPT_DIAGNOSTICS_CPU_BENCH level=%u iterations=180000 p50NsPerSubmit=%.3f p95NsPerSubmit=%.3f clockCalls=%llu retainedEvents=%llu\n",
            mode,samples[4],samples[8],static_cast<unsigned long long>(clockCalls.load()-beforeClocks),static_cast<unsigned long long>(written));}
}
int main(int argc,char** argv){try{
    off();eventPoll();hierarchy();workersAndCancellation();activeAndFailure();switchAndReset();auto fixture=categories();bounded();benchmark();
    if(argc==2){std::ofstream file(argv[1],std::ios::binary);file.write(reinterpret_cast<const char*>(fixture.data()),std::streamsize(fixture.size()*8));require(bool(file),"Fixture write failed");}
    std::puts("RTXPT_DIAGNOSTICS_ALL PASS");return 0;
}catch(const std::exception& e){std::fprintf(stderr,"RTXPT_DIAGNOSTICS_FAIL %s\n",e.what());return 1;}}
