package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import top.ceroxe.mcvulkanrt.renderer.spi.RayTracingBackendProvider;

/** Dependency-free executable contract gate for the public renderer boundary. */
public final class RendererApiContractSelfTest {
    private RendererApiContractSelfTest() {
    }

    public static void main(String[] args) {
        assertConfigurationBounds();
        assertCameraAndFrameValidation();
        assertAssetOwnership();
        assertMipChainContract();
        assertTransactionOwnershipAndConflicts();
        assertGpuTimingIdentity();
        assertGpuFrameDescriptorValidation();
        assertExportedHandleLifecycle();
        assertProviderSelection();
        System.out.println("RendererApiContractSelfTest passed");
    }

    private static void assertConfigurationBounds() {
        require(RayTracingRendererConfig.defaults().maxFramesInFlight() == 3, "default frame ring changed");
        expect(IllegalArgumentException.class, () -> new RayTracingRendererConfig(1, false, true));
        expect(IllegalArgumentException.class, () -> new RayTracingRendererConfig(17, false, true));
    }

    private static void assertCameraAndFrameValidation() {
        CameraState camera = camera();
        RenderFrameRequest request = new RenderFrameRequest(
                7L, 3L, 1920, 1080, camera, EnvironmentState.neutral()
        );
        require(request.width() == 1920 && request.height() == 1080, "frame extent changed");
        require(request.lightmap() == LightmapState.fullIntensity(),
                "legacy frame construction must select the neutral lightmap");
        require(request.fog() == DistanceFogState.disabled(),
                "legacy frame construction must select disabled distance fog");
        require(request.textureSampling().equals(TextureSamplingState.pixelStable()),
                "legacy frame construction must select pixel-stable texture minification");
        require(TextureSamplingState.anisotropic(16).maxAnisotropy() == 16,
                "supported anisotropic sampling level changed");
        expect(IllegalArgumentException.class, () -> TextureSamplingState.anisotropic(17));
        expect(IllegalArgumentException.class, () -> new TextureSamplingState(
                TextureSamplingState.MinificationMode.PIXEL_STABLE, 2
        ));
        int[] lightmapTexels = new int[LightmapState.ENTRY_COUNT];
        lightmapTexels[0] = 0xff33_2211;
        LightmapState lightmap = new LightmapState(4L, lightmapTexels);
        lightmapTexels[0] = 0;
        require(lightmap.texelsRgba8().get(0) == 0xff33_2211,
                "lightmap retained caller-owned texels");
        expect(ReadOnlyBufferException.class, () -> lightmap.texelsRgba8().put(0, 0));
        expect(IllegalArgumentException.class, () -> new LightmapState(-1L, new int[256]));
        expect(IllegalArgumentException.class, () -> new LightmapState(0L, new int[255]));
        expect(IllegalArgumentException.class, () -> new DistanceFogState(
                1.1F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F
        ));
        expect(IllegalArgumentException.class, () -> new CameraState(
                0.0D, 0.0D, 0.0D,
                0.0F, 0.0F, -2.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 1.0F
        ));
        expect(IllegalArgumentException.class, () -> new RenderFrameRequest(
                1L, 0L, Integer.MAX_VALUE, Integer.MAX_VALUE, camera, EnvironmentState.neutral()
        ));
    }

