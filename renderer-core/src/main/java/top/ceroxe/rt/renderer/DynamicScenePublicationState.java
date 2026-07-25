package top.ceroxe.rt.renderer;

import java.util.Objects;

/**
 * Composes authoritative dynamic-content snapshots with independently produced
 * lightmap state before the renderer publishes one complete RT scene snapshot.
 *
 * <p>host extracts model ownership and lightmap uniforms through different
 * frame paths. Treating a lightmap-only update as an empty authoritative scene
 * removes every persistent dynamic slot for one frame, then reallocates the same
 * owners on the next extraction. This state object is the single publication
 * boundary: only an authoritative snapshot may replace content, while a lightmap
 * update replaces only the lightmap component. It also translates the independent
 * source revisions into one monotonic publication generation.</p>
 */
final class DynamicScenePublicationState {
    private DynamicRenderScene retainedScene = DynamicRenderScene.empty();
    private long publishedRevision;
    private long publishedLightmapRevision;

    private static LightmapPayload newestLightmap(
            LightmapPayload sceneLightmap,
            LightmapPayload latestLightmap
    ) {
        if (latestLightmap.revision() == sceneLightmap.revision()) {
            if (!latestLightmap.equals(sceneLightmap)) {
                throw new IllegalArgumentException("one lightmap revision must identify exactly one payload");
            }
            return sceneLightmap;
        }
        return latestLightmap.revision() > sceneLightmap.revision() ? latestLightmap : sceneLightmap;
    }

    DynamicRenderScene publishSnapshot(
            DynamicRenderScene scene,
            LightmapPayload latestLightmapPayload
    ) {
        DynamicRenderScene source = Objects.requireNonNull(scene, "scene");
        if (!source.hasSceneUpdate()) {
            throw new IllegalArgumentException("authoritative dynamic snapshot must carry an update");
        }
        LightmapPayload lightmap = newestLightmap(
                newestLightmap(
                        source.lightmapPayload(),
                        Objects.requireNonNull(latestLightmapPayload, "latestLightmapPayload")
                ),
                retainedScene.lightmapPayload()
        );
        return retain(source.withLightmapPayload(lightmap), lightmap.revision());
    }

    DynamicRenderScene publishLightmapUpdate(LightmapPayload lightmapPayload) {
        LightmapPayload lightmap = Objects.requireNonNull(lightmapPayload, "lightmapPayload");
        if (lightmap.revision() == publishedLightmapRevision
                && !lightmap.equals(retainedScene.lightmapPayload())) {
            throw new IllegalArgumentException("one lightmap revision must identify exactly one payload");
        }
        if (lightmap.revision() <= publishedLightmapRevision) {
            return DynamicRenderScene.empty();
        }
        return retain(retainedScene.withLightmapPayload(lightmap), lightmap.revision());
    }

    void reset() {
        retainedScene = DynamicRenderScene.empty();
        publishedRevision = 0L;
        publishedLightmapRevision = 0L;
    }

    private DynamicRenderScene retain(DynamicRenderScene scene, long lightmapRevision) {
        long sourceRevision = Math.max(scene.revision(), lightmapRevision);
        long publicationRevision = sourceRevision > publishedRevision
                ? sourceRevision
                : Math.addExact(publishedRevision, 1L);
        retainedScene = scene.withRevision(publicationRevision);
        publishedRevision = publicationRevision;
        publishedLightmapRevision = Math.max(publishedLightmapRevision, lightmapRevision);
        return retainedScene;
    }
}
