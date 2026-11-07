#pragma once

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include "rtxpt_affine_adapter.h"

#include <donut/core/vfs/VFS.h>
#include <donut/engine/Scene.h>
#include <donut/engine/TextureCache.h>
#include <donut/engine/DescriptorTableManager.h>
#include "SampleCommon/ExtendedScene.h"
#include "Materials/MaterialsBaker.h"
#include <nvrhi/common/misc.h>
#include <donut/shaders/bindless.h>
#include "rtxpt_memory_resources.h"
#include "rtxpt_diagnostics.h"

namespace rtr::upstream {
// Extend the documented Scene subclass boundary; expose no SDK type to Java.
// The base uploader still creates buffers/descriptors and packs geometry rows.
class SceneBufferUpload : public donut::engine::Scene {
public:
    static void Run(donut::engine::Scene& scene,nvrhi::ICommandList* commands){
        // Form the pointer through the derived extension, then retain virtual
        // dispatch to ExtendedScene's original (private) override. No downcast.
        const auto upload=&SceneBufferUpload::CreateMeshBuffers;
        (scene.*upload)(commands);
    }
};
class ManagedScene final : public ExtendedScene {
public:
    using ExtendedScene::ExtendedScene;
    void RefreshAttributes(nvrhi::ICommandList* commands,
        const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){
        SceneBufferUpload::Run(*this,commands);
        for(const auto& mesh:meshes)UpdateGeometry(mesh);
        WriteGeometryBuffer(commands);
    }
private:
    // Original RefreshBuffers owns CPU capacities, dense indices and uploads.
    // Its factories run only after the corresponding public graph count has
    // grown past capacity (Scene.cpp:617-635). Match its 1024-row rounding and
    // ExtendedScene's final descriptors without allocating a throwaway base
    // buffer just to obtain that descriptor (ExtendedScene.cpp:353-371).
    nvrhi::BufferHandle CreateTableBuffer(size_t count,uint32_t stride,
        const char* name,bool instances=false){
        nvrhi::BufferDesc desc;
        desc.byteSize=nvrhi::align<size_t>(count,1024)*stride;
        desc.debugName=name;
        desc.structStride=instances&&m_Device->getGraphicsAPI()==nvrhi::GraphicsAPI::D3D11?0:stride;
        desc.canHaveRawViews=true;
        desc.canHaveUAVs=true;
        desc.isVertexBuffer=instances;
        desc.initialState=nvrhi::ResourceStates::NonPixelShaderResource;
        desc.keepInitialState=true;
        return m_Device->createBuffer(desc);
    }
    nvrhi::BufferHandle CreateMaterialBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetMaterials().size(),sizeof(MaterialConstants),"BindlessMaterials");
    }
    nvrhi::BufferHandle CreateGeometryBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetGeometryCount(),sizeof(GeometryData),"BindlessGeometry");
    }
    nvrhi::BufferHandle CreateInstanceBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetMeshInstances().size(),sizeof(InstanceData),"Instances",true);
    }
};

// Converts the private, synchronous resource packet into original scene objects.
// GPU allocation, upload pages, descriptors and AS execution use original
// Scene/NVRHI/DescriptorTable services. This class only maps authored identities.
// Immutable texture generations use NVRHI directly: file TextureCache marks its
// images permanently ShaderResource and cannot represent copy-on-write updates.
class Resources final {
public:
    struct Changes {
        bool topology = false;
        bool materials = false;
        bool transforms = false;
        bool textures = false;
        bool attributes = false;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> retiredMeshes;
        std::vector<std::shared_ptr<donut::engine::BufferGroup>> retiredBuffers;
        std::vector<nvrhi::rt::AccelStructHandle> retiredStructures;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> deformedMeshes;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> reshapedMeshes;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> buildMeshes;
        std::vector<std::shared_ptr<donut::engine::LoadedTexture>> retiredTextures;
        bool existingMaterialsChanged = false;
        bool materialDomainsChanged = false;
        bool coverageChanged = false;
        bool requiresCoverageRebuild = false;
        std::vector<std::string> retiredInstances;
        std::vector<std::string> retiredGeometries;
    };

    void SetTextureResources(nvrhi::IDevice* device,
        std::shared_ptr<donut::engine::DescriptorTableManager> descriptors) {
        m_textureDevice=device;m_textureDescriptors=std::move(descriptors);
    }
    bool HasInstance(const std::string& name) const { return m_nodes.contains(name); }
    // Full-upload count/bytes, rectangle count/bytes, GPU-copy bytes, identical
    // generation count, sampler-only count. Actual commands, not predicted work.
    const std::array<uint64_t,7>& TextureWork() const {return m_textureWork;}
    // Old-mesh tangent revalidation visits, and redundant dependency iterations
    // skipped because the normal-mapping requirement was already satisfied.
    const std::array<uint64_t,2>& ReferenceWork() const {return m_referenceWork;}
    // Lifetime work counts: existing-node pose packets, their nodes, empty packets.
    const std::array<uint64_t,3>& PoseWork() const {return m_poseWork;}
    std::array<double,24> InstanceTransformState(const std::string& name) const {
        auto node=m_nodes.find(name);
        if(node==m_nodes.end())throw std::invalid_argument("Unknown adapter instance: "+name);
        const auto& current=node->second.node->GetLocalToWorldTransformFloat();
        const auto& previous=node->second.node->GetPrevLocalToWorldTransformFloat();
        std::array<double,24> result{};
        for(unsigned row=0;row<3;++row){
            for(unsigned col=0;col<3;++col){
                result[row*4+col]=current.m_linear[col][row];
                result[12+row*4+col]=previous.m_linear[col][row];
            }
            result[row*4+3]=current.m_translation[row];
            result[12+row*4+3]=previous.m_translation[row];
        }
        return result;
    }
    std::array<double,2> MeshResourceState(const std::string& name)const{
        const auto& mesh=*m_meshes.at(m_nodes.contains(name)?m_nodes.at(name).mesh:name);
        return {double(mesh.accelStruct?mesh.accelStruct->getDeviceAddress():0),
            double(reinterpret_cast<uintptr_t>(mesh.buffers->vertexBuffer.Get()))};
    }
    size_t GeometryCorners(const std::string& name) const {
        const auto separator=name.rfind(':');
        if(separator!=std::string::npos){
            const auto mesh=m_meshes.find(name.substr(0,separator));size_t index=0;
            const auto first=name.data()+separator+1,last=name.data()+name.size();
            const auto parsed=std::from_chars(first,last,index);
            if(mesh!=m_meshes.end()&&parsed.ec==std::errc()&&parsed.ptr==last&&index<mesh->second->geometries.size())
                return mesh->second->geometries[index]->numIndices;
        }
        throw std::invalid_argument("Unknown adapter geometry: "+name);
    }

    // Enable only when the consumer's own pass explicitly implements every
    // returned policy. The original beauty material sampler is never replaced.
    void SetExternalTextureSampling(bool enabled) { m_externalTextureSampling = enabled; }

    // base, normal, metal/roughness, emissive. Bit 0/1: clamp U/V; bit 2: nearest.
    // This is transport metadata for a consumer-owned pass, not Material.flags.
    std::array<uint32_t, 4> TextureSamplingPolicies(const std::string& materialName) const {
        auto material = m_materials.find(materialName);
        if (material == m_materials.end())
            throw std::invalid_argument("Unknown sampling material: " + materialName);
        std::array<uint32_t, 4> result{};
        for (size_t slot = 0; slot < result.size(); ++slot) {
            const auto id = material->second.textures[slot];
            if (id < 0) continue;
            const auto& texture = m_textures.at(id);
            result[slot] = texture.u | (texture.v << 1) | ((texture.filter == 0 ? 1u : 0u) << 2);
        }
        return result;
    }