    private static void assertAssetOwnership() {
        byte[] pixels = {1, 2, 3, 4};
        TextureAsset texture = new TextureAsset(
                1L, 1, 1,
                TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT,
                TextureAsset.AddressMode.CLAMP_TO_EDGE,
                TextureAsset.Filter.LINEAR,
                pixels
        );
        pixels[0] = 99;
        require(texture.rgba8().get(0) == 1, "texture retained caller-owned pixel array");
        require(texture.mipLevelCount() == 1 && texture.mipWidth(0) == 1 && texture.mipByteOffset(0) == 0,
                "legacy texture construction must retain a single mip level");
        expect(ReadOnlyBufferException.class, () -> texture.rgba8().put(0, (byte) 8));

        float[] positions = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F};
        int[] indices = {0, 1, 2};
        MeshAsset mesh = new MeshAsset(
                3L,
                positions,
                new float[0],
                new float[0],
                new float[0],
                new int[0],
                indices,
                new long[]{2L}
        );
        positions[0] = 42.0F;
        indices[0] = 2;
        require(mesh.positions().get(0) == 0.0F, "mesh retained caller-owned position array");
        require(mesh.triangleIndices().get(0) == 0, "mesh retained caller-owned index array");
        expect(ReadOnlyBufferException.class, () -> mesh.positions().put(0, 2.0F));
        expect(IllegalArgumentException.class, () -> new MeshAsset(
                4L,
                new float[]{0.0F, 0.0F, 0.0F},
                new float[0],
                new float[0],
                new float[0],
                new int[0],
                new int[]{0, 1, 0},
                new long[]{2L}
        ));

