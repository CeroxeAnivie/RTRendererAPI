package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import top.ceroxe.rt.renderer.RendererLog;

/** Content-addressed, bounded persistence for opaque driver pipeline data. */
final class RtPipelineCacheStore {
    private static final long MAGIC=0x5254504341434845L;
    private static final int FORMAT=1, HEADER_BYTES=48;
    private static final AtomicInteger WARNINGS=new AtomicInteger(16);
    private static final Object WRITE_LOCK=new Object();
    private final Path file;
    private final RtPipelineCache.DeviceIdentity identity;
    private final Policy policy;
    private volatile PersistedEntry persisted;

    record Policy(long maxEntryBytes,long maxDirectoryBytes,int maxEntries) {
        Policy {
            if(maxEntryBytes<32 || maxEntryBytes>Integer.MAX_VALUE-HEADER_BYTES
                    || maxDirectoryBytes<maxEntryBytes+HEADER_BYTES || maxEntries<1)
                throw new IllegalArgumentException("invalid pipeline cache policy");
        }
        static Policy configured() {
            long entryMiB=Math.clamp(Long.getLong("top.ceroxe.rt.pipelineCache.maxEntryMiB",128L),1L,1024L);
            long directoryMiB=Math.clamp(Long.getLong("top.ceroxe.rt.pipelineCache.maxDirectoryMiB",512L),entryMiB+1,16384L);
            return new Policy(entryMiB*1024*1024,directoryMiB*1024*1024,
                    Math.clamp(Integer.getInteger("top.ceroxe.rt.pipelineCache.maxEntries",128),1,4096));
        }
    }

