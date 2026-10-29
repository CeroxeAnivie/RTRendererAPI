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
            for(int session=0;session<2;session++) {
                // A later lifecycle replaces retired slots with larger capacity; no old buffer is rebound in flight.
                try(var first=new CudaSharedRayFrame(device,cuda,4096*(session+1),4L<<20);
                    var second=new CudaSharedRayFrame(device,cuda,4096*(session+1),4L<<20);
                    var firstQuery=new MediumRayQueryPipeline(device,scene.tlas(),first);
                    var secondQuery=new MediumRayQueryPipeline(device,scene.tlas(),second)) {
                    CudaSharedRayFrame[] frames={first,second};MediumRayQueryPipeline[] queries={firstQuery,secondQuery};
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
            verifyBlockingExchange(device,cuda);
        }
        System.out.println("CudaSharedMediumRayNativeSelfTest passed: two slots x two lifecycles x three fresh paths x five rounds; closest/occlusion/epoch/full-array checks; blocking copy, nested exchange and failure drain");
    }
    private static void verifyBlockingExchange(VulkanDeviceRuntime device,CudaMediumRuntime cuda) {
        String fill="extern \"C\" __global__ void exchange_fill(unsigned int *p,int count){int i=blockIdx.x*blockDim.x+threadIdx.x;if(i<count)p[i]=0x34567890u;}";
        try(var kernel=cuda.compileBatchKernel(fill,"exchange_fill");var arena=Arena.ofConfined();
            var frame=CudaSharedRayFrame.storage(device,cuda,128,1L<<20);
            var nested=CudaSharedRayFrame.storage(device,cuda,128,1L<<20)) {
            var host=arena.allocate(128,16);
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
