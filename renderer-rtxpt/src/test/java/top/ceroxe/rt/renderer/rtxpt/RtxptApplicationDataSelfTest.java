package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-JDK bounded application-data regression checks; no renderer, native library or GPU. */
public final class RtxptApplicationDataSelfTest {
    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private static final int DEFERRED_PATCHES = 100_000;

    private RtxptApplicationDataSelfTest() { }

    public static void main(String[] args) {
        publicationRangeAndOwnership();
        directPatchRangeAndOwnership();
        exactDirtyRanges();
        failedFlushRetainsOnlyUnsentRanges();
        failedFirstRangeCanRetry();
        fullPublicationAbsorbsPatches();
        failedFullPublicationRemainsPending();
        replacementDiscardsOldPendingState();
        invalidInputsPreservePendingState();
        fragmentedPatchesHaveBoundedWrites();
        repeatedDeferredPatchesStayBounded();
        clearAndEmptyPublication();
        System.out.println("RtxptApplicationDataSelfTest: 12 checks passed; 100000 deferred patches, 16 MiB capacity, at most 256 patch writes");
    }
    private static void failedFullPublicationRemainsPending(){
        var storage=new RtxptApplicationData();byte[] expected=sequence(32,11);
        storage.publish(ByteBuffer.wrap(expected));
        var refused=new IllegalStateException("injected full upload refusal");
        try{new RtxptApplicationData.PatchWriter(){
            public void write(long generation,int offset,byte[] bytes){throw refused;}
        }.write(1,0,storage.fullPublication());storage.publicationApplied();throw new AssertionError("failure swallowed");}
        catch(IllegalStateException failure){check(failure==refused,"full upload failure identity");}
        equal(expected,storage.fullPublication(),"Failed full publication must remain pending for exact retry");
        check(flush(storage,1).isEmpty(),"Failed full publication cannot become patch work");
        storage.publicationApplied();check(storage.fullPublication()==null,"Successful retry acknowledges full publication");
    }

    private static void publicationRangeAndOwnership() {
        var storage = new RtxptApplicationData();
        byte[] source = sequence(20, 1);
        var input = ByteBuffer.wrap(source);
        input.position(4).limit(16);
        byte[] expected = Arrays.copyOfRange(source, 4, 16);
        storage.publish(input);
        check(input.position() == 4 && input.limit() == 16, "publish changed input range");
        check(storage.capacity() == 12, "publication capacity does not match remaining bytes");
        Arrays.fill(source, (byte) -1);
        equal(expected, storage.fullPublication(), "publication retained a writable caller alias");
        check(storage.fullPublication() == storage.fullPublication(), "full upload must borrow the authoritative array");
    }

