#pragma once
#include <nvrhi/nvrhi.h>
#include <stdexcept>

// Contents change through buffer uploads and bindless tables. Rebuild only
// when an actual bound handle, subresource or layout changes. NVRHI retains
// previously submitted binding sets until their command lists have completed.
struct RtrCachedBindings {
    nvrhi::BindingSetHandle value;
    nvrhi::IBindingSet* Get(nvrhi::IDevice* device,const nvrhi::BindingSetDesc& desc,nvrhi::IBindingLayout* layout){
        if(!value||value->getLayout()!=layout||!(*value->getDesc()==desc)){
            auto next=device->createBindingSet(desc,layout);
            if(!next)throw std::runtime_error("Could not allocate native compute bindings");
            value=next;
        }
        return value;
    }
};
