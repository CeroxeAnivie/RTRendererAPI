package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** A model contains only new geometry and affected material/texture dependencies. */
final class RtxptDelta {
    record Work(long itemReads,long dependencyVisits,long inspectedMapNodes,int exportedModels) { }
    record Prepared(RtxptScene translated,int meshes,int materials,int textures,boolean hasWork,boolean mappingsChanged,Work work,
                    java.nio.ByteBuffer memory,List<RtxptScene.Item> changedVariants,
                    List<RtxptScene.Variant> materialVariants,List<String> removedMaterials,
                    List<RtxptScene.Item> removedMeshVariants) { }
    static void commitMeshVariants(Map<Long,Map<String,RtxptScene.Item>> resident,Prepared delta){
        for(var item:delta.removedMeshVariants()){
            var variants=resident.get(item.mesh());if(variants==null)continue;
            variants.remove(RtxptScene.meshName(item));if(variants.isEmpty())resident.remove(item.mesh());
        }
        for(var item:delta.changedVariants())resident.computeIfAbsent(item.mesh(),id->new LinkedHashMap<>())
                .put(RtxptScene.meshName(item),item);
    }
    private static boolean imported(Map<Long,Map<String,RtxptScene.Item>> resident,RtxptScene.Item item){
        var variants=resident.get(item.mesh());return variants!=null&&variants.containsKey(RtxptScene.meshName(item));
    }
    static boolean alphaEquivalent(TextureAsset a,TextureAsset b){
        if(a==b)return true;if(a==null||b==null)return false;
        if(a.width()!=b.width()||a.height()!=b.height()||a.mipLevelCount()!=b.mipLevelCount()||a.filter()!=b.filter()||a.addressU()!=b.addressU()||a.addressV()!=b.addressV())return false;
        var patch=b.sourcePatch().orElse(null);
        if(patch!=null&&b.parentGeneration()==a.generation()){
            var original=a.rgba8();var pixels=patch.rgba8();int stride=a.mipWidth(patch.mipLevel())*4,offset=a.mipByteOffset(patch.mipLevel());
            for(int y=0;y<patch.height();y++)for(int x=0;x<patch.width();x++)if(original.get(offset+(patch.y()+y)*stride+(patch.x()+x)*4+3)!=pixels.get(y*patch.rowPitch()+x*4+3))return false;
            return true;
        }
        var x=a.rgba8();var y=b.rgba8();
        // Exact full-content republishes are common immutable generations. Let
        // the JDK compare their contiguous bytes before walking alpha channels.
        if(x.equals(y))return true;
        for(int i=3;i<x.remaining();i+=4)if(x.get(i)!=y.get(i))return false;return true;
    }
    /** One prepare only: immutable generations are keyed by object identity. */
    static final class AlphaPairs {
        private final IdentityHashMap<TextureAsset,IdentityHashMap<TextureAsset,Boolean>> pairs=new IdentityHashMap<>();
        int comparisons;
        boolean equivalent(TextureAsset a,TextureAsset b){
            if(a==b||a==null||b==null)return a==b;
            var next=pairs.computeIfAbsent(a,key->new IdentityHashMap<>());
            var known=next.get(b);if(known!=null)return known;
            boolean equal=alphaEquivalent(a,b);++comparisons;next.put(b,equal);return equal;
        }
    }
    private static final class Selection {
        final RtxptScene scene;final List<RtxptScene.Item> items;
        final Map<Long,List<RtxptScene.Item>> cache=new HashMap<>();
        final Map<Long,Map<String,RtxptScene.Item>> importedCache=new HashMap<>();
        private Map<Long,List<RtxptScene.Item>> transientByMesh;
        private Map<String,RtxptScene.Item> transientByName;
        long reads,dependencies;
        Selection(RtxptScene scene,List<RtxptScene.Item> items){this.scene=scene;this.items=items;}
        List<RtxptScene.Item> variants(long mesh){return cache.computeIfAbsent(mesh,id->{
            var variants=new LinkedHashMap<String,RtxptScene.Item>();
            for(long instance:scene.instancesUsingMesh(id)){dependencies++;var item=scene.item(instance);variants.putIfAbsent(RtxptScene.meshName(item),item);}
            for(var item:transientUsers(id)){dependencies++;variants.putIfAbsent(RtxptScene.meshName(item),item);}
            return List.copyOf(variants.values());
        });}
        Map<String,RtxptScene.Item> importedVariants(long mesh){return importedCache.computeIfAbsent(mesh,id->{
            var result=new LinkedHashMap<String,RtxptScene.Item>();
            for(var item:variants(id))result.put(RtxptScene.meshName(item),item);
            return result;
        });}
        // A transaction can change many meshes used by frame primitives. Index
        // the immutable transient tail once rather than reading all P entries
        // for each of M changed meshes, variants and retiring node names.
        private void indexTransient(){
            if(transientByMesh!=null)return;
            transientByMesh=new HashMap<>();transientByName=new HashMap<>();
            for(int index=scene.instances.size();index<items.size();index++){
                var item=items.get(index);reads++;
                transientByMesh.computeIfAbsent(item.mesh(),id->new ArrayList<>()).add(item);
                transientByName.put(item.name(),item);
            }
        }
        List<RtxptScene.Item> transientUsers(long mesh){indexTransient();return transientByMesh.getOrDefault(mesh,List.of());}
        RtxptScene.Item transientItem(String name){indexTransient();return transientByName.get(name);}
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
    static Prepared prepare(RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,
            List<RtxptScene.Item> items,Map<Long,TextureAsset> residentTextures,RtxptMemoryResources.PacketPool packets) {
        return prepare(old,scene,previous,items,residentTextures,Map.of(),packets);
    }
    static Prepared prepare(RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,
            List<RtxptScene.Item> items,Map<Long,TextureAsset> residentTextures,
            Map<Long,Map<String,RtxptScene.Variant>> residentMaterials,RtxptMemoryResources.PacketPool packets) {
        return prepare(old,scene,previous,items,residentTextures,residentMaterials,null,packets);
    }
    static Prepared prepare(RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,
            List<RtxptScene.Item> items,Map<Long,TextureAsset> residentTextures,
            Map<Long,Map<String,RtxptScene.Variant>> residentMaterials,
            Map<Long,Map<String,RtxptScene.Item>> residentMeshes,RtxptMemoryResources.PacketPool packets) {
        var changedTextures=new HashSet<Long>();var changedMaterials=new HashSet<Long>();var changedGeometry=new HashSet<Long>();
        long nodes=scene.textures.forEachChanged(old.textures,(id,value)->{if(!Objects.equals(value,old.textures.get(id)))changedTextures.add(id);});
        nodes+=scene.materials.forEachChanged(old.materials,(id,value)->{if(!Objects.equals(value,old.materials.get(id)))changedMaterials.add(id);});
        nodes+=scene.meshes.forEachChanged(old.meshes,(id,value)->{if(!sameGeometry(value,old.meshes.get(id)))changedGeometry.add(id);});
        var current=new Selection(scene,items);var prior=new Selection(old,previous);
        // Snapshot-only callers have no persistent native registry. Resolve
        // only requested mesh dependencies; an independent scalar/pose edit
        // must not read or index the complete previous frame.
        java.util.function.LongFunction<Map<String,RtxptScene.Item>> importedMeshes=residentMeshes==null
                ?prior::importedVariants:residentMeshes::get;
        java.util.function.Predicate<RtxptScene.Item> isImported=residentMeshes==null
                ?prior::contains:item->imported(residentMeshes,item);
        for(long texture:changedTextures)for(long material:scene.materialsUsingTexture(texture)){current.dependencies++;changedMaterials.add(material);}
        var changes=RtxptScene.changedItems(old,scene,previous,items);nodes+=changes.inspectedNodes();
        var lifetimeMeshes=new HashSet<Long>(changedGeometry);
        for(var item:changes.upserts()){
            var before=item.name().startsWith("i")?old.item(Long.parseLong(item.name(),1,item.name().length(),10)):prior.transientItem(item.name());
            if(!RtxptScene.sameStructure(before,item)){lifetimeMeshes.add(item.mesh());if(before!=null)lifetimeMeshes.add(before.mesh());}
        }
        for(var name:changes.removals()){
            var before=name.startsWith("i")?old.item(Long.parseLong(name,1,name.length(),10)):prior.transientItem(name);
            if(before!=null)lifetimeMeshes.add(before.mesh());
        }
        // Keep every active semantic variant and at most one dormant variant
        // per still-declared mesh. Arbitrary visibility/boundary history cannot
        // grow a permanent native cache; the last representation survives a
        // no-instance interval until explicit public mesh removal/reset.
        var removedMeshItems=new LinkedHashMap<String,RtxptScene.Item>();
        for(long mesh:lifetimeMeshes){
            var known=importedMeshes.apply(mesh);if(known==null)continue;
            var active=new HashSet<String>();
            if(scene.meshes.containsKey(mesh))for(var item:current.variants(mesh))active.add(RtxptScene.meshName(item));
            boolean dormantKept=false;
            for(var entry:known.entrySet()){
                if(active.contains(entry.getKey()))continue;
                if(scene.meshes.containsKey(mesh)&&!dormantKept){dormantKept=true;continue;}
                removedMeshItems.put(entry.getKey(),entry.getValue());
            }
        }
        var meshItems=new LinkedHashMap<String,RtxptScene.Item>();var updates=new LinkedHashMap<String,RtxptScene.Item>();
        for(var item:changes.upserts()){
            updates.put(item.name(),item);
            if(!isImported.test(item))meshItems.putIfAbsent(RtxptScene.meshName(item),item);
        }
        for(long mesh:changedGeometry)if(scene.meshes.containsKey(mesh)){
            for(var item:current.variants(mesh))meshItems.putIfAbsent(RtxptScene.meshName(item),item);
            var known=importedMeshes.apply(mesh);if(known!=null)for(var entry:known.entrySet())
                if(!removedMeshItems.containsKey(entry.getKey()))meshItems.putIfAbsent(entry.getKey(),entry.getValue());
        }
        // Native memory meshes omit unused tangent storage. A later normal-map
        // enable prepares only this material's immutable mesh variants before
        // publishing the material; no CPU copy of every imported mesh is kept.
        for(long id:changedMaterials){
            var material=scene.materials.get(id);var before=old.materials.get(id);
            if(material==null||material.normalTextureId()<0||(before!=null&&before.normalTextureId()>=0))continue;
            for(long mesh:scene.meshesUsingMaterial(id)){current.dependencies++;
                for(var item:current.variants(mesh))meshItems.putIfAbsent(RtxptScene.meshName(item),item);
                var known=importedMeshes.apply(mesh);if(known!=null)for(var entry:known.entrySet())
                    if(!removedMeshItems.containsKey(entry.getKey()))meshItems.putIfAbsent(entry.getKey(),entry.getValue());}
        }
        // Geometry replacement must reattach exactly its users, including frame primitives.
        for(var item:meshItems.values()){
            for(long id:scene.instancesUsingMesh(item.mesh())){current.dependencies++;var user=scene.item(id);if(RtxptScene.meshName(user).equals(RtxptScene.meshName(item)))updates.put(user.name(),user);}
            for(var user:current.transientUsers(item.mesh())){current.dependencies++;if(RtxptScene.meshName(user).equals(RtxptScene.meshName(item)))updates.put(user.name(),user);}
        }
        var variants=new LinkedHashMap<String,RtxptScene.Variant>();
        for(var item:meshItems.values()){
            var previousMesh=old.meshes.get(item.mesh());boolean resident=previousMesh!=null&&isImported.test(item);
            var previousMaterials=resident?old.materialIds(previousMesh):Set.<Long>of();
            for(long id:scene.materialIds(scene.meshes.get(item.mesh()))){current.dependencies++;
                if(resident&&previousMaterials.contains(id)&&!changedMaterials.contains(id))continue;
                var variant=new RtxptScene.Variant(id,item.boundary(),item.visibility());variants.put(variant.name(),variant);
            }
        }
        var removedMaterials=new ArrayList<String>();
        for(long id:changedMaterials){
            var resident=residentMaterials.get(id);
            if(!scene.materials.containsKey(id)){
                if(resident!=null)removedMaterials.addAll(resident.keySet());
                continue;
            }
            // Definitions outlive their current geometry users. Keep every
            // already imported variant current as well, including texture-ID
            // rebinding before an old texture is explicitly removed.
            if(resident!=null)variants.putAll(resident);
            for(long mesh:scene.meshesUsingMaterial(id)){current.dependencies++;
                for(var item:current.variants(mesh)){var variant=new RtxptScene.Variant(id,item.boundary(),item.visibility());variants.put(variant.name(),variant);}
            }
        }
        var translated=new RtxptScene();
        for(var item:meshItems.values())translated.meshes.put(item.mesh(),scene.meshes.get(item.mesh()));
        for(var variant:variants.values()){
            var material=scene.materials.get(variant.material());translated.materials.put(material.id(),material);
            for(long id:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()}){
                current.dependencies++;if(id>=0)translated.textures.put(id,scene.textures.get(id));
            }
        }
        boolean hasWork=!meshItems.isEmpty()||!variants.isEmpty()||!changes.removals().isEmpty()||!updates.isEmpty()||!changedTextures.isEmpty()||!removedMaterials.isEmpty()||!removedMeshItems.isEmpty();
        boolean mappingsChanged=!meshItems.isEmpty()||!old.sameRetainedStructure(scene)||!sameDynamicStructure(old,scene,previous,items);
        if(!hasWork)return new Prepared(translated,0,0,0,false,mappingsChanged,
                new Work(current.reads+prior.reads+changes.itemReads(),current.dependencies+prior.dependencies,nodes,0),null,List.of(),List.of(),List.of(),List.of());
        var coverageChanged=new ArrayList<String>();
        var alphaPairs=new AlphaPairs();
        for(var variant:variants.values()){
            var a=old.materials.get(variant.material());var b=scene.materials.get(variant.material());
            if(a==null||!alphaPairs.equivalent(old.textures.get(a.baseColorTextureId()),scene.textures.get(b.baseColorTextureId())))coverageChanged.add(variant.name());
        }
        // Only known native residents are eligible. Limit selection to changed
        // variants and retiring users, never a complete resident geometry scan.
        // Decode resolves all references before the packet retires any owner.
        var inputSources=new LinkedHashMap<String,RtxptScene.Item>();
        if(!meshItems.isEmpty()){
            for(var item:meshItems.values())if(old.meshes.containsKey(item.mesh()))
                {var known=importedMeshes.apply(item.mesh());if(known!=null)for(var source:known.values())inputSources.putIfAbsent(RtxptScene.meshName(source),source);}
            for(var source:removedMeshItems.values())if(old.meshes.containsKey(source.mesh()))inputSources.putIfAbsent(RtxptScene.meshName(source),source);
            for(var name:changes.removals()){
                if(name.startsWith("i")){
                    var source=old.item(Long.parseLong(name,1,name.length(),10));
                    if(source!=null)inputSources.putIfAbsent(RtxptScene.meshName(source),source);
                }else{
                    var source=prior.transientItem(name);
                    if(source!=null)inputSources.putIfAbsent(RtxptScene.meshName(source),source);
                }
            }
        }
        var memory=RtxptMemoryResources.pack(old,scene,meshItems.values(),variants.values(),updates.values(),changes.removals(),coverageChanged,residentTextures,packets,removedMaterials,inputSources.values(),List.copyOf(removedMeshItems.keySet()));
        long dependencies=current.dependencies+prior.dependencies,reads=current.reads+prior.reads+changes.itemReads();
        return new Prepared(translated,meshItems.size(),variants.size(),changedTextures.size(),true,mappingsChanged,new Work(reads,dependencies,nodes,0),memory,List.copyOf(meshItems.values()),List.copyOf(variants.values()),List.copyOf(removedMaterials),List.copyOf(removedMeshItems.values()));
    }
    private static boolean sameDynamicStructure(RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,List<RtxptScene.Item> items){
        int before=previous.size()-old.instances.size(),after=items.size()-scene.instances.size();if(before!=after)return false;
        for(int index=0;index<after;index++)if(!RtxptScene.sameStructure(previous.get(old.instances.size()+index),items.get(scene.instances.size()+index)))return false;
        return true;
    }
    /** Only attributes consumed by the resident geometry importer participate.
     * Baseline coordinates and authored tint have independent GPU publications. */
    static boolean sameGeometry(MeshAsset a,MeshAsset b){
        return a==b || a!=null&&b!=null&&sameInputChannels(a,b)&&a.triangleMaterialIds().equals(b.triangleMaterialIds());
    }
    static boolean sameInputChannels(MeshAsset a,MeshAsset b){
        return a==b || a!=null&&b!=null&&a.positions().equals(b.positions())&&a.normals().equals(b.normals())
            &&a.tangents().equals(b.tangents())&&a.textureCoordinates().equals(b.textureCoordinates())
            &&a.triangleIndices().equals(b.triangleIndices());
    }
}
