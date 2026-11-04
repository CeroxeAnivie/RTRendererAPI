#pragma once
#include <cstring>
#include <limits>
#include <type_traits>

// Bounds-checked, little-endian private transport; never retains a JVM address.
class RtrResourceReader {
    const uint8_t* cursor;const uint8_t* end;
public:
    RtrResourceReader(const void* data,size_t size):cursor(static_cast<const uint8_t*>(data)),end(cursor+size){}
    const uint8_t* take(size_t bytes){if(bytes>size_t(end-cursor))throw std::invalid_argument("Truncated resource packet");auto result=cursor;cursor+=bytes;return result;}
    template<class T>T value(){T result;std::memcpy(&result,take(sizeof(T)),sizeof(T));
        if constexpr(std::is_same_v<T,float>)if(!std::isfinite(result))throw std::invalid_argument("Nonfinite resource scalar");return result;}
    uint32_t count(){auto n=value<uint32_t>();if(n>size_t(end-cursor))throw std::invalid_argument("Invalid resource count");return n;}
    std::string text(){auto n=count();auto data=take(n);return std::string(reinterpret_cast<const char*>(data),n);}
    std::vector<float> floats(){auto n=count();auto data=take(size_t(n)*4);std::vector<float> result(n);std::memcpy(result.data(),data,size_t(n)*4);
        for(float x:result)if(!std::isfinite(x))throw std::invalid_argument("Nonfinite resource input");return result;}
    std::vector<uint8_t> bytes(){auto n=count();auto p=take(n);return {p,p+n};}
    void finish(){if(cursor!=end)throw std::invalid_argument("Trailing resource packet data");}
};
