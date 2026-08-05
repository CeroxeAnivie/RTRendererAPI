package demo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import top.ceroxe.rt.renderer.api.CpuFrame;

final class RenderWindow implements AutoCloseable {
    private final JFrame frame;
    private final FramePanel panel;
    private final GraphicsDevice graphicsDevice;
    private final boolean fullScreen;

    private RenderWindow(DemoConfig config, AtomicBoolean running, RenderStats stats) {
        frame = new JFrame("RTRendererAPI " + DemoBuildInfo.version() + " - Uncapped Hex Ball Ray Tracing");
        panel = new FramePanel(config, stats);
        graphicsDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        fullScreen = !config.windowed();

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setBackground(Color.BLACK);
        frame.setContentPane(panel);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                running.set(false);
            }
        });
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    running.set(false);
                }
            }
        });

        if (fullScreen) {
            frame.setUndecorated(true);
            frame.setResizable(false);
            graphicsDevice.setFullScreenWindow(frame);
        } else {
            frame.setMinimumSize(new Dimension(640, 360));
            frame.setSize(Math.min(config.width(), 1600), Math.min(config.height(), 900));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }
        frame.requestFocusInWindow();
    }

    static RenderWindow open(DemoConfig config, AtomicBoolean running, RenderStats stats)
            throws Exception {
        AtomicReference<RenderWindow> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(new RenderWindow(config, running, stats));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("failed to create the render window", failure.get());
        }
        return result.get();
    }

    void publish(CpuFrame cpuFrame) {
        panel.publish(cpuFrame);
    }

    void publishOverlay(DemoTechnologyHud.Snapshot snapshot) {
        panel.publishOverlay(snapshot);
    }

    @Override
    public void close() {
        Runnable dispose = () -> {
            if (fullScreen && graphicsDevice.getFullScreenWindow() == frame) {
                graphicsDevice.setFullScreenWindow(null);
            }
            frame.dispose();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            dispose.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(dispose);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw new IllegalStateException("failed to dispose the render window", failure.getCause());
            }
        }
    }

    private static final class FramePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Font OVERLAY_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 15);

        // Swing serialization is not a supported lifecycle for this native renderer window.
        private final transient DemoConfig config;
        private final transient RenderStats stats;
        private final transient AtomicReference<BufferedImage> latestImage = new AtomicReference<>();
        private final transient AtomicReference<BufferedImage> paintingImage = new AtomicReference<>();
        private final transient AtomicReference<DemoTechnologyHud.Snapshot> overlay =
                new AtomicReference<>();
        private final transient BufferedImage[] imagePool = new BufferedImage[3];

        private FramePanel(DemoConfig config, RenderStats stats) {
            this.config = config;
            this.stats = stats;
            setBackground(Color.BLACK);
            setFocusable(false);
        }

        private void publish(CpuFrame cpuFrame) {
            BufferedImage current = latestImage.get();
            BufferedImage painting = paintingImage.get();
            BufferedImage destination = null;
            for (BufferedImage candidate : imagePool) {
                if (candidate != null && candidate != current && candidate != painting
                        && candidate.getWidth() == cpuFrame.width()
                        && candidate.getHeight() == cpuFrame.height()) {
                    destination = candidate;
                    break;
                }
            }
            if (destination == null) {
                destination = new BufferedImage(
                        cpuFrame.width(), cpuFrame.height(), BufferedImage.TYPE_INT_ARGB
                );
                for (int index = 0; index < imagePool.length; index++) {
                    if (imagePool[index] == null) {
                        imagePool[index] = destination;
                        break;
                    }
                }
            }
            toBufferedImage(cpuFrame, destination, stats);
            latestImage.set(destination);
            repaint();
        }

        private void publishOverlay(DemoTechnologyHud.Snapshot snapshot) {
            overlay.set(java.util.Objects.requireNonNull(snapshot, "snapshot"));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                BufferedImage image = latestImage.get();
                if (image != null) {
                    paintingImage.set(image);
                    try {
                        double scale = Math.min(
                                getWidth() / (double) image.getWidth(),
                                getHeight() / (double) image.getHeight()
                        );
                        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                        int x = (getWidth() - width) / 2;
                        int y = (getHeight() - height) / 2;
                        canvas.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        );
                        canvas.drawImage(image, x, y, width, height, null);
                    } finally {
                        paintingImage.compareAndSet(image, null);
                    }
                }
                paintOverlay(canvas);
            } finally {
                canvas.dispose();
            }
        }

        private void paintOverlay(Graphics2D canvas) {
            canvas.setFont(OVERLAY_FONT);
            DemoTechnologyHud.Snapshot snapshot = overlay.get();
            if (snapshot == null) return;
            String[] lines = snapshot.text().split("\\R", -1);
            int lineHeight = canvas.getFontMetrics().getHeight();
            int boxWidth = 0;
            for (String line : lines) {
                boxWidth = Math.max(boxWidth, canvas.getFontMetrics().stringWidth(line));
            }
            boxWidth = Math.min(Math.max(0, getWidth() - 28), boxWidth + 24);
            int boxHeight = lineHeight * lines.length + 16;
            canvas.setColor(new Color(0, 0, 0, 175));
            canvas.fillRect(14, 14, boxWidth, boxHeight);
            canvas.setColor(new Color(255, 255, 255, 225));
            canvas.setStroke(new BasicStroke(1.0F));
            int baseline = 14 + canvas.getFontMetrics().getAscent() + 8;
            for (String line : lines) {
                canvas.drawString(line, 26, baseline);
                baseline += lineHeight;
            }
        }

        private static void toBufferedImage(CpuFrame frame, BufferedImage image, RenderStats stats) {
            int[] destination = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            ByteBuffer source = frame.pixelsRgba8().order(ByteOrder.LITTLE_ENDIAN);
            IntBuffer packedPixels = source.asIntBuffer();
            int minimumLuminance = 255;
            int maximumLuminance = 0;
            long chromaticPixels = 0L;
            for (int pixel = 0; pixel < destination.length; pixel++) {
                int abgr = packedPixels.get(pixel);
                int argb = abgr & 0xff00ff00
                        | (abgr & 0x000000ff) << 16
                        | (abgr & 0x00ff0000) >>> 16;
                destination[pixel] = argb;
                if ((pixel & 63) != 0) continue;

                int red = argb >>> 16 & 0xff;
                int green = argb >>> 8 & 0xff;
                int blue = argb & 0xff;
                int luminance = (77 * red + 150 * green + 29 * blue) >>> 8;
                minimumLuminance = Math.min(minimumLuminance, luminance);
                maximumLuminance = Math.max(maximumLuminance, luminance);
                if (Math.max(red, Math.max(green, blue))
                        - Math.min(red, Math.min(green, blue)) >= 12) chromaticPixels++;
            }
            int sampledPixels = (destination.length + 63) / 64;
            stats.observeFrameContent(maximumLuminance - minimumLuminance, chromaticPixels, sampledPixels);
        }
    }
}
