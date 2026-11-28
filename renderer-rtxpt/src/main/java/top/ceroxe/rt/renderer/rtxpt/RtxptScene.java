package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Retained memory resources. No tracing, file export, denoising or scheduling. */
final class RtxptScene {
    boolean nativeLighting;
    final RtxptSceneTable<TextureAsset> textures;
    final RtxptSceneTable<MaterialAsset> materials;
    final RtxptSceneTable<MeshAsset> meshes;
    final RtxptSceneTable<SceneInstance> instances;
    final RtxptLightMap lights;
    final RtxptPersistentMap<SparseLightSource> sparseSources;
    private record MeshMaterials(MeshAsset mesh,Set<Long> ids,List<RtxptAttributeBatch.Group> groups) { }
    private final RtxptPersistentMap<MeshMaterials> meshMaterials;
    private final RtxptPersistentMap<RtxptPersistentMap<Boolean>> textureUsers,materialUsers,meshUsers;
    private final RtxptPersistentMap<Item> itemTable;
    private Object itemStructure=new Object();
    private long geometryBytes,textureBytes;
    long referenceChecks;
    Set<Long> materialIds(MeshAsset mesh){
        var cached=meshMaterials.get(mesh.id());if(cached!=null&&cached.mesh()==mesh)return cached.ids();
        if(cached!=null&&cached.mesh().triangleMaterialIds().equals(mesh.triangleMaterialIds())){
            meshMaterials.put(mesh.id(),new MeshMaterials(mesh,cached.ids(),cached.groups()));return cached.ids();
        }
        var groups=RtxptAttributeBatch.groups(mesh);var ids=new LinkedHashSet<Long>();for(var group:groups)ids.add(group.material());
        var result=Set.copyOf(ids);meshMaterials.put(mesh.id(),new MeshMaterials(mesh,result,groups));return result;
    }
    List<RtxptAttributeBatch.Group> attributeGroups(MeshAsset mesh){materialIds(mesh);return meshMaterials.get(mesh.id()).groups();}
    private List<SceneLight> sortedLights;
    private List<Item> residentItems;
    private FramePrimitiveBatch cachedPrimitiveBatch;
    private List<Item> cachedFrameItems;
    private Object cachedResidentItems;
    private RtxptSceneTable<MeshAsset> cachedPrimitiveMeshes;
    void selectNativeLighting(){
        if(nativeLighting)return;
        nativeLighting=true;
        // Mode selection can follow retained inputs. The resident snapshot
        // must keep its imported identities until the next resource delta.
        for(var instance:instances.values()){
            var old=itemTable.get(instance.id());
            if(old!=null&&old.visibility()!=1){
                itemTable.put(instance.id(),new Item(old.name(),old.mesh(),old.transform(),old.boundary(),1));
                itemStructure=new Object();
            }
        }
        residentItems=null;cachedPrimitiveBatch=null;cachedFrameItems=null;
        cachedResidentItems=null;cachedPrimitiveMeshes=null;
    }
    List<SceneLight> sortedLights(){if(sortedLights==null)sortedLights=List.copyOf(lights.values());return sortedLights;}
    RtxptScene() {
        textures=new RtxptSceneTable<>();materials=new RtxptSceneTable<>();meshes=new RtxptSceneTable<>();instances=new RtxptSceneTable<>();
        meshMaterials=new RtxptPersistentMap<>();textureUsers=new RtxptPersistentMap<>();materialUsers=new RtxptPersistentMap<>();meshUsers=new RtxptPersistentMap<>();
        itemTable=new RtxptPersistentMap<>();
        lights=new RtxptLightMap();sparseSources=new RtxptPersistentMap<>();
    }
    RtxptScene(RtxptScene src) {
        nativeLighting=src.nativeLighting;
        textures=new RtxptSceneTable<>(src.textures);materials=new RtxptSceneTable<>(src.materials);meshes=new RtxptSceneTable<>(src.meshes);
        instances=new RtxptSceneTable<>(src.instances);residentItems=src.residentItems;lights=new RtxptLightMap(src.lights);sortedLights=src.sortedLights;meshMaterials=new RtxptPersistentMap<>(src.meshMaterials);
        textureUsers=new RtxptPersistentMap<>(src.textureUsers);materialUsers=new RtxptPersistentMap<>(src.materialUsers);meshUsers=new RtxptPersistentMap<>(src.meshUsers);referenceChecks=src.referenceChecks;
        sparseSources=new RtxptPersistentMap<>(src.sparseSources);
        itemTable=new RtxptPersistentMap<>(src.itemTable);itemStructure=src.itemStructure;
        geometryBytes=src.geometryBytes;textureBytes=src.textureBytes;
    }
    static <T> void remove(Map<Long,T> m,LongBuffer ids){while(ids.hasRemaining())m.remove(ids.get());}
    void retain(SceneTransaction tx) {
        retain(tx,true);
    }
    void retain(SceneTransaction tx,boolean nativeContract) {
        if(tx.reset())resetRetainedState();
        var changes=changes(tx);
        removePreviousDependencies(changes);
        applyResourceChanges(changes);
        registerNewDependencies(changes);
        rejectRemovedDependencies(changes.removals());
        if(nativeContract)validateNativeContract(changes);
        updateResidentItems(changes.changedInstances());
    }
    private record RetainChanges(SceneTransaction.Removals removals,
            List<TextureAsset> textures,List<MaterialAsset> materials,List<MeshAsset> meshes,
            List<SceneInstance> instances,List<SceneLight> lights,
            Set<Long> changedMaterials,Set<Long> changedMeshes,Set<Long> changedInstances,Set<Long> changedTextures) { }
    private void resetRetainedState(){
        meshMaterials.clear();textureUsers.clear();materialUsers.clear();meshUsers.clear();
        textures.clear();materials.clear();meshes.clear();instances.clear();lights.clear();sparseSources.clear();itemTable.clear();
        itemStructure=new Object();residentItems=null;geometryBytes=textureBytes=0;
    }
    private RetainChanges changes(SceneTransaction tx){
        var removals=tx.removals();var upserts=tx.upserts();
        for(var texture:upserts.textures())texture.validateReplacementOf(textures.get(texture.id()));
        // Exact immutable republishing is a no-op. A remove+upsert still executes both operations.
        var textureUpserts=changedUpserts(upserts.textures(),textures,TextureAsset::id,removals.textureIds().hasRemaining(),true);
        var materialUpserts=changedUpserts(upserts.materials(),materials,MaterialAsset::id,removals.materialIds().hasRemaining(),false);
        var meshUpserts=changedUpserts(upserts.meshes(),meshes,MeshAsset::id,removals.meshIds().hasRemaining(),false);
        var instanceUpserts=changedUpserts(upserts.instances(),instances,SceneInstance::id,removals.instanceIds().hasRemaining(),false);
        var lightUpserts=changedUpserts(upserts.lights(),lights,SceneLight::id,removals.lightIds().hasRemaining(),false);
        if(tx.reset()||!lightUpserts.isEmpty()||removals.lightIds().hasRemaining())sortedLights=null;
        var changedMaterials=changedIds(materialUpserts,MaterialAsset::id,removals.materialIds());
        var changedMeshes=changedIds(meshUpserts,MeshAsset::id,removals.meshIds());
        var changedInstances=changedIds(instanceUpserts,SceneInstance::id,removals.instanceIds());
        var changedTextures=changedIds(textureUpserts,TextureAsset::id,removals.textureIds());
        return new RetainChanges(removals,textureUpserts,materialUpserts,meshUpserts,instanceUpserts,lightUpserts,
            changedMaterials,changedMeshes,changedInstances,changedTextures);
    }
    private static <T> Set<Long> changedIds(List<T> upserts,java.util.function.ToLongFunction<T> identity,LongBuffer removals){
        var changed=new HashSet<Long>();upserts.forEach(value->changed.add(identity.applyAsLong(value)));collectIds(removals,changed);return changed;
    }
    private void removePreviousDependencies(RetainChanges changes){
        for(long id:changes.changedMaterials()){var old=materials.get(id);if(old!=null)for(long texture:textureIds(old))dependency(textureUsers,texture,id,false);}
        for(long id:changes.changedMeshes()){var old=meshes.get(id);if(old!=null)for(long material:materialIds(old))dependency(materialUsers,material,id,false);}
        for(long id:changes.changedInstances()){var old=instances.get(id);if(old!=null)dependency(meshUsers,old.meshAssetId(),id,false);}
    }
    private void applyResourceChanges(RetainChanges changes){
        for(long id:changes.changedMeshes()){var old=meshes.get(id);if(old!=null)geometryBytes=Math.subtractExact(geometryBytes,RendererResourceOptions.meshBytes(old));}
        for(long id:changes.changedTextures()){var old=textures.get(id);if(old!=null)textureBytes=Math.subtractExact(textureBytes,old.rgba8().remaining());}
        var removals=changes.removals();
        remove(textures,removals.textureIds());remove(materials,removals.materialIds());remove(meshes,removals.meshIds());
        remove(instances,removals.instanceIds());remove(lights,removals.lightIds());
        changes.textures().forEach(value->textures.put(value.id(),value));changes.materials().forEach(value->materials.put(value.id(),value));
        changes.meshes().forEach(value->meshes.put(value.id(),value));changes.instances().forEach(value->instances.put(value.id(),value));
        changes.lights().forEach(value->lights.put(value.id(),value));
        var removedMeshes=removals.meshIds();while(removedMeshes.hasRemaining())meshMaterials.remove(removedMeshes.get());
        for(long id:changes.changedMeshes()){var value=meshes.get(id);if(value!=null)geometryBytes=Math.addExact(geometryBytes,RendererResourceOptions.meshBytes(value));}
        for(long id:changes.changedTextures()){var value=textures.get(id);if(value!=null)textureBytes=Math.addExact(textureBytes,value.rgba8().remaining());}
    }
    private void registerNewDependencies(RetainChanges changes){
        for(var material:changes.materials())for(long texture:textureIds(material))if(texture>=0){
            ++referenceChecks;if(!textures.containsKey(texture))throw new IllegalArgumentException("Missing texture "+texture);
            dependency(textureUsers,texture,material.id(),true);
        }
        for(var mesh:changes.meshes())for(long material:materialIds(mesh)){
            ++referenceChecks;if(!materials.containsKey(material))throw new IllegalArgumentException("Missing triangle material");
            dependency(materialUsers,material,mesh.id(),true);
        }
        for(var instance:changes.instances()){
            ++referenceChecks;if(!meshes.containsKey(instance.meshAssetId()))throw new IllegalArgumentException("Missing mesh "+instance.meshAssetId());
            dependency(meshUsers,instance.meshAssetId(),instance.id(),true);
        }
    }
    private void rejectRemovedDependencies(SceneTransaction.Removals removals){
        rejectReferencedRemoval(textures,textureUsers,removals.textureIds(),"texture");
        rejectReferencedRemoval(materials,materialUsers,removals.materialIds(),"material");
        rejectReferencedRemoval(meshes,meshUsers,removals.meshIds(),"mesh");
    }
    private void validateNativeContract(RetainChanges changes){
        var check=new HashSet<>(changes.changedInstances());
        for(var instance:changes.instances())check.add(instance.id());
        for(long id:changes.changedMeshes()){var users=meshUsers.get(id);if(users!=null)check.addAll(users.keySet());}
        for(long id:check){var instance=instances.get(id);if(instance!=null)validateInstance(instance.meshAssetId(),instance.transform(),
            instance.mediumBoundary().orElse(null),instance.castsShadow(),instance.visibilityMask());}
    }
    private void updateResidentItems(Set<Long> changedInstances){
        for(long id:changedInstances){
            var instance=instances.get(id);var before=itemTable.get(id);
            var after=instance==null?null:new Item("i"+id,instance.meshAssetId(),instance.transform(),instance.mediumBoundary().orElse(null),
                nativeLighting?1:instance.surfaceVisibility());
            if(Objects.equals(before,after))continue;
            if(!sameStructure(before,after))itemStructure=new Object();
            if(after==null)itemTable.remove(id);else itemTable.put(id,after);
            residentItems=null;
        }
    }
    void retireResourceSnapshots() {
        textures.retireSnapshots(); materials.retireSnapshots();
        meshes.retireSnapshots(); instances.retireSnapshots();
    }
    void validateAssetBudget(RendererResourceOptions budget){
        budget.validateAssetBytes(geometryBytes,textureBytes);
    }
    Set<Long> materialsUsingTexture(long id){var users=textureUsers.get(id);return users==null?Set.of():users.keySet();}
    Set<Long> meshesUsingMaterial(long id){var users=materialUsers.get(id);return users==null?Set.of():users.keySet();}
    Set<Long> instancesUsingMesh(long id){var users=meshUsers.get(id);return users==null?Set.of():users.keySet();}
    Item item(long id){return itemTable.get(id);}
    boolean sameRetainedStructure(RtxptScene other){return itemStructure==other.itemStructure;}
    private static long[] textureIds(MaterialAsset m){return new long[]{m.baseColorTextureId(),m.normalTextureId(),m.metallicRoughnessTextureId(),m.emissiveTextureId()};}
    private static void collectIds(LongBuffer ids,Set<Long> output){while(ids.hasRemaining())output.add(ids.get());}
    private static <T> List<T> changedUpserts(List<T> values,Map<Long,T> resident,
            java.util.function.ToLongFunction<T> identity,boolean hasRemovals,boolean identityOnly){
        if(values.isEmpty()||hasRemovals)return values;
        ArrayList<T> result=null;
        for(int index=0;index<values.size();index++){
            T value=values.get(index),before=resident.get(identity.applyAsLong(value));
            boolean same=identityOnly?value==before:Objects.equals(value,before);
            if(same){if(result==null)result=new ArrayList<>(values.subList(0,index));}
            else if(result!=null)result.add(value);
        }
        return result==null?values:result;
    }
    private static void dependency(RtxptPersistentMap<RtxptPersistentMap<Boolean>> index,long target,long source,boolean add){
        if(target<0)return;var old=index.get(target);if((old!=null&&old.containsKey(source))==add)return;
        var next=old==null?new RtxptPersistentMap<Boolean>():new RtxptPersistentMap<>(old);if(add)next.put(source,true);else next.remove(source);
        if(next.isEmpty())index.remove(target);else index.put(target,next);
    }
    private void rejectReferencedRemoval(Map<Long,?> table,RtxptPersistentMap<RtxptPersistentMap<Boolean>> users,LongBuffer removed,String kind){
        while(removed.hasRemaining()){long id=removed.get();++referenceChecks;if(!table.containsKey(id)&&users.containsKey(id))throw new IllegalArgumentException("Referenced "+kind+" removed: "+id);}
    }

