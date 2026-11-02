#pragma once
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

// Keep RTXPT's compiler and permutation model. Only remove installation paths
// from its cache key so an offline-built permutation survives JAR extraction.
namespace RtrShaderBundle {
inline void RequirePrecompiled(const std::string& missing) {
    const char* required=std::getenv("RTR_RTXPT_REQUIRE_PRECOMPILED");
    if(required && std::string(required)=="1")
        throw std::runtime_error("Required packaged shader permutation is missing or stale: "+missing);
}
inline void Replace(std::string& text,const std::string& from,const std::string& to) {
    size_t offset=0;
    while((offset=text.find(from,offset))!=std::string::npos) {
        text.replace(offset,from.size(),to);offset+=to.size();
    }
}
inline std::string PortableCommand(std::string command,const std::filesystem::path& source,
        const std::filesystem::path& relativeSource,const std::vector<std::filesystem::path>& includes) {
    Replace(command,"\""+source.string()+"\"","\"@Rtxpt/"+relativeSource.generic_string()+"\"");
    for(size_t i=0;i<includes.size();++i)
        Replace(command,"\""+includes[i].string()+"\"","\"@Include"+std::to_string(i)+"\"");
    return command;
}
inline void Export(const std::string& outputStem,const std::string& hash,
        const std::string& command,const std::string& portableCommand) {
    // Explicit build-time collection only; normal applications do no file I/O here.
    const char* directory=std::getenv("RTR_RTXPT_SHADER_MANIFEST_DIR");
    if(!directory || !*directory)return;
    std::filesystem::create_directories(directory);
    std::ofstream output(std::filesystem::path(directory)/(hash+".rtrdxc"),std::ios::binary);
    if(!output)throw std::runtime_error("Cannot export RTXPT shader compilation manifest");
    output<<outputStem<<'\n'<<hash<<'\n'<<command<<'\n'<<portableCommand<<'\n';
    if(!output)throw std::runtime_error("Cannot write RTXPT shader compilation manifest");
}
}
