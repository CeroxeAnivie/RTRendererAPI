package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Synchronous borrowed memory packet. Native staging copies finish before JNI returns. */
final class RtxptMemoryResources {
    private record Geometry(RtxptScene.Item item,RtxptScene.ExportGeometry data,List<RtxptAttributeBatch.Group> groups) { }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage){
        var textures=new LinkedHashMap<Long,TextureAsset>();
        for(var variant:materials){var material=scene.materials.get(variant.material());
            for(long id:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()})
                if(id>=0)textures.put(id,scene.textures.get(id));}
        var geometry=new ArrayList<Geometry>();
        for(var item:meshes){var mesh=scene.meshes.get(item.mesh());geometry.add(new Geometry(item,RtxptScene.ExportGeometry.from(mesh),scene.attributeGroups(mesh)));}
        var removedTextures=new ArrayList<Long>();scene.textures.forEachChanged(old.textures,(id,value)->{if(value==null)removedTextures.add(id);});
        var size=new Writer(null);write(size,textures,materials,geometry,nodes,removals,coverage,scene,removedTextures);
        if(size.offset>512L*1024*1024)throw new IllegalArgumentException("Dynamic resource packet exceeds 512 MiB staging capacity; split the transaction");
        var packet=ByteBuffer.allocateDirect(Math.toIntExact(size.offset)).order(ByteOrder.LITTLE_ENDIAN);
        write(new Writer(packet),textures,materials,geometry,nodes,removals,coverage,scene,removedTextures);return packet.flip().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void write(Writer w,Map<Long,TextureAsset> textures,Collection<RtxptScene.Variant> materials,
            List<Geometry> meshes,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,RtxptScene scene,List<Long> removedTextures){
        w.i(0x52445254);w.i(textures.size());
        for(var t:textures.values()){w.l(t.id());w.i(t.width());w.i(t.height());w.i(t.mipLevelCount());w.i(t.colorSpace().ordinal());
            w.i(t.addressU().ordinal());w.i(t.addressV().ordinal());w.i(t.filter().ordinal());w.bytes(t.rgba8());}
        w.i(materials.size());for(var v:materials){var m=scene.materials.get(v.material());w.s(v.name());w.i(m.baseColorRgba8());
            w.f(v.visibility());w.f(m.roughness());w.f(m.metallic());w.f(m.transmission());w.f(m.alphaCutoff());w.i(m.blendMode().ordinal());w.i(m.doubleSided()?1:0);
            w.i(m.emissiveColorRgba8());w.f(m.emissiveStrength());w.f(v.boundary()==null?m.indexOfRefraction():v.boundary().medium().indexOfRefraction());
            w.l(m.baseColorTextureId());w.l(m.normalTextureId());w.l(m.metallicRoughnessTextureId());w.l(m.emissiveTextureId());
            w.i(v.boundary()==null?0:1);if(v.boundary()!=null){var b=v.boundary();w.f(b.medium().absorption().red());w.f(b.medium().absorption().green());w.f(b.medium().absorption().blue());w.i(b.priority());}}
        w.i(meshes.size());for(var g:meshes){w.s(RtxptScene.meshName(g.item));w.floats(g.data.positions());w.floats(g.data.normals());w.floats(g.data.tangents());w.floats(g.data.uvs());
            w.i(g.groups.size());for(var group:g.groups){w.s(new RtxptScene.Variant(group.material(),g.item.boundary(),g.item.visibility()).name());w.i(group.corners().length);
                if(w.data==null)w.offset+=(long)group.corners().length*4;
                else for(int corner:group.corners())w.i(g.data.indices().get(corner));}}
        w.i(removals.size());for(var name:removals)w.s(name);
        w.i(nodes.size());for(var node:nodes){w.s(node.name());w.s(RtxptScene.meshName(node));w.floats(node.transform().elements());}
        w.i(coverage.size());for(var name:coverage)w.s(name);
        w.i(removedTextures.size());for(long id:removedTextures)w.l(id);
    }
    private static final class Writer {
        final ByteBuffer data;long offset;
        Writer(ByteBuffer data){this.data=data;}
        void i(int v){offset+=4;if(data!=null)data.putInt(v);}
        void l(long v){offset+=8;if(data!=null)data.putLong(v);}
        void f(float v){i(Float.floatToRawIntBits(v));}
        void s(String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);i(bytes.length);offset+=bytes.length;if(data!=null)data.put(bytes);}
        void bytes(ByteBuffer value){i(value.remaining());offset+=value.remaining();if(data!=null)data.put(value);}
        void floats(FloatBuffer value){i(value.remaining());offset+=(long)value.remaining()*4;if(data!=null){int bytes=value.remaining()*4;data.asFloatBuffer().put(value);data.position(data.position()+bytes);}}
    }
}