    // Call only after discarding the corresponding original scene, on its owner
    // thread. Never retain an ID mapping into a newly loaded scene graph.
    void Reset() {
        m_nodes.clear();
        m_meshes.clear();
        m_meshSources.clear();
        m_materials.clear();
        m_textures.clear();
        m_meshNodes.clear();
        m_materialMeshes.clear();
        m_textureMaterials.clear();
        m_pendingMaterials.clear();
        m_pendingTextureMaterials.clear();
        m_pendingTransforms.clear();
        m_graph.reset();
    }

    // Apply changed poses once, immediately before the original frame refresh.
    // Structural/material preparation may refresh the graph earlier; publishing
    // existing-node poses there would make that second refresh erase prevTransform.
    void ApplyPendingTransforms() {
        for(const auto& [name,input]:m_pendingTransforms)
            if(auto node=m_nodes.find(name);node!=m_nodes.end())
                SetPose(node->second,input);
        m_pendingTransforms.clear();
    }

    // Donut Material has no IOR/volume fields. After the original SceneLoaded /
    // MaterialsBaker lifecycle has created PTMaterial, map those transport fields
    // through the original, public PTMaterial data and its dirty contract.
    void ConfigureMaterials(bool sceneReloaded = false) {
        auto configure = [&](const std::string& name, MaterialEntry& entry) {
            RtrDiagnostics::Scope materialRow(RtrDiagnostics::SceneMaterialRows,1,0,1,0,
                RtrDiagnostics::Identity(name),0,RtrDiagnostics::Material);
            auto material = std::dynamic_pointer_cast<MaterialEx>(entry.material);
            if (!material || !material->PTMaterial)
                throw std::runtime_error("Original material bake has not completed: " + name);
            auto& target = *material->PTMaterial;
            // These are authored API parameters, mapped to the public native
            // data model. The baker keeps its GPUDataIndex/permutation and
            // performs FillData/upload on GPUDataDirty in its ordinary Update.
            // ImportFromDonut is private and is deliberately not copied here.
            target.BaseOrDiffuseColor=material->baseOrDiffuseColor;
            target.EmissiveColor=material->emissiveColor;
            target.Opacity=material->opacity;
            target.Roughness=material->roughness;
            target.Metalness=material->metalness;
            target.TransmissionFactor=material->transmissionFactor;
            target.AlphaCutoff=material->alphaCutoff;
            target.EnableAlphaTesting=material->domain==donut::engine::MaterialDomain::AlphaTested
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaTested;
            target.EnableTransmission=material->domain==donut::engine::MaterialDomain::Transmissive
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaBlended
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaTested;
            target.IoR = entry.ior;
            // Public upstream material controls (Docs/Design.md, Path Decomposition).
            // Preserve its opt-out default; consumers explicitly select optical layers.
            target.PSDExclude = entry.psdExclude;
            target.PSDDominantDeltaLobe = entry.psdDominant;
            target.PSDBlockMotionVectorsAtSurfaceType = entry.psdBlockMotionVectors;
            if (entry.boundary) {
                target.VolumeAttenuationColor = donut::math::float3(
                    std::exp(-entry.absorption.x), std::exp(-entry.absorption.y),
                    std::exp(-entry.absorption.z));
                target.VolumeAttenuationDistance = 1.f;
                target.NestedPriority = entry.priority;
            } else {
                target.VolumeAttenuationColor=donut::math::float3(1.f);
                target.VolumeAttenuationDistance=std::numeric_limits<float>::max();
                target.NestedPriority=PTMaterial::kMaterialMaxNestedPriority;
            }
            target.GPUDataDirty = true;
        };
        if (sceneReloaded) {
            // A complete original bake replaces every PTMaterial; reapply only
            // the transport fields unavailable on Donut Material in that case.
            for (auto& [name, entry] : m_materials) configure(name, entry);
        } else {
            for (const auto& name : m_pendingMaterials)
                if (auto i = m_materials.find(name); i != m_materials.end()) configure(name, i->second);
        }
        m_pendingMaterials.clear();
    }

    // Existing texture identities do not change a PT material's shader features.
    // Rebind through the public PTTexture adapter and GPUDataDirty contract;
    // the original baker remains the owner of material GPU rows and uploads.
    void RebindMaterialTextures(const std::filesystem::path& mediaPath) {
        for(const auto& name:m_pendingTextureMaterials) {
            auto resident=m_materials.find(name);
            if(resident==m_materials.end())continue;
            auto material=std::dynamic_pointer_cast<MaterialEx>(resident->second.material);
            if(!material||!material->PTMaterial)
                throw std::logic_error("Original material must be imported before rebinding textures");
            auto& target=*material->PTMaterial;
            target.BaseTexture.InitFromLoadedTexture(material->baseOrDiffuseTexture,true,false,mediaPath);
            target.NormalTexture.InitFromLoadedTexture(material->normalTexture,false,true,mediaPath);
            target.OcclusionRoughnessMetallicTexture.InitFromLoadedTexture(
                material->metalRoughOrSpecularTexture,material->useSpecularGlossModel,false,mediaPath);
            target.EmissiveTexture.InitFromLoadedTexture(material->emissiveTexture,true,false,mediaPath);
            target.GPUDataDirty=true;
        }
        m_pendingTextureMaterials.clear();
    }

