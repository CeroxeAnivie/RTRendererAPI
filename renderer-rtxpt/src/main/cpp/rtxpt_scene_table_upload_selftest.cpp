#include "rtxpt_scene_table_upload.h"
#include <iostream>
#include <cstring>

using namespace rtr::upstream::scene_upload;
static void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
struct Buffer final : RefCounter<IBuffer> {
    BufferDesc description;
    explicit Buffer(uint64_t bytes){description.byteSize=bytes;}
    const BufferDesc& getDesc() const override{return description;}
    GpuVirtualAddress getGpuVirtualAddress() const override{return 0;}
};
struct Recording final : ForwardingCommandList {
    struct Write {IBuffer* buffer;uint64_t offset;std::vector<uint8_t> bytes;};
    std::vector<Write> writes;
    std::vector<uint8_t> gpu;
    unsigned markers=0;
    explicit Recording(size_t bytes):gpu(bytes,0){}
    void writeBuffer(IBuffer* buffer,const void* data,size_t size,uint64_t offset=0) override {
        require(offset<=gpu.size()&&size<=gpu.size()-offset,"Recording write exceeded destination");
        const auto* bytes=static_cast<const uint8_t*>(data);
        writes.push_back({buffer,offset,std::vector<uint8_t>(bytes,bytes+size)});
        std::memcpy(gpu.data()+offset,data,size);
    }
    void beginMarker(const char*) override{++markers;}
};
int main(){
    constexpr size_t stride=112,capacity=1024;
    BufferHandle buffer,other;buffer.Attach(new Buffer(stride*capacity));other.Attach(new Buffer(stride*capacity));
    RefCountPtr<Recording> commands;commands.Attach(new Recording(stride*capacity));
    RefCountPtr<SceneTableUploadCommands> upload;upload.Attach(new SceneTableUploadCommands());
    std::vector<uint8_t> data(stride*capacity);
    for(size_t i=0;i<data.size();++i)data[i]=uint8_t(i*17+i/stride);
    std::vector<uint32_t> dirty{3};
    auto record=[&](bool initialized=true){
        return upload->Record(commands,buffer,stride,dirty,initialized,[&](ICommandList* target){
            require(commands->GetRefCount()==2,"Recording target was not retained for the native call");
            target->beginMarker("forwarded");target->writeBuffer(buffer,data.data(),data.size());
        });
    };
    auto initial=record(false);
    require(initial.full&&initial.rows==capacity&&initial.writes==1&&initial.bytes==data.size(),"First publication must upload the original full native table");
    require(commands->gpu==data&&commands->GetRefCount()==1,"Full upload bytes or target lifetime changed");
    commands->writes.clear();
    for(size_t i=stride*3;i<stride*4;++i)data[i]^=0x5a;
    auto single=record();
    require(!single.full&&single.rows==1&&single.writes==1&&single.bytes==stride,"One dirty row uploaded the full table");
    require(commands->writes.size()==1&&commands->writes[0].offset==stride*3&&commands->gpu==data,"Partial upload corrupted its neighboring rows");
    commands->writes.clear();dirty={7,2,3,2,6};
    auto runs=record();
    require(!runs.full&&runs.rows==4&&runs.writes==2&&commands->writes[0].offset==stride*2
        &&commands->writes[1].offset==stride*6,"Unsorted/duplicate rows were not merged into exact runs");
    commands->writes.clear();dirty.clear();
    auto empty=record();require(empty.bytes==0&&commands->writes.empty(),"Empty dirty publication issued a GPU write");
    for(uint32_t row=0;row<130;row+=2)dirty.push_back(row);
    commands->writes.clear();auto fragmented=record();
    require(fragmented.full&&fragmented.writes==1&&fragmented.bytes==data.size(),"Fragmented publication did not take the bounded full fallback");
    dirty={capacity};commands->writes.clear();
    require(record().full&&commands->writes.size()==1,"Out-of-array dirty row did not retain original full behavior");
    dirty={1};commands->writes.clear();
    auto offset=upload->Record(commands,buffer,stride,dirty,true,[&](ICommandList* target){target->writeBuffer(buffer,data.data(),stride,2*stride);});
    require(offset.full&&commands->writes.size()==1&&commands->writes[0].offset==2*stride,"Unexpected native sub-write was not forwarded intact");
    commands->writes.clear();
    upload->Record(commands,buffer,stride,dirty,true,[&](ICommandList* target){
        target->writeBuffer(other,data.data(),stride);target->writeBuffer(buffer,data.data(),data.size());
    });
    require(commands->writes.size()==2&&commands->writes[0].buffer==other,"Unrelated buffer write was intercepted");
    bool failed=false;
    try{upload->Record(commands,buffer,stride,dirty,true,[&](ICommandList* target){target->writeBuffer(buffer,data.data(),data.size()+1);});}
    catch(const std::out_of_range&){failed=true;}
    require(failed&&commands->GetRefCount()==1,"Invalid native write did not fail cleanly");
    failed=false;
    try{upload->Record(commands,buffer,stride,dirty,true,[](ICommandList*){throw std::runtime_error("native failure");});}
    catch(const std::runtime_error&){failed=true;}
    require(failed&&commands->GetRefCount()==1,"Throwing native writer retained its recording target");
    commands->writes.clear();record();require(commands->writes.size()==1,"Uploader could not be reused after native failure");
    failed=false;
    try{upload->beginMarker("expired");}catch(const std::logic_error&){failed=true;}
    require(failed&&upload->GetRefCount()==1,"Uploader escaped its synchronous recording scope");
    require(commands->markers>0,"Non-buffer command-list methods were not forwarded");
    std::cout<<"SCENE_TABLE_UPLOAD PASS singleRow=true exactRuns=true firstFull=true fragmentationFallback=true bounds=true nativeFailureCleanup=true refCounts=true\n";
}
