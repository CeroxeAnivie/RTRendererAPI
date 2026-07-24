package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Atomic placement authority for all variable-sized GPUScene payloads.
 *
 * <p>Each stream has an independent arena so unrelated growth does not force a monolithic buffer
 * rewrite. Preparation produces immutable placements suitable for descriptor packing and staging
 * uploads. The owner commits every arena only after all revisions and completion-state versions
 * validate, preserving one scene generation across geometry and texture memory.</p>
 */
final class VulkanGpuSceneMemory {
    private static final long TEXTURE_ALIGNMENT = 4L;
    private static final long GEOMETRY_ALIGNMENT = 16L;

    private final VulkanRangeArena<TextureAsset> texturePixels = new VulkanRangeArena<>(TextureAsset::id);
    private final VulkanRangeArena<MeshAsset> positions = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> normals = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> tangents = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> textureCoordinates = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> colors = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> lightmapCoordinates = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> indices = new VulkanRangeArena<>(MeshAsset::id);
    private final VulkanRangeArena<MeshAsset> triangleMaterialSlots = new VulkanRangeArena<>(MeshAsset::id);

    synchronized Prepared prepare(VulkanSceneResidency.SceneChangeSet scene) {
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(scene, "scene");
        List<TextureAsset> textureWrites = values(changes.textures().writes());
        List<MeshAsset> meshWrites = values(changes.meshes().writes());
        LongBuffer removedTextures = removalBuffer(changes.reset(), changes.textures().removedIdentities());
        long[] removedMeshes = changes.meshes().removedIdentities();

        VulkanRangeArena.Prepared<TextureAsset> preparedTexturePixels = texturePixels.prepare(
                changes.revision(), changes.reset(),
                requests(textureWrites, VulkanGpuSceneMemory::textureBytes, TEXTURE_ALIGNMENT),
                removedTextures
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedPositions = positions.prepare(
                changes.revision(), changes.reset(),
                requests(meshWrites, mesh -> floatBytes(mesh.positions().remaining()), GEOMETRY_ALIGNMENT),
                removalBuffer(changes.reset(), removedMeshes)
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedNormals = optionalMeshArena(
                normals, changes, meshWrites, removedMeshes,
                mesh -> mesh.normals().hasRemaining(), mesh -> floatBytes(mesh.normals().remaining())
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedTangents = optionalMeshArena(
                tangents, changes, meshWrites, removedMeshes,
                mesh -> mesh.tangents().hasRemaining(), mesh -> floatBytes(mesh.tangents().remaining())
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedTextureCoordinates = optionalMeshArena(
                textureCoordinates, changes, meshWrites, removedMeshes,
                mesh -> mesh.textureCoordinates().hasRemaining(),
                mesh -> floatBytes(mesh.textureCoordinates().remaining())
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedColors = optionalMeshArena(
                colors, changes, meshWrites, removedMeshes,
                mesh -> mesh.vertexColorsRgba8().hasRemaining(),
                mesh -> intBytes(mesh.vertexColorsRgba8().remaining())
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedLightmapCoordinates = optionalMeshArena(
                lightmapCoordinates, changes, meshWrites, removedMeshes,
                mesh -> mesh.lightmapCoordinates().hasRemaining(),
                mesh -> floatBytes(mesh.lightmapCoordinates().remaining())
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedIndices = indices.prepare(
                changes.revision(), changes.reset(),
                requests(meshWrites, mesh -> intBytes(mesh.triangleIndices().remaining()), GEOMETRY_ALIGNMENT),
                removalBuffer(changes.reset(), removedMeshes)
        );
        VulkanRangeArena.Prepared<MeshAsset> preparedMaterialSlots = triangleMaterialSlots.prepare(
                changes.revision(), changes.reset(),
                requests(meshWrites, mesh -> intBytes(mesh.triangleMaterialIds().remaining()), GEOMETRY_ALIGNMENT),
                removalBuffer(changes.reset(), removedMeshes)
        );

        ArrayList<TextureUpload> textureUploads = new ArrayList<>(textureWrites.size());
        for (TextureAsset texture : textureWrites) {
            VulkanRangeArena.Allocation allocation = preparedTexturePixels.nextAllocations().get(texture.id());
            textureUploads.add(new TextureUpload(texture, new VulkanGpuSceneAbi.TexturePlacement(
                    allocation.offsetBytes(), textureBytes(texture)
            )));
        }
        ArrayList<MeshUpload> meshUploads = new ArrayList<>(meshWrites.size());
        for (MeshAsset mesh : meshWrites) {
            meshUploads.add(new MeshUpload(mesh, new VulkanGpuSceneAbi.GeometryPlacement(
                    offset(preparedPositions, mesh.id()),
                    optionalOffset(preparedNormals, mesh.id()),
                    optionalOffset(preparedTangents, mesh.id()),
                    optionalOffset(preparedTextureCoordinates, mesh.id()),
                    optionalOffset(preparedColors, mesh.id()),
                    optionalOffset(preparedLightmapCoordinates, mesh.id()),
                    offset(preparedIndices, mesh.id()),
                    offset(preparedMaterialSlots, mesh.id())
            )));
        }
        return new Prepared(
                this, changes.revision(),
                preparedTexturePixels, preparedPositions, preparedNormals, preparedTangents,
                preparedTextureCoordinates, preparedColors, preparedLightmapCoordinates,
                preparedIndices, preparedMaterialSlots,
                List.copyOf(textureUploads), List.copyOf(meshUploads)
        );
    }

    synchronized void validate(Prepared prepared, long retireAfterEpoch) {
        Prepared checked = validateOwner(prepared);
        texturePixels.validate(checked.texturePixels, retireAfterEpoch);
        positions.validate(checked.positions, retireAfterEpoch);
        normals.validate(checked.normals, retireAfterEpoch);
        tangents.validate(checked.tangents, retireAfterEpoch);
        textureCoordinates.validate(checked.textureCoordinates, retireAfterEpoch);
        colors.validate(checked.colors, retireAfterEpoch);
        lightmapCoordinates.validate(checked.lightmapCoordinates, retireAfterEpoch);
        indices.validate(checked.indices, retireAfterEpoch);
        triangleMaterialSlots.validate(checked.triangleMaterialSlots, retireAfterEpoch);
    }

    synchronized State commit(Prepared prepared, long retireAfterEpoch) {
        validate(prepared, retireAfterEpoch);
        return commitValidated(prepared, retireAfterEpoch);
    }

    /** Publishes a plan already validated together with the identity and native transfer owners. */
    synchronized State commitValidated(Prepared prepared, long retireAfterEpoch) {
        Prepared checked = validateOwner(prepared);
        texturePixels.commitValidated(checked.texturePixels, retireAfterEpoch);
        positions.commitValidated(checked.positions, retireAfterEpoch);
        normals.commitValidated(checked.normals, retireAfterEpoch);
        tangents.commitValidated(checked.tangents, retireAfterEpoch);
        textureCoordinates.commitValidated(checked.textureCoordinates, retireAfterEpoch);
        colors.commitValidated(checked.colors, retireAfterEpoch);
        lightmapCoordinates.commitValidated(checked.lightmapCoordinates, retireAfterEpoch);
        indices.commitValidated(checked.indices, retireAfterEpoch);
        triangleMaterialSlots.commitValidated(checked.triangleMaterialSlots, retireAfterEpoch);
        checked.committed = true;
        return state();
    }

    synchronized State releaseThrough(long completedEpoch) {
        texturePixels.releaseThrough(completedEpoch);
        positions.releaseThrough(completedEpoch);
        normals.releaseThrough(completedEpoch);
        tangents.releaseThrough(completedEpoch);
        textureCoordinates.releaseThrough(completedEpoch);
        colors.releaseThrough(completedEpoch);
        lightmapCoordinates.releaseThrough(completedEpoch);
        indices.releaseThrough(completedEpoch);
        triangleMaterialSlots.releaseThrough(completedEpoch);
        return state();
    }

    synchronized State state() {
        return new State(
                texturePixels.state(), positions.state(), normals.state(), tangents.state(),
                textureCoordinates.state(), colors.state(), lightmapCoordinates.state(),
                indices.state(), triangleMaterialSlots.state()
        );
    }

    synchronized VulkanGpuSceneAbi.GeometryPlacement geometryPlacement(long meshIdentity) {
        VulkanRangeArena.Allocation position = positions.allocation(meshIdentity);
        if (position == null) return null;
        VulkanRangeArena.Allocation index = indices.allocation(meshIdentity);
        VulkanRangeArena.Allocation materialSlots = triangleMaterialSlots.allocation(meshIdentity);
        if (index == null || materialSlots == null) {
            throw new IllegalStateException("resident mesh is missing a required GPUScene stream");
        }
        return new VulkanGpuSceneAbi.GeometryPlacement(
                position.offsetBytes(),
                currentOptionalOffset(normals, meshIdentity),
                currentOptionalOffset(tangents, meshIdentity),
                currentOptionalOffset(textureCoordinates, meshIdentity),
                currentOptionalOffset(colors, meshIdentity),
                currentOptionalOffset(lightmapCoordinates, meshIdentity),
                index.offsetBytes(),
                materialSlots.offsetBytes()
        );
    }

    private Prepared validateOwner(Prepared prepared) {
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        if (checked.owner != this || checked.committed) {
            throw new IllegalStateException("GPUScene memory plan belongs to another owner or was committed");
        }
        return checked;
    }

    private static VulkanRangeArena.Prepared<MeshAsset> optionalMeshArena(
            VulkanRangeArena<MeshAsset> arena,
            VulkanSceneResidency.SceneChangeSet scene,
            List<MeshAsset> meshWrites,
            long[] removedMeshes,
            Predicate<MeshAsset> present,
            ToLongFunction<MeshAsset> bytes
    ) {
        List<MeshAsset> presentWrites = meshWrites.stream().filter(present).toList();
        long[] removals = scene.reset()
                ? new long[0]
                : optionalRemovals(arena, meshWrites, removedMeshes, present);
        return arena.prepare(
                scene.revision(), scene.reset(),
                requests(presentWrites, bytes, GEOMETRY_ALIGNMENT),
                LongBuffer.wrap(removals)
        );
    }

    private static long[] optionalRemovals(
            VulkanRangeArena<MeshAsset> arena,
            List<MeshAsset> meshWrites,
            long[] removedMeshes,
            Predicate<MeshAsset> present
    ) {
        LongOpenHashSet removals = new LongOpenHashSet(removedMeshes);
        for (MeshAsset mesh : meshWrites) {
            if (!present.test(mesh) && arena.allocation(mesh.id()) != null) {
                removals.add(mesh.id());
            }
        }
        LongArrayList ordered = new LongArrayList(removals);
        ordered.sort(Long::compare);
        return ordered.toLongArray();
    }

    private static <T> List<T> values(List<StableIdentitySlots.SlotWrite<T>> writes) {
        return writes.stream().map(StableIdentitySlots.SlotWrite::value).toList();
    }

    private static <T> List<VulkanRangeArena.RangeRequest<T>> requests(
            List<T> values,
            ToLongFunction<T> bytes,
            long alignment
    ) {
        return values.stream()
                .map(value -> new VulkanRangeArena.RangeRequest<>(value, bytes.applyAsLong(value), alignment))
                .toList();
    }

    private static long textureBytes(TextureAsset texture) {
        return texture.rgba8().remaining();
    }

    private static long floatBytes(int count) {
        return Math.multiplyExact((long) count, Float.BYTES);
    }

    private static long intBytes(int count) {
        return Math.multiplyExact((long) count, Integer.BYTES);
    }

    private static LongBuffer removalBuffer(boolean reset, long[] identities) {
        return LongBuffer.wrap(reset ? new long[0] : identities);
    }

    private static long offset(VulkanRangeArena.Prepared<?> prepared, long identity) {
        VulkanRangeArena.Allocation allocation = prepared.nextAllocations().get(identity);
        if (allocation == null) {
            throw new IllegalStateException("required GPUScene stream has no prepared allocation for " + identity);
        }
        return allocation.offsetBytes();
    }

    private static long optionalOffset(VulkanRangeArena.Prepared<?> prepared, long identity) {
        VulkanRangeArena.Allocation allocation = prepared.nextAllocations().get(identity);
        return allocation == null ? -1L : allocation.offsetBytes();
    }

    private static long currentOptionalOffset(VulkanRangeArena<?> arena, long identity) {
        VulkanRangeArena.Allocation allocation = arena.allocation(identity);
        return allocation == null ? -1L : allocation.offsetBytes();
    }

    record TextureUpload(TextureAsset texture, VulkanGpuSceneAbi.TexturePlacement placement) {
        TextureUpload {
            texture = Objects.requireNonNull(texture, "texture");
            placement = Objects.requireNonNull(placement, "placement");
        }
    }

    record MeshUpload(MeshAsset mesh, VulkanGpuSceneAbi.GeometryPlacement placement) {
        MeshUpload {
            mesh = Objects.requireNonNull(mesh, "mesh");
            placement = Objects.requireNonNull(placement, "placement");
        }
    }

    record State(
            VulkanRangeArena.State texturePixels,
            VulkanRangeArena.State positions,
            VulkanRangeArena.State normals,
            VulkanRangeArena.State tangents,
            VulkanRangeArena.State textureCoordinates,
            VulkanRangeArena.State colors,
            VulkanRangeArena.State lightmapCoordinates,
            VulkanRangeArena.State indices,
            VulkanRangeArena.State triangleMaterialSlots
    ) {
        State {
            texturePixels = Objects.requireNonNull(texturePixels, "texturePixels");
            positions = Objects.requireNonNull(positions, "positions");
            normals = Objects.requireNonNull(normals, "normals");
            tangents = Objects.requireNonNull(tangents, "tangents");
            textureCoordinates = Objects.requireNonNull(textureCoordinates, "textureCoordinates");
            colors = Objects.requireNonNull(colors, "colors");
            lightmapCoordinates = Objects.requireNonNull(lightmapCoordinates, "lightmapCoordinates");
            indices = Objects.requireNonNull(indices, "indices");
            triangleMaterialSlots = Objects.requireNonNull(triangleMaterialSlots, "triangleMaterialSlots");
        }
    }

    static final class Prepared {
        private final VulkanGpuSceneMemory owner;
        private final long revision;
        private final VulkanRangeArena.Prepared<TextureAsset> texturePixels;
        private final VulkanRangeArena.Prepared<MeshAsset> positions;
        private final VulkanRangeArena.Prepared<MeshAsset> normals;
        private final VulkanRangeArena.Prepared<MeshAsset> tangents;
        private final VulkanRangeArena.Prepared<MeshAsset> textureCoordinates;
        private final VulkanRangeArena.Prepared<MeshAsset> colors;
        private final VulkanRangeArena.Prepared<MeshAsset> lightmapCoordinates;
        private final VulkanRangeArena.Prepared<MeshAsset> indices;
        private final VulkanRangeArena.Prepared<MeshAsset> triangleMaterialSlots;
        private final List<TextureUpload> textureUploads;
        private final List<MeshUpload> meshUploads;
        private boolean committed;

        private Prepared(
                VulkanGpuSceneMemory owner, long revision,
                VulkanRangeArena.Prepared<TextureAsset> texturePixels,
                VulkanRangeArena.Prepared<MeshAsset> positions,
                VulkanRangeArena.Prepared<MeshAsset> normals,
                VulkanRangeArena.Prepared<MeshAsset> tangents,
                VulkanRangeArena.Prepared<MeshAsset> textureCoordinates,
                VulkanRangeArena.Prepared<MeshAsset> colors,
                VulkanRangeArena.Prepared<MeshAsset> lightmapCoordinates,
                VulkanRangeArena.Prepared<MeshAsset> indices,
                VulkanRangeArena.Prepared<MeshAsset> triangleMaterialSlots,
                List<TextureUpload> textureUploads,
                List<MeshUpload> meshUploads
        ) {
            this.owner = owner;
            this.revision = revision;
            this.texturePixels = texturePixels;
            this.positions = positions;
            this.normals = normals;
            this.tangents = tangents;
            this.textureCoordinates = textureCoordinates;
            this.colors = colors;
            this.lightmapCoordinates = lightmapCoordinates;
            this.indices = indices;
            this.triangleMaterialSlots = triangleMaterialSlots;
            this.textureUploads = textureUploads;
            this.meshUploads = meshUploads;
        }

        long revision() { return revision; }
        List<TextureUpload> textureUploads() { return textureUploads; }
        List<MeshUpload> meshUploads() { return meshUploads; }
    }
}