    void validate() {
        for(var i:instances.values())validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.castsShadow(),i.visibilityMask());
    }
    void validateInstance(long id,AffineTransform transform,MediumBoundary b,boolean shadow,int mask) {
        var m=meshes.get(id);if(m==null)throw new IllegalArgumentException("Missing mesh "+id);
        if(b!=null && !m.isClosedManifold())throw new IllegalArgumentException("Medium requires a closed outward-wound manifold mesh");
        if(!nativeLighting&&(!shadow||mask!=255))
            throw new IllegalArgumentException("Custom ray masks and no-shadow instances require the native-lighting output contract");
        if(nativeLighting&&mask!=255&&(mask&~RtxptLightingRoles.TRAVERSAL)!=0)
            throw new IllegalArgumentException("Native lighting supports receiver=1, shadow=2, bounce=4 and dielectric=32 ray roles");
        if(nativeLighting&&mask!=255&&(mask&NativeLightingPublication.Instance.DIELECTRIC_BOUNDARY)!=0&&b==null)
            throw new IllegalArgumentException("A dielectric ray mask requires a medium boundary");
        // AffineTransform already enforces finite, invertible input. The native
        // adapter composes two original TRS nodes when a shear needs both.
    }
    record Item(String name,long mesh,AffineTransform transform,MediumBoundary boundary,float visibility,String meshName) {
        Item(String name,long mesh,AffineTransform transform,MediumBoundary boundary,float visibility){
            this(name,mesh,transform,boundary,visibility,"mesh"+mesh+suffix(boundary,visibility));
        }
    }
    private static final class Items extends AbstractList<Item> implements RandomAccess {
        final RtxptPersistentMap<Item> values;final Object structure;final List<Item> dynamic;
        Items(RtxptPersistentMap<Item> source,Object structure){values=new RtxptPersistentMap<>(source);this.structure=structure;dynamic=List.of();}
        Items(Items source,List<Item> dynamic){values=source.values;structure=source.structure;this.dynamic=List.copyOf(dynamic);}
        @Override public int size(){return values.size()+dynamic.size();}
        @Override public Item get(int index){Objects.checkIndex(index,size());return index<values.size()?values.valueAt(index):dynamic.get(index-values.size());}
        @Override public Iterator<Item> iterator(){var retained=values.values().iterator();var transientItems=dynamic.iterator();
            return new Iterator<>(){public boolean hasNext(){return retained.hasNext()||transientItems.hasNext();}public Item next(){return retained.hasNext()?retained.next():transientItems.next();}};}
    }
    List<Item> items(RenderFrameRequest f) {
        if(residentItems==null)residentItems=new Items(itemTable,itemStructure);
        if(f.primitiveBatch().primitives().isEmpty())return residentItems;
        if(cachedPrimitiveBatch==f.primitiveBatch()&&cachedResidentItems==residentItems
                &&cachedPrimitiveMeshes!=null&&cachedPrimitiveMeshes.sameSnapshot(meshes))return cachedFrameItems;
        var a=new ArrayList<Item>(f.primitiveBatch().primitives().size());
        int k=0;for(var i:f.primitiveBatch().primitives()) {
            validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.castsShadow(),i.visibilityMask());
            a.add(new Item("p"+k++,i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),nativeLighting?1:i.surfaceVisibility()));
        }
        cachedPrimitiveBatch=f.primitiveBatch();cachedResidentItems=residentItems;
        cachedPrimitiveMeshes=new RtxptSceneTable<>(meshes);
        return cachedFrameItems=new Items((Items)residentItems,a);
    }
    static boolean sameStructure(List<Item> a,List<Item> b) {
        if(a==b)return true;
        if(a instanceof Items x&&b instanceof Items y)return x.structure==y.structure&&sameStructure(x.dynamic,y.dynamic);
        if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++){
            if(!sameStructure(a.get(i),b.get(i)))return false;
        }return true;
    }
    static boolean sameItems(List<Item> a,List<Item> b){return a==b||a instanceof Items x&&b instanceof Items y&&x.values.sameSnapshot(y.values)&&x.dynamic.equals(y.dynamic);}
    static boolean sameStructure(Item a,Item b){return a==b||a!=null&&b!=null&&a.name.equals(b.name)&&a.mesh==b.mesh&&Objects.equals(a.boundary,b.boundary)&&a.visibility==b.visibility;}
    record ItemChanges(List<Item> upserts,List<String> removals,long inspectedNodes,long itemReads) {
        static final ItemChanges EMPTY=new ItemChanges(List.of(),List.of(),0,0);
    }
    static ItemChanges changedItems(RtxptScene old,RtxptScene scene,List<Item> previous,List<Item> current){
        if(current==previous)return ItemChanges.EMPTY;
        var upserts=new ArrayList<Item>();var removals=new ArrayList<String>();
        long nodes=scene.itemTable.forEachChanged(old.itemTable,(id,value)->{
            if(value==null)removals.add("i"+id);else if(!value.equals(old.itemTable.get(id)))upserts.add(value);
        });
        var oldDynamic=new HashMap<String,Item>();long reads=0;
        for(int index=old.instances.size();index<previous.size();index++){var item=previous.get(index);oldDynamic.put(item.name(),item);reads++;}
        for(int index=scene.instances.size();index<current.size();index++){var item=current.get(index);if(!item.equals(oldDynamic.remove(item.name())))upserts.add(item);reads++;}
        removals.addAll(oldDynamic.keySet());return new ItemChanges(upserts,removals,nodes,reads);
    }
    record Variant(long material,MediumBoundary boundary,float visibility,String name) {
        Variant(long material,MediumBoundary boundary,float visibility){
            this(material,boundary,visibility,"m"+material+suffix(boundary,visibility));
        }
    }
    static String suffix(MediumBoundary boundary,float visibility) {
        String value="_v"+Integer.toHexString(Float.floatToIntBits(visibility));
        if(boundary==null)return value;
        var m=boundary.medium();var a=m.absorption();
        return value+"_b"+Integer.toHexString(Float.floatToIntBits(m.indexOfRefraction()))+"_"+boundary.priority()
            +"_"+Integer.toHexString(Float.floatToIntBits(a.red()))+"_"+Integer.toHexString(Float.floatToIntBits(a.green()))+"_"+Integer.toHexString(Float.floatToIntBits(a.blue()));
    }
    static String meshName(Item i){return i.meshName();}
}
