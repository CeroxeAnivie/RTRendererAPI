package top.ceroxe.rt.renderer.rt.acceleration;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.DynamicMeshAsset;
import top.ceroxe.rt.renderer.DynamicMeshAssetIdAllocator;
import top.ceroxe.rt.renderer.DynamicMeshInstance;
import top.ceroxe.rt.renderer.DynamicRenderLane;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererForegroundWork;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.rt.renderer.DynamicMeshAssetIdAllocator.Domain;
import top.ceroxe.rt.renderer.DynamicMeshInstance.AffineTransform;
import top.ceroxe.rt.renderer.DynamicMeshInstance.SurfaceDecal;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveGeometryKind;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveKind;
import top.ceroxe.rt.renderer.RendererFrameSubmission.Source;
import top.ceroxe.rt.renderer.orchestration.work.SectionWorkLane;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure.TlasInstance;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionActiveViewPolicy.Refresh;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionMaterialReuseCache.Outcome;
import top.ceroxe.rt.renderer.rt.material.RtBlendMode;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.SectionMaterial;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.Snapshot;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

public final class RtDynamicMeshContractsSelfTest {
   private RtDynamicMeshContractsSelfTest() {
   }

   public static void main(String[] args) {
      deeplyOwnsDynamicMeshData();
      rejectsInvalidGeometryAndMaterialReferences();
      admitsTriangleNativeMaterialRecordsWithoutQuadPadding();
      resolvesInstanceRenderLaneBeforeBlasScheduling();
      separatesAssetIdDomains();
      promotesLegacyModelsIntoStableInstances();
      preservesFullAffineTransformForTlas();
      sharesGeometryWhileVaryingPerInstanceMaterial();
      keepsOnlyTheNewestQueuedAssetVersion();
      keepsResidentBlasVisibleDuringVersionBuild();
      separatesLegacyGeometryRevisionFromInstanceRevision();
      separatesDynamicMaterialUploadsFromTlasRefits();
      keepsTlasUpdateEligibleForTransformOnlyChanges();
      coalescesOnlyTransformOnlyTlasUpdates();
      retriesDynamicGenerationAfterStalePublicationDiscard();
      keepsTlasTopologyStableInsidePersistentCapacity();
      preservesPagedTlasInstanceGenerationsAcrossSparseUpdates();
      invalidatesActiveViewOnlyForTopologyOwnerGenerations();
      keepsMaterialLayoutStableForTransformOnlyChanges();
      composesDynamicMaterialsAgainstBoundTerrainPrefix();
      keepsDynamicMaterialCustomIndicesStableAcrossTerrainGrowth();
      retainsPresentedFrontInPhysicalAdmissionUntilAcknowledged();
      keepsSurvivingPersistentSlotsStableAcrossRemovalAndReuse();
      rebasesFullBootstrapOntoAuthoritativeCollectorSlots();
      serializesDynamicFoilModeIntoFaceMetadata();
      serializesDynamicBlendModeIntoFaceIdentity();
      serializesReferenceOverlayIntoVertexLighting();
      serializesReferenceTintAlphaIntoVertexLighting();
      serializesSameSurfaceCrumblingDecal();
      validatesDynamicTlasPhysicalSlotAccounting();
      reusesImmutableIdentityAndVisibleMembershipViews();
      forwardsPackedAdmissionMembershipIdentity();
      validatesMembershipByGenerationDelta();
      exactAdmissionNeverPublishesFarFieldProxyCells();
      sharesUnchangedMembershipRadixBranches();
      reusesStageCoverageProofUntilPublicationChanges();
      retainsGeometricPackedBuilderCapacityAcrossResets();
      keepsPackedMembershipConcurrentReadsImmutable();
      reusesNativeTerrainOwnershipUntilMembershipChanges();
      rebasesEquivalentForegroundWorkOntoTheCurrentPublication();
      requeuesEvictedForegroundSourceWork();
      reusesPackedSectionMaterialsWithoutHidingCacheMissCauses();
      System.out.println("RtDynamicMeshContractsSelfTest passed");
   }

   private static void preservesPagedTlasInstanceGenerationsAcrossSparseUpdates() {
      List<RtAccelerationStructure.TlasInstance> initial = List.of(tlasInstance(4096L, 0.0F), tlasInstance(8192L, 1.0F), tlasInstance(12288L, 2.0F));
      List<RtAccelerationStructure.TlasInstance> firstGeneration = RtPersistentTlasInstanceTable.update(List.of(), initial, new BitSet());
      List<RtAccelerationStructure.TlasInstance> unchangedGeneration = RtPersistentTlasInstanceTable.update(firstGeneration, initial, new BitSet());
      require(unchangedGeneration == firstGeneration, "an unchanged dynamic TLAS slot table must retain its immutable generation");
      List<RtAccelerationStructure.TlasInstance> moved = List.of((RtAccelerationStructure.TlasInstance)initial.get(0), tlasInstance(8192L, 9.0F), (RtAccelerationStructure.TlasInstance)initial.get(2));
      BitSet dirtySlots = new BitSet();
      dirtySlots.set(1);
      List<RtAccelerationStructure.TlasInstance> sparseGeneration = RtPersistentTlasInstanceTable.update(firstGeneration, moved, dirtySlots);
      require(sparseGeneration != firstGeneration && sparseGeneration.get(0) == initial.get(0) && ((RtAccelerationStructure.TlasInstance)sparseGeneration.get(1)).m03() == 9.0F && sparseGeneration.get(2) == initial.get(2), "a sparse dynamic TLAS update must replace only the named physical slot values");
      List<RtAccelerationStructure.TlasInstance> expanded = List.of((RtAccelerationStructure.TlasInstance)moved.get(0), (RtAccelerationStructure.TlasInstance)moved.get(1), (RtAccelerationStructure.TlasInstance)moved.get(2), tlasInstance(16384L, 4.0F));
      List<RtAccelerationStructure.TlasInstance> resizedGeneration = RtPersistentTlasInstanceTable.update(sparseGeneration, expanded, dirtySlots);
      require(resizedGeneration.size() == 4 && ((RtAccelerationStructure.TlasInstance)resizedGeneration.get(3)).blasDeviceAddress() == 16384L, "a physical TLAS capacity change must publish a complete new immutable generation");
   }

