#pragma once
#include "rtxpt_process_policy.h"
#include "SampleCommon/ShaderCompilerUtils.h"
#include <fstream>
#include <set>
#include <sstream>

namespace rtr::upstream {
inline std::string ReadPrecompiledFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("Missing precompiled runtime input: " + path.string());
    std::ostringstream data; data << input.rdbuf();
    if (input.bad()) throw std::runtime_error("Cannot read precompiled runtime input: " + path.string());
    return data.str();
}
inline std::string DecodePrecompiledCommand(const std::string& text) {
    static const std::string alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result; unsigned accumulator=0; int bits=0;
    for (char value:text) {
        if(value=='=')break;
        const auto index=alphabet.find(value);
        if(index==std::string::npos)throw std::runtime_error("Invalid precompiled command encoding");
        accumulator=(accumulator<<6)|unsigned(index);bits+=6;
        if(bits>=8){bits-=8;result.push_back(char((accumulator>>bits)&255));}
    }
    return result;
}
inline std::string ReadPrecompiledText(const std::filesystem::path& path) {
    auto text=ReadPrecompiledFile(path);
    std::erase(text,'\r');
    return text;
}
inline void InstallPrecompiledShaders(const std::filesystem::path& bin) {
    RequirePrecompiledProcess();
    const auto root=bin/"PrecompiledShaders";
    std::istringstream sources(ReadPrecompiledText(root/"sources.tsv"));
    std::string sourceLine;
    if(!std::getline(sources,sourceLine)||sourceLine!="rtr-precompiled-sources-v1")
        throw std::runtime_error("Invalid precompiled source identity catalogue");
    size_t sourceCount=0;
    while(std::getline(sources,sourceLine)) {
        const auto tab=sourceLine.find('\t');
        if(tab!=64)throw std::runtime_error("Invalid precompiled source identity");
        const auto relative=sourceLine.substr(tab+1);
        if(relative.rfind("ShaderDynamic/Source/",0)!=0||relative.find("..")!=std::string::npos
                ||relative.find('\\')!=std::string::npos||relative.find(':')!=std::string::npos)
            throw std::runtime_error("Invalid precompiled source path");
        if(ShaderCompilerUtils::ComputeSha256Hex(ReadPrecompiledFile(bin/relative))!=sourceLine.substr(0,tab))
            throw std::runtime_error("Precompiled shader source identity mismatch: "+relative);
        ++sourceCount;
    }
    if(!sourceCount)throw std::runtime_error("Empty precompiled source identity catalogue");
    std::istringstream input(ReadPrecompiledText(root/"index.tsv"));
    std::string line;
    if(!std::getline(input,line)||line!="rtr-precompiled-runtime-v1")
        throw std::runtime_error("Invalid precompiled runtime catalogue");
    size_t count=0;
    while(std::getline(input,line)) {
        const auto first=line.find('\t'),second=line.find('\t',first+1);
        if(first==std::string::npos||second==std::string::npos)
            throw std::runtime_error("Invalid precompiled runtime entry");
        const auto prefix=line.substr(0,first),digest=line.substr(first+1,second-first-1);
        if(prefix.empty()||prefix.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_/")!=std::string::npos
                ||digest.size()!=64||digest.find_first_not_of("0123456789abcdef")!=std::string::npos)
            throw std::runtime_error("Invalid precompiled runtime identity");
        auto command=DecodePrecompiledCommand(line.substr(second+1));
        size_t position=0; bool replaced=false;
        while((position=command.find("${BIN}",position))!=std::string::npos){
            command.replace(position,6,bin.string());position+=bin.string().size();replaced=true;
        }
        if(!replaced)throw std::runtime_error("Precompiled runtime command has no relocatable root");
        const auto payload=ReadPrecompiledFile(root/"blobs"/(digest+".bin"));
        if(ShaderCompilerUtils::ComputeSha256Hex(payload)!=digest)
            throw std::runtime_error("Precompiled shader SHA-256 mismatch: "+prefix);
        const auto target=bin/"ShaderDynamic/Bin/dxil"/(prefix+"_"+
            ShaderCompilerUtils::ComputeSha256Hex(command).substr(0,24)+".bin");
        std::filesystem::create_directories(target.parent_path());
        // Never promote an unknown or corrupted existing cache binary.
        if(!std::filesystem::exists(target)||ReadPrecompiledFile(target)!=payload){
            auto temporary=target;temporary+="."+std::to_string(GetCurrentProcessId())+".install";
            {std::ofstream output(temporary,std::ios::binary|std::ios::trunc);
                output.write(payload.data(),payload.size());
                if(!output)throw std::runtime_error("Cannot install precompiled shader: "+target.string());}
            if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)) {
                const auto error=GetLastError();std::filesystem::remove(temporary);
                throw std::runtime_error("Cannot publish precompiled shader; Win32="+std::to_string(error));
            }
        }
        std::filesystem::last_write_time(target,std::filesystem::file_time_type::clock::now());
        ++count;
    }
    if(!count)throw std::runtime_error("Empty precompiled runtime catalogue");
    donut::log::info("Precompiled shaders installed: %zu; runtime compiler processes prohibited",count);
}

class PrecompiledGlobals {
public:
    void Check(const std::vector<donut::engine::ShaderMacro>& macros) {
        if(m_names.empty()) {
            std::istringstream input(ReadPrecompiledText(donut::app::GetDirectoryWithExecutable()/"PrecompiledShaders/globals.txt"));
            std::string line;
            if(!std::getline(input,line)||line!="rtr-precompiled-globals-v1"||!std::getline(input,line))
                throw std::runtime_error("Invalid precompiled global profile catalogue");
            std::istringstream names(line);while(std::getline(names,line,';'))m_names.push_back(line);
            while(std::getline(input,line))m_signatures.insert(line);
            if(m_names.empty()||m_signatures.empty())throw std::runtime_error("Empty precompiled global profiles");
        }
        if(std::equal(macros.begin(),macros.end(),m_previous.begin(),m_previous.end(),
            [](const auto& a,const auto& b){return a.name==b.name&&a.definition==b.definition;}))return;
        std::string signature;
        for(const auto& name:m_names) {
            const auto found=std::find_if(macros.begin(),macros.end(),[&](const auto& m){return m.name==name;});
            if(found==macros.end())throw std::runtime_error("Missing upstream shader macro: "+name);
            signature+=name+"="+found->definition+"\n";
        }
        if(!m_signatures.contains(ShaderCompilerUtils::ComputeSha256Hex(signature)))
            throw std::runtime_error("RTXPT configuration has no build-time shader profile; runtime compilation is prohibited. Macros:\n"+signature);
        m_previous=macros;
    }
private:
    std::vector<std::string> m_names;
    std::set<std::string> m_signatures;
    std::vector<donut::engine::ShaderMacro> m_previous;
};
}
