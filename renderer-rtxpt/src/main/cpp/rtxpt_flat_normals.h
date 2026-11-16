#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace RtrFlatNormals {
struct Channels {
    std::vector<float> positions,normals,tangents,uv;
    std::vector<uint32_t> indices;
    bool expanded=false;
};

// Match the old implicit flat normals, including the zero-area fallback.
// A vertex is shared only while all of its corner normals are identical.
#ifdef _MSC_VER
#pragma float_control(precise, on, push)
#pragma fp_contract(off)
#endif
template<class FloatView,class Index>
std::array<float,3> TriangleNormal(FloatView positions,uint32_t first,Index indexAt){
    const size_t a=size_t(indexAt(first))*3,b=size_t(indexAt(first+1))*3,c=size_t(indexAt(first+2))*3;
    const double ux=double(positions[b])-positions[a],uy=double(positions[b+1])-positions[a+1],uz=double(positions[b+2])-positions[a+2];
    const double vx=double(positions[c])-positions[a],vy=double(positions[c+1])-positions[a+1],vz=double(positions[c+2])-positions[a+2];
    const double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
    const double length=std::sqrt(nx*nx+ny*ny+nz*nz);
    if(length==0)return std::array<float,3>{0,0,1};
    return std::array<float,3>{float(nx/length),float(ny/length),float(nz/length)};
}

struct Plan {
    // Indexed meshes retain one normal per source vertex. Expanded output
    // retains one normal per triangle, while its source channels stay indexed.
    std::vector<float> normals;
    bool expanded=false;
};
template<class FloatView,class Index>
Plan PlanCanonical(FloatView positions,FloatView uv,uint32_t corners,Index indexAt){
    Plan result;const size_t vertices=positions.size()/3;
    bool indexed=uv.empty();
    if(indexed)result.normals.resize(vertices*3);
    for(uint32_t first=0;first<corners&&indexed;first+=3){
        const auto n=TriangleNormal(positions,first,indexAt);
        for(uint32_t corner=first;corner<first+3;++corner){
            auto* target=result.normals.data()+size_t(indexAt(corner))*3;
            if(target[0]==0&&target[1]==0&&target[2]==0)std::memcpy(target,n.data(),sizeof(n));
            else if(std::memcmp(target,n.data(),sizeof(n))!=0){indexed=false;break;}
        }
    }
    if(indexed){
        for(size_t vertex=0;vertex<vertices;++vertex)
            if(result.normals[vertex*3]==0&&result.normals[vertex*3+1]==0&&result.normals[vertex*3+2]==0){indexed=false;break;}
        if(indexed)return result;
    }
    result.expanded=true;result.normals.resize(corners);
    for(uint32_t first=0;first<corners;first+=3){
        const auto n=TriangleNormal(positions,first,indexAt);
        std::memcpy(result.normals.data()+first,n.data(),sizeof(n));
    }
    return result;
}

struct PackedPlan {std::vector<uint32_t> normals;bool expanded=false;};
template<class FloatView,class Index,class Pack>
PackedPlan PlanPackedCanonical(FloatView positions,FloatView uv,uint32_t corners,Index indexAt,bool independentQuads,Pack pack){
    PackedPlan result;
    if(!uv.empty()){
        result.expanded=true;result.normals.resize(corners/3);
        for(uint32_t first=0;first<corners;first+=3)result.normals[first/3]=pack(TriangleNormal(positions,first,indexAt));
        return result;
    }
    if(independentQuads){
        const auto quads=corners/6;bool indexed=true;
        result.normals.resize(size_t(quads)*2);
        for(uint32_t quad=0;quad<quads;++quad){
            const auto a=TriangleNormal(positions,quad*6,indexAt),b=TriangleNormal(positions,quad*6+3,indexAt);
            indexed&=std::memcmp(a.data(),b.data(),sizeof(a))==0;
            result.normals[quad*2]=pack(a);result.normals[quad*2+1]=pack(b);
        }
        if(indexed){
            result.normals.resize(size_t(quads)*4);
            // Backwards expansion keeps the first two values of every quad intact.
            for(size_t quad=quads;quad-->0;){const auto value=result.normals[quad*2];
                for(unsigned corner=0;corner<4;++corner)result.normals[quad*4+corner]=value;}
        }else result.expanded=true;
        return result;
    }
    const auto plan=PlanCanonical(positions,uv,corners,indexAt);result.expanded=plan.expanded;
    result.normals.resize(plan.normals.size()/3);
    for(size_t i=0;i<result.normals.size();++i)result.normals[i]=pack(std::array<float,3>{plan.normals[i*3],plan.normals[i*3+1],plan.normals[i*3+2]});
    return result;
}

template<class FloatView,class Index>
Channels Generate(FloatView positions,FloatView tangents,FloatView uv,uint32_t corners,Index indexAt){
    const auto vertices=positions.size()/3;
    const auto normal=[&](uint32_t first){return TriangleNormal(positions,first,indexAt);};
    Channels result;
    // Compare the complete normal, including signed zero. A zero vector
    // cannot be a generated normal, so it also marks unused source vertices.
    // UV-authored meshes preserve their old corner identity: coverage may use
    // shared indices to recognize a quad, so changing those would change shading.
    bool indexed=uv.empty();
    if(indexed)result.normals.resize(vertices*3);
    for(uint32_t first=0;first<corners&&indexed;first+=3){
        const auto n=normal(first);
        for(uint32_t corner=first;corner<first+3;++corner){
            auto* target=result.normals.data()+size_t(indexAt(corner))*3;
            if(target[0]==0&&target[1]==0&&target[2]==0)std::memcpy(target,n.data(),sizeof(n));
            else if(std::memcmp(target,n.data(),sizeof(n))!=0){indexed=false;break;}
        }
    }
    // Unreferenced positions did not participate in the old bounds or buffer
    // layout. Preserve the old representation for those meshes as well.
    if(indexed){
        for(size_t v=0;v<vertices;++v)if(result.normals[v*3]==0&&result.normals[v*3+1]==0&&result.normals[v*3+2]==0){indexed=false;break;}
        if(indexed){
            result.positions.resize(positions.size());
            std::memcpy(result.positions.data(),positions.data(),positions.size()*sizeof(float));
            if(!tangents.empty()){
                result.tangents.resize(tangents.size());
                std::memcpy(result.tangents.data(),tangents.data(),tangents.size()*sizeof(float));
            }
            result.indices.resize(corners);
            for(uint32_t corner=0;corner<corners;++corner)result.indices[corner]=indexAt(corner);
            return result;
        }
    }
    result.expanded=true;
    result.positions.resize(size_t(corners)*3);
    result.normals.resize(size_t(corners)*3);
    if(!tangents.empty())result.tangents.resize(size_t(corners)*4);
    if(!uv.empty())result.uv.resize(size_t(corners)*2);
    result.indices.resize(corners);
    for(uint32_t first=0;first<corners;first+=3){
        const auto n=normal(first);
        for(uint32_t corner=first;corner<first+3;++corner){
            const auto source=size_t(indexAt(corner)),target=size_t(corner);
            std::memcpy(result.positions.data()+target*3,positions.data()+source*12,12);
            std::memcpy(result.normals.data()+target*3,n.data(),sizeof(n));
            if(!tangents.empty())std::memcpy(result.tangents.data()+target*4,tangents.data()+source*16,16);
            if(!uv.empty())std::memcpy(result.uv.data()+target*2,uv.data()+source*8,8);
            result.indices[corner]=corner;
        }
    }
    return result;
}
#ifdef _MSC_VER
#pragma float_control(pop)
#endif
}
