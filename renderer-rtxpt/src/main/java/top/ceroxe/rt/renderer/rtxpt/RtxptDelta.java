package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptJson.object;

/** A model contains only new geometry and affected material/texture dependencies. */
final class RtxptDelta {
    record Prepared(Path manifest,RtxptScene translated,Map<Long,Path> textureFiles,int meshes,int materials,int textures,boolean hasWork) { }
    static boolean alphaEquivalent(TextureAsset a,TextureAsset b){
        if(a==b)return true;if(a==null||b==null)return false;
        if(a.width()!=b.width()||a.height()!=b.height()||a.addressU()!=b.addressU()||a.addressV()!=b.addressV())return false;
        var x=a.rgba8();var y=b.rgba8();for(int i=3;i<x.remaining();i+=4)if(x.get(i)!=y.get(i))return false;return true;
    }
    static Prepared prepare(Path directory,RtxptScene old,RtxptScene scene,List<RtxptScene.Item> previous,
                            List<RtxptScene.Item> items,Map<Long,Path> existingTextures)throws IOException {
        var textureFiles=new HashMap<>(existingTextures);
        var changedTextures=new HashSet<Long>();
        for(var entry:scene.textures.entrySet())if(!Objects.equals(old.textures.get(entry.getKey()),entry.getValue())){
            changedTextures.add(entry.getKey());textureFiles.remove(entry.getKey());
        }
        textureFiles.keySet().retainAll(scene.textures.keySet());
        var oldItems=new HashMap<String,RtxptScene.Item>();previous.forEach(i->oldItems.put(i.name(),i));
        var oldVariants=new HashSet<String>();previous.forEach(i->oldVariants.add(RtxptScene.meshName(i)));
        var meshItems=new ArrayList<RtxptScene.Item>();var imported=new HashSet<String>();
        var updates=new ArrayList<Object>();var names=new HashSet<String>();
        var variants=new LinkedHashMap<String,RtxptScene.Variant>();
        var meshMaterials=new HashMap<Long,Set<Long>>();
        for(var i:items) {
            names.add(i.name());String key=RtxptScene.meshName(i);var mesh=scene.meshes.get(i.mesh());
            boolean geometry=!Objects.equals(old.meshes.get(i.mesh()),mesh)||!oldVariants.contains(key);
            if(geometry&&imported.add(key))meshItems.add(i);
            var before=oldItems.get(i.name());
            if(geometry||!i.equals(before)){
                float[] transform=new float[12];i.transform().elements().get(transform);
                updates.add(object("name",i.name(),"mesh",key,"transform",transform));
            }
            var ids=meshMaterials.computeIfAbsent(i.mesh(),ignored->scene.materialIds(mesh));
            for(long id:ids) {
                var m=scene.materials.get(id);
                boolean affected=geometry||!Objects.equals(m,old.materials.get(id));
                for(long t:new long[]{m.baseColorTextureId(),m.normalTextureId(),m.metallicRoughnessTextureId(),m.emissiveTextureId()})affected|=changedTextures.contains(t);
                if(affected){var variant=new RtxptScene.Variant(id,i.boundary(),i.visibility());variants.put(variant.name(),variant);}
            }
        }
        var translated=new RtxptScene();
        for(var i:meshItems)translated.meshes.put(i.mesh(),scene.meshes.get(i.mesh()));
        for(var v:variants.values()){
            var m=scene.materials.get(v.material());translated.materials.put(m.id(),m);
            for(long t:new long[]{m.baseColorTextureId(),m.normalTextureId(),m.metallicRoughnessTextureId(),m.emissiveTextureId()})
                if(t>=0)translated.textures.put(t,scene.textures.get(t));
        }
        var coverageChanged=new ArrayList<String>();
        for(var v:variants.values()){
            var a=old.materials.get(v.material());var b=scene.materials.get(v.material());
            if(a==null||!alphaEquivalent(old.textures.get(a.baseColorTextureId()),scene.textures.get(b.baseColorTextureId())))coverageChanged.add(v.name());
        }
        Path model=translated.exportModel(directory,meshItems,textureFiles,new ArrayList<>(variants.values()));
        var removed=previous.stream().map(RtxptScene.Item::name).filter(n->!names.contains(n)).toList();
        Path manifest=directory.resolve("delta.json");
        Files.writeString(manifest,RtxptJson.encode(object("model",model.toAbsolutePath().toString().replace('\\','/'),
            "removeNodes",removed,"nodes",updates,"textures",changedTextures.size(),"coverageChangedMaterials",coverageChanged)));
        int writtenTextures=(int)translated.textures.keySet().stream().filter(changedTextures::contains).count();
        return new Prepared(manifest,translated,textureFiles,imported.size(),variants.size(),writtenTextures,
            !meshItems.isEmpty()||!variants.isEmpty()||!removed.isEmpty()||!updates.isEmpty());
    }
}
