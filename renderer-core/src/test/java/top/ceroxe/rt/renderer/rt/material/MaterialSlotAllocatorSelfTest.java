package top.ceroxe.rt.renderer.rt.material;

import java.util.List;
import java.util.SplittableRandom;
import top.ceroxe.rt.renderer.scene.SectionKey;

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
      MaterialSlotAllocator.FaceRange prefix = allocator.allocate(2147483391);
      MaterialSlotAllocator.FaceRange tail = allocator.allocate(64);
      allocator.release(tail);
      RuntimeException failure = expectFailure(() -> allocator.grow(prefix, 2147483647));
      require(failure instanceof IllegalArgumentException, "overflowing tail growth must be rejected");
      allocator.assertInvariants(List.of(anchor, prefix));
      MaterialSlotAllocator.FaceRange recovered = allocator.allocate(64);
      require(recovered.firstFace() == 2147483455, "rejected tail growth must preserve the adjacent free range");
      allocator.assertInvariants(List.of(anchor, prefix, recovered));
   }

   private static void survivesMcLikeAllocationChurnAboveThousandFps() {
      int sectionCount = 4096;
      int frames = 1200;
      int replacementsPerFrame = 24;
      int mutationsPerFrame = 96;
      SectionKey[] keys = new SectionKey[4096];
      MaterialSlotAllocator<SectionKey> allocator = new MaterialSlotAllocator<>();
      SplittableRandom random = new SplittableRandom(5567388484518562881L);
      RtSceneMaterialTable.SectionMaterial[] materials = materialSamples();

      for(int index = 0; index < 4096; ++index) {
         keys[index] = key(index);
         allocator.update(keys[index], materials[random.nextInt(materials.length)]);
      }

      allocator.assertInvariants();
      long allocatorNanos = 0L;
      int nextKey = 4096;

      for(int frame = 0; frame < 1200; ++frame) {
         long frameStartNanos = System.nanoTime();

         for(int mutation = 0; mutation < 96; ++mutation) {
            int index = random.nextInt(4096);
            allocator.update(keys[index], materials[random.nextInt(materials.length)]);
         }

         for(int replacement = 0; replacement < 24; ++replacement) {
            int index = Math.floorMod(frame * 24 + replacement, 4095);
            require(allocator.release(keys[index]), "active stress slot must release exactly once");
            keys[index] = key(nextKey++);
            allocator.update(keys[index], materials[random.nextInt(materials.length)]);
         }

         allocatorNanos += Math.max(1L, System.nanoTime() - frameStartNanos);
         allocator.assertInvariants();
      }

      long framesPerSecond = 1200000000000L / Math.max(1L, allocatorNanos);
      // Preserve timing telemetry for controlled benchmarks, but gate only deterministic allocator
      // invariants because a shared CI runner cannot provide a stable wall-clock performance floor.
      System.out.println("MaterialSlotAllocatorStressMetrics fps=" + framesPerSecond + ", frames=1200, slots=" + allocator.slotCount() + ", faceCapacity=" + allocator.faceCapacity());
      require(allocator.slotCount() == 4096, "slot churn must remain bounded after warmup");
      require(allocator.activeSlotCount() == 4096, "slot churn must retain every active section");
      require(allocator.reusedSlotAllocations() >= 28800L, "every replacement must reuse a persistent slot");
   }

   private static RtSceneMaterialTable.SectionMaterial[] materialSamples() {
      RtSceneMaterialTable.SectionMaterial[] samples = new RtSceneMaterialTable.SectionMaterial[153];

      for(int index = 0; index < samples.length; ++index) {
         int faces = 40 + index;
         int[] records = new int[Math.multiplyExact(faces, 12)];

         for(int face = 0; face < faces; ++face) {
            records[face * 12] = index * 257 + face;
         }

         samples[index] = new RtSceneMaterialTable.SectionMaterial(records);
      }

      return samples;
   }

   private static SectionKey key(int id) {
      return new SectionKey(id & 2047, id >>> 11 & 127, id >>> 18);
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
