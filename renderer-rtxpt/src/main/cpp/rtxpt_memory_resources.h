#pragma once
#include <cstring>
#include <limits>
#include <type_traits>
#include <algorithm>
#include <array>
#if defined(_M_X64) || defined(__x86_64__)
#include <emmintrin.h>
#endif

// Validate whole channels in unaligned SSE lanes; scalar tails never read past
// a private input block. Rejection rules and all stored float bits are unchanged.
inline void RtrValidateFiniteChannel(const uint8_t* values,size_t count){
    size_t i=0;uint32_t invalid=0;
#if defined(_M_X64) || defined(__x86_64__)
    auto errors=_mm_setzero_si128();const auto exponent=_mm_set1_epi32(0x7f800000);
    for(;i+4<=count;i+=4){const auto bits=_mm_loadu_si128(reinterpret_cast<const __m128i*>(values+i*4));
        errors=_mm_or_si128(errors,_mm_cmpeq_epi32(_mm_and_si128(bits,exponent),exponent));}
    invalid=uint32_t(_mm_movemask_epi8(errors));
#endif
    for(;i<count;++i){uint32_t bits;std::memcpy(&bits,values+i*4,4);invalid|=uint32_t((bits&0x7f800000u)==0x7f800000u);}
    if(invalid)throw std::invalid_argument("Nonfinite resource input");
}
inline void RtrValidateBaselineChannel(const uint8_t* values,size_t count){
    size_t i=0;uint32_t invalid=0;
#if defined(_M_X64) || defined(__x86_64__)
    auto errors=_mm_setzero_ps();const auto zero=_mm_setzero_ps(),one=_mm_set1_ps(1.f);
    for(;i+4<=count;i+=4){const auto value=_mm_castsi128_ps(_mm_loadu_si128(reinterpret_cast<const __m128i*>(values+i*4)));
        errors=_mm_or_ps(errors,_mm_or_ps(_mm_cmpnle_ps(value,one),_mm_cmpnge_ps(value,zero)));}
    invalid=uint32_t(_mm_movemask_ps(errors));
#endif
    for(;i<count;++i){float value;std::memcpy(&value,values+i*4,4);invalid|=uint32_t(!(value>=0&&value<=1));}
    if(invalid)throw std::invalid_argument("Receiver baseline outside [0,1]");
}
// A fully checked independent-quad map proves every index in range. Generic
// topology uses unsigned comparisons, including forged UINT32_MAX values.
inline bool RtrValidateTriangleIndices(const uint8_t* values,uint32_t count,size_t vertices){
    if(!vertices||vertices>UINT32_MAX||!count||count%3)throw std::invalid_argument("Invalid triangle input extent");
    bool independent=vertices%4==0&&uint64_t(count)==vertices/4*6;
    if(independent){
        uint32_t i=0,difference=0;
#if defined(_M_X64) || defined(__x86_64__)
        auto errors=_mm_setzero_si128();
        const auto a=_mm_setr_epi32(0,1,2,2),b=_mm_setr_epi32(3,0,4,5),c=_mm_setr_epi32(6,6,7,4);
        for(;i+12<=count;i+=12){const auto base=_mm_set1_epi32(int(i/6*4));const auto* data=values+size_t(i)*4;
            errors=_mm_or_si128(errors,_mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(data)),_mm_add_epi32(base,a)));
            errors=_mm_or_si128(errors,_mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(data+16)),_mm_add_epi32(base,b)));
            errors=_mm_or_si128(errors,_mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(data+32)),_mm_add_epi32(base,c)));
        }
        difference=uint32_t(_mm_movemask_epi8(_mm_cmpeq_epi32(errors,_mm_setzero_si128())))!=0xffff;
#endif
        constexpr std::array<uint32_t,6> quad{0,1,2,2,3,0};
        for(;i<count;++i){uint32_t value;std::memcpy(&value,values+size_t(i)*4,4);difference|=value^(i/6*4+quad[i%6]);}
        if(!difference)return true;
    }
    uint32_t i=0,invalid=0;
#if defined(_M_X64) || defined(__x86_64__)
    const auto sign=_mm_set1_epi32(int(0x80000000u)),limit=_mm_set1_epi32(int(uint32_t(vertices)^0x80000000u));
    auto errors=_mm_setzero_si128();
    for(;i+4<=count;i+=4){const auto value=_mm_loadu_si128(reinterpret_cast<const __m128i*>(values+size_t(i)*4));
        errors=_mm_or_si128(errors,_mm_or_si128(_mm_cmpgt_epi32(_mm_xor_si128(value,sign),limit),_mm_cmpeq_epi32(_mm_xor_si128(value,sign),limit)));}
    invalid=uint32_t(_mm_movemask_epi8(errors));
