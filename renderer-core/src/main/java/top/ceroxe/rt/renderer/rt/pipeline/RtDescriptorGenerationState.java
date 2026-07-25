package top.ceroxe.rt.renderer.rt.pipeline;

/**
 * Transactional descriptor-visible TLAS identity and generation state.
 *
 * <p>Callers prepare an immutable transition before performing work that may fail, then commit it
 * only at the descriptor publication point. A stale or non-contiguous transition is rejected
 * before any state changes, preventing a partially visible generation.</p>
 */
final class RtDescriptorGenerationState {
    private long worldTlas;
    private long dynamicTlas;
    private int terrainMaterialCount;
    private long generation;

    RtDescriptorGenerationState(
            long worldTlas,
            long dynamicTlas,
            int terrainMaterialCount,
            long generation
    ) {
        if (terrainMaterialCount < 0) {
            throw new IllegalArgumentException("terrain material count must not be negative");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("descriptor generation must be positive");
        }
        this.worldTlas = worldTlas;
        this.dynamicTlas = dynamicTlas;
        this.terrainMaterialCount = terrainMaterialCount;
        this.generation = generation;
    }

    Transition prepareWorld(long nextWorldTlas, int nextTerrainMaterialCount, boolean forceChange) {
        if (!forceChange
                && worldTlas == nextWorldTlas
                && terrainMaterialCount == nextTerrainMaterialCount) {
            return Transition.unchanged(generation, worldTlas, dynamicTlas, terrainMaterialCount);
        }
        return Transition.changed(
                generation,
                nextWorldTlas,
                dynamicTlas,
                nextTerrainMaterialCount
        );
    }

    Transition prepareWorldAndDynamic(
            long nextWorldTlas,
            long nextDynamicTlas,
            int nextTerrainMaterialCount,
            boolean forceChange
    ) {
        if (!forceChange
                && worldTlas == nextWorldTlas
                && dynamicTlas == nextDynamicTlas
                && terrainMaterialCount == nextTerrainMaterialCount) {
            return Transition.unchanged(generation, worldTlas, dynamicTlas, terrainMaterialCount);
        }
        return Transition.changed(
                generation,
                nextWorldTlas,
                nextDynamicTlas,
                nextTerrainMaterialCount
        );
    }

    void commit(Transition transition) {
        if (transition.previousGeneration() != generation) {
            throw new IllegalStateException(
                    "stale descriptor transition: active=" + generation
                            + ", transition=" + transition.previousGeneration()
            );
        }
        long expectedGeneration = transition.changed()
                ? Math.incrementExact(generation)
                : generation;
        if (transition.nextGeneration() != expectedGeneration) {
            throw new IllegalStateException(
                    "non-contiguous descriptor transition: expected=" + expectedGeneration
                            + ", actual=" + transition.nextGeneration()
            );
        }
        worldTlas = transition.worldTlas();
        dynamicTlas = transition.dynamicTlas();
        terrainMaterialCount = transition.terrainMaterialCount();
        generation = transition.nextGeneration();
    }

    long worldTlas() {
        return worldTlas;
    }

    long dynamicTlas() {
        return dynamicTlas;
    }

    int terrainMaterialCount() {
        return terrainMaterialCount;
    }

    long generation() {
        return generation;
    }

    record Transition(
            long previousGeneration,
            long nextGeneration,
            boolean changed,
            long worldTlas,
            long dynamicTlas,
            int terrainMaterialCount
    ) {
        private static Transition unchanged(
                long generation,
                long worldTlas,
                long dynamicTlas,
                int terrainMaterialCount
        ) {
            return new Transition(
                    generation,
                    generation,
                    false,
                    worldTlas,
                    dynamicTlas,
                    terrainMaterialCount
            );
        }

        private static Transition changed(
                long previousGeneration,
                long worldTlas,
                long dynamicTlas,
                int terrainMaterialCount
        ) {
            if (terrainMaterialCount < 0) {
                throw new IllegalArgumentException("terrain material count must not be negative");
            }
            return new Transition(
                    previousGeneration,
                    Math.incrementExact(previousGeneration),
                    true,
                    worldTlas,
                    dynamicTlas,
                    terrainMaterialCount
            );
        }
    }
}
