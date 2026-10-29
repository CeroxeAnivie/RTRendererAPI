import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Compare complete raw RGBA16F snapshots; run with Java 25 source-file mode. */
public final class CompareHdrSnapshots {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("first.rgba16f second.rgba16f maximumAbsoluteError");
        double tolerance = Double.parseDouble(args[2]);
        if (!Double.isFinite(tolerance) || tolerance < 0) throw new IllegalArgumentException("invalid tolerance");
        byte[] first = Files.readAllBytes(Path.of(args[0])), second = Files.readAllBytes(Path.of(args[1]));
        if (first.length == 0 || first.length % 8 != 0 || first.length != second.length)
            throw new AssertionError("snapshots must have identical nonzero complete RGBA16F extents");
        var a = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN);
        var b = ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN);
        long changed = 0, channels = first.length / 2;
        double maximum = 0, sum = 0, squared = 0;
        for (int channel = 0; channel < channels; channel++) {
            float x = Float.float16ToFloat(a.getShort()), y = Float.float16ToFloat(b.getShort());
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new AssertionError("nonfinite HDR at " + channel);
            double difference = Math.abs((double) x - y);
            maximum = Math.max(maximum, difference);
            sum += difference;
            squared += difference * difference;
            if (difference != 0) changed++;
            if (channel % 4 == 3 && difference != 0) throw new AssertionError("alpha changed at " + channel);
        }
        System.out.printf(Locale.ROOT,
                "channels=%d changed=%d maxAbsolute=%.12g meanAbsolute=%.12g rmse=%.12g%n",
                channels, changed, maximum, sum / channels, Math.sqrt(squared / channels));
        if (maximum > tolerance) throw new AssertionError("HDR difference exceeds tolerance: " + maximum);
    }
}
