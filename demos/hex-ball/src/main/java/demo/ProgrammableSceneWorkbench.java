package demo;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.Renderer;

/** Interactive shader, immutable-buffer and nanosecond-timeline laboratory. */
final class ProgrammableSceneWorkbench {
    private static final String DEFAULT_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
            #include "gpuscene_common.glsl"
            layout(set=0,binding=1,rgba16f) uniform writeonly image2D outputImage;
            layout(set=0,binding=33,std430) readonly buffer Parameters { vec4 radiance; } parameters;
            void main() {
                vec2 uv = (vec2(gl_LaunchIDEXT.xy) + 0.5) / vec2(gl_LaunchSizeEXT.xy);
                float pulse = 0.5 + 0.5 * sin(gsFrameSecondsSince(uint64_t(0)) * 3.0);
                imageStore(outputImage, ivec2(gl_LaunchIDEXT.xy),
                    vec4(parameters.radiance.rgb * vec3(uv, pulse), 1));
            }
            """;

    private ProgrammableSceneWorkbench() { }

    static void open() {
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame("RTRendererAPI — Programmable scene / HDR / timeline");
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JLabel image = new JLabel();
            JTextArea status = new JTextArea(4, 75); status.setEditable(false);
            JTextArea raygen = new JTextArea(DEFAULT_SHADER, 18, 70);
            JTextArea miss = new JTextArea(), hit = new JTextArea(), anyHit = new JTextArea();
            JTabbedPane editors = new JTabbedPane();
            editors.addTab("Ray generation", new JScrollPane(raygen)); editors.addTab("Miss", new JScrollPane(miss));
            editors.addTab("Closest hit", new JScrollPane(hit)); editors.addTab("Any hit", new JScrollPane(anyHit));
            JCheckBox paused = new JCheckBox("Pause");
            JSlider seek = new JSlider(0, 10000, 0);
            JSpinner speed = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 8.0, 0.25));
            JSpinner exposure = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 16.0, 0.25));
            JTextField values = new JTextField("4,2,0.5,1"); JTextField offset = new JTextField("0");
            JButton patch = new JButton("Update float range"), compile = new JButton("Compile and apply stages");
            JPanel controls = new JPanel(new GridLayout(0, 2, 6, 4));
            controls.add(paused); controls.add(new JLabel("Preview uses exposure tone mapping; native bytes stay HDR"));
            controls.add(new JLabel("Seek milliseconds (0..10000)")); controls.add(seek);
            controls.add(new JLabel("Timeline speed")); controls.add(speed);
            controls.add(new JLabel("Preview exposure")); controls.add(exposure);
            controls.add(new JLabel("Byte offset into 64 KiB immutable data")); controls.add(offset);
            controls.add(new JLabel("Float values, comma separated")); controls.add(values);
            controls.add(patch); controls.add(compile);
            var data = new AtomicReference<>(initialData());
            var shader = new AtomicReference<>(program(DEFAULT_SHADER, "", "", ""));
            var timeline = new AtomicReference<>(new Timeline(false, 1, 0, 0));
            var displayExposure = new AtomicReference<>(1.0);
            AtomicBoolean running = new AtomicBoolean(true);
            Runnable updateTimeline = () -> timeline.updateAndGet(old -> new Timeline(paused.isSelected(),
                    ((Number) speed.getValue()).doubleValue(), old.seekNanos, old.revision));
            paused.addActionListener(event -> updateTimeline.run()); speed.addChangeListener(event -> updateTimeline.run());
            exposure.addChangeListener(event -> displayExposure.set(((Number) exposure.getValue()).doubleValue()));
            seek.addChangeListener(event -> { if (!seek.getValueIsAdjusting()) timeline.updateAndGet(old ->
                    new Timeline(old.paused, old.speed, seek.getValue() * 1_000_000L, old.revision + 1)); });
            patch.addActionListener(event -> {
                try {
                    String[] components = values.getText().split(",");
                    ByteBuffer bytes = ByteBuffer.allocate(components.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (String component : components) bytes.putFloat(Float.parseFloat(component.trim()));
                    var update = new ResourceData.Update(Integer.parseInt(offset.getText()), new ResourceData(bytes.flip()));
                    data.updateAndGet(old -> old.withUpdates(List.of(update)));
                } catch (RuntimeException failure) { status.setText(failure.toString()); }
            });
            compile.addActionListener(event -> {
                try { shader.set(program(raygen.getText(), miss.getText(), hit.getText(), anyHit.getText())); }
                catch (RuntimeException failure) { status.setText(failure.toString()); }
            });
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { running.set(false); }
            });
            JPanel top = new JPanel(new BorderLayout()); top.add(image, BorderLayout.WEST); top.add(controls, BorderLayout.CENTER);
            window.add(top, BorderLayout.NORTH); window.add(editors, BorderLayout.CENTER); window.add(new JScrollPane(status), BorderLayout.SOUTH);
            window.pack(); window.setLocationRelativeTo(null); window.setVisible(true);
            Thread.ofPlatform().name("programmable-scene").start(() -> render(running, shader, data, timeline, displayExposure, image, status));
        });
    }

    private record Timeline(boolean paused, double speed, long seekNanos, long revision) { }

    private static SceneShaderProgram program(String raygen, String miss, String hit, String anyHit) {
        var builder = SceneShaderProgram.builder().dataBindingCount(1).shader(ShaderStage.RAY_GENERATION, raygen);
        if (!miss.isBlank()) builder.shader(ShaderStage.RAY_MISS, miss);
        if (!hit.isBlank()) builder.shader(ShaderStage.RAY_CLOSEST_HIT, hit);
        if (!anyHit.isBlank()) builder.shader(ShaderStage.RAY_ANY_HIT, anyHit);
        return builder.build();
    }

    private static ResourceData initialData() {
        ByteBuffer bytes = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putFloat(4).putFloat(2).putFloat(0.5f).putFloat(1).position(0);
        return new ResourceData(bytes);
    }

    private static void render(AtomicBoolean running, AtomicReference<SceneShaderProgram> shaders,
            AtomicReference<ResourceData> data, AtomicReference<Timeline> controls, AtomicReference<Double> exposure,
            JLabel image, JTextArea status) {
        long time = 0, appliedSeek = -1;
        SceneShaderProgram rejected = null;
        while (running.get()) {
            SceneShaderProgram selected = shaders.get();
            if (selected == rejected) { java.util.concurrent.locks.LockSupport.parkNanos(20_000_000); continue; }
            var config = RendererConfig.builder().sceneShaders(selected).temporalRendering(TemporalRenderingOptions.disabled())
                    .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).build();
            try (Renderer renderer = Renderer.open(config)) {
                long revision = renderer.apply(SceneTransaction.empty(0)).acceptedSceneRevision();
                var camera = CameraState.lookAt(0, 0, 5, 0, 0, 0).aspectRatio(16.0 / 9).build();
                long previous = System.nanoTime(), sequence = 0;
                while (running.get() && shaders.get() == selected) {
                    long now = System.nanoTime(); Timeline policy = controls.get();
                    long delta = policy.paused ? 0 : (long) ((now - previous) * policy.speed); previous = now;
                    if (appliedSeek != policy.revision) { time = policy.seekNanos; appliedSeek = policy.revision; delta = 0; }
                    else time += delta;
                    var request = RenderFrameRequest.builder(sequence++, 320, 180, camera).minimumSceneRevision(revision)
                            .clock(FrameClock.of(time, delta)).shaderData(List.of(data.get())).build();
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    while (renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred) {
                        if (!running.get()) return;
                        if (System.nanoTime() > deadline) throw new IllegalStateException("admission timeout");
                        java.util.concurrent.locks.LockSupport.parkNanos(100000);
                    }
                    FrameSnapshot frame = renderer.awaitFrame(Duration.ofSeconds(15)).orElseThrow();
                    BufferedImage preview = preview(frame, exposure.get());
                    String evidence = "Native: " + frame.format() + ", " + frame.byteCount() + " bytes; time=" + time
                            + " ns; delta=" + delta + " ns\n" + renderer.health();
                    SwingUtilities.invokeLater(() -> { image.setIcon(new ImageIcon(preview)); status.setText(evidence); });
                    java.util.concurrent.locks.LockSupport.parkNanos(16000000);
                }
            } catch (Exception failure) {
                rejected = selected;
                SwingUtilities.invokeLater(() -> status.setText("Compile/render failed: " + failure + "\nEdit and apply to retry."));
            }
        }
    }

    private static BufferedImage preview(FrameSnapshot frame, double exposure) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        ByteBuffer bytes = frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            int color = 0;
            for (int channel = 0; channel < 3; channel++) {
                float linear = Float.float16ToFloat(bytes.getShort((y * frame.width() + x) * 8 + channel * 2));
                double mapped = Math.max(0, linear * exposure); mapped = mapped / (1 + mapped);
                color = (color << 8) | (int) Math.round(255 * Math.pow(mapped, 1.0 / 2.2));
            }
            image.setRGB(x, y, color);
        }
        return image;
    }
}
