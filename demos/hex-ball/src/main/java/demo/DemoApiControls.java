package demo;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import top.ceroxe.rt.renderer.api.*;

/** Public-policy editor shared by the graphical workbench and reproducible CLI runs. */
final class DemoApiControls {
    private static final Map<String, String> CONFIG = new LinkedHashMap<>();
    private static final Map<String, String> FRAME = new LinkedHashMap<>();

    private DemoApiControls() { }

    static String[] parse(String[] arguments) {
        CONFIG.clear(); FRAME.clear();
        var rest = new ArrayList<String>();
        for (String argument : arguments) {
            Map<String, String> target = argument.startsWith("--api.") ? CONFIG
                    : argument.startsWith("--frame.") ? FRAME : null;
            if (target == null) { rest.add(argument); continue; }
            int equals = argument.indexOf('=');
            if (equals < 0) throw new IllegalArgumentException("expected key=value: " + argument);
            int prefix = target == CONFIG ? 6 : 8;
            if (target.put(argument.substring(prefix, equals), argument.substring(equals + 1)) != null)
                throw new IllegalArgumentException("duplicate control: " + argument);
        }
        return rest.toArray(String[]::new);
    }

    static RendererConfig configure(RendererConfig config) {
        return (RendererConfig) apply(config, CONFIG);
    }

    static RenderFrameRequest frame(RenderFrameRequest request) {
        return (RenderFrameRequest) apply(request, FRAME);
    }

    private static Object apply(Object source, Map<String, String> values) {
        if (values.isEmpty()) return source;
        try {
            if (source.getClass().isRecord()) return applyRecord(source, values);
            Method copy = source.getClass().getMethod(source instanceof RendererConfig ? "copyBuilder" : "toBuilder");
            Object builder = copy.invoke(source);
            var nested = new LinkedHashMap<String, Map<String, String>>();
            for (var entry : values.entrySet()) {
                int dot = entry.getKey().indexOf('.');
                if (dot < 0) set(builder, entry.getKey(), entry.getValue());
                else nested.computeIfAbsent(entry.getKey().substring(0, dot), key -> new LinkedHashMap<>())
                        .put(entry.getKey().substring(dot + 1), entry.getValue());
            }
            for (var group : nested.entrySet()) {
                Object value = source.getClass().getMethod(group.getKey()).invoke(source);
                Object replacement;
                if (value instanceof TemporalRenderingOptions temporal) {
                    var mode = TemporalRenderingOptions.Mode.valueOf(group.getValue().getOrDefault("mode", temporal.mode().name()));
                    for (String key : group.getValue().keySet())
                        if (!key.equals("mode") && !key.equals("maxHistoryFrames")) throw new IllegalArgumentException("unknown temporal control: " + key);
                    replacement = switch (mode) {
                        case DISABLED -> TemporalRenderingOptions.disabled();
                        case BALANCED -> TemporalRenderingOptions.balanced();
                        case ACCUMULATING -> TemporalRenderingOptions.accumulating(Integer.parseInt(
                                group.getValue().getOrDefault("maxHistoryFrames", "8")));
                    };
                } else {
                    replacement = apply(value, group.getValue());
                }
                builder.getClass().getMethod(group.getKey(), value.getClass()).invoke(builder, replacement);
            }
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("policy failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("unknown or unsupported API control: " + values, failure);
        }
    }

    private static Object applyRecord(Object source, Map<String, String> values) throws ReflectiveOperationException {
        var components = source.getClass().getRecordComponents();
        var arguments = new Object[components.length];
        var types = new Class<?>[components.length];
        var remaining = new LinkedHashMap<>(values);
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            String name = component.getName();
            Object value = component.getAccessor().invoke(source);
            types[i] = component.getType();
            String direct = remaining.remove(name);
            var nested = new LinkedHashMap<String, String>();
            for (String key : List.copyOf(remaining.keySet())) {
                if (key.startsWith(name + ".")) nested.put(key.substring(name.length() + 1), remaining.remove(key));
            }
            if (direct != null && !nested.isEmpty()) throw new IllegalArgumentException("duplicate record control: " + name);
            arguments[i] = direct != null ? convert(types[i], name, direct) : apply(value, nested);
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("unknown record controls: " + remaining.keySet());
        return source.getClass().getConstructor(types).newInstance(arguments);
    }

    private static void set(Object builder, String key, String value) throws ReflectiveOperationException {
        for (Method method : builder.getClass().getMethods()) {
            if (!method.getName().equals(key) || method.getParameterCount() == 0
                    || method.getReturnType() != builder.getClass()) continue;
            String[] parts = value.split(",", -1);
            if (parts.length != method.getParameterCount()) continue;
            Object[] arguments = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) arguments[i] = convert(method.getParameterTypes()[i], key, parts[i].trim());
            method.invoke(builder, arguments); return;
        }
        throw new IllegalArgumentException("unknown API control or wrong component count: " + key);
    }