    Changes Apply(const void* bytes, size_t size, donut::engine::Scene& scene,
        const std::function<nvrhi::ICommandList*()>& acquireCommands, uint32_t frameIndex,
        const std::function<void(bool)>& beforeStructural = {}) {
        if (!bytes || !size || !acquireCommands)
            throw std::invalid_argument("A resource packet and command-list provider are required");
        auto graph = scene.GetSceneGraph();
        if (!graph || !graph->GetRootNode())
            throw std::logic_error("Create the original empty scene graph before applying resources");
        if (auto bound = m_graph.lock(); bound && bound != graph)
            throw std::logic_error("Reset resource identities before replacing the original scene");

        // Decode and validate the complete packet before changing any scene object.
        RtrDiagnostics::Scope applyScope(RtrDiagnostics::Resources,0,0,1,0,0,0,0,size);
        Packet packet;{RtrDiagnostics::Scope decodeScope(RtrDiagnostics::Import);packet=Decode(bytes,size);}
        ValidateReferences(packet);
        // A resident pose publication has no GPU resource work. Keep its complete
        // packet/reference validation, then stage the exact authored transforms
        // for the original frame refresh, without opening an upload command list.
        const bool posesOnly=packet.textures.empty()&&packet.materials.empty()&&packet.meshes.empty()
            &&packet.removeNodes.empty()&&packet.coverage.empty()&&packet.removeTextures.empty()
            &&std::all_of(packet.nodes.begin(),packet.nodes.end(),[&](const auto& input){
                const auto old=m_nodes.find(input.name);
                return old!=m_nodes.end()&&old->second.mesh==input.mesh;
            });
        if(posesOnly){
            for(auto& input:packet.nodes){
                auto name=input.name;
                m_pendingTransforms.insert_or_assign(std::move(name),std::move(input));
            }
            m_graph=graph;
            if(packet.nodes.empty())++m_poseWork[2];
            else {++m_poseWork[0];m_poseWork[1]+=packet.nodes.size();}
            Changes changes;changes.transforms=!packet.nodes.empty();return changes;
        }
        auto* commands=acquireCommands();
        if(!commands)throw std::runtime_error("Resource command-list acquisition failed");
        // Exact input equality avoids turning an unchanged material into a
        // scene reload. No epsilon or hash collision can suppress a real edit.
        for(auto i=packet.materials.begin();i!=packet.materials.end();)
            if(auto old=m_materials.find(i->first);old!=m_materials.end()&&SameMaterial(old->second,i->second))i=packet.materials.erase(i);
            else ++i;
        bool requiresMaterialLifecycle=false;
        for(const auto& [name,entry]:packet.materials) {
            const auto previous=m_materials.find(name);
            requiresMaterialLifecycle|=previous==m_materials.end()
                ||previous->second.material->domain!=entry.material->domain;
        }
        std::unordered_set<std::string> attributeMeshes,deformedMeshes,reshapedMeshes;
        // Position changes are not graph topology changes when indices, groups,
        // materials and vertex layouts stay identical. The external direct path
        // can rebuild this one native BLAS without replacing its MeshInfo/leaf.
        const bool allowDeformation=m_externalTextureSampling&&!requiresMaterialLifecycle&&packet.coverage.empty();
        for(const auto& [name,mesh]:packet.meshes) {
            if(CanUpdateAttributes(name,*mesh))attributeMeshes.insert(name);
            else if(allowDeformation&&CanUpdateAttributes(name,*mesh,true)) {
                attributeMeshes.insert(name);deformedMeshes.insert(name);
            } else if(allowDeformation&&CanRetainGeometryLayout(name,*mesh)) {
                // Geometry contents/counts are BLAS inputs, not scene-graph
                // identities. Keep the registered mesh/geometry slots when
                // their material layout has not changed; rebuild this BLAS.
                attributeMeshes.insert(name);reshapedMeshes.insert(name);
            }
        }
        Changes changes;
        changes.attributes=!attributeMeshes.empty();
        // A generation/rectangle publication can contain identical pixels. It
        // still advances the parent-generation contract below, but must not
        // invalidate materials, history or acceleration structures.
        changes.textures = !packet.removeTextures.empty();
        for(const auto& [id,texture]:packet.textures) {
            const auto old=m_textures.find(id);
            changes.textures |= old==m_textures.end()||!old->second.SameLayout(texture)
                ||!texture.unchangedPixels;
        }
        changes.materials = !packet.materials.empty();
        changes.coverageChanged = !packet.coverage.empty();
        // The independent pass evaluates opacity itself without OMMs, so pixel/
        // sampler coverage changes do not change its BLAS geometry flags. Native
        // beauty retains the original conservative coverage rebuild lifecycle.
        changes.requiresCoverageRebuild=changes.coverageChanged&&!m_externalTextureSampling;
        changes.topology = packet.meshes.size()!=attributeMeshes.size() || !packet.removeNodes.empty()
            || changes.requiresCoverageRebuild;
        changes.transforms = !packet.nodes.empty();
        bool structural=changes.topology||changes.materials||changes.textures||changes.attributes;
        for(const auto& node:packet.nodes){
            const auto old=m_nodes.find(node.name);
            structural |= old==m_nodes.end()||old->second.mesh!=node.mesh;
        }
        // The sample's TLAS holds device addresses, not leases on removed pooled
        // BLASes. Retire previous GPU readers before graph edits can free them.
        if(structural&&beforeStructural)beforeStructural(!requiresMaterialLifecycle&&!changes.requiresCoverageRebuild);

        if (!packet.textures.empty() && (!m_textureDevice||!m_textureDescriptors))
            throw std::logic_error("Inject native texture resources before importing textures");
        for (auto& [id, texture] : packet.textures) {
            const auto old=m_textures.find(id);
            const Texture* previous=old==m_textures.end()?nullptr:&old->second;
            if(previous&&texture.unchangedPixels) {
                texture.loaded=previous->loaded;
                ++m_textureWork[previous->SameLayout(texture)?5:6];
            } else
                texture.loaded=UploadTexture(id,texture,previous,commands);
        }

        auto findTexture = [&](int64_t id) -> std::shared_ptr<donut::engine::LoadedTexture> {
            if (id < 0) return {};
            if (auto i = packet.textures.find(id); i != packet.textures.end()) return i->second.loaded;
            return m_textures.at(id).loaded;
        };
        auto assignTextures = [&](MaterialEntry& entry) {
            entry.material->baseOrDiffuseTexture = findTexture(entry.textures[0]);
            entry.material->normalTexture = findTexture(entry.textures[1]);
            entry.material->metalRoughOrSpecularTexture = findTexture(entry.textures[2]);
            entry.material->emissiveTexture = findTexture(entry.textures[3]);
            entry.material->dirty = true;
        };
        for (auto& [name, entry] : packet.materials) assignTextures(entry);

        // Preserve registered material identity. The public dirty flag is the
        // original Scene's supported way to publish an edited material.
        for (auto& [name, entry] : packet.materials) {
            auto old = m_materials.find(name);
            if (old != m_materials.end()) {
                changes.existingMaterialsChanged = true;
                const bool domainChanged=old->second.material->domain!=entry.material->domain;
                changes.materialDomainsChanged|=domainChanged;
                changes.topology|=domainChanged;
                const auto constants = old->second.material->materialConstants;
                const auto materialID = old->second.material->materialID;
                UnlinkMaterialTextures(name, old->second);
                *old->second.material = *entry.material;
                old->second.material->materialConstants = constants;
                old->second.material->materialID = materialID;
                entry.material = old->second.material;
            }
        }
        for (const auto& [id, texture] : packet.textures) {
            if(auto old=m_textures.find(id);old!=m_textures.end()&&old->second.loaded==texture.loaded)continue;
            auto users = m_textureMaterials.find(id);
            if (users == m_textureMaterials.end()) continue;
            for (const auto& name : users->second) {
                if (packet.materials.count(name)) continue;
                assignTextures(m_materials.at(name));
                m_pendingTextureMaterials.insert(name);
            }
        }
        for (auto& [name, entry] : packet.materials) {
            LinkMaterialTextures(name, entry);
            m_pendingMaterials.insert(name);
            m_pendingTextureMaterials.insert(name);
            m_materials.insert_or_assign(name, std::move(entry));
        }
        for (auto& [id, texture] : packet.textures) {
            if(auto old=m_textures.find(id);old!=m_textures.end()) {
                if(old->second.loaded!=texture.loaded)changes.retiredTextures.push_back(old->second.loaded);
                // Decode proved exact equality without copying the full image.
                // Retain its shadow only after all packet validation has passed.
                if(texture.unchangedPixels)texture.pixels=std::move(old->second.pixels);
            }
            texture.unchangedPixels=false;
            m_textures.insert_or_assign(id, std::move(texture));
        }
        for (auto id : packet.removeTextures) if(auto old=m_textures.find(id);old!=m_textures.end()){
            changes.retiredTextures.push_back(old->second.loaded);
            m_textures.erase(old);
        }

        std::unordered_set<std::string> touchedMeshes, touchedMaterials;
        std::vector<std::shared_ptr<Mesh>> attributeUpdates;
        for (auto& [name, mesh] : packet.meshes) {
            if(attributeMeshes.contains(name)){
                RtrDiagnostics::Scope retainedMesh(RtrDiagnostics::SceneMeshPreparation,1,0,1,0,
                    RtrDiagnostics::Identity(name),0,reshapedMeshes.contains(name)?RtrDiagnostics::Topology
                        :(deformedMeshes.contains(name)?RtrDiagnostics::Deformation:RtrDiagnostics::Attributes));
                // Original Scene buffers have permanent read states and cannot
                // be rewritten as CopyDest. Ask its uploader for the new buffer;
                // unchanged positions retain the BLAS, while stable-topology
                // deformation requests a native rebuild of only this mesh.
                auto& resident=*m_meshes.at(name);
                changes.retiredBuffers.push_back(resident.buffers);
                if(deformedMeshes.contains(name)||reshapedMeshes.contains(name)) {
                    auto& source=m_meshSources.at(name);
                    source.positions=mesh->buffers->positionData;
                    if(reshapedMeshes.contains(name))source.indices=mesh->buffers->indexData;
                    resident.objectSpaceBounds=mesh->objectSpaceBounds;
                    resident.totalVertices=mesh->totalVertices;
                    resident.totalIndices=mesh->totalIndices;
                    resident.vertexOffset=mesh->vertexOffset;
                    resident.indexOffset=mesh->indexOffset;
                    for(size_t i=0;i<resident.geometries.size();++i) {
                        resident.geometries[i]->objectSpaceBounds=mesh->geometries[i]->objectSpaceBounds;
                        if(reshapedMeshes.contains(name)) {
                            // globalGeometryIndex and the native material owner
                            // remain registered; only authored ranges change.
                            auto& target=*resident.geometries[i];const auto& input=*mesh->geometries[i];
                            target.indexOffsetInMesh=input.indexOffsetInMesh;
                            target.vertexOffsetInMesh=input.vertexOffsetInMesh;
                            target.numIndices=input.numIndices;target.numVertices=input.numVertices;
                        }
                    }
                    if(reshapedMeshes.contains(name))changes.reshapedMeshes.push_back(m_meshes.at(name));
                    else changes.deformedMeshes.push_back(m_meshes.at(name));
                    // A public no-value transform edit marks the new leaf bounds
                    // dirty without unregistering/reordering any scene resource.
                    // Consume it only in the normal Render refresh, preserving
                    // the current/previous pose publication boundary.
                    if(auto users=m_meshNodes.find(name);users!=m_meshNodes.end())
                        for(const auto& instance:users->second)
                            m_nodes.at(instance).node->SetTransform(nullptr,nullptr,nullptr);
                }
                mesh->buffers->instanceBuffer=resident.buffers->instanceBuffer;
                resident.buffers=std::move(mesh->buffers);
                attributeUpdates.push_back(m_meshes.at(name));
                continue;
            }
            // The original uploader frees its CPU arrays. Retain only the
            // geometry inputs needed to prove a future update does not need AS.
            m_meshSources.insert_or_assign(name,MeshSource{mesh->buffers->positionData,mesh->buffers->indexData});
            if (auto old = m_meshes.find(name); old != m_meshes.end()) {
                changes.retiredMeshes.push_back(old->second);
                for(size_t i=0;i<old->second->geometries.size();++i)
                    changes.retiredGeometries.push_back(name+":"+std::to_string(i));
                UnlinkMeshMaterials(name, *old->second, touchedMaterials);
            }
            for (auto& geometry : mesh->geometries)
                geometry->material = m_materials.at(geometry->material->name).material;
            LinkMeshMaterials(name, *mesh);
            m_meshes.insert_or_assign(name, std::move(mesh));
            touchedMeshes.insert(name);
        }
        for (const auto& name : packet.removeNodes) {
            if (auto i = m_nodes.find(name); i != m_nodes.end()) {
                changes.retiredInstances.push_back(name);
                touchedMeshes.insert(i->second.mesh);
                m_meshNodes.at(i->second.mesh).erase(name);
                graph->Detach(i->second.poseNode);
                m_nodes.erase(i);
                m_pendingTransforms.erase(name);
            }
        }
        // Replacing a mesh must also replace all existing leaves referencing it.
        // SetLeaf handles official resource registration and graph dirty flags.
        for (const auto& [meshName, mesh] : packet.meshes) {
            if(attributeMeshes.contains(meshName))continue;
            auto users = m_meshNodes.find(meshName);
            if (users == m_meshNodes.end()) continue;
            for (const auto& name : users->second)
                m_nodes.at(name).node->SetLeaf(m_factory.CreateMeshInstance(m_meshes.at(meshName)));
        }
        if (requiresMaterialLifecycle || changes.requiresCoverageRebuild) graph->GetRootNode()->InvalidateContent();
        for (const auto& input : packet.nodes) {
            auto old = m_nodes.find(input.name);
            const bool created=old==m_nodes.end();
            if (created) {
                auto poseNode=std::make_shared<Node>();poseNode->SetName(input.name+"/affine");
                graph->Attach(graph->GetRootNode(),poseNode);
                auto node = graph->AttachLeafNode(poseNode,m_factory.CreateMeshInstance(m_meshes.at(input.mesh)));
                node->SetName(input.name);
                old = m_nodes.emplace(input.name, Instance{input.mesh,node,poseNode}).first;
                m_meshNodes[input.mesh].insert(input.name);
                changes.topology = true;
            } else if (old->second.mesh != input.mesh) {
                touchedMeshes.insert(old->second.mesh);
                m_meshNodes.at(old->second.mesh).erase(input.name);
                old->second.node->SetLeaf(m_factory.CreateMeshInstance(m_meshes.at(input.mesh)));
                old->second.mesh = input.mesh;
                m_meshNodes[input.mesh].insert(input.name);
                changes.topology = true;
            }
            if(created)
                SetPose(old->second,input);
            else
                m_pendingTransforms.insert_or_assign(input.name,input);
        }
        // Only identities touched by this packet can become unreachable. Actual
        // scene registration/release remains entirely inside the original graph.
        for (const auto& name : touchedMeshes) {
            auto users = m_meshNodes.find(name);
            if (users != m_meshNodes.end() && !users->second.empty()) continue;
            m_meshNodes.erase(name);
            if (auto mesh = m_meshes.find(name); mesh != m_meshes.end()) {
                for(size_t i=0;i<mesh->second->geometries.size();++i)
                    changes.retiredGeometries.push_back(name+":"+std::to_string(i));
                UnlinkMeshMaterials(name, *mesh->second, touchedMaterials);
                changes.retiredMeshes.push_back(mesh->second);
                m_meshSources.erase(name);
                m_meshes.erase(mesh);
            }
        }
        for (const auto& [name, entry] : packet.materials) touchedMaterials.insert(name);
        for (const auto& name : touchedMaterials) {
            auto users = m_materialMeshes.find(name);
            if (users != m_materialMeshes.end() && !users->second.empty()) continue;
            m_materialMeshes.erase(name);
            if (auto material = m_materials.find(name); material != m_materials.end()) {
                UnlinkMaterialTextures(name, material->second);
                m_materials.erase(material);
            }
            m_pendingMaterials.erase(name);
        }
        m_graph = graph;
        // The native uploader/builders still own GPU resources. Hand them only
        // actual incoming/replaced meshes, not a repeated resident-scene scan.
        for(const auto& [name,mesh]:packet.meshes)
            if(!attributeMeshes.contains(name)) {
                const auto resident=m_meshes.find(name);
                if(resident!=m_meshes.end())changes.buildMeshes.push_back(resident->second);
            }
        // Structural/material initialization still needs original buffer allocation
        // before its bakers run. Existing-node poses remain staged until Render.
        // A transform-only packet needs neither this refresh nor a GPU submission.
        const bool originalUploadsAttributes=graph->HasPendingStructureChanges();
        // A texture replacement leaves the original Material dirty. Sample::Render
        // refreshes that same current frame before MaterialsBaker::Update and our
        // lighting pass; an eager texture-only refresh would scan the scene twice.
        // New structure and material definitions still need immediate preparation.
        if(changes.topology||requiresMaterialLifecycle)
            {RtrDiagnostics::Scope refreshScope(RtrDiagnostics::SceneFullRefresh);scene.Refresh(commands,frameIndex);}
        // A texture/material refresh need not have a structural dirty flag, so the original
        // Scene does not upload replacement attribute buffers or geometry rows.
        // Do not lose those updates merely because a texture shares the packet.
        // Actual structural refreshes already run the original complete uploader.
        if(!attributeUpdates.empty()&&!originalUploadsAttributes)
            {RtrDiagnostics::Scope attributeScope(RtrDiagnostics::AttributePreparation,attributeUpdates.size());dynamic_cast<ManagedScene&>(scene).RefreshAttributes(commands,attributeUpdates);}
        return changes;
    }

private:
    using Material = donut::engine::Material;
    using Mesh = donut::engine::MeshInfo;
    using Node = donut::engine::SceneGraphNode;

