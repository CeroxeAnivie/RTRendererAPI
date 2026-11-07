#pragma once

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <donut/core/vfs/VFS.h>
#include <donut/engine/Scene.h>
#include <donut/engine/TextureCache.h>
#include "SampleCommon/ExtendedScene.h"
#include "Materials/MaterialsBaker.h"
#include "rtxpt_memory_resources.h"

namespace rtr::upstream {

// Converts the private, synchronous resource packet into original scene objects.
// GPU buffers, upload pages, descriptors, BLAS/TLAS and their retirement belong
// to the original Scene/TextureCache/PathTracer; this class does not manage them.
class Resources final {
public:
    struct Changes {
        bool topology = false;
        bool materials = false;
        bool transforms = false;
        bool textures = false;
        std::vector<std::string> retiredInstances;
        std::vector<std::string> retiredGeometries;
    };

    void SetTextureCache(std::shared_ptr<donut::engine::TextureCache> cache) {
        m_textureCache = std::move(cache);
    }
    bool HasInstance(const std::string& name) const { return m_nodes.contains(name); }
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
        m_materials.clear();
        if(m_textureCache)for(const auto& [id,texture]:m_textures)
            if(texture.loaded)m_textureCache->UnloadTexture(texture.loaded);
        m_textures.clear();
        m_meshNodes.clear();
        m_materialMeshes.clear();
        m_textureMaterials.clear();
        m_pendingMaterials.clear();
        m_pendingTransforms.clear();
        m_graph.reset();
    }

    // Apply changed poses once, immediately before the original frame refresh.
    // Structural/material preparation may refresh the graph earlier; publishing
    // existing-node poses there would make that second refresh erase prevTransform.
    void ApplyPendingTransforms() {
        for(const auto& [name,input]:m_pendingTransforms)
            if(auto node=m_nodes.find(name);node!=m_nodes.end())
                node->second.node->SetTransform(&input.translation,&input.rotation,&input.scaling);
        m_pendingTransforms.clear();
    }

