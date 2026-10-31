package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.MediumRayQueryPipeline;

/** Runs production shared slots and packaged shaders against real scene BLAS/TLAS. */
public final class CudaSharedMediumRayNativeSelfTest {
    public static void main(String[] args) throws Exception {
        String wrapper;
        try(var stream=CudaSharedMediumRayNativeSelfTest.class.getResourceAsStream("/medium/shared_ray_test.cu")) {
            if(stream==null)throw new AssertionError("missing test continuation");
            wrapper=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        var capability=VulkanRtCapabilityProbe.capture();
        try(var device=VulkanDeviceRuntime.open(capability,RendererRtDiagnostics.noop(),true,false);
            var cuda=CudaMediumRuntime.open(0);
            var scene=new Scene(device);
            var init=cuda.compileBatchKernel(wrapper,"test_init_rays");
            var advance=cuda.compileBatchKernel(wrapper,"test_advance_rays");
            var shadow=cuda.compileBatchKernel(wrapper,"test_init_shadow")) {
            verifyBlockingExchange(device,cuda);
            for(int session=0;session<2;session++) {
                // A later lifecycle replaces retired slots with larger capacity; no old buffer is rebound in flight.
                try(var first=new CudaSharedRayFrame(device,cuda,4096*(session+1),4L<<20);
                    var second=new CudaSharedRayFrame(device,cuda,4096*(session+1),4L<<20);
                    var firstQuery=new MediumRayQueryPipeline(device,scene.tlas(),first);
                    var secondQuery=new MediumRayQueryPipeline(device,scene.tlas(),second)) {
                    CudaSharedRayFrame[] frames={first,second};MediumRayQueryPipeline[] queries={firstQuery,secondQuery};
                    try(var pairHost=cuda.allocateHost(first.byteSize())) {
                        for(int pair=0;pair<64;pair++) {
                            first.produceThenExchangePairBlocking(command->{},records->cuda.launchBlocking(init,records,4096,128),
                                    command->firstQuery.record(command,4096),records->{
                                        cuda.launchBlocking(advance,records,4096,128);
                                        cuda.downloadAsync(records,pairHost);
                                    },pair>=16&&pair<56,pair%16>=8);
                            for(int path=0;path<4096;path++){
                                int hits=pairHost.getInt(path*128L+96),errors=pairHost.getInt(path*128L+100);
                                if(errors!=0||Float.intBitsToFloat(hits)!=(path%4==0?0:1))
                                    throw new AssertionError("queued RT epoch pair="+pair+" path="+path+" hits="+Float.intBitsToFloat(hits)+" errors="+errors);
                            }
                            if(pair%11==0)first.acquireForVulkanBlocking();
                        }
                    }
                    verifyBatchEpochs(cuda,first,firstQuery,init,advance);
                    for(int pass=0;pass<3;pass++) {
                        for(int slot=0;slot<2;slot++){final int index=slot;frames[slot].exchangeBlocking(records->cuda.launchBlocking(init,records,4096,128),cmd->queries[index].record(cmd,4096));}
                        for(int round=1;round<5;round++)for(int slot=0;slot<2;slot++){final int index=slot;frames[slot].exchangeBlocking(records->cuda.launchBlocking(advance,records,4096,128),cmd->queries[index].record(cmd,4096));}
                        for(var frame:frames)verify(cuda,frame,false);
                    }
                    first.exchangeBlocking(records->cuda.launchBlocking(shadow,records,4096,128),cmd->firstQuery.record(cmd,4096));
                    verify(cuda,first,true);
                }
                if(cuda.residentBytes()!=0)throw new AssertionError("shared slot registration leaked bytes="+cuda.residentBytes());
            }
        }
        System.out.println("CudaSharedMediumRayNativeSelfTest passed: two slots x two lifecycles x three fresh paths x five rounds; closest/occlusion/epoch/full-array checks; 2/4/6/8-round batches, every-round failure drain, blocking copy and nested exchange");
    }
    private static void verifyBatchEpochs(CudaMediumRuntime cuda,CudaSharedRayFrame frame,MediumRayQueryPipeline query,
            CudaMediumRuntime.BatchKernel init,CudaMediumRuntime.BatchKernel advance){
        var outputs=new CudaMediumRuntime.HostMemory[8];
        try {
            for(int round=0;round<outputs.length;round++)outputs[round]=cuda.allocateHost(frame.byteSize());
            for(int batch=0;batch<16;batch++){
                int rounds=new int[]{4,8,6,2}[batch%4];
                frame.produceThenExchangeBatchBlocking(rounds,(round,command)->{
                    if(round>0)query.record(command,4096);
                },(round,records)->{
                    cuda.launchBlocking(round==0?init:advance,records,4096,128);
                    cuda.downloadAsync(records,outputs[round]);
                });
                for(int round=0;round<rounds;round++)for(int path=0;path<4096;path++){
                    int hits=outputs[round].getInt(path*128L+96),errors=outputs[round].getInt(path*128L+100);
                    float expected=path%4==0?0:Math.min(round,2);
                    if(errors!=0||Float.intBitsToFloat(hits)!=expected)
                        throw new AssertionError("queued batch epoch batch="+batch+" round="+round+" path="+path
                                +" hits="+Float.intBitsToFloat(hits)+" expected="+expected+" errors="+errors);
                }
                if(rounds>=4)verify(cuda,frame,false);
                // Transition from a retained batch back through both pair protocols.
                frame.produceThenExchangePairBlocking(command->{},records->cuda.launchBlocking(init,records,4096,128),
                        command->query.record(command,4096),records->cuda.launchBlocking(advance,records,4096,128),true,batch%2==0);
                if(batch%3==0)frame.acquireForVulkanBlocking();
            }
        }finally{for(var output:outputs)if(output!=null)output.close();}
    }
    private static void verifyBlockingExchange(VulkanDeviceRuntime device,CudaMediumRuntime cuda) {
        String fill="extern \"C\" __global__ void exchange_fill(unsigned int *p,int count){int i=blockIdx.x*blockDim.x+threadIdx.x;if(i<count)p[i]=0x34567890u;}";
        String verifyAndPublish="extern \"C\" __global__ void exchange_verify_publish(unsigned int *p,int count){int i=blockIdx.x*blockDim.x+threadIdx.x;if(i<count){p[i]=p[i]==0x34567890u?0x13572468u:0xdeadc0deu;__threadfence_system();}}";
        String verifyCopied="extern \"C\" __global__ void exchange_verify_copied(unsigned int *p,int count){int i=blockIdx.x*blockDim.x+threadIdx.x;if(i<count&&p[32+i]!=0x13572468u)p[32+i]=0xdeadbeefu;}";
        try(var kernel=cuda.compileBatchKernel(fill,"exchange_fill");var arena=Arena.ofConfined();
            var verifier=cuda.compileBatchKernel(verifyAndPublish,"exchange_verify_publish");
            var copied=cuda.compileBatchKernel(verifyCopied,"exchange_verify_copied");
            var frame=CudaSharedRayFrame.storage(device,cuda,256,1L<<20);
            var nested=CudaSharedRayFrame.storage(device,cuda,128,1L<<20);
            var diagnostic=Boolean.getBoolean("top.ceroxe.rt.medium.interopEdgeDiagnostics")
                    ?top.ceroxe.rt.renderer.rt.device.RtGpuBuffer.createHostVisibleBuffer(device.device(),device.allocator(),256,
                        org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT):null) {
            var host=arena.allocate(128,16);
            try(var firstHost=cuda.allocateHost(128);var secondHost=cuda.allocateHost(128)) {
                for(int pair=0;pair<32;pair++) {
                    frame.produceThenExchangePairBlocking(command->org.lwjgl.vulkan.VK10.vkCmdFillBuffer(
                            command,frame.buffer(),0,256,0x34567890),storage->{
                                cuda.launchBlocking(verifier,storage,32,4);
                                cuda.downloadAsync(storage.slice(0,128),firstHost);
                            },command->{
                                try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                                    var copy=org.lwjgl.vulkan.VkBufferCopy.calloc(1,stack)
                                            .srcOffset(0).dstOffset(128).size(128);
                                    org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(command,frame.buffer(),frame.buffer(),copy);
                                    if(diagnostic!=null) {
                                        var dependency=org.lwjgl.vulkan.VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                                                .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                                                .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT)
                                                .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                                                .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                                                .buffer(frame.buffer()).offset(0).size(256);
                                        org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,dependency,null);
                                        copy.srcOffset(0).dstOffset(0).size(256);
                                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(command,frame.buffer(),diagnostic.buffer(),copy);
                                        var hostDependency=org.lwjgl.vulkan.VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                                                .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                                                .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT)
                                                .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                                                .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                                                .buffer(diagnostic.buffer()).offset(0).size(256);
                                        org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT,0,null,hostDependency,null);
                                    }
                                }
                            },storage->{
                                cuda.launchBlocking(copied,storage,32,4);
                                cuda.downloadAsync(storage.slice(128,128),secondHost);
                            },pair>=8&&pair<28,pair%8>=4);
                    long firstValue=pair*2L+1L,secondValue=firstValue+1L;
                    for(int word=0;word<32;word++) {
                        if(firstHost.getInt(word*4L)!=0x13572468||secondHost.getInt(word*4L)!=0x13572468)
                            throw new AssertionError("queued pair ordering pair="+pair+" word="+word
                                    +" firstValue="+firstValue+" secondValue="+secondValue
                                    +" first="+Integer.toHexString(firstHost.getInt(word*4L))
                                    +" second="+Integer.toHexString(secondHost.getInt(word*4L))
                                    +edgeDiagnostic(diagnostic,word));
                    }
                    if(pair%7==0)frame.acquireForVulkanBlocking();
                }
            }
            for(int round=0;round<16;round++) {
                frame.produceThenExchangeBlocking(command->org.lwjgl.vulkan.VK10.vkCmdFillBuffer(
                        command,frame.buffer(),0,128,0),storage->cuda.launchBlocking(kernel,storage,32,4),
                        command->{});
                frame.nativeOnlyBlocking(storage->{cuda.download(storage,host);requireFilled(host);});
            }
            // The nested legacy path must finish the outer stream's wait before submitting
            // another Vulkan release, and restore the outer batch after the inner fence.
            frame.exchangeBlocking(storage->{
                cuda.launchBlocking(kernel,storage,32,4);
                nested.exchangeBlocking(other->cuda.launchBlocking(kernel,other,32,4),command->{});
                cuda.download(storage,host);requireFilled(host);
            },command->{});
            for(boolean binary:new boolean[]{false,true})for(boolean retained:new boolean[]{false,true})for(int stage=0;stage<4;stage++) {
                final int failureStage=stage;
                RuntimeException injected=new IllegalStateException("paired exchange callback failure stage="+stage);
                try(var failed=CudaSharedRayFrame.storage(device,cuda,128,1L<<20)) {
                    // Exercise failures after a completed externally-owned pair too.
                    failed.produceThenExchangePairBlocking(command->{},storage->cuda.launchBlocking(kernel,storage,32,4),
                            command->{},storage->cuda.launchBlocking(kernel,storage,32,4),retained,binary);
                    try {
                        failed.produceThenExchangePairBlocking(command->{if(failureStage==0)throw injected;},
                                storage->{cuda.launchBlocking(kernel,storage,32,4);if(failureStage==1)throw injected;},
                                command->{if(failureStage==2)throw injected;},
                                storage->{cuda.launchBlocking(kernel,storage,32,4);if(failureStage==3)throw injected;},retained,binary);
                        throw new AssertionError("paired exchange lost callback failure");
                    } catch(RuntimeException failure) {if(failure!=injected)throw failure;}
                }
                frame.nativeOnlyBlocking(storage->{
                    cuda.launchBlocking(kernel,storage,32,4);cuda.download(storage,host);requireFilled(host);
                });
            }
            for(int failureRound=0;failureRound<8;failureRound++)for(boolean recordFailure:new boolean[]{false,true}){
                final int selectedRound=failureRound;
                RuntimeException injected=new IllegalStateException("batch callback failure round="+failureRound+" record="+recordFailure);
                try(var failed=CudaSharedRayFrame.storage(device,cuda,128,1L<<20)){
                    failed.produceThenExchangeBatchBlocking(2,(round,command)->{},
                            (round,storage)->cuda.launchBlocking(kernel,storage,32,4));
                    try {
                        failed.produceThenExchangeBatchBlocking(8,(round,command)->{
                            if(recordFailure&&round==selectedRound)throw injected;
                        },(round,storage)->{
                            cuda.launchBlocking(kernel,storage,32,4);
                            if(!recordFailure&&round==selectedRound)throw injected;
                        });
                        throw new AssertionError("batch exchange lost callback failure");
                    }catch(RuntimeException failure){if(failure!=injected)throw failure;}
                }
                frame.nativeOnlyBlocking(storage->{cuda.launchBlocking(kernel,storage,32,4);cuda.download(storage,host);requireFilled(host);});
            }
            for(boolean consumerFailure:new boolean[]{false,true}) {
                RuntimeException injected=new IllegalStateException("queued exchange callback failure");
                try(var failed=CudaSharedRayFrame.storage(device,cuda,128,1L<<20)) {
                    try {
                        failed.exchangeBlocking(storage->{
                            cuda.launchBlocking(kernel,storage,32,4);
                            if(!consumerFailure)throw injected;
                        },command->{if(consumerFailure)throw injected;});
                        throw new AssertionError("exchange lost callback failure");
                    } catch(RuntimeException failure) {if(failure!=injected)throw failure;}
                }
                frame.nativeOnlyBlocking(storage->{
                    cuda.launchBlocking(kernel,storage,32,4);cuda.download(storage,host);requireFilled(host);
                });
            }
        }
        if(cuda.residentBytes()!=0)throw new AssertionError("blocking regression leaked shared storage");
    }
    private static void requireFilled(MemorySegment bytes) {
        for(long offset=0;offset<bytes.byteSize();offset+=4)
            if(bytes.get(ValueLayout.JAVA_INT,offset)!=0x34567890)
                throw new AssertionError("CUDA/Vulkan blocking visibility at byte "+offset);
    }
    private static String edgeDiagnostic(top.ceroxe.rt.renderer.rt.device.RtGpuBuffer diagnostic,int word) {
        if(diagnostic==null)return "";
        var view=ByteBuffer.wrap(diagnostic.readBytes()).order(ByteOrder.nativeOrder());
        return " VulkanObservedSource="+Integer.toHexString(view.getInt(word*4))
                +" VulkanCopiedDestination="+Integer.toHexString(view.getInt(128+word*4));
    }
    private static void verify(CudaMediumRuntime cuda,CudaSharedRayFrame frame,boolean shadow) {
        try(var arena=Arena.ofConfined()) {
            MemorySegment bytes=arena.allocate(frame.byteSize());
            frame.nativeOnlyBlocking(records->cuda.download(records,bytes));
            ByteBuffer data=bytes.asByteBuffer().order(ByteOrder.nativeOrder());
            int errors=0;
            for(int i=0;i<4096;i++) {
                int at=i*128;boolean expectedHit=i%4!=0;
                for(int lane=0;lane<32;lane++)if((lane<8||lane>=12&&lane<16||lane>=20)&&!Float.isFinite(data.getFloat(at+lane*4)))errors++;
                if(data.getInt(at+40)!=i)errors++;
                if(shadow){if((data.getFloat(at+48)>=0)!=expectedHit||data.getInt(at+76)!=77)errors++;continue;}
                if(data.getInt(at+32)!=0||data.getInt(at+44)!=(expectedHit?2:0)||data.getInt(at+76)!=(expectedHit?2:0))errors++;
                if(data.getFloat(at+96)!=(expectedHit?2:0)||data.getFloat(at+100)!=0)errors++;
                double distance=data.getFloat(at+92);
                for(int c=0;c<3;c++){double actual=data.getFloat(at+80+c*4),expected=Math.exp(-.1*(c+1)*distance);if(!Double.isFinite(actual)||Math.abs(actual-expected)>2e-5)errors++;}
                if(expectedHit&&(data.getFloat(at+112)<0||data.getFloat(at+112)>1||data.getFloat(at+116)<2||data.getFloat(at+116)>3))errors++;
            }
            if(errors!=0)throw new AssertionError("shared native ray validation failures="+errors+", shadow="+shadow);
        }
    }
    private static final class Scene implements AutoCloseable {
        private final VulkanGpuScene scene;private final VulkanSceneAcceleration acceleration;
        Scene(VulkanDeviceRuntime device) throws Exception {
            scene=new VulkanGpuScene(new VulkanGpuSceneBuffers(device.device(),device.allocator(),device.buildCommands()));
            acceleration=new VulkanSceneAcceleration(device,scene);
            try {
                var material=MaterialAsset.builder(20).blendMode(MaterialAsset.BlendMode.OPAQUE).baseColorRgba8(-1).roughness(.5f).doubleSided(true).build();
                var mesh=MeshAsset.builder(30,new float[]{-4,-4,0,4,-4,0,4,4,0,-4,4,0,-4,-4,2,4,-4,2,4,4,2,-4,4,2},new int[]{0,1,2,0,2,3,4,5,6,4,6,7},new long[]{20,20,20,20}).build();
                var update=new VulkanSceneResidency().prepare(SceneTransaction.builder(0).resetScene().upsert(material).upsert(mesh).upsert(SceneInstance.builder(40,30).build()).build());
                scene.submit(update.changeSet(),0);long deadline=System.nanoTime()+10_000_000_000L;
                while(scene.poll(0).activeRevision()!=0){if(System.nanoTime()>deadline)throw new AssertionError("scene timeout");Thread.sleep(1);}
                acceleration.submit(update.changeSet(),0);
                while(acceleration.poll(0).activeRevision()!=0){if(System.nanoTime()>deadline)throw new AssertionError("TLAS timeout");Thread.sleep(1);}
            }catch(Exception|Error failure){close();throw failure;}
        }
        long tlas(){return acceleration.requireActiveTlas(0).handle();}
        public void close(){try{acceleration.close();}finally{scene.close();}}
    }
}