    struct Texture {
        uint32_t width = 0, height = 0, mips = 0, color = 0, u = 0, v = 0, filter = 0;
        uint64_t generation = 0;
        bool rectangle=false,unchangedPixels=false;
        uint32_t patchMip=0,patchX=0,patchY=0,patchWidth=0,patchHeight=0;
        // Required only for the packet's parent-generation rectangle semantics.
        // There is no upload cache, spare-resource list or GPU allocation policy.
        std::vector<uint8_t> pixels;
        std::shared_ptr<donut::engine::LoadedTexture> loaded;
        bool SameImageLayout(const Texture& other) const {
            return width == other.width && height == other.height && mips == other.mips
                && color == other.color;
        }
        bool SameLayout(const Texture& other) const {
            // Sampler policy belongs to the external pass metadata. It is not
            // part of the original TextureCache image or descriptor identity.
            return SameImageLayout(other) && u == other.u && v == other.v && filter == other.filter;
        }
    };
    struct MaterialEntry {
        std::shared_ptr<Material> material;
        std::array<int64_t, 4> textures{};
        float ior = 1.5f;
        bool psdExclude = true;
        int psdDominant = -1;
        int psdBlockMotionVectors = 0;
        bool boundary = false;
        donut::math::float3 absorption = 0.f;
        int priority = 0;
    };
    struct Instance {
        std::string mesh;
        std::shared_ptr<Node> node,poseNode;
    };
    struct NodeInput {
        std::string name, mesh;
        AffinePose pose;
    };
    static void SetPose(Instance& instance,const NodeInput& input){
        const auto& pose=input.pose;
        instance.poseNode->SetTransform(&pose.translation,&pose.rotation,&pose.scaling);
        if(instance.poseNode!=instance.node)instance.node->SetRotation(pose.inner);
    }
    struct MeshSource {
        std::vector<donut::math::float3> positions;
        std::vector<uint32_t> indices;
    };
    std::unordered_map<std::string,MeshSource> m_meshSources;
    template<class T>static bool SameArray(const std::vector<T>& a,const std::vector<T>& b){
        return a.size()==b.size()&&(a.empty()||std::memcmp(a.data(),b.data(),a.size()*sizeof(T))==0);
    }
    bool CanUpdateAttributes(const std::string& name,const Mesh& mesh,bool allowPositionChanges=false)const{
        const auto old=m_meshes.find(name),end=m_meshes.end();
        const auto shadow=m_meshSources.find(name);
        if(old==end||shadow==m_meshSources.end()||!old->second->buffers->vertexBuffer
            ||old->second->geometries.size()!=mesh.geometries.size())return false;
        const auto& a=*old->second;const auto& b=*mesh.buffers;
        if(a.totalVertices!=mesh.totalVertices||a.totalIndices!=mesh.totalIndices
            ||a.vertexOffset!=mesh.vertexOffset||a.indexOffset!=mesh.indexOffset||a.type!=mesh.type
            ||a.isSkinPrototype||a.skinPrototype.use_count()!=0
            ||shadow->second.positions.size()!=b.positionData.size()
            ||(!allowPositionChanges&&!SameArray(shadow->second.positions,b.positionData))
            ||!SameArray(shadow->second.indices,b.indexData))return false;
        for(size_t i=0;i<a.geometries.size();++i){
            const auto& x=*a.geometries[i];const auto& y=*mesh.geometries[i];
            if(x.material->name!=y.material->name||x.indexOffsetInMesh!=y.indexOffsetInMesh||x.numIndices!=y.numIndices
                ||x.vertexOffsetInMesh!=y.vertexOffsetInMesh||x.numVertices!=y.numVertices)return false;
        }
        auto sizeMatches=[&](donut::engine::VertexAttribute attribute,size_t bytes){
            // Scene::AppendBufferRange stores the native uploader's 16-byte
            // padded range, not the logical attribute byte count.
            return a.buffers->getVertexBufferRange(attribute).byteSize==nvrhi::align(bytes,size_t(16));
        };
        return sizeMatches(donut::engine::VertexAttribute::Position,b.positionData.size()*sizeof(donut::math::float3))
            &&sizeMatches(donut::engine::VertexAttribute::Normal,b.normalData.size()*sizeof(uint32_t))
            &&sizeMatches(donut::engine::VertexAttribute::Tangent,b.tangentData.size()*sizeof(uint32_t))
            &&sizeMatches(donut::engine::VertexAttribute::TexCoord1,b.texcoord1Data.size()*sizeof(donut::math::float2));
    }
    bool CanRetainGeometryLayout(const std::string& name,const Mesh& mesh)const{
        const auto old=m_meshes.find(name);
        if(old==m_meshes.end()||!old->second->buffers->vertexBuffer)return false;
        const auto& resident=*old->second;
        if(resident.type!=mesh.type||resident.geometries.size()!=mesh.geometries.size()
            ||resident.isSkinPrototype||resident.skinPrototype.use_count()!=0
            ||mesh.isSkinPrototype||mesh.skinPrototype.use_count()!=0)return false;
        for(size_t i=0;i<resident.geometries.size();++i) {
            const auto& previous=*resident.geometries[i];const auto& next=*mesh.geometries[i];
            if(previous.material->name!=next.material->name||previous.type!=next.type)return false;
        }
        return true;
    }
    static bool SameMaterial(const MaterialEntry& a,const MaterialEntry& b){
        const auto& x=*a.material;const auto& y=*b.material;
        return a.textures==b.textures&&a.ior==b.ior&&a.psdExclude==b.psdExclude&&a.psdDominant==b.psdDominant
            &&a.psdBlockMotionVectors==b.psdBlockMotionVectors&&a.boundary==b.boundary&&a.priority==b.priority
            &&std::memcmp(&a.absorption,&b.absorption,sizeof(a.absorption))==0
            &&std::memcmp(&x.baseOrDiffuseColor,&y.baseOrDiffuseColor,sizeof(x.baseOrDiffuseColor))==0
            &&std::memcmp(&x.emissiveColor,&y.emissiveColor,sizeof(x.emissiveColor))==0
            &&x.opacity==y.opacity&&x.roughness==y.roughness&&x.metalness==y.metalness
            &&x.transmissionFactor==y.transmissionFactor&&x.alphaCutoff==y.alphaCutoff
            &&x.domain==y.domain&&x.doubleSided==y.doubleSided;
    }
    struct Packet {
        std::unordered_map<int64_t, Texture> textures;
        std::unordered_map<std::string, MaterialEntry> materials;
        std::unordered_map<std::string, std::shared_ptr<Mesh>> meshes;
        std::vector<std::string> removeNodes, coverage;
        std::vector<int64_t> removeTextures;
        std::vector<NodeInput> nodes;
    };

