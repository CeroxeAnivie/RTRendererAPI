#pragma once
#include "rtxpt_retained_light_tree.h"
#include <unordered_map>
#include <unordered_set>
#include <cstring>

namespace rtr::upstream {
// Original scene lights feed the original LightsBaker. The compact record/tree
// below is only the input of the independent adapter lighting evaluator.
class Lights final {
public:
    struct Record {dm::float4 positionType,directionRange,colorIntensity,coneShadow;};
    static_assert(sizeof(Record)==64);
    std::vector<Record> records;
    rtr::RetainedLightTree tree;
    nvrhi::BufferHandle buffer;
    uint32_t treeBase=0;

    void Reset(donut::engine::Scene* scene=nullptr) {
        if(scene&&m_root&&m_root->GetParent())scene->GetSceneGraph()->Detach(m_root);
        records.clear();m_ids.clear();m_slots.clear();m_objects.clear();m_root=nullptr;
        tree={};m_recordDirty.clear();m_treeDirty.clear();m_full=true;
    }
    bool Apply(const std::vector<int64_t>& ids,const std::vector<float>& values,
        const std::vector<int64_t>& removals,const std::vector<float>& sun,
        donut::engine::Scene& scene,bool adapterLighting) {
        if(values.size()!=ids.size()*16 || sun.size()!=16)throw std::invalid_argument("Invalid analytic light publication");
        Validate(sun.data(),true,adapterLighting);
        std::unordered_set<int64_t> touched;
        for(auto id:removals)if(id<0||!m_slots.count(id)||!touched.insert(id).second)throw std::invalid_argument("Unknown or duplicate analytic removal");
        for(size_t i=0;i<ids.size();++i){if(ids[i]<0||!touched.insert(ids[i]).second)throw std::invalid_argument("Duplicate analytic identity");Validate(values.data()+i*16,false,adapterLighting);}
        const auto graph=scene.GetSceneGraph();bool structural=false;
        if(!m_root){
            m_root=std::make_shared<donut::engine::SceneGraphNode>();m_root->SetName("AdapterLights");graph->Attach(graph->GetRootNode(),m_root);
            Record initial{};std::memcpy(&initial,sun.data(),64);records.push_back(initial);m_ids.push_back(-1);
            auto object=Make(initial);auto node=std::make_shared<donut::engine::SceneGraphNode>();node->SetName("AdapterSun");node->SetLeaf(object);graph->Attach(m_root,node);Update(*object,initial);m_objects.emplace(-1,std::move(object));
            double normalization=1;tree.Rebuild(records,&normalization);m_full=true;structural=true;
        }
        tree.BeginChanges();
        Record sunRecord;std::memcpy(&sunRecord,sun.data(),64);
        if(std::memcmp(&records[0],&sunRecord,64)!=0){records[0]=sunRecord;Update(*m_objects.at(-1),sunRecord);tree.Refit(records,0,1,m_treeDirty);m_recordDirty.push_back(0);}
        for(auto id:removals){
            const auto slot=m_slots.at(id),last=uint32_t(records.size()-1);
            graph->Detach(m_objects.at(id)->GetNode()->shared_from_this());m_objects.erase(id);m_slots.erase(id);tree.Remove(slot,m_treeDirty);
            if(slot!=last){records[slot]=records[last];m_ids[slot]=m_ids[last];m_slots.at(m_ids[slot])=slot;m_recordDirty.push_back(slot);}
            records.pop_back();m_ids.pop_back();structural=true;
        }
        for(size_t i=0;i<ids.size();++i){
            Record value;std::memcpy(&value,values.data()+i*16,64);const auto id=ids[i];auto found=m_slots.find(id);
            if(found==m_slots.end()){
                const auto slot=uint32_t(records.size());m_slots.emplace(id,slot);records.push_back(value);m_ids.push_back(id);
                auto object=Make(value);auto node=std::make_shared<donut::engine::SceneGraphNode>();node->SetName("AdapterLight"+std::to_string(id));node->SetLeaf(object);graph->Attach(m_root,node);Update(*object,value);m_objects.emplace(id,std::move(object));
                tree.Insert(records,slot,1,m_treeDirty);m_recordDirty.push_back(slot);structural=true;
            }else {const auto slot=found->second;if(std::memcmp(&records[slot],&value,64)==0)continue;
                if(records[slot].positionType.w!=value.positionType.w){
                    auto old=m_objects.at(id);auto node=old->GetNode()->shared_from_this();auto replacement=Make(value);node->SetLeaf(replacement);m_objects[id]=std::move(replacement);structural=true;
                }
                records[slot]=value;Update(*m_objects.at(id),value);tree.Refit(records,slot,1,m_treeDirty);m_recordDirty.push_back(slot);
            }
        }
        return structural;
    }
    void Upload(nvrhi::IDevice* device,nvrhi::ICommandList* commands){
        uint32_t recordCapacity=std::max(64u,m_recordCapacity),nodeCapacity=std::max(128u,m_nodeCapacity);
        while(recordCapacity<records.size())recordCapacity*=2;while(nodeCapacity<tree.nodes.size())nodeCapacity*=2;
        if(!buffer||recordCapacity!=m_recordCapacity||nodeCapacity!=m_nodeCapacity){
            nvrhi::BufferDesc desc;desc.byteSize=(size_t(recordCapacity)+nodeCapacity)*64;desc.structStride=64;desc.debugName="Adapter analytic inputs";
            desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;buffer=device->createBuffer(desc);
            if(!buffer)throw std::runtime_error("Could not allocate adapter analytic inputs");
            m_recordCapacity=recordCapacity;m_nodeCapacity=nodeCapacity;treeBase=recordCapacity;m_full=true;
        }
        if(m_full){if(!records.empty())commands->writeBuffer(buffer,records.data(),records.size()*64);if(!tree.nodes.empty())commands->writeBuffer(buffer,tree.nodes.data(),tree.nodes.size()*64,size_t(treeBase)*64);}
        else {UploadRuns(commands,records,m_recordDirty,0);UploadRuns(commands,tree.nodes,m_treeDirty,treeBase);}
        m_full=false;m_recordDirty.clear();m_treeDirty.clear();
    }
private:
    std::vector<int64_t> m_ids;
    std::unordered_map<int64_t,uint32_t> m_slots;
    std::unordered_map<int64_t,std::shared_ptr<donut::engine::Light>> m_objects;
    std::shared_ptr<donut::engine::SceneGraphNode> m_root;
    std::vector<uint32_t> m_recordDirty,m_treeDirty;
    uint32_t m_recordCapacity=0,m_nodeCapacity=0;bool m_full=true;
    template<class T>void UploadRuns(nvrhi::ICommandList* commands,const std::vector<T>& data,std::vector<uint32_t>& dirty,uint32_t base){
        std::sort(dirty.begin(),dirty.end());dirty.erase(std::unique(dirty.begin(),dirty.end()),dirty.end());
        for(size_t begin=0;begin<dirty.size();){if(dirty[begin]>=data.size()){++begin;continue;}size_t end=begin+1;
            while(end<dirty.size()&&dirty[end]<data.size()&&dirty[end]==dirty[end-1]+1)++end;
            commands->writeBuffer(buffer,data.data()+dirty[begin],(end-begin)*64,size_t(base+dirty[begin])*64);
            begin=end;
        }
    }
    static void Validate(const float* value,bool sun,bool adapterLighting){
        for(unsigned i=0;i<16;++i)if(!std::isfinite(value[i]))throw std::invalid_argument("Non-finite analytic light");
        const auto type=int(value[3]);
        if(type<0||type>2||float(type)!=value[3]||(sun&&type!=0)||value[7]<0||value[8]<0||value[9]<0||value[10]<0||value[11]<0
            ||(value[14]!=0&&value[14]!=1)||(type!=0&&value[7]<=0)||(type==2&&(value[12]<value[13]||value[12]>1||value[13]<-1)))
            throw std::invalid_argument("Invalid analytic light record");
        if(type!=1&&std::abs(value[4]*value[4]+value[5]*value[5]+value[6]*value[6]-1)>.003f)throw std::invalid_argument("Non-unit analytic direction");
        if(!adapterLighting&&value[14]==0&&value[11]>0)throw std::invalid_argument("Original beauty has no per-light shadow disable hook");
    }
    static std::shared_ptr<donut::engine::Light> Make(const Record& record){
        if(record.positionType.w==0)return std::make_shared<donut::engine::DirectionalLight>();
        if(record.positionType.w==1)return std::make_shared<PointLightEx>();
        return std::make_shared<SpotLightEx>();
    }
    static void Update(donut::engine::Light& light,const Record& record){
        light.color=record.colorIntensity.xyz();light.SetPosition(dm::double3(record.positionType.xyz()));
        if(record.positionType.w==0){auto& target=static_cast<donut::engine::DirectionalLight&>(light);target.irradiance=record.colorIntensity.w;target.angularSize=0;target.SetDirection(dm::double3(record.directionRange.xyz()));}
        else if(record.positionType.w==1){auto& target=static_cast<PointLightEx&>(light);target.intensity=record.colorIntensity.w;target.range=record.directionRange.w;target.radius=0;}
        else {auto& target=static_cast<SpotLightEx&>(light);target.intensity=record.colorIntensity.w;target.range=record.directionRange.w;target.radius=0;
            target.innerAngle=dm::degrees(std::acos(std::clamp(record.coneShadow.x,-1.f,1.f)));target.outerAngle=dm::degrees(std::acos(std::clamp(record.coneShadow.y,-1.f,1.f)));target.SetDirection(dm::double3(record.directionRange.xyz()));}
    }
};
}
