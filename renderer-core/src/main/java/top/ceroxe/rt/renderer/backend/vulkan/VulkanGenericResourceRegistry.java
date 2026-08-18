package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.BufferResource;
import top.ceroxe.rt.renderer.api.BufferUsage;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceMutationKey;
import top.ceroxe.rt.renderer.api.ResourceResidencyEvidence;
import top.ceroxe.rt.renderer.api.ResourceTransactionEvidence;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Owns generic Vulkan buffer and texture generations independently from the retained GPUScene registry.
 *
 * <p>The executable subset deliberately stops at transfer operations. Views, descriptors, shader
 * sampling and attachment ownership remain capability-gated until the backend can preserve their
 * full semantic contract instead of accepting descriptors it cannot consume.</p>
 */
final class VulkanGenericResourceRegistry implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final VulkanGenericSamplerCache samplers;
    private final Map<ResourceGenerationKey, BufferRecord> buffers = new LinkedHashMap<>();
    private final Map<ResourceGenerationKey, TextureRecord> textures = new LinkedHashMap<>();
    private final Map<ResourceGenerationKey, ResourceResidencyEvidence> evidence = new HashMap<>();
    /*
     * A residency outcome describes the most recently completed contents.  It is deliberately
     * not sufficient to represent an earlier recorded write: an application may ask to mutate
     * the same allocation again while that write is still on the GPU.  Preserve each submission
     * token until its fence completes so admission and retirement remain exact.
     */
    private final Map<ResourceGenerationKey, ResourceMutationKey> inFlightMutations = new HashMap<>();
    /* Composition reads use a frame-sequence domain, so they have their own exact pin ledger. */
    private final Map<ResourceGenerationKey, Integer> compositionPinCounts = new HashMap<>();
    private final Map<RenderResourceId, Long> highestVersionById = new HashMap<>();
    private final Map<RenderResourceId, ResourceKind> resourceKinds = new HashMap<>();
    private long latestTransactionRevision = -1L;
    private boolean closed;

    VulkanGenericResourceRegistry(VulkanDeviceRuntime device) {
        this.device = Objects.requireNonNull(device, "device");
        this.samplers = new VulkanGenericSamplerCache(
                device.device(), device.samplerAnisotropyEnabled(), device.maxSamplerAnisotropy()
        );
    }

    ResourceTransactionEvidence apply(RenderResourceTransaction transaction, long completedCommandSequence) {
        requireOpen();
        RenderResourceTransaction checked = Objects.requireNonNull(transaction, "transaction");
        if (checked.revision() <= latestTransactionRevision) {
            return rejected(checked, "resource transaction revision must strictly advance: latest="
                    + latestTransactionRevision + ", supplied=" + checked.revision());
        }
        if (!checked.hasChanges()) {
            return rejected(checked, "resource transaction must publish or retire at least one resource identity");
        }
        try {
            validateUpserts(checked);
            List<BufferRecord> retirement = resolveRetirements(checked, completedCommandSequence);
            List<TextureRecord> textureRetirement = resolveTextureRetirements(checked, completedCommandSequence);
            ArrayList<BufferRecord> created = new ArrayList<>();
            ArrayList<TextureRecord> createdTextures = new ArrayList<>();
            try {
                for (BufferResource buffer : checked.buffers()) {
                    created.add(create(buffer, checked.revision()));
                }
                for (TextureResource texture : checked.textures()) {
                    createdTextures.add(create(texture, checked.revision()));
                }
            } catch (RuntimeException | LinkageError failure) {
                closeAll(created);
                closeTextures(createdTextures);
                throw failure;
            }

            ArrayList<ResourceResidencyEvidence> changed = new ArrayList<>(
                    created.size() + createdTextures.size() + retirement.size() + textureRetirement.size());
            for (BufferRecord record : created) {
                buffers.put(record.generation(), record);
                highestVersionById.merge(record.generation().id(), record.generation().version().value(), Math::max);
                resourceKinds.put(record.generation().id(), ResourceKind.BUFFER);
                evidence.put(record.generation(), record.evidence());
                changed.add(record.evidence());
            }
            for (TextureRecord record : createdTextures) {
                textures.put(record.generation(), record);
                highestVersionById.merge(record.generation().id(), record.generation().version().value(), Math::max);
                resourceKinds.put(record.generation().id(), ResourceKind.TEXTURE);
                evidence.put(record.generation(), record.evidence());
                changed.add(record.evidence());
            }
            for (BufferRecord record : retirement) {
                if (record.evidence().outcome() == ResourceResidencyEvidence.Outcome.ACCEPTED) {
                    ResourceResidencyEvidence released = new ResourceResidencyEvidence(
                            record.generation(), ResourceResidencyEvidence.Outcome.RETIRED_UNUSED,
                            record.publicationRevision(), OptionalLong.empty(), OptionalLong.empty(),
                            "unused Vulkan buffer generation destroyed without fabricating GPU completion"
                    );
                    record.evidence().requireNext(released);
                    buffers.remove(record.generation());
                    evidence.put(record.generation(), released);
                    record.buffer().close();
                    changed.add(released);
                    continue;
                }
                ResourceResidencyEvidence pending = new ResourceResidencyEvidence(
                        record.generation(), ResourceResidencyEvidence.Outcome.RETIRE_PENDING,
                        record.publicationRevision(), record.evidence().submissionSequence(),
                        OptionalLong.of(completedCommandSequence), "all recorded users completed before retirement"
                );
                record.evidence().requireNext(pending);
                ResourceResidencyEvidence retired = new ResourceResidencyEvidence(
                        record.generation(), ResourceResidencyEvidence.Outcome.RETIRED,
                        record.publicationRevision(), pending.submissionSequence(), pending.lastConsumerSequence(),
                        "Vulkan buffer generation destroyed after proven completion"
                );
                pending.requireNext(retired);
                buffers.remove(record.generation());
                evidence.put(record.generation(), retired);
                record.buffer().close();
                changed.add(retired);
            }
            for (TextureRecord record : textureRetirement) {
                ResourceResidencyEvidence current = record.evidence();
                if (current.outcome() == ResourceResidencyEvidence.Outcome.ACCEPTED) {
                    ResourceResidencyEvidence released = new ResourceResidencyEvidence(
                            record.generation(), ResourceResidencyEvidence.Outcome.RETIRED_UNUSED,
                            record.publicationRevision(), OptionalLong.empty(), OptionalLong.empty(),
                            "unused Vulkan texture generation destroyed without fabricating GPU completion"
                    );
                    current.requireNext(released);
                    textures.remove(record.generation());
                    evidence.put(record.generation(), released);
                    record.close();
                    changed.add(released);
                    continue;
                }
                ResourceResidencyEvidence pending = new ResourceResidencyEvidence(
                        record.generation(), ResourceResidencyEvidence.Outcome.RETIRE_PENDING,
                        record.publicationRevision(), current.submissionSequence(), OptionalLong.of(completedCommandSequence),
                        "all recorded texture users completed before retirement"
                );
                current.requireNext(pending);
                ResourceResidencyEvidence retired = new ResourceResidencyEvidence(
                        record.generation(), ResourceResidencyEvidence.Outcome.RETIRED,
                        record.publicationRevision(), pending.submissionSequence(), pending.lastConsumerSequence(),
                        "Vulkan texture generation destroyed after proven completion"
                );
                pending.requireNext(retired);
                textures.remove(record.generation());
                evidence.put(record.generation(), retired);
                record.close();
                changed.add(retired);
            }
            latestTransactionRevision = checked.revision();
            return new ResourceTransactionEvidence(
                    checked.revision(), ResourceTransactionEvidence.Outcome.ACCEPTED, changed,
                    "generic Vulkan buffer resource transaction accepted"
            );
        } catch (RuntimeException failure) {
            if (failure instanceof RendererDeviceException) {
                throw failure;
            }
            return rejected(checked, failure.getMessage() == null ? "resource transaction rejected" : failure.getMessage());
        }
    }

    BufferRecord requireBuffer(BufferResource descriptor) {
        requireOpen();
        BufferResource checked = Objects.requireNonNull(descriptor, "descriptor");
        ResourceGenerationKey key = ResourceGenerationKey.of(checked);
        BufferRecord record = buffers.get(key);
        if (record == null || !sameDescriptor(record.descriptor(), checked)) {
            throw new IllegalArgumentException("buffer generation is not resident: " + key);
        }
        return record;
    }

    TextureRecord requireTexture(TextureResource descriptor) {
        requireOpen();
        TextureResource checked = Objects.requireNonNull(descriptor, "descriptor");
        ResourceGenerationKey key = ResourceGenerationKey.of(checked);
        TextureRecord record = textures.get(key);
        if (record == null || !sameDescriptor(record.descriptor(), checked)) {
            throw new IllegalArgumentException("texture generation is not resident: " + key);
        }
        return record;
    }

    long requireTextureView(top.ceroxe.rt.renderer.api.TextureView view) {
        top.ceroxe.rt.renderer.api.TextureView checked = Objects.requireNonNull(view, "view");
        TextureRecord record = requireTexture(checked.texture());
        requireReadable(record);
        return record.views().require(checked);
    }

    /**
     * Resolves a resident view after command-plan validation has already established its exact
     * visibility edge. Descriptor updates happen before command submission, so re-checking
     * {@code GPU_READY} here would incorrectly reject a legal same-command-buffer producer.
     */
    long requirePlannedTextureView(top.ceroxe.rt.renderer.api.TextureView view) {
        top.ceroxe.rt.renderer.api.TextureView checked = Objects.requireNonNull(view, "view");
        TextureRecord record = requireTexture(checked.texture());
        return record.views().require(checked);
    }

    VulkanGenericSamplerCache samplers() {
        requireOpen();
        return samplers;
    }

    VulkanGenericCompositionSource requireCompositionSource(ResourceMutationKey mutation) {
        requireOpen();
        ResourceMutationKey checked = Objects.requireNonNull(mutation, "mutation");
        TextureRecord record = textures.get(checked.generation());
        if (record == null) throw new IllegalArgumentException("composition source texture is not resident: " + checked.generation());
        if (record.evidence().outcome() != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("composition source is not GPU-ready: " + checked);
        }
        TextureResource descriptor = record.descriptor();
        if (descriptor.dimension() != top.ceroxe.rt.renderer.api.TextureDimension.TEXTURE_2D
                || descriptor.sampleCount() != 1 || descriptor.mipLevelCount() != 1
                || descriptor.arrayLayerCount() != 1) {
            throw new IllegalArgumentException("composition sources must be single-sample 2D full-resolution textures");
        }
        if (!descriptor.usage().contains(top.ceroxe.rt.renderer.api.TextureUsage.STORAGE_READ)
                && !descriptor.usage().contains(top.ceroxe.rt.renderer.api.TextureUsage.STORAGE_READ_WRITE)) {
            throw new IllegalArgumentException("composition source must declare storage-read usage");
        }
        if (descriptor.format() != top.ceroxe.rt.renderer.api.TextureFormat.RGBA8_UNORM
                && descriptor.format() != top.ceroxe.rt.renderer.api.TextureFormat.RGBA16_FLOAT) {
            throw new IllegalArgumentException("composition source format is not supported by the Vulkan composition shader");
        }
        top.ceroxe.rt.renderer.api.TextureView view = new top.ceroxe.rt.renderer.api.TextureView(
                descriptor,
                top.ceroxe.rt.renderer.api.TextureViewDimension.TEXTURE_2D,
                new top.ceroxe.rt.renderer.api.TextureSubresourceRange(
                        top.ceroxe.rt.renderer.api.TextureAspect.COLOR, 0, 1, 0, 1
                )
        );
        return new VulkanGenericCompositionSource(
                checked, descriptor.format(), descriptor.width(), descriptor.height(),
                record.image().image(), record.views().require(view),
                record.layouts().layout(top.ceroxe.rt.renderer.api.TextureAspect.COLOR, 0, 0)
        );
    }

    void markCompositionRead(ResourceMutationKey mutation) {
        TextureRecord record = textures.get(Objects.requireNonNull(mutation, "mutation").generation());
        if (record == null) throw new IllegalArgumentException("composition source texture is not resident: " + mutation);
        record.layouts().set(new top.ceroxe.rt.renderer.api.TextureSubresourceRange(
                top.ceroxe.rt.renderer.api.TextureAspect.COLOR, 0, 1, 0, 1
        ), org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL);
    }

    CompositionPinLease acquireCompositionPins(VulkanGenericCompositionSource[] sources) {
        requireOpen();
        Objects.requireNonNull(sources, "sources");
        LinkedHashMap<ResourceGenerationKey, Integer> acquired = new LinkedHashMap<>();
        try {
            for (VulkanGenericCompositionSource source : sources) {
                ResourceGenerationKey generation = Objects.requireNonNull(source, "sources contains null")
                        .mutation().generation();
                if (!textures.containsKey(generation)) {
                    throw new IllegalArgumentException("composition source texture is not resident: " + generation);
                }
                int acquiredCount = Math.addExact(acquired.getOrDefault(generation, 0), 1);
                int residentCount = Math.addExact(compositionPinCounts.getOrDefault(generation, 0), 1);
                compositionPinCounts.put(generation, residentCount);
                acquired.put(generation, acquiredCount);
            }
            return new CompositionPinLease(this, Map.copyOf(acquired));
        } catch (RuntimeException failure) {
            releaseCompositionPins(acquired);
            throw failure;
        }
    }

    private void releaseCompositionPins(Map<ResourceGenerationKey, Integer> releases) {
        for (Map.Entry<ResourceGenerationKey, Integer> entry : releases.entrySet()) {
            ResourceGenerationKey generation = entry.getKey();
            int amount = entry.getValue();
            int current = compositionPinCounts.getOrDefault(generation, 0);
            if (amount <= 0 || current < amount) {
                throw new IllegalStateException("composition pin release underflow: " + generation);
            }
            if (current == amount) compositionPinCounts.remove(generation);
            else compositionPinCounts.put(generation, current - amount);
        }
    }

    static final class CompositionPinLease implements AutoCloseable {
        private final VulkanGenericResourceRegistry owner;
        private final Map<ResourceGenerationKey, Integer> pins;
        private boolean closed;

        private CompositionPinLease(
                VulkanGenericResourceRegistry owner, Map<ResourceGenerationKey, Integer> pins
        ) {
            this.owner = owner;
            this.pins = pins;
        }

        @Override
        public void close() {
            if (closed) return;
            owner.releaseCompositionPins(pins);
            closed = true;
        }
    }

    void requireReadable(BufferRecord record) {
        ResourceResidencyEvidence current = evidence(record.generation());
        if (current.outcome() != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("buffer generation is not GPU-ready for reading: " + record.generation());
        }
    }

    void requireWritable(BufferRecord record) {
        requireNoInFlightMutation(record.generation());
        ResourceResidencyEvidence.Outcome outcome = evidence(record.generation()).outcome();
        if (outcome != ResourceResidencyEvidence.Outcome.ACCEPTED
                && outcome != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("buffer generation is not writable: " + record.generation() + " outcome=" + outcome);
        }
    }

    void requireWritable(TextureRecord record) {
        requireNoInFlightMutation(record.generation());
        if (record.evidence().outcome() != ResourceResidencyEvidence.Outcome.ACCEPTED
                && record.evidence().outcome() != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("texture generation is not writable: " + record.generation()
                    + " outcome=" + record.evidence().outcome());
        }
    }

    void requireAttachmentWritable(TextureRecord record, top.ceroxe.rt.renderer.api.LoadOp loadOperation) {
        ResourceResidencyEvidence.Outcome outcome = record.evidence().outcome();
        if (loadOperation == top.ceroxe.rt.renderer.api.LoadOp.LOAD) {
            if (outcome != ResourceResidencyEvidence.Outcome.GPU_READY) {
                throw new IllegalStateException("load attachment requires GPU-ready contents: " + record.generation()
                        + " outcome=" + outcome);
            }
        } else if (outcome != ResourceResidencyEvidence.Outcome.ACCEPTED
                && outcome != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("discarded or cleared attachment must be accepted or reusable: "
                    + record.generation() + " outcome=" + outcome);
        }
    }

    void requireReadable(TextureRecord record) {
        if (record.evidence().outcome() != ResourceResidencyEvidence.Outcome.GPU_READY) {
            throw new IllegalStateException("texture generation is not GPU-ready for reading: " + record.generation());
        }
    }

    void markRecorded(List<BufferRecord> writes, long submissionSequence) {
        for (BufferRecord record : distinct(writes)) {
            registerMutation(record.generation(), submissionSequence);
            ResourceResidencyEvidence current = evidence(record.generation());
            ResourceResidencyEvidence next = new ResourceResidencyEvidence(
                    record.generation(), ResourceResidencyEvidence.Outcome.UPLOAD_RECORDED,
                    record.publicationRevision(), OptionalLong.of(submissionSequence), OptionalLong.empty(),
                    "Vulkan generic command submission recorded a write"
            );
            current.requireNext(next);
            record.evidence(next);
            evidence.put(record.generation(), next);
            record.lastUseSubmissionSequence(submissionSequence);
        }
    }

    void markCompleted(List<BufferRecord> writes, long submissionSequence) {
        for (BufferRecord record : distinct(writes)) {
            completeMutation(record.generation(), submissionSequence);
            ResourceResidencyEvidence current = evidence(record.generation());
            ResourceResidencyEvidence next = new ResourceResidencyEvidence(
                    record.generation(), ResourceResidencyEvidence.Outcome.GPU_READY,
                    record.publicationRevision(), OptionalLong.of(submissionSequence), OptionalLong.empty(),
                    "Vulkan fence completion observed for generic buffer write"
            );
            current.requireNext(next);
            record.evidence(next);
            evidence.put(record.generation(), next);
        }
    }

    void markTextureRecorded(List<TextureRecord> writes, long submissionSequence) {
        for (TextureRecord record : distinctTextures(writes)) {
            registerMutation(record.generation(), submissionSequence);
            ResourceResidencyEvidence current = evidence(record.generation());
            ResourceResidencyEvidence next = new ResourceResidencyEvidence(
                    record.generation(), ResourceResidencyEvidence.Outcome.UPLOAD_RECORDED,
                    record.publicationRevision(), OptionalLong.of(submissionSequence), OptionalLong.empty(),
                    "Vulkan generic command submission recorded a texture write"
            );
            current.requireNext(next);
            record.evidence(next);
            evidence.put(record.generation(), next);
            record.lastUseSubmissionSequence(submissionSequence);
        }
    }

    void markTextureCompleted(List<TextureRecord> writes, long submissionSequence) {
        for (TextureRecord record : distinctTextures(writes)) {
            completeMutation(record.generation(), submissionSequence);
            ResourceResidencyEvidence current = evidence(record.generation());
            ResourceResidencyEvidence next = new ResourceResidencyEvidence(
                    record.generation(), ResourceResidencyEvidence.Outcome.GPU_READY,
                    record.publicationRevision(), OptionalLong.of(submissionSequence), OptionalLong.empty(),
                    "Vulkan fence completion observed for generic texture write"
            );
            current.requireNext(next);
            record.evidence(next);
            evidence.put(record.generation(), next);
        }
    }

    void noteReadUse(List<BufferRecord> reads, long submissionSequence) {
        for (BufferRecord record : distinct(reads)) {
            record.lastUseSubmissionSequence(Math.max(record.lastUseSubmissionSequence(), submissionSequence));
        }
    }

    void noteTextureReadUse(List<TextureRecord> reads, long submissionSequence) {
        for (TextureRecord record : distinctTextures(reads)) {
            record.lastUseSubmissionSequence(Math.max(record.lastUseSubmissionSequence(), submissionSequence));
        }
    }

    ResourceResidencyEvidence evidence(ResourceGenerationKey generation) {
        return evidence.get(Objects.requireNonNull(generation, "generation"));
    }

    private void validateUpserts(RenderResourceTransaction transaction) {
        for (BufferResource descriptor : transaction.buffers()) {
            ResourceGenerationKey generation = ResourceGenerationKey.of(descriptor);
            requireResourceKind(generation.id(), ResourceKind.BUFFER);
            if (buffers.containsKey(generation) || evidence.containsKey(generation)) {
                throw new IllegalArgumentException("buffer generation was already published: " + generation);
            }
            Long previous = highestVersionById.get(generation.id());
            if (previous != null && generation.version().value() <= previous) {
                throw new IllegalArgumentException("buffer generation must advance its stable identity version: " + generation.id());
            }
        }
        for (TextureResource descriptor : transaction.textures()) {
            ResourceGenerationKey generation = ResourceGenerationKey.of(descriptor);
            requireResourceKind(generation.id(), ResourceKind.TEXTURE);
            if (textures.containsKey(generation) || evidence.containsKey(generation)) {
                throw new IllegalArgumentException("texture generation was already published: " + generation);
            }
            Long previous = highestVersionById.get(generation.id());
            if (previous != null && generation.version().value() <= previous) {
                throw new IllegalArgumentException("texture generation must advance its stable identity version: " + generation.id());
            }
        }
    }

    private List<BufferRecord> resolveRetirements(RenderResourceTransaction transaction, long completedCommandSequence) {
        if (completedCommandSequence < -1L) {
            throw new IllegalArgumentException("completed command sequence must be -1 or non-negative");
        }
        ArrayList<BufferRecord> records = new ArrayList<>();
        for (ResourceGenerationKey generation : transaction.retiredGenerations()) {
            requireNoInFlightMutation(generation);
            BufferRecord record = buffers.get(generation);
            if (record == null) continue;
            ResourceResidencyEvidence current = evidence(record.generation());
            boolean unused = current.outcome() == ResourceResidencyEvidence.Outcome.ACCEPTED;
            boolean completed = current.outcome() == ResourceResidencyEvidence.Outcome.GPU_READY
                    && record.lastUseSubmissionSequence() <= completedCommandSequence;
            if (!unused && !completed) {
                throw new IllegalStateException("resource generation cannot retire before all GPU users complete: " + generation);
            }
            records.add(record);
        }
        return List.copyOf(records);
    }

    private List<TextureRecord> resolveTextureRetirements(
            RenderResourceTransaction transaction, long completedCommandSequence
    ) {
        ArrayList<TextureRecord> records = new ArrayList<>();
        for (ResourceGenerationKey generation : transaction.retiredGenerations()) {
            requireNoInFlightMutation(generation);
            if (compositionPinCounts.containsKey(generation)) {
                throw new IllegalStateException("texture generation is retained by an external-frame composition: " + generation);
            }
            TextureRecord record = textures.get(generation);
            if (record == null) continue;
            ResourceResidencyEvidence current = record.evidence();
            boolean unused = current.outcome() == ResourceResidencyEvidence.Outcome.ACCEPTED;
            boolean completed = current.outcome() == ResourceResidencyEvidence.Outcome.GPU_READY
                    && record.lastUseSubmissionSequence() <= completedCommandSequence;
            if (!unused && !completed) {
                throw new IllegalStateException("texture generation cannot retire before all GPU users complete: " + generation);
            }
            records.add(record);
        }
        for (ResourceGenerationKey generation : transaction.retiredGenerations()) {
            if (!buffers.containsKey(generation) && !textures.containsKey(generation)) {
                throw new IllegalArgumentException("cannot retire an unknown active resource generation: " + generation);
            }
        }
        return List.copyOf(records);
    }

    private BufferRecord create(BufferResource descriptor, long transactionRevision) {
        ResourceGenerationKey generation = ResourceGenerationKey.of(descriptor);
        RtGpuBuffer buffer = descriptor.usage().contains(BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)
                ? RtGpuBuffer.createHostVisibleDeviceAddressBuffer(
                        device.device(), device.allocator(), descriptor.byteSize(), usageFlags(descriptor),
                        top.ceroxe.rt.renderer.RtStallTelemetrySink.NOOP
                )
                : RtGpuBuffer.createHostVisibleBuffer(
                        device.device(), device.allocator(), descriptor.byteSize(), usageFlags(descriptor)
                );
        return new BufferRecord(
                descriptor, buffer, transactionRevision,
                ResourceResidencyEvidence.accepted(generation, transactionRevision, "Vulkan buffer allocation accepted")
        );
    }

    private TextureRecord create(TextureResource descriptor, long transactionRevision) {
        ResourceGenerationKey generation = ResourceGenerationKey.of(descriptor);
        return new TextureRecord(
                device.device(), descriptor, VulkanGenericTextureImage.create(device.device(), device.allocator(), descriptor),
                transactionRevision,
                ResourceResidencyEvidence.accepted(generation, transactionRevision,
                        "Vulkan generic texture allocation accepted; texture contents are not GPU-ready")
        );
    }

    private void registerMutation(ResourceGenerationKey generation, long submissionSequence) {
        ResourceMutationKey mutation = new ResourceMutationKey(generation, submissionSequence);
        ResourceMutationKey previous = inFlightMutations.putIfAbsent(generation, mutation);
        if (previous != null) {
            throw new IllegalStateException("resource generation already has an unresolved mutation: " + previous);
        }
    }

    private void completeMutation(ResourceGenerationKey generation, long submissionSequence) {
        ResourceMutationKey expected = new ResourceMutationKey(generation, submissionSequence);
        ResourceMutationKey recorded = inFlightMutations.remove(generation);
        if (!expected.equals(recorded)) {
            throw new IllegalStateException("completed resource mutation does not match the recorded submission: expected="
                    + expected + ", actual=" + recorded);
        }
    }

    private void requireNoInFlightMutation(ResourceGenerationKey generation) {
        ResourceMutationKey mutation = inFlightMutations.get(generation);
        if (mutation != null) {
            throw new IllegalStateException("resource generation has an unresolved mutation: " + mutation);
        }
    }

    private void requireResourceKind(RenderResourceId id, ResourceKind requested) {
        ResourceKind established = resourceKinds.get(id);
        if (established != null && established != requested) {
            throw new IllegalArgumentException("resource identity cannot change kind across generations: " + id);
        }
    }


    private static int usageFlags(BufferResource descriptor) {
        int flags = 0;
        for (BufferUsage usage : descriptor.usage()) {
            flags |= switch (usage) {
                case COPY_SOURCE -> VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
                case COPY_DESTINATION -> VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
                case VERTEX -> VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                case INDEX -> VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                case UNIFORM -> VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                case STORAGE_READ, STORAGE_READ_WRITE -> VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                case INDIRECT -> VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
                case ACCELERATION_STRUCTURE_BUILD_INPUT ->
                        KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            };
        }
        return flags;
    }

    private static boolean sameDescriptor(BufferResource first, BufferResource second) {
        return first.id().equals(second.id())
                && first.version().equals(second.version())
                && first.byteSize() == second.byteSize()
                && first.usage().equals(second.usage());
    }

    private static boolean sameDescriptor(TextureResource first, TextureResource second) {
        return first.id().equals(second.id()) && first.version().equals(second.version())
                && first.dimension() == second.dimension()
                && first.width() == second.width() && first.height() == second.height() && first.depth() == second.depth()
                && first.mipLevelCount() == second.mipLevelCount() && first.arrayLayerCount() == second.arrayLayerCount()
                && first.sampleCount() == second.sampleCount() && first.format() == second.format()
                && first.usage().equals(second.usage());
    }

    private static List<BufferRecord> distinct(List<BufferRecord> records) {
        LinkedHashMap<ResourceGenerationKey, BufferRecord> distinct = new LinkedHashMap<>();
        for (BufferRecord record : records) {
            distinct.put(record.generation(), record);
        }
        return List.copyOf(distinct.values());
    }

    private static List<TextureRecord> distinctTextures(List<TextureRecord> records) {
        LinkedHashMap<ResourceGenerationKey, TextureRecord> distinct = new LinkedHashMap<>();
        for (TextureRecord record : records) distinct.put(record.generation(), record);
        return List.copyOf(distinct.values());
    }

    private ResourceTransactionEvidence rejected(RenderResourceTransaction transaction, String detail) {
        ArrayList<ResourceResidencyEvidence> rejected = new ArrayList<>();
        for (BufferResource resource : transaction.buffers()) {
            rejected.add(ResourceResidencyEvidence.rejected(
                    ResourceGenerationKey.of(resource), transaction.revision(), detail
            ));
        }
        for (TextureResource resource : transaction.textures()) {
            rejected.add(ResourceResidencyEvidence.rejected(
                    ResourceGenerationKey.of(resource), transaction.revision(), detail
            ));
        }
        return new ResourceTransactionEvidence(
                transaction.revision(), ResourceTransactionEvidence.Outcome.REJECTED, rejected, detail
        );
    }

    private static void closeAll(List<BufferRecord> records) {
        for (BufferRecord record : records) {
            try {
                record.buffer().close();
            } catch (RuntimeException ignored) {
                // Original allocation failure remains the useful exception; all candidates are isolated.
            }
        }
    }

    private static void closeTextures(List<TextureRecord> records) {
        for (TextureRecord record : records) {
            try {
                record.close();
            } catch (RuntimeException ignored) {
                // Rollback preserves the allocation failure as the primary diagnostic.
            }
        }
    }


    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic resource registry is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        samplers.close();
        inFlightMutations.clear();
        compositionPinCounts.clear();
        for (BufferRecord record : buffers.values()) record.buffer().close();
        buffers.clear();
        for (TextureRecord record : textures.values()) record.close();
        textures.clear();
    }

    static final class BufferRecord {
        private final BufferResource descriptor;
        private final RtGpuBuffer buffer;
        private final long publicationRevision;
        private ResourceResidencyEvidence evidence;
        private long lastUseSubmissionSequence = -1L;

        private BufferRecord(
                BufferResource descriptor,
                RtGpuBuffer buffer,
                long publicationRevision,
                ResourceResidencyEvidence evidence
        ) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.buffer = Objects.requireNonNull(buffer, "buffer");
            this.publicationRevision = publicationRevision;
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        BufferResource descriptor() { return descriptor; }
        ResourceGenerationKey generation() { return ResourceGenerationKey.of(descriptor); }
        RtGpuBuffer buffer() { return buffer; }
        long publicationRevision() { return publicationRevision; }
        ResourceResidencyEvidence evidence() { return evidence; }
        void evidence(ResourceResidencyEvidence value) { evidence = Objects.requireNonNull(value, "value"); }
        long lastUseSubmissionSequence() { return lastUseSubmissionSequence; }
        void lastUseSubmissionSequence(long value) { lastUseSubmissionSequence = value; }
    }

    static final class TextureRecord {
        private final TextureResource descriptor;
        private final VulkanGenericTextureImage image;
        private final VulkanGenericTextureViews views;
        private final long publicationRevision;
        private ResourceResidencyEvidence evidence;
        private final VulkanGenericTextureLayoutState layouts = new VulkanGenericTextureLayoutState();
        private long lastUseSubmissionSequence = -1L;

        private TextureRecord(VkDevice device, TextureResource descriptor, VulkanGenericTextureImage image,
                              long publicationRevision, ResourceResidencyEvidence evidence) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.image = Objects.requireNonNull(image, "image");
            this.views = new VulkanGenericTextureViews(Objects.requireNonNull(device, "device"), image);
            this.publicationRevision = publicationRevision;
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        TextureResource descriptor() { return descriptor; }
        ResourceGenerationKey generation() { return ResourceGenerationKey.of(descriptor); }
        VulkanGenericTextureImage image() { return image; }
        VulkanGenericTextureViews views() { return views; }
        long publicationRevision() { return publicationRevision; }
        ResourceResidencyEvidence evidence() { return evidence; }
        void evidence(ResourceResidencyEvidence value) { evidence = Objects.requireNonNull(value, "value"); }
        VulkanGenericTextureLayoutState layouts() { return layouts; }
        long lastUseSubmissionSequence() { return lastUseSubmissionSequence; }
        void lastUseSubmissionSequence(long value) { lastUseSubmissionSequence = value; }
        void close() {
            RuntimeException failure = null;
            try {
                views.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                image.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }

    private enum ResourceKind { BUFFER, TEXTURE }

}
