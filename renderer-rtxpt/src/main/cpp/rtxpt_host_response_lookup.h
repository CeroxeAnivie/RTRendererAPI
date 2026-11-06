#pragma once
#include <array>

namespace rtr {
// Donut's vector default constructor intentionally leaves its components untouched.
// Legacy 512-float publications retain the remaining host-response lanes, so every
// lane must have a defined value before the first publication is validated.
template<class Pair> std::array<Pair,640> DefaultHostResponseLookup(){
    std::array<Pair,640> values;
    for(auto& value:values)value=Pair(0,0);
    for(int sky=0;sky<16;++sky)for(int local=0;local<16;++local)
        values[sky*16+local]=Pair(local/15.f,sky/15.f);
    return values;
}
}
