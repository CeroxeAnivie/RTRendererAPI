#pragma once
#include <array>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <mutex>
#include <thread>

// Optional diagnostic export: fixed storage, nonblocking producers, one writer.
class RtrAsyncOutput {
    FILE* file=nullptr;
    bool ownsFile=true;
    std::array<std::array<char,512>,2048> lines{};
    size_t first=0,count=0;
    bool stopping=false;
    std::mutex mutex;std::condition_variable available;std::thread writer;
    void Start(){
        writer=std::thread([this]{
            std::unique_lock lock(mutex);
            for(;;){
                available.wait(lock,[&]{return count||stopping;});if(!count&&stopping)break;
                auto line=lines[first];first=(first+1)%lines.size();--count;
                lock.unlock();std::fputs(line.data(),file);lock.lock();
            }
        });
    }
public:
    uint64_t dropped=0;
    explicit RtrAsyncOutput(const char* path){
        if(fopen_s(&file,path,"wb")!=0)throw std::runtime_error("Cannot open GPU diagnostic output");
        Start();
    }
    explicit RtrAsyncOutput(FILE* target,bool closeFile=false):file(target),ownsFile(closeFile){Start();}
    void Write(const char* line){
        std::lock_guard lock(mutex);
        if(count==lines.size()){++dropped;return;}
        strncpy_s(lines[(first+count)%lines.size()].data(),512,line,_TRUNCATE);++count;available.notify_one();
    }
    ~RtrAsyncOutput(){
        {std::lock_guard lock(mutex);stopping=true;available.notify_one();}
        if(writer.joinable())writer.join();if(file&&ownsFile)std::fclose(file);
    }
};
