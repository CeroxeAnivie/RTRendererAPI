package demo;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.Caustics;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FrameClock;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumBoundary;
import top.ceroxe.rt.renderer.api.MediumDensityField;
import top.ceroxe.rt.renderer.api.MediumTransport;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.PrimitiveInstance;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.Rgb;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;

/** Selectable, one-profile-at-a-time visual certification scenes for implemented medium routes. */
public final class MediumCertificationDemo {
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int MAX_QUEUED = 2;
    private static final Caustics SCATTER_GLASS_CAUSTICS =
            Caustics.photonMap(0, 2, 0, 12, 65_536, .16f).withMaxEvents(20);

    private MediumCertificationDemo() { }

    enum Route {
        CAMERA_ABSORPTION,
        BOUNDARY_DENSITY,
        CAMERA_SINGLE_SCATTER,
        CAMERA_MULTIPLE_SCATTER,
        HETEROGENEOUS_SINGLE_SCATTER,
        HETEROGENEOUS_MULTIPLE_SCATTER,
        BOUNDARY_SCATTER,
        PBR_SINGLE_SIDED,
        PBR_DOUBLE_SIDED,
        OPTICAL_SMOOTH_NESTED,
        OPTICAL_ROUGH_NESTED,
        SCATTER_GLASS_CAUSTICS
    }

    enum Preset {
        CAMERA_ABSORPTION("1", "相机吸收与发光", Route.CAMERA_ABSORPTION, new Rig(0, 2.0, 9, 0, 1, 0)),
        BOUNDARY_DENSITY("2", "边界密度雾舱", Route.BOUNDARY_DENSITY, new Rig(-1.1, 2.8, 8.5, 0, 1, 0)),
        CAMERA_SINGLE_SCATTER("3", "均匀相机单次散射", Route.CAMERA_SINGLE_SCATTER, new Rig(1.1, 2.4, 8, 0, 1, 0)),
        CAMERA_MULTIPLE_SCATTER("4", "均匀相机多次散射", Route.CAMERA_MULTIPLE_SCATTER, new Rig(0, 3.2, 8.5, 0, 1, 0)),
        HETEROGENEOUS_SINGLE_SCATTER("5", "异质密度单次散射", Route.HETEROGENEOUS_SINGLE_SCATTER, new Rig(-1.2, 2.3, 7.8, 0, 1, 0)),
        HETEROGENEOUS_MULTIPLE_SCATTER("6", "异质密度多次散射", Route.HETEROGENEOUS_MULTIPLE_SCATTER, new Rig(1.2, 2.7, 8.2, 0, 1, 0)),
        BOUNDARY_SCATTER("7", "体积边界散射", Route.BOUNDARY_SCATTER, new Rig(0, 2.4, 7.6, 0, 1, 0)),
        PBR_SINGLE_SIDED("8", "非金属 PBR 单面", Route.PBR_SINGLE_SIDED, new Rig(0, 1.8, 8, 0, 1, 0)),
        PBR_DOUBLE_SIDED("9", "非金属 PBR 双面", Route.PBR_DOUBLE_SIDED, new Rig(0, 1.8, 8, 0, 1, 0)),
        OPTICAL_SMOOTH_NESTED("0", "平滑嵌套光学介质", Route.OPTICAL_SMOOTH_NESTED, new Rig(-.5, 2.1, 7.5, 0, 1, 0)),
        OPTICAL_ROUGH_NESTED("Q", "粗糙嵌套光学介质", Route.OPTICAL_ROUGH_NESTED, new Rig(.5, 2.4, 8, 0, 1, 0)),
        SCATTER_GLASS_CAUSTICS("W", "散射雾中的折射玻璃焦散", Route.SCATTER_GLASS_CAUSTICS,
                new Rig(0, 2.5, 8.4, 0, 1.15, 0));

        private final String key;
        private final String label;
        private final Route route;
        private final Rig rig;