    private static Object convert(Class<?> type, String key, String value) {
        if (type == Rgb.class) {
            String[] channels = value.split(",", -1);
            if (channels.length != 3) throw new IllegalArgumentException(key + " requires three RGB components");
            return new Rgb(Float.parseFloat(channels[0].trim()), Float.parseFloat(channels[1].trim()),
                    Float.parseFloat(channels[2].trim()));
        }
        if (type.isEnum()) return Arrays.stream(type.getEnumConstants())
                .filter(item -> ((Enum<?>) item).name().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid " + key + ": " + value));
        if (type == boolean.class) {
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("expected true/false: " + key);
            return Boolean.parseBoolean(value);
        }
        if (type == int.class) return Integer.parseInt(value);
        if (type == long.class) return Long.parseLong(value);
        if (type == float.class) return Float.parseFloat(value);
        if (type == double.class) return Double.parseDouble(value);
        throw new IllegalArgumentException("unsupported control type: " + key);
    }

    static void openWorkbench() {
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame("RTRendererAPI " + DemoBuildInfo.version() + " — Feature Workbench");
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            var fields = new LinkedHashMap<String, JComponent>();
            JTabbedPane tabs = new JTabbedPane();
            JPanel policies = new JPanel(new GridLayout(0, 2, 8, 6));
            RendererConfig defaults = RendererConfig.presentation();
            addEditors(policies, fields, "--api.", defaults, defaults.copyBuilder());
            tabs.addTab("Renderer policies", new JScrollPane(policies));
            JTextArea help = new JTextArea("Each option maps directly to the public API. Empty means keep preset.\n"
                    + "FG/MFG: choose preference, mode, multiplier and fallback independently.\n"
                    + "REQUIRED disables fallback; unsupported combinations fail explicitly.\n"
                    + "Apply launches an isolated renderer; close it to retire GPU resources.\n"
                    + "FG/MFG output requires the render window in the Windows foreground.\n"
                    + "The renderer HUD and log report negotiated capabilities and actual output.\n\n"
                    + "Programmable scenes: use --programmable for editable GLSL, HDR radiance,\n"
                    + "timeline pause/seek/rate, partial buffer updates and native readback.\n"
                    + "Frame controls also accept --frame.<policy>.<field>=value.\n"
                    + "Generic pass, compute and procedural RT examples are linked in docs/Java.md.");
            help.setEditable(false); tabs.addTab("Usage", new JScrollPane(help));
            JPanel framePolicies = new JPanel(new GridLayout(0, 2, 8, 6));
            var frame = DemoRendererProfile.frame(0, 1280, 720, DemoRendererProfile.camera(1280, 720, 15.4)).build();
            addEditors(framePolicies, fields, "--frame.", frame, frame.toBuilder());
            tabs.addTab("Frame policies", new JScrollPane(framePolicies));
            JTextArea output = new JTextArea(8, 80); output.setEditable(false);
            JButton launch = new JButton("Apply and launch hex-ball");
            JButton programmable = new JButton("Open programmable scene");
            JButton media = new JButton("Open medium transport laboratory");
            JTextField extent = new JTextField("1280x720", 12);
            JPanel actions = new JPanel(); actions.add(new JLabel("Output extent")); actions.add(extent);
            actions.add(launch); actions.add(programmable); actions.add(media);
            List<Process> children = new ArrayList<>();
            media.addActionListener(event -> spawn(List.of("--media"), output, children));
            Runnable start = () -> {
                try {
                    var args = new ArrayList<String>();
                    String[] dimensions = extent.getText().trim().split("x");
                    if (dimensions.length != 2) throw new IllegalArgumentException("extent must be WIDTHxHEIGHT");
                    args.add("--width=" + Integer.parseInt(dimensions[0])); args.add("--height=" + Integer.parseInt(dimensions[1]));
                    for (var entry : fields.entrySet()) {
                        JComponent field = entry.getValue();
                        String value = field instanceof JComboBox<?> combo ? String.valueOf(combo.getSelectedItem())
                                : ((JTextField) field).getText().trim();
                        if (!value.isEmpty()) args.add(entry.getKey() + "=" + value);
                    }
                    spawn(args, output, children);
                } catch (RuntimeException failure) { output.append(failure + "\n"); }
            };
            launch.addActionListener(event -> start.run());
            programmable.addActionListener(event -> spawn(List.of("--programmable"), output, children));
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) {
                    // Children are independent windows. They own their normal GPU teardown.
                    output.append("Workbench closed; close renderer windows to stop them.\n");
                }
            });
            window.add(tabs, BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout()); south.add(actions, BorderLayout.NORTH);
            south.add(new JScrollPane(output), BorderLayout.CENTER); window.add(south, BorderLayout.SOUTH);
            window.setSize(1000, 800); window.setLocationRelativeTo(null); window.setVisible(true);
        });
    }

    private static void addEditors(JPanel panel, Map<String, JComponent> fields, String prefix, Object value, Object builder) {
        Method[] methods = builder.getClass().getMethods();
        Arrays.sort(methods, java.util.Comparator.comparing(Method::getName));
        for (Method setter : methods) {
            if (setter.getParameterCount() == 0 || setter.getReturnType() != builder.getClass()) continue;
            Class<?> type = setter.getParameterTypes()[0]; String key = prefix + setter.getName();
            if (Arrays.stream(setter.getParameterTypes()).allMatch(t -> t.isEnum() || t.isPrimitive())) {
                JComponent field;
                if (setter.getParameterCount() == 1 && (type.isEnum() || type == boolean.class)) {
                    List<String> choices = new ArrayList<>(List.of(""));
                    if (type.isEnum()) for (Object item : type.getEnumConstants()) choices.add(((Enum<?>) item).name());
                    else choices.addAll(List.of("false", "true"));
                    field = new JComboBox<>(choices.toArray(String[]::new));
                } else {
                    field = new JTextField();
                    field.setToolTipText(setter.getParameterCount() + " numeric components, comma separated");
                }
                try { field.setToolTipText("Preset: " + value.getClass().getMethod(setter.getName()).invoke(value)); }
                catch (ReflectiveOperationException ignored) { field.setToolTipText(key); }
                panel.add(new JLabel(key.substring(key.indexOf('.') + 1))); panel.add(field); fields.put(key, field);
            } else {
                try {
                    Object child = value.getClass().getMethod(setter.getName()).invoke(value);
                    if (child instanceof TemporalRenderingOptions) {
                        var modes = new JComboBox<>(new String[]{"", "DISABLED", "BALANCED", "ACCUMULATING"});
                        panel.add(new JLabel("temporalRendering.mode")); panel.add(modes); fields.put(key + ".mode", modes);
                        var history = new JTextField(); panel.add(new JLabel("temporalRendering.maxHistoryFrames")); panel.add(history);
                        fields.put(key + ".maxHistoryFrames", history);
                    } else if (type.isRecord()) addRecordEditors(panel, fields, key + ".", child);
                    else addEditors(panel, fields, key + ".", child, type.getMethod("toBuilder").invoke(child));
                } catch (ReflectiveOperationException ignored) { /* Non-scalar assets have dedicated scene editors. */ }
            }
        }
    }

    private static void addRecordEditors(JPanel panel, Map<String, JComponent> fields, String prefix, Object value)
            throws ReflectiveOperationException {
        for (var component : value.getClass().getRecordComponents()) {
            Object current = component.getAccessor().invoke(value);
            String key = prefix + component.getName();
            if (component.getType().isRecord() && component.getType() != Rgb.class) {
                addRecordEditors(panel, fields, key + ".", current);
            } else {
                var field = new JTextField();
                field.setToolTipText(current instanceof Rgb rgb
                        ? "RGB components, comma separated. Preset: " + rgb.red() + "," + rgb.green() + "," + rgb.blue()
                        : "Preset: " + current);
                panel.add(new JLabel(key.substring(key.indexOf('.') + 1))); panel.add(field); fields.put(key, field);
            }
        }
    }

    private static void spawn(List<String> arguments, JTextArea output, List<Process> children) {
        try {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            command.add("--enable-native-access=ALL-UNNAMED"); command.add("-Dfile.encoding=UTF-8");
            command.add("-cp"); command.add(System.getProperty("java.class.path")); command.add("demo.HexBallDemo");
            command.addAll(arguments);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).start(); children.add(child);
            Thread.ofPlatform().daemon(true).start(() -> {
                try (var reader = child.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String text = line + "\n";
                        SwingUtilities.invokeLater(() -> { output.append(text); if (output.getDocument().getLength() > 100000) output.replaceRange("", 0, 50000); });
                    }
                } catch (java.io.IOException failure) { SwingUtilities.invokeLater(() -> output.append(failure + "\n")); }
            });
        } catch (java.io.IOException failure) { output.append(failure + "\n"); }
    }
}
