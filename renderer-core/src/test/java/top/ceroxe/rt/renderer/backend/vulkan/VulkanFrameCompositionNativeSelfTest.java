package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.BeginRenderPassCommand;
import top.ceroxe.rt.renderer.api.ClearValue;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.FrameCompositionPlan;
import top.ceroxe.rt.renderer.api.FrameCompositionRequest;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.RenderAttachment;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderPassDescriptor;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceMutationKey;
import top.ceroxe.rt.renderer.api.ResourceTransactionEvidence;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.StoreOp;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.api.TextureView;
import top.ceroxe.rt.renderer.api.TextureViewDimension;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

/** Real Vulkan composition smoke: generic render output -> provider-owned composition -> readback. */
public final class VulkanFrameCompositionNativeSelfTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    private VulkanFrameCompositionNativeSelfTest() { }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), "composition smoke requires hardware RT: " + capability.summary());
        RendererConfig configuration = RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .maxFramesInFlight(2)
                .validationEnabled(true)
                .gpuTimingsEnabled(true)
                .build();
        VulkanGpuSceneRenderingSession scene = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        VulkanGenericCommandSession generic = new VulkanGenericCommandSession(scene.genericCommandRuntime(), 2);
        try {
            TextureResource source = new TextureResource(
                    new RenderResourceId(700L),
                    top.ceroxe.rt.renderer.api.ResourceVersion.initial(),
                    TextureDimension.TEXTURE_2D,
                    WIDTH, HEIGHT, 1, 1, 1, 1,
                    TextureFormat.RGBA8_UNORM,
                    EnumSet.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.STORAGE_READ)
            );
            require(generic.submitResources(new RenderResourceTransaction(
                    0L, List.of(), List.of(source), List.of()
            )).outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED,
                    "composition source publication was rejected");
            TextureSubresourceRange range = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);
            TextureView view = new TextureView(source, TextureViewDimension.TEXTURE_2D, range);
            RenderPassDescriptor pass = RenderPassDescriptor.color(WIDTH, HEIGHT, List.of(
                    RenderAttachment.cleared(
                            view, StoreOp.STORE, new ClearValue.Color(0.82f, 0.14f, 0.06f, 1.0f)
                    )
            ));
            CommandExecutionEvidence sourceSubmission = generic.submit(new RenderCommandTransaction(
                    0L, List.of(new BeginRenderPassCommand(pass), new top.ceroxe.rt.renderer.api.EndRenderPassCommand())
            ));
            require(sourceSubmission.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                    "generic source render was not recorded: " + sourceSubmission);
            awaitGeneric(generic, 0L);

            ResourceMutationKey mutation = new ResourceMutationKey(ResourceGenerationKey.of(source), 0L);
            FrameCompositionRequest request = new FrameCompositionRequest(
                    List.of(new FrameCompositionPlan.Layer(mutation, FrameCompositionPlan.Operation.REPLACE)),
                    WIDTH, HEIGHT, FrameOutputFormat.SDR_RGBA8, 0L, 0L,
                    FrameCompositionRequest.AlphaEncoding.PREMULTIPLIED
            );
            var submitted = scene.compose(request, generic);
            require(submitted.outcome() == top.ceroxe.rt.renderer.api.FrameCompositionEvidence.Outcome.SUBMITTED,
                    "composition was not submitted: " + submitted);
            var completed = awaitComposition(scene, 0L);
            require(completed.outcome() == top.ceroxe.rt.renderer.api.FrameCompositionEvidence.Outcome.GPU_COMPLETED,
                    "composition did not reach GPU_COMPLETED: " + completed);

            CpuFrame frame = awaitCpuFrame(scene);
            byte[] pixels = new byte[frame.byteCount()];
            frame.pixelsRgba8().get(pixels);
            int red = Byte.toUnsignedInt(pixels[0]);
            int green = Byte.toUnsignedInt(pixels[1]);
            int blue = Byte.toUnsignedInt(pixels[2]);
            require(red > green && green > blue,
                    "composition readback did not preserve source color ordering: " + red + "," + green + "," + blue);
            System.out.println("VulkanFrameCompositionNativeSelfTest passed: evidence=" + completed
                    + ", firstPixel=" + red + "," + green + "," + blue);
        } finally {
            generic.close();
            scene.close();
        }
    }

    private static void awaitGeneric(VulkanGenericCommandSession generic, long sequence) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            generic.pump();
            CommandExecutionEvidence evidence = generic.commandEvidence(sequence).orElseThrow();
            if (evidence.outcome() == CommandExecutionEvidence.Outcome.GPU_COMPLETED
                    || evidence.outcome() == CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED) return;
            if (evidence.outcome() != CommandExecutionEvidence.Outcome.RECORDED) {
                throw new IllegalStateException("generic source render failed: " + evidence);
            }
            Thread.sleep(1L);
        }
        throw new IllegalStateException("generic source render timed out");
    }

    private static top.ceroxe.rt.renderer.api.FrameCompositionEvidence awaitComposition(
            VulkanGpuSceneRenderingSession scene, long sequence
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var evidence = scene.compositionEvidence(sequence).orElseThrow();
            if (evidence.outcome() == top.ceroxe.rt.renderer.api.FrameCompositionEvidence.Outcome.GPU_COMPLETED
                    || evidence.outcome() == top.ceroxe.rt.renderer.api.FrameCompositionEvidence.Outcome.CONSUMER_ACCEPTED) {
                return evidence;
            }
            if (evidence.outcome() == top.ceroxe.rt.renderer.api.FrameCompositionEvidence.Outcome.REJECTED) {
                throw new IllegalStateException("composition failed: " + evidence);
            }
            Thread.sleep(1L);
        }
        throw new IllegalStateException("composition timed out");
    }

    private static CpuFrame awaitCpuFrame(VulkanGpuSceneRenderingSession scene) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            CpuFrame frame = scene.captureLatestCpuFrame(-1L);
            if (frame != null) return frame;
            Thread.sleep(1L);
        }
        throw new IllegalStateException("composition readback timed out");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
