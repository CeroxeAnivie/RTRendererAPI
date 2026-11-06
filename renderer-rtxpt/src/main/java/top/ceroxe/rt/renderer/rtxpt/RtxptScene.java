package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Retained memory resources. No tracing, file export, denoising or scheduling. */
final class RtxptScene {
    final RtxptPersistentMap<TextureAsset> textures;
    final RtxptPersistentMap<MaterialAsset> materials;
    final RtxptPersistentMap<MeshAsset> meshes;
    final RtxptPersistentMap<SceneInstance> instances;
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
    List<SceneLight> sortedLights(){if(sortedLights==null)sortedLights=List.copyOf(lights.values());return sortedLights;}
    final Map<String,MediumBoundary> interiors=new LinkedHashMap<>();
    final Map<String,Float> materialIors=new LinkedHashMap<>();
    RtxptScene() {
        textures=new RtxptPersistentMap<>();materials=new RtxptPersistentMap<>();meshes=new RtxptPersistentMap<>();instances=new RtxptPersistentMap<>();
        meshMaterials=new RtxptPersistentMap<>();textureUsers=new RtxptPersistentMap<>();materialUsers=new RtxptPersistentMap<>();meshUsers=new RtxptPersistentMap<>();
        itemTable=new RtxptPersistentMap<>();
        lights=new RtxptLightMap();sparseSources=new RtxptPersistentMap<>();
    }
    RtxptScene(RtxptScene src) {
        textures=new RtxptPersistentMap<>(src.textures);materials=new RtxptPersistentMap<>(src.materials);meshes=new RtxptPersistentMap<>(src.meshes);
        instances=new RtxptPersistentMap<>(src.instances);residentItems=src.residentItems;lights=new RtxptLightMap(src.lights);sortedLights=src.sortedLights;meshMaterials=new RtxptPersistentMap<>(src.meshMaterials);
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
        if(tx.reset()){meshMaterials.clear();textureUsers.clear();materialUsers.clear();meshUsers.clear();textures.clear();materials.clear();meshes.clear();instances.clear();lights.clear();sparseSources.clear();itemTable.clear();itemStructure=new Object();residentItems=null;geometryBytes=textureBytes=0;}
        if(tx.reset()||!tx.upserts().lights().isEmpty()||tx.removals().lightIds().hasRemaining())sortedLights=null;
        var r=tx.removals();var u=tx.upserts();
        for(var t:u.textures())t.validateReplacementOf(textures.get(t.id()));
        // Reverse dependencies are immutable sets in persistent maps. Only changed paths are copied.
        var changedMaterials=new HashSet<Long>();u.materials().forEach(m->changedMaterials.add(m.id()));collectIds(r.materialIds(),changedMaterials);
        var changedMeshes=new HashSet<Long>();u.meshes().forEach(m->changedMeshes.add(m.id()));collectIds(r.meshIds(),changedMeshes);
        var changedInstances=new HashSet<Long>();u.instances().forEach(i->changedInstances.add(i.id()));collectIds(r.instanceIds(),changedInstances);
        var changedTextures=new HashSet<Long>();u.textures().forEach(t->changedTextures.add(t.id()));collectIds(r.textureIds(),changedTextures);
        for(long id:changedMeshes){var old=meshes.get(id);if(old!=null)geometryBytes=Math.subtractExact(geometryBytes,RendererResourceOptions.meshBytes(old));}
        for(long id:changedTextures){var old=textures.get(id);if(old!=null)textureBytes=Math.subtractExact(textureBytes,old.rgba8().remaining());}
        for(long id:changedMaterials){var old=materials.get(id);if(old!=null)for(long texture:textureIds(old))dependency(textureUsers,texture,id,false);}
        for(long id:changedMeshes){var old=meshes.get(id);if(old!=null)for(long material:materialIds(old))dependency(materialUsers,material,id,false);}
        for(long id:changedInstances){var old=instances.get(id);if(old!=null)dependency(meshUsers,old.meshAssetId(),id,false);}
        remove(textures,r.textureIds());remove(materials,r.materialIds());remove(meshes,r.meshIds());remove(instances,r.instanceIds());remove(lights,r.lightIds());
        u.textures().forEach(t->textures.put(t.id(),t));u.materials().forEach(m->materials.put(m.id(),m));
        u.meshes().forEach(m->meshes.put(m.id(),m));u.instances().forEach(i->instances.put(i.id(),i));u.lights().forEach(l->lights.put(l.id(),l));
        var removedMeshMaterials=r.meshIds();while(removedMeshMaterials.hasRemaining())meshMaterials.remove(removedMeshMaterials.get());
        for(long id:changedMeshes){var value=meshes.get(id);if(value!=null)geometryBytes=Math.addExact(geometryBytes,RendererResourceOptions.meshBytes(value));}
        for(long id:changedTextures){var value=textures.get(id);if(value!=null)textureBytes=Math.addExact(textureBytes,value.rgba8().remaining());}
        for(var m:u.materials()){for(long t:textureIds(m))if(t>=0){++referenceChecks;if(!textures.containsKey(t))throw new IllegalArgumentException("Missing texture "+t);dependency(textureUsers,t,m.id(),true);}
            if(nativeContract)validateMaterial(m);}
        for(var m:u.meshes())for(long id:materialIds(m)){++referenceChecks;if(!materials.containsKey(id))throw new IllegalArgumentException("Missing triangle material");dependency(materialUsers,id,m.id(),true);}
        for(var i:u.instances()){++referenceChecks;if(!meshes.containsKey(i.meshAssetId()))throw new IllegalArgumentException("Missing mesh "+i.meshAssetId());dependency(meshUsers,i.meshAssetId(),i.id(),true);}
        rejectReferencedRemoval(textures,textureUsers,r.textureIds(),"texture");rejectReferencedRemoval(materials,materialUsers,r.materialIds(),"material");rejectReferencedRemoval(meshes,meshUsers,r.meshIds(),"mesh");
        if(nativeContract){var check=new HashSet<>(changedInstances);for(long id:changedMeshes){var users=meshUsers.get(id);if(users!=null)check.addAll(users.keySet());}
            for(long id:check){var i=instances.get(id);if(i!=null)validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.renderState(),i.castsShadow(),i.visibilityMask());}}
        for(long id:changedInstances){var instance=instances.get(id);var before=itemTable.get(id);
            var after=instance==null?null:new Item("i"+id,instance.meshAssetId(),instance.transform(),instance.mediumBoundary().orElse(null),instance.surfaceVisibility());
            if(Objects.equals(before,after))continue;
            if(!sameStructure(before,after))itemStructure=new Object();
            if(after==null)itemTable.remove(id);else itemTable.put(id,after);residentItems=null;
        }
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
    private static void dependency(RtxptPersistentMap<RtxptPersistentMap<Boolean>> index,long target,long source,boolean add){
        if(target<0)return;var old=index.get(target);if((old!=null&&old.containsKey(source))==add)return;
        var next=old==null?new RtxptPersistentMap<Boolean>():new RtxptPersistentMap<>(old);if(add)next.put(source,true);else next.remove(source);
        if(next.isEmpty())index.remove(target);else index.put(target,next);
    }
    private void rejectReferencedRemoval(Map<Long,?> table,RtxptPersistentMap<RtxptPersistentMap<Boolean>> users,LongBuffer removed,String kind){
        while(removed.hasRemaining()){long id=removed.get();++referenceChecks;if(!table.containsKey(id)&&users.containsKey(id))throw new IllegalArgumentException("Referenced "+kind+" removed: "+id);}
    }
    private static void validateMaterial(MaterialAsset m){
        if(m.shadingModel()!=MaterialAsset.ShadingModel.PHYSICALLY_BASED || m.surfaceOverlay().enabled())
            throw new IllegalArgumentException("RTXPT uses native physical materials; custom surface equations/overlays belong to Vulkan");
    }
    void validate() {
        for(var m:materials.values())validateMaterial(m);
        for(var i:instances.values())validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.renderState(),i.castsShadow(),i.visibilityMask());
    }
    void validateInstance(long id,AffineTransform transform,MediumBoundary b,InstanceRenderState state,boolean shadow,int mask) {
        var m=meshes.get(id);if(m==null)throw new IllegalArgumentException("Missing mesh "+id);
        if(b!=null && !m.isClosedManifold())throw new IllegalArgumentException("Medium requires a closed outward-wound manifold mesh");
        if(!state.equals(InstanceRenderState.defaults()) || !shadow || mask!=255)
            throw new IllegalArgumentException("Custom ray masks, no-shadow instances and game surface overrides belong to Vulkan");
        // Upstream scene nodes use TRS. Reject shear instead of silently changing geometry.
        for(int a=0;a<3;a++)for(int c=a+1;c<3;c++) {
            double dot=0,aa=0,cc=0;for(int r=0;r<3;r++){double x=transform.element(r*4+a),y=transform.element(r*4+c);dot+=x*y;aa+=x*x;cc+=y*y;}
            if(Math.abs(dot)>1e-5*Math.sqrt(aa*cc))throw new IllegalArgumentException("RTXPT scene transforms must be TRS without shear");
        }
    }
    record Item(String name,long mesh,AffineTransform transform,MediumBoundary boundary,float visibility) { }
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
        var a=new ArrayList<Item>(f.primitiveBatch().primitives().size());
        int k=0;for(var i:f.primitiveBatch().primitives()) {
            validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.renderState(),i.castsShadow(),i.visibilityMask());
            a.add(new Item("p"+k++,i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.surfaceVisibility()));
        }
        return new Items((Items)residentItems,a);
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
    static FloatBuffer floats(int n){return ByteBuffer.allocateDirect(Math.multiplyExact(n,4)).order(ByteOrder.nativeOrder()).asFloatBuffer();}
    void uploadInteriors(long handle) {
        if(!materialIors.isEmpty()) {
            var iors=floats(materialIors.size());
            materialIors.values().forEach(iors::put);
            RtxptNative.materialIors(handle,materialIors.keySet().toArray(String[]::new),iors.flip());
        }
        if(interiors.isEmpty())return;
        var data=floats(interiors.size()*5);for(var b:interiors.values()) {
            var m=b.medium();data.put(m.absorption().red()).put(m.absorption().green()).put(m.absorption().blue()).put(m.indexOfRefraction()).put(b.priority());
        }
        RtxptNative.interiors(handle,interiors.keySet().toArray(String[]::new),data.flip());
    }
    /** Missing normals require flat triangle corners in the resident mesh representation.
     * Authored shading and all vertex attributes stay intact during memory packing. */
    record ExportGeometry(FloatBuffer positions,FloatBuffer normals,FloatBuffer tangents,
                          FloatBuffer uvs,IntBuffer colors,IntBuffer indices,int vertexCount) {
        static ExportGeometry from(MeshAsset mesh) {
            var positions=mesh.positions();var normals=mesh.normals();var tangents=mesh.tangents();
            var uvs=mesh.textureCoordinates();var colors=mesh.vertexColorsRgba8();var indices=mesh.triangleIndices();
            if(normals.hasRemaining())return new ExportGeometry(positions,normals,tangents,uvs,colors,indices,mesh.vertexCount());
            int corners=indices.remaining();
            var p=FloatBuffer.allocate(Math.multiplyExact(corners,3));
            var n=FloatBuffer.allocate(Math.multiplyExact(corners,3));
            var t=FloatBuffer.allocate(tangents.hasRemaining()?Math.multiplyExact(corners,4):0);
            var uv=FloatBuffer.allocate(uvs.hasRemaining()?Math.multiplyExact(corners,2):0);
            var c=IntBuffer.allocate(colors.hasRemaining()?corners:0);var remapped=IntBuffer.allocate(corners);
            for(int first=0;first<corners;first+=3) {
                int a=indices.get(first)*3,b=indices.get(first+1)*3,d=indices.get(first+2)*3;
                double ux=(double)positions.get(b)-positions.get(a),uy=(double)positions.get(b+1)-positions.get(a+1),uz=(double)positions.get(b+2)-positions.get(a+2);
                double vx=(double)positions.get(d)-positions.get(a),vy=(double)positions.get(d+1)-positions.get(a+1),vz=(double)positions.get(d+2)-positions.get(a+2);
                double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
                // A zero-area primitive cannot be hit; keep its exported attributes finite.
                if(length==0){nx=0;ny=0;nz=1;length=1;}
                for(int j=0;j<3;j++) {
                    int vertex=indices.get(first+j);copyVertex(positions,p,vertex,3);
                    n.put((float)(nx/length)).put((float)(ny/length)).put((float)(nz/length));
                    if(t.capacity()!=0)copyVertex(tangents,t,vertex,4);
                    if(uv.capacity()!=0)copyVertex(uvs,uv,vertex,2);
                    if(c.capacity()!=0)c.put(colors.get(vertex));remapped.put(first+j);
                }
            }
            return new ExportGeometry(p.flip(),n.flip(),t.flip(),uv.flip(),c.flip(),remapped.flip(),corners);
        }
        private static void copyVertex(FloatBuffer source,FloatBuffer target,int vertex,int components) {
            for(int component=0;component<components;component++)target.put(source.get(vertex*components+component));
        }
    }
    record Variant(long material,MediumBoundary boundary,float visibility) {
        String name(){return "m"+material+suffix(boundary,visibility);}
    }
    static String suffix(MediumBoundary boundary,float visibility) {
        String value="_v"+Integer.toHexString(Float.floatToIntBits(visibility));
        if(boundary==null)return value;
        var m=boundary.medium();var a=m.absorption();
        return value+"_b"+Integer.toHexString(Float.floatToIntBits(m.indexOfRefraction()))+"_"+boundary.priority()
            +"_"+Integer.toHexString(Float.floatToIntBits(a.red()))+"_"+Integer.toHexString(Float.floatToIntBits(a.green()))+"_"+Integer.toHexString(Float.floatToIntBits(a.blue()));
    }
    static String meshName(Item i){return "mesh"+i.mesh()+suffix(i.boundary(),i.visibility());}
}