        Preset(String key, String label, Route route, Rig rig) {
            this.key = key;
            this.label = label;
            this.route = route;
            this.rig = rig;
        }

        String key() { return key; }
        String label() { return label; }
        Route route() { return route; }
        Rig rig() { return rig; }

        static Preset byKey(String key) {
            return Arrays.stream(values()).filter(value -> value.key.equalsIgnoreCase(key))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown preset key: " + key));
        }

        static Preset next(Preset value, int direction) {
            Preset[] all = values();
            return all[Math.floorMod(value.ordinal() + direction, all.length)];
        }

        static Fixture fixture(Preset preset, long revision, long sequence, int width, int height,
                double seconds, double deltaSeconds) {
            return Fixtures.build(preset, revision, sequence, width, height, seconds, deltaSeconds);
        }
    }

    record Fixture(Preset preset, Route route, SceneTransaction scene, EnvironmentState environment,
            MediumTransport transport, FramePrimitiveBatch batch, Medium cameraMedium, MediumBoundary boundary,
            int scatteringBounces, boolean heterogeneous, boolean pbrSurface, boolean doubleSided,
            boolean optical, boolean causticsEnabled) {
        RenderFrameRequest frame(long sequence, int width, int height, double seconds, double delta, Rig rig) {
            return RenderFrameRequest.builder(sequence, width, height, rig.camera(width, height))
                    .clock(FrameClock.of(Math.max(0, (long) (seconds * 1e9)), Math.max(0, (long) (delta * 1e9))))
                    .minimumSceneRevision(scene.revision()).antiAliasing(new AntiAliasingState(1))
                    .environment(environment).mediumTransport(transport).primitiveBatch(batch)
                    .caustics(causticsEnabled ? SCATTER_GLASS_CAUSTICS : Caustics.disabled()).build();
        }
    }

    record Rig(double ex, double ey, double ez, double tx, double ty, double tz) {
        CameraState camera(int width, int height) {
            return CameraState.lookAt(ex, ey, ez, tx, ty, tz).verticalFieldOfViewDegrees(43)
                    .aspectRatio(width / (double) height).build();
        }
        Rig mix(Rig target, double amount) {
            double t = amount * amount * (3 - 2 * amount);
            return new Rig(lerp(ex, target.ex, t), lerp(ey, target.ey, t), lerp(ez, target.ez, t),
                    lerp(tx, target.tx, t), lerp(ty, target.ty, t), lerp(tz, target.tz, t));
        }
        private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    }

    static final class Controls {
        private final AtomicInteger selected;
        private final JFrame frame = new JFrame("RTRendererAPI 7.0.0 | 介质认证控制台");
        private final JLabel metrics = new JLabel("GPU 完成计时等待样本…");
        private final JLabel active = new JLabel("当前场景：初始化中");

        Controls(AtomicInteger selected) {
            this.selected = selected;
            build();
        }

        private void build() {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout(8, 8));
            var routes = new JComboBox<>(Preset.values());
            routes.setSelectedIndex(selected.get());
            routes.addActionListener(event -> selected.set(routes.getSelectedIndex()));
            var buttons = new JPanel(new GridLayout(1, 2, 6, 0));
            var previous = new JButton("上一场  ←");
            var next = new JButton("下一场  →");
            previous.addActionListener(event -> choose(-1, routes));
            next.addActionListener(event -> choose(1, routes));
            buttons.add(previous);
            buttons.add(next);
            var capabilities = new JTextArea(capabilityBoard());
            capabilities.setEditable(false);
            capabilities.setLineWrap(true);
            capabilities.setWrapStyleWord(true);
            capabilities.setRows(19);
            capabilities.setBackground(frame.getBackground());
            var selector = new JPanel(new BorderLayout(6, 6));
            selector.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
            selector.add(routes, BorderLayout.NORTH);
            selector.add(buttons, BorderLayout.CENTER);
            selector.add(active, BorderLayout.SOUTH);
            frame.add(selector, BorderLayout.NORTH);
            frame.add(new JScrollPane(capabilities), BorderLayout.CENTER);
            var footer = new JPanel(new GridLayout(2, 1));
            footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
            footer.add(metrics);
            footer.add(new JLabel("数字键选择场景；Q 粗糙嵌套；W 散射 + 玻璃 + 焦散；FG 不计入渲染 FPS。"));
            frame.add(footer, BorderLayout.SOUTH);
            bindKeys(routes);
            frame.setPreferredSize(new Dimension(450, 560));
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        }

