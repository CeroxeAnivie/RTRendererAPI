package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Packs one prepared scene generation into coalesced little-endian staging-copy chunks. */
final class VulkanGpuSceneUploadPlanner {
    private VulkanGpuSceneUploadPlanner() {
    }

    static Plan plan(
            VulkanSceneResidency.SceneChangeSet scene,
            VulkanGpuSceneMemory.Prepared memory,
            VulkanGpuSceneIdentityIndex.Prepared identities
    ) {
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(scene, "scene");
        VulkanGpuSceneMemory.Prepared placements = Objects.requireNonNull(memory, "memory");
        VulkanGpuSceneIdentityIndex.Prepared slots = Objects.requireNonNull(identities, "identities");
        if (changes.revision() != placements.revision() || changes.revision() != slots.revision()) {
            throw new IllegalArgumentException("GPUScene upload inputs belong to different revisions");
        }

        Builder builder = new Builder(changes.revision());
        Long2ObjectOpenHashMap<VulkanGpuSceneAbi.TexturePlacement> texturePlacements = new Long2ObjectOpenHashMap<>();
        for (VulkanGpuSceneMemory.TextureUpload upload : placements.textureUploads()) {
            texturePlacements.put(upload.texture().id(), upload.placement());
            builder.add(Target.TEXTURE_PIXELS, upload.placement().byteOffset(), bytes(upload.texture().rgba8()), 1);
        }
        for (StableIdentitySlots.SlotWrite<top.ceroxe.mcvulkanrt.renderer.api.TextureAsset> write
                : changes.textures().writes()) {
            VulkanGpuSceneAbi.TexturePlacement placement = texturePlacements.get(write.id());
            if (placement == null) throw missingPlacement("texture", write.id());
            builder.add(Target.TEXTURE_RECORDS, recordOffset(write.slot(), VulkanGpuSceneAbi.TEXTURE_RECORD_WORDS),
                    ints(VulkanGpuSceneAbi.packTexture(write.value(), placement)), 1);
        }
        addClears(builder, Target.TEXTURE_RECORDS, changes.textures().clearedSlots(),
                VulkanGpuSceneAbi.TEXTURE_RECORD_WORDS);

        for (StableIdentitySlots.SlotWrite<top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset> write
                : changes.materials().writes()) {
            builder.add(Target.MATERIAL_RECORDS, recordOffset(write.slot(), VulkanGpuSceneAbi.MATERIAL_RECORD_WORDS),
                    ints(VulkanGpuSceneAbi.packMaterial(write.value(), slots::textureSlot)), 1);
        }
        addClears(builder, Target.MATERIAL_RECORDS, changes.materials().clearedSlots(),
                VulkanGpuSceneAbi.MATERIAL_RECORD_WORDS);

        Long2ObjectOpenHashMap<VulkanGpuSceneAbi.GeometryPlacement> meshPlacements = new Long2ObjectOpenHashMap<>();
        for (VulkanGpuSceneMemory.MeshUpload upload : placements.meshUploads()) {
            MeshAsset mesh = upload.mesh();
            VulkanGpuSceneAbi.GeometryPlacement placement = upload.placement();
            meshPlacements.put(mesh.id(), placement);
            builder.add(Target.POSITIONS, placement.positionBytes(), floats(mesh.positions()), mesh.vertexCount());
            addOptional(builder, Target.NORMALS, placement.normalBytes(), mesh.normals(), mesh.vertexCount());
            addOptional(builder, Target.TANGENTS, placement.tangentBytes(), mesh.tangents(), mesh.vertexCount());
            addOptional(builder, Target.TEXTURE_COORDINATES, placement.textureCoordinateBytes(),
                    mesh.textureCoordinates(), mesh.vertexCount());
            if (placement.colorBytes() >= 0L) {
                builder.add(Target.COLORS, placement.colorBytes(), ints(mesh.vertexColorsRgba8()), mesh.vertexCount());
            }
            addOptional(
                    builder,
                    Target.LIGHTMAP_COORDINATES,
                    placement.lightmapCoordinateBytes(),
                    mesh.lightmapCoordinates(),
                    mesh.vertexCount()
            );
            builder.add(Target.INDICES, placement.indexBytes(), ints(mesh.triangleIndices()), mesh.triangleCount());
            builder.add(Target.TRIANGLE_MATERIAL_SLOTS, placement.triangleMaterialSlotBytes(),
                    resolvedSlots(mesh.triangleMaterialIds(), slots::materialSlot), mesh.triangleCount());
        }
        for (StableIdentitySlots.SlotWrite<MeshAsset> write : changes.meshes().writes()) {
            VulkanGpuSceneAbi.GeometryPlacement placement = meshPlacements.get(write.id());
            if (placement == null) throw missingPlacement("mesh", write.id());
            builder.add(Target.MESH_RECORDS, recordOffset(write.slot(), VulkanGpuSceneAbi.MESH_RECORD_WORDS),
                    ints(VulkanGpuSceneAbi.packMesh(write.value(), placement)), 1);
        }
        addClears(builder, Target.MESH_RECORDS, changes.meshes().clearedSlots(),
                VulkanGpuSceneAbi.MESH_RECORD_WORDS);

        for (StableIdentitySlots.SlotWrite<top.ceroxe.mcvulkanrt.renderer.api.SceneInstance> write
                : changes.instances().writes()) {
            builder.add(Target.INSTANCE_RECORDS,
                    recordOffset(write.slot(), VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS),
                    ints(VulkanGpuSceneAbi.packInstance(write.value(), slots::meshSlot)), 1);
        }
        addClears(builder, Target.INSTANCE_RECORDS, changes.instances().clearedSlots(),
                VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS);

        for (StableIdentitySlots.SlotWrite<top.ceroxe.mcvulkanrt.renderer.api.SceneLight> write
                : changes.lights().writes()) {
            builder.add(Target.LIGHT_RECORDS, recordOffset(write.slot(), VulkanGpuSceneAbi.LIGHT_RECORD_WORDS),
                    ints(VulkanGpuSceneAbi.packLight(write.value())), 1);
        }
        addClears(builder, Target.LIGHT_RECORDS, changes.lights().clearedSlots(),
                VulkanGpuSceneAbi.LIGHT_RECORD_WORDS);
        return builder.build();
    }