#endif
    for(;i<count;++i){uint32_t value;std::memcpy(&value,values+size_t(i)*4,4);invalid|=uint32_t(value>=vertices);}
    if(invalid)throw std::invalid_argument("Triangle index exceeds vertices");return false;
}

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
        const auto result=readFloatSpan();RtrValidateFiniteChannel(result.data(),result.size());
        return result;
    }
    std::vector<float> floats(){auto view=floatView();std::vector<float> result(view.size());
        std::memcpy(result.data(),view.data(),view.size()*4);return result;}
    std::vector<uint8_t> bytes(){auto n=count();auto p=take(n);return {p,p+n};}
    void finish(){if(cursor!=end)throw std::invalid_argument("Trailing resource packet data");}
};

// Immutable channels may share an existing native owner. Reading through memcpy
// preserves exact bits even when a preceding wire string left a channel unaligned.
template<class T> class RtrInputSpan {
    const uint8_t* values=nullptr;size_t length=0;
public:
    RtrInputSpan()=default;
    RtrInputSpan(const uint8_t* data,size_t count):values(data),length(count){}
    size_t size()const{return length;}bool empty()const{return !length;}
    const uint8_t* data()const{return values;}
    T operator[](size_t index)const{T result;std::memcpy(&result,values+index*sizeof(T),sizeof(T));return result;}
    bool operator==(const RtrInputSpan& other)const{
        return length==other.length&&(!length||values==other.values||std::memcmp(values,other.values,length*sizeof(T))==0);
    }
};
struct RtrPositionBounds {std::array<float,3> minimum,maximum;};
inline RtrPositionBounds RtrValidatedPositionBounds(RtrResourceReader::FloatView values){
    if(values.empty()||values.size()%3)throw std::invalid_argument("Invalid position extent");
    const size_t vertices=values.size()/3;size_t vertex=0;uint32_t invalid=0;
    const float infinity=std::numeric_limits<float>::infinity();
    RtrPositionBounds result{{infinity,infinity,infinity},{-infinity,-infinity,-infinity}};
#if defined(_M_X64) || defined(__x86_64__)
    auto low=_mm_set1_ps(infinity),high=_mm_set1_ps(-infinity);
    auto invalidLanes=_mm_setzero_si128();const auto exponent=_mm_set1_epi32(0x7f800000);
    // The fourth lane is the following position's x. Keep it inside the span;
    // only xyz contributes bounds, and the tail validates that vertex again.
    for(;vertex+4<vertices;vertex+=4){
        const auto* bytes=values.data()+vertex*12;
        __m128 points[4];
        for(unsigned i=0;i<4;++i){
            points[i]=_mm_castsi128_ps(_mm_loadu_si128(reinterpret_cast<const __m128i*>(bytes+i*12)));
            const auto bits=_mm_castps_si128(points[i]);
            invalidLanes=_mm_or_si128(invalidLanes,_mm_cmpeq_epi32(_mm_and_si128(bits,exponent),exponent));
        }
        low=_mm_min_ps(low,_mm_min_ps(_mm_min_ps(points[0],points[1]),_mm_min_ps(points[2],points[3])));
        high=_mm_max_ps(high,_mm_max_ps(_mm_max_ps(points[0],points[1]),_mm_max_ps(points[2],points[3])));
    }
    alignas(16) float lows[4],highs[4];_mm_store_ps(lows,low);_mm_store_ps(highs,high);
    for(unsigned axis=0;axis<3;++axis){result.minimum[axis]=lows[axis];result.maximum[axis]=highs[axis];}
    invalid=uint32_t(_mm_movemask_epi8(invalidLanes));
#endif
    for(;vertex<vertices;++vertex)for(unsigned axis=0;axis<3;++axis){
        const auto value=values[vertex*3+axis];uint32_t bits;std::memcpy(&bits,&value,4);
        invalid|=uint32_t((bits&0x7f800000u)==0x7f800000u);
        // Donut min/max select the right operand on ties (including +/- zero).
        auto& low=result.minimum[axis];auto& high=result.maximum[axis];
        low=low<value?low:value;high=high>value?high:value;
    }
    if(invalid)throw std::invalid_argument("Nonfinite resource position");
    return result;
}
