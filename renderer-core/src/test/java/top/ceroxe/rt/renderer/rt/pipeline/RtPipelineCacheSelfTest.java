package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

public final class RtPipelineCacheSelfTest {
   private RtPipelineCacheSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
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
      verifyContentAddressingAndPersistence(identity);
      System.out.println("RtPipelineCacheSelfTest passed");
   }

   private static void verifyContentAddressingAndPersistence(RtPipelineCache.DeviceIdentity identity) throws Exception {
      String key=RtPipelineCacheStore.programKey("layout-1",new byte[]{1,2},new byte[]{3});
      require(key.equals(RtPipelineCacheStore.programKey("layout-1",new byte[]{1,2},new byte[]{3})),"same program has unstable identity");
      require(!key.equals(RtPipelineCacheStore.programKey("layout-1",new byte[]{1},new byte[]{2,3})),"stage boundaries omitted from identity");
      require(!key.equals(RtPipelineCacheStore.programKey("layout-2",new byte[]{1,2},new byte[]{3})),"pipeline ABI omitted from identity");
      require(!key.equals(RtPipelineCacheStore.programKey("layout-1",new byte[]{3},new byte[]{1,2})),"stage order omitted from identity");
      Path directory=Files.createTempDirectory("rt-pipeline-cache-test-");
      try {
         var policy=new RtPipelineCacheStore.Policy(1024,4096,2);
         var first=new RtPipelineCacheStore(directory,"gpuscene",identity,key,policy);
         byte[] data=header(identity,128);
         require(first.read().length==0,"missing cache must be a miss");
         first.write(data);
         require(Arrays.equals(data,first.read()),"cache round trip lost opaque driver data");
         BasicFileAttributes before=Files.readAttributes(first.file(),BasicFileAttributes.class);
         first.write(data);
         BasicFileAttributes after=Files.readAttributes(first.file(),BasicFileAttributes.class);
         require(before.lastModifiedTime().equals(after.lastModifiedTime()),"identical warm cache data was rewritten");
         if(before.fileKey()!=null) require(before.fileKey().equals(after.fileKey()),"identical warm cache data replaced the file");
         byte[] changed=data.clone();changed[changed.length-1]=7;
         first.write(changed);
         require(Arrays.equals(changed,first.read()),"changed cache data was skipped");
         first.write(data);
         byte[] encoded=Files.readAllBytes(first.file());encoded[encoded.length-1]^=1;
         Files.write(first.file(),encoded);
         require(first.read().length==0,"checksum-corrupt payload reached the driver");
         first.write(data);
         byte[] oversized=Files.readAllBytes(first.file());
         ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(12,2048);
         Files.write(first.file(),oversized);
         require(first.read().length==0,"oversized declared payload was accepted");
         Files.write(first.file(),new byte[9]);
         require(first.read().length==0,"truncated envelope was accepted");
         first.write(data);
         first.write(header(identity,2048));
         require(Arrays.equals(data,first.read()),"rejected replacement destroyed existing valid data");
         var wrongDriver=new RtPipelineCache.DeviceIdentity(identity.vendorId(),identity.deviceId()+1,identity.uuid());
         require(!first.file().equals(new RtPipelineCacheStore(directory,"gpuscene",wrongDriver,key,policy).file()),"devices share a cache file");
         byte[] foreign=header(wrongDriver,128);
         first.write(foreign);
         require(Arrays.equals(data,first.read()),"foreign driver bytes replaced valid cache");
         Files.setLastModifiedTime(first.file(),FileTime.fromMillis(1));
         var second=new RtPipelineCacheStore(directory,"gpuscene",identity,RtPipelineCacheStore.programKey("second",data),policy);
         second.write(data);
         Files.setLastModifiedTime(second.file(),FileTime.fromMillis(2));
         var third=new RtPipelineCacheStore(directory,"bootstrap",identity,RtPipelineCacheStore.programKey("third",data),policy);
         third.write(data);
         require(!Files.exists(first.file()) && Files.exists(second.file()) && Files.exists(third.file()),"entry budget must evict oldest program only");
         Path unrelated=directory.resolve("host-owned.bin");Files.write(unrelated,new byte[4]);
         var diskPolicy=new RtPipelineCacheStore.Policy(128,200,8);
         var fourth=new RtPipelineCacheStore(directory,"gpuscene",identity,RtPipelineCacheStore.programKey("fourth",data),diskPolicy);
         fourth.write(data);
         require(!Files.exists(second.file()) && !Files.exists(third.file()) && Arrays.equals(data,fourth.read()),"directory byte budget not enforced");
         require(Files.exists(unrelated),"cache pruning touched an unrelated file");
         try(var files=Files.list(directory)) {require(files.noneMatch(path->path.toString().endsWith(".tmp")),"successful writes leaked temporary files");}
      } finally {
         try(var files=Files.walk(directory)) {for(var file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);}
      }
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