    // Donut Material has no IOR/volume fields. After the original SceneLoaded /
    // MaterialsBaker lifecycle has created PTMaterial, map those transport fields
    // through the original, public PTMaterial data and its dirty contract.
    void ConfigureMaterials(bool sceneReloaded = false) {
        auto configure = [&](const std::string& name, MaterialEntry& entry) {
            auto material = std::dynamic_pointer_cast<MaterialEx>(entry.material);
            if (!material || !material->PTMaterial)
                throw std::runtime_error("Original material bake has not completed: " + name);
            auto& target = *material->PTMaterial;
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

    Changes Apply(const void* bytes, size_t size, donut::engine::Scene& scene,
        nvrhi::ICommandList* commands, uint32_t frameIndex) {
        if (!bytes || !size || !commands)
            throw std::invalid_argument("A resource packet and open command list are required");
        auto graph = scene.GetSceneGraph();
        if (!graph || !graph->GetRootNode())
            throw std::logic_error("Create the original empty scene graph before applying resources");
        if (auto bound = m_graph.lock(); bound && bound != graph)
            throw std::logic_error("Reset resource identities before replacing the original scene");

        // Decode and validate the complete packet before changing any scene object.
        Packet packet = Decode(bytes, size);
        ValidateReferences(packet);
        Changes changes;
        changes.textures = !packet.textures.empty() || !packet.removeTextures.empty();
        changes.materials = !packet.materials.empty();
        changes.topology = !packet.meshes.empty() || !packet.removeNodes.empty()
            || !packet.coverage.empty();
        changes.transforms = !packet.nodes.empty();

        if (!packet.textures.empty() && !m_textureCache)
            throw std::logic_error("Inject the original TextureCache before importing textures");
        for (auto& [id, texture] : packet.textures) {
            auto old = m_textures.find(id);
            if (old != m_textures.end() && old->second.SameLayout(texture)
                && old->second.pixels == texture.pixels) {
                texture.loaded = old->second.loaded;
                continue;
            }
            donut::engine::TextureLoadOptions options;
            options.sRGBMode = texture.color ? donut::engine::SRGBMode::ForceSRGB
                : donut::engine::SRGBMode::ForceLinear;
            auto blob = std::make_shared<TextureBlob>(texture);
            texture.loaded = m_textureCache->LoadTextureFromMemory(blob,
                "external/texture-" + std::to_string(id) + "-"
                    + std::to_string(texture.generation) + ".dds",
                "image/vnd-ms.dds", options, nullptr, commands);
            if (!texture.loaded || !texture.loaded->texture)
                throw std::runtime_error("Original TextureCache rejected the RGBA8 texture");
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
                changes.topology |= old->second.material->domain != entry.material->domain;
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
            auto users = m_textureMaterials.find(id);
            if (users == m_textureMaterials.end()) continue;
            for (const auto& name : users->second) {
                if (packet.materials.count(name)) continue;
                assignTextures(m_materials.at(name));
                m_pendingMaterials.insert(name);
                changes.materials = true;
            }
        }
        for (auto& [name, entry] : packet.materials) {
            LinkMaterialTextures(name, entry);
            m_pendingMaterials.insert(name);
            m_materials.insert_or_assign(name, std::move(entry));
        }
        for (auto& [id, texture] : packet.textures) {
            if(auto old=m_textures.find(id);old!=m_textures.end()&&old->second.loaded!=texture.loaded)
                m_textureCache->UnloadTexture(old->second.loaded);
            m_textures.insert_or_assign(id, std::move(texture));
        }
        for (auto id : packet.removeTextures) if(auto old=m_textures.find(id);old!=m_textures.end()){
            m_textureCache->UnloadTexture(old->second.loaded);m_textures.erase(old);
        }

        std::unordered_set<std::string> touchedMeshes, touchedMaterials;
        for (auto& [name, mesh] : packet.meshes) {
            if (auto old = m_meshes.find(name); old != m_meshes.end()) {
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
                graph->Detach(i->second.node);
                m_nodes.erase(i);
                m_pendingTransforms.erase(name);
            }
        }
        // Replacing a mesh must also replace all existing leaves referencing it.
        // SetLeaf handles official resource registration and graph dirty flags.
        for (const auto& [meshName, mesh] : packet.meshes) {
            auto users = m_meshNodes.find(meshName);
            if (users == m_meshNodes.end()) continue;
            for (const auto& name : users->second)
                m_nodes.at(name).node->SetLeaf(m_factory.CreateMeshInstance(m_meshes.at(meshName)));
        }
        if (changes.materials || !packet.coverage.empty()) graph->GetRootNode()->InvalidateContent();
        for (const auto& input : packet.nodes) {
            auto old = m_nodes.find(input.name);
            const bool created=old==m_nodes.end();
            if (created) {
                auto node = graph->AttachLeafNode(graph->GetRootNode(),
                    m_factory.CreateMeshInstance(m_meshes.at(input.mesh)));
                node->SetName(input.name);
                old = m_nodes.emplace(input.name, Instance{input.mesh, std::move(node)}).first;
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
                old->second.node->SetTransform(&input.translation,&input.rotation,&input.scaling);
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
        // Structural/material initialization still needs original buffer allocation
        // before its bakers run. Existing-node poses remain staged until Render.
        // A transform-only packet needs neither this refresh nor a GPU submission.
        if(changes.topology||changes.materials||changes.textures)
            scene.Refresh(commands,frameIndex);
        return changes;
    }

private:
    using Material = donut::engine::Material;
    using Mesh = donut::engine::MeshInfo;
    using Node = donut::engine::SceneGraphNode;

    struct Texture {
        uint32_t width = 0, height = 0, mips = 0, color = 0, u = 0, v = 0, filter = 0;
        uint64_t generation = 0;
        // Required only for the packet's parent-generation rectangle semantics.
        // There is no upload cache, spare-resource list or GPU allocation policy.
        std::vector<uint8_t> pixels;
        std::shared_ptr<donut::engine::LoadedTexture> loaded;
        bool SameLayout(const Texture& other) const {
            return width == other.width && height == other.height && mips == other.mips
                && color == other.color && u == other.u && v == other.v && filter == other.filter;
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
        std::shared_ptr<Node> node;
    };
    struct NodeInput {
        std::string name, mesh;
        donut::math::double3 translation, scaling;
        donut::math::dquat rotation;
    };
    struct Packet {
        std::unordered_map<int64_t, Texture> textures;
        std::unordered_map<std::string, MaterialEntry> materials;
        std::unordered_map<std::string, std::shared_ptr<Mesh>> meshes;
        std::vector<std::string> removeNodes, coverage;
        std::vector<int64_t> removeTextures;
        std::vector<NodeInput> nodes;
    };

    // Raw RGBA8 is represented as an uncompressed DX10 DDS container so that
    // original TextureCache owns decoding, texture creation and upload as usual.
    class TextureBlob final : public donut::vfs::IBlob {
        std::vector<uint8_t> m_bytes;
        void Put(size_t offset, uint32_t value) {
            for (size_t i = 0; i < 4; ++i) m_bytes[offset + i] = uint8_t(value >> (i * 8));
        }
    public:
        explicit TextureBlob(const Texture& texture) : m_bytes(148 + texture.pixels.size()) {
            Put(0, 0x20534444); Put(4, 124); Put(8, 0x2100f);
            Put(12, texture.height); Put(16, texture.width); Put(20, texture.width * 4);
            Put(28, texture.mips); Put(76, 32); Put(80, 4); Put(84, 0x30315844);
            Put(108, texture.mips > 1 ? 0x401008 : 0x1000);
            Put(128, texture.color ? 29 : 28); // DXGI R8G8B8A8_UNORM[_SRGB]
            Put(132, 3); Put(140, 1); // D3D10_RESOURCE_DIMENSION_TEXTURE2D, one slice
            std::memcpy(m_bytes.data() + 148, texture.pixels.data(), texture.pixels.size());
        }
        const void* data() const override { return m_bytes.data(); }
        size_t size() const override { return m_bytes.size(); }
    };

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
            if (!transport) texture.pixels = reader.bytes();
            else {
                const auto parent = reader.value<uint64_t>();
                const auto mip = reader.value<uint32_t>(), x = reader.value<uint32_t>();
                const auto y = reader.value<uint32_t>(), patchWidth = reader.value<uint32_t>();
                const auto patchHeight = reader.value<uint32_t>(), pitch = reader.value<uint32_t>();
                auto pixels = reader.bytes();
                auto old = m_textures.find(id);
                if (old == m_textures.end() || old->second.generation != parent
                    || !old->second.SameLayout(texture) || mip >= texture.mips
                    || !patchWidth || !patchHeight)
                    throw std::invalid_argument("Stale or invalid texture patch");
                width = std::max(1u, texture.width >> mip); height = std::max(1u, texture.height >> mip);
                if (uint64_t(x) + patchWidth > width || uint64_t(y) + patchHeight > height
                    || uint64_t(pitch) < uint64_t(patchWidth) * 4
                    || uint64_t(pitch) * (patchHeight - 1) + uint64_t(patchWidth) * 4 != pixels.size())
                    throw std::invalid_argument("Invalid texture patch range");
                texture.pixels = old->second.pixels;
                size_t offset = 0;
                for (uint32_t i = 0; i < mip; ++i)
                    offset += size_t(std::max(1u, texture.width >> i)) * std::max(1u, texture.height >> i) * 4;
                for (uint32_t row = 0; row < patchHeight; ++row)
                    std::memcpy(texture.pixels.data() + offset + (size_t(y + row) * width + x) * 4,
                        pixels.data() + size_t(row) * pitch, size_t(patchWidth) * 4);
            }
            if (texture.pixels.size() != expected)
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
            // API matrices act on column vectors, while SceneGraph uses row
            // vectors and constructs S*R*T. The original decomposeAffine takes
            // scale from columns: decompose the column convention and conjugate
            // the original quaternion to obtain SceneGraph's row convention.
            auto decomposition = transform;
            decomposition.m_linear = donut::math::transpose(transform.m_linear);
            donut::math::decomposeAffine(decomposition, &input.translation, &input.rotation, &input.scaling);
            input.rotation = donut::math::conjugate(input.rotation);
            const auto rebuilt = donut::math::scaling(input.scaling) * input.rotation.toAffine()
                * donut::math::translation(input.translation);
            for (int row = 0; row < 3; ++row)
                for (int col = 0; col < 3; ++col) {
                    const auto expected = transform.m_linear[row][col], actual = rebuilt.m_linear[row][col];
                    if (!std::isfinite(actual) || std::abs(expected - actual) > 1.e-5 * std::max(1., std::abs(expected)))
                        throw std::invalid_argument("Original SceneGraph TRS cannot represent this affine transform without loss");
                }
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

    void ValidateReferences(const Packet& packet) const {
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
            for (const auto& mesh : users->second)
                if (!packet.meshes.count(mesh)) validateMesh(m_meshes.at(mesh));
        }
        for (const auto& input : packet.nodes)
            if (!packet.meshes.count(input.mesh) && !m_meshes.count(input.mesh))
                throw std::invalid_argument("Unknown instance mesh: " + input.mesh);
        for (const auto& name : packet.coverage) (void)FindMaterial(name, packet);
    }

    ExtendedSceneTypeFactory m_factory;
    bool m_externalTextureSampling = false;
    std::shared_ptr<donut::engine::TextureCache> m_textureCache;
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
    std::unordered_map<std::string,NodeInput> m_pendingTransforms;
};

} // namespace rtr::upstream