        SceneInstance fullyVisible = new SceneInstance(
                5L, 3L, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true
        );
        require(fullyVisible.surfaceVisibility() == 1.0F,
                "legacy instance construction must remain fully visible");
        expect(IllegalArgumentException.class, () -> new SceneInstance(
                6L, 3L, AffineTransform.identity(), SceneInstance.Mobility.STATIC,
                0xff, true, Float.NaN
        ));
        expect(IllegalArgumentException.class, () -> new SceneInstance(
                6L, 3L, AffineTransform.identity(), SceneInstance.Mobility.STATIC,
                0xff, true, 1.01F
        ));
    }

    private static void assertMipChainContract() {
        byte[] mipBytes = new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))];
        TextureAsset texture = new TextureAsset(
                8L, 4, 2, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.CLAMP_TO_EDGE, TextureAsset.AddressMode.CLAMP_TO_EDGE,
                TextureAsset.Filter.LINEAR, 3, mipBytes
        );
        require(texture.mipLevelCount() == 3
                        && texture.mipWidth(0) == 4 && texture.mipHeight(0) == 2
                        && texture.mipWidth(1) == 2 && texture.mipHeight(1) == 1
                        && texture.mipWidth(2) == 1 && texture.mipHeight(2) == 1
                        && texture.mipByteOffset(0) == 0
                        && texture.mipByteOffset(1) == 32
                        && texture.mipByteOffset(2) == 40,
                "mip chain dimensions and tightly packed offsets changed");
        expect(IllegalArgumentException.class, () -> new TextureAsset(
                9L, 4, 2, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.CLAMP_TO_EDGE, TextureAsset.AddressMode.CLAMP_TO_EDGE,
                TextureAsset.Filter.LINEAR, 4, mipBytes
        ));
    }

    private static void assertTransactionOwnershipAndConflicts() {
        MaterialAsset material = material(2L);
        require(material.shadingModel() == MaterialAsset.ShadingModel.PHYSICALLY_BASED,
                "legacy material construction must retain PBR shading");
        List<MaterialAsset> callerList = new ArrayList<>();
        callerList.add(material);
        long[] removals = {9L};
        SceneTransaction transaction = new SceneTransaction(
                5L,
                false,
                new SceneTransaction.Upserts(List.of(), callerList, List.of(), List.of(), List.of()),
                new SceneTransaction.Removals(
                        new long[0], removals, new long[0], new long[0], new long[0]
                )
        );
        callerList.clear();
        removals[0] = 10L;
        require(transaction.upserts().materials().size() == 1, "transaction retained caller-owned list");
        require(transaction.removals().materialIds().get(0) == 9L, "transaction retained caller-owned removal array");
        require(transaction.hasChanges(), "non-empty transaction reported no changes");
        expect(IllegalArgumentException.class, () -> new SceneTransaction(
                6L,
                false,
                new SceneTransaction.Upserts(List.of(), List.of(material), List.of(), List.of(), List.of()),
                new SceneTransaction.Removals(
                        new long[0], new long[]{2L}, new long[0], new long[0], new long[0]
                )
        ));
        expect(IllegalArgumentException.class, () -> new SceneTransaction(
                6L,
                false,
                SceneTransaction.Upserts.empty(),
                new SceneTransaction.Removals(
                        new long[]{1L, 1L}, new long[0], new long[0], new long[0], new long[0]
                )
        ));
    }

    private static void assertGpuTimingIdentity() {
        RendererDiagnostics.FrameGpuTiming timing = new RendererDiagnostics.FrameGpuTiming(
                true, 10L, 0L, 0L, 400L, 25L, 425L, 500L
        );
        require(timing.averageTotalNanos() == 425L, "GPU total timing changed");
        expect(IllegalArgumentException.class, () -> new RendererDiagnostics.FrameGpuTiming(
                true, 10L, 0L, 0L, 400L, 25L, 420L, 500L
        ));
    }

    private static void assertGpuFrameDescriptorValidation() {
        GpuFrameLease.FrameDescriptor descriptor = new GpuFrameLease.FrameDescriptor(
                8L,
                5L,
                1920,
                1080,
                37,
                1,
                1,
                0x10,
                0,
                1,
                1,
                1,
                1,
                0,
                0,
                8_294_400L,
                0L,
                false
        );
        require(descriptor.frameSequence() == 8L, "GPU frame descriptor changed");
        expect(IllegalArgumentException.class, () -> new GpuFrameLease.FrameDescriptor(
                8L, 5L, 0, 1080, 37, 1, 1, 0x10, 0, 1,
                1, 1, 1, 0, 0, 1L, 0L, false
        ));
        expect(IllegalArgumentException.class, () -> new GpuFrameLease.ExternalSemaphoreSignal(
                1L, 2, GpuFrameLease.SemaphoreKind.BINARY, 1L,
                GpuFrameLease.ImportDisposition.IMPORT_CONSUMES_HANDLE
        ));
    }

    private static void assertExportedHandleLifecycle() {
        TrackingHandle imported = new TrackingHandle(
                GpuFrameLease.ImportDisposition.IMPORT_CONSUMES_HANDLE
        );
        require(imported.markImported(), "first successful import did not transition handle state");
        require(!imported.markImported(), "native handle imported more than once");
        imported.close();
        require(imported.nativeCloses.get() == 0, "import-consumed handle was closed by exporter");

        TrackingHandle abandoned = new TrackingHandle(
                GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE
        );
        abandoned.close();
        abandoned.close();
        require(abandoned.nativeCloses.get() == 1, "abandoned exported handle did not close exactly once");

        new GpuFrameLease.AcquireSignal(imported, GpuFrameLease.SemaphoreKind.BINARY, 0L);
        expect(IllegalArgumentException.class, () -> new GpuFrameLease.AcquireSignal(
                abandoned, GpuFrameLease.SemaphoreKind.TIMELINE, 0L
        ));
    }

    private static void assertProviderSelection() {
        TrackingProvider unsupported = new TrackingProvider(
                "unsupported", 100, RayTracingBackendProvider.ProbeResult.unsupported("missing feature"), false
        );
        TrackingProvider compatible = new TrackingProvider(
                "vulkan", 10, RayTracingBackendProvider.ProbeResult.compatible("ready"), false
        );
        RayTracingRenderer selected = RendererBootstrap.openProviders(
                null, RayTracingRendererConfig.defaults(), List.of(compatible, unsupported)
        );
        require(selected == compatible.renderer, "bootstrap did not select the compatible provider");
        require(unsupported.opens.get() == 0 && compatible.opens.get() == 1,
                "bootstrap opened an incompatible provider or opened twice");

        expect(RendererInitializationException.class, () -> RendererBootstrap.openProviders(
                null,
                RayTracingRendererConfig.defaults(),
                List.of(
                        compatible,
                        new TrackingProvider(
                                "vulkan", 1,
                                RayTracingBackendProvider.ProbeResult.compatible("duplicate"), false
                        )
                )
        ));

        TrackingProvider broken = new TrackingProvider(
                "broken", 200, RayTracingBackendProvider.ProbeResult.compatible("probe ready"), true
        );
        RendererInitializationException initialization = expect(
                RendererInitializationException.class,
                () -> RendererBootstrap.openProviders(
                        null, RayTracingRendererConfig.defaults(), List.of(compatible, broken)
                )
        );
        require(initialization.providerId().equals("broken"), "initialization failure lost provider identity");
        require(compatible.opens.get() == 1, "bootstrap silently fell back after initialization failure");

        RendererUnavailableException unavailable = expect(
                RendererUnavailableException.class,
                () -> RendererBootstrap.openProviders(
                        "missing", RayTracingRendererConfig.defaults(), List.of(compatible)
                )
        );
        require(unavailable.attempts().isEmpty(), "explicit missing provider fabricated probe attempts");
    }

    private static CameraState camera() {
        return new CameraState(
                0.0D, 0.0D, 0.0D,
                0.0F, 0.0F, -1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.5625F
        );
    }

    private static MaterialAsset material(long id) {
        return new MaterialAsset(
                id,
                MaterialAsset.BlendMode.MASKED,
                0xffffffff,
                -1L,
                -1L,
                -1L,
                -1L,
                0x000000ff,
                0.0F,
                0.5F,
                1.0F,
                0.0F,
                0.0F,
                1.5F,
                true
        );
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle {
        private final GpuFrameLease.ImportDisposition disposition;
        private final AtomicInteger nativeCloses = new AtomicInteger();
        private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;

        private TrackingHandle(GpuFrameLease.ImportDisposition disposition) {
            this.disposition = disposition;
        }

        @Override
        public long value() {
            return 1L;
        }

        @Override
        public int vulkanHandleType() {
            return 2;
        }

        @Override
        public GpuFrameLease.ImportDisposition importDisposition() {
            return disposition;
        }

        @Override
        public GpuFrameLease.HandleState state() {
            return state;
        }

        @Override
        public boolean markImported() {
            if (state != GpuFrameLease.HandleState.EXPORTED) {
                return false;
            }
            state = GpuFrameLease.HandleState.IMPORTED;
            if (disposition == GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE) {
                nativeCloses.incrementAndGet();
            }
            return true;
        }

        @Override
        public void close() {
            if (state == GpuFrameLease.HandleState.CLOSED) {
                return;
            }
            if (state == GpuFrameLease.HandleState.EXPORTED) {
                nativeCloses.incrementAndGet();
            }
            state = GpuFrameLease.HandleState.CLOSED;
        }
    }

    private static final class TrackingProvider implements RayTracingBackendProvider {
        private final Descriptor descriptor;
        private final ProbeResult probe;
        private final boolean failOpen;
        private final AtomicInteger opens = new AtomicInteger();
        private final RayTracingRenderer renderer = new TrackingRenderer();

        private TrackingProvider(String id, int priority, ProbeResult probe, boolean failOpen) {
            this.descriptor = new Descriptor(id, priority, API_MAJOR, 0);
            this.probe = probe;
            this.failOpen = failOpen;
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public ProbeResult probe(RayTracingRendererConfig configuration) {
            return probe;
        }

        @Override
        public RayTracingRenderer open(RayTracingRendererConfig configuration) {
            opens.incrementAndGet();
            if (failOpen) {
                throw new IllegalStateException("synthetic initialization failure");
            }
            return renderer;
        }
    }

    private static final class TrackingRenderer implements RayTracingRenderer {
        @Override public Status status() { return Status.READY; }
        @Override public SceneUpdateResult apply(SceneTransaction transaction) { throw new UnsupportedOperationException(); }
        @Override public FrameSubmissionResult submit(RenderFrameRequest request) { throw new UnsupportedOperationException(); }
        @Override public GpuFrameLease acquireLatestFrame() { return null; }
        @Override public RendererDiagnostics diagnostics() { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
