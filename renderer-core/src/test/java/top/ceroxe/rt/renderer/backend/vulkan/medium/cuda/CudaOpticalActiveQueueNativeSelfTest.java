package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.Set;

/** Uses the production reset/queue helpers, including partial warps and empty successor queues. */
public final class CudaOpticalActiveQueueNativeSelfTest {
    private static final String PROBE="""
        extern "C" __global__ void active_queue_probe(unsigned char*b,int count){
            unsigned int*h=(unsigned int*)b,slot=blockIdx.x*blockDim.x+threadIdx.x;
            unsigned int statistics[4]={0u,0u,0u,0u};
            if(slot<(unsigned int)count&&slot<h[52]){
                unsigned int item=h[54]?((unsigned int*)(b+h[54]))[slot]:slot;
                statistics[0]=h[56]<2u&&item%(h[56]==0u?4u:3u)!=0u?1u:0u;
            }
            rtr_optical::queueContinued(b,count,statistics[0]);
            rtr_optical::reduceStatistics(b,statistics);
        }
        """;
    public static void main(String[] args){
        try(var cuda=CudaMediumRuntime.open(0);var arena=Arena.ofConfined();
            var reset=cuda.compileBatchKernel(CudaOpticalMediumSource.source(),"native_optical_reset");
            var probe=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+PROBE,"active_queue_probe")){
            for(int count:new int[]{1,31,32,33,255,256,257,1069}){
                int stride=(count*4+15)&~15,first=384,second=first+stride,guard=second+stride,bytes=guard+16;
                try(var records=cuda.allocate(bytes)){
                    var host=arena.allocate(bytes,16);
                    host.set(ValueLayout.JAVA_INT,8,count);host.set(ValueLayout.JAVA_INT,68,1);
                    host.set(ValueLayout.JAVA_INT,188,256);host.set(ValueLayout.JAVA_INT,200,first);
                    host.set(ValueLayout.JAVA_INT,204,second);host.asSlice(guard,16).fill((byte)0x5a);
                    Set<Integer> expected=new HashSet<>();for(int i=0;i<count;i++)expected.add(i);
                    for(int phase=0;phase<4;phase++){
                        host.set(ValueLayout.JAVA_INT,68,phase==0?1:0);host.set(ValueLayout.JAVA_INT,224,phase);
                        cuda.upload(records.slice(0,bytes),host);
                        cuda.launchBlocking(reset,records.slice(0,bytes),1,1);
                        cuda.launchBlocking(probe,records.slice(0,bytes),count,1);
                        cuda.download(records.slice(0,bytes),host);
                        final int pass=phase;expected.removeIf(i->pass>=2||i%(pass==0?4:3)==0);
                        int found=host.get(ValueLayout.JAVA_INT,212),offset=host.get(ValueLayout.JAVA_INT,220);
                        require(found==expected.size()&&host.get(ValueLayout.JAVA_INT,112)==found,"exact queue/statistics count");
                        require(offset==(phase%2==0?first:second),"ping-pong output queue");
                        Set<Integer> actual=new HashSet<>();
                        for(int i=0;i<found;i++)require(actual.add(host.get(ValueLayout.JAVA_INT,offset+i*4L)),"unique original path identity");
                        require(actual.equals(expected),"surviving identities preserved: count="+count+" phase="+phase);
                        for(int i=0;i<16;i++)require(host.get(ValueLayout.JAVA_BYTE,guard+i)==(byte)0x5a,"queue capacity guard");
                    }
                }
            }
        }
        System.out.println("CudaOpticalActiveQueueNativeSelfTest passed: 8 capacities, partial warps, exact counters, identity, ping-pong and empty queues");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
