#pragma once
#include "rtxpt_retained_light_tree.h"
#include "rtxpt_diagnostics.h"
#include <array>
#include <cstring>
#include <set>
#include <unordered_map>
#include <unordered_set>
#define RTR_REGION_INCIDENCE 1

// Incremental treelet frontiers, stable hash slots and independent list blocks.
class RtrSparseRegions {
public:
    static constexpr int CellSize=8,CoordinateLimit=1048576,TreeletHeight=3;
    static constexpr size_t LinkBudget=1048576,CellBudget=131072,RootCellBudget=512;
    static constexpr uint32_t None=0xffffffff,Empty=0xffffffff,Tombstone=0xfffffffe,MaxPoolOrder=19;
    static constexpr uint32_t HashBuckets=uint32_t(CellBudget)*2,GlobalWordCapacity=262144;
    static constexpr size_t MaximumPackedNodes=HashBuckets+GlobalWordCapacity/16+(1u<<MaxPoolOrder);
    struct Cell {int32_t x,y,z;bool operator==(const Cell& b)const{return x==b.x&&y==b.y&&z==b.z;}};
    static_assert(sizeof(Cell)==12);
    static uint32_t Hash(Cell c){return (uint32_t(c.x)*73856093u)^(uint32_t(c.y)*19349663u)^(uint32_t(c.z)*83492791u);}
    struct HashCell {size_t operator()(Cell c)const{return Hash(c);}};
    struct Root {std::array<float,7> bounds{};std::vector<Cell> cells;std::vector<uint32_t> members,cellSlots;bool global=false;uint32_t globalSlot=0;};
    struct CellEntry {std::vector<uint32_t> roots,rootCellIndices;uint32_t slot=None,block=None,order=0;bool overflow=false;};
    struct Shape {
        std::array<float,7> bounds{};uint32_t parent=None,left=None,right=None;int height=-2,kind=-2;
        bool operator==(const Shape& b)const{return bounds==b.bounds&&parent==b.parent&&left==b.left&&right==b.right&&height==b.height&&kind==b.kind;}
    };
    std::unordered_map<uint32_t,Root> roots;
    std::unordered_map<Cell,CellEntry,HashCell> cells;
    std::vector<rtr::LightTreeNode> packed;
    std::vector<uint32_t> global,dirtyNodes;
    std::vector<uint8_t> queuedNodes;
    std::vector<Shape> shapes;
    std::vector<uint32_t> owners;
    std::vector<uint64_t> affectedEpochs,topologyEpochs;
    std::unordered_map<uint32_t,std::vector<Cell>> overflowByHome;
    std::vector<uint32_t> hashHoles;
    std::vector<uint32_t> publishRoots;
    uint64_t linkRemovals=0;
    uint64_t affectedEpoch=0;
    std::array<std::set<uint32_t>,MaxPoolOrder+1> freeBlocks;
    uint32_t buckets=0,globalWords=GlobalWordCapacity,poolNodes=0,tombstones=0;
    size_t links=0;
    bool dirty=false,full=false,indexed=true,initialized=false;
    uint64_t frontierVisited=0,fullPacks=0;
    uint64_t membershipBuilds=0,membershipNodes=0;
    uint64_t hashMoves=0,overflowRetries=0;
    // builds, coverage changes, roots, cells, links, global roots, bytes,
    // bounded hash-probe overflows, uploaded bytes.
    std::array<uint64_t,9> work{};
    size_t CpuBytes()const{
        size_t bytes=packed.capacity()*sizeof(rtr::LightTreeNode)+(global.capacity()+dirtyNodes.capacity()+owners.capacity()+hashHoles.capacity()+publishRoots.capacity())*4+queuedNodes.capacity()+shapes.capacity()*sizeof(Shape)+(affectedEpochs.capacity()+topologyEpochs.capacity())*8;
        for(const auto& [id,r]:roots)bytes+=sizeof(Root)+r.cells.capacity()*sizeof(Cell)+(r.members.capacity()+r.cellSlots.capacity())*4;
        for(const auto& [cell,e]:cells)bytes+=sizeof(CellEntry)+(e.roots.capacity()+e.rootCellIndices.capacity())*4;
        return bytes; // tracked payload capacity; unordered-map allocator metadata is excluded
    }
    void Clear(){
        roots.clear();cells.clear();global.clear();packed.clear();shapes.clear();owners.clear();affectedEpochs.clear();topologyEpochs.clear();affectedEpoch=0;dirtyNodes.clear();queuedNodes.clear();overflowByHome.clear();hashHoles.clear();
        for(auto& blocks:freeBlocks)blocks.clear();buckets=poolNodes=tombstones=0;globalWords=GlobalWordCapacity;links=0;
        dirty=full=true;indexed=true;initialized=false;for(size_t i=2;i<=6;i++)work[i]=0;
    }
    static std::array<float,7> Bounds(const rtr::LightTreeNode& n){return {n.minimum[0],n.minimum[1],n.minimum[2],n.maximum[0],n.maximum[1],n.maximum[2],n.range};}
    static bool CoverageBox(const std::array<float,7>& b,std::array<int,3>& lo,std::array<int,3>& hi,double& radius){
        radius=b[6];double margin=1e-4;
        for(int a=0;a<6;a++)margin=std::max(margin,std::abs(double(b[a]))*2e-6);radius+=margin;
        size_t total=1;
        for(int a=0;a<3;a++){
            double lower=std::floor((double(b[a])-radius)/CellSize),upper=std::floor((double(b[a+3])+radius)/CellSize);
            if(lower< -CoordinateLimit||upper>=CoordinateLimit||upper-lower+1>RootCellBudget)return false;
            lo[a]=int(lower);hi[a]=int(upper);total*=size_t(hi[a]-lo[a]+1);if(total>RootCellBudget)return false;
        }return true;
    }
    static std::vector<Cell> Coverage(const std::array<float,7>& b){
        std::array<int,3> lo{},hi{};double radius;if(!CoverageBox(b,lo,hi,radius))return {};
        std::vector<Cell> result;result.reserve(size_t(hi[0]-lo[0]+1)*(hi[1]-lo[1]+1)*(hi[2]-lo[2]+1));
        for(int z=lo[2];z<=hi[2];z++)for(int y=lo[1];y<=hi[1];y++)for(int x=lo[0];x<=hi[0];x++){
            const int xyz[3]={x,y,z};double squared=0;
            for(int a=0;a<3;a++){double lower=double(xyz[a])*CellSize,upper=lower+CellSize;
                double d=std::max({double(b[a])-upper,lower-double(b[a+3]),0.});squared+=d*d;}
            if(squared<=radius*radius)result.push_back({x,y,z});
        }return result;
    }
private:
    friend struct RtrSparseRegionsTestAccess;
    using ChangedCells=std::unordered_set<Cell,HashCell>;
    static Shape Describe(const rtr::RetainedLightTree& tree,const std::vector<rtr::LightTreeNode>& nodes,uint32_t id){
        Shape s;s.height=tree.heights[id];s.parent=tree.parents[id];
        if(s.height<0)return s;const auto& n=nodes[id];s.bounds=Bounds(n);s.kind=n.light>=0?1:0;
        if(!s.kind){s.left=uint32_t(n.left);s.right=uint32_t(n.right);}return s;
    }
    bool Eligible(const rtr::RetainedLightTree& tree,const std::vector<rtr::LightTreeNode>& nodes,uint32_t id)const{
        if(nodes[id].light>=0)return true;std::array<int,3> lo{},hi{};double radius;
        return tree.heights[id]<=TreeletHeight&&CoverageBox(Bounds(nodes[id]),lo,hi,radius);
    }
    static void Members(const std::vector<rtr::LightTreeNode>& nodes,uint32_t id,std::vector<uint32_t>& result){
        result.push_back(id);if(nodes[id].light<0){Members(nodes,uint32_t(nodes[id].left),result);Members(nodes,uint32_t(nodes[id].right),result);}
    }
    void Unlink(uint32_t id,Root& root,size_t index,ChangedCells& changed){
        const auto cell=root.cells[index];auto& entry=cells.at(cell);const uint32_t slot=root.cellSlots.at(index);
        if(slot>=entry.roots.size()||entry.roots[slot]!=id||entry.rootCellIndices.at(slot)!=index)throw std::logic_error("Broken regional incidence index");
        const auto moved=entry.roots.back(),movedIndex=entry.rootCellIndices.back();
        entry.roots[slot]=moved;entry.rootCellIndices[slot]=movedIndex;entry.roots.pop_back();entry.rootCellIndices.pop_back();
        if(slot<entry.roots.size())roots.at(moved).cellSlots.at(movedIndex)=slot;
        --links;++linkRemovals;changed.insert(cell);
    }
    uint32_t Link(uint32_t id,Cell cell,uint32_t index,ChangedCells& changed){
        auto& entry=cells[cell];const auto slot=uint32_t(entry.roots.size());
        entry.roots.push_back(id);entry.rootCellIndices.push_back(index);++links;changed.insert(cell);return slot;
    }
    void RemoveRoot(uint32_t id,ChangedCells& changed){
        auto old=roots.find(id);if(old==roots.end())return;auto& root=old->second;
        for(auto member:root.members)if(member<owners.size()&&owners[member]==id)owners[member]=None;
        if(root.global){uint32_t slot=root.globalSlot,moved=global.back();global[slot]=moved;global.pop_back();if(moved!=id)roots.at(moved).globalSlot=slot;}
        for(size_t index=0;index<root.cells.size();++index)Unlink(id,root,index,changed);
        roots.erase(old);
    }
    void UpdateRoot(const std::vector<rtr::LightTreeNode>& nodes,uint32_t id,ChangedCells& changed){
        const auto bound=Bounds(nodes[id]);auto old=roots.find(id);std::vector<uint32_t> members;
        // Bounds/refits do not change ownership. Keep the resident membership
        // list until a child link actually changes, instead of recursively
        // rebuilding and allocating it for every moving support update.
        if(old==roots.end()||topologyEpochs[id]==affectedEpoch){
            Members(nodes,id,members);++membershipBuilds;membershipNodes+=members.size();
            // Newly merged roots replace each previous child frontier once.
            for(auto member:members){uint32_t previous=owners[member];if(previous!=None&&previous!=id)RemoveRoot(previous,changed);}
            if(old!=roots.end()&&old->second.members!=members){
                for(auto member:old->second.members)if(owners[member]==id)owners[member]=None;
                old->second.members=members;for(auto member:members)owners[member]=id;
            }
        }
        if(old!=roots.end()){
            if(old->second.bounds==bound)return;
            auto coverage=Coverage(bound);
            if(!old->second.global&&old->second.cells==coverage){old->second.bounds=bound;return;}
            // Most moving supports retain nearly all receiver cells. Patch the
            // symmetric difference instead of deleting and reallocating every
            // cell/list shared by the old and new coverage.
            size_t newCells=0;for(auto cell:coverage)newCells+=!cells.count(cell);
            if(!old->second.global&&!coverage.empty()&&links-old->second.cells.size()+coverage.size()<=LinkBudget&&cells.size()+newCells<=CellBudget){
                auto less=[](Cell a,Cell b){return a.z!=b.z?a.z<b.z:a.y!=b.y?a.y<b.y:a.x<b.x;};
                std::vector<uint32_t> nextSlots(coverage.size());size_t a=0,b=0;
                while(a<old->second.cells.size()||b<coverage.size()){
                    if(a<old->second.cells.size()&&(b==coverage.size()||less(old->second.cells[a],coverage[b]))){
                        Unlink(id,old->second,a++,changed);
                    }else if(b<coverage.size()&&(a==old->second.cells.size()||less(coverage[b],old->second.cells[a]))){
                        nextSlots[b]=Link(id,coverage[b],uint32_t(b),changed);++b;
                    }else{nextSlots[b]=old->second.cellSlots[a];cells.at(coverage[b]).rootCellIndices.at(nextSlots[b])=uint32_t(b);++a;++b;}
                }
                old->second.bounds=bound;old->second.cells=std::move(coverage);old->second.cellSlots=std::move(nextSlots);++work[1];return;
            }
            if(members.empty())members=old->second.members;
            RemoveRoot(id,changed);
        }
        Root root;root.bounds=bound;root.members=std::move(members);root.cells=Coverage(bound);
        size_t newCells=0;for(auto cell:root.cells)newCells+=!cells.count(cell);
        if(root.cells.empty()||links+root.cells.size()>LinkBudget||cells.size()+newCells>CellBudget){root.cells.clear();root.global=true;root.globalSlot=uint32_t(global.size());global.push_back(id);}
        else{root.cellSlots.resize(root.cells.size());for(uint32_t index=0;index<root.cells.size();++index)root.cellSlots[index]=Link(id,root.cells[index],index,changed);}
        for(auto member:root.members)owners[member]=id;roots.emplace(id,std::move(root));++work[1];
    }
    void Sync(const rtr::RetainedLightTree& tree,const std::vector<rtr::LightTreeNode>& nodes,uint32_t id,
            ChangedCells& changed,bool force){
        ++frontierVisited;
        if(Eligible(tree,nodes,id)){UpdateRoot(nodes,id,changed);return;}
        if(roots.count(id)){RemoveRoot(id,changed);force=true;}
        const auto& n=nodes[id];for(uint32_t child:{uint32_t(n.left),uint32_t(n.right)})
            if(force||affectedEpochs[child]==affectedEpoch)Sync(tree,nodes,child,changed,force);
    }
    static bool MatchMembers(const std::vector<rtr::LightTreeNode>& nodes,uint32_t id,const std::vector<uint32_t>& members,size_t& at){
        if(at==members.size()||members[at++]!=id)return false;const auto& n=nodes[id];
        return n.light>=0||(MatchMembers(nodes,uint32_t(n.left),members,at)&&MatchMembers(nodes,uint32_t(n.right),members,at));
    }
    void ReconcileFrontier(const rtr::RetainedLightTree& tree,const std::vector<rtr::LightTreeNode>& nodes,ChangedCells& changed){
        std::vector<uint32_t> finalRoots,stack{tree.root};finalRoots.reserve(roots.size());
        while(!stack.empty()){auto id=stack.back();stack.pop_back();++frontierVisited;
            if(Eligible(tree,nodes,id)){finalRoots.push_back(id);topologyEpochs[id]=affectedEpoch;}
            else{stack.push_back(uint32_t(nodes[id].right));stack.push_back(uint32_t(nodes[id].left));}}
        // Rotations can exchange descendants between two surviving frontier
        // roots. An old owner must never delete a different final root that
        // this same transaction already updated. Plan the final set first.
        for(auto at=roots.begin();at!=roots.end();){auto id=at->first;++at;if(topologyEpochs[id]!=affectedEpoch)RemoveRoot(id,changed);}
        std::fill(owners.begin(),owners.end(),None);
        for(auto id:finalRoots){auto old=roots.find(id);size_t at=0;
            const bool same=old!=roots.end()&&MatchMembers(nodes,id,old->second.members,at)&&at==old->second.members.size();
            if(same){topologyEpochs[id]=0;for(auto member:old->second.members)owners[member]=id;}
            else topologyEpochs[id]=affectedEpoch;
            UpdateRoot(nodes,id,changed);
        }
    }
    void Mark(uint32_t index){
        dirty=true;if(full)return;
        if(queuedNodes.size()<=index)queuedNodes.resize(packed.size());
        if(!queuedNodes[index]){dirtyNodes.push_back(index);queuedNodes[index]=1;}
    }
    void WriteWord(uint32_t offset,uint32_t value){
        const uint32_t node=buckets+offset/16;auto* words=reinterpret_cast<unsigned char*>(&packed.at(node));
        uint32_t previous;std::memcpy(&previous,words+(offset%16)*4,4);
        if(previous!=value){std::memcpy(words+(offset%16)*4,&value,4);Mark(node);}
    }
    uint32_t Count(uint32_t slot)const{uint32_t result;std::memcpy(&result,&packed[slot].maximum[0],4);return result;}
    void Bucket(uint32_t slot,Cell cell,uint32_t start,uint32_t count){
        rtr::LightTreeNode next{};std::memcpy(next.minimum,&cell,12);std::memcpy(&next.left,&start,4);std::memcpy(&next.maximum[0],&count,4);
        if(std::memcmp(&packed[slot],&next,sizeof(next))){packed[slot]=next;Mark(slot);}
    }
    void ForgetOverflow(Cell cell,CellEntry& entry){
        if(!entry.overflow)return;
        auto found=overflowByHome.find(Hash(cell)&(buckets-1));
        if(found==overflowByHome.end())throw std::logic_error("Missing regional overflow home");
        auto& list=found->second;auto at=std::find(list.begin(),list.end(),cell);
        if(at==list.end())throw std::logic_error("Missing regional overflow cell");
        *at=list.back();list.pop_back();if(list.empty())overflowByHome.erase(found);entry.overflow=false;
    }
    void EraseHash(Cell cell,CellEntry& entry){
        ForgetOverflow(cell,entry);if(entry.slot==None)return;
        uint32_t hole=entry.slot,scan=(hole+1)&(buckets-1);
        // Every placed key is within 16 probes of its home. Move keys back
        // only when the hole lies on that key's probe chain, including wrap.
        for(uint32_t scanned=0;Count(scan)!=Empty;++scanned,scan=(scan+1)&(buckets-1)){
            if(scanned>=buckets)throw std::logic_error("Regional hash has no terminating slot");
            Cell next;std::memcpy(&next,packed[scan].minimum,12);const uint32_t home=Hash(next)&(buckets-1);
            if(((hole-home)&(buckets-1))>=((scan-home)&(buckets-1)))continue;
            packed[hole]=packed[scan];Mark(hole);cells.at(next).slot=hole;hole=scan;++hashMoves;
        }
        Bucket(hole,{INT32_MAX,INT32_MAX,INT32_MAX},0,Empty);entry.slot=None;hashHoles.push_back(hole);
    }
    void FreeBlock(uint32_t block,uint32_t order){
        while(order<MaxPoolOrder){uint32_t buddy=block^(1u<<order);auto i=freeBlocks[order].find(buddy);
            if(i==freeBlocks[order].end())break;freeBlocks[order].erase(i);block=std::min(block,buddy);++order;}
        freeBlocks[order].insert(block);
    }
    uint32_t AllocateBlock(uint32_t order){
        for(;;){
            for(uint32_t found=order;found<=MaxPoolOrder;found++)if(!freeBlocks[found].empty()){
                uint32_t block=*freeBlocks[found].begin();freeBlocks[found].erase(freeBlocks[found].begin());
                while(found>order){--found;freeBlocks[found].insert(block+(1u<<found));}return block;}
            if(poolNodes>=(1u<<MaxPoolOrder))throw std::length_error("Regional list storage budget exceeded");
            const uint32_t added=poolNodes?poolNodes:64,first=poolNodes;poolNodes+=added;
            packed.resize(size_t(buckets)+globalWords/16+poolNodes);
            uint32_t growOrder=0;while((1u<<growOrder)<added)++growOrder;FreeBlock(first,growOrder);
        }
    }
    void PublishCell(Cell cell,CellEntry& entry){
        if(entry.slot==None){
            uint32_t slot=Hash(cell)&(buckets-1),tombstone=None;
            for(uint32_t probe=0;probe<16;probe++,slot=(slot+1)&(buckets-1)){
                uint32_t count=Count(slot);if(count==Tombstone&&tombstone==None)tombstone=slot;
                if(count==Empty){entry.slot=tombstone==None?slot:tombstone;break;}
            }
            if(entry.slot==None)entry.slot=tombstone;
            if(entry.slot==None){++work[7];if(!entry.overflow){overflowByHome[Hash(cell)&(buckets-1)].push_back(cell);entry.overflow=true;}return;}
            if(Count(entry.slot)==Tombstone)--tombstones;
        }
        ForgetOverflow(cell,entry);
        uint32_t order=0;while((16u<<order)<entry.roots.size())++order;
        if(entry.block==None||entry.order<order){
            if(entry.block!=None)FreeBlock(entry.block,entry.order);entry.block=AllocateBlock(order);entry.order=order;
            // New CPU storage is zero, while retained GPU capacity is unspecified.
            // Root id zero must be uploaded even when WriteWord sees no CPU change.
            for(uint32_t i=0;i<(entry.roots.size()+15)/16;i++)Mark(buckets+globalWords/16+entry.block+i);
        }
        // Live incidence order is stable until O(1) swap removal. Sort only
        // the final transaction publication; never invalidate the backlinks.
        publishRoots.assign(entry.roots.begin(),entry.roots.end());std::sort(publishRoots.begin(),publishRoots.end());
        const uint32_t start=globalWords+entry.block*16;
        for(uint32_t i=0;i<publishRoots.size();++i)WriteWord(start+i,publishRoots[i]);
        Bucket(entry.slot,cell,start,uint32_t(entry.roots.size()));
    }
    void RecoverOverflow(){
        if(hashHoles.empty()||overflowByHome.empty()){hashHoles.clear();return;}
        std::vector<uint32_t> homes;homes.reserve(hashHoles.size()*16);
        for(auto hole:hashHoles)for(uint32_t distance=0;distance<16;++distance)homes.push_back((hole-distance)&(buckets-1));
        std::sort(homes.begin(),homes.end());homes.erase(std::unique(homes.begin(),homes.end()),homes.end());hashHoles.clear();
        // A failed insertion relies on a full 16-slot probe window to request
        // exact-tree fallback. Any newly empty window must reinsert its waiting
        // keys before publishing, otherwise an early empty would omit a source.
        for(auto home:homes){auto found=overflowByHome.find(home);if(found==overflowByHome.end())continue;
            auto waiting=std::move(found->second);overflowByHome.erase(found);
            for(auto cell:waiting){auto at=cells.find(cell);if(at==cells.end())continue;at->second.overflow=false;
                if(at->second.roots.empty())continue;++overflowRetries;PublishCell(cell,at->second);}
        }
    }
    void FullPack(uint32_t nextBuckets=HashBuckets){
        RtrDiagnostics::Scope initialize(RtrDiagnostics::SparseRegionInitialize,cells.size(),roots.size(),1,0,0,0,RtrDiagnostics::CapacityGrowth,MaximumPackedNodes*64);
        if(nextBuckets!=HashBuckets)throw std::logic_error("Regional hash layout must remain stable");
        buckets=HashBuckets;globalWords=GlobalWordCapacity;if(global.size()>globalWords)throw std::length_error("Regional global root budget exceeded");
        packed.reserve(MaximumPackedNodes);
        packed.assign(size_t(buckets)+globalWords/16,{});poolNodes=tombstones=0;
        overflowByHome.clear();hashHoles.clear();
        for(auto& blocks:freeBlocks)blocks.clear();
        for(uint32_t slot=0;slot<buckets;slot++){uint32_t empty=Empty;std::memcpy(&packed[slot].maximum[0],&empty,4);}
        for(auto& [cell,entry]:cells){entry.slot=entry.block=None;entry.order=0;entry.overflow=false;PublishCell(cell,entry);}
        for(uint32_t i=0;i<global.size();i++)WriteWord(i,global[i]);
        for(auto index:dirtyNodes)queuedNodes[index]=0;
        dirtyNodes.clear();dirty=full=true;++fullPacks;
    }
public:
    void Update(const rtr::RetainedLightTree& tree,const std::vector<rtr::LightTreeNode>& nodes,bool enabled,
            const std::vector<uint32_t>& nodeChanges,bool reset=false){
        if(!enabled||tree.root==rtr::RetainedLightTree::None){if(initialized||!packed.empty())Clear();return;}
        if(reset&&initialized)Clear();
        ChangedCells changed;const auto oldGlobal=global;
        const bool rebuilding=!initialized;owners.resize(nodes.size(),None);
        shapes.resize(nodes.size());
        affectedEpochs.resize(nodes.size());topologyEpochs.resize(nodes.size());
        if(++affectedEpoch==0){std::fill(affectedEpochs.begin(),affectedEpochs.end(),0);std::fill(topologyEpochs.begin(),topologyEpochs.end(),0);++affectedEpoch;}
        bool affected=false,topologyChanged=false;
        auto inspect=[&](uint32_t id){if(id>=nodes.size())return;auto shape=Describe(tree,nodes,id);
            if(rebuilding||!(shape==shapes[id])){
                const auto& old=shapes[id];
                const bool topology=rebuilding||shape.kind!=old.kind||shape.left!=old.left||shape.right!=old.right;
                topologyChanged|=topology;
                shapes[id]=shape;if(tree.heights[id]<0)RemoveRoot(id,changed);
                else for(uint32_t parent=id;parent!=None;parent=tree.parents[parent]){
                    if(affectedEpochs[parent]==affectedEpoch)break;affectedEpochs[parent]=affectedEpoch;affected=true;
                }
            }};
        if(rebuilding)for(uint32_t id=0;id<nodes.size();++id)inspect(id);
        else for(auto id:nodeChanges)inspect(id);
        if(rebuilding||topologyChanged)ReconcileFrontier(tree,nodes,changed);
        else if(affected)Sync(tree,nodes,tree.root,changed,false);
        initialized=true;
        const bool coverageChanged=!changed.empty();
        {RtrDiagnostics::Scope erase(RtrDiagnostics::SparseHashErase,changed.size());
        for(auto i=changed.begin();i!=changed.end();){auto entry=cells.find(*i);
            if(entry!=cells.end()&&entry->second.roots.empty()){
                if(entry->second.block!=None)FreeBlock(entry->second.block,entry->second.order);
                if(buckets)EraseHash(entry->first,entry->second);
                cells.erase(entry);i=changed.erase(i);
            }else ++i;}}
        const bool globalChanged=oldGlobal!=global;
        if(rebuilding||!buckets){
            FullPack();
        }else{
            RtrDiagnostics::Scope publish(RtrDiagnostics::SparseRegionPublish,changed.size(),roots.size());
            for(auto cell:changed)PublishCell(cell,cells.at(cell));
            {RtrDiagnostics::Scope recover(RtrDiagnostics::SparseHashRecover,hashHoles.size(),overflowByHome.size());RecoverOverflow();}
            if(globalChanged)for(uint32_t i=0;i<global.size();i++)WriteWord(i,global[i]);
        }
        indexed=!(global.size()>32&&global.size()*8>roots.size());
        if(rebuilding||coverageChanged||globalChanged)++work[0];
        work[2]=roots.size();work[3]=cells.size();work[4]=links;work[5]=global.size();work[6]=packed.size()*64;
    }
    void Uploaded(){for(auto index:dirtyNodes)queuedNodes[index]=0;dirtyNodes.clear();dirty=full=false;}
};
