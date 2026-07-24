package top.ceroxe.mcvulkanrt.renderer.rt.material;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.List;
import java.util.SplittableRandom;

public final class MaterialSlotAllocatorSelfTest {
    private static final int INTS_PER_FACE_RECORD = 12;

    private MaterialSlotAllocatorSelfTest() {
    }

    public static void main(String[] args) {
        preservesFaceAllocatorAfterRejectedTailGrowth();
        survivesMcLikeAllocationChurnAboveThousandFps();
        System.out.println("MaterialSlotAllocatorSelfTest passed");
    }

    private static void preservesFaceAllocatorAfterRejectedTailGrowth() {
        MaterialSlotAllocator.FaceRangeAllocator allocator = new MaterialSlotAllocator.FaceRangeAllocator();
        MaterialSlotAllocator.FaceRange anchor = allocator.allocate(64);
        MaterialSlotAllocator.FaceRange prefix = allocator.allocate(Integer.MAX_VALUE - 256);
        MaterialSlotAllocator.FaceRange tail = allocator.allocate(64);
        allocator.release(tail);

        RuntimeException failure = expectFailure(() -> allocator.grow(prefix, Integer.MAX_VALUE));
        require(failure instanceof IllegalArgumentException, "overflowing tail growth must be rejected");
        allocator.assertInvariants(List.of(anchor, prefix));

        MaterialSlotAllocator.FaceRange recovered = allocator.allocate(64);
        require(recovered.firstFace() == Integer.MAX_VALUE - 192,
                "rejected tail growth must preserve the adjacent free range");
        allocator.assertInvariants(List.of(anchor, prefix, recovered));
    }

    private static void survivesMcLikeAllocationChurnAboveThousandFps() {
        final int sectionCount = 4096;
        final int frames = 1200;
        final int replacementsPerFrame = 24;
        final int mutationsPerFrame = 96;
        SectionKey[] keys = new SectionKey[sectionCount];
        MaterialSlotAllocator<SectionKey> allocator = new MaterialSlotAllocator<>();
        SplittableRandom random = new SplittableRandom(0x4D435654_52544C41L);
        RtSceneMaterialTable.SectionMaterial[] materials = materialSamples();

        for (int index = 0; index < sectionCount; index++) {
            keys[index] = key(index);
            allocator.update(keys[index], materials[random.nextInt(materials.length)]);
        }
        allocator.assertInvariants();

        long allocatorNanos = 0L;
        int nextKey = sectionCount;
        for (int frame = 0; frame < frames; frame++) {
            long frameStartNanos = System.nanoTime();
            for (int mutation = 0; mutation < mutationsPerFrame; mutation++) {
                int index = random.nextInt(sectionCount);
                allocator.update(keys[index], materials[random.nextInt(materials.length)]);
            }
            for (int replacement = 0; replacement < replacementsPerFrame; replacement++) {
                int index = Math.floorMod(frame * replacementsPerFrame + replacement, sectionCount - 1);
                require(allocator.release(keys[index]), "active stress slot must release exactly once");
                keys[index] = key(nextKey++);
                allocator.update(keys[index], materials[random.nextInt(materials.length)]);
            }
            allocatorNanos += Math.max(1L, System.nanoTime() - frameStartNanos);
            allocator.assertInvariants();
        }
        long framesPerSecond = frames * 1_000_000_000L / Math.max(1L, allocatorNanos);

        require(allocator.slotCount() == sectionCount, "slot churn must remain bounded after warmup");
        require(allocator.activeSlotCount() == sectionCount, "slot churn must retain every active section");
        require(allocator.reusedSlotAllocations() >= (long) frames * replacementsPerFrame,
                "every replacement must reuse a persistent slot");
        require(framesPerSecond >= 500L,
                "material allocator stress gate below 500 FPS: fps=" + framesPerSecond
                        + ", slots=" + allocator.slotCount()
                        + ", faceCapacity=" + allocator.faceCapacity());
    }

    private static RtSceneMaterialTable.SectionMaterial[] materialSamples() {
        RtSceneMaterialTable.SectionMaterial[] samples = new RtSceneMaterialTable.SectionMaterial[153];
        for (int index = 0; index < samples.length; index++) {
            int faces = 40 + index;
            int[] records = new int[Math.multiplyExact(faces, INTS_PER_FACE_RECORD)];
            for (int face = 0; face < faces; face++) {
                records[face * INTS_PER_FACE_RECORD] = index * 257 + face;
            }
            samples[index] = new RtSceneMaterialTable.SectionMaterial(records);
        }
        return samples;
    }

    private static SectionKey key(int id) {
        return new SectionKey(id & 0x7FF, (id >>> 11) & 0x7F, id >>> 18);
    }

    private static RuntimeException expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
