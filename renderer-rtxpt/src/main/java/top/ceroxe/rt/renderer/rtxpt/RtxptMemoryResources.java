package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** JNI copies the borrowed packet before return; its queued native owner is independent. */
final class RtxptMemoryResources {
    static final int PROTOCOL=0x5244525b; // Ordered packet input inheritance; 0x52445259/5a remain readable.
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
    private record MaterialTransfer(byte[] name,RtxptScene.Variant variant,MaterialAsset material) { }
    private record NodeTransfer(byte[] name,byte[] mesh,FloatBuffer transform) { }
    private record GroupTransfer(byte[] material,int[] corners) { }
    private record Geometry(byte[] name,RtxptScene.ExportGeometry data,
                            List<GroupTransfer> groups,byte[] inheritedFrom) { }
    private record InputSource(String name,MeshAsset mesh) { }
    private static byte[] name(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private record Channel(Object heap,long address,long bytes) {
        private static final Channel EMPTY=new Channel(null,0,0);
        static Channel of(Buffer buffer){
            if(!buffer.hasRemaining())return EMPTY;
            var segment=MemorySegment.ofBuffer(buffer);
            return new Channel(segment.heapBase().orElse(null),segment.address(),segment.byteSize());
        }
    }
    private record Channels(Channel positions,Channel normals,Channel tangents,Channel uv,Channel indices) {
        static Channels of(MeshAsset mesh){return new Channels(Channel.of(mesh.positions()),Channel.of(mesh.normals()),
                Channel.of(mesh.tangents()),Channel.of(mesh.textureCoordinates()),Channel.of(mesh.triangleIndices()));}
    }
    /** Prepared once: sizing and writing must not each compare an entire image. */
    private record TextureTransfer(TextureAsset texture,TexturePatch patch,long inheritedGeneration) {
        static TextureTransfer prepare(TextureAsset texture,TextureAsset base){
            var patch=texture.sourcePatch().filter(p->base!=null&&base.generation()==texture.parentGeneration()).orElse(null);
            if(patch!=null)return new TextureTransfer(texture,patch,0);
            boolean sameImage=base!=null&&base.width()==texture.width()&&base.height()==texture.height()
                    &&base.mipLevelCount()==texture.mipLevelCount()&&base.colorSpace()==texture.colorSpace()
                    &&base.rgba8().equals(texture.rgba8());
            return new TextureTransfer(texture,null,sameImage?base.generation():0);
        }
        int transport(){return patch!=null?1:inheritedGeneration!=0?2:0;}
    }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,
            Map<Long,TextureAsset> resident,PacketPool pool){
        return pack(old,scene,meshes,materials,nodes,removals,coverage,resident,pool,List.of());
    }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,
            Map<Long,TextureAsset> resident,PacketPool pool,List<String> removedMaterials){
        return pack(old,scene,meshes,materials,nodes,removals,coverage,resident,pool,removedMaterials,List.of());
    }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,
            Map<Long,TextureAsset> resident,PacketPool pool,List<String> removedMaterials,
            Collection<RtxptScene.Item> inheritedCandidates){
        return pack(old,scene,meshes,materials,nodes,removals,coverage,resident,pool,removedMaterials,inheritedCandidates,List.of());
    }
    static ByteBuffer pack(RtxptScene old,RtxptScene scene,Collection<RtxptScene.Item> meshes,
            Collection<RtxptScene.Variant> materials,Collection<RtxptScene.Item> nodes,List<String> removals,List<String> coverage,
            Map<Long,TextureAsset> resident,PacketPool pool,List<String> removedMaterials,
            Collection<RtxptScene.Item> inheritedCandidates,List<String> removedMeshes){
        var textures=new LinkedHashMap<Long,TextureAsset>();
        for(var variant:materials){var material=scene.materials.get(variant.material());
            for(long id:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()})
                if(id>=0&&resident.get(id)!=scene.textures.get(id))textures.put(id,scene.textures.get(id));}
        var transfers=new ArrayList<TextureTransfer>(textures.size());
        for(var texture:textures.values())transfers.add(TextureTransfer.prepare(texture,resident.get(texture.id())));
        // Sizing and writing use one immutable transport view. Native names,
        // UTF-8 bytes and read-only transform views are prepared once, rather
        // than reconstructed while walking the same packet a second time.
        var materialTransfers=new ArrayList<MaterialTransfer>(materials.size());
        for(var variant:materials)materialTransfers.add(new MaterialTransfer(name(variant.name()),variant,scene.materials.get(variant.material())));
        var nodeTransfers=new ArrayList<NodeTransfer>(nodes.size());
        for(var node:nodes)nodeTransfers.add(new NodeTransfer(name(node.name()),name(RtxptScene.meshName(node)),node.transform().elements()));
        var byStorage=new HashMap<Channels,InputSource>();
        var byName=new HashMap<String,InputSource>();
        var byPose=new HashMap<AffineTransform,InputSource>();
        for(var source:inheritedCandidates){
            var input=new InputSource(RtxptScene.meshName(source),old.meshes.get(source.mesh()));
            byStorage.putIfAbsent(Channels.of(input.mesh()),input);
            byName.putIfAbsent(input.name(),input);byPose.putIfAbsent(source.transform(),input);
        }
        var geometry=new ArrayList<Geometry>();
        var writtenInputs=new HashMap<String,MeshAsset>();
        for(var item:meshes){
            var mesh=scene.meshes.get(item.mesh());String inheritedFrom=null;
            var channels=Channels.of(mesh);
            var source=byStorage.get(channels);
            if(source==null)source=byName.get(RtxptScene.meshName(item));
            if(source==null)source=byPose.get(item.transform());
            // Storage is only a lookup key. Exact equality still decides what
            // can be inherited, including callers using independent buffers.
            // A prior packet entry shadows the resident name in the new wire.
            // Compare that actual generation, never the stale resident lookup.
            if(source!=null&&RtxptDelta.sameInputChannels(writtenInputs.getOrDefault(source.name(),source.mesh()),mesh))
                inheritedFrom=source.name();
            var groups=new ArrayList<GroupTransfer>();
            for(var group:scene.attributeGroups(mesh))groups.add(new GroupTransfer(
                    name(new RtxptScene.Variant(group.material(),item.boundary(),item.visibility()).name()),group.corners()));
            geometry.add(new Geometry(name(RtxptScene.meshName(item)),inheritedFrom==null?RtxptScene.ExportGeometry.from(mesh):null,
                    groups,inheritedFrom==null?null:name(inheritedFrom)));
            var written=new InputSource(RtxptScene.meshName(item),mesh);
            writtenInputs.put(written.name(),mesh);
            byStorage.put(channels,written);byName.put(written.name(),written);
        }
        var removedTextures=new ArrayList<Long>();scene.textures.forEachChanged(old.textures,(id,value)->{if(value==null)removedTextures.add(id);});
        var removedNames=removals.stream().map(RtxptMemoryResources::name).toList();
        var coverageNames=coverage.stream().map(RtxptMemoryResources::name).toList();
        var removedMaterialNames=removedMaterials.stream().map(RtxptMemoryResources::name).toList();
        var removedMeshNames=removedMeshes.stream().map(RtxptMemoryResources::name).toList();
        var size=new Writer(null);write(size,transfers,materialTransfers,geometry,nodeTransfers,removedNames,coverageNames,removedTextures,removedMaterialNames,removedMeshNames);
        if(size.offset>512L*1024*1024)throw new IllegalArgumentException("Dynamic resource packet exceeds 512 MiB staging capacity; split the transaction");
        var packet=pool.acquire(Math.toIntExact(size.offset));
        write(new Writer(packet),transfers,materialTransfers,geometry,nodeTransfers,removedNames,coverageNames,removedTextures,removedMaterialNames,removedMeshNames);pool.packed();return packet.flip().slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void write(Writer w,List<TextureTransfer> textures,List<MaterialTransfer> materials,
            List<Geometry> meshes,List<NodeTransfer> nodes,List<byte[]> removals,List<byte[]> coverage,List<Long> removedTextures,List<byte[]> removedMaterials,List<byte[]> removedMeshes){
        w.i(PROTOCOL);w.i(textures.size());
        for(var transfer:textures){var t=transfer.texture();w.l(t.id());w.i(t.width());w.i(t.height());w.i(t.mipLevelCount());w.i(t.colorSpace().ordinal());
            w.i(t.addressU().ordinal());w.i(t.addressV().ordinal());w.i(t.filter().ordinal());w.l(t.generation());
            var patch=transfer.patch();w.i(transfer.transport());
            if(transfer.inheritedGeneration()!=0)w.l(transfer.inheritedGeneration());
            else if(patch==null)w.bytes(t.rgba8());
            else{w.l(t.parentGeneration());w.i(patch.mipLevel());w.i(patch.x());w.i(patch.y());w.i(patch.width());w.i(patch.height());w.i(patch.rowPitch());w.bytes(patch.rgba8());}}
        w.i(materials.size());for(var transfer:materials){var v=transfer.variant();var m=transfer.material();w.s(transfer.name());w.i(m.baseColorRgba8());
            w.f(v.visibility());w.f(m.roughness());w.f(m.metallic());w.f(m.transmission());w.f(m.alphaCutoff());w.i(m.blendMode().ordinal());w.i(m.doubleSided()?1:0);
            var emission=m.emissiveRadiance();w.f(emission.red());w.f(emission.green());w.f(emission.blue());
            w.f(v.boundary()==null?m.indexOfRefraction():v.boundary().medium().indexOfRefraction());
            w.i(m.pathDecomposition()==MaterialAsset.PathDecomposition.EXCLUDED?1:0);
            w.i(switch(m.pathDecomposition()){case EXCLUDED,AUTOMATIC->-1;case TRANSMISSION->0;case REFLECTION->1;});
            w.i(switch(m.motionVectorBlocking()){case OFF->0;case AUTO_LOW->1;case AUTO_HIGH->2;case FULL->3;});
            w.l(m.baseColorTextureId());w.l(m.normalTextureId());w.l(m.metallicRoughnessTextureId());w.l(m.emissiveTextureId());
            w.i(v.boundary()==null?0:1);if(v.boundary()!=null){var b=v.boundary();w.f(b.medium().absorption().red());w.f(b.medium().absorption().green());w.f(b.medium().absorption().blue());w.i(b.priority());}}
        w.i(meshes.size());for(var g:meshes){w.s(g.name);w.i(g.inheritedFrom==null?0:1);
            if(g.inheritedFrom!=null)w.s(g.inheritedFrom);
            else{w.floats(g.data.positions());w.floats(g.data.normals());w.floats(g.data.tangents());w.floats(g.data.uvs());
            // Triangle identity and winding are independent of surface grouping.
            // The finite-signal adapter consumes this order directly; the native
            // beauty importer builds its ordinary material groups from the map.
            w.ints(g.data.indices());}
            w.i(g.groups.size());for(var group:g.groups){w.s(group.material());w.i(group.corners().length/3);
                if(w.data==null)w.offset+=(long)group.corners().length/3*4;
                else for(int i=0;i<group.corners().length;i+=3)w.i(group.corners()[i]/3);}}
        w.i(removals.size());for(var name:removals)w.s(name);
        w.i(nodes.size());for(var node:nodes){w.s(node.name());w.s(node.mesh());w.floats(node.transform());}
        w.i(coverage.size());for(var name:coverage)w.s(name);
        w.i(removedTextures.size());for(long id:removedTextures)w.l(id);
        w.i(removedMaterials.size());for(var name:removedMaterials)w.s(name);
        w.i(removedMeshes.size());for(var name:removedMeshes)w.s(name);
    }
    private static final class Writer {
        final ByteBuffer data;long offset;
        Writer(ByteBuffer data){this.data=data;}
        void i(int v){offset+=4;if(data!=null)data.putInt(v);}
        void l(long v){offset+=8;if(data!=null)data.putLong(v);}
        void f(float v){i(Float.floatToRawIntBits(v));}
        void s(byte[] bytes){i(bytes.length);offset+=bytes.length;if(data!=null)data.put(bytes);}
        void bytes(ByteBuffer value){i(value.remaining());offset+=value.remaining();if(data!=null)data.put(value);}
        void floats(FloatBuffer value){i(value.remaining());offset+=(long)value.remaining()*4;if(data!=null){int bytes=value.remaining()*4;data.asFloatBuffer().put(value);data.position(data.position()+bytes);}}
        void ints(IntBuffer value){i(value.remaining());offset+=(long)value.remaining()*4;if(data!=null){int bytes=value.remaining()*4;data.asIntBuffer().put(value);data.position(data.position()+bytes);}}
    }
}