    // Authored raw RGBA8 needs no container decode. Use the native allocator,
    // descriptor table and uploader directly; no second pool or upload arena.
    // Each changed generation is immutable while older GPU work can read it.
    std::shared_ptr<donut::engine::LoadedTexture> UploadTexture(int64_t id,const Texture& input,
        const Texture* previous,nvrhi::ICommandList* commands) {
        std::array<uint64_t,7> work{};
        auto loaded=std::make_shared<donut::engine::LoadedTexture>();
        loaded->path="external/texture-"+std::to_string(id)+"-"+std::to_string(input.generation)+".rgba8";
        loaded->mimeType="application/x-rgba8";loaded->originalBitsPerPixel=32;
        loaded->loadOptions.sRGBMode=input.color?donut::engine::SRGBMode::ForceSRGB:donut::engine::SRGBMode::ForceLinear;
        nvrhi::TextureDesc desc;desc.width=input.width;desc.height=input.height;desc.mipLevels=input.mips;
        desc.format=input.color?nvrhi::Format::SRGBA8_UNORM:nvrhi::Format::RGBA8_UNORM;
        desc.debugName=loaded->path;desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;
        loaded->texture=m_textureDevice->createTexture(desc);
        if(!loaded->texture)throw std::runtime_error("Native authored texture allocation failed");
        loaded->bindlessDescriptor=m_textureDescriptors->CreateDescriptorHandle(nvrhi::BindingSetItem::Texture_SRV(0,loaded->texture));
        const bool compatible=previous&&previous->SameImageLayout(input);
        size_t offset=0;
        for(uint32_t mip=0;mip<input.mips;++mip) {
            const auto width=std::max(1u,input.width>>mip),height=std::max(1u,input.height>>mip);
            const size_t bytes=size_t(width)*height*4;
            // Rectangle decode already proved the authored mip changed and all
            // other mips stayed equal; do not rescan the complete CPU image.
            const bool unchanged=compatible&&(input.rectangle?mip!=input.patchMip
                :std::memcmp(input.pixels.data()+offset,previous->pixels.data()+offset,bytes)==0);
            if(unchanged) {
                commands->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),
                    previous->loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));
                work[4]+=bytes;
            } else if(compatible&&input.rectangle&&mip==input.patchMip
                &&uint64_t(input.patchWidth)*input.patchHeight<uint64_t(width)*height) {
                commands->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),
                    previous->loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));
                work[4]+=bytes;
                auto patchDesc=desc;patchDesc.width=input.patchWidth;patchDesc.height=input.patchHeight;patchDesc.mipLevels=1;
                patchDesc.debugName="Authored texture rectangle upload";
                auto patch=m_textureDevice->createTexture(patchDesc);
                if(!patch)throw std::runtime_error("Native texture rectangle allocation failed");
                commands->writeTexture(patch,0,0,input.pixels.data()+offset+(size_t(input.patchY)*width+input.patchX)*4,size_t(width)*4);
                auto target=nvrhi::TextureSlice().setMipLevel(mip);target.x=input.patchX;target.y=input.patchY;
                target.width=input.patchWidth;target.height=input.patchHeight;
                commands->copyTexture(loaded->texture,target,patch,nvrhi::TextureSlice());
                ++work[2];work[3]+=uint64_t(input.patchWidth)*input.patchHeight*4;
                // NVRHI command-list references keep this temporary image and its
                // native upload storage alive through GPU execution.
            } else {
                commands->writeTexture(loaded->texture,0,mip,input.pixels.data()+offset,size_t(width)*4);
                ++work[0];work[1]+=bytes;
            }
            offset+=bytes;
        }
        for(size_t i=0;i<work.size();++i)m_textureWork[i]+=work[i];
        return loaded;
    }

    static donut::math::float3 Color(uint32_t rgba) {
        return donut::math::float3(float(rgba & 255), float((rgba >> 8) & 255),
            float((rgba >> 16) & 255)) / 255.f;
    }
    static std::string Name(RtrResourceReader& reader) {
        auto result = reader.text();
        if (result.empty() || result.find('\0') != std::string::npos)
            throw std::invalid_argument("Resource identity must be nonempty UTF-8 without NUL");
        return result;
    }
    void LinkMaterialTextures(const std::string& name, const MaterialEntry& entry) {
        for (auto id : entry.textures) if (id >= 0) m_textureMaterials[id].insert(name);
    }
    void UnlinkMaterialTextures(const std::string& name, const MaterialEntry& entry) {
        for (auto id : entry.textures) if (id >= 0) {
            auto i = m_textureMaterials.find(id);
            if (i != m_textureMaterials.end() && (i->second.erase(name), i->second.empty()))
                m_textureMaterials.erase(i);
        }
    }
    void LinkMeshMaterials(const std::string& name, const Mesh& mesh) {
        for (const auto& geometry : mesh.geometries)
            m_materialMeshes[geometry->material->name].insert(name);
    }
    void UnlinkMeshMaterials(const std::string& name, const Mesh& mesh,
        std::unordered_set<std::string>& touched) {
        for (const auto& geometry : mesh.geometries) {
            const auto& material = geometry->material->name;
            touched.insert(material);
            auto i = m_materialMeshes.find(material);
            if (i != m_materialMeshes.end()) i->second.erase(name);
        }
    }
    const Texture& FindTexture(int64_t id, const Packet& packet) const {
        if (auto i = packet.textures.find(id); i != packet.textures.end()) return i->second;
        auto i = m_textures.find(id);
        if (i == m_textures.end()) throw std::invalid_argument("Unknown texture identity");
        return i->second;
    }
    const MaterialEntry& FindMaterial(const std::string& name, const Packet& packet) const {
        if (auto i = packet.materials.find(name); i != packet.materials.end()) return i->second;
        auto i = m_materials.find(name);
        if (i == m_materials.end()) throw std::invalid_argument("Unknown geometry material: " + name);
        return i->second;
    }

    Packet Decode(const void* bytes, size_t size) {
        RtrResourceReader reader(bytes, size);
        if (reader.value<uint32_t>() != 0x52445257)
            throw std::invalid_argument("Unknown resource transport");
        Packet packet;
        for (uint32_t n = reader.count(); n; --n) {
            const auto id = reader.value<int64_t>();
            Texture texture;
            texture.width = reader.value<uint32_t>(); texture.height = reader.value<uint32_t>();
            texture.mips = reader.value<uint32_t>(); texture.color = reader.value<uint32_t>();
            texture.u = reader.value<uint32_t>(); texture.v = reader.value<uint32_t>();
            texture.filter = reader.value<uint32_t>(); texture.generation = reader.value<uint64_t>();
            const auto transport = reader.value<uint32_t>();
            if (id < 0 || !texture.width || !texture.height || texture.width > 16384
                || texture.height > 16384 || !texture.mips || texture.mips > 15
                || texture.color > 1 || texture.u > 1 || texture.v > 1 || texture.filter > 1
                || !texture.generation || transport > 1)
                throw std::invalid_argument("Invalid texture metadata");
            // Original RTXPT's public material path has one shared material
            // sampler, not per-texture address/filter policies. Never ignore it.
            if (!m_externalTextureSampling && (texture.u || texture.v || texture.filter != 1))
                throw std::invalid_argument("Original RTXPT material path cannot express per-texture clamp or nearest sampling");
            size_t expected = 0;
            uint32_t width = texture.width, height = texture.height;
            for (uint32_t mip = 0; mip < texture.mips; ++mip) {
                expected += size_t(width) * height * 4;
                if (mip + 1 < texture.mips && width == 1 && height == 1)
                    throw std::invalid_argument("Texture mip chain exceeds its extent");
                width = std::max(1u, width / 2); height = std::max(1u, height / 2);
            }
            if (!transport) {
                const auto count=reader.count();const auto* pixels=reader.take(count);
                if(count!=expected)throw std::invalid_argument("Invalid RGBA8 texture byte count");
                const auto old=m_textures.find(id);
                texture.unchangedPixels=old!=m_textures.end()&&old->second.SameImageLayout(texture)
                    &&old->second.pixels.size()==expected
                    &&std::memcmp(old->second.pixels.data(),pixels,expected)==0;
                if(!texture.unchangedPixels)texture.pixels.assign(pixels,pixels+count);
            }
            else {
                const auto parent = reader.value<uint64_t>();
                const auto mip = reader.value<uint32_t>(), x = reader.value<uint32_t>();
                const auto y = reader.value<uint32_t>(), patchWidth = reader.value<uint32_t>();
                const auto patchHeight = reader.value<uint32_t>(), pitch = reader.value<uint32_t>();
                const auto count=reader.count();const auto* pixels=reader.take(count);
                auto old = m_textures.find(id);
                if (old == m_textures.end() || old->second.generation != parent
                    || !old->second.SameLayout(texture) || mip >= texture.mips
                    || !patchWidth || !patchHeight || old->second.pixels.size()!=expected)
                    throw std::invalid_argument("Stale or invalid texture patch");
                width = std::max(1u, texture.width >> mip); height = std::max(1u, texture.height >> mip);
                if (uint64_t(x) + patchWidth > width || uint64_t(y) + patchHeight > height
                    || uint64_t(pitch) < uint64_t(patchWidth) * 4
                    || uint64_t(pitch) * (patchHeight - 1) + uint64_t(patchWidth) * 4 != count)
                    throw std::invalid_argument("Invalid texture patch range");
                texture.rectangle=true;texture.patchMip=mip;texture.patchX=x;texture.patchY=y;
                texture.patchWidth=patchWidth;texture.patchHeight=patchHeight;
                size_t offset = 0;
                for (uint32_t i = 0; i < mip; ++i)
                    offset += size_t(std::max(1u, texture.width >> i)) * std::max(1u, texture.height >> i) * 4;
                texture.unchangedPixels=true;
                for(uint32_t row=0;row<patchHeight;++row)
                    if(std::memcmp(old->second.pixels.data()+offset+(size_t(y+row)*width+x)*4,
                        pixels+size_t(row)*pitch,size_t(patchWidth)*4)!=0) {
                        texture.unchangedPixels=false;break;
                    }
                if(!texture.unchangedPixels) {
                    texture.pixels=old->second.pixels;
                    for (uint32_t row = 0; row < patchHeight; ++row)
                        std::memcpy(texture.pixels.data() + offset + (size_t(y + row) * width + x) * 4,
                            pixels + size_t(row) * pitch, size_t(patchWidth) * 4);
                }
            }
            if (!texture.unchangedPixels && texture.pixels.size() != expected)
                throw std::invalid_argument("Invalid RGBA8 texture byte count");
            if (!packet.textures.emplace(id, std::move(texture)).second)
                throw std::invalid_argument("Duplicate texture identity");
        }
        for (uint32_t n = reader.count(); n; --n) {
            MaterialEntry entry;
            entry.material = m_factory.CreateMaterial();
            auto& material = *entry.material;
            material.name = Name(reader);
            const auto rgba = reader.value<uint32_t>();
            material.baseOrDiffuseColor = Color(rgba);
            material.opacity = float(rgba >> 24) / 255.f * reader.value<float>();
            material.roughness = reader.value<float>(); material.metalness = reader.value<float>();
            material.transmissionFactor = reader.value<float>(); material.alphaCutoff = reader.value<float>();
            const auto blend = reader.value<uint32_t>(), doubleSided = reader.value<uint32_t>();
            material.doubleSided = doubleSided != 0;
            // The private wire carries canonical linear radiance. Donut already
            // accepts float3 emission; do not quantize it back to a packed color.
            material.emissiveColor.x = reader.value<float>();
            material.emissiveColor.y = reader.value<float>();
            material.emissiveColor.z = reader.value<float>();
            material.emissiveIntensity = 1.f; entry.ior = reader.value<float>();
            const auto psdExclude = reader.value<int32_t>();
            entry.psdDominant = reader.value<int32_t>();
            if (psdExclude < 0 || psdExclude > 1 || entry.psdDominant < -1 || entry.psdDominant > 1
                || (psdExclude != 0 && entry.psdDominant != -1))
                throw std::invalid_argument("Invalid native material path decomposition");
            entry.psdExclude = psdExclude != 0;
            entry.psdBlockMotionVectors = reader.value<int32_t>();
            if (entry.psdBlockMotionVectors < 0 || entry.psdBlockMotionVectors > 3)
                throw std::invalid_argument("Invalid native material motion-vector blocking");
            for (unsigned component=0;component<3;++component)
                if (!std::isfinite(material.emissiveColor[component]) || material.emissiveColor[component]<0)
                    throw std::invalid_argument("Invalid linear emissive radiance");
            if (blend > 2 || doubleSided > 1 || entry.ior < 1.f)
                throw std::invalid_argument("Invalid material input");
            material.domain = donut::engine::MaterialDomain(blend + (material.transmissionFactor > 0 ? 3 : 0));
            for (auto& id : entry.textures) id = reader.value<int64_t>();
            const auto boundary = reader.value<uint32_t>();
            if (boundary > 1) throw std::invalid_argument("Invalid medium boundary flag");
            entry.boundary = boundary != 0;
            if (entry.boundary) {
                entry.absorption.x = reader.value<float>(); entry.absorption.y = reader.value<float>();
                entry.absorption.z = reader.value<float>(); entry.priority = reader.value<int32_t>();
                if (entry.absorption.x < 0 || entry.absorption.y < 0 || entry.absorption.z < 0
                    || entry.priority < 0 || entry.priority > PTMaterial::kMaterialMaxNestedPriority)
                    throw std::invalid_argument("Medium boundary exceeds original material limits");
            }
            const auto name = material.name;
            if (!packet.materials.emplace(name, std::move(entry)).second)
                throw std::invalid_argument("Duplicate material identity");
        }
        for (uint32_t n = reader.count(); n; --n) {
            auto mesh = m_factory.CreateMesh();
            mesh->name = Name(reader);
            auto positions = reader.floatView(), normals = reader.floatView();
            auto tangents = reader.floatView(), uv = reader.floatView();
            if (positions.empty() || positions.size() % 3 || normals.size() != positions.size()
                || (!tangents.empty() && tangents.size() != positions.size() / 3 * 4)
                || (!uv.empty() && uv.size() != positions.size() / 3 * 2))
                throw std::invalid_argument("Invalid mesh attributes");
            const auto vertices = positions.size() / 3;
            mesh->buffers = std::make_shared<donut::engine::BufferGroup>();
            auto& buffers = *mesh->buffers;
            buffers.positionData.resize(vertices); buffers.normalData.resize(vertices);
            if (!tangents.empty()) buffers.tangentData.resize(vertices);
            if (!uv.empty()) buffers.texcoord1Data.resize(vertices);
            mesh->objectSpaceBounds = donut::math::box3::empty();
            for (size_t v = 0; v < vertices; ++v) {
                buffers.positionData[v] = donut::math::float3(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
                buffers.normalData[v] = donut::math::vectorToSnorm8(donut::math::float3(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]));
                if (!tangents.empty()) buffers.tangentData[v] = donut::math::vectorToSnorm8(donut::math::float4(tangents[v * 4], tangents[v * 4 + 1], tangents[v * 4 + 2], tangents[v * 4 + 3]));
                if (!uv.empty()) buffers.texcoord1Data[v] = donut::math::float2(uv[v * 2], uv[v * 2 + 1]);
                mesh->objectSpaceBounds |= buffers.positionData[v];
            }
            const auto groups = reader.count();
            if (!groups) throw std::invalid_argument("Mesh requires geometry groups");
            for (uint32_t group = 0; group < groups; ++group) {
                const auto materialName = Name(reader);
                auto geometry = m_factory.CreateMeshGeometry();
                geometry->material = FindMaterial(materialName, packet).material;
                geometry->indexOffsetInMesh = uint32_t(buffers.indexData.size());
                geometry->numIndices = reader.count(); geometry->numVertices = uint32_t(vertices);
                geometry->objectSpaceBounds = mesh->objectSpaceBounds;
                if (geometry->numIndices % 3 || !geometry->numIndices
                    || uint64_t(buffers.indexData.size()) + geometry->numIndices > UINT32_MAX)
                    throw std::invalid_argument("Invalid triangle index count");
                for (uint32_t k = 0; k < geometry->numIndices; ++k) {
                    const auto index = reader.value<uint32_t>();
                    if (index >= vertices) throw std::invalid_argument("Triangle index exceeds vertices");
                    buffers.indexData.push_back(index);
                }
                mesh->geometries.push_back(std::move(geometry));
            }
            mesh->totalVertices = uint32_t(vertices); mesh->totalIndices = uint32_t(buffers.indexData.size());
            const auto name = mesh->name;
            if (!packet.meshes.emplace(name, std::move(mesh)).second)
                throw std::invalid_argument("Duplicate mesh identity");
        }
        std::unordered_set<std::string> removals, nodes;
        for (uint32_t n = reader.count(); n; --n) {
            auto name = Name(reader);
            if (!removals.insert(name).second) throw std::invalid_argument("Duplicate node removal");
            packet.removeNodes.push_back(std::move(name));
        }
        for (uint32_t n = reader.count(); n; --n) {
            NodeInput input;
            input.name = Name(reader); input.mesh = Name(reader);
            if (!nodes.insert(input.name).second || removals.count(input.name))
                throw std::invalid_argument("Duplicate or conflicting instance identity");
            const auto values = reader.floats();
            if (values.size() != 12) throw std::invalid_argument("Transform requires twelve floats");
            const double determinant = double(values[0]) * (double(values[5]) * values[10] - double(values[6]) * values[9])
                - double(values[1]) * (double(values[4]) * values[10] - double(values[6]) * values[8])
                + double(values[2]) * (double(values[4]) * values[9] - double(values[5]) * values[8]);
            if (std::abs(determinant) < 1.e-8)
                throw std::invalid_argument("Instance transform must be invertible");
            donut::math::daffine3 transform;
            for (int row = 0; row < 3; ++row)
                for (int col = 0; col < 3; ++col) transform.m_linear[col][row] = double(values[row * 4 + col]);
            transform.m_translation = donut::math::double3(values[3], values[7], values[11]);
            input.pose=AffinePose::Decode(transform);
            packet.nodes.push_back(std::move(input));
        }
        for (uint32_t n = reader.count(); n; --n) packet.coverage.push_back(Name(reader));
        std::unordered_set<int64_t> removedTextures;
        for (uint32_t n = reader.count(); n; --n) {
            const auto id = reader.value<int64_t>();
            if (id < 0 || packet.textures.count(id) || !removedTextures.insert(id).second)
                throw std::invalid_argument("Duplicate or conflicting texture removal");
            packet.removeTextures.push_back(id);
        }
        reader.finish();
        return packet;
    }

    void ValidateReferences(const Packet& packet) {
        const std::unordered_set<int64_t> removed(packet.removeTextures.begin(), packet.removeTextures.end());
        auto validateMaterial = [&](const MaterialEntry& entry) {
            for (auto id : entry.textures) {
                if (id < -1) throw std::invalid_argument("Invalid texture reference sentinel");
                if (id >= 0) {
                    if (removed.count(id)) throw std::invalid_argument("Cannot remove a referenced texture");
                    (void)FindTexture(id, packet);
                }
            }
        };
        for (const auto& [name, entry] : packet.materials) validateMaterial(entry);
        auto validateMesh = [&](const std::shared_ptr<Mesh>& mesh) {
            for (const auto& geometry : mesh->geometries) {
                const auto& entry = FindMaterial(geometry->material->name, packet);
                if (entry.textures[1] >= 0 && mesh->buffers->tangentData.empty()
                    && !mesh->buffers->hasAttribute(donut::engine::VertexAttribute::Tangent))
                    throw std::invalid_argument("Normal-mapped meshes require explicit tangents on the original scene path");
            }
        };
        for (const auto& [name, mesh] : packet.meshes) validateMesh(mesh);
        for (const auto& [name, entry] : packet.materials) {
            auto users = m_materialMeshes.find(name);
            if (users == m_materialMeshes.end()) continue;
            // Existing meshes were checked when their normal map was enabled.
            // Pixel, sampler, scalar or normal-texture identity edits cannot add
            // a missing vertex requirement; changed meshes are checked above.
            // Keep all material texture-ID validation before this fast path.
            const auto previous=m_materials.find(name);
            if(entry.textures[1]<0 || (previous!=m_materials.end()&&previous->second.textures[1]>=0)) {
                m_referenceWork[1]+=users->second.size();
                continue;
            }
            for (const auto& mesh : users->second)
                if (!packet.meshes.count(mesh)) {
                    ++m_referenceWork[0];
                    validateMesh(m_meshes.at(mesh));
                }
        }
        for (const auto& input : packet.nodes)
            if (!packet.meshes.count(input.mesh) && !m_meshes.count(input.mesh))
                throw std::invalid_argument("Unknown instance mesh: " + input.mesh);
        for (const auto& name : packet.coverage) (void)FindMaterial(name, packet);
    }

    ExtendedSceneTypeFactory m_factory;
    bool m_externalTextureSampling = false;
    std::array<uint64_t,7> m_textureWork{};
    std::array<uint64_t,2> m_referenceWork{};
    std::array<uint64_t,3> m_poseWork{};
    nvrhi::IDevice* m_textureDevice=nullptr;
    std::shared_ptr<donut::engine::DescriptorTableManager> m_textureDescriptors;
    std::weak_ptr<donut::engine::SceneGraph> m_graph;
    std::unordered_map<int64_t, Texture> m_textures;
    std::unordered_map<std::string, MaterialEntry> m_materials;
    std::unordered_map<std::string, std::shared_ptr<Mesh>> m_meshes;
    std::unordered_map<std::string, Instance> m_nodes;
    // External IDs have no upstream lookup/replace API. These indexes route only
    // changed identities; they do not mirror geometry, AS or GPU resource state.
    std::unordered_map<std::string, std::unordered_set<std::string>> m_meshNodes;
    std::unordered_map<std::string, std::unordered_set<std::string>> m_materialMeshes;
    std::unordered_map<int64_t, std::unordered_set<std::string>> m_textureMaterials;
    std::unordered_set<std::string> m_pendingMaterials;
    std::unordered_set<std::string> m_pendingTextureMaterials;
    std::unordered_map<std::string,NodeInput> m_pendingTransforms;
};

} // namespace rtr::upstream
