package demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.PrimitiveInstance;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;

final class DemoScene {
    private static final float BACK_Z = 0.0F;
    private static final float RIM_DEPTH = 1.35F;
    private static final float BALL_CENTER_Z = (float) HexPhysics.BALL_RADIUS + 0.08F;

    private static final long BACK_MATERIAL = 100L;
    private static final long RIM_MATERIAL = 101L;
    private static final long[] BALL_MATERIALS = {110L, 111L, 112L};
    private static final long BACK_MESH = 200L;
    private static final long RIM_MESH = 201L;
    private static final long[] BALL_MESHES = {210L, 211L, 212L};
    private static final long BACK_INSTANCE = 300L;
    private static final long RIM_INSTANCE = 301L;

    private static final int[] BALL_COLORS = {
            rgba(218, 132, 140, 255),
            rgba(132, 214, 158, 255),
            rgba(132, 158, 222, 255)
    };

    private static final double[][] EMITTER_POSITIONS = {
            {-3.25, 2.55, 3.80},
            {3.15, 2.25, 3.60},
            {0.10, -3.15, 3.45}
    };

    private final Geometry sphere = sphereGeometry(64, 128);

    SceneTransaction initialTransaction() {
        List<MaterialAsset> materials = new ArrayList<>();
        materials.add(MaterialAsset.builder(BACK_MATERIAL)
                .baseColorRgba8(rgba(104, 112, 126, 255))
                .metallic(0.42F)
                .roughness(0.20F)
                .build());
        materials.add(MaterialAsset.builder(RIM_MATERIAL)
                .baseColorRgba8(rgba(174, 181, 192, 255))
                .metallic(0.05F)
                .roughness(0.30F)
                .blendMode(MaterialAsset.BlendMode.MASKED)
                .alphaCutoff(0.5F)
                .doubleSided(true)
                .build());

        for (int index = 0; index < BALL_COLORS.length; index++) {
            materials.add(MaterialAsset.builder(BALL_MATERIALS[index])
                    .baseColorRgba8(BALL_COLORS[index])
                    // PVD-colored polished stainless steel: a fully conducting surface avoids
                    // the dielectric/metal blend that previously read as concentric shells.
                    .metallic(1.0F)
                    .roughness(0.12F)
                    .build());
        }

        List<MeshAsset> meshes = new ArrayList<>();
        meshes.add(backMesh());
        meshes.add(rimMesh());
        for (int index = 0; index < BALL_COLORS.length; index++) {
            meshes.add(meshWithMaterial(BALL_MESHES[index], sphere, BALL_MATERIALS[index]));
        }

        List<SceneInstance> instances = new ArrayList<>();
        instances.add(SceneInstance.builder(BACK_INSTANCE, BACK_MESH).build());
        instances.add(SceneInstance.builder(RIM_INSTANCE, RIM_MESH).build());

        List<SceneLight> lights = List.of(
                SceneLight.point(400L, EMITTER_POSITIONS[0][0], EMITTER_POSITIONS[0][1], EMITTER_POSITIONS[0][2])
                        .color(1.0F, 0.035F, 0.025F).intensity(310.0F).range(10.5F).build(),
                SceneLight.point(401L, EMITTER_POSITIONS[1][0], EMITTER_POSITIONS[1][1], EMITTER_POSITIONS[1][2])
                        .color(0.025F, 1.0F, 0.08F).intensity(300.0F).range(10.5F).build(),
                SceneLight.point(402L, EMITTER_POSITIONS[2][0], EMITTER_POSITIONS[2][1], EMITTER_POSITIONS[2][2])
                        .color(0.025F, 0.12F, 1.0F).intensity(340.0F).range(11.0F).build()
        );

        return SceneTransaction.builder(0L)
                .resetScene()
                .upsertMaterials(materials)
                .upsertMeshes(meshes)
                .upsertInstances(instances)
                .upsertLights(lights)
                .build();
    }

    FramePrimitiveBatch primitiveBatch(HexPhysics physics, FramePrimitiveBatch previousBatch) {
        List<HexPhysics.Ball> balls = Objects.requireNonNull(physics, "physics").balls();
        FramePrimitiveBatch previous = Objects.requireNonNull(previousBatch, "previousBatch");
        if (!previous.isEmpty() && previous.size() != balls.size()) {
            throw new IllegalStateException("previous frame primitive count no longer matches physics state");
        }
        FramePrimitiveBatch.Builder batch = FramePrimitiveBatch.builder();
        for (int index = 0; index < balls.size(); index++) {
            HexPhysics.Ball ball = balls.get(index);
            AffineTransform transform = uniformTransform(
                    (float) HexPhysics.BALL_RADIUS, ball.x, ball.y, BALL_CENTER_Z
            );
            AffineTransform previousTransform = transform;
            if (!previous.isEmpty()) {
                PrimitiveInstance prior = previous.primitives().get(index);
                if (prior.meshAssetId() != BALL_MESHES[index]) {
                    throw new IllegalStateException("frame primitive ordering changed at index " + index);
                }
                previousTransform = prior.transform();
            }
            batch.add(PrimitiveInstance.builder(BALL_MESHES[index])
                    .transform(transform)
                    .previousTransform(previousTransform)
                    .build());
        }
        return batch.build();
    }

