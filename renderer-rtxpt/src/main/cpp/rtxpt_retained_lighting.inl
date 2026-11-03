// Included in HostPathTracer. Validation and allocation precede resident mutation.
std::vector<int64_t> managedLightIds;
std::unordered_map<const donut::engine::Light*,size_t> lightMirrorIndices;
std::array<uint64_t,16> lightingWork{};
uint32_t lightSlotCapacity=0,treeSlotCapacity=0;
struct LightGroups {
    std::vector<std::shared_ptr<donut::engine::SceneGraphNode>> buckets;
    std::unordered_map<donut::engine::SceneGraphNode*,uint32_t> indices;
    std::unordered_map<uint64_t,std::shared_ptr<donut::engine::SceneGraphNode>> branches;
    std::set<uint32_t> available;
} lightGroups;
template<class T> static void Grow(std::vector<T>& values,size_t count){if(values.capacity()<count){size_t capacity=std::max<size_t>(64,values.capacity());while(capacity<count)capacity*=2;values.reserve(capacity);}}
static void AttachManagedLight(const std::shared_ptr<donut::engine::SceneGraph>& graph,LightGroups& groups,
    const std::shared_ptr<donut::engine::SceneGraphNode>& root,const std::shared_ptr<donut::engine::SceneGraphNode>& leaf){
    if(groups.available.empty()){
        uint32_t index=uint32_t(groups.buckets.size());auto parent=root;
        // Fixed radix paths bound dirty traversal independently of resident lights.
        for(int shift=24;shift>=6;shift-=6){uint64_t key=(uint64_t(shift)<<32)|(index>>shift);auto entry=groups.branches.find(key);
            if(entry==groups.branches.end()){auto branch=std::make_shared<donut::engine::SceneGraphNode>();branch->SetName("lg"+std::to_string(key));graph->Attach(parent,branch);entry=groups.branches.emplace(key,std::move(branch)).first;}
            parent=entry->second;
        }
        auto bucket=std::make_shared<donut::engine::SceneGraphNode>();bucket->SetName("lb"+std::to_string(index));graph->Attach(parent,bucket);
        groups.indices.emplace(bucket.get(),index);groups.buckets.push_back(bucket);groups.available.insert(index);
    }
    auto index=*groups.available.begin();auto bucket=groups.buckets[index];graph->Attach(bucket,leaf);
    if(bucket->GetNumChildren()>=128)groups.available.erase(index);
}
void DetachManagedLight(const std::shared_ptr<donut::engine::SceneGraph>& graph,const std::shared_ptr<donut::engine::Light>& light){
    auto node=light->GetNode()->shared_from_this();auto bucket=lightGroups.indices.find(node->GetParent());
    if(bucket!=lightGroups.indices.end())lightGroups.available.insert(bucket->second);graph->Detach(node);
}
std::array<uint64_t,16> LightingWork() const{return lightingWork;}
donut::engine::SceneGraphNode* ManagedLightNode(int64_t id) const{return managedLightObjects.at(lightIndices.at(id))->GetNode();}

