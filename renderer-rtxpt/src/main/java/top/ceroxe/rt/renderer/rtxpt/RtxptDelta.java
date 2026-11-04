package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptJson.object;

/** A model contains only new geometry and affected material/texture dependencies. */
final class RtxptDelta {
    record Work(long itemReads,long dependencyVisits,long inspectedMapNodes,int exportedModels) { }
    record Prepared(Path manifest,RtxptScene translated,Map<Long,Path> textureFiles,int meshes,int materials,int textures,
                    boolean hasWork,boolean mappingsChanged,Path directory,List<Path> retiredTextures,Work work,
                    java.nio.ByteBuffer memory,List<RtxptScene.Item> changedVariants) { }
    static boolean alphaEquivalent(TextureAsset a,TextureAsset b){
        if(a==b)return true;if(a==null||b==null)return false;
        if(a.width()!=b.width()||a.height()!=b.height()||a.mipLevelCount()!=b.mipLevelCount()||a.filter()!=b.filter()||a.addressU()!=b.addressU()||a.addressV()!=b.addressV())return false;
        var x=a.rgba8();var y=b.rgba8();for(int i=3;i<x.remaining();i+=4)if(x.get(i)!=y.get(i))return false;return true;
    }
    private static final class Selection {
        final RtxptScene scene;final List<RtxptScene.Item> items;
        final Map<Long,List<RtxptScene.Item>> cache=new HashMap<>();
        long reads,dependencies;
        Selection(RtxptScene scene,List<RtxptScene.Item> items){this.scene=scene;this.items=items;}
        List<RtxptScene.Item> variants(long mesh){return cache.computeIfAbsent(mesh,id->{
            var variants=new LinkedHashMap<String,RtxptScene.Item>();
            for(long instance:scene.instancesUsingMesh(id)){dependencies++;var item=scene.item(instance);variants.putIfAbsent(RtxptScene.meshName(item),item);}
            for(int index=scene.instances.size();index<items.size();index++){var item=items.get(index);reads++;if(item.mesh()==id)variants.putIfAbsent(RtxptScene.meshName(item),item);}
            return List.copyOf(variants.values());
        });}
        boolean contains(RtxptScene.Item item){
            // A pose in a mixed resource transaction keeps its existing variant;
            // do not enumerate every other instance sharing that mesh.
            if(item.name().startsWith("i")){
                long id=Long.parseLong(item.name(),1,item.name().length(),10);
                if(RtxptScene.sameStructure(scene.item(id),item))return true;
            }
            String name=RtxptScene.meshName(item);return variants(item.mesh()).stream().anyMatch(i->RtxptScene.meshName(i).equals(name));
        }
    }
    static Prepared prepare(Path parent,RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,
                            List<RtxptScene.Item> items,Map<Long,Path> existingTextures)throws IOException {
        var changedTextures=new HashSet<Long>();var changedMaterials=new HashSet<Long>();var changedGeometry=new HashSet<Long>();
        long nodes=scene.textures.forEachChanged(old.textures,(id,value)->{if(!Objects.equals(value,old.textures.get(id)))changedTextures.add(id);});
        nodes+=scene.materials.forEachChanged(old.materials,(id,value)->{if(!Objects.equals(value,old.materials.get(id)))changedMaterials.add(id);});
        nodes+=scene.meshes.forEachChanged(old.meshes,(id,value)->{if(!sameGeometry(value,old.meshes.get(id)))changedGeometry.add(id);});
        var current=new Selection(scene,items);var prior=new Selection(old,previous);
        for(long texture:changedTextures)for(long material:scene.materialsUsingTexture(texture)){current.dependencies++;changedMaterials.add(material);}
        var changes=RtxptScene.changedItems(old,scene,previous,items);nodes+=changes.inspectedNodes();
        var meshItems=new LinkedHashMap<String,RtxptScene.Item>();var updates=new LinkedHashMap<String,RtxptScene.Item>();
        for(var item:changes.upserts()){
            updates.put(item.name(),item);
            if(!prior.contains(item))meshItems.putIfAbsent(RtxptScene.meshName(item),item);
        }
        for(long mesh:changedGeometry)for(var item:current.variants(mesh))meshItems.putIfAbsent(RtxptScene.meshName(item),item);
        // Geometry replacement must reattach exactly its users, including frame primitives.
        for(var item:meshItems.values()){
            for(long id:scene.instancesUsingMesh(item.mesh())){current.dependencies++;var user=scene.item(id);if(RtxptScene.meshName(user).equals(RtxptScene.meshName(item)))updates.put(user.name(),user);}
            for(int index=scene.instances.size();index<items.size();index++){var user=items.get(index);current.reads++;if(RtxptScene.meshName(user).equals(RtxptScene.meshName(item)))updates.put(user.name(),user);}
        }
        var variants=new LinkedHashMap<String,RtxptScene.Variant>();
        for(var item:meshItems.values()){
            var previousMesh=old.meshes.get(item.mesh());boolean resident=previousMesh!=null&&prior.contains(item);
            var previousMaterials=resident?old.materialIds(previousMesh):Set.<Long>of();
            for(long id:scene.materialIds(scene.meshes.get(item.mesh()))){current.dependencies++;
                if(resident&&previousMaterials.contains(id)&&!changedMaterials.contains(id))continue;
                var variant=new RtxptScene.Variant(id,item.boundary(),item.visibility());variants.put(variant.name(),variant);
            }
        }
        for(long id:changedMaterials){if(!scene.materials.containsKey(id))continue;
            for(long mesh:scene.meshesUsingMaterial(id)){current.dependencies++;
                for(var item:current.variants(mesh)){var variant=new RtxptScene.Variant(id,item.boundary(),item.visibility());variants.put(variant.name(),variant);}
            }
        }
        Map<Long,Path> textureFiles=existingTextures instanceof RtxptPersistentMap<Path> persistent
            ?new RtxptPersistentMap<>(persistent):new TextureOverlay(existingTextures);
        var retired=new ArrayList<Path>();
        for(long id:changedTextures){var path=textureFiles.remove(id);if(path!=null)retired.add(path);}
        var translated=new RtxptScene();
        for(var item:meshItems.values())translated.meshes.put(item.mesh(),scene.meshes.get(item.mesh()));
        for(var variant:variants.values()){
            var material=scene.materials.get(variant.material());translated.materials.put(material.id(),material);
            for(long id:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()}){
                current.dependencies++;if(id>=0)translated.textures.put(id,scene.textures.get(id));
            }
        }
        boolean hasWork=!meshItems.isEmpty()||!variants.isEmpty()||!changes.removals().isEmpty()||!updates.isEmpty()||!changedTextures.isEmpty();
        boolean mappingsChanged=!meshItems.isEmpty()||!old.sameRetainedStructure(scene)||!sameDynamicStructure(old,scene,previous,items);
        long dependencies=current.dependencies+prior.dependencies,reads=current.reads+prior.reads+changes.itemReads();
        if(!hasWork)return new Prepared(null,translated,textureFiles,0,0,0,false,mappingsChanged,null,retired,new Work(reads,dependencies,nodes,0),null,List.of());
        var coverageChanged=new ArrayList<String>();
        for(var variant:variants.values()){
            var a=old.materials.get(variant.material());var b=scene.materials.get(variant.material());
            if(a==null||!alphaEquivalent(old.textures.get(a.baseColorTextureId()),scene.textures.get(b.baseColorTextureId())))coverageChanged.add(variant.name());
        }
        int writtenTextures=0;for(long id:translated.textures.keySet())if(textureFiles.get(id)==null)writtenTextures++;
        var memory=RtxptMemoryResources.pack(old,scene,meshItems.values(),variants.values(),updates.values(),changes.removals(),coverageChanged);
        return new Prepared(null,translated,textureFiles,meshItems.size(),variants.size(),changedTextures.size(),true,mappingsChanged,null,List.of(),new Work(reads,dependencies,nodes,0),memory,List.copyOf(meshItems.values()));
    }
    private static boolean sameDynamicStructure(RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,List<RtxptScene.Item> items){
        int before=previous.size()-old.instances.size(),after=items.size()-scene.instances.size();if(before!=after)return false;
        for(int index=0;index<after;index++)if(!RtxptScene.sameStructure(previous.get(old.instances.size()+index),items.get(scene.instances.size()+index)))return false;
        return true;
    }
    /** External callers may supply an ordinary immutable map; do not copy its unrelated entries. */
    private static final class TextureOverlay extends AbstractMap<Long,Path> {
        final Map<Long,Path> base;final Map<Long,Path> changed=new HashMap<>();final Set<Long> removed=new HashSet<>();
        TextureOverlay(Map<Long,Path> base){this.base=base;}
        @Override public Path get(Object key){return changed.containsKey(key)?changed.get(key):removed.contains(key)?null:base.get(key);}
        @Override public Path put(Long key,Path value){var old=get(key);changed.put(key,value);removed.remove(key);return old;}
        @Override public Path remove(Object key){var old=get(key);changed.remove(key);removed.add((Long)key);return old;}
        @Override public Set<Entry<Long,Path>> entrySet(){var entries=new HashMap<>(base);removed.forEach(entries::remove);entries.putAll(changed);return Collections.unmodifiableMap(entries).entrySet();}
    }
    /** Only attributes consumed by the resident geometry importer participate.
     * Baseline coordinates and authored tint have independent GPU publications. */
    static boolean sameGeometry(MeshAsset a,MeshAsset b){
        return a==b || a!=null&&b!=null&&a.positions().equals(b.positions())&&a.normals().equals(b.normals())
            &&a.tangents().equals(b.tangents())&&a.textureCoordinates().equals(b.textureCoordinates())
            &&a.triangleIndices().equals(b.triangleIndices())&&a.triangleMaterialIds().equals(b.triangleMaterialIds());
    }
}
