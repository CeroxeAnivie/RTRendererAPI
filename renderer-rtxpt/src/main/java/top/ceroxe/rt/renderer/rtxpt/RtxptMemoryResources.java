package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Synchronous borrowed memory packet. Native staging copies finish before JNI returns. */
final class RtxptMemoryResources {
    static final class PacketPool implements AutoCloseable {
        private Arena arena;
        private ByteBuffer buffer;
        private int borrowers;
        private boolean diagnostics=true;
        private int used;
        synchronized void diagnostics(boolean enabled){diagnostics=enabled;}
        private void report(String action,int released){
            if(!diagnostics)return;var event=new RtxptResourcePacketEvent();
            if(!event.isEnabled())return;event.action=action;event.pool=Integer.toUnsignedLong(System.identityHashCode(this));
            event.usedBytes=used;event.capacityBytes=buffer==null?0:buffer.capacity();event.releasedBytes=released;event.commit();
        }
        // Native borrows are synchronous. Pending retries retain this storage,
        // while replacement and renderer close explicitly release it without GC.
        synchronized void clear(){
            if(borrowers!=0)throw new IllegalStateException("Resource packet is borrowed by JNI");
            int capacity=capacity();buffer=null;var old=arena;arena=null;if(old!=null)old.close();
            if(capacity!=0)report("RELEASE",capacity);used=0;
        }
        @Override public void close(){clear();}
        synchronized int capacity(){return buffer==null?0:buffer.capacity();}
        static int storageCapacity(int size){
            if(size<0||size>512*1024*1024)throw new IllegalArgumentException("Invalid resource packet capacity");
            return size<=1024*1024?Math.max(4096,Integer.highestOneBit(Math.max(1,size-1))<<1)
                    :Math.toIntExact(((long)size+65535)&~65535L);
        }
        synchronized ByteBuffer acquire(int size){
            int requestedCapacity=storageCapacity(size);
            if(borrowers!=0)throw new IllegalStateException("Resource packet is borrowed by JNI");
            if(buffer==null||buffer.capacity()<size){
                // Large complete publications must not round e.g. 257 MiB to
                // 512 MiB. Keep small reuse buckets and bound large slack to 64 KiB.
                int capacity=requestedCapacity;
                // No in-flight borrower exists. Retire the obsolete packet first
                // so growth does not retain old+new native capacities at its peak.
                clear();var next=Arena.ofShared();
                try{buffer=next.allocate(capacity,8).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);arena=next;}
                catch(RuntimeException|Error failure){next.close();throw failure;}
                used=size;report("ALLOCATE",0);
            }
            used=size;return buffer.clear().limit(size);
        }
        synchronized void packed(){report("PACK",0);}
        synchronized Borrow borrow(ByteBuffer packet){
            if(buffer==null||packet==null||!packet.isDirect()||!MemorySegment.ofBuffer(packet).scope().isAlive()
                    ||MemorySegment.ofBuffer(packet).address()!=MemorySegment.ofBuffer(buffer).address()
                    ||packet.capacity()>buffer.capacity())throw new IllegalArgumentException("Packet does not belong to this pool");
            ++borrowers;return new Borrow(this,packet);
        }
        private synchronized void release(){if(--borrowers<0)throw new IllegalStateException("Unbalanced resource packet borrow");}
        static final class Borrow implements AutoCloseable {
            private PacketPool owner;private final ByteBuffer buffer;
            Borrow(PacketPool owner,ByteBuffer buffer){this.owner=owner;this.buffer=buffer;}
            ByteBuffer buffer(){if(owner==null)throw new IllegalStateException("Resource packet borrow closed");return buffer;}
            @Override public void close(){if(owner!=null){var pool=owner;owner=null;pool.release();}}
        }
    }
    private record Geometry(RtxptScene.Item item,RtxptScene.ExportGeometry data,List<RtxptAttributeBatch.Group> groups) { }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,
            Map<Long,TextureAsset> resident,PacketPool pool){
        var textures=new LinkedHashMap<Long,TextureAsset>();
        for(var variant:materials){var material=scene.materials.get(variant.material());
            for(long id:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()})
                if(id>=0&&resident.get(id)!=scene.textures.get(id))textures.put(id,scene.textures.get(id));}
        var geometry=new ArrayList<Geometry>();
        for(var item:meshes){var mesh=scene.meshes.get(item.mesh());geometry.add(new Geometry(item,RtxptScene.ExportGeometry.from(mesh),scene.attributeGroups(mesh)));}
        var removedTextures=new ArrayList<Long>();scene.textures.forEachChanged(old.textures,(id,value)->{if(value==null)removedTextures.add(id);});
        var size=new Writer(null);write(size,textures,materials,geometry,nodes,removals,coverage,scene,removedTextures,resident);
        if(size.offset>512L*1024*1024)throw new IllegalArgumentException("Dynamic resource packet exceeds 512 MiB staging capacity; split the transaction");
        var packet=pool.acquire(Math.toIntExact(size.offset));
        write(new Writer(packet),textures,materials,geometry,nodes,removals,coverage,scene,removedTextures,resident);pool.packed();return packet.flip().slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void write(Writer w,Map<Long,TextureAsset> textures,Collection<RtxptScene.Variant> materials,
            List<Geometry> meshes,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,RtxptScene scene,List<Long> removedTextures,Map<Long,TextureAsset> resident){
        w.i(0x52445254);w.i(textures.size());
        for(var t:textures.values()){w.l(t.id());w.i(t.width());w.i(t.height());w.i(t.mipLevelCount());w.i(t.colorSpace().ordinal());
            w.i(t.addressU().ordinal());w.i(t.addressV().ordinal());w.i(t.filter().ordinal());w.l(t.generation());
            var base=resident.get(t.id());var patch=t.sourcePatch().filter(p->base!=null&&base.generation()==t.parentGeneration()).orElse(null);
            w.i(patch==null?0:1);
            if(patch==null)w.bytes(t.rgba8());else{w.l(t.parentGeneration());w.i(patch.mipLevel());w.i(patch.x());w.i(patch.y());w.i(patch.width());w.i(patch.height());w.i(patch.rowPitch());w.bytes(patch.rgba8());}}
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