static void ValidateLight(const float* v,bool sun=false){
    for(size_t i=0;i<16;i++)if(!std::isfinite(v[i]))throw std::invalid_argument("Non-finite light record");
    int type=int(v[3]);
    if(float(type)!=v[3]||type<0||type>2||(sun&&type!=0)||v[7]<0||v[8]<0||v[9]<0||v[10]<0||v[11]<0
        ||(v[14]!=0&&v[14]!=1)||(type!=0&&v[7]<=0)||(type==2&&(v[12]<v[13]||v[12]>1||v[13]<-1)))
        throw std::invalid_argument("Invalid light record");
    if(type!=1&&std::abs(v[4]*v[4]+v[5]*v[5]+v[6]*v[6]-1.f)>.003f)throw std::invalid_argument("Light direction must be normalized");
}
static void UpdateLightObject(donut::engine::Light& light,const SceneLight& v){
    light.color=v.colorIntensity.xyz();light.SetPosition(double3(v.positionType.xyz()));
    if(v.positionType.w==0){auto& l=static_cast<donut::engine::DirectionalLight&>(light);l.irradiance=v.colorIntensity.w;l.SetDirection(double3(v.directionRange.xyz()));}
    else if(v.positionType.w==1){auto& l=static_cast<PointLightEx&>(light);l.intensity=v.colorIntensity.w;l.range=v.directionRange.w;}
    else{auto& l=static_cast<SpotLightEx&>(light);l.intensity=v.colorIntensity.w;l.range=v.directionRange.w;
        l.innerAngle=degrees(std::acos(std::clamp(v.coneShadow.x,-1.f,1.f)));l.outerAngle=degrees(std::acos(std::clamp(v.coneShadow.y,-1.f,1.f)));l.SetDirection(double3(v.directionRange.xyz()));}
    if(auto* m=dynamic_cast<LightExtension*>(&light)){m->HostManaged=true;m->CastsShadow=v.coneShadow.z!=0;}
}
static std::shared_ptr<donut::engine::Light> CreateLightObject(const SceneLight& v,bool sun){
    std::shared_ptr<donut::engine::Light> object;
    if(v.positionType.w==0){
        if(sun){auto l=std::make_shared<donut::engine::DirectionalLight>();l->angularSize=.53f;object=l;}
        else{auto l=std::make_shared<ManagedDirectional>();l->angularSize=0;object=l;}
    }else if(v.positionType.w==1){auto l=std::make_shared<PointLightEx>();l->radius=0;object=l;}
    else{auto l=std::make_shared<SpotLightEx>();l->radius=0;object=l;}
    return object; // Position/direction require the node installed by SetLeaf.
}
void AddLightMirror(const std::shared_ptr<donut::engine::Light>& light){lightMirrorIndices.emplace(light.get(),m_lights.size());m_lights.push_back(light);}
void RemoveLightMirror(const std::shared_ptr<donut::engine::Light>& light){
    auto entry=lightMirrorIndices.find(light.get());if(entry==lightMirrorIndices.end())throw std::logic_error("Missing resident light mirror");
    auto index=entry->second;
    if(index+1!=m_lights.size()){m_lights[index]=std::move(m_lights.back());lightMirrorIndices.at(m_lights[index].get())=index;}
    m_lights.pop_back();lightMirrorIndices.erase(entry);
}
void RebuildLightTree(){
    RtrDiagnostics::Scope scope(RtrDiagnostics::LightTree,sceneLights.size(),1);
    const auto begin=RtrDiagnostics::level.load()?RtrDiagnostics::Now():0;
    lightTree.Rebuild(sceneLights,&lightPowerNormalization);++lightIndexBuilds;++lightingWork[8];
    dirtyLightIndices.clear();dirtyTreeIndices.clear();uploadSunOnly=false;
    if(begin)lightIndexBuildNs+=RtrDiagnostics::Now()-begin;
}
void ApplyLighting(const std::vector<int64_t>& ids,const std::vector<float>& records,const std::array<float,4>& sky){
    RtrDiagnostics::Scope scope(RtrDiagnostics::Lighting,sceneLights.size(),ids.size());
    if(!GetScene()||!managedLighting)throw std::logic_error("Lighting requires a retained API scene");
    size_t count=ids.size();
    if(!count||records.size()!=count*16||ids[0]!=-1||count>size_t(NEEAT_MAX_LIGHTS-RTXPT_NEEAT_ENVMAP_QT_TOTAL_NODE_COUNT))throw std::invalid_argument("Invalid complete lighting batch");
    for(float v:sky)if(!std::isfinite(v)||v<0)throw std::invalid_argument("Invalid lighting sky");
    std::vector<SceneLight> next(count);std::vector<std::shared_ptr<donut::engine::Light>> objects;objects.reserve(count);
    auto graph=GetScene()->GetSceneGraph();auto root=std::make_shared<donut::engine::SceneGraphNode>();root->SetName("RTRLighting");
    LightGroups groups;
    std::unordered_map<int64_t,uint32_t> indices;indices.reserve(count);
    {
        RtrDiagnostics::Scope stage(RtrDiagnostics::LightObjects,count,count);
        for(uint32_t i=0;i<count;i++){
            if(i%256==0)RtrHostResources::PumpEvents();
            const float* v=&records[i*16];ValidateLight(v,i==0);
            if((i&&ids[i]<0)||!indices.emplace(ids[i],i).second)throw std::invalid_argument("Duplicate or invalid light ID");
            std::memcpy(&next[i],v,sizeof(SceneLight));auto object=CreateLightObject(next[i],i==0);
            // An orphan subtree is never registered in a candidate SceneGraph.
            auto node=std::make_shared<donut::engine::SceneGraphNode>();node->SetLeaf(object);node->SetName(i==0?"Sun":"l"+std::to_string(ids[i]));
            AttachManagedLight(graph,groups,root,node);
            UpdateLightObject(*object,next[i]);objects.push_back(std::move(object));
        }
    }
    {
        RtrDiagnostics::Scope stage(RtrDiagnostics::LightGraph,graph->GetLights().size(),count);
        if(managedLightRoot)graph->Detach(managedLightRoot);
        else{auto previous=graph->GetLights();for(auto& l:previous)if(l->GetLightType()!=LightType_Environment)graph->Detach(l->GetNode()->shared_from_this());}
        managedLightRoot=graph->Attach(graph->GetRootNode(),root);graph->Refresh(GetFrameIndex());
        m_lights=graph->GetLights();lightMirrorIndices.clear();lightMirrorIndices.reserve(m_lights.size());
        for(size_t i=0;i<m_lights.size();++i)lightMirrorIndices.emplace(m_lights[i].get(),i);
    }
    lightingWork[4]+=count;lightingWork[6]+=managedLightObjects.size();++lightingWork[13];++lightingWork[14];
    managedLightObjects=std::move(objects);managedLightIds=ids;lightIndices=std::move(indices);lightGroups=std::move(groups);
    sceneLights=std::move(next);sceneSky=float4(sky[0],sky[1],sky[2],sky[3]);RebuildLightTree();
    sceneLightsDirty=true;lightingCommitPending=true;lightingSceneDirty=true;lightingPolicyLogged=false;directionalStrataLogged=false;
    ConfigureManagedBackground();LightingHistory(true);++lightingBatchCount;lightingWork[0]=sceneLights.size();
    const char* freeze=std::getenv("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE");if(freeze&&std::string(freeze)=="1")g_sampleUIData.DbgFreezeRealtimeNoiseSeed=true;
}
void ApplyLightPatch(const std::vector<int64_t>& ids,const std::vector<float>& records,const std::vector<int64_t>& removals,
    const std::array<float,16>& sun,const std::array<float,4>& sky){
    RtrDiagnostics::Scope scope(RtrDiagnostics::Lighting,sceneLights.size(),ids.size()+removals.size());
    auto started=RtrDiagnostics::level.load()?RtrDiagnostics::Now():0;
    if(!managedLightRoot||sceneLights.empty()||records.size()!=ids.size()*16)throw std::invalid_argument("Invalid retained lighting patch");
    struct Prepared {int64_t id;SceneLight value;std::shared_ptr<donut::engine::Light> replacement;};
    std::vector<Prepared> prepared;prepared.reserve(ids.size());std::unordered_map<int64_t,bool> touched;touched.reserve(ids.size()+removals.size());
    std::vector<std::shared_ptr<donut::engine::SceneGraphNode>> preparedNodes;preparedNodes.reserve(ids.size());
    size_t additions=0;bool structural=!removals.empty(),abrupt=!removals.empty();
    {
        RtrDiagnostics::Scope stage(RtrDiagnostics::LightValidation,sceneLights.size(),ids.size()+removals.size());
        ValidateLight(sun.data(),true);for(float v:sky)if(!std::isfinite(v)||v<0)throw std::invalid_argument("Invalid lighting sky");
        for(auto id:removals)if(id<0||!lightIndices.count(id)||!touched.emplace(id,true).second)throw std::invalid_argument("Unknown or duplicate removed light ID");
        for(size_t i=0;i<ids.size();i++){
            if(ids[i]<0||!touched.emplace(ids[i],true).second)throw std::invalid_argument("Duplicate patch light ID");
            const auto* v=&records[i*16];ValidateLight(v);Prepared p;p.id=ids[i];std::memcpy(&p.value,v,sizeof(SceneLight));
            auto old=lightIndices.find(p.id);bool added=old==lightIndices.end();additions+=added;
            if(added||sceneLights[old->second].positionType.w!=p.value.positionType.w){
                auto node=std::make_shared<donut::engine::SceneGraphNode>();node->SetName("l"+std::to_string(p.id));
                p.replacement=CreateLightObject(p.value,false);node->SetLeaf(p.replacement);UpdateLightObject(*p.replacement,p.value);
                // Hold the orphan node strongly until graph attachment.
                preparedNodes.push_back(std::move(node));structural=true;abrupt=true;
            }else{preparedNodes.push_back(nullptr);abrupt|=Abrupt(sceneLights[old->second],p.value);}
            prepared.push_back(std::move(p));
        }
        size_t count=sceneLights.size()-removals.size()+additions;
        if(count>size_t(NEEAT_MAX_LIGHTS-RTXPT_NEEAT_ENVMAP_QT_TOTAL_NODE_COUNT))throw std::invalid_argument("Retained lighting capacity exceeded");
        Grow(sceneLights,count);Grow(managedLightIds,count);Grow(managedLightObjects,count);lightIndices.reserve(count);
        Grow(m_lights,m_lights.size()+additions);lightMirrorIndices.reserve(m_lights.size()+additions);
        lightTree.Reserve(count);dirtyLightIndices.reserve(dirtyLightIndices.size()+ids.size()+removals.size()+1);
        dirtyTreeIndices.reserve(dirtyTreeIndices.size()+(ids.size()+removals.size()+1)*128);
    }
    if(!sceneLightsDirty)uploadSunOnly=true;
    auto graph=GetScene()->GetSceneGraph();uint64_t visitedBefore=lightTree.visited;
    {
        RtrDiagnostics::Scope stage(RtrDiagnostics::LightGraph,sceneLights.size(),ids.size()+removals.size());
        for(auto id:removals){
            uint32_t slot=lightIndices.at(id),last=uint32_t(sceneLights.size()-1);auto old=managedLightObjects[slot];
            DetachManagedLight(graph,old);RemoveLightMirror(old);lightIndices.erase(id);lightTree.Remove(slot,dirtyTreeIndices);
            if(slot!=last){sceneLights[slot]=sceneLights[last];managedLightIds[slot]=managedLightIds[last];managedLightObjects[slot]=std::move(managedLightObjects[last]);lightIndices.at(managedLightIds[slot])=slot;dirtyLightIndices.push_back(slot);}
            sceneLights.pop_back();managedLightIds.pop_back();managedLightObjects.pop_back();++lightingWork[6];
        }
        for(size_t i=0;i<prepared.size();i++){
            auto& p=prepared[i];auto old=lightIndices.find(p.id);bool added=old==lightIndices.end();uint32_t slot=added?uint32_t(sceneLights.size()):old->second;
            if(p.replacement){
                if(!added){auto previous=managedLightObjects[slot];DetachManagedLight(graph,previous);RemoveLightMirror(previous);++lightingWork[6];}
                AttachManagedLight(graph,lightGroups,managedLightRoot,preparedNodes[i]);AddLightMirror(p.replacement);++lightingWork[4];
            }else{UpdateLightObject(*managedLightObjects[slot],p.value);++lightingWork[5];}
            if(added){lightIndices.emplace(p.id,slot);sceneLights.push_back(p.value);managedLightIds.push_back(p.id);managedLightObjects.push_back(p.replacement);lightTree.Insert(sceneLights,slot,lightPowerNormalization,dirtyTreeIndices);}
            else{sceneLights[slot]=p.value;if(p.replacement)managedLightObjects[slot]=p.replacement;lightTree.Refit(sceneLights,slot,lightPowerNormalization,dirtyTreeIndices);}
            dirtyLightIndices.push_back(slot);
        }
        if(structural){graph->Refresh(GetFrameIndex());++lightingWork[14];}
    }
    SceneLight nextSun;std::memcpy(&nextSun,sun.data(),sizeof(SceneLight));bool environmentChanged=std::memcmp(&sceneLights[0],&nextSun,sizeof(SceneLight))!=0;
    for(int a=0;a<4;a++)if(sceneSky[a]!=sky[a]){environmentChanged=true;abrupt|=std::abs(sceneSky[a]-sky[a])>std::max(.001f,std::max(sceneSky[a],sky[a])*.1f);}
    if(environmentChanged){abrupt|=Abrupt(sceneLights[0],nextSun);sceneLights[0]=nextSun;sceneSky=float4(sky[0],sky[1],sky[2],sky[3]);
        UpdateLightObject(*managedLightObjects[0],nextSun);lightTree.Refit(sceneLights,0,lightPowerNormalization,dirtyTreeIndices);dirtyLightIndices.push_back(0);ConfigureManagedBackground();}
    // Extreme HDR growth requires a deliberate normalization rebuild, not per-edit sorting.
    auto exceedsNormalization=[&](const SceneLight& v){
        double power=double(v.colorIntensity.w)*(.2126*v.colorIntensity.x+.7152*v.colorIntensity.y+.0722*v.colorIntensity.z)/lightPowerNormalization;
        return !std::isfinite(float(power))||power>1e25;
    };
    if(exceedsNormalization(nextSun)||std::any_of(prepared.begin(),prepared.end(),[&](const Prepared& p){return exceedsNormalization(p.value);}))RebuildLightTree();
    lightingWork[0]=sceneLights.size();++lightingWork[1];lightingWork[2]+=ids.size();lightingWork[3]+=removals.size();lightingWork[7]+=lightTree.visited-visitedBefore;
    lightDeltaRecords+=ids.size();lightTreeRefits+=ids.size()+(environmentChanged?1:0);
    sceneLightsDirty=true;lightingCommitPending=true;++lightingBatchCount;LightingHistory(abrupt);
    if(structural){lightingSceneDirty=true;lightingPolicyLogged=false;directionalStrataLogged=false;}
    if(started)lightingWork[15]=RtrDiagnostics::Now()-started;
}
void ApplyEnvironment(const std::array<float,16>& sun,const std::array<float,4>& sky){ApplyLightPatch({}, {}, {},sun,sky);}
