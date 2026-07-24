package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Accepted/active generation separation, backpressure, and retirement contract gate. */
public final class VulkanGpuSceneSelfTest {
    private VulkanGpuSceneSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        acceptedGenerationRemainsInactiveUntilFenceCompletion();
        inFlightGenerationRejectsASecondGenerationWithoutPublishingIt();
        System.out.println("VulkanGpuSceneSelfTest passed");
    }

    private static void acceptedGenerationRemainsInactiveUntilFenceCompletion() throws Exception {
        FakeTransferQueue transfers = new FakeTransferQueue();
        VulkanSceneResidency residency = new VulkanSceneResidency();
        try (VulkanGpuScene scene = new VulkanGpuScene(transfers)) {
            VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, 10L));
            VulkanGpuScene.Admission admission = scene.submit(initial.changeSet(), 0L);
            require(admission.acceptedRevision() == 0L && !admission.active(),
                    "submission fence was confused with scene acceptance");
            residency.commit(initial);
            VulkanGpuScene.Snapshot accepted = scene.snapshot();
            require(accepted.acceptedRevision() == 0L && accepted.activeRevision() == -1L
                            && accepted.pendingRevision() == 0L,
                    "accepted generation was exposed before native completion");

            transfers.completePending();
            VulkanGpuScene.Snapshot active = scene.poll(0L);
            require(active.activeRevision() == 0L && active.pendingRevision() == -1L,
                    "completed native generation was not activated");
            require(scene.requireBuffer(VulkanGpuSceneUploadPlanner.Target.TEXTURE_RECORDS, 0L)
                            .capacityBytes() == 4_096L,
                    "active buffer binding was unavailable");
            require(transfers.latestReleasedEpoch == 0L,
                    "descriptor completion epoch did not reach native retirement");
        }
    }

    private static void inFlightGenerationRejectsASecondGenerationWithoutPublishingIt() throws Exception {
        FakeTransferQueue transfers = new FakeTransferQueue();
        VulkanSceneResidency residency = new VulkanSceneResidency();
        try (VulkanGpuScene scene = new VulkanGpuScene(transfers)) {
            VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, 10L));
            scene.submit(initial.changeSet(), 0L);
            residency.commit(initial);

            VulkanSceneResidency.PreparedUpdate successor = residency.prepare(transaction(1L, false, 10L));
            boolean rejected = false;
            try {
                scene.submit(successor.changeSet(), 1L);
            } catch (VulkanGpuScene.BusyException expected) {
                rejected = true;
            }
            require(rejected, "in-flight GPUScene generation did not apply backpressure");
            require(scene.snapshot().acceptedRevision() == 0L && transfers.submissions == 1,
                    "rejected generation changed CPU or native authority");

            transfers.completePending();
            scene.poll(0L);
            VulkanGpuScene.Admission accepted = scene.submit(successor.changeSet(), 1L);
            require(accepted.acceptedRevision() == 1L && transfers.submissions == 2,
                    "same prepared generation could not be retried after backpressure cleared");
        }
    }

    private static SceneTransaction transaction(long revision, boolean reset, long textureId) {
        TextureAsset texture = new TextureAsset(
                textureId, 1, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, new byte[]{1, 2, 3, 4}
        );
        return new SceneTransaction(
                revision, reset,
                new SceneTransaction.Upserts(List.of(texture), List.of(), List.of(), List.of(), List.of()),
                SceneTransaction.Removals.empty()
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeTransferQueue implements VulkanGpuSceneTransferQueue {
        private final EnumMap<VulkanGpuSceneUploadPlanner.Target, BufferBinding> buffers =
                new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
        private FakeTicket pending;
        private long activeRevision = -1L;
        private long latestReleasedEpoch = -1L;
        private int submissions;
        private boolean closed;

        @Override
        public TransferTicket submit(VulkanGpuSceneUploadPlanner.Plan uploadPlan) {
            require(!closed && pending == null, "fake transfer queue received an invalid submission");
            VulkanGpuSceneUploadPlanner.Plan plan = Objects.requireNonNull(uploadPlan, "uploadPlan");
            for (VulkanGpuSceneUploadPlanner.Chunk chunk : plan.chunks()) {
                buffers.putIfAbsent(chunk.target(), new BufferBinding(
                        chunk.target().ordinal() + 1L,
                        0x1000L + chunk.target().ordinal() * 0x1000L,
                        4_096L
                ));
            }
            pending = new FakeTicket(plan.revision());
            submissions++;
            return pending;
        }

        @Override
        public boolean pollAndActivate(TransferTicket transfer, long retireAfterEpoch) {
            FakeTicket ticket = requireTicket(transfer);
            if (!ticket.complete) return false;
            ticket.activated = true;
            activeRevision = ticket.revision;
            pending = null;
            return true;
        }

        @Override
        public void waitAndActivate(TransferTicket transfer, long retireAfterEpoch) {
            FakeTicket ticket = requireTicket(transfer);
            ticket.complete = true;
            pollAndActivate(ticket, retireAfterEpoch);
        }

        @Override
        public void releaseRetiredThrough(long completedDescriptorEpoch) {
            latestReleasedEpoch = completedDescriptorEpoch;
        }

        @Override
        public BufferBinding buffer(VulkanGpuSceneUploadPlanner.Target target) {
            return buffers.get(target);
        }

        @Override
        public TransferState state() {
            return new TransferState(activeRevision, buffers.size(), buffers.size() * 4_096L,
                    pending != null, 0, 0L);
        }

        @Override
        public void close() {
            closed = true;
            pending = null;
            buffers.clear();
        }

        private void completePending() {
            require(pending != null, "fake transfer queue had no pending generation");
            pending.complete = true;
        }

        private FakeTicket requireTicket(TransferTicket candidate) {
            if (!(candidate instanceof FakeTicket ticket) || ticket != pending || ticket.activated) {
                throw new IllegalStateException("fake transfer ticket is stale");
            }
            return ticket;
        }
    }

    private static final class FakeTicket implements VulkanGpuSceneTransferQueue.TransferTicket {
        private final long revision;
        private boolean complete;
        private boolean activated;

        private FakeTicket(long revision) {
            this.revision = revision;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public boolean activated() {
            return activated;
        }
    }
}