    private static MeshAsset backMesh() {
        float radius = (float) (HexPhysics.HEX_APOTHEM / Math.cos(Math.PI / 6.0));
        float[] positions = new float[7 * 3];
        float[] normals = new float[7 * 3];
        normals[2] = 1.0F;
        for (int vertex = 0; vertex < 6; vertex++) {
            double angle = Math.PI / 6.0 + vertex * Math.PI / 3.0;
            int offset = (vertex + 1) * 3;
            positions[offset] = radius * (float) Math.cos(angle);
            positions[offset + 1] = radius * (float) Math.sin(angle);
            positions[offset + 2] = BACK_Z;
            normals[offset + 2] = 1.0F;
        }
        int[] indices = new int[18];
        for (int side = 0; side < 6; side++) {
            int offset = side * 3;
            indices[offset] = 0;
            indices[offset + 1] = side + 1;
            indices[offset + 2] = ((side + 1) % 6) + 1;
        }
        return MeshAsset.builder(BACK_MESH, positions, indices, filledMaterials(6, BACK_MATERIAL))
                .normals(normals)
                .build();
    }

    private static MeshAsset rimMesh() {
        float radius = (float) (HexPhysics.HEX_APOTHEM / Math.cos(Math.PI / 6.0));
        float[] positions = new float[6 * 4 * 3];
        float[] normals = new float[positions.length];
        int[] indices = new int[6 * 6];
        for (int side = 0; side < 6; side++) {
            double firstAngle = Math.PI / 6.0 + side * Math.PI / 3.0;
            double secondAngle = Math.PI / 6.0 + ((side + 1) % 6) * Math.PI / 3.0;
            float firstX = radius * (float) Math.cos(firstAngle);
            float firstY = radius * (float) Math.sin(firstAngle);
            float secondX = radius * (float) Math.cos(secondAngle);
            float secondY = radius * (float) Math.sin(secondAngle);
            int vertex = side * 4;
            putPosition(positions, vertex, firstX, firstY, BACK_Z);
            putPosition(positions, vertex + 1, secondX, secondY, BACK_Z);
            putPosition(positions, vertex + 2, secondX, secondY, RIM_DEPTH);
            putPosition(positions, vertex + 3, firstX, firstY, RIM_DEPTH);

            double normalAngle = (side + 1) * Math.PI / 3.0;
            float inwardX = -(float) Math.cos(normalAngle);
            float inwardY = -(float) Math.sin(normalAngle);
            for (int local = 0; local < 4; local++) {
                putPosition(normals, vertex + local, inwardX, inwardY, 0.0F);
            }

            int index = side * 6;
            indices[index] = vertex;
            indices[index + 1] = vertex + 1;
            indices[index + 2] = vertex + 2;
            indices[index + 3] = vertex;
            indices[index + 4] = vertex + 2;
            indices[index + 5] = vertex + 3;
        }
        return MeshAsset.builder(RIM_MESH, positions, indices, filledMaterials(12, RIM_MATERIAL))
                .normals(normals)
                .build();
    }

    private static MeshAsset meshWithMaterial(long id, Geometry geometry, long materialId) {
        return MeshAsset.builder(
                        id,
                        geometry.positions,
                        geometry.indices,
                        filledMaterials(geometry.indices.length / 3, materialId)
                )
                .normals(geometry.normals)
                .build();
    }

    private static Geometry sphereGeometry(int latitudeSegments, int longitudeSegments) {
        int stride = longitudeSegments + 1;
        int vertexCount = (latitudeSegments + 1) * stride;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];

        for (int latitude = 0; latitude <= latitudeSegments; latitude++) {
            double polar = Math.PI * latitude / latitudeSegments;
            double y = Math.cos(polar);
            double ring = Math.sin(polar);
            for (int longitude = 0; longitude <= longitudeSegments; longitude++) {
                double azimuth = 2.0 * Math.PI * longitude / longitudeSegments;
                float x = (float) (ring * Math.cos(azimuth));
                float z = (float) (ring * Math.sin(azimuth));
                int offset = (latitude * stride + longitude) * 3;
                positions[offset] = x;
                positions[offset + 1] = (float) y;
                positions[offset + 2] = z;
                normals[offset] = x;
                normals[offset + 1] = (float) y;
                normals[offset + 2] = z;
            }
        }

        int[] indices = new int[latitudeSegments * longitudeSegments * 6];
        int write = 0;
        for (int latitude = 0; latitude < latitudeSegments; latitude++) {
            for (int longitude = 0; longitude < longitudeSegments; longitude++) {
                int upper = latitude * stride + longitude;
                int lower = upper + stride;
                if (latitude != 0) {
                    indices[write++] = upper;
                    indices[write++] = upper + 1;
                    indices[write++] = lower;
                }
                if (latitude != latitudeSegments - 1) {
                    indices[write++] = upper + 1;
                    indices[write++] = lower + 1;
                    indices[write++] = lower;
                }
            }
        }
        return new Geometry(positions, normals, Arrays.copyOf(indices, write));
    }

    private static AffineTransform uniformTransform(float scale, double x, double y, double z) {
        return new AffineTransform(new float[]{
                scale, 0.0F, 0.0F, (float) x,
                0.0F, scale, 0.0F, (float) y,
                0.0F, 0.0F, scale, (float) z
        });
    }

    private static void putPosition(float[] destination, int vertex, float x, float y, float z) {
        int offset = vertex * 3;
        destination[offset] = x;
        destination[offset + 1] = y;
        destination[offset + 2] = z;
    }

    private static long[] filledMaterials(int triangles, long materialId) {
        long[] materials = new long[triangles];
        Arrays.fill(materials, materialId);
        return materials;
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    private record Geometry(float[] positions, float[] normals, int[] indices) {
    }
}
