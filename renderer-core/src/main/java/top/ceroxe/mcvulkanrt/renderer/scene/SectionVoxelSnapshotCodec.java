package top.ceroxe.mcvulkanrt.renderer.scene;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Stable, bounded transport for a renderer-owned section snapshot.
 *
 * <p>Unlike a generic object stream this protocol has no caller-controlled
 * allocation sizes: every array has the fixed section or boundary cardinality.
 * It is therefore suitable for a runtime capture process to hand an immutable
 * snapshot to the isolated sourceEngine Oracle JVM.</p>
 */
public final class SectionVoxelSnapshotCodec {
    private static final int MAGIC = 0x4D435653; // MCVS
    private static final int VERSION = 1;

    private SectionVoxelSnapshotCodec() {
    }

    public static void write(SectionVoxelSnapshot snapshot, OutputStream output) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(output, "output");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        data.writeInt(snapshot.key().x());
        data.writeInt(snapshot.key().y());
        data.writeInt(snapshot.key().z());
        writeInts(data, snapshot.voxelTypeIds());
        writeInts(data, snapshot.mediumStateIds());
        writeInts(data, snapshot.mediumTypeIds());
        writeBytes(data, snapshot.mediumAmounts());
        writeBytes(data, snapshot.fluidFlowX());
        writeBytes(data, snapshot.fluidFlowZ());
        writeInts(data, snapshot.mapColors());
        writeInts(data, snapshot.blockTintLayer0Colors());
        writeInts(data, snapshot.blockTintLayer1Colors());
        writeInts(data, snapshot.blockTintLayer2Colors());
        writeInts(data, snapshot.blockTintLayer3Colors());
        writeInts(data, snapshot.fluidMapColors());
        writeBytes(data, snapshot.lightEmissions());
        writeBytes(data, snapshot.materialFlags());
        writeBytes(data, snapshot.shadeBrightnesses());
        data.writeBoolean(snapshot.hasOnlyAir());
        data.writeBoolean(snapshot.hasFluid());
        SectionBoundarySnapshot boundary = snapshot.capturedBoundary();
        data.writeBoolean(boundary != null);
        if (boundary != null) {
            writeBoundary(data, boundary);
        }
        data.flush();
    }

    public static SectionVoxelSnapshot read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("not an MCVulkanRT section snapshot");
        if (data.readInt() != VERSION) throw new IOException("unsupported section snapshot format");
        SectionKey key = new SectionKey(data.readInt(), data.readInt(), data.readInt());
        int count = SectionVoxelSnapshot.BLOCKS_PER_SECTION;
        int[] blockStates = readInts(data, count);
        int[] fluidVoxelStates = readInts(data, count);
        int[] mediumTypes = readInts(data, count);
        byte[] mediumAmounts = readBytes(data, count);
        byte[] fluidFlowX = readBytes(data, count);
        byte[] fluidFlowZ = readBytes(data, count);
        int[] mapColors = readInts(data, count);
        int[] tint0 = readInts(data, count);
        int[] tint1 = readInts(data, count);
        int[] tint2 = readInts(data, count);
        int[] tint3 = readInts(data, count);
        int[] fluidMapColors = readInts(data, count);
        byte[] emissions = readBytes(data, count);
        byte[] materialFlags = readBytes(data, count);
        byte[] shades = readBytes(data, count);
        boolean onlyAir = data.readBoolean();
        boolean hasFluid = data.readBoolean();
        SectionBoundarySnapshot boundary = data.readBoolean() ? readBoundary(data) : null;
        return new SectionVoxelSnapshot(key, blockStates, fluidVoxelStates, mediumTypes,
                mediumAmounts, fluidFlowX, fluidFlowZ, mapColors, tint0, tint1, tint2, tint3,
                fluidMapColors, emissions, materialFlags, shades, onlyAir, hasFluid, boundary);
    }

    private static void writeBoundary(DataOutputStream data, SectionBoundarySnapshot boundary) throws IOException {
        int geometryCount = SectionBoundarySnapshot.geometrySampleCount();
        int[] blockStates = new int[geometryCount];
        int[] fluidVoxelStates = new int[geometryCount];
        int[] mediumTypes = new int[geometryCount];
        byte[] mediumAmounts = new byte[geometryCount];
        byte[] geometryFlags = new byte[geometryCount];
        forEachGeometry((x, y, z, index) -> {
            blockStates[index] = boundary.voxelTypeIdAt(x, y, z);
            fluidVoxelStates[index] = boundary.mediumStateIdAt(x, y, z);
            mediumTypes[index] = boundary.mediumTypeIdAt(x, y, z);
            mediumAmounts[index] = (byte) boundary.mediumAmountAt(x, y, z);
            geometryFlags[index] = (byte) boundary.geometryMaterialFlagsAt(x, y, z);
        });
        int lightCount = SectionBoundarySnapshot.lightSampleCount();
        int[] mapColors = new int[lightCount];
        byte[] emissions = new byte[lightCount];
        byte[] lightFlags = new byte[lightCount];
        byte[] shades = new byte[lightCount];
        forEachLight((x, y, z, index) -> {
            mapColors[index] = boundary.packedMapColorAt(x, y, z);
            emissions[index] = (byte) boundary.lightEmissionAt(x, y, z);
            lightFlags[index] = (byte) boundary.lightMaterialFlagsAt(x, y, z);
            shades[index] = (byte) boundary.shadeBrightnessAt(x, y, z);
        });
        writeInts(data, blockStates); writeInts(data, fluidVoxelStates); writeInts(data, mediumTypes);
        writeBytes(data, mediumAmounts); writeBytes(data, geometryFlags);
        writeInts(data, mapColors); writeBytes(data, emissions); writeBytes(data, lightFlags); writeBytes(data, shades);
    }

    private static SectionBoundarySnapshot readBoundary(DataInputStream data) throws IOException {
        int geometryCount = SectionBoundarySnapshot.geometrySampleCount();
        int[] blockStates = readInts(data, geometryCount);
        int[] fluidVoxelStates = readInts(data, geometryCount);
        int[] mediumTypes = readInts(data, geometryCount);
        byte[] mediumAmounts = readBytes(data, geometryCount);
        byte[] geometryFlags = readBytes(data, geometryCount);
        int lightCount = SectionBoundarySnapshot.lightSampleCount();
        int[] mapColors = readInts(data, lightCount);
        byte[] emissions = readBytes(data, lightCount);
        byte[] lightFlags = readBytes(data, lightCount);
        byte[] shades = readBytes(data, lightCount);
        return new SectionBoundarySnapshot(blockStates, fluidVoxelStates, mediumTypes, mediumAmounts,
                geometryFlags, mapColors, emissions, lightFlags, shades);
    }

    private static void writeInts(DataOutputStream output, int[] values) throws IOException { for (int value : values) output.writeInt(value); }
    private static void writeBytes(DataOutputStream output, byte[] values) throws IOException { output.write(values); }
    private static int[] readInts(DataInputStream input, int count) throws IOException { int[] values = new int[count]; for (int i = 0; i < count; i++) values[i] = input.readInt(); return values; }
    private static byte[] readBytes(DataInputStream input, int count) throws IOException { byte[] values = new byte[count]; input.readFully(values); return values; }

    private static void forEachGeometry(CoordinateConsumer consumer) {
        for (int y = -1; y <= SectionVoxelSnapshot.SECTION_SIZE; y++) for (int z = -1; z <= SectionVoxelSnapshot.SECTION_SIZE; z++) for (int x = -1; x <= SectionVoxelSnapshot.SECTION_SIZE; x++) if (SectionBoundarySnapshot.containsGeometryCoordinate(x, y, z)) consumer.accept(x, y, z, SectionBoundarySnapshot.geometrySampleIndex(x, y, z));
    }
    private static void forEachLight(CoordinateConsumer consumer) {
        for (int y = -2; y <= SectionVoxelSnapshot.SECTION_SIZE + 1; y++) for (int z = -2; z <= SectionVoxelSnapshot.SECTION_SIZE + 1; z++) for (int x = -2; x <= SectionVoxelSnapshot.SECTION_SIZE + 1; x++) if (SectionBoundarySnapshot.containsLightCoordinate(x, y, z)) consumer.accept(x, y, z, SectionBoundarySnapshot.lightSampleIndex(x, y, z));
    }
    @FunctionalInterface private interface CoordinateConsumer { void accept(int x, int y, int z, int index); }
}
