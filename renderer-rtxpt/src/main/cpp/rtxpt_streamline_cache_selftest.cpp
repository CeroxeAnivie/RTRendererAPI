namespace sl { enum class Result { eOk, eFailure }; }
static int generation=1,lookups=0;
#define SL_FEATURE_FUN_IMPORT_STATIC(feature,func)
#include "rtxpt_streamline_cache.h"
// Use the same SDK wrapper shape, including its result type.
using PFun_cachedFeature=sl::Result();
static sl::Result featureOne(){return sl::Result::eOk;}
static sl::Result featureTwo(){return sl::Result::eFailure;}
static sl::Result importFeature(int,const char*,void*& function) {
    ++lookups;function=reinterpret_cast<void*>(generation==1?featureOne:featureTwo);return sl::Result::eOk;
}
#define slGetFeatureFunction importFeature
static sl::Result cachedFeature(){
    SL_FEATURE_FUN_IMPORT_STATIC(0,cachedFeature);
    return s_cachedFeature();
}
int main(){
    // Runtime checks are deliberate: Release builds compile out assert().
    for(int i=0;i<10000;++i)if(cachedFeature()!=sl::Result::eOk)return 1;
    if(lookups!=1)return 2;
    rtr::streamline::FunctionCaches::reset();generation=2;
    for(int i=0;i<10000;++i)if(cachedFeature()!=sl::Result::eFailure)return 3;
    if(lookups!=2)return 4;
    rtr::streamline::FunctionCaches::reset();
    rtr::streamline::FunctionCaches::reset();generation=1;
    if(cachedFeature()!=sl::Result::eOk||lookups!=3)return 5;
    return 0;
}
