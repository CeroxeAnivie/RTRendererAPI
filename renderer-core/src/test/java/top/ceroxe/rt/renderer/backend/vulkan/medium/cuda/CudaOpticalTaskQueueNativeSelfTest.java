package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Independent original-ID oracle for the production queue producer and reset. */
public final class CudaOpticalTaskQueueNativeSelfTest {
    private static final String PROBE="""
        extern "C" __global__ void optical_task_queue_probe(unsigned char*b,int count){
            unsigned int*h=(unsigned int*)b;
            const unsigned int slot=blockIdx.x*blockDim.x+threadIdx.x;
            if(blockIdx.x*blockDim.x>=h[52])return;
            unsigned int continued=0u;
            if(slot<(unsigned int)count&&slot<h[52]){
                const unsigned int item=rtr_optical::queuedInputItem(h,b,slot);
                ((unsigned int*)(b+h[11]))[item]++;
                continued=item%5u!=0u;
            }
            rtr_optical::queueContinued(b,count,continued);
        }
        """;

    public static void main(String[] args){
        int checks=0;
        try(var cuda=CudaMediumRuntime.open(0,16L*1024*1024);
                var reset=cuda.compileBatchKernel(CudaOpticalMediumSource.source(),"native_optical_reset");
                var probe=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+PROBE,"optical_task_queue_probe")){
            for(int layout:new int[]{0,2,18})for(int count:new int[]{1,31,32,33,255,256,257,1069}){
                int stateBytes=(layout&2)!=0?112:256;
                int witness=384+count*stateBytes,first=(witness+count*4+15)&~15;
                int stride=(count*4+15)&~15,second=first+stride,control=second+stride,bytes=control+64;
                try(Arena arena=Arena.ofConfined();var memory=cuda.allocate(bytes)){
                    var host=arena.allocate(bytes,16);host.fill((byte)0);
                    var data=host.asByteBuffer().order(ByteOrder.nativeOrder());
                    data.putInt(8,count).putInt(36,384).putInt(44,witness).putInt(68,1)
                            .putInt(188,layout|256|65536).putInt(200,first).putInt(204,second).putInt(236,control);
                    for(int i=0;i<count;++i){int mode=i%3==0?1:i%3==1?2:3;
                        int word=(layout&16)!=0?8*count+i:i*(stateBytes/4)+8;
                        data.putInt(384+word*4,mode);
                    }
                    cuda.upload(memory.slice(0,bytes),host);
                    Set<Integer> expected=new HashSet<>();for(int i=0;i<count;++i)expected.add(i);
                    for(int round=0;round<3;++round){
                        cuda.launchBlocking(reset,memory.slice(0,bytes),1,384);
                        cuda.launchBlocking(probe,memory.slice(0,bytes),count,1);
                        cuda.download(memory.slice(0,bytes),host);
                        Set<Integer> survivors=new HashSet<>();for(int id:expected)if(id%5!=0)survivors.add(id);
                        int main=data.getInt(control+16),connections=data.getInt(control+32);
                        require(main+connections==survivors.size()&&data.getInt(212)==survivors.size(),"counts");
                        int queue=data.getInt(220);Set<Integer> actual=new HashSet<>();
                        for(int kind=0;kind<2;++kind)for(int at=0;at<(kind==0?main:connections);++at){
                            int index=kind==0?at:count-1-at,id=data.getInt(queue+index*4);
                            require(survivors.contains(id)&&actual.add(id),"missing/duplicate queue ID");
                            require((id%3!=0)==(kind!=0),"mixed work type");
                        }
                        require(actual.equals(survivors),"ID set");
                        for(int i=0;i<count;++i)require(data.getInt(witness+i*4)==(i%5==0?1:round+1),"consumed twice or omitted");
                        for(int offset:new int[]{20,24,36,40})require(data.getInt(control+offset)==1,"indirect height/depth");
                        // Leave h[17] unchanged deliberately: an empty indirect launch
                        // has no raygen invocation to clear the initialization marker.
                        expected=survivors;checks++;
                    }
                }
            }
        }
        System.out.println("CudaOpticalTaskQueueNativeSelfTest passed: "+checks+" queue swaps, exact IDs/types/counts, partial blocks and empty launches");
    }
    private static void require(boolean passed,String detail){if(!passed)throw new AssertionError("optical task queue: "+detail);}
}
