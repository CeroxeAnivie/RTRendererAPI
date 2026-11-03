package top.ceroxe.rt.renderer.rtxpt;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Same black-box retained transaction workload can run against a published baseline provider. */
public final class RtxptRetainedTransactionBenchmark {
    public static void main(String[] args)throws Exception {
        int count=args.length>0?Integer.parseInt(args[0]):4096;
        var initial=SceneTransaction.builder(1).resetScene();
        for(long id=1;id<=count;id++)initial.upsert(MaterialAsset.builder(id).build())
            .upsert(MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{id}).build())
            .upsert(SceneInstance.builder(id,id).build());
        var scene=new RtxptScene();scene.retain(initial.build());
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        for(String kind:List.of("light","material")){
            var samples=new long[1000];long allocated=0,cpu=0;
            for(int i=-200;i<1000;i++){
                var builder=SceneTransaction.builder(i+10000);
                if(kind.equals("light"))builder.upsert(SceneLight.point(1,0,0,1).range(5).build());
                else builder.upsert(MaterialAsset.builder(1).baseColorRgba8((i&1)==0?0xff808080:0xff404040).build());
                var tx=builder.build();long a=bean.getThreadAllocatedBytes(thread),c=bean.getCurrentThreadCpuTime(),start=System.nanoTime();
                var next=new RtxptScene(scene);next.retain(tx);scene=next;
                long elapsed=System.nanoTime()-start;
                if(i>=0){samples[i]=elapsed;allocated+=bean.getThreadAllocatedBytes(thread)-a;cpu+=bean.getCurrentThreadCpuTime()-c;}
            }
            Arrays.sort(samples);System.out.printf(Locale.ROOT,"TRANSACTION_BENCH kind=%s meshes=%d samples=1000 p50Ns=%d p95Ns=%d p99Ns=%d maxNs=%d cpuNsPerOp=%d allocatedBytesPerOp=%d%n",
                kind,count,samples[499],samples[949],samples[989],samples[999],cpu/1000,allocated/1000);
        }
    }
}
