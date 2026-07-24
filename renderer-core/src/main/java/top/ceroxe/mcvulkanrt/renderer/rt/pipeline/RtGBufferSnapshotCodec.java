package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Versioned binary transport for diagnostic-only G-buffer artifacts. */
public final class RtGBufferSnapshotCodec {
    private static final int MAGIC = 0x4D434742; // MCGB
    private static final int VERSION = 1;

    private RtGBufferSnapshotCodec() {
    }

    public static void write(RtGBufferSnapshot snapshot, OutputStream output) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        DataOutputStream data = new DataOutputStream(Objects.requireNonNull(output, "output"));
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        data.writeLong(snapshot.frameStateSequence());
        data.writeInt(snapshot.width());
        data.writeInt(snapshot.height());
        for (float value : snapshot.depth()) data.writeInt(Float.floatToRawIntBits(value));
        writeInts(data, snapshot.normalOct16());
        writeInts(data, snapshot.albedoRgba8());
        writeInts(data, snapshot.materialIds());
        writeInts(data, snapshot.emissiveRgba8());
        data.write(snapshot.cameraMediumIds());
        data.flush();
    }

    public static RtGBufferSnapshot read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(Objects.requireNonNull(input, "input"));
        if (data.readInt() != MAGIC) throw new IOException("not an MCVulkanRT G-buffer artifact");
        if (data.readInt() != VERSION) throw new IOException("unsupported G-buffer artifact version");
        long sequence = data.readLong();
        int width = data.readInt();
        int height = data.readInt();
        final int pixels;
        try {
            pixels = RtGBufferSnapshot.pixelCount(width, height);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid G-buffer extent", invalid);
        }
        float[] depth = new float[pixels];
        for (int index = 0; index < pixels; index++) depth[index] = Float.intBitsToFloat(data.readInt());
        int[] normals = readInts(data, pixels);
        int[] albedo = readInts(data, pixels);
        int[] materials = readInts(data, pixels);
        int[] emissive = readInts(data, pixels);
        byte[] media = data.readNBytes(pixels);
        if (media.length != pixels) throw new IOException("truncated G-buffer camera-medium attachment");
        return new RtGBufferSnapshot(sequence, width, height, depth, normals, albedo, materials, emissive, media);
    }

    private static void writeInts(DataOutputStream output, int[] values) throws IOException {
        for (int value : values) output.writeInt(value);
    }

    private static int[] readInts(DataInputStream input, int count) throws IOException {
        int[] values = new int[count];
        for (int index = 0; index < count; index++) values[index] = input.readInt();
        return values;
    }
}