   private static void admitsTriangleNativeMaterialRecordsWithoutQuadPadding() {
      float[] positions = new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F};
      int[] indices = new int[]{0, 1, 2, 0, 2, 3, 0, 3, 4};
      int[] materialRecords = new int[36];
      materialRecords[0] = 11;
      materialRecords[12] = 22;
      materialRecords[24] = 33;
      RtDynamicTriangleMesh mesh = RtDynamicTriangleMesh.fromTriangleRecords(91L, 1L, positions, indices, materialRecords, RtMaterialTelemetrySink.NOOP);
      positions[0] = 99.0F;
      indices[0] = 4;
      materialRecords[0] = 99;
      require(mesh.triangleCount() == 3 && mesh.faceCount() == 3, "triangle-native payload must retain one material record per primitive");
      require(mesh.primitivesPerMaterialRecord() == 1, "triangle-native payload was silently reinterpreted as a quad lane");
      require(mesh.vertexPositions()[0] == 0.0F && mesh.indices()[0] == 0 && mesh.faceRecords()[0] == 11, "triangle-native payload retained caller-owned mutable arrays");
      expectFailure(() -> RtDynamicTriangleMesh.fromTriangleRecords(92L, 1L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new int[24], RtMaterialTelemetrySink.NOOP));
   }

   private static RtAccelerationStructure.TlasInstance tlasInstance(long address, float translateX) {
      return new RtAccelerationStructure.TlasInstance(address, translateX, 0.0F, 0.0F, 0);
   }

   private static void rebasesEquivalentForegroundWorkOntoTheCurrentPublication() {
      List<SectionKey> keys = List.of(new SectionKey(1, 2, 3), new SectionKey(4, 5, 6));
      PackedSectionMembership current = PackedSectionMembership.canonicalDistinct(keys);
      PackedSectionMembership equalButNew = PackedSectionMembership.canonicalDistinct(new ArrayList<>(keys));
      require(current != equalButNew && current.equals(equalButNew), "test must exercise equal membership with a distinct publication identity");
      RendererViewState candidateView = RendererViewState.host(9L, equalButNew);
      RendererForegroundWork candidate = new RendererForegroundWork(candidateView, 17L, Set.of());
      RendererForegroundWork rebased = RtSectionForegroundState.rebaseEquivalentMembership(current, candidate);
      require(rebased.sectionKeys() == current, "equal foreground work must share the cache-owned immutable authority identity");
      require(rebased.successorGeneration() == 17L && rebased.viewState().revision() == 9L, "identity rebasing must preserve successor and view revisions");
   }

   private static void reusesImmutableIdentityAndVisibleMembershipViews() {
      require(AffineTransform.identity() == AffineTransform.identity(), "immutable identity transforms must be shared by stable physical TLAS slots");
      SectionKey first = new SectionKey(-4, 2, 7);
      SectionKey second = new SectionKey(9, 3, 11);
      RendererViewState view = RendererViewState.host(7L, List.of(second, first, second));
      Set<SectionKey> membership = view.visibleSectionKeySet();
      require(membership.size() == 2 && membership.contains(first) && membership.contains(second), "canonical visible membership must retain exact set semantics without duplicate keys");
      require((new ArrayList<>(membership)).equals(view.visibleSectionKeys()), "visible membership iteration must preserve canonical renderer ordering");
      expectFailure(() -> membership.remove(first));
   }

   private static void reusesNativeTerrainOwnershipUntilMembershipChanges() {
      SectionKey source = new SectionKey(1, 2, 3);
      SectionKey queued = new SectionKey(2, 2, 3);
      SectionKey recording = new SectionKey(3, 2, 3);
      SectionKey gpuInFlight = new SectionKey(4, 2, 3);
      SectionKey active = new SectionKey(5, 2, 3);
      SectionKey bound = new SectionKey(4, 5, 6);
      SectionKey authority = new SectionKey(7, 2, 3);
      NativeTerrainOwnership ownership = new NativeTerrainOwnership(Set.of(source), Set.of(queued), Set.of(recording), Set.of(gpuInFlight), Set.of(active), Set.of(bound), Set.of(authority));
      RtNativeTerrainOwnershipCache cache = new RtNativeTerrainOwnershipCache();
      long firstGeneration = cache.observeGeneration(1L, 2L, 3L, 4L, 5L, 6L, 7L);
      cache.publish(ownership, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
      require(cache.isCurrent(1L, 2L, 3L, 4L, 5L, 6L, 7L), "stable ownership generations must reuse one immutable snapshot");
      require(cache.snapshot() == ownership, "a cache hit must preserve snapshot identity instead of copying seven large sets");
      require(firstGeneration == 0L && cache.observeGeneration(1L, 2L, 3L, 4L, 5L, 6L, 7L) == firstGeneration && cache.observeGeneration(1L, 2L, 3L, 4L, 5L, 6L, 8L) == firstGeneration + 1L, "ownership generation must advance exactly once per changed revision vector");
      require(ownership.ownsSection(source) && ownership.ownsSection(queued) && ownership.ownsSection(recording) && ownership.ownsSection(gpuInFlight) && ownership.ownsSection(active) && ownership.ownsSection(bound) && !ownership.ownsSection(authority), "only actual terrain lifecycle stages must exclude a section from CPU backfill");
      require(!ownership.ownsSection(new SectionKey(99, 2, 3)), "unowned terrain must remain eligible for CPU backfill");
      long[] revisions = new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L};

      for(int changedDomain = 0; changedDomain < revisions.length; ++changedDomain) {
         long[] changed = revisions.clone();
         changed[changedDomain]++;
         require(!cache.isCurrent(changed[0], changed[1], changed[2], changed[3], changed[4], changed[5], changed[6]), "every ownership domain must invalidate the shared snapshot independently");
      }

      try {
         ownership.sourceSectionKeys().add(bound);
         throw new AssertionError("native terrain ownership must remain immutable");
      } catch (UnsupportedOperationException value14) {
      }
   }

   private static void requeuesEvictedForegroundSourceWork() {
      RtPendingBlasBuildQueue<Void> queue = new RtPendingBlasBuildQueue<>(2, 9223372036854775807L);
      RtPendingBlasBuildOwnership ownership = new RtPendingBlasBuildOwnership();
      SectionTriangleMesh first = sectionMaterialMesh(21, 100, 1000);
      SectionTriangleMesh second = sectionMaterialMesh(22, 101, 1000);
      SectionTriangleMesh third = sectionMaterialMesh(23, 102, 1000);
      queue.enqueue(first, SectionWorkLane.FOREGROUND);
      queue.enqueue(second, SectionWorkLane.FOREGROUND);
      queue.enqueue(third, SectionWorkLane.FOREGROUND);
      require(!queue.contains(first.key()), "bounded foreground queue should expose eviction for recovery testing");
      require(ownership.enqueueIfUnowned(first, queue, SectionWorkLane.FOREGROUND), "durable successor work must re-admit an evicted source mesh");
      require(queue.contains(first.key()), "re-admitted successor source must become actual queue ownership");
   }

   private static void forwardsPackedAdmissionMembershipIdentity() {
      SectionKey first = new SectionKey(-2, 4, 1);
      SectionKey second = new SectionKey(3, 4, 1);
      PackedSectionMembership publication = PackedSectionMembership.canonicalDistinct(List.of(first, second));
      require(RtSectionInstanceAdmission.immutableMembershipSnapshot(publication) == publication, "admission must forward an existing packed membership without freezing it again");
      RtNativeTerrainOwnershipCache cache = new RtNativeTerrainOwnershipCache();
      require(cache.freezeSource(publication, 10L) == publication && cache.freezeActive(publication, 11L) == publication && cache.freezeBound(publication, 12L) == publication && cache.freezeAuthority(publication, 13L) == publication, "native ownership must retain all four producer packed publication identities");
      PackedSectionMembership changed = PackedSectionMembership.canonicalDistinct(List.of(second));
      require(cache.freezeSource(changed, 14L) == changed && cache.freezeActive(changed, 15L) == changed && cache.freezeBound(changed, 16L) == changed && cache.freezeAuthority(changed, 17L) == changed, "each native ownership revision must publish its changed producer identity");
      require(expectFailure(() -> RtSectionInstanceAdmission.immutableMembershipSnapshot(List.of(first, first))) instanceof IllegalArgumentException, "external admission collections must still reject duplicate section coordinates");
   }

   private static void validatesMembershipByGenerationDelta() {
      SectionKey first = new SectionKey(-1, 2, 3);
      SectionKey second = new SectionKey(0, 2, 3);
      SectionKey third = new SectionKey(1, 2, 3);
      PackedSectionMembership resident = packedSuccessor((PackedSectionMembership)null, first, second);
      PackedSectionMembership base = packedSuccessor((PackedSectionMembership)null, first);
      RtSectionInstanceAdmission planner = new RtSectionInstanceAdmission(8, 8, 0, true, true);
      planner.plan(RendererViewState.host(1L, resident), resident, base, 1L, 1L);
      require(planner.fullMembershipValidations() == 1L && planner.deltaMembershipValidations() == 0L, "the first trusted producer generation must establish its subset relation in full");
      PackedSectionMembership expandedResident = packedSuccessor(resident, first, second, third);
      PackedSectionMembership expandedBase = packedSuccessor(base, first, third);
      planner.plan(RendererViewState.host(2L, expandedResident), expandedResident, expandedBase, 2L, 2L);
      require(planner.fullMembershipValidations() == 1L && planner.deltaMembershipValidations() == 1L, "a direct coverage successor must validate only entered/exited packed coordinates");
      PackedSectionMembership retiredResident = packedSuccessor(expandedResident, second, third);
      PackedSectionMembership retiredBase = packedSuccessor(expandedBase, third);
      planner.plan(RendererViewState.host(3L, retiredResident), retiredResident, retiredBase, 3L, 3L);
      require(planner.deltaMembershipValidations() == 2L, "retire transitions must preserve the relation when both publications retire ownership");
      PackedSectionMembership emptyResident = packedSuccessor(retiredResident);
      PackedSectionMembership emptyBase = packedSuccessor(retiredBase);
      planner.plan(RendererViewState.host(4L, emptyResident), emptyResident, emptyBase, 4L, 4L);
      require(planner.deltaMembershipValidations() == 3L, "an empty successor must remain a valid immutable generation transition");
      RtSectionInstanceAdmission invalidPlanner = new RtSectionInstanceAdmission(8, 8, 0, true, true);
      invalidPlanner.plan(RendererViewState.host(10L, expandedResident), expandedResident, expandedBase, 10L, 10L);
      PackedSectionMembership unrevisionedReplacement = packedSuccessor((PackedSectionMembership)null, first, second, third);
      require(expectFailure(() -> invalidPlanner.plan(RendererViewState.host(10L, unrevisionedReplacement), unrevisionedReplacement, expandedBase, 10L, 10L)) instanceof IllegalArgumentException, "a packed producer must advance its revision when publication identity changes");
      PackedSectionMembership invalidResident = packedSuccessor(expandedResident, first, second);
      require(expectFailure(() -> invalidPlanner.plan(RendererViewState.host(11L, invalidResident), invalidResident, expandedBase, 11L, 10L)) instanceof IllegalArgumentException, "retiring a resident coordinate still owned by Base must fail full fallback validation");
      require(invalidPlanner.fullMembershipValidations() == 2L, "an invalid or non-provable transition must never bypass complete validation");
      require(expectFailure(() -> planner.plan(RendererViewState.host(20L, List.of(first)), List.of(first), List.of(third), 20L, 20L)) instanceof IllegalArgumentException, "revision tokens must not make externally assembled collections a trusted boundary");
   }

   private static void exactAdmissionNeverPublishesFarFieldProxyCells() {
      SectionKey first = new SectionKey(0, 4, 0);
      SectionKey second = new SectionKey(1, 4, 0);
      PackedSectionMembership resident = PackedSectionMembership.canonicalDistinct(List.of(first, second));
      PackedSectionMembership exactReady = PackedSectionMembership.canonicalDistinct(List.of(first));
      RtSectionInstanceAdmission planner = new RtSectionInstanceAdmission(32768, 256, 0, true, false, false, false);
      RtSectionInstanceAdmission.Admission admission = planner.plan(RendererViewState.host(1L, resident), resident, exactReady, 1L, 1L);
      require(admission.baseSections().equals(List.of(first)), "exact admission must retain the completed exact BLAS");
      require(admission.farFieldCells().isEmpty(), "exact admission must never substitute a far-field proxy cell");
      require(admission.uncoveredSections() == 1, "missing exact geometry must remain explicitly uncovered until its BLAS installs");
   }

   private static void keepsPackedMembershipConcurrentReadsImmutable() {
      SectionKey[] keys = new SectionKey[256];

      for(int index = 0; index < keys.length; ++index) {
         keys[index] = new SectionKey(index - 128, index & 7, 17 - index);
      }

      PackedSectionMembership publication = packedSuccessor((PackedSectionMembership)null, keys);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread[] readers = new Thread[4];

      for(int reader = 0; reader < readers.length; ++reader) {
         readers[reader] = new Thread(() -> {
            try {
               for(int iteration = 0; iteration < 2000; ++iteration) {
                  for(SectionKey key : keys) {
                     require(publication.contains(key), "concurrent membership reads must retain every published coordinate");
                     require(RendererViewState.canonicalSectionIndex(publication.orderedKeys(), key) >= 0, "concurrent canonical lookup must retain the generation index");
                  }
               }
            } catch (Throwable throwable) {
               failure.compareAndSet(null, throwable);
            }

         }, "packed-membership-reader-" + reader);
         readers[reader].start();
      }

      for(Thread reader : readers) {
         try {
            reader.join();
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("membership concurrency test interrupted", interrupted);
         }
      }

      if (failure.get() != null) {
         throw new AssertionError("immutable membership publication failed concurrent reads", (Throwable)failure.get());
      }
   }

   private static void sharesUnchangedMembershipRadixBranches() {
      SectionKey[] keys = new SectionKey[128];

      for(int index = 0; index < keys.length; ++index) {
         keys[index] = new SectionKey(index - 64, index & 15, 97 - index * 3);
      }

      SectionKey removed = keys[0];
      int affectedPage = membershipPage(removed);
      int affectedRootSlot = affectedPage >>> 9;
      SectionKey replacement = null;

      for(int x = 10000; x < 100000; ++x) {
         SectionKey candidate = new SectionKey(x, 5, -211);
         if (membershipPage(candidate) == affectedPage) {
            replacement = candidate;
            break;
         }
      }

      require(replacement != null, "test setup must find a replacement in the affected membership page");
      PackedSectionMembership previous = packedSuccessor((PackedSectionMembership)null, keys);
      List<SectionKey> successorKeys = new ArrayList<>(Arrays.asList(keys));
      successorKeys.remove(removed);
      successorKeys.add(replacement);
      PackedSectionMembership successor = packedSuccessor(previous, (SectionKey[])successorKeys.toArray((x$0) -> new SectionKey[x$0]));
      require(!successor.contains(removed) && successor.contains(replacement) && previous.contains(removed) && !previous.contains(replacement), "copy-on-write membership pages must isolate predecessor and successor values");
      require(packedSuccessor(successor, (SectionKey[])successorKeys.toArray((x$0) -> new SectionKey[x$0])) == successor, "a stable canonical generation must reuse its complete membership publication");

      try {
         Field membershipField = PackedSectionMembership.class.getDeclaredField("membershipIndex");
         membershipField.setAccessible(true);
         Object previousIndex = membershipField.get(previous);
         Object successorIndex = membershipField.get(successor);
         Field rootField = previousIndex.getClass().getDeclaredField("root");
         rootField.setAccessible(true);
         Object previousNode = rootField.get(previousIndex);
         Object successorNode = rootField.get(successorIndex);
         Field childrenField = previousNode.getClass().getDeclaredField("children");
         childrenField.setAccessible(true);

         for(int level = 3; level >= 0; --level) {
            require(previousNode != successorNode, "a changed page must copy every radix node on its ownership path");
            Object[] previousChildren = (Object[]) childrenField.get(previousNode);
            Object[] successorChildren = (Object[]) childrenField.get(successorNode);
            require(previousChildren.length == 8 && successorChildren.length == 8, "membership radix nodes must remain bounded 8-way publications");
            int changedSlot = affectedPage >>> level * 3 & 7;

            for(int slot = 0; slot < previousChildren.length; ++slot) {
               if (slot != changedSlot) {
                  require(previousChildren[slot] == successorChildren[slot], "an untouched radix branch must retain predecessor identity");
               }
            }

            if (level > 0) {
               previousNode = previousChildren[changedSlot];
               successorNode = successorChildren[changedSlot];
            } else {
               require(previousChildren[changedSlot] != successorChildren[changedSlot], "the affected immutable page payload must be republished");
            }
         }

         Object[] previousRootChildren = (Object[]) childrenField.get(rootField.get(previousIndex));
         Object[] successorRootChildren = (Object[]) childrenField.get(rootField.get(successorIndex));
         boolean sharedPopulatedRootBranch = false;

         for(int slot = 0; slot < previousRootChildren.length; ++slot) {
            if (slot != affectedRootSlot && previousRootChildren[slot] != null) {
               sharedPopulatedRootBranch |= previousRootChildren[slot] == successorRootChildren[slot];
            }
         }

         require(sharedPopulatedRootBranch, "a small delta must structurally share populated directories outside its radix path");
      } catch (ReflectiveOperationException reflectiveFailure) {
         throw new AssertionError("unable to inspect persistent membership radix ownership", reflectiveFailure);
      }
   }

   private static int membershipPage(SectionKey key) {
      long packed = key.packed();
      return (int)HashCommon.mix(packed) & 4095;
   }

   private static void reusesStageCoverageProofUntilPublicationChanges() {
      SectionKey first = new SectionKey(-1, 3, 4);
      SectionKey second = new SectionKey(0, 3, 4);
      SectionKey third = new SectionKey(1, 3, 4);
      PackedSectionMembership authoritative = packedSuccessor((PackedSectionMembership)null, first, second, third);
      PackedSectionMembership partial = packedSuccessor((PackedSectionMembership)null, first, third);
      RtSectionCoverageProof proof = new RtSectionCoverageProof();
      require(proof.matchedCount(authoritative, 1L, partial, 10L) == 2, "the first stage publication must establish exact foreground overlap");
      require(proof.matchedCount(authoritative, 1L, partial, 10L) == 2 && proof.recomputations() == 1L, "stable publication identity and revision must reuse one coverage proof");
      PackedSectionMembership complete = packedSuccessor(partial, first, second, third);
      require(proof.matchedCount(authoritative, 1L, complete, 11L) == 3 && proof.recomputations() == 2L, "a stage delta must advance coverage exactly once");
      PackedSectionMembership contractedAuthority = packedSuccessor(authoritative, first, second);
      require(proof.matchedCount(contractedAuthority, 2L, complete, 11L) == 2 && proof.recomputations() == 3L, "an authoritative generation change must invalidate every dependent proof");
      PackedSectionMembership shiftedStage = complete.withDelta(List.of(), List.of(first));
      PackedSectionMembership shiftedAuthority = contractedAuthority.withDelta(List.of(third), List.of(first));
      require(proof.matchedCount(shiftedAuthority, 3L, shiftedStage, 12L) == 2 && proof.recomputations() == 4L, "simultaneous authority and stage deltas must update their intersection exactly once");
      require(proof.matchedCount(shiftedAuthority, 4L, shiftedStage, 13L) == 2 && proof.recomputations() == 4L, "revision-only changes on identical immutable publications must not trigger a scan");
      require(expectFailure(() -> proof.matchedCount(shiftedAuthority, 3L, shiftedStage, 13L)) instanceof IllegalStateException, "coverage proof must reject a regressing revision on the same immutable publication");
   }

   private static void retainsGeometricPackedBuilderCapacityAcrossResets() {
      try {
         PackedSectionMembership.Builder builder = PackedSectionMembership.builder(1);
         Field storageField = builder.getClass().getDeclaredField("packedSections");
         storageField.setAccessible(true);
         int initialCapacity = ((long[])storageField.get(builder)).length;
         require(initialCapacity >= 16 && Integer.bitCount(initialCapacity) == 1, "publication scratch must start at a bounded geometric capacity");
         builder.reset(initialCapacity + 1);
         int expandedCapacity = ((long[])storageField.get(builder)).length;
         require(expandedCapacity >= initialCapacity * 2 && Integer.bitCount(expandedCapacity) == 1, "crossing a high watermark must grow scratch geometrically");
         builder.addPacked(SectionKey.pack(0, 0, 0));
         builder.buildCanonical((PackedSectionMembership)null);
         builder.reset(1);
         require(((long[])storageField.get(builder)).length == expandedCapacity, "reset must retain the high-watermark array instead of allocating an exact-size replacement");
      } catch (ReflectiveOperationException reflectiveFailure) {
         throw new AssertionError("unable to inspect packed membership builder capacity", reflectiveFailure);
      }
   }

   private static PackedSectionMembership packedSuccessor(PackedSectionMembership previous, SectionKey... keys) {
      PackedSectionMembership.Builder builder = PackedSectionMembership.builder(keys.length);

      for(SectionKey key : keys) {
         builder.addPacked(key.packed());
      }

      return builder.buildCanonical(previous);
   }

   private static void reusesPackedSectionMaterialsWithoutHidingCacheMissCauses() {
      RtSectionMaterialReuseCache cache = new RtSectionMaterialReuseCache();
      SectionTriangleMesh first = sectionMaterialMesh(1, 100, 1000);
      RtSectionMaterialReuseCache.Result firstResult = cache.materialFor(first);
      require(firstResult.outcome() == Outcome.EMPTY, "the first packed section material must report an empty-cache miss");
      RtSectionMaterialReuseCache.Result identityRetry = cache.materialFor(first);
      require(identityRetry.outcome() == Outcome.GENERATION_HIT, "retrying one mesh identity must consume its generation-owned publication");
      require(identityRetry.material() == firstResult.material(), "identity retries must not derive or allocate a second packed material");
      require(!firstResult.material().meshBackedPublication(), "the bounded reuse cache must retain only compact material records");
      require(SectionMaterial.fromMesh(first).meshBackedPublication(), "queued source ownership may retain its lazy generation until BLAS installation");
      SectionTriangleMesh identical = sectionMaterialMesh(1, 100, 1000);
      RtSectionMaterialReuseCache.Result hit = cache.materialFor(identical);
      require(hit.outcome() == Outcome.HIT, "an equivalent successor revision must reuse the existing packed record array");
      require(hit.material() == firstResult.material(), "equivalent mesh revisions must share immutable material publication identity");
      require(identical.packedMaterialPublication() == firstResult.material(), "cross-generation reuse must be installed on the successor mesh for later retries");
      SectionTriangleMesh different = sectionMaterialMesh(1, 101, 1000);
      RtSectionMaterialReuseCache.Result fingerprintMiss = cache.materialFor(different);
      require(fingerprintMiss.outcome() == Outcome.FINGERPRINT_MISS, "a changed material revision must report a fingerprint miss before packing");
      require(fingerprintMiss.material() != firstResult.material(), "a material revision change must publish a distinct immutable payload");
      require(cache.materialFor(different).material() == fingerprintMiss.material(), "a changed generation must still reuse its own publication on BLAS retry");
      SectionTriangleMesh collisionFirst = sectionMaterialMesh(4, 200, 2000);
      SectionTriangleMesh collisionSecond = sectionMaterialMesh(5, 201, 1969);
      require(SectionMaterial.meshRecordHash(collisionFirst) == SectionMaterial.meshRecordHash(collisionSecond), "test setup must produce a material fingerprint collision");
      cache.clear();
      RtSectionMaterialReuseCache.Result collisionFirstResult = cache.materialFor(collisionFirst);
      RtSectionMaterialReuseCache.Result collisionResult = cache.materialFor(collisionSecond);
      require(collisionResult.outcome() == Outcome.FINGERPRINT_COLLISION, "a colliding but unequal material must report the collision miss cause");
      require(collisionResult.material() != collisionFirstResult.material(), "a fingerprint collision must never alias different material records");
      require(cache.materialFor(collisionFirst).material() == collisionFirstResult.material(), "a colliding insertion must not evict the previous reusable material");
   }

   private static SectionTriangleMesh sectionMaterialMesh(int sectionX, int textureId, int firstUv) {
      return new SectionTriangleMesh(new SectionKey(sectionX, 0, 0), new short[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{42}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{10531008}, new byte[]{7}, new byte[]{0}, new int[]{textureId}, new int[]{firstUv}, new int[]{2000}, new int[]{3000}, new int[]{4000}, new byte[]{1}, new byte[]{0});
   }

   private static void coalescesOnlyTransformOnlyTlasUpdates() {
      require(RtDynamicTlasCache.shouldDeferTransformOnlyUpdate(true, 99L, 100L), "a transform-only successor must wait for its coalescing deadline");
      require(!RtDynamicTlasCache.shouldDeferTransformOnlyUpdate(true, 100L, 100L), "a transform-only successor must become eligible at its deadline");
      require(!RtDynamicTlasCache.shouldDeferTransformOnlyUpdate(false, 99L, 100L), "topology and geometry successors must bypass transform cadence");
      long wrappedDeadline = -9223372036854775804L;
      require(RtDynamicTlasCache.shouldDeferTransformOnlyUpdate(true, 9223372036854775806L, wrappedDeadline), "transform cadence must remain ordered across nanoTime wraparound");
      require(!RtDynamicTlasCache.shouldMaterializeSnapshot(true, false, true, 12L, 11L, 4L, 4L, 7L, 7L, 9L, 8L, 99L, 100L), "an in-flight dynamic TLAS must suppress heavyweight instance snapshot materialization");
      require(!RtDynamicTlasCache.shouldMaterializeSnapshot(false, true, true, 12L, 11L, 4L, 4L, 7L, 7L, 9L, 8L, 99L, 100L), "a completed descriptor candidate must suppress redundant instance snapshot materialization");
      require(!RtDynamicTlasCache.shouldMaterializeSnapshot(false, false, true, 12L, 11L, 4L, 4L, 7L, 7L, 9L, 8L, 99L, 100L), "a transform-only successor must remain scalar-only before its convergence deadline");
      require(RtDynamicTlasCache.shouldMaterializeSnapshot(false, false, true, 12L, 11L, 5L, 4L, 7L, 7L, 9L, 8L, 99L, 100L), "a topology successor must materialize immediately despite transform cadence");
      require(RtDynamicTlasCache.shouldMaterializeSnapshot(false, false, true, 12L, 11L, 4L, 4L, 7L, 7L, 9L, 8L, 100L, 100L), "an expired transform deadline must materialize the newest coalesced instance snapshot");
      require(RtDynamicTlasCache.shouldPublishCompletedGeneration(20L, 11L, 8955L), "a completed topology generation must publish even when capture already produced successors");
      require(!RtDynamicTlasCache.shouldPublishCompletedGeneration(11L, 11L, 8955L), "a descriptor-visible generation must never be rebound as new progress");
   }

   private static void retriesDynamicGenerationAfterStalePublicationDiscard() {
      require(RtDynamicTlasCache.shouldMaterializeSnapshot(false, false, true, 42L, 41L, 7L, 7L, 9L, 9L, 13L, 12L, 200L, 200L), "discarding an unbound stale candidate must leave its generation eligible for immediate retry");
      require(!RtDynamicTlasCache.shouldMaterializeSnapshot(false, true, true, 42L, 41L, 7L, 7L, 9L, 9L, 13L, 12L, 200L, 200L), "the exact completed candidate must remain the sole scheduler owner until commit or discard");
      require(RtDynamicTlasCache.shouldPublishCompletedGeneration(42L, 41L, 43L), "a retried dynamic generation must remain publishable against a newer observed producer revision");
   }

   private static void deeplyOwnsDynamicMeshData() {
      float[] sourcePositions = quadPositions(0.0F);
      int[] sourceIndices = quadIndices();
      ArrayList<DynamicMeshAsset.Face> sourceFaces = new ArrayList<>(List.of(tintedFace()));
      DynamicMeshAsset asset = new DynamicMeshAsset(1L, 1L, sourcePositions, sourceIndices, sourceFaces);
      sourcePositions[0] = 999.0F;
      sourceIndices[0] = 3;
      sourceFaces.clear();
      requireNear(asset.vertexPositions()[0], -0.5F, "asset must copy source positions");
      require(asset.indices()[0] == 0, "asset must copy source indices");
      require(asset.faceCount() == 1, "asset must copy the source face list");
      float[] returnedPositions = asset.vertexPositions();
      int[] returnedIndices = asset.indices();
      returnedPositions[0] = 123.0F;
      returnedIndices[0] = 2;
      requireNear(asset.vertexPositions()[0], -0.5F, "position getter must be defensive");
      require(asset.indices()[0] == 0, "index getter must be defensive");
      expectFailure(() -> asset.faces().clear());
      ArrayList<DynamicMeshInstance.FaceMaterial> sourceMaterials = new ArrayList<>(List.of(faceMaterial(-13426159, 1)));
      DynamicMeshInstance instance = new DynamicMeshInstance(asset, AffineTransform.identity(), sourceMaterials);
      sourceMaterials.clear();
      require(instance.faceMaterial(0).tintRgba8() == -13426159, "instance must copy face material data");
      require(instance.faceMaterial(0).foilMode() == 1, "instance must preserve foil data");
      expectFailure(() -> instance.faceMaterials().clear());
   }

   private static void resolvesInstanceRenderLaneBeforeBlasScheduling() {
      DynamicMeshInstance.FaceMaterial world = faceMaterial(-1, 0, false);
      DynamicMeshInstance.FaceMaterial overlay = faceMaterial(-1, 0, true);
      require(DynamicRenderLane.fromFaceMaterials(List.of(world)) == DynamicRenderLane.WORLD, "depth-tested materials must resolve to the world render lane");
      require(DynamicRenderLane.fromFaceMaterials(List.of(overlay)) == DynamicRenderLane.ALWAYS_ON_TOP, "see-through materials must resolve to the always-on-top render lane");
      expectFailure(() -> DynamicRenderLane.fromFaceMaterials(List.of(world, overlay)));
      expectFailure(() -> DynamicRenderLane.fromFaceMaterials(List.of()));
      DynamicMeshAsset asset = meshAsset(8001L, 1L, 0.0F);
      DynamicRenderScene.DynamicModelInstance overlayInstance = new DynamicRenderScene.DynamicModelInstance(8001L, PrimitiveKind.ENTITY, asset, AffineTransform.identity(), List.of(overlay), 0, "overlay-render-lane");
      require(overlayInstance.renderLane() == DynamicRenderLane.ALWAYS_ON_TOP, "dynamic model construction must cache its validated render lane");
   }

   private static void rejectsInvalidGeometryAndMaterialReferences() {
      expectFailure(() -> new DynamicMeshAsset(0L, 1L, quadPositions(0.0F), quadIndices(), List.of(tintedFace())));
      expectFailure(() -> new DynamicMeshAsset(1L, 0L, quadPositions(0.0F), quadIndices(), List.of(tintedFace())));
      float[] nonFinitePositions = quadPositions(0.0F);
      nonFinitePositions[4] = 0.0F / 0.0F;
      expectFailure(() -> new DynamicMeshAsset(1L, 1L, nonFinitePositions, quadIndices(), List.of(tintedFace())));
      expectFailure(() -> new DynamicMeshAsset(1L, 1L, quadPositions(0.0F), new int[]{0, 1, 8, 0, 8, 3}, List.of(tintedFace())));
      expectFailure(() -> new DynamicMeshAsset(1L, 1L, quadPositions(0.0F), new int[]{0, 1, 2}, List.of(tintedFace())));
      DynamicMeshAsset asset = meshAsset(2L, 1L, 0.0F);
      expectFailure(() -> new DynamicMeshInstance(asset, AffineTransform.identity(), List.of()));
      expectFailure(() -> new DynamicMeshInstance(asset, AffineTransform.identity(), List.of(faceMaterial(-1, 0), faceMaterial(-1, 0))));
      expectFailure(() -> faceMaterial(-1, 3));
      expectFailure(() -> new DynamicMeshInstance.AffineTransform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F));
   }

   private static void separatesAssetIdDomains() {
      long itemId = DynamicMeshAssetIdAllocator.next(Domain.ITEM);
      long cubeId = DynamicMeshAssetIdAllocator.next(Domain.MODEL_CUBE);
      require(itemId != cubeId, "asset ID domains must never collide");
      require(DynamicMeshAssetIdAllocator.domain(itemId) == Domain.ITEM, "item asset ID must retain its domain");
      require(DynamicMeshAssetIdAllocator.domain(cubeId) == Domain.MODEL_CUBE, "model cube asset ID must retain its domain");
      expectFailure(() -> DynamicMeshAssetIdAllocator.domain(0L));
   }

   private static void promotesLegacyModelsIntoStableInstances() {
      DynamicRenderScene.DynamicPrimitive first = new DynamicRenderScene.DynamicPrimitive(51L, PrimitiveKind.ENTITY, PrimitiveGeometryKind.MODEL, 2.0, 3.0, 4.0, 0.0F, 0.0F, 0.0F, 0.8F, -13426159, 7, 15728880, true, "legacy");
      DynamicRenderScene.DynamicPrimitive moved = new DynamicRenderScene.DynamicPrimitive(51L, first.kind(), first.geometryKind(), 8.0, 3.0, 4.0, first.yaw(), first.pitch(), first.roll(), first.radius(), -10070716, first.textureKey(), first.packedLight(), first.castsShadow(), first.debugName());
      DynamicRenderScene.DynamicPrimitive firstInstance = RtDynamicTriangleMesh.promoteLegacyModelPrimitive(first);
      DynamicRenderScene.DynamicPrimitive movedInstance = RtDynamicTriangleMesh.promoteLegacyModelPrimitive(moved);
      require(firstInstance.meshInstance() != null, "legacy MODEL must become a real mesh instance");
      require(firstInstance.meshInstance().asset() == movedInstance.meshInstance().asset(), "legacy MODEL frames must share one immutable BLAS asset");
      requireNear(firstInstance.meshInstance().transform().translateX(), 2.0F, "legacy instance translation must preserve world position");
      requireNear(movedInstance.meshInstance().transform().translateX(), 8.0F, "legacy movement must update only the instance transform");
      require(!firstInstance.meshInstance().faceMaterials().equals(movedInstance.meshInstance().faceMaterials()), "legacy color changes must remain per-instance material state");
   }

   private static void preservesFullAffineTransformForTlas() {
      DynamicMeshInstance.AffineTransform transform = new DynamicMeshInstance.AffineTransform(0.0F, -2.0F, 0.0F, 10.0F, 3.0F, 0.0F, 0.0F, 20.0F, 0.0F, 0.0F, 4.0F, 30.0F);
      RtAccelerationStructure.TlasInstance instance = new RtAccelerationStructure.TlasInstance(4096L, transform, 17);
      float[] expected = new float[]{0.0F, -2.0F, 0.0F, 10.0F, 3.0F, 0.0F, 0.0F, 20.0F, 0.0F, 0.0F, 4.0F, 30.0F};

      for(int index = 0; index < expected.length; ++index) {
         requireNear(instance.transform().value(index), expected[index], "TLAS affine element " + index + " mismatch");
      }

      requireNear(instance.translateX(), 10.0F, "TLAS translation X mismatch");
      requireNear(instance.translateY(), 20.0F, "TLAS translation Y mismatch");
      requireNear(instance.translateZ(), 30.0F, "TLAS translation Z mismatch");
      expectFailure(() -> transform.value(12));
   }

   private static void sharesGeometryWhileVaryingPerInstanceMaterial() {
      DynamicMeshAsset asset = meshAsset(10L, 1L, 0.0F);
      DynamicMeshInstance firstInstance = new DynamicMeshInstance(asset, AffineTransform.identity(), List.of(faceMaterial(-16776961, 0)));
      DynamicMeshInstance secondInstance = new DynamicMeshInstance(asset, new DynamicMeshInstance.AffineTransform(1.0F, 0.0F, 0.0F, 4.0F, 0.0F, 1.0F, 0.0F, 5.0F, 0.0F, 0.0F, 1.0F, 6.0F), List.of(faceMaterial(-16711936, 1)));
      require(firstInstance.asset() == asset && secondInstance.asset() == asset, "instances must retain the same immutable asset identity");
      DynamicRenderScene.DynamicPrimitive first = primitive(100L, firstInstance, 0);
      DynamicRenderScene.DynamicPrimitive second = primitive(101L, secondInstance, 15728880);
      RtSceneMaterialTable.SectionMaterial firstMaterial = RtDynamicTriangleMesh.materialFor(first);
      RtSceneMaterialTable.SectionMaterial secondMaterial = RtDynamicTriangleMesh.materialFor(second);
      require(!Arrays.equals(firstMaterial.faceRecords(), secondMaterial.faceRecords()), "tint and packed light must remain per-instance material data");
      RtDynamicTriangleMesh geometry = RtDynamicTriangleMesh.fromAsset(asset);
      require(Arrays.equals(geometry.vertexPositions(), asset.vertexPositions()), "shared BLAS geometry must be sourced only from the asset");
      require(Arrays.equals(geometry.indices(), asset.indices()), "instance material changes must not alter BLAS indices");
      RtDynamicBlasCache.AssetBuildQueue queue = new RtDynamicBlasCache.AssetBuildQueue();
      queue.offer(asset, (DynamicMeshAsset)null, asset);
      require(queue.isEmpty(), "an already cached asset must not schedule a BLAS rebuild");
   }

   private static void keepsOnlyTheNewestQueuedAssetVersion() {
      DynamicMeshAsset revisionOne = meshAsset(20L, 1L, 0.0F);
      DynamicMeshAsset revisionTwo = meshAsset(20L, 2L, 0.1F);
      DynamicMeshAsset revisionThree = meshAsset(20L, 3L, 0.2F);
      RtDynamicBlasCache.AssetBuildQueue queue = new RtDynamicBlasCache.AssetBuildQueue();
      queue.offer(revisionOne, (DynamicMeshAsset)null, (DynamicMeshAsset)null);
      queue.offer(revisionTwo, (DynamicMeshAsset)null, (DynamicMeshAsset)null);
      queue.offer(revisionThree, revisionOne, (DynamicMeshAsset)null);
      require(queue.size() == 1, "one asset id must occupy one queue slot");
      require(queue.asset(20L).equals(revisionThree), "newest desired asset version must replace older queue data");
      require(queue.poll().equals(revisionThree), "queue must submit the newest desired version");
      require(queue.isEmpty(), "poll must remove the queued asset");
      RendererFrameCausality causality = new RendererFrameCausality(7L, Source.FRAME_END, 11L);
      queue.offer(revisionOne, causality, (DynamicMeshAsset)null, (DynamicMeshAsset)null);
      require(queue.causality(20L) == causality, "queued asset must retain its admission causality by identity");
      expectFailure(() -> queue.offer(revisionTwo, (DynamicMeshAsset)null, revisionThree));
      DynamicMeshAsset divergentRevisionThree = meshAsset(20L, 3L, 0.3F);
      queue.offer(revisionThree, (DynamicMeshAsset)null, (DynamicMeshAsset)null);
      expectFailure(() -> queue.offer(divergentRevisionThree, (DynamicMeshAsset)null, (DynamicMeshAsset)null));
      DynamicMeshAsset otherAsset = meshAsset(21L, 1L, 0.0F);
      queue.offer(otherAsset, (DynamicMeshAsset)null, (DynamicMeshAsset)null);
      LongOpenHashSet activeAssetIds = new LongOpenHashSet();
      activeAssetIds.add(21L);
      queue.retainAssetIds(activeAssetIds);
      require(queue.size() == 1 && queue.asset(21L).equals(otherAsset), "inactive queued assets must be removable without disturbing active ids");
   }

   private static void keepsResidentBlasVisibleDuringVersionBuild() {
      DynamicMeshAsset resident = meshAsset(30L, 1L, 0.0F);
      DynamicMeshAsset desired = meshAsset(30L, 2L, 0.25F);
      require(RtDynamicBlasCache.residentAssetUsableDuringReplacement(desired, resident), "a completed older revision must remain visible while its replacement BLAS builds");
      require(RtDynamicBlasCache.residentAssetUsableDuringReplacement(desired, desired), "the completed desired revision must remain directly renderable");
      require(!RtDynamicBlasCache.residentAssetUsableDuringReplacement(meshAsset(31L, 1L, 0.0F), resident), "an unrelated asset id must never borrow resident BLAS geometry");
      expectFailure(() -> RtDynamicBlasCache.residentAssetUsableDuringReplacement(resident, desired));
      expectFailure(() -> RtDynamicBlasCache.residentAssetUsableDuringReplacement(meshAsset(30L, 1L, 0.5F), resident));
   }

   private static void separatesLegacyGeometryRevisionFromInstanceRevision() {
      require(RtDynamicBlasCache.shouldSubmitLegacyBuild(7L, 0L, -1L), "first legacy mesh revision must submit");
      require(!RtDynamicBlasCache.shouldSubmitLegacyBuild(7L, 7L, -1L), "active legacy revision must suppress duplicate builds");
      require(!RtDynamicBlasCache.shouldSubmitLegacyBuild(8L, 7L, 9L), "newer pending legacy build must suppress an older candidate");
      require(RtDynamicBlasCache.shouldSubmitLegacyBuild(10L, 7L, 9L), "newest legacy geometry revision must replace an older pending build");
      expectFailure(() -> RtDynamicBlasCache.shouldSubmitLegacyBuild(0L, 0L, -1L));
      expectFailure(() -> RtDynamicBlasCache.shouldSubmitLegacyBuild(1L, 0L, -2L));
   }

   private static void separatesDynamicMaterialUploadsFromTlasRefits() {
      require(!RtDynamicBlasCache.advancesTlasRevision(false, false), "light, tint, texture, and face-material changes must not rebuild or refit the TLAS");
      require(RtDynamicBlasCache.advancesMaterialRevision(true), "dynamic material changes must retain their independent upload revision");
      require(RtDynamicBlasCache.advancesTlasRevision(false, true), "object-to-world transform changes must still refit the TLAS");
      require(RtDynamicBlasCache.advancesTlasRevision(true, false), "instance add/remove or asset replacement must still rebuild the TLAS input");
      require(!RtDynamicBlasCache.advancesMaterialRevision(false), "an unchanged material payload must not submit an upload");
   }

   private static void keepsTlasUpdateEligibleForTransformOnlyChanges() {
      RtAccelerationStructure.TlasInstance before = new RtAccelerationStructure.TlasInstance(4096L, AffineTransform.identity(), 7);
      RtAccelerationStructure.TlasInstance moved = new RtAccelerationStructure.TlasInstance(4096L, new DynamicMeshInstance.AffineTransform(1.0F, 0.0F, 0.0F, 12.0F, 0.0F, 1.0F, 0.0F, 4.0F, 0.0F, 0.0F, 1.0F, -3.0F), 7);
      int beforeTopology = RtWorldTlasCache.instanceTopologyHash(List.of(before));
      int movedTopology = RtWorldTlasCache.instanceTopologyHash(List.of(moved));
      require(beforeTopology == movedTopology, "transform-only updates must preserve TLAS acceleration-structure topology");
      require(RtWorldTlasCache.shouldUpdateWorldTlas(true, beforeTopology, movedTopology), "a stable BLAS address and custom index must use TLAS update mode");
      RtAccelerationStructure.TlasInstance rebuiltBlas = new RtAccelerationStructure.TlasInstance(8192L, moved.transform(), 7);
      int rebuiltTopology = RtWorldTlasCache.instanceTopologyHash(List.of(rebuiltBlas));
      require(rebuiltTopology == beforeTopology, "a replacement BLAS must preserve the same persistent TLAS instance slot");
      require(RtWorldTlasCache.shouldUpdateWorldTlas(true, beforeTopology, rebuiltTopology), "a replacement BLAS address with a stable slot must use TLAS update mode");
      require(!RtWorldTlasCache.shouldUpdateWorldTlas(false, beforeTopology, movedTopology), "TLAS update mode requires a bound source TLAS");
      RtDynamicResidencyState backlog = new RtDynamicResidencyState(9, 4, 7, 2);
      require(backlog.hasBacklog(), "test setup must represent active asynchronous residency work");
      require(RtDynamicTlasCache.shouldSubmitSnapshot(false, 12L, 11L, 49, 49), "residency backlog must not participate in ready-instance transform submission");
      require(!RtDynamicTlasCache.shouldSubmitSnapshot(true, 12L, 11L, 49, 49), "one in-flight TLAS submission must remain the only scheduler serialization barrier");
      require(!RtDynamicTlasCache.shouldSubmitSnapshot(false, 12L, 12L, 49, 49), "an already-built snapshot must not resubmit");
      require(RtDynamicTlasCache.shouldSubmitSnapshot(false, 12L, 12L, 50, 49), "a changed BLAS/custom-index layout must submit even when the scene revision is unchanged");
   }

   private static void keepsTlasTopologyStableInsidePersistentCapacity() {
      require(RtWorldTlasCache.persistentInstanceCapacity(1, 0) == 1, "first persistent TLAS slot must allocate one physical instance");
      require(RtWorldTlasCache.persistentInstanceCapacity(3, 1) == 4, "persistent TLAS capacity must grow geometrically");
      require(RtWorldTlasCache.persistentInstanceCapacity(2, 4) == 4, "persistent TLAS capacity must not shrink when active instances leave");
      RtAccelerationStructure.TlasInstance active = new RtAccelerationStructure.TlasInstance(4096L, AffineTransform.identity(), 7);
      RtAccelerationStructure.TlasInstance inactive = TlasInstance.inactive(8192L);
      require(inactive.visibilityMask() == 0 && inactive.blasDeviceAddress() == 8192L, "inactive TLAS slots must keep a valid BLAS address while rejecting every ray mask");
      int sparseTopology = RtWorldTlasCache.instanceTopologyHash(List.of(active, inactive, inactive, inactive));
      RtAccelerationStructure.TlasInstance replacement = new RtAccelerationStructure.TlasInstance(12288L, AffineTransform.identity(), 19);
      int denserTopology = RtWorldTlasCache.instanceTopologyHash(List.of(active, replacement, inactive, inactive));
      require(sparseTopology == denserTopology, "active count, BLAS address, material index, transform and mask changes inside capacity must use UPDATE");
      require(RtWorldTlasCache.shouldUpdateWorldTlas(true, sparseTopology, denserTopology), "a persistent physical slot table must remain update eligible");
      expectFailure(() -> RtWorldTlasCache.persistentInstanceCapacity(0, 0));
   }

   private static void invalidatesActiveViewOnlyForTopologyOwnerGenerations() {
      require(!RtSectionActiveViewCache.topologyInputsChanged(7L, 7L, 11L, 11L, 13L, 13L), "stable resident, BLAS and source-geometry owners must reuse one ActiveView publication");
      require(RtSectionActiveViewCache.topologyInputsChanged(7L, 8L, 11L, 11L, 13L, 13L), "resident membership changes must invalidate ActiveView topology");
      require(RtSectionActiveViewCache.topologyInputsChanged(7L, 7L, 11L, 12L, 13L, 13L), "completed BLAS membership changes must invalidate ActiveView topology");
      require(RtSectionActiveViewCache.topologyInputsChanged(7L, 7L, 11L, 11L, 13L, 14L), "proxy geometry publication changes must invalidate ActiveView topology");
      require(RtSectionActiveViewPolicy.refresh(17L, 17L, 23L, 24L, false) == Refresh.MATERIAL_ONLY, "material generations must refresh material ownership without rebuilding ActiveView topology");
   }

   private static void keepsMaterialLayoutStableForTransformOnlyChanges() {
      RtAccelerationStructure.TlasInstance first = new RtAccelerationStructure.TlasInstance(4096L, AffineTransform.identity(), 7);
      RtAccelerationStructure.TlasInstance moved = new RtAccelerationStructure.TlasInstance(4096L, new DynamicMeshInstance.AffineTransform(1.0F, 0.0F, 0.0F, 12.0F, 0.0F, 1.0F, 0.0F, 4.0F, 0.0F, 0.0F, 1.0F, -3.0F), 7);
      RtAccelerationStructure.TlasInstance second = new RtAccelerationStructure.TlasInstance(8192L, AffineTransform.identity(), 11);
      int stableLayout = RtWorldTlasCache.instanceLayoutHash(List.of(first, second));
      require(stableLayout == RtWorldTlasCache.instanceLayoutHash(List.of(moved, second)), "transform-only animation must not invalidate the material layout");
      require(stableLayout != RtWorldTlasCache.instanceLayoutHash(List.of(second, first)), "instance reordering must invalidate the material layout");
      require(stableLayout != RtWorldTlasCache.instanceLayoutHash(List.of(new RtAccelerationStructure.TlasInstance(12288L, first.transform(), first.customIndex()), second)), "BLAS identity changes must invalidate the material layout");
      require(stableLayout != RtWorldTlasCache.instanceLayoutHash(List.of(new RtAccelerationStructure.TlasInstance(first.blasDeviceAddress(), first.transform(), 13), second)), "custom-index changes must invalidate the material layout");
   }

   private static void composesDynamicMaterialsAgainstBoundTerrainPrefix() {
      RtSceneMaterialTable.SectionMaterial terrainA = materialSection(3);
      RtSceneMaterialTable.SectionMaterial terrainB = materialSection(11);
      RtSceneMaterialTable.SectionMaterial staleDynamic = materialSection(19);
      RtSceneMaterialTable.SectionMaterial nextDynamic = materialSection(31);
      RtSceneMaterialTable.Snapshot boundComposite = Snapshot.fromMaterialSlots(List.of(terrainA, terrainB, staleDynamic), new int[]{0, 4, 8}, 9, 500L, 4097);
      int dynamicLayoutHash = 51966;
      int combinedLayoutHash = RtWorldTlasCache.combinedSplitLayoutHash(2, dynamicLayoutHash);
      RtSceneMaterialTable.Snapshot terrainPrefix = boundComposite.prefix(2, 500L, combinedLayoutHash);
      RtSceneMaterialTable.Snapshot dynamicSuffix = Snapshot.fromMaterialSlots(List.of(nextDynamic), new int[]{0}, nextDynamic.faceCount(), 501L, dynamicLayoutHash);
      RtSceneMaterialTable.Snapshot recomposed = Snapshot.compose(terrainPrefix, dynamicSuffix, 1001L, combinedLayoutHash);
      require(terrainPrefix.sectionMaterials().equals(List.of(terrainA, terrainB)), "split dynamic publication must preserve the descriptor-visible terrain prefix exactly");
      require(terrainPrefix.faceCount() == 5, "terrain prefix must retain sparse face ranges without leaking the stale dynamic suffix");
      require(recomposed.sectionMaterials().equals(List.of(terrainA, terrainB, nextDynamic)), "split dynamic publication must replace only the dynamic material suffix");
      require(recomposed.sectionFirstFaces()[2] == terrainPrefix.faceCount(), "dynamic suffix must begin after the complete sparse terrain face capacity");
      require(recomposed.instanceLayoutHash() == combinedLayoutHash, "split material generation must carry the matching terrain/dynamic layout hash");
      require(combinedLayoutHash != RtWorldTlasCache.combinedSplitLayoutHash(2, dynamicLayoutHash + 1), "dynamic TLAS layout changes must invalidate the split material generation");
      require(combinedLayoutHash != RtWorldTlasCache.combinedSplitLayoutHash(3, dynamicLayoutHash), "terrain namespace changes must invalidate the split material generation");
      expectFailure(() -> boundComposite.prefix(4, 500L, combinedLayoutHash));
   }

   private static RtSceneMaterialTable.SectionMaterial materialSection(int marker) {
      int[] faceRecord = new int[12];
      faceRecord[0] = marker;
      return new RtSceneMaterialTable.SectionMaterial(faceRecord);
   }

   private static void keepsSurvivingPersistentSlotsStableAcrossRemovalAndReuse() {
      RtDynamicBlasCache.PersistentSlots<String> slots = new RtDynamicBlasCache.PersistentSlots<>();
      int firstSlot = slots.allocate(100L);
      slots.set(firstSlot, "first");
      int survivorSlot = slots.allocate(200L);
      slots.set(survivorSlot, "survivor");
      slots.remove(100L);
      require(slots.slotFor(200L) == survivorSlot, "removing an earlier owner must not move a surviving custom-index slot");
      int reusedSlot = slots.allocate(300L);
      slots.set(reusedSlot, "replacement");
      require(reusedSlot == firstSlot, "a vacant physical slot must be reused before growing capacity");
      require(slots.slotFor(200L) == survivorSlot, "reusing a vacancy must not alter an unrelated surviving slot");
      slots.remove(200L);
      slots.remove(300L);
      slots.trimTrailingVacancies();
      require(slots.capacity() == 0 && slots.activeCount() == 0, "only trailing vacant slots may be compacted after every owner leaves");
      RtDynamicBlasCache.PersistentSlots<String> exact = new RtDynamicBlasCache.PersistentSlots<>();
      exact.allocateAt(3, 400L);
      exact.set(3, "exact");
      require(exact.capacity() == 4 && exact.slotFor(400L) == 3, "collector physical slot must pass through native without remapping");
      expectFailure(() -> exact.allocateAt(3, 401L));
      expectFailure(() -> exact.removeAt(3, 999L));
      exact.removeAt(3, 400L);
      require(exact.activeCount() == 0, "exact slot removal must retire the matching primitive identity once");
   }

   private static void rebasesFullBootstrapOntoAuthoritativeCollectorSlots() {
      DynamicMeshAsset asset = meshAsset(4000L, 1L, 0.0F);
      List<DynamicRenderScene.DynamicModelInstance> fullScene = new ArrayList<>(400);

      for(int index = 0; index < 400; ++index) {
         fullScene.add(new DynamicRenderScene.DynamicModelInstance(10000L + (long)index, PrimitiveKind.ENTITY, asset, AffineTransform.identity(), List.of(faceMaterial(-1, 0)), 15728880, "authoritative-" + index));
      }

      RtDynamicBlasCache.PersistentSlots<DynamicRenderScene.DynamicModelInstance> bootstrap = new RtDynamicBlasCache.PersistentSlots<>();

      for(int index = 0; index < 16; ++index) {
         DynamicRenderScene.DynamicModelInstance instance = (DynamicRenderScene.DynamicModelInstance)fullScene.get(399 - index);
         int slot = bootstrap.allocate(instance.id());
         bootstrap.set(slot, instance);
      }

      int[] slots = new int[401];
      byte[] masks = new byte[401];
      DynamicRenderScene.DynamicModelInstance[] publications = new DynamicRenderScene.DynamicModelInstance[401];
      float[] transforms = new float[4812];

      for(int slot = 0; slot < 400; ++slot) {
         slots[slot] = slot;
         masks[slot] = (byte)(61 | (slot == 7 ? 2 : 0));
         publications[slot] = (DynamicRenderScene.DynamicModelInstance)fullScene.get(slot);

         for(int component = 0; component < 12; ++component) {
            transforms[slot * 12 + component] = AffineTransform.identity().value(component);
         }
      }

      slots[400] = 400;
      masks[400] = 6;
      int[] membershipSlots = new int[fullScene.size()];
      DynamicRenderScene.DynamicModelInstance[] membershipInstances = new DynamicRenderScene.DynamicModelInstance[fullScene.size()];

      for(int slot = 0; slot < fullScene.size(); ++slot) {
         membershipSlots[slot] = slot;
         membershipInstances[slot] = (DynamicRenderScene.DynamicModelInstance)fullScene.get(slot);
      }

      DynamicRenderScene.DynamicModelSlotSnapshot membershipSnapshot = new DynamicRenderScene.DynamicModelSlotSnapshot(20L, 401, membershipSlots, membershipInstances);
      DynamicRenderScene.DynamicModelFrameDelta authoritative = new DynamicRenderScene.DynamicModelFrameDelta(20L, 20L, 20L, 20L, 20L, 401, 400, membershipSnapshot, slots, masks, publications, transforms);
      RtDynamicBlasCache.PersistentSlots<DynamicRenderScene.DynamicModelInstance> rebased = RtDynamicBlasCache.rebaseAuthoritativeModelSlots(bootstrap, authoritative);
      require(bootstrap.activeCount() == 16, "authoritative rebase must not mutate the bootstrap table before publication");
      require(rebased.capacity() == 401 && rebased.activeCount() == fullScene.size(), "authoritative rebase must preserve physical holes and publish every active collector slot");

      for(int slot = 0; slot < fullScene.size(); ++slot) {
         require(((DynamicRenderScene.DynamicModelInstance)rebased.get(slot)).equals(fullScene.get(slot)) && rebased.slotFor(((DynamicRenderScene.DynamicModelInstance)fullScene.get(slot)).id()) == slot, "native dynamic slot table must equal the collector full scene at slot " + slot);
      }

      require(rebased.get(400) == null, "a removal-only authoritative update must remain an inactive physical hole");
      rebased.allocateAt(0, ((DynamicRenderScene.DynamicModelInstance)fullScene.getFirst()).id());
      require(((DynamicRenderScene.DynamicModelInstance)rebased.get(0)).equals(fullScene.getFirst()), "replayed exact (id, slot) allocation must be idempotent");
      expectFailure(() -> rebased.allocateAt(400, ((DynamicRenderScene.DynamicModelInstance)fullScene.getFirst()).id()));
      expectFailure(() -> rebased.allocateAt(0, 99999L));
      int[] transformSlots = new int[]{3, 397};
      byte[] transformMasks = new byte[]{8, 8};
      float[] transformOnly = new float[transformSlots.length * 12];

      for(int update = 0; update < transformSlots.length; ++update) {
         for(int component = 0; component < 12; ++component) {
            transformOnly[update * 12 + component] = ((DynamicRenderScene.DynamicModelInstance)fullScene.get(transformSlots[update])).transform().value(component);
         }

         transformOnly[update * 12 + 3] += 100.0F + (float)update;
      }

      DynamicRenderScene.DynamicModelFrameDelta lateConsumerDelta = new DynamicRenderScene.DynamicModelFrameDelta(20L, 20L, 21L, 20L, 20L, 401, 400, membershipSnapshot, transformSlots, transformMasks, new DynamicRenderScene.DynamicModelInstance[transformSlots.length], transformOnly);
      RtDynamicBlasCache.PersistentSlots<DynamicRenderScene.DynamicModelInstance> lateRebase = RtDynamicBlasCache.rebaseAuthoritativeModelSlots(bootstrap, lateConsumerDelta);
      RtDynamicTransformSlots lateTransforms = RtDynamicBlasCache.rebaseAuthoritativeTransformSlots(lateConsumerDelta);
      require(lateRebase.activeCount() == 400 && ((DynamicRenderScene.DynamicModelInstance)lateRebase.get(397)).equals(fullScene.get(397)), "a late native consumer must rebase from retained membership, not the lossy dirty delta");
      require(Float.compare(lateTransforms.value(3, 3), transformOnly[3]) == 0 && Float.compare(lateTransforms.value(397, 3), transformOnly[15]) == 0, "a late native consumer must rebase transforms from the latest authoritative lane");
      require(Float.compare(((DynamicRenderScene.DynamicModelInstance)lateRebase.get(3)).transformValue(3), lateTransforms.value(3, 3)) != 0, "membership metadata must not masquerade as authoritative animation state");
   }

   private static void keepsDynamicMaterialCustomIndicesStableAcrossTerrainGrowth() {
      int firstDynamicSlot = RtDynamicBlasCache.dynamicMaterialCustomIndex(0);
      int laterDynamicSlot = RtDynamicBlasCache.dynamicMaterialCustomIndex(417);
      require(firstDynamicSlot == 8388608, "the first dynamic material must begin in the marked stable namespace");
      require((laterDynamicSlot & 8388608) != 0, "every dynamic material custom index must carry the namespace marker");
      require((laterDynamicSlot & 8388607) == 417, "the marker must preserve the persistent local material slot");
      require(laterDynamicSlot < 16777216, "dynamic custom indices must remain inside Vulkan's 24-bit instance field");
      int beforeTerrainGrowth = RtDynamicBlasCache.dynamicMaterialCustomIndex(417);
      int afterTerrainGrowth = RtDynamicBlasCache.dynamicMaterialCustomIndex(417);
      require(beforeTerrainGrowth == afterTerrainGrowth, "terrain material growth must never rewrite a live dynamic TLAS custom index");
      expectFailure(() -> RtDynamicBlasCache.dynamicMaterialCustomIndex(-1));
      expectFailure(() -> RtDynamicBlasCache.dynamicMaterialCustomIndex(8388608));
   }

   private static void retainsPresentedFrontInPhysicalAdmissionUntilAcknowledged() {
      SectionKey earlierCommitted = new SectionKey(-1, 4, 0);
      SectionKey committedOnly = new SectionKey(0, 4, 0);
      SectionKey sharedMember = new SectionKey(1, 4, 0);
      SectionKey successorOnly = new SectionKey(2, 4, 0);
      RendererViewState logicalSuccessor = new RendererViewState(152L, true, true, 2, 4, 0, List.of(sharedMember, successorOnly));
      require(RtSectionActiveViewPolicy.physicalAdmissionView(logicalSuccessor, Set.of()) == logicalSuccessor, "an empty presentation generation must preserve the logical view identity");
      RendererViewState beforePresentationAck = RtSectionActiveViewPolicy.physicalAdmissionView(logicalSuccessor, Set.of(committedOnly, sharedMember));
      require(beforePresentationAck == logicalSuccessor, "physical admission must not union the GPU-owned front into the fixed successor slot budget");
      require(beforePresentationAck.revision() == logicalSuccessor.revision() && beforePresentationAck.cameraSectionX() == logicalSuccessor.cameraSectionX(), "physical admission must preserve logical successor generation and camera priority");
      RendererViewState largerRetainedFront = RtSectionActiveViewPolicy.physicalAdmissionView(logicalSuccessor, Set.of(earlierCommitted, committedOnly, sharedMember));
      require(largerRetainedFront == logicalSuccessor, "a larger GPU-owned front must not expand active BLAS residency beyond the logical successor");
      require(RtSectionActiveViewPolicy.physicalAdmissionView(logicalSuccessor, Set.of(sharedMember, successorOnly)) == logicalSuccessor, "presentation acknowledgement must immediately retire committed-only admission");
      expectFailure(() -> RtSectionActiveViewPolicy.physicalAdmissionView(logicalSuccessor, null));
   }

   private static void serializesDynamicFoilModeIntoFaceMetadata() {
      DynamicMeshAsset asset = meshAsset(40L, 1L, 0.0F);
      DynamicMeshInstance instance = new DynamicMeshInstance(asset, AffineTransform.identity(), List.of(faceMaterial(-1, 2)));
      RtSceneMaterialTable.SectionMaterial material = RtDynamicTriangleMesh.materialFor(new DynamicRenderScene.DynamicModelInstance(400L, PrimitiveKind.DROPPED_ITEM, asset, instance.transform(), instance.faceMaterials(), 15728880, "foil-contract"));
      int metadata = material.faceRecords()[1];
      int materialFlags = metadata >>> 24 & 255;
      require((materialFlags >>> 3 & 3) == 2, "foil mode must survive into the RT face record consumed by closest-hit shading");
   }

   private static void serializesReferenceOverlayIntoVertexLighting() {
      int lighting = 8355904;
      int noOverlay = RtDynamicTriangleMesh.packDynamicOverlay(lighting, 655360);
      require((noOverlay & 16777215) == lighting, "overlay packing must preserve block, sky, and shade lighting bytes");
      require(noOverlay >>> 24 == 160, "no-overlay state must retain reference's exact 16x16 lookup coordinate");
      int hurtOverlay = RtDynamicTriangleMesh.packDynamicOverlay(lighting, 196608);
      require(hurtOverlay >>> 24 == 48, "hurt overlay must retain reference's red-overlay row");
      int whiteOverlay = RtDynamicTriangleMesh.packDynamicOverlay(lighting, 655375);
      require(whiteOverlay >>> 24 == 175, "white overlay progress must retain both four-bit coordinates");
      expectFailure(() -> RtDynamicTriangleMesh.packDynamicOverlay(16777216, 0));
      expectFailure(() -> RtDynamicTriangleMesh.packDynamicOverlay(lighting, 16));
   }

   private static void serializesDynamicBlendModeIntoFaceIdentity() {
      int outlineRgba8 = 2141965807;

      for(RtBlendMode blendMode : RtBlendMode.values()) {
         int packed = RtDynamicTriangleMesh.packDynamicFaceIdentity(outlineRgba8, blendMode);
         require((packed & 16777215) == (outlineRgba8 & 16777215), "blend encoding must preserve the dynamic outline RGB payload");
         require(RtDynamicTriangleMesh.unpackDynamicBlendMode(packed) == blendMode, "every renderer-owned blend mode must round-trip through face metadata");
      }

      expectFailure(() -> RtDynamicTriangleMesh.unpackDynamicBlendMode(16777216));
      expectFailure(() -> RtBlendMode.fromFaceCode(15));
   }

   private static void serializesReferenceTintAlphaIntoVertexLighting() {
      int lighting = 8355904;
      int packed = RtDynamicTriangleMesh.packDynamicTintAlpha(lighting, 1007755827);
      require((packed & 16777215) == lighting, "tint-alpha packing must preserve block, sky, and shade lighting bytes");
      require(packed >>> 24 == 60, "dynamic tint alpha must retain all eight reference color-alpha bits");
      int outline = RtDynamicTriangleMesh.packDynamicOutlineAlpha(lighting, 1514418978);
      require(outline >>> 24 == 90, "dynamic outline alpha must retain all eight reference color-alpha bits");
      expectFailure(() -> RtDynamicTriangleMesh.packDynamicTintAlpha(16777216, 0));
      expectFailure(() -> RtDynamicTriangleMesh.packDynamicOutlineAlpha(16777216, 0));
   }

   private static void serializesSameSurfaceCrumblingDecal() {
      DynamicMeshInstance.SurfaceDecal decal = SurfaceDecal.repeating(42300, -0.25F, 0.125F, 0.75F, 0.125F, 0.75F, 1.125F, -0.25F, 1.125F);
      int tintWord = RtDynamicTriangleMesh.packDynamicDecalWord(decal, 0, 2130706432);
      int outlineWord = RtDynamicTriangleMesh.packDynamicDecalWord(decal, 1, 1509949440);
      int metadataWord = RtDynamicTriangleMesh.packDynamicDecalWord(decal, 2, (decal.textureId() >>> 12 & 15) << 24 | decal.fractionalBits() << 28);
      require(tintWord >>> 24 == 127, "decal encoding must preserve dynamic tint alpha");
      require(outlineWord >>> 24 == 90, "decal encoding must preserve dynamic outline alpha");
      int decodedTextureId = tintWord >>> 20 & 15 | (outlineWord >>> 20 & 15) << 4 | (metadataWord >>> 20 & 15) << 8 | (metadataWord >>> 24 & 15) << 12;
      require(decodedTextureId == decal.textureId(), "same-surface decal texture identity must round-trip through dynamic face records");
      require(metadataWord >>> 28 == decal.fractionalBits(), "same-surface decal precision must round-trip through dynamic face records");
      expectFailure(() -> SurfaceDecal.repeating(65536, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F));
   }

   private static void validatesDynamicTlasPhysicalSlotAccounting() {
      RtAccelerationStructure.TlasInstance inactive = TlasInstance.inactive(4096L);
      RtSceneMaterialTable.SectionMaterial tombstone = RtSceneMaterialTable.tombstoneSectionMaterial();
      RtDynamicInstanceSnapshot input = new RtDynamicInstanceSnapshot(1L, 1L, 0L, 0L, 1L, 1L, DynamicRenderScene.empty(), List.of(inactive), List.of(tombstone), new int[]{0}, 0, 0L, 0L, 0L);
      RtDynamicResidencyState residency = new RtDynamicResidencyState(1, 1, 1, 0);
      require(residency.hasBacklog(), "asset residency backlog must remain independently observable");
      require(input.instances().size() == 1 && input.activeInstanceCount() == 0, "inactive physical slots must remain represented without inflating active-instance telemetry");
      expectFailure(() -> new RtDynamicInstanceSnapshot(1L, 1L, 0L, 0L, 1L, 1L, DynamicRenderScene.empty(), List.of(inactive), List.of(tombstone), new int[]{0}, 2, 0L, 0L, 0L));
      expectFailure(() -> new RtDynamicResidencyState(-1, 0, 0, 0));
   }

   private static DynamicRenderScene.DynamicPrimitive primitive(long id, DynamicMeshInstance instance, int packedLight) {
      DynamicMeshInstance.AffineTransform transform = instance.transform();
      return new DynamicRenderScene.DynamicPrimitive(id, PrimitiveKind.DROPPED_ITEM, PrimitiveGeometryKind.MODEL, (double)transform.translateX(), (double)transform.translateY(), (double)transform.translateZ(), 0.0F, 0.0F, 0.0F, 0.5F, 0.5F, 0.5F, 0, 0, packedLight, true, "dynamic-mesh-contract", instance);
   }

   private static DynamicMeshAsset meshAsset(long id, long revision, float xOffset) {
      return new DynamicMeshAsset(id, revision, quadPositions(xOffset), quadIndices(), List.of(tintedFace()));
   }

   private static float[] quadPositions(float xOffset) {
      return new float[]{-0.5F + xOffset, 0.0F, -0.5F, 0.5F + xOffset, 0.0F, -0.5F, 0.5F + xOffset, 0.0F, 0.5F, -0.5F + xOffset, 0.0F, 0.5F};
   }

   private static int[] quadIndices() {
      return new int[]{0, 1, 2, 0, 2, 3};
   }

   private static DynamicMeshAsset.Face tintedFace() {
      return new DynamicMeshAsset.Face(FaceDirection.POSITIVE_Y.ordinal(), true);
   }

   private static DynamicMeshInstance.FaceMaterial faceMaterial(int tintRgba8, int foilMode) {
      return faceMaterial(tintRgba8, foilMode, false);
   }

   private static DynamicMeshInstance.FaceMaterial faceMaterial(int tintRgba8, int foilMode, boolean alwaysOnTop) {
      return new DynamicMeshInstance.FaceMaterial(7, 0, 1, 2, 3, tintRgba8, true, false, RtBlendMode.OPAQUE, 0, foilMode, 0, false, alwaysOnTop, 655360);
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException ex) {
         return ex;
      }

      throw new AssertionError("expected failure");
   }

   private static void requireNear(float actual, float expected, String message) {
      if (Math.abs(actual - expected) > 1.0E-6F) {
         throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
