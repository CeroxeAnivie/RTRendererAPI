package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;

/** Exact snapshot, failure-branch, change-journal and retirement checks. No GPU required. */
public final class RtxptSceneTableSelfTest {
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var table=new RtxptSceneTable<Object>();
        for(long i=0;i<4096;i++) table.put(i,new Object());
        table.retireSnapshots();
        long inspected=0;
        for(int frame=0;frame<1000;frame++) {
            var before=new RtxptSceneTable<>(table);
            long key=frame%4096; Object old=before.get(key),next=new Object();
            table.put(key,next);
            check(before.get(key)==old,"publication mutated its prior generation");
            int[] changes={0};
            long visited=table.forEachChanged(before,(id,value)->{
                check(id==key && value==next,"incorrect dirty identity/value"); changes[0]++;
            });
            check(changes[0]==1 && visited==1,"one-object update scanned the scene");
            inspected+=visited; table.retireSnapshots();
            check(table.retainedChanges()==0,"native completion retained the update journal");
            check(table.size()==4096,"retirement changed scene ownership");
            check(table.forEachChanged(new RtxptSceneTable<>(table),(id,value)->{
                throw new AssertionError("stable scene produced work");
            })==0,"stable scene was traversed");
        }
        var accepted=new RtxptSceneTable<>(table);
        var rejected=new RtxptSceneTable<>(accepted);
        rejected.put(50000L,new Object());
        var retry=new RtxptSceneTable<>(accepted);
        retry.put(60000L,new Object());
        check(!retry.containsKey(50000L),"rejected transaction leaked into retry");
        check(!accepted.containsKey(50000L)&&!accepted.containsKey(60000L),"fork mutated its accepted source");
        check(rejected.containsKey(50000L)&&!rejected.containsKey(60000L),"branches share mutable values");
        var unchangedAfterRejection=new RtxptSceneTable<>(accepted);
        unchangedAfterRejection.retireSnapshots();
        check(!unchangedAfterRejection.containsKey(50000L),"retirement published a rejected update");
        check(unchangedAfterRejection.size()==4096,"retirement lost an unchanged accepted table");
        var random=new Random(0x50008000L);
        var state=new RtxptSceneTable<Object>();
        var oracle=new HashMap<Long,Object>();
        for(int round=0;round<100;round++) {
            var before=new RtxptSceneTable<>(state);
            var oldOracle=new HashMap<>(oracle);
            for(int edit=0;edit<40;edit++) {
                long key=random.nextInt(128);
                if(random.nextBoolean()) {Object value=new Object();state.put(key,value);oracle.put(key,value);}
                else {state.remove(key);oracle.remove(key);}
            }
            check(state.equals(oracle)&&before.equals(oldOracle),"randomized immutable snapshot mismatch");
            var actual=new HashMap<Long,Object>();
            state.forEachChanged(before,actual::put);
            var keys=new HashSet<Long>(oracle.keySet());keys.addAll(oldOracle.keySet());
            for(long key:keys) {
                boolean changed=oracle.get(key)!=oldOracle.get(key);
                check(actual.containsKey(key)==changed,"journal missed or fabricated a change");
                if(changed)check(actual.get(key)==oracle.get(key),"journal returned an old generation");
            }
            state.retireSnapshots();
        }
        var clearedBefore=new RtxptSceneTable<>(state);
        state.clear();
        check(state.isEmpty()&&!clearedBefore.isEmpty(),"reset discarded a live snapshot");
        System.out.println("SCENE_TABLE PASS resources=4096 frames=1000 changedIdentityVisits="+inspected
                +" stableWork=0 randomizedPublications=100 branchIsolation=true boundedRetirement=true");
    }
}
