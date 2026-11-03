#pragma once
#include <bcrypt.h>
#include "rtr_native_lighting_abi.h"

struct RtrNativeExtension {
    std::vector<uint8_t> code;
    std::string identity;
    uint32_t signals=0,minimumBytes=0;
    static std::string Sha256(const std::vector<uint8_t>& bytes){
        BCRYPT_ALG_HANDLE algorithm=nullptr;uint8_t digest[32]{};
        if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0)throw std::runtime_error("Cannot open extension hash provider");
        auto result=BCryptHash(algorithm,nullptr,0,const_cast<PUCHAR>(bytes.data()),ULONG(bytes.size()),digest,32);BCryptCloseAlgorithmProvider(algorithm,0);
        if(result<0)throw std::runtime_error("Cannot hash extension artifact");
        const char* hex="0123456789abcdef";std::string text;for(auto value:digest){text+=hex[value>>4];text+=hex[value&15];}return text;
    }
    static std::vector<uint8_t> Read(const std::filesystem::path& path,size_t maximum){
        const auto size=std::filesystem::file_size(path);if(size==0||size>maximum)throw std::invalid_argument("Extension artifact exceeds its size range");
        std::vector<uint8_t> result(size);std::ifstream stream(path,std::ios::binary);stream.read(reinterpret_cast<char*>(result.data()),std::streamsize(size));
        if(!stream)throw std::runtime_error("Cannot read extension artifact");return result;
    }
    static RtrNativeExtension Load(const std::filesystem::path& manifest,uint32_t requested,size_t applicationBytes){
        auto path=std::filesystem::canonical(manifest);auto document=Read(path,1024*1024);Json::Value root;Json::CharReaderBuilder builder;std::string errors;
        std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
        if(!reader->parse(reinterpret_cast<const char*>(document.data()),reinterpret_cast<const char*>(document.data()+document.size()),&root,&errors))throw std::invalid_argument("Invalid native-lighting extension manifest");
        if(root["format"].asString()!="rtr-native-lighting-extension"||root["formatVersion"].asUInt()!=1||root["abiVersion"].asUInt()!=1
            ||root["entryPoint"].asString()!="main"||root["shaderModel"].asString()!="cs_6_6"||root["constantsBytes"].asUInt()!=320
            ||root["threadGroup"].size()!=3||root["threadGroup"][0].asUInt()!=8||root["threadGroup"][1].asUInt()!=8||root["threadGroup"][2].asUInt()!=1
            ||root["shaderAbiSha256"].asString()!=RTR_NATIVE_LIGHTING_ABI_SHA256)throw std::invalid_argument("Native-lighting extension ABI does not match this runtime");
        RtrNativeExtension result;result.signals=root["signalsMask"].asUInt();result.minimumBytes=root["application"]["minimumBytes"].asUInt();
        if(result.signals==0||result.signals>15||(requested&~result.signals)!=0||result.minimumBytes%4!=0||result.minimumBytes>16777216
            ||root["application"]["multipleOf"].asUInt()!=4||root["application"]["maximumBytes"].asUInt()!=16777216
            ||applicationBytes<result.minimumBytes||applicationBytes>16777216||applicationBytes%4!=0)throw std::invalid_argument("Extension output mask or application publication is incompatible");
        if(!root["resources"].isArray()||root["resources"].empty())throw std::invalid_argument("Extension resource reflection is missing");
        std::set<std::pair<unsigned,std::string>> resources;
        for(const auto& resource:root["resources"]){unsigned space=resource["space"].asUInt();auto binding=resource["register"].asString();auto count=resource["count"].asString();
            bool allowed=false;
            if(space==0&&count=="1"){allowed=binding=="cb0"||binding=="s0";
                for(unsigned i=0;i<=11;i++)allowed|=binding=="t"+std::to_string(i);for(unsigned i=0;i<=4;i++)allowed|=binding=="u"+std::to_string(i);}
            else allowed=(space==1||space==2)&&binding=="t0"&&count=="unbounded";
            if(!allowed||!resources.emplace(space,binding).second)throw std::invalid_argument("Unsupported or duplicate extension resource binding");}
        auto file=root["dxil"]["file"].asString();auto relative=std::filesystem::u8path(file);
        if(relative.empty()||relative!=relative.filename()||relative=="."||relative=="..")throw std::invalid_argument("Extension DXIL must be adjacent to the manifest");
        auto binary=std::filesystem::canonical(path.parent_path()/relative);if(binary.parent_path()!=path.parent_path())throw std::invalid_argument("Extension DXIL resolves outside its bundle");
        result.code=Read(binary,64*1024*1024);result.identity=Sha256(result.code);
        if(result.code.size()<32||std::memcmp(result.code.data(),"DXBC",4)!=0||root["dxil"]["bytes"].asUInt64()!=result.code.size()
            ||root["dxil"]["sha256"].asString()!=result.identity)throw std::invalid_argument("Extension DXIL size, container or SHA-256 does not match");
        return result;
    }
};