    RtPipelineCacheStore(Path directory,String namespace,RtPipelineCache.DeviceIdentity identity,String programKey,Policy policy) {
        if(namespace==null || !namespace.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("invalid cache namespace");
        if(programKey==null || !programKey.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid pipeline program key");
        this.identity=Objects.requireNonNull(identity,"identity");
        this.policy=Objects.requireNonNull(policy,"policy");
        this.file=Objects.requireNonNull(directory,"directory").toAbsolutePath().normalize()
                .resolve(namespace+'-'+identity.fileToken()+'-'+programKey+".bin");
    }

    Path file() {return file;}
    long maxEntryBytes() {return policy.maxEntryBytes();}

    /** Stage order, boundaries and the explicit descriptor/group ABI participate in identity. */
    static String programKey(String pipelineAbi,byte[]... stages) {
        Objects.requireNonNull(pipelineAbi,"pipelineAbi");
        if(pipelineAbi.isBlank() || stages.length==0) throw new IllegalArgumentException("empty pipeline identity");
        var digest=sha256();
        update(digest,"RTRendererPipelineCache/1".getBytes(StandardCharsets.UTF_8));
        update(digest,pipelineAbi.getBytes(StandardCharsets.UTF_8));
        digest.update(ByteBuffer.allocate(4).putInt(stages.length).array());
        for(byte[] stage:stages) update(digest,Objects.requireNonNull(stage,"stage"));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest,byte[] bytes) {
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    byte[] read() {
        persisted=null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return new byte[0];
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] header=input.readNBytes(HEADER_BYTES);
            if(header.length!=HEADER_BYTES) return invalid("truncated envelope");
            var fields=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if(fields.getLong()!=MAGIC || fields.getInt()!=FORMAT) return invalid("unknown envelope");
            int length=fields.getInt();
            byte[] checksum=new byte[32];fields.get(checksum);
            if(length<32 || length>policy.maxEntryBytes()) return invalid("payload size outside budget");
            byte[] data=input.readNBytes(length);
            if(data.length!=length || input.read()!=-1) return invalid("payload length mismatch");
            if(!MessageDigest.isEqual(checksum,sha256().digest(data)) || !RtPipelineCache.headerMatches(data,identity))
                return invalid("checksum or driver identity mismatch");
            try {Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()));}
            catch(IOException ignored) { /* Read-only caches remain usable. */ }
            remember(checksum,HEADER_BYTES+(long)data.length);
            RendererLog.debug("Pipeline cache hit: {} bytes from {}",data.length,file);
            return data;
        } catch(IOException failure) {
            warn("Pipeline cache read failed: {} ({})",file,failure.toString());
            return new byte[0];
        }
    }

    private byte[] invalid(String reason) {
        warn("Pipeline cache ignored: {} ({})",file,reason);
        return new byte[0];
    }

    void write(byte[] data) {
        if(data.length>policy.maxEntryBytes()) {
            warn("Pipeline cache not persisted: {} bytes, entry budget {}",data.length,policy.maxEntryBytes());
            return;
        }
        if(!RtPipelineCache.headerMatches(data,identity)) {
            warn("Pipeline cache not persisted: invalid driver header for {}",file);
            return;
        }
        byte[] checksum=sha256().digest(data);
        synchronized(WRITE_LOCK) {
            Path temporary=null;
            try {
                if(unchanged(checksum,HEADER_BYTES+(long)data.length)) return;
                Files.createDirectories(file.getParent());
                temporary=Files.createTempFile(file.getParent(),file.getFileName()+".",".tmp");
                byte[] header=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(MAGIC).putInt(FORMAT).putInt(data.length).put(checksum).array();
                try(var output=Files.newOutputStream(temporary)) {output.write(header);output.write(data);}
                try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
                temporary=null;
                remember(checksum,HEADER_BYTES+(long)data.length);
                prune();
                RendererLog.debug("Pipeline cache persisted: {} bytes to {}",data.length,file);
            } catch(IOException failure) {
                warn("Pipeline cache persistence failed: {} ({})",file,failure.toString());
            } finally {
                if(temporary!=null) try {Files.deleteIfExists(temporary);} catch(IOException ignored) {}
            }
        }
    }

    private boolean unchanged(byte[] checksum,long length) throws IOException {
        PersistedEntry known=persisted;
        return known!=null && known.length==length && MessageDigest.isEqual(known.checksum,checksum)
                && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.size(file)==length
                && Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).equals(known.modified);
    }

    private void remember(byte[] checksum,long length) {
        try {
            persisted=Files.size(file)==length
                    ?new PersistedEntry(checksum,length,Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS)):null;
        } catch(IOException ignored) {persisted=null;}
    }

    private record PersistedEntry(byte[] checksum,long length,FileTime modified) {}

    private void prune() throws IOException {
        var entries=new ArrayList<Entry>();
        long total=0;
        try(var files=Files.list(file.getParent())) {
            for(var candidate:files.filter(path->managedFile(path.getFileName().toString())).toList()) {
                if(!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    long bytes=Files.size(candidate);
                    entries.add(new Entry(candidate,bytes,Files.getLastModifiedTime(candidate).toMillis()));
                    total=Math.addExact(total,bytes);
                } catch(NoSuchFileException ignored) { /* Another process may prune simultaneously. */ }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::modified));
        int count=entries.size();
        for(var entry:entries) {
            if(total<=policy.maxDirectoryBytes() && count<=policy.maxEntries()) break;
            if(entry.path().equals(file)) continue;
            if(Files.deleteIfExists(entry.path())) {total-=entry.bytes();count--;}
        }
    }

    private static boolean managedFile(String name) {
        return name.matches("[a-z0-9-]{1,32}-[0-9a-f]{1,8}-[0-9a-f]{1,8}-[0-9a-f]{32}-[0-9a-f]{64}\\.bin");
    }
    private record Entry(Path path,long bytes,long modified) {}
    private static MessageDigest sha256() {
        try {return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    static void warn(String template,Object... arguments) {
        if(WARNINGS.getAndUpdate(left->Math.max(0,left-1))>0) RendererLog.warn(template,arguments);
    }
}
