package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class RtPipelineCacheSelfTest {
   private RtPipelineCacheSelfTest() {
   }

   public static void main(String[] arguments) {
      byte[] uuid = new byte[16];

      for(int index = 0; index < uuid.length; ++index) {
         uuid[index] = (byte)(index + 1);
      }

      RtPipelineCache.DeviceIdentity identity = new RtPipelineCache.DeviceIdentity(4318, 11352, uuid);
      byte[] valid = header(identity, 48);
      require(RtPipelineCache.headerMatches(valid, identity), "valid cache header was rejected");
      byte[] wrongVersion = valid.clone();
      ByteBuffer.wrap(wrongVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
      require(!RtPipelineCache.headerMatches(wrongVersion, identity), "unknown cache header version was accepted");
      byte[] wrongUuid = valid.clone();
      wrongUuid[16] = (byte)(wrongUuid[16] ^ 1);
      require(!RtPipelineCache.headerMatches(wrongUuid, identity), "foreign driver cache UUID was accepted");
      require(!RtPipelineCache.headerMatches(Arrays.copyOf(valid, 31), identity), "truncated cache header was accepted");
      System.out.println("RtPipelineCacheSelfTest passed");
   }

   private static byte[] header(RtPipelineCache.DeviceIdentity identity, int totalBytes) {
      ByteBuffer bytes = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
      bytes.putInt(32);
      bytes.putInt(1);
      bytes.putInt(identity.vendorId());
      bytes.putInt(identity.deviceId());
      bytes.put(identity.uuid());
      return bytes.array();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
