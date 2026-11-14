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
    class FloatView {
        const uint8_t* values;size_t length;
    public:
        FloatView(const uint8_t* data,size_t count):values(data),length(count){}
        size_t size()const{return length;}bool empty()const{return !length;}
        const uint8_t* data()const{return values;}
        float operator[](size_t index)const{float result;std::memcpy(&result,values+index*4,4);return result;}
    };
    // The mesh decoder validates before retaining, so it can compute position
    // bounds in the same pass instead of reading that channel twice.
    FloatView readFloatSpan(){auto n=count();return FloatView(take(size_t(n)*4),n);}
    FloatView floatView(){
        const auto result=readFloatSpan();uint32_t invalid=0;
        for(size_t i=0;i<result.size();++i){
            uint32_t bits;std::memcpy(&bits,result.data()+size_t(i)*4,4);
            invalid|=uint32_t((bits&0x7f800000u)==0x7f800000u);
        }
        if(invalid)throw std::invalid_argument("Nonfinite resource input");
        return result;
    }
    std::vector<float> floats(){auto view=floatView();std::vector<float> result(view.size());
        std::memcpy(result.data(),view.data(),view.size()*4);return result;}
    std::vector<uint8_t> bytes(){auto n=count();auto p=take(n);return {p,p+n};}
    void finish(){if(cursor!=end)throw std::invalid_argument("Trailing resource packet data");}
};
