package top.ceroxe.rt.renderer.rtxpt;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Primitive-key scene storage following the 5.0.0 retained authority's hash-table model.
 * Immutable API assets are retained directly. Forks cost O(1); updates journal only changed
 * identities. A successful native frame retires historical cells at its explicit boundary.
 * No complete map copies or persistent-tree rotations occur in the ordinary update lane.
 */
final class RtxptSceneTable<V> extends AbstractMap<Long,V> {
    private static final class Cell<V> {
        final long epoch;
        final V value;
        Cell<V> previous;
        Cell(long epoch,V value,Cell<V> previous) {
            this.epoch=epoch; this.value=value; this.previous=previous;
        }
    }
    private record Change(long epoch,long key) { }
    private static final class Store<V> {
        final Long2ObjectOpenHashMap<Cell<V>> entries=new Long2ObjectOpenHashMap<>();
        final ArrayList<Change> changes=new ArrayList<>();
        long epoch,retiredBefore;
        Store<V> forkParent;
        long forkEpoch;
    }
    private Store<V> store;
    private long epoch;
    private int count;
    RtxptSceneTable() { store=new Store<>(); }
    RtxptSceneTable(RtxptSceneTable<V> source) {
        store=source.store; epoch=source.epoch; count=source.count;
    }
    boolean sameSnapshot(RtxptSceneTable<?> other) {
        return store==other.store && epoch==other.epoch;
    }
    private void readable() {
        if(epoch<store.retiredBefore)
            throw new IllegalStateException("Scene snapshot used after its native completion boundary");
    }
    private V value(long key) {
        readable();
        var cell=store.entries.get(key);
        while(cell!=null && cell.epoch>epoch) cell=cell.previous;
        return cell==null?null:cell.value;
    }
    @Override public V get(Object key) { return key instanceof Long id?value(id):null; }
    V get(long key) { return value(key); }
    @Override public boolean containsKey(Object key) { return get(key)!=null; }
    boolean containsKey(long key) { return value(key)!=null; }
    @Override public int size() { return count; }
    private void writable() {
        readable();
        if(epoch==store.epoch) return;
        // Only branching from a superseded/rejected transaction requires detaching.
        // Valid successive publications always append to the active primitive table.
        var detached=new Store<V>();
        for(var entry:entrySet())
            detached.entries.put(entry.getKey().longValue(),new Cell<>(0,entry.getValue(),null));
        detached.forkParent=store; detached.forkEpoch=epoch;
        store=detached; epoch=0;
    }
    @Override public V put(Long key,V value) {
        Objects.requireNonNull(key,"key"); Objects.requireNonNull(value,"value");
        return putValue(key,value);
    }
    V put(long key,V value) { return putValue(key,Objects.requireNonNull(value,"value")); }
    private V putValue(long key,V value) {
        V previous=value(key);
        if(previous==value) return previous;
        writable();
        epoch=++store.epoch;
        store.entries.put(key,new Cell<>(epoch,value,store.entries.get(key)));
        store.changes.add(new Change(epoch,key));
        if(previous==null && value!=null) count++;
        if(previous!=null && value==null) count--;
        return previous;
    }
    @Override public V remove(Object key) {
        return key instanceof Long id?putValue(id,null):null;
    }
    @Override public void clear() {
        if(count==0) return;
        long[] keys=entrySet().stream().mapToLong(e->e.getKey()).toArray();
        for(long key:keys) putValue(key,null);
    }
    long forEachChanged(RtxptSceneTable<V> before,BiConsumer<Long,V> action) {
        Objects.requireNonNull(action,"action"); readable(); before.readable();
        if(sameSnapshot(before)) return 0;
        var keys=new LongOpenHashSet(); long inspected=0;
        if(store==before.store && epoch>=before.epoch) {
            int low=0,high=store.changes.size();
            while(low<high) {
                int middle=(low+high)>>>1;
                if(store.changes.get(middle).epoch<=before.epoch) low=middle+1;
                else high=middle;
            }
            for(int i=low;i<store.changes.size();i++) {
                var change=store.changes.get(i);
                if(change.epoch>epoch) break;
                inspected++; keys.add(change.key);
            }
        } else if(store.forkParent==before.store && store.forkEpoch==before.epoch) {
            // A retry/fixture fork keeps the exact base token: only its own journal
            // can differ. Do not scan both complete tables to rediscover that fact.
            for(var change:store.changes) {
                if(change.epoch>epoch) break;
                inspected++; keys.add(change.key);
            }
        } else {
            for(var entry:entrySet()) { keys.add(entry.getKey().longValue()); inspected++; }
            for(var entry:before.entrySet()) { keys.add(entry.getKey().longValue()); inspected++; }
        }
        long[] ordered=keys.toLongArray(); Arrays.sort(ordered);
        for(long key:ordered) {
            V next=value(key);
            if(next!=before.value(key)) action.accept(key,next);
        }
        return inspected;
    }
    /** Called only after the current native frame and its resource import have completed. */
    void retireSnapshots() {
        if(epoch!=store.epoch) writable();
        store.forkParent=null;
        if(store.changes.isEmpty()) return;
        var dirty=new LongOpenHashSet();
        for(var change:store.changes) dirty.add(change.key);
        for(long key:dirty) {
            var cell=store.entries.get(key);
            if(cell.value==null) store.entries.remove(key);
            else cell.previous=null;
        }
        store.changes.clear(); store.retiredBefore=epoch;
    }
    int retainedChanges() { return store.changes.size(); }
    @Override public Set<Map.Entry<Long,V>> entrySet() {
        readable();
        return new AbstractSet<>() {
            @Override public int size() { return count; }
            @Override public Iterator<Map.Entry<Long,V>> iterator() {
                long[] keys=store.entries.keySet().toLongArray();
                return new Iterator<>() {
                    int cursor; Map.Entry<Long,V> next;
                    private void advance() {
                        while(next==null && cursor<keys.length) {
                            long key=keys[cursor++]; V item=value(key);
                            if(item!=null) next=new SimpleImmutableEntry<>(key,item);
                        }
                    }
                    @Override public boolean hasNext() { advance(); return next!=null; }
                    @Override public Map.Entry<Long,V> next() {
                        advance(); if(next==null) throw new NoSuchElementException();
                        var result=next; next=null; return result;
                    }
                };
            }
        };
    }
}