        private void choose(int direction, JComboBox<Preset> routes) {
            int next = Math.floorMod(selected.get() + direction, Preset.values().length);
            routes.setSelectedIndex(next);
        }

        private void bindKeys(JComboBox<Preset> routes) {
            var input = frame.getRootPane().getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
            var actions = frame.getRootPane().getActionMap();
            for (Preset preset : Preset.values()) {
                int code = preset.key.equals("Q") ? KeyEvent.VK_Q : KeyEvent.getExtendedKeyCodeForChar(preset.key.charAt(0));
                String action = "preset-" + preset.ordinal();
                input.put(KeyStroke.getKeyStroke(code, 0), action);
                actions.put(action, new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent event) { routes.setSelectedIndex(preset.ordinal()); }
                });
            }
            input.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "preset-next");
            input.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "preset-previous");
            actions.put("preset-next", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { choose(1, routes); }
            });
            actions.put("preset-previous", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { choose(-1, routes); }
            });
        }

        void update(Preset preset, String text) {
            SwingUtilities.invokeLater(() -> {
                if (!frame.isDisplayable()) return;
                active.setText("当前场景：" + preset.label + " | 路由候选：" + preset.route);
                metrics.setText(text);
            });
        }

        void close() { SwingUtilities.invokeLater(frame::dispose); }
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("介质认证演示：每个 preset 配置隔离或集成介质验收场景。启动需 Vulkan/CUDA GPU。\n"
                    + "快捷键 1-9、0、Q、W 选择；←/→ 或控制台按钮轮播。FG 关闭，FPS 来自 GPU 完成 timestamp。\n"
                    + "--width=1280 --height=720 --spp=1 --preset=CAMERA_ABSORPTION");
            return;
        }
        Map<String, String> options = parseOptions(args);
        int width = intOption(options, "width", DEFAULT_WIDTH, 320, 8192);
        int height = intOption(options, "height", DEFAULT_HEIGHT, 180, 8192);
        int spp = intOption(options, "spp", 1, 1, 8);
        if (spp != 1 && spp != 2 && spp != 4 && spp != 8)
            throw new IllegalArgumentException("spp must be one of 1, 2, 4 or 8");
        Preset initial = presetOption(options);
        var selected = new AtomicInteger(initial.ordinal());
        final Controls[] controls = new Controls[1];
        SwingUtilities.invokeAndWait(() -> controls[0] = new Controls(selected));
        try {
            run(width, height, spp, selected, controls[0]);
        } finally {
            controls[0].close();
        }
    }

    private static void run(int width, int height, int spp, AtomicInteger selected, Controls controls) {
        var configuration = RendererConfig.recommended().copyBuilder()
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .maxFramesInFlight(3).gpuTimingsEnabled(true).cpuFrameReadbackEnabled(false)
                .frameGeneration(FrameGenerationOptions.disabled()).frameReconstruction(FrameReconstructionOptions.disabled())
                .denoising(top.ceroxe.rt.renderer.api.DenoisingOptions.disabled())
                .temporalRendering(TemporalRenderingOptions.disabled()).build();
        try (Renderer renderer = Renderer.open("vulkan-rt", configuration);
             VulkanFramePresenter presenter = VulkanFramePresenter.open(renderer, VulkanFramePresenterConfig.builder()
                     .title("RTRendererAPI 7 | 光学实验馆 · 微型星系")
                     .initialExtent(width, height).windowMode(VulkanFramePresenterConfig.WindowMode.WINDOWED)
                     .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                     .maximumFramesQueuedAhead(MAX_QUEUED).build())) {
            long revision = 1;
            Preset active = Preset.values()[selected.get()];
            Fixture fixture = Preset.fixture(active, revision, 0, width, height, 0, 0);
            renderer.apply(fixture.scene);
            long sequence = 0, submitted = 0, retired = 0, presented = 0;
            long start = System.nanoTime(), lastReport = start, priorSamples = 0;
            long previousFrameNanos = start;
            double transitionStart = -1;
            Rig transitionFrom = active.rig;
            Rig transitionTo = active.rig;
            boolean stopping = false;
            while (!stopping) {
                presenter.pollEvents();
                var window = presenter.windowState();
                if (window.closeRequested()) break;
                long now = System.nanoTime();
                int requested = selected.get();
                if (requested != active.ordinal()) {
                    transitionFrom = rigAt(transitionFrom, transitionTo, transitionStart, now);
                    active = Preset.values()[requested];
                    fixture = Preset.fixture(active, ++revision, sequence, Math.max(width, 1), Math.max(height, 1),
                            (now - start) / 1e9, 0);
                    renderer.apply(fixture.scene);
                    transitionTo = active.rig;
                    transitionStart = now / 1e9;
                }
                if (!window.drawable()) {
                    var minimizedPresent = presenter.presentLatestFrame();
                    if (minimizedPresent.isPresent()) retired++;
                    LockSupport.parkNanos(1_000_000);
                    continue;
                }
                if (submitted - retired < MAX_QUEUED) {
                    double seconds = (now - start) / 1e9;
                    Rig rig = transitionStart < 0 ? active.rig : rigAt(transitionFrom, transitionTo, transitionStart, now);
                    var request = fixture.frame(sequence, window.framebufferWidth(), window.framebufferHeight(),
                            seconds, Math.max(0, (now - previousFrameNanos) / 1e9), rig);
                    if (spp != 1) request = request.toBuilder().antiAliasing(new AntiAliasingState(spp)).build();
                    if (renderer.trySubmit(request) instanceof Renderer.FrameSubmitted) {
                        submitted++;
                        previousFrameNanos = now;
                    }
                    sequence++;
                }
                var presentation = presenter.presentLatestFrame();
                if (presentation.isPresent()) {
                    retired++;
                    if (presentation.orElseThrow().outcome() == VulkanFramePresenter.Outcome.PRESENTED) presented++;
                } else LockSupport.parkNanos(100_000);
                if (now - lastReport >= 1_000_000_000L) {
                    var timing = renderer.diagnostics().frameGpuTiming();
                    long completed = timing.completedSamples();
                    double elapsed = (now - lastReport) / 1e9;
                    double gpuFps = (completed - priorSamples) / elapsed;
                    String mean = timing.enabled() && timing.completedSamples() > 0
                            ? String.format(Locale.ROOT, "%.2f ms", timing.averageTotalNanos() / 1e6) : "等待 GPU 样本";
                    String metrics = String.format(Locale.ROOT,
                            "GPU completed %.1f fps | GPU 整帧均值 %s | 已提交 %d / 已消费 %d / 已呈现 %d",
                            gpuFps, mean, submitted, retired, presented);
                    presenter.setOverlayText("光学实验馆 | " + active.label + "\n"
                            + "GPU 完成帧率 " + String.format(Locale.ROOT, "%.1f fps", gpuFps)
                            + " | GPU 整帧均值 " + mean + "\n"
                            + active.route + " | FG 已关闭；呈现输出不计入渲染 FPS");
                    controls.update(active, metrics);
                    priorSamples = completed;
                    lastReport = now;
                }
            }
            System.out.println("MediumCertificationDemo presentation=" + presenter.performanceSnapshot());
        } catch (RuntimeException failure) {
            controls.update(Preset.values()[selected.get()], "运行失败：" + failure.getMessage());
            throw failure;
        }
    }

    private static Rig rigAt(Rig from, Rig to, double transitionStart, long nowNanos) {
        if (transitionStart < 0) return to;
        double amount = Math.clamp((nowNanos / 1e9 - transitionStart) / .8, 0, 1);
        return from.mix(to, amount);
    }

    private static String capabilityBoard() {
        return "当前支持的隔离验收场景\n"
                + "✓ 相机均匀吸收与发光\n✓ IOR=1 体积边界吸收、发光与密度图\n"
                + "✓ 均匀相机介质单次/多次散射\n✓ 异质相机介质单次/多次散射\n"
                + "✓ IOR=1 散射体积边界\n✓ 非金属 opaque PBR，单面/双面\n"
                + "✓ 无 caustics 的平滑/粗糙嵌套光学介质\n\n"
                + "集成验收候选（W）\n"
                + "散射雾、IOR-changing 折射玻璃和表面焦散配置在同一帧；这是 GPU 接线与图像验收场景，不代表该组合已通过。\n\n"
                + "仍待 GPU 图像验收的能力\n"
                + "… W 场景的 focused surface caustics 与联合输运\n"
                + "… 金属 secondary reflection 的介质段\n… 透射 PBR 与介质散射组合\n\n"
                + "画面切换采用完整 preset 与相机平滑机位过渡；互斥路由不会合并到同一帧。\n"
                + "GPU completed FPS 由完成的 GPU timestamp 样本计数计算；FG 已禁用。";
    }

    static Map<String, String> parseOptions(String[] args) {
        Set<String> supported = Set.of("width", "height", "spp", "preset");
        var options = new LinkedHashMap<String, String>();
        for (String arg : args) {
            if (arg.equals("--help")) continue;
            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 3 || separator == arg.length() - 1) {
                throw new IllegalArgumentException("expected --name=value: " + arg);
            }
            String name = arg.substring(2, separator);
            if (!supported.contains(name)) throw new IllegalArgumentException("unknown argument: " + name);
            if (options.putIfAbsent(name, arg.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("duplicate option " + name);
            }
        }
        return Map.copyOf(options);
    }

    private static Preset presetOption(Map<String, String> options) {
        String name = option(options, "preset", Preset.CAMERA_ABSORPTION.name());
        try { return Preset.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("unknown preset: " + name, invalid); }
    }

    private static int intOption(Map<String, String> options, String key, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(option(options, key, Integer.toString(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " must be within " + minimum + ".." + maximum);
        return value;
    }

    private static String option(Map<String, String> options, String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    private static final class Fixtures {
        private static final int DENSITY_TEXTURE = 90;
        private static final int DENSITY_SIZE = 12;
        private static final AffineTransform LOCAL_TO_UVW = new AffineTransform(new float[]{.5f, 0, 0, .5f,
                0, .5f, 0, .5f, 0, 0, .5f, .5f});

        private Fixtures() { }

        static Fixture build(Preset preset, long revision, long sequence, int width, int height, double seconds, double delta) {
            var tx = SceneTransaction.builder(revision).resetScene();
            boolean integrated = preset.route == Route.SCATTER_GLASS_CAUSTICS;
            boolean pbr = preset.route == Route.PBR_SINGLE_SIDED || preset.route == Route.PBR_DOUBLE_SIDED || integrated;
            boolean singleSided = preset.route == Route.PBR_SINGLE_SIDED;
            boolean optical = preset.route == Route.OPTICAL_SMOOTH_NESTED || preset.route == Route.OPTICAL_ROUGH_NESTED;
            boolean glassGeometry = optical || integrated;
            float glassRoughness = preset.route == Route.OPTICAL_SMOOTH_NESTED || integrated ? .015f : .28f;
            tx.upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT)
                    .baseColorRgba8(0xff275d91).emissive(0xff31b8ff, .35f).doubleSided(true).build());
            tx.upsert(MaterialAsset.builder(2).shadingModel(MaterialAsset.ShadingModel.UNLIT)
                    .baseColorRgba8(0xff381d65).emissive(0xffffa637, 1.2f).doubleSided(true).build());
            tx.upsert(MaterialAsset.builder(3).shadingModel(pbr ? MaterialAsset.ShadingModel.PHYSICALLY_BASED
                            : MaterialAsset.ShadingModel.UNLIT)
                    .baseColorRgba8(0xffcaa66a).roughness(.42f).metallic(0).doubleSided(!singleSided).build());
            tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xffd9efff).roughness(glassRoughness)
                    .transmission(1).indexOfRefraction(1.48f).doubleSided(true).build());
            tx.upsert(MeshAsset.triangles(1, new float[]{-6,-3,-2, 6,-3,-2, 6,4,-2, -6,4,-2},
                    new int[]{0,1,2,0,2,3}, 3));
            tx.upsert(boxMesh(2, 1));
            tx.upsert(sphereMesh(3, glassGeometry ? 4 : 1, 28, 18));
            tx.upsert(torusMesh(4, 2, 36, 12));
            tx.upsert(SceneInstance.builder(1, 1).build());
            tx.upsert(SceneInstance.builder(2, 4).transform(scaleTranslate(1.3f, 0, 1.25f, 0)).build());
            if (integrated) {
                tx.upsert(SceneInstance.builder(3, 3).transform(scaleTranslate(.98f, 0, 1.15f, 0)).build());
            }
            tx.upsert(SceneLight.point(1, -3, 4, 4).color(1, .56f, .29f).intensity(80).range(24).build());
            tx.upsert(SceneLight.point(2, 3, 2, 2).color(.18f, .52f, 1).intensity(55).range(20).build());
            tx.upsert(SceneLight.spot(3, 0, 5, 1, 0, -1, -.2f).color(.65f, .78f, 1).intensity(95)
                    .range(22).coneDegrees(18, 38).build());
            var vacuum = Medium.vacuum();
            Medium camera = vacuum;
            MediumBoundary boundary = null;
            FramePrimitiveBatch batch = FramePrimitiveBatch.empty();
            int bounces = 0;
            boolean heterogeneous = false;
            switch (preset.route) {
                case CAMERA_ABSORPTION -> camera = medium(new Rgb(.12f, .035f, .012f), Rgb.ZERO,
                        new Rgb(.08f, .022f, .008f), 1, 0, MediumDensityField.uniform());
                case BOUNDARY_DENSITY -> {
                    tx.upsert(densityTexture());
                    camera = medium(new Rgb(.008f, .012f, .018f), Rgb.ZERO, Rgb.ZERO, 1, 0, MediumDensityField.uniform());
                    boundary = new MediumBoundary(medium(new Rgb(.05f, .24f, .38f), Rgb.ZERO,
                            new Rgb(.02f, .08f, .19f), 1, 0, MediumDensityField.grid(DENSITY_TEXTURE, DENSITY_SIZE, LOCAL_TO_UVW)),
                            3, MediumBoundary.Orientation.OUTWARD, false);
                    tx.upsert(SceneInstance.builder(20, 2).transform(scaleTranslate(2.15f, 0, 1, 0)).mediumBoundary(boundary).build());
                }
                case CAMERA_SINGLE_SCATTER, CAMERA_MULTIPLE_SCATTER,
                        HETEROGENEOUS_SINGLE_SCATTER, HETEROGENEOUS_MULTIPLE_SCATTER -> {
                    heterogeneous = preset.route == Route.HETEROGENEOUS_SINGLE_SCATTER
                            || preset.route == Route.HETEROGENEOUS_MULTIPLE_SCATTER;
                    boolean multiple = preset.route == Route.CAMERA_MULTIPLE_SCATTER
                            || preset.route == Route.HETEROGENEOUS_MULTIPLE_SCATTER;
                    bounces = multiple ? 2 : 0;
                    MediumDensityField field = MediumDensityField.uniform();
                    if (heterogeneous) {
                        tx.upsert(densityTexture());
                        field = MediumDensityField.grid(DENSITY_TEXTURE, DENSITY_SIZE, worldToUVW());
                    }
                    camera = medium(new Rgb(.012f, .02f, .045f), new Rgb(.24f, .36f, .52f),
                            new Rgb(.008f, .018f, .05f), 1, .62f, field);
                }
                case BOUNDARY_SCATTER -> {
                    boundary = new MediumBoundary(medium(new Rgb(.02f, .07f, .13f), new Rgb(.3f, .42f, .58f),
                            new Rgb(.01f, .025f, .07f), 1, .7f, MediumDensityField.uniform()),
                            4, MediumBoundary.Orientation.OUTWARD, false);
                    batch = FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(2)
                            .transform(scaleTranslate(2.15f, 0, 1, 0)).mediumBoundary(boundary).build()));
                }
                case SCATTER_GLASS_CAUSTICS -> {
                    camera = medium(new Rgb(.02f, .03f, .055f), new Rgb(.22f, .31f, .44f),
                            new Rgb(.003f, .008f, .018f), 1, .58f, MediumDensityField.uniform());
                    bounces = 1;
                }
                case PBR_SINGLE_SIDED, PBR_DOUBLE_SIDED ->
                    camera = medium(new Rgb(.035f, .012f, .004f), Rgb.ZERO,
                            new Rgb(.008f, .003f, .001f), 1, 0, MediumDensityField.uniform());
                case OPTICAL_SMOOTH_NESTED, OPTICAL_ROUGH_NESTED -> {
                    var outer = PrimitiveInstance.builder(3).transform(scaleTranslate(1.65f, -1.3f, 1.15f, 0))
                            .mediumBoundary(opticalBoundary(.13f, .024f, .008f, 1.48f, 2)).build();
                    var inner = PrimitiveInstance.builder(3).transform(scaleTranslate(.9f, -1.3f, 1.15f, 0))
                            .mediumBoundary(opticalBoundary(.015f, .045f, .12f, 1.31f, 5)).build();
                    batch = FramePrimitiveBatch.of(List.of(outer, inner));
                }
                default -> throw new AssertionError(preset.route);
            }
            int steps = 12;
            var transport = new MediumTransport(20, 1, steps, 4, 60, 0, bounces);
            var environment = EnvironmentState.builder().skyRadiance(.055f, .095f, .18f).ambientIntensity(.24f)
                    .sunDirection(-.24f, -.87f, -.43f).sunRadiance(.9f, .82f, .68f).sunIntensity(1.2f)
                    .cameraMedium(camera).build();
            return new Fixture(preset, preset.route, tx.build(), environment, transport, batch, camera, boundary, bounces,
                    heterogeneous, pbr, !singleSided, optical, integrated);
        }

        private static Medium medium(Rgb absorption, Rgb scattering, Rgb emission,
                float density, float anisotropy, MediumDensityField field) {
            return new Medium(absorption, scattering, emission, density, 1, anisotropy, field);
        }

        private static MediumBoundary opticalBoundary(float r, float g, float b, float ior, int priority) {
            return new MediumBoundary(new Medium(new Rgb(r, g, b), Rgb.ZERO, Rgb.ZERO, 1, ior, 0),
                    priority, MediumBoundary.Orientation.OUTWARD, true);
        }

        private static TextureAsset densityTexture() {
            byte[] rgba = new byte[DENSITY_SIZE * DENSITY_SIZE * DENSITY_SIZE * 4];
            for (int z = 0; z < DENSITY_SIZE; z++) for (int y = 0; y < DENSITY_SIZE; y++)
                for (int x = 0; x < DENSITY_SIZE; x++) {
                    double dx = (x + .5) / DENSITY_SIZE - .5, dy = (y + .5) / DENSITY_SIZE - .5;
                    double dz = (z + .5) / DENSITY_SIZE - .5;
                    double cloud = Math.exp(-(dx * dx * 9 + dy * dy * 5 + dz * dz * 8));
                    int at = ((z * DENSITY_SIZE + y) * DENSITY_SIZE + x) * 4;
                    rgba[at] = (byte) Math.round(32 + 215 * cloud);
                    rgba[at + 1] = rgba[at + 2] = (byte) 128;
                    rgba[at + 3] = (byte) 255;
                }
            return TextureAsset.builder(DENSITY_TEXTURE, DENSITY_SIZE, DENSITY_SIZE * DENSITY_SIZE)
                    .colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
        }

        private static AffineTransform worldToUVW() {
            return new AffineTransform(new float[]{.09f, 0, 0, .5f, 0, .12f, 0, .25f, 0, 0, .09f, .5f});
        }

        private static MeshAsset boxMesh(long id, long material) {
            float[] p = {-1,-1,-1, 1,-1,-1, 1,1,-1, -1,1,-1, -1,-1,1, 1,-1,1, 1,1,1, -1,1,1};
            int[] indices = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
            return MeshAsset.triangles(id, p, indices, material);
        }

        private static MeshAsset sphereMesh(long id, long material, int segments, int stacks) {
            int count = 2 + (stacks - 1) * segments;
            float[] p = new float[count * 3]; p[1] = 1;
            for (int ring = 1; ring < stacks; ring++) for (int i = 0; i < segments; i++) {
                double theta = Math.PI * ring / stacks, phi = 2 * Math.PI * i / segments;
                int at = (1 + (ring - 1) * segments + i) * 3;
                p[at] = (float) (Math.sin(theta) * Math.cos(phi));
                p[at + 1] = (float) Math.cos(theta); p[at + 2] = (float) (Math.sin(theta) * Math.sin(phi));
            }
            int bottom = count - 1; p[bottom * 3 + 1] = -1;
            var indices = new java.util.ArrayList<Integer>();
            for (int i = 0; i < segments; i++) {
                int a = 1 + i, b = 1 + (i + 1) % segments;
                add(indices, 0, b, a);
                for (int ring = 0; ring < stacks - 2; ring++) {
                    int c = a + ring * segments, d = b + ring * segments;
                    add(indices, c, d, d + segments, c, d + segments, c + segments);
                }
                add(indices, bottom, a + (stacks - 2) * segments, b + (stacks - 2) * segments);
            }
            return MeshAsset.triangles(id, p, indices.stream().mapToInt(Integer::intValue).toArray(), material);
        }

        private static MeshAsset torusMesh(long id, long material, int around, int tube) {
            float[] p = new float[around * tube * 3];
            int[] indices = new int[around * tube * 6];
            for (int u = 0; u < around; u++) for (int v = 0; v < tube; v++) {
                double a = u * Math.PI * 2 / around, b = v * Math.PI * 2 / tube;
                int at = (u * tube + v) * 3;
                p[at] = (float) ((1.5 + .34 * Math.cos(b)) * Math.cos(a));
                p[at + 1] = (float) ((1.5 + .34 * Math.cos(b)) * Math.sin(a));
                p[at + 2] = (float) (.34 * Math.sin(b));
                int n = (u * tube + v) * 6;
                indices[n] = u * tube + v;
                indices[n + 1] = ((u + 1) % around) * tube + v;
                indices[n + 2] = u * tube + (v + 1) % tube;
                indices[n + 3] = indices[n + 1];
                indices[n + 4] = ((u + 1) % around) * tube + (v + 1) % tube;
                indices[n + 5] = indices[n + 2];
            }
            return MeshAsset.triangles(id, p, indices, material);
        }

        private static void add(List<Integer> values, int... entries) { for (int value : entries) values.add(value); }
        private static AffineTransform scaleTranslate(float scale, float x, float y, float z) {
            return new AffineTransform(new float[]{scale,0,0,x, 0,scale,0,y, 0,0,scale,z});
        }
    }
}
