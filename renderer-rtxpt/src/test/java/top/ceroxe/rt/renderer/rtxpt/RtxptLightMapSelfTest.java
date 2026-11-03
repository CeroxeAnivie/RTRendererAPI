package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

public final class RtxptLightMapSelfTest {
    public static void main(String[] args){
        var map=new RtxptLightMap();var reference=new TreeMap<Long,SceneLight>();var random=new Random(706);
        for(int i=0;i<160000;i++){var light=SceneLight.point(i,i%100,2,i/100).range(20).build();map.put((long)i,light);reference.put((long)i,light);}
        var original=new RtxptLightMap(map);var originalReference=new TreeMap<>(reference);
        for(int batch=0;batch<200;batch++){
            var previous=new RtxptLightMap(map);var before=new TreeMap<>(reference);
            for(int edit=0;edit<18;edit++){
                long id=random.nextInt(170000);
                if(random.nextBoolean()){var light=SceneLight.point(id,random.nextDouble()*8,2,1).color(.2f,.6f,1).range(8).build();require(Objects.equals(map.put(id,light),reference.put(id,light)),"Previous upsert value");}
                else require(Objects.equals(map.remove(id),reference.remove(id)),"Previous removed value");
            }
            require(previous.equals(before),"Old transaction snapshot remains immutable");
            require(map.size()==reference.size()&&new ArrayList<>(map.entrySet()).equals(new ArrayList<>(reference.entrySet())),"Ordered reference matches after mixed edits");
        }
        require(original.equals(originalReference),"Original 160k snapshot survives all edits");
        var tx=new RtxptScene();tx.lights.putAll(map);var copy=new RtxptScene(tx);copy.lights.clear();require(tx.lights.equals(reference),"Scene reset cannot modify the resident snapshot");
        System.out.println("RTXPT_LIGHT_MAP PASS 160000 resident, 200 mixed batches, immutable snapshots, ordered TreeMap reference");
    }
}
