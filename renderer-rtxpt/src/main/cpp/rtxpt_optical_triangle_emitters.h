#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <numbers>
#include <random>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

class RtrOpticalTriangleEmitters {
public:
    static constexpr uint32_t SampleAxis=256,SampleBudget=SampleAxis*SampleAxis;
    struct alignas(16) Row {
        std::array<float,4> originReach,edgeU,edgeV,normal,fluxPerSample;
        std::array<uint32_t,4> volumeSlots;
    };
    static_assert(sizeof(Row)==96);

    bool Empty()const{return rows.empty();}
    const std::vector<Row>& Rows()const{return rows;}
    const std::array<double,3>& FluxBound()const{return fluxBound;}
    bool NeedsUpload()const{return dirty;}
    void Uploaded(){dirty=false;}

    void Set(const std::vector<float>& records,const std::vector<int64_t>& volumes){
        constexpr size_t Stride=13,Capacity=4;
        const size_t count=records.size()/Stride;
        if(records.size()%Stride||volumes.size()!=count*Capacity)
            throw std::invalid_argument("Invalid optical triangle emitter publication");

        std::vector<size_t> emitting;
        for(size_t i=0;i<count;++i){
            const float* r=records.data()+i*Stride;
            const int64_t* ids=volumes.data()+i*Capacity;
            if(!std::all_of(r,r+Stride,[](float x){return std::isfinite(x);})||
                !(r[12]>0)||std::min({r[9],r[10],r[11]})<0)
                throw std::invalid_argument("Invalid optical triangle emitter coordinates, radiance or reach");
            for(size_t j=0;j<Capacity;++j)if(ids[j]<-1||
                (ids[j]>=0&&std::find(ids,ids+j,ids[j])!=ids+j))
                throw std::invalid_argument("Invalid or duplicate optical triangle emitter medium ID");
            const auto n=AreaNormal(r);
            const double area=.5*std::hypot(n[0],n[1],n[2]);
            const float flux=float(std::numbers::pi*area*std::max({r[9],r[10],r[11]}));
            if(!(float(area)>0)||!std::isfinite(float(area))||!std::isfinite(flux))
                throw std::invalid_argument("Invalid optical triangle emitter area or flux");
            if(flux>0)emitting.push_back(i);
        }

        std::vector<Row> next;
        std::vector<VolumeSeed> nextSeeds;
        std::array<double,3> nextBound{};
        const size_t sources=std::min(emitting.size(),size_t(SampleBudget));
        next.reserve(sources);
        std::mt19937 selector(0x7954d8abu);
        for(size_t row=0;row<sources;++row){
            size_t index=row,inverseProbability=1;
            if(emitting.size()>SampleBudget){
                // One sample per disjoint stratum. Its selected source has
                // probability 1/stratumSize, including uneven final strata.
                const size_t begin=uint64_t(row)*emitting.size()/sources;
                const size_t end=uint64_t(row+1)*emitting.size()/sources;
                index=std::uniform_int_distribution<size_t>(begin,end-1)(selector);
                inverseProbability=end-begin;
            }
            const size_t input=emitting[index];
            const float* r=records.data()+input*Stride;
            const auto n=AreaNormal(r);
            const double length=std::hypot(n[0],n[1],n[2]);
            // The GPU uses lane % sources, so this is the exact lane count for
            // this row. Cosine-weighted hemisphere sampling cancels the cosine.
            const size_t samples=SampleBudget/sources+(row<SampleBudget%sources);
            const double weight=std::numbers::pi*.5*length*double(inverseProbability)/double(samples);
            Row tri{{r[0],r[1],r[2],r[12]},{r[3],r[4],r[5],0},{r[6],r[7],r[8],0},
                {float(n[0]/length),float(n[1]/length),float(n[2]/length),0},{}, {}};
            tri.volumeSlots.fill(UINT32_MAX);
            for(size_t c=0;c<3;++c){
                tri.fluxPerSample[c]=float(weight*r[9+c]);
                if(!std::isfinite(tri.fluxPerSample[c]))
                    throw std::invalid_argument("Optical triangle emitter sample flux exceeds the float range");
                nextBound[c]+=double(tri.fluxPerSample[c])*double(samples);
            }
            for(uint32_t slot=0;slot<Capacity;++slot){
                const int64_t id=volumes[input*Capacity+slot];
                if(id>=0)nextSeeds.push_back({uint32_t(row),slot,"i"+std::to_string(id)});
            }
            next.push_back(tri);
        }
        rows=std::move(next);seeds=std::move(nextSeeds);fluxBound=nextBound;dirty=!rows.empty();
    }

    template<class ResolveSlot> bool ResolveVolumes(ResolveSlot resolveSlot){
        // Exterior rows have no lookups. Other rows are patched only when the
        // traced table actually moves their retained volume to a new slot.
        bool changed=false;
        for(const auto& seed:seeds){
            const auto resolved=resolveSlot(seed.name);
            auto& slot=rows[seed.row].volumeSlots[seed.slot];
            if(slot!=resolved){slot=resolved;dirty=true;changed=true;}
        }
        return changed;
    }

private:
    struct VolumeSeed {uint32_t row,slot;std::string name;};
    std::vector<Row> rows;
    std::vector<VolumeSeed> seeds;
    std::array<double,3> fluxBound{};
    bool dirty=false;

    static std::array<double,3> AreaNormal(const float* r){
        return {double(r[4])*r[8]-double(r[5])*r[7],double(r[5])*r[6]-double(r[3])*r[8],
            double(r[3])*r[7]-double(r[4])*r[6]};
    }
};