    private static void addClears(Builder builder, Target target, int[] slots, int words) {
        byte[] cleared = ints(VulkanGpuSceneAbi.clearedRecord(words));
        for (int slot : slots) {
            builder.add(target, recordOffset(slot, words), cleared, 1);
        }
    }

    private static void addOptional(
            Builder builder, Target target, long offset, FloatBuffer values, int logicalRecords
    ) {
        if (offset < 0L) {
            if (values.hasRemaining()) throw new IllegalStateException(target + " placement is missing");
            return;
        }
        builder.add(target, offset, floats(values), logicalRecords);
    }

    private static byte[] resolvedSlots(LongBuffer identities, VulkanGpuSceneAbi.SlotResolver resolver) {
        LongBuffer source = identities.duplicate();
        ByteBuffer bytes = littleEndian(Math.multiplyExact(source.remaining(), Integer.BYTES));
        while (source.hasRemaining()) {
            long id = source.get();
            int slot = resolver.resolve(id);
            if (slot < 0) throw new IllegalArgumentException("triangle material identity has no resident slot: " + id);
            bytes.putInt(slot);
        }
        return bytes.array();
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer view = source.duplicate();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    private static byte[] floats(FloatBuffer source) {
        FloatBuffer view = source.duplicate();
        ByteBuffer bytes = littleEndian(Math.multiplyExact(view.remaining(), Float.BYTES));
        while (view.hasRemaining()) bytes.putFloat(view.get());
        return bytes.array();
    }

    private static byte[] ints(IntBuffer source) {
        IntBuffer view = source.duplicate();
        ByteBuffer bytes = littleEndian(Math.multiplyExact(view.remaining(), Integer.BYTES));
        while (view.hasRemaining()) bytes.putInt(view.get());
        return bytes.array();
    }

    private static byte[] ints(int[] values) {
        ByteBuffer bytes = littleEndian(Math.multiplyExact(values.length, Integer.BYTES));
        for (int value : values) bytes.putInt(value);
        return bytes.array();
    }

    private static ByteBuffer littleEndian(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long recordOffset(int slot, int words) {
        return VulkanGpuSceneAbi.recordByteOffset(slot, words);
    }

    private static IllegalStateException missingPlacement(String domain, long identity) {
        return new IllegalStateException(domain + " upload has no prepared memory placement for " + identity);
    }

    enum Target {
        TEXTURE_RECORDS,
        TEXTURE_PIXELS,
        MATERIAL_RECORDS,
        MESH_RECORDS,
        POSITIONS,
        NORMALS,
        TANGENTS,
        TEXTURE_COORDINATES,
        COLORS,
        LIGHTMAP_COORDINATES,
        INDICES,
        TRIANGLE_MATERIAL_SLOTS,
        INSTANCE_RECORDS,
        LIGHT_RECORDS
    }

    record Chunk(Target target, long targetOffsetBytes, byte[] payload, int logicalRecords) {
        Chunk {
            target = Objects.requireNonNull(target, "target");
            if (targetOffsetBytes < 0L || (targetOffsetBytes & 3L) != 0L || logicalRecords <= 0) {
                throw new IllegalArgumentException("GPUScene upload chunk offset or record count is invalid");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
            if (payload.length == 0 || (payload.length & 3) != 0) {
                throw new IllegalArgumentException("GPUScene upload payload must contain aligned words");
            }
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        long endOffsetBytes() {
            return Math.addExact(targetOffsetBytes, payload.length);
        }
    }

    record Plan(long revision, List<Chunk> chunks, long uploadBytes, int logicalRecords) {
        Plan {
            if (revision < 0L || uploadBytes < 0L || logicalRecords < 0) {
                throw new IllegalArgumentException("GPUScene upload plan counters are invalid");
            }
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }

        boolean isEmpty() { return chunks.isEmpty(); }
    }

    private static final class Builder {
        private final long revision;
        private final ArrayList<Chunk> chunks = new ArrayList<>();

        private Builder(long revision) {
            this.revision = revision;
        }

        private void add(Target target, long offset, byte[] payload, int logicalRecords) {
            chunks.add(new Chunk(target, offset, payload, logicalRecords));
        }

        private Plan build() {
            chunks.sort(Comparator.comparing(Chunk::target).thenComparingLong(Chunk::targetOffsetBytes));
            ArrayList<Chunk> coalesced = new ArrayList<>(chunks.size());
            for (Chunk chunk : chunks) {
                if (!coalesced.isEmpty()) {
                    Chunk previous = coalesced.get(coalesced.size() - 1);
                    if (previous.target() == chunk.target()
                            && previous.endOffsetBytes() == chunk.targetOffsetBytes()) {
                        byte[] merged = new byte[Math.addExact(previous.payload.length, chunk.payload.length)];
                        System.arraycopy(previous.payload, 0, merged, 0, previous.payload.length);
                        System.arraycopy(chunk.payload, 0, merged, previous.payload.length, chunk.payload.length);
                        coalesced.set(coalesced.size() - 1, new Chunk(
                                chunk.target(), previous.targetOffsetBytes(), merged,
                                Math.addExact(previous.logicalRecords(), chunk.logicalRecords())
                        ));
                        continue;
                    }
                    if (previous.target() == chunk.target()
                            && previous.endOffsetBytes() > chunk.targetOffsetBytes()) {
                        throw new IllegalStateException("overlapping GPUScene upload chunks for " + chunk.target());
                    }
                }
                coalesced.add(chunk);
            }
            long bytes = 0L;
            int records = 0;
            for (Chunk chunk : coalesced) {
                bytes = Math.addExact(bytes, chunk.payload.length);
                records = Math.addExact(records, chunk.logicalRecords());
            }
            return new Plan(revision, coalesced, bytes, records);
        }
    }
}
