#include "rtxpt_task_batch.h"
#include <atomic>
#include <cstdio>
#include <numeric>
#include <stdexcept>
#include <vector>
#include <algorithm>

template<class Func> static void legacySpawn(donut::engine::ThreadPool& pool,std::atomic<unsigned>& pending,unsigned chunk,unsigned chunks,size_t step,size_t count,Func& fn){
    if(chunk>=chunks||size_t(chunk)*step>=count)return;
    ++pending;
    pool.AddTask([&,chunk,chunks,step,count]{
        legacySpawn(pool,pending,chunk*2+1,chunks,step,count,fn);
        legacySpawn(pool,pending,chunk*2+2,chunks,step,count,fn);
        fn(size_t(chunk)*step,std::min(count,size_t(chunk+1)*step));--pending;
    });
}
static void benchmark(){
    donut::engine::ThreadPool pool(4);
    for(size_t count:{16u,64u,256u,4096u}){
        std::vector<size_t> values(count);auto fn=[&](size_t first,size_t last){for(size_t i=first;i<last;++i)values[i]=i*17+3;};
        std::vector<double> oldTimes,newTimes;
        for(int sample=0;sample<80;++sample){
            auto begin=std::chrono::steady_clock::now();std::atomic<unsigned> pending{0};
            unsigned chunks=std::max(1u,std::thread::hardware_concurrency());size_t step=(count+chunks-1)/chunks;
            legacySpawn(pool,pending,1,chunks,step,count,fn);legacySpawn(pool,pending,2,chunks,step,count,fn);
            fn(0,std::min(count,step));while(pending)std::this_thread::sleep_for(std::chrono::milliseconds(2));
            const double oldMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();pool.WaitForTasks();
            begin=std::chrono::steady_clock::now();ParallelForChunked(pool,count,fn);
            const double newMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();
            if(sample>=16){oldTimes.push_back(oldMs);newTimes.push_back(newMs);}
        }
        std::sort(oldTimes.begin(),oldTimes.end());std::sort(newTimes.begin(),newTimes.end());
        std::printf("RTXPT_CPU_BATCH_BENCH count=%zu workers=4 hardware=%u samples=%zu oldP50Ms=%.6f oldP95Ms=%.6f oldP99Ms=%.6f oldMaxMs=%.6f oldOver2Ms=%zu newP50Ms=%.6f newP95Ms=%.6f newP99Ms=%.6f newMaxMs=%.6f newOver2Ms=%zu\n",count,std::thread::hardware_concurrency(),oldTimes.size(),oldTimes[31],oldTimes[60],oldTimes[63],oldTimes.back(),size_t(std::count_if(oldTimes.begin(),oldTimes.end(),[](double v){return v>2;})),newTimes[31],newTimes[60],newTimes[63],newTimes.back(),size_t(std::count_if(newTimes.begin(),newTimes.end(),[](double v){return v>2;})));
    }
    for(unsigned level:{0u,1u,2u}){
        RtrDiagnostics::Configure(level);std::vector<double> times;
        for(unsigned sample=0;sample<120;++sample){
            auto begin=std::chrono::steady_clock::now();
            for(unsigned repeat=0;repeat<100;++repeat)ParallelForChunked(pool,256,[](size_t,size_t){});
            const auto ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count()/100;
            if(sample>=20)times.push_back(ms);
        }
        std::sort(times.begin(),times.end());
        std::printf("RTXPT_CPU_DIAGNOSTIC_BENCH level=%u batches=10000 count=256 workers=4 p50Ms=%.6f p95Ms=%.6f\n",level,times[49],times[94]);
    }
    RtrDiagnostics::Configure(0);
}

static void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
int main(){
    try {
        for(unsigned threads:{1u,2u,4u,8u}){
            donut::engine::ThreadPool pool(threads);
            for(size_t count:{0u,1u,3u,63u,64u,256u,4096u,100000u}){
                std::vector<size_t> values(count);std::atomic<unsigned> calls{0};
                ParallelForChunkedIdx(pool,count,[&](size_t begin,size_t end,uint32_t chunk){
                    ++calls;require(chunk<ParallelForChunkCount(pool,count),"Invalid output slot");
                    for(size_t i=begin;i<end;++i)values[i]=i*17+3;
                });
                require(calls<=threads+1,"Unbounded task partitioning");
                for(size_t i=0;i<count;++i)require(values[i]==i*17+3,"Serial reference mismatch");
            }
            for(bool caller:{false,true}){
                std::atomic<unsigned> finished{0};bool thrown=false;
                try{ParallelForChunkedIdx(pool,4096,[&](size_t,size_t,uint32_t chunk){
                    if((chunk==0)==caller)throw std::runtime_error("Expected batch failure");++finished;
                });}catch(const std::runtime_error&){thrown=true;}
                require(thrown,"Batch exception swallowed");
                const auto after=finished.load();pool.WaitForTasks();require(finished==after,"Batch returned with borrowed closure outstanding");
            }
            pool.AddTask([]{throw std::runtime_error("Expected pool failure");});bool thrown=false;
            try{pool.WaitForTasks();}catch(const std::runtime_error&){thrown=true;}
            require(thrown,"Pool exception swallowed");pool.WaitForTasks();
        }
        // A foreign job stays blocked until after this batch has returned.
        donut::engine::ThreadPool pool(2);std::atomic<bool> release{false},complete{false},started{false};
        pool.AddTask([&]{started=true;while(!release)std::this_thread::yield();});
        while(!started)std::this_thread::yield();
        std::thread watchdog([&]{for(int i=0;i<200&&!complete;i++)std::this_thread::sleep_for(std::chrono::milliseconds(5));release=true;});
        ParallelForChunked(pool,128,[](size_t,size_t){});
        const bool isolated=!release;complete=true;watchdog.join();pool.WaitForTasks();
        require(isolated,"Frame batch waited on unrelated pool work");
        require(RtrDiagnostics::Snapshot()[3]==0,"Disabled diagnostics recorded events");
        RtrDiagnostics::Configure(2);ParallelForChunked(pool,256,[](size_t,size_t){});
        require(RtrDiagnostics::Snapshot()[3]>0,"Deep diagnostics missing batch evidence");
        RtrDiagnostics::Record({1,1,1,1,RtrDiagnostics::Frame,0,60'000'000,60'000'000,0,0,0,0});
        for(unsigned i=0;i<9000;++i)RtrDiagnostics::Record({2,1,1,1,RtrDiagnostics::Worker,1,1,1,1,1,0,0});
        const auto retained=RtrDiagnostics::Snapshot();
        require(retained[2]>0&&retained[4]>0&&retained[5]>0,"Rolling overwrite or slow-frame retention missing");
        RtrDiagnostics::Configure(0);
        benchmark();
        std::puts("RTXPT_TASK_BATCH PASS worker=1,2,4,8 serial-equivalence exceptions isolation bounded-diagnostics");
    } catch(const std::exception& error){std::fprintf(stderr,"%s\n",error.what());return 1;}
}