    private static void directPatchRangeAndOwnership() {
        var storage = published(24);
        var source = ByteBuffer.allocateDirect(20);
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) (i + 20));
        source.position(4).limit(16);
        var input = source.asReadOnlyBuffer();
        byte[] expected = new byte[12];
        input.duplicate().get(expected);
        storage.patch(8, input);
        check(input.position() == 4 && input.limit() == 16, "patch changed input range");
        source.put(4, (byte) 0);
        List<Write> writes = flush(storage, 3);
        check(writes.size() == 1 && writes.get(0).offset() == 8, "direct patch range changed");
        equal(expected, writes.get(0).data(), "patch retained a writable caller alias");
    }

    private static void exactDirtyRanges() {
        var storage = published(48);
        storage.patch(4, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        storage.patch(8, ByteBuffer.wrap(new byte[]{5, 6, 7, 8}));
        storage.patch(20, ByteBuffer.wrap(new byte[]{9, 10, 11, 12}));
        storage.patch(8, ByteBuffer.wrap(new byte[]{13, 14, 15, 16}));
        storage.patch(24, ByteBuffer.wrap(new byte[]{17, 18, 19, 20, 21, 22, 23, 24}));
        List<Write> writes = flush(storage, 7);
        check(writes.size() == 2, "gap must stay separate while adjacent words merge");
        check(writes.get(0).generation() == 7 && writes.get(1).generation() == 7, "flush changed generation");
        check(writes.get(0).offset() == 4 && writes.get(1).offset() == 20, "dirty offsets are not exact");
        equal(new byte[]{1, 2, 3, 4, 13, 14, 15, 16}, writes.get(0).data(), "overlap did not keep final bytes");
        equal(new byte[]{9, 10, 11, 12, 17, 18, 19, 20, 21, 22, 23, 24}, writes.get(1).data(), "adjacent range did not merge exactly");
        check(flush(storage, 7).isEmpty(), "successful ranges were not acknowledged");
    }

    private static void failedFlushRetainsOnlyUnsentRanges() {
        var storage = published(32);
        storage.patch(4, bytes(1));
        storage.patch(12, bytes(2));
        storage.patch(20, bytes(3));
        var successful = new ArrayList<Write>();
        RuntimeException failure = new IllegalStateException("injected second write refusal");
        int[] attempts = {0};
        try {
            storage.flushPatches(11, (generation, offset, values) -> {
                if (++attempts[0] == 2) throw failure;
                successful.add(new Write(generation, offset, values));
            });
            throw new AssertionError("injected writer failure did not propagate");
        } catch (RuntimeException actual) {
            check(actual == failure, "writer failure identity changed");
        }
        check(successful.size() == 1 && successful.get(0).offset() == 4, "successful prefix was not sent");
        List<Write> retry = flush(storage, 11);
        check(retry.size() == 2 && retry.get(0).offset() == 12 && retry.get(1).offset() == 20, "retry lost unsent ranges or resent the successful prefix");
        equal(new byte[]{2, 2, 2, 2}, retry.get(0).data(), "failed range bytes changed");
        equal(new byte[]{3, 3, 3, 3}, retry.get(1).data(), "later range bytes changed");
    }

    private static void failedFirstRangeCanRetry() {
        var storage = published(16);
        storage.patch(0, bytes(4));
        storage.patch(12, bytes(5));
        expect(IllegalStateException.class, () -> storage.flushPatches(15, (generation, offset, values) -> {
            throw new IllegalStateException("injected first write refusal");
        }));
        List<Write> retry = flush(storage, 15);
        check(retry.size() == 2 && retry.get(0).offset() == 0 && retry.get(1).offset() == 12, "first-range failure lost pending bytes");
    }

    private static void fullPublicationAbsorbsPatches() {
        var storage = new RtxptApplicationData();
        storage.publish(ByteBuffer.wrap(new byte[16]));
        storage.patch(4, bytes(6));
        storage.patch(8, bytes(7));
        byte[] expected = new byte[16];
        Arrays.fill(expected, 4, 8, (byte) 6);
        Arrays.fill(expected, 8, 12, (byte) 7);
        equal(expected, storage.fullPublication(), "full publication omitted pending patches");
        check(flush(storage, 20).isEmpty(), "full pending publication must suppress patch uploads");
        storage.publicationApplied();
        check(storage.fullPublication() == null && flush(storage, 20).isEmpty(), "applied full publication retained upload work");
    }

    private static void replacementDiscardsOldPendingState() {
        var storage = published(32);
        storage.patch(28, bytes(8));
        byte[] replacement = sequence(8, 40);
        storage.publish(ByteBuffer.wrap(replacement));
        check(storage.capacity() == 8, "replacement kept old publication capacity");
        equal(replacement, storage.fullPublication(), "replacement bytes changed");
        check(flush(storage, 25).isEmpty(), "replacement leaked an old dirty range");
        storage.publicationApplied();
        check(flush(storage, 25).isEmpty(), "old dirty range survived full acknowledgement");
    }

    private static void invalidInputsPreservePendingState() {
        var storage = published(16);
        storage.patch(4, bytes(9));
        long retained = storage.retainedBytes();
        expect(IllegalArgumentException.class, () -> storage.publish(ByteBuffer.allocate(6)));
        expect(IllegalArgumentException.class, () -> storage.publish(ByteBuffer.allocate(MAXIMUM_BYTES + 4)));
        expect(NullPointerException.class, () -> storage.publish(null));
        expect(IllegalArgumentException.class, () -> storage.patch(-4, bytes(1)));
        expect(IllegalArgumentException.class, () -> storage.patch(2, bytes(1)));
        expect(IllegalArgumentException.class, () -> storage.patch(16, bytes(1)));
        expect(IllegalArgumentException.class, () -> storage.patch(Integer.MAX_VALUE - 3, bytes(1)));
        expect(IllegalArgumentException.class, () -> storage.patch(0, ByteBuffer.allocate(0)));
        expect(IllegalArgumentException.class, () -> storage.patch(0, ByteBuffer.allocate(3)));
        expect(IllegalArgumentException.class, () -> storage.patch(0, ByteBuffer.allocate(20)));
        expect(NullPointerException.class, () -> storage.patch(0, null));
        expect(IllegalArgumentException.class, () -> storage.flushPatches(0, (generation, offset, values) -> { }));
        expect(NullPointerException.class, () -> storage.flushPatches(30, null));
        check(storage.capacity() == 16 && storage.retainedBytes() == retained, "invalid input changed storage state");
        List<Write> writes = flush(storage, 30);
        check(writes.size() == 1 && writes.get(0).offset() == 4, "invalid input changed dirty ranges");
        equal(new byte[]{9, 9, 9, 9}, writes.get(0).data(), "invalid input corrupted pending bytes");

        storage.publish(ByteBuffer.wrap(sequence(8, 60)));
        byte[] full = storage.fullPublication();
        expect(IllegalArgumentException.class, () -> storage.publish(ByteBuffer.allocate(2)));
        expect(IllegalArgumentException.class, () -> storage.patch(8, bytes(2)));
        check(storage.fullPublication() == full, "invalid input replaced a pending full publication");
        equal(sequence(8, 60), full, "invalid input corrupted pending full bytes");
    }

    private static void fragmentedPatchesHaveBoundedWrites() {
        var exact = published(256 * 8);
        for (int i = 0; i < 256; i++) exact.patch(i * 8, bytes(i));
        List<Write> exactWrites = flush(exact, 50);
        check(exactWrites.size() == 256, "256 sparse spans must remain exact");
        for (int i = 0; i < exactWrites.size(); i++) {
            var write = exactWrites.get(i);
            check(write.offset() == i * 8 && write.data().length == 4, "ordinary sparse words expanded across clean gaps");
        }

        var fragmented = published(257 * 8);
        byte[] expected = new byte[257 * 8];
        for (int i = 0; i < 257; i++) {
            fragmented.patch(i * 8, bytes(i));
            Arrays.fill(expected, i * 8, i * 8 + 4, (byte) i);
        }
        int[] attempts = {0};
        expect(IllegalStateException.class, () -> fragmented.flushPatches(51, (generation, offset, values) -> {
            attempts[0]++;
            check(offset == 0 && values.length == expected.length, "fragmented fallback must be one whole publication");
            throw new IllegalStateException("injected fragmented fallback refusal");
        }));
        check(attempts[0] == 1, "fragmentation fallback made multiple writes");
        fragmented.patch(0, bytes(42));
        Arrays.fill(expected, 0, 4, (byte) 42);
        List<Write> retry = flush(fragmented, 52);
        check(retry.size() == 1 && retry.get(0).generation() == 52 && retry.get(0).offset() == 0, "failed fragmented fallback did not retry once");
        equal(expected, retry.get(0).data(), "fragmented fallback retry lost final bytes");
        check(flush(fragmented, 52).isEmpty(), "successful fragmented fallback retained dirty work");
    }

    private static void repeatedDeferredPatchesStayBounded() {
        var storage = published(MAXIMUM_BYTES);
        long bounded = MAXIMUM_BYTES + MAXIMUM_BYTES / 32L;
        check(storage.retainedBytes() == bounded, "maximum shadow/BitSet capacity is not exact");
        byte[] expected = new byte[MAXIMUM_BYTES];
        byte[] patchBytes = new byte[32];
        var input = ByteBuffer.wrap(patchBytes);
        input.position(4).limit(28);
        for (int i = 0; i < DEFERRED_PATCHES; i++) {
            for (int j = 4; j < 28; j++) patchBytes[j] = (byte) (i + j);
            int offset = i % 257 == 0 ? MAXIMUM_BYTES - 24 : (i * 37 % 64) * 4;
            storage.patch(offset, input);
            System.arraycopy(patchBytes, 4, expected, offset, 24);
        }
        check(input.position() == 4 && input.limit() == 28, "deferred patching changed the caller range");
        check(storage.retainedBytes() == bounded, "retained bytes grew with deferred patch count");
        byte[] gpu = new byte[MAXIMUM_BYTES];
        storage.flushPatches(DEFERRED_PATCHES + 1L, (generation, offset, values) ->
                System.arraycopy(values, 0, gpu, offset, values.length));
        equal(expected, gpu, "deferred overlap final bytes differ");
        check(flush(storage, DEFERRED_PATCHES + 1L).isEmpty(), "deferred dirty ranges were not cleared");
        check(storage.retainedBytes() == bounded, "flush changed retained capacity unexpectedly");
        storage.clear();
        check(storage.retainedBytes() == 0, "clear did not release maximum-capacity state");
    }

    private static void clearAndEmptyPublication() {
        var storage = published(16);
        storage.patch(4, bytes(1));
        storage.clear();
        storage.clear();
        check(storage.capacity() == 0 && storage.retainedBytes() == 0 && storage.fullPublication() == null, "clear retained storage or a full publication");
        check(flush(storage, 0).isEmpty(), "unpublished generation zero must not reject an empty flush");
        check(flush(storage, 40).isEmpty(), "clear retained dirty uploads");
        expect(IllegalStateException.class, () -> storage.patch(0, bytes(2)));
        storage.publish(ByteBuffer.allocate(0));
        check(storage.fullPublication() != null && storage.fullPublication().length == 0, "aligned empty publication must stay representable");
        check(storage.capacity() == 0 && storage.retainedBytes() == 0, "empty publication allocated payload capacity");
        check(flush(storage, 0).isEmpty(), "full pending upload must suppress patch generation validation");
        storage.publicationApplied();
        check(flush(storage, 0).isEmpty(), "no dirty work must not require a patch generation");
        check(storage.fullPublication() == null && flush(storage, 41).isEmpty(), "empty publication acknowledgement failed");
    }

    private static RtxptApplicationData published(int bytes) {
        var storage = new RtxptApplicationData();
        storage.publish(ByteBuffer.wrap(new byte[bytes]));
        storage.publicationApplied();
        return storage;
    }

    private static List<Write> flush(RtxptApplicationData storage, long generation) {
        var writes = new ArrayList<Write>();
        storage.flushPatches(generation, (value, offset, data) -> writes.add(new Write(value, offset, data)));
        return writes;
    }

    private static ByteBuffer bytes(int value) {
        return ByteBuffer.wrap(new byte[]{(byte) value, (byte) value, (byte) value, (byte) value});
    }

    private static byte[] sequence(int bytes, int start) {
        byte[] values = new byte[bytes];
        for (int i = 0; i < bytes; i++) values[i] = (byte) (start + i);
        return values;
    }

    private static void equal(byte[] expected, byte[] actual, String message) {
        check(Arrays.equals(expected, actual), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private record Write(long generation, int offset, byte[] data) { }
}
