package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.BeginRenderPassCommand;
import top.ceroxe.rt.renderer.api.BufferResource;
import top.ceroxe.rt.renderer.api.BufferUsage;
import top.ceroxe.rt.renderer.api.ClearValue;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.EndRenderPassCommand;
import top.ceroxe.rt.renderer.api.EvidenceQuery;
import top.ceroxe.rt.renderer.api.EvidenceRetentionPolicy;
import top.ceroxe.rt.renderer.api.RenderAttachment;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderPassDescriptor;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceMutationKey;
import top.ceroxe.rt.renderer.api.ResourceResidencyEvidence;
import top.ceroxe.rt.renderer.api.ResourceTransactionEvidence;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.StoreOp;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.api.TextureView;
import top.ceroxe.rt.renderer.api.TextureViewDimension;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/** Real-device regression for bounded completed history, exact mutations and sparse identities. */
public final class VulkanEvidenceRetentionNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), capability.summary());
        var policy = new EvidenceRetentionPolicy(4, 4, 8, 3);
        var config = RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .maxFramesInFlight(2).validationEnabled(true).evidenceRetention(policy).build();
        try (var scene = VulkanGpuSceneRenderingSession.open(capability, config, RendererRtDiagnostics.noop());
             var session = new VulkanGenericCommandSession(scene.genericCommandRuntime(), 2, policy)) {
            var run = new Run(session);
            var source = texture(41, 0);
            run.publish(source);
            for (int frame = 0; frame < 8; frame++) run.render(source);
            long initialCommands = liveCount("CommandExecutionEvidence");
            for (int frame = 0; frame < 2000; frame++) run.render(source);
            long finalCommands = liveCount("CommandExecutionEvidence");
            require(finalCommands - initialCommands <= 4, "command evidence retained cumulative frames");
            require(session.retentionStatistics().commandEntries() == 4, "command capacity was not enforced");
            require(session.queryCommandEvidence(0).status() == EvidenceQuery.Status.OUTSIDE_RETENTION_WINDOW,
                    "old completed command did not expire");
            var mutation = new ResourceMutationKey(ResourceGenerationKey.of(source), run.sequence - 1);
            var other = texture(9, 0);
            run.publish(other);
            for (int frame = 0; frame < 12; frame++) run.render(other);
            require(session.queryCommandEvidence(mutation.commandSequence()).status()
                    == EvidenceQuery.Status.OUTSIDE_RETENTION_WINDOW, "source command remained historical");
            var compositionSource = session.requireCompositionSource(mutation);
            var pin1 = session.pinComposition(new VulkanGenericCompositionSource[]{compositionSource});
            var pin2 = session.pinComposition(new VulkanGenericCompositionSource[]{compositionSource});
            require(session.submit(run.command(source)).outcome() == CommandExecutionEvidence.Outcome.REJECTED,
                    "pinned mutation was overwritten");
            require(run.retireAttempt(source).outcome() == ResourceTransactionEvidence.Outcome.REJECTED,
                    "pinned generation retired");
            pin1.close();
            pin1.close();
            require(session.submit(run.command(source)).outcome() == CommandExecutionEvidence.Outcome.REJECTED,
                    "one consumer released another consumer's protection");
            pin2.close();
            run.render(source);
            expect(IllegalArgumentException.class, () -> session.requireCompositionSource(mutation));
            var replacement = texture(41, 1);
            run.publish(replacement);
            var overCapacity = run.publishAttempt(texture(123, 0));
            require(overCapacity.outcome() == ResourceTransactionEvidence.Outcome.REJECTED
                    && overCapacity.detail().contains("RESIDENT_GENERATION_BUDGET_EXHAUSTED"),
                    "resident generation capacity did not reject admission");
            run.render(replacement);
            expect(IllegalArgumentException.class, () -> session.requireCompositionSource(
                    new ResourceMutationKey(ResourceGenerationKey.of(replacement), mutation.commandSequence())));
            run.retire(replacement);
            run.retire(other);

            var residentStats = session.retentionStatistics();
            var badTexture = texture(111, 0);
            var badRetirement = new ResourceGenerationKey(new RenderResourceId(112), ResourceVersion.initial());
            require(session.submitResources(RenderResourceTransaction.builder(run.revision)
                    .upsert(badTexture).retire(badRetirement).build()).outcome() == ResourceTransactionEvidence.Outcome.REJECTED,
                    "invalid mixed resource transaction was accepted");
            require(session.retentionStatistics().resourceIdentities() == residentStats.resourceIdentities(),
                    "rollback polluted identity tracking");
            require(session.queryResourceEvidence(ResourceGenerationKey.of(badTexture)).status() == EvidenceQuery.Status.UNKNOWN,
                    "rollback published resource evidence");

            for (int generation = 0; generation < 8; generation++) run.generation(generation);
            long initialResources = liveCount("ResourceResidencyEvidence");
            for (int generation = 8; generation < 2008; generation++) run.generation(generation);
            long finalResources = liveCount("ResourceResidencyEvidence");
            require(finalResources - initialResources <= 4, "retired resource evidence grew with generations");
            require(session.retentionStatistics().retiredResourceHistory() == 4, "retired history exceeded capacity");
            require(session.queryResourceEvidence(ResourceGenerationKey.of(texture(64, 0))).status()
                    == EvidenceQuery.Status.OUTSIDE_RETENTION_WINDOW, "old generation did not expire");
            require(run.publishAttempt(texture(64, 0)).outcome() == ResourceTransactionEvidence.Outcome.REJECTED,
                    "expired generation was replayed");
            var reusedKind = new BufferResource(new RenderResourceId(64), new ResourceVersion(2008), 16,
                    EnumSet.of(BufferUsage.COPY_DESTINATION));
            require(session.submitResources(RenderResourceTransaction.builder(run.revision).upsert(reusedKind).build())
                    .outcome() == ResourceTransactionEvidence.Outcome.REJECTED, "identity changed kind after eviction");
            long[] sparseIds = {Long.MAX_VALUE, 0, 4, 4096, 3};
            for (long id : sparseIds) {
                var resource = texture(id, 0);
                run.publish(resource);
                run.retire(resource);
            }
            require(session.retentionStatistics().resourceIdentities() == 8, "sparse IDs were collapsed into a watermark");
            for (int attempt = 0; attempt < 2000; attempt++) {
                var rejected = run.publishAttempt(texture(100_000L + attempt, 0));
                require(rejected.outcome() == ResourceTransactionEvidence.Outcome.REJECTED
                        && rejected.detail().contains("RESOURCE_IDENTITY_BUDGET_EXHAUSTED"),
                        "identity budget did not fail explicitly");
            }
            require(session.retentionStatistics().resourceIdentities() == 8, "rejected identities were retained");
            run.generation(2008);
            require(session.submit(new RenderCommandTransaction(0, run.command(source).commands())).outcome()
                    == CommandExecutionEvidence.Outcome.REJECTED, "command replay survived evidence eviction");
            run.render(source);
            long unobservedStart = run.sequence;
            for (int frame = 0; frame < 4; frame++) {
                require(session.submit(run.command(source)).outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                        "unobserved command rejected before budget was full");
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (session.retentionStatistics().pendingCommands() != 0) {
                    require(System.nanoTime() < deadline, "unobserved completion timed out");
                    Thread.sleep(1);
                }
                run.sequence++;
            }
            var blocked = session.submit(run.command(source));
            require(blocked.outcome() == CommandExecutionEvidence.Outcome.BLOCKED
                    && blocked.reason() == CommandExecutionEvidence.Reason.BOUNDED_BACKPRESSURE,
                    "unobserved evidence did not apply native admission backpressure");
            for (long sequence = unobservedStart; sequence < run.sequence; sequence++) {
                require(session.queryCommandEvidence(sequence).evidence().orElseThrow().outcome().outputProduced(),
                        "slow reader lost the terminal result");
            }
            run.render(source);
            System.out.println("VulkanEvidenceRetentionNativeSelfTest passed: commandLive=" + initialCommands + "->"
                    + finalCommands + ", resourceLive=" + initialResources + "->" + finalResources
                    + ", statistics=" + session.retentionStatistics());
        }
        try (var renderer = top.ceroxe.rt.renderer.api.RendererBootstrap.open(config)) {
            var access = renderer.extension(top.ceroxe.rt.renderer.api.RendererEvidenceAccess.class).orElseThrow();
            require(access.queryCommandExecutionEvidence(0).status() == EvidenceQuery.Status.UNKNOWN,
                    "new public session inherited command history");
            require(access.evidenceRetentionStatistics().policy().equals(policy), "public configuration lost evidence policy");
            var resource = texture(900, 0);
            require(renderer.submitResources(RenderResourceTransaction.builder(0).upsert(resource).build()).outcome()
                    == ResourceTransactionEvidence.Outcome.ACCEPTED, "public resource publication failed");
            var view = new TextureView(resource, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            var pass = RenderPassDescriptor.color(8, 8, List.of(RenderAttachment.cleared(view, StoreOp.STORE,
                    new ClearValue.Color(1, 0, 0, 1))));
            require(renderer.submitCommands(new RenderCommandTransaction(0,
                    List.of(new BeginRenderPassCommand(pass), new EndRenderPassCommand()))).outcome()
                    == CommandExecutionEvidence.Outcome.RECORDED, "public command submission failed");
            var lease = access.retainCommandEvidence(0);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!access.queryCommandExecutionEvidence(0).evidence().orElseThrow().outcome().outputProduced()) {
                require(System.nanoTime() < deadline, "public evidence completion timed out");
                Thread.sleep(1);
            }
            renderer.close();
            lease.close();
            lease.close();
            expect(top.ceroxe.rt.renderer.api.RendererStateException.class, () -> access.queryCommandExecutionEvidence(0));
        }
    }

    private static TextureResource texture(long id, long version) {
        return new TextureResource(new RenderResourceId(id), new ResourceVersion(version), TextureDimension.TEXTURE_2D,
                8, 8, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                EnumSet.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.STORAGE_READ));
    }

    private static final class Run {
        final VulkanGenericCommandSession session;
        long revision;
        long sequence;

        Run(VulkanGenericCommandSession session) { this.session = session; }

        ResourceTransactionEvidence publishAttempt(TextureResource resource) {
            return session.submitResources(RenderResourceTransaction.builder(revision).upsert(resource).build());
        }

        void publish(TextureResource resource) {
            require(publishAttempt(resource).outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "publication rejected");
            revision++;
        }

        ResourceTransactionEvidence retireAttempt(TextureResource resource) {
            return session.submitResources(RenderResourceTransaction.builder(revision)
                    .retire(ResourceGenerationKey.of(resource)).build());
        }

        void retire(TextureResource resource) {
            var result = retireAttempt(resource);
            require(result.outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "retirement rejected: " + result);
            require(result.resources().getFirst().outcome().retired(), "retirement fabricated completion");
            revision++;
        }

        void generation(long generation) throws Exception {
            var resource = texture(64, generation);
            publish(resource);
            render(resource);
            long publication = revision - 1;
            retire(resource);
            var retired = session.queryResourceEvidence(ResourceGenerationKey.of(resource)).evidence().orElseThrow();
            require(retired.outcome() == ResourceResidencyEvidence.Outcome.RETIRED
                    && retired.transactionRevision() == publication
                    && retired.mutationKey().orElseThrow().commandSequence() == sequence - 1
                    && retired.lastConsumerSequence().orElseThrow() >= sequence - 1, "retirement lost exact provenance");
        }

        RenderCommandTransaction command(TextureResource resource) {
            var view = new TextureView(resource, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            var pass = RenderPassDescriptor.color(8, 8, List.of(RenderAttachment.cleared(view, StoreOp.STORE,
                    new ClearValue.Color(1, 0, 0, 1))));
            return new RenderCommandTransaction(sequence, List.of(new BeginRenderPassCommand(pass), new EndRenderPassCommand()));
        }

        void render(TextureResource resource) throws Exception {
            var submitted = session.submit(command(resource));
            require(submitted.outcome() == CommandExecutionEvidence.Outcome.RECORDED, "command rejected: " + submitted.detail());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (true) {
                var evidence = session.commandEvidence(sequence).orElseThrow();
                if (evidence.outcome().outputProduced()) break;
                require(evidence.outcome() == CommandExecutionEvidence.Outcome.RECORDED, "command failed");
                require(System.nanoTime() < deadline, "GPU completion timed out");
                Thread.sleep(0, 250_000);
            }
            var frame = session.captureLatestCpuFrame(sequence - 1);
            require(frame != null && frame.frameSequence() == sequence && frame.width() == 8 && frame.height() == 8,
                    "readback identity/extent mismatch");
            var bytes = frame.pixelsRgba8();
            while (bytes.hasRemaining()) {
                require(Byte.toUnsignedInt(bytes.get()) == 255 && bytes.get() == 0 && bytes.get() == 0
                        && Byte.toUnsignedInt(bytes.get()) == 255, "CPU output pixel is not red RGBA8");
            }
            sequence++;
        }
    }

    private static long liveCount(String simpleName) throws Exception {
        String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[]{new String[]{"-all=false"}}, new String[]{String[].class.getName()});
        var pattern = Pattern.compile("\\s*\\d+:\\s+(\\d+)\\s+\\d+\\s+top\\.ceroxe\\.rt\\.renderer\\.api\\."
                + Pattern.quote(simpleName) + "(?:\\s.*)?");
        for (String line : histogram.lines().toList()) {
            var match = pattern.matcher(line);
            if (match.matches()) return Long.parseLong(match.group(1));
        }
        throw new AssertionError("live histogram missing " + simpleName);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected failure", failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
