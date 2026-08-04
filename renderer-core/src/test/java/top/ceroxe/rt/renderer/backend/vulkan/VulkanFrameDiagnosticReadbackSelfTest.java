package top.ceroxe.rt.renderer.backend.vulkan;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class VulkanFrameDiagnosticReadbackSelfTest {
   private static final String EXHAUSTIVE_HALF_FLOAT_GOLDEN_SHA256 = "7f30fa646a57ed533b0cb3bce429763bc2ccd3e1e96084ebbebd88071d4d9df7";

   private VulkanFrameDiagnosticReadbackSelfTest() {
   }

   public static void main(String[] arguments) {
      byte[] source = new byte[24];
      putPixel(source, 0, 0, 15360, 17408, 15360);
      putPixel(source, 8, 31744, 64512, 32256, 0);
      putPixel(source, 16, 1, 32768, 15360, 14336);
      byte[] converted = VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(source);
      require(converted.length == 12, "RGBA16F conversion changed pixel count");
      require(unsigned(converted[0]) == 0, "zero radiance must remain black");
      require(unsigned(converted[1]) == 231, "unit radiance ACES/gamma result changed");
      require(unsigned(converted[2]) == 252, "bright HDR radiance did not approach display white");
      require(unsigned(converted[3]) == 255, "unit alpha must remain opaque");
      require(unsigned(converted[4]) == 255, "positive infinity must saturate rather than wrap");
      require(unsigned(converted[5]) == 0 && unsigned(converted[6]) == 0, "negative infinity and NaN must deterministically map to black");
      require(unsigned(converted[7]) == 0, "zero alpha changed");
      require(unsigned(converted[8]) == 0 && unsigned(converted[9]) == 0, "positive subnormal and negative zero must remain bounded at black");
      require(unsigned(converted[10]) == 231 && unsigned(converted[11]) == 128, "finite color/alpha quantization changed");
      require(VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(new byte[0]).length == 0, "empty payload must remain empty");
      expect(IllegalArgumentException.class, () -> VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(new byte[7]));
      expect(NullPointerException.class, () -> VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8((byte[])null));
      byte[] finite = new byte[16];
      putPixel(finite, 0, 0, 15360, 31743, 15360);
      putPixel(finite, 8, 1, 32768, 48127, 14336);
      VulkanFramePixelCodec.requireFiniteLinearHdrRgba16f(finite);
      expect(IllegalStateException.class, () -> VulkanFramePixelCodec.requireFiniteLinearHdrRgba16f(source));
      expect(IllegalArgumentException.class, () -> VulkanFramePixelCodec.requireFiniteLinearHdrRgba16f(new byte[7]));
      expect(NullPointerException.class, () -> VulkanFramePixelCodec.requireFiniteLinearHdrRgba16f(null));
      verifiesExhaustiveHalfFloatGolden();
      System.out.println("VulkanFrameDiagnosticReadbackSelfTest passed");
   }

   private static void verifiesExhaustiveHalfFloatGolden() {
      int encodings = 65536;
      byte[] source = new byte[encodings * 8];

      for(int bits = 0; bits < encodings; ++bits) {
         int offset = bits * 8;
         putPixel(source, offset, bits, bits, bits, 15360);
      }

      byte[] converted = VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(source);
      require(converted.length == encodings * 4, "exhaustive half-float conversion changed pixel count");
      int previous = -1;

      for(int bits = 0; bits < encodings; ++bits) {
         int offset = bits * 4;
         int value = unsigned(converted[offset]);
         int expected = referenceToneMapped(Float.float16ToFloat((short)bits));
         require(value == expected, "tone map diverged from the independent JDK half-float oracle at bits 0x" + Integer.toHexString(bits) + ": expected=" + expected + ", actual=" + value);
         boolean condition10000 = value == unsigned(converted[offset + 1]) && value == unsigned(converted[offset + 2]);
         String details10001 = Integer.toHexString(bits);
         require(condition10000, "identical HDR channels diverged at half bits 0x" + details10001);
         require(unsigned(converted[offset + 3]) == 255, "opaque alpha changed at half bits 0x" + Integer.toHexString(bits));
         if (bits <= 31743) {
            require(value >= previous, "finite positive tone map regressed at half bits 0x" + Integer.toHexString(bits));
            previous = value;
         }
      }

      String digest = sha256(converted);
      require("7f30fa646a57ed533b0cb3bce429763bc2ccd3e1e96084ebbebd88071d4d9df7".equals(digest), "exhaustive half-float tone-map golden changed: expected=7f30fa646a57ed533b0cb3bce429763bc2ccd3e1e96084ebbebd88071d4d9df7, actual=" + digest);
   }

   private static int referenceToneMapped(float value) {
      if (!Float.isNaN(value) && !(value <= 0.0F)) {
         if (value == 1.0F / 0.0F) {
            return 255;
         } else {
            double numerator = (double)value * (2.51 * (double)value + 0.03);
            double denominator = (double)value * (2.43 * (double)value + 0.59) + 0.14;
            double aces = Math.clamp(numerator / denominator, 0.0, 1.0);
            double display = Math.pow(aces, 0.45454545454545453);
            return (int)Math.round(Math.clamp(display, 0.0, 1.0) * 255.0);
         }
      } else {
         return 0;
      }
   }

   private static String sha256(byte[] bytes) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException impossible) {
         throw new AssertionError("Java runtime does not provide mandatory SHA-256", impossible);
      }
   }

   private static void putPixel(byte[] target, int offset, int red, int green, int blue, int alpha) {
      putHalf(target, offset, red);
      putHalf(target, offset + 2, green);
      putHalf(target, offset + 4, blue);
      putHalf(target, offset + 6, alpha);
   }

   private static void putHalf(byte[] target, int offset, int bits) {
      target[offset] = (byte)bits;
      target[offset + 1] = (byte)(bits >>> 8);
   }

   private static int unsigned(byte value) {
      return value & 255;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static void expect(Class<? extends Throwable> type, Runnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName());
   }
}
