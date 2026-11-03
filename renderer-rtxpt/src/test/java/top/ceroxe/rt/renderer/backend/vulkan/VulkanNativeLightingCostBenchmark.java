package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingCostBenchmark;
import static org.lwjgl.vulkan.VK10.*;

/** Independently executed E12 shader adapter on the legacy retained Vulkan scene. */
public final class VulkanNativeLightingCostBenchmark {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void vk(int status,String message){VulkanFailures.check(status,message);}
    private static Map<String,Object> map(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static String json(Object object){return RtxptNativeLightingCostBenchmark.json(object);}
    private static byte[] compile(Path source,int groupCapacity)throws Exception{
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try{
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options,Shaderc.shaderc_spirv_version_1_5);
            Shaderc.shaderc_compile_options_set_optimization_level(options,Shaderc.shaderc_optimization_level_performance);
            Shaderc.shaderc_compile_options_add_macro_definition(options,"RTR_LIGHTING_GROUP_CAPACITY",Integer.toString(groupCapacity));
            result=Shaderc.shaderc_compile_into_spv(compiler,Files.readString(source),Shaderc.shaderc_compute_shader,source.toString(),"main",options);
            check(result!=0&&Shaderc.shaderc_result_get_compilation_status(result)==Shaderc.shaderc_compilation_status_success,
                result==0?"shaderc returned null":Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer bytes=Shaderc.shaderc_result_get_bytes(result);byte[] output=new byte[bytes.remaining()];bytes.get(output);return output;
        }finally{if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
    static int groupCapacity(List<SparseLightSource> sources){int mask=0;for(var s:sources)if(s.composition()!=SparseLightSource.Composition.PHYSICAL_SUM)mask|=1<<s.group();return Integer.bitCount(mask)<=1?1:32;}
    private static void verifyGroupSelection(){
        var s=RtxptNativeLightingCostBenchmark.sources(1).getFirst();
        check(groupCapacity(List.of())==1,"empty grouped mask");
        check(groupCapacity(List.of(s.withComposition(SparseLightSource.Composition.PHYSICAL_SUM,0),s.withComposition(SparseLightSource.Composition.PHYSICAL_SUM,31)))==1,"additive sources do not consume grouped storage");
        check(groupCapacity(List.of(s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,31),s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,31).withLayer(SparseLightSource.Layer.STATIC)))==1,"one arbitrary group, both compositions and layers");
        check(groupCapacity(List.of(s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0),s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,31)))==32,"two grouped identities must retain full32");
        var all=new ArrayList<SparseLightSource>();for(int i=0;i<32;i++)all.add(s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,i));check(groupCapacity(all)==32,"all32 groups are retained");
        System.out.println("VULKAN_E12_GROUP_SELECTION PASS empty/additive/arbitrary-single/mixed-composition/mixed-layer/two/full32");
    }
    // Same append-only insertion, support bounds and AVL rotations as the native
    // retained sparse-source tree. No intentionally slower flat light scan.
    private static final class Node{
        float[] lo=new float[3],hi=new float[3];int left,right,parent=-1,height,light=-1,leaves=1;float range;
    }
    private static final class Tree{
        final List<Node> nodes=new ArrayList<>();int root=-1;
        int allocate(){nodes.add(new Node());return nodes.size()-1;}
        void replace(int parent,int old,int child){if(parent<0)root=child;else if(nodes.get(parent).left==old)nodes.get(parent).left=child;else nodes.get(parent).right=child;}
        double growth(Node a,Node b){double before=0,after=0;for(int i=0;i<3;i++){double x=(double)a.hi[i]-a.lo[i];before+=x*x;double y=(double)Math.max(a.hi[i],b.hi[i])-Math.min(a.lo[i],b.lo[i]);after+=y*y;}return after-before;}
        void refresh(int i){Node n=nodes.get(i);if(n.light>=0){n.height=0;return;}Node a=nodes.get(n.left),b=nodes.get(n.right);for(int axis=0;axis<3;axis++){n.lo[axis]=Math.min(a.lo[axis],b.lo[axis]);n.hi[axis]=Math.max(a.hi[axis],b.hi[axis]);}n.range=Math.max(a.range,b.range);n.leaves=a.leaves+b.leaves;n.height=1+Math.max(a.height,b.height);}
        int left(int a){Node x=nodes.get(a);int b=x.right;Node y=nodes.get(b);int beta=y.left,p=x.parent;replace(p,a,b);y.parent=p;y.left=a;x.parent=b;x.right=beta;nodes.get(beta).parent=a;refresh(a);refresh(b);return b;}
        int right(int a){Node x=nodes.get(a);int b=x.left;Node y=nodes.get(b);int beta=y.right,p=x.parent;replace(p,a,b);y.parent=p;y.right=a;x.parent=b;x.left=beta;nodes.get(beta).parent=a;refresh(a);refresh(b);return b;}
        void fix(int i){while(i>=0){refresh(i);Node n=nodes.get(i);int balance=nodes.get(n.left).height-nodes.get(n.right).height;if(balance>1){Node l=nodes.get(n.left);if(nodes.get(l.left).height<nodes.get(l.right).height)left(n.left);i=right(i);}else if(balance< -1){Node r=nodes.get(n.right);if(nodes.get(r.right).height<nodes.get(r.left).height)right(n.right);i=left(i);}i=nodes.get(i).parent;}}
        void insert(SparseLightSource s,int slot){int leaf=allocate();Node n=nodes.get(leaf);n.light=slot;n.range=s.range();n.lo=new float[]{(float)s.x(),(float)s.y(),(float)s.z()};n.hi=n.lo.clone();if(root<0){root=leaf;return;}int sibling=root;while(nodes.get(sibling).light<0){Node branch=nodes.get(sibling),a=nodes.get(branch.left),b=nodes.get(branch.right);double ca=growth(a,n),cb=growth(b,n);sibling=ca<cb||(ca==cb&&a.leaves<b.leaves)?branch.left:branch.right;}int parent=nodes.get(sibling).parent,branch=allocate();Node p=nodes.get(branch);p.left=sibling;p.right=leaf;p.parent=parent;nodes.get(sibling).parent=n.parent=branch;replace(parent,sibling,branch);fix(branch);}
    }
    private static ByteBuffer input(int width,int height,List<SparseLightSource> sources,Tree tree){
        var scene=RtxptNativeLightingCostBenchmark.scene();var meshes=scene.upserts().meshes();var materials=scene.upserts().materials();
        ByteBuffer b=ByteBuffer.allocate((80+sources.size()*24+4*20+tree.nodes.size()*16)*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float f:RtxptNativeLightingCostBenchmark.parameters(width,height))b.putFloat(f);
        for(var s:sources){float[] words={(float)s.x(),(float)s.y(),(float)s.z(),s.shape().ordinal(),s.directionX(),s.directionY(),s.directionZ(),s.range(),s.red(),s.green(),s.blue(),s.intensity(),s.halfX(),s.halfY(),s.halfZ(),s.falloffExponent(),s.innerConeCosine(),s.outerConeCosine(),s.group(),s.composition().ordinal(),s.castsShadow()?1:0,Float.intBitsToFloat((int)s.id()),Float.intBitsToFloat((int)(s.id()>>>32)),s.layer().ordinal()+2*s.attenuation().ordinal()};for(float f:words)b.putFloat(f);}
        for(int i=0;i<4;i++){var p=meshes.get(i).positions();for(int v=0;v<4;v++)b.putFloat(p.get()).putFloat(p.get()).putFloat(p.get()).putFloat(0);int c=materials.get(i).baseColorRgba8();for(int channel=0;channel<4;channel++)b.putFloat(((c>>>(channel*8))&255)/255f);}
        for(Node n:tree.nodes){for(float f:n.lo)b.putFloat(f);b.putFloat(n.left);for(float f:n.hi)b.putFloat(f);b.putFloat(n.right);b.putFloat(0).putFloat(0).putFloat(n.range).putFloat(n.light);b.putFloat(n.leaves).putFloat(0).putFloat(0).putFloat(0);}return b.flip();
    }
    private static final class Adapter implements AutoCloseable{
        final VulkanDeviceRuntime runtime;final VkDevice device;final RtGpuImage direct,indirect;final RtGpuBuffer inputs;
        final long descriptorLayout,descriptorPool,set,pipelineLayout,pipeline,queries;final int width,height,count,root;final double frequency;
        Adapter(VulkanSceneRuntime scene,byte[] spirv,int width,int height,List<SparseLightSource> sources)throws Exception{
            runtime=scene.device();device=runtime.device();this.width=width;this.height=height;count=sources.size();Tree tree=new Tree();for(int i=0;i<count;i++)tree.insert(sources.get(i),i);root=Math.max(0,tree.root);
            ByteBuffer bytes=input(width,height,sources,tree);
            inputs=RtGpuBuffer.createHostVisibleBuffer(device,runtime.allocator(),bytes.remaining(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);inputs.writeBytes(bytes);
            direct=RtGpuImage.createStorageImage(device,runtime.allocator(),width,height,VK_FORMAT_R16G16B16A16_SFLOAT);
            indirect=RtGpuImage.createStorageImage(device,runtime.allocator(),width,height,VK_FORMAT_R16G16B16A16_SFLOAT);
            try(MemoryStack stack=MemoryStack.stackPush()){
                var properties=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(runtime.physicalDevice(),properties);frequency=1e9/properties.limits().timestampPeriod();
                LongBuffer handle=stack.mallocLong(1);var bindings=VkDescriptorSetLayoutBinding.calloc(4,stack);
                int[] types={KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                for(int i=0;i<4;i++)bindings.get(i).binding(i).descriptorCount(1).descriptorType(types[i]).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                vk(vkCreateDescriptorSetLayout(device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle),"create E12 layout");descriptorLayout=handle.get(0);
                var sizes=VkDescriptorPoolSize.calloc(3,stack);sizes.get(0).type(types[0]).descriptorCount(1);sizes.get(1).type(types[1]).descriptorCount(2);sizes.get(2).type(types[3]).descriptorCount(1);
                vk(vkCreateDescriptorPool(device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,handle),"create E12 pool");descriptorPool=handle.get(0);
                vk(vkAllocateDescriptorSets(device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)),handle),"allocate E12 descriptors");set=handle.get(0);
                var writes=VkWriteDescriptorSet.calloc(4,stack);for(int i=0;i<4;i++)writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(types[i]);
                var acceleration=VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(scene.requireActiveTlas(1).handle()));writes.get(0).pNext(acceleration.address());
                var images=VkDescriptorImageInfo.calloc(2,stack);images.get(0).imageView(direct.imageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);images.get(1).imageView(indirect.imageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(1).pImageInfo(VkDescriptorImageInfo.create(images.get(0).address(),1));writes.get(2).pImageInfo(VkDescriptorImageInfo.create(images.get(1).address(),1));
                var buffer=VkDescriptorBufferInfo.calloc(1,stack);buffer.get(0).buffer(inputs.buffer()).offset(0).range(inputs.sizeBytes());writes.get(3).pBufferInfo(buffer);vkUpdateDescriptorSets(device,writes,null);
                var range=VkPushConstantRange.calloc(1,stack);range.get(0).offset(0).size(16).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                vk(vkCreatePipelineLayout(device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(range),null,handle),"create E12 pipeline layout");pipelineLayout=handle.get(0);
                ByteBuffer code=MemoryUtil.memAlloc(spirv.length);long module;try{code.put(spirv).flip();vk(vkCreateShaderModule(device,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,handle),"create E12 module");module=handle.get(0);}finally{MemoryUtil.memFree(code);}
                try{var info=VkComputePipelineCreateInfo.calloc(1,stack);info.get(0).sType$Default().layout(pipelineLayout).stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));vk(vkCreateComputePipelines(device,0,info,null,handle),"create E12 compute pipeline");pipeline=handle.get(0);}finally{vkDestroyShaderModule(device,module,null);}
                vk(vkCreateQueryPool(device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),null,handle),"create E12 timestamps");queries=handle.get(0);
            }
            runtime.frameCommands().submitOneTime((cmd,stack)->{barrier(cmd,stack,direct,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,0,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);barrier(cmd,stack,indirect,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,0,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);});
        }
        long[] dispatch()throws Exception{
            long start=System.nanoTime();try(var submission=runtime.frameCommands().submitOneTimeAsync((cmd,stack)->{
                var memory=VkMemoryBarrier.calloc(1,stack);memory.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,memory,null,null);
                vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,stack.longs(set),null);
                vkCmdPushConstants(cmd,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(width,height,count,root));vkCmdResetQueryPool(cmd,queries,0,2);
                vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,0);vkCmdDispatch(cmd,(width+7)/8,(height+7)/8,1);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queries,1);
            })){long submit=System.nanoTime()-start;long deadline=System.nanoTime()+30_000_000_000L;while(!submission.pollComplete()){check(System.nanoTime()<deadline,"Vulkan E12 dispatch timed out");Thread.sleep(1);}try(MemoryStack stack=MemoryStack.stackPush()){LongBuffer ticks=stack.mallocLong(2);vk(vkGetQueryPoolResults(device,queries,0,2,ticks,8,VK_QUERY_RESULT_64_BIT),"read E12 timestamps");return new long[]{ticks.get(0),ticks.get(1),submit,System.nanoTime()-start-submit};}}
        }
        void pfm(RtGpuImage image,Path target)throws Exception{
            try(RtGpuBuffer readback=RtGpuBuffer.createHostVisibleBuffer(device,runtime.allocator(),(long)width*height*8,VK_BUFFER_USAGE_TRANSFER_DST_BIT)){
                runtime.frameCommands().submitOneTime((cmd,stack)->{
                    barrier(cmd,stack,image,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
                    var copy=VkBufferImageCopy.calloc(1,stack);copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.get(0).imageExtent().set(width,height,1);vkCmdCopyImageToBuffer(cmd,image.image(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer(),copy);
                    var host=VkMemoryBarrier.calloc(1,stack);host.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,host,null,null);
                    barrier(cmd,stack,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_ACCESS_TRANSFER_READ_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                });
                ByteBuffer raw=ByteBuffer.wrap(readback.readBytes((long)width*height*8)).order(ByteOrder.LITTLE_ENDIAN);byte[] header=("PF\n"+width+" "+height+"\n-1\n").getBytes(StandardCharsets.US_ASCII);ByteBuffer output=ByteBuffer.allocate(header.length+width*height*12).order(ByteOrder.LITTLE_ENDIAN).put(header);
                // PFM scanlines are bottom-up, matching native CaptureLinear.
                for(int y=height-1;y>=0;y--)for(int x=0;x<width;x++){int offset=(y*width+x)*8;for(int c=0;c<3;c++)output.putFloat(Float.float16ToFloat(raw.getShort(offset+c*2)));}Files.write(target,output.array());
            }
        }
        public void close(){vkDestroyQueryPool(device,queries,null);vkDestroyPipeline(device,pipeline,null);vkDestroyPipelineLayout(device,pipelineLayout,null);vkDestroyDescriptorPool(device,descriptorPool,null);vkDestroyDescriptorSetLayout(device,descriptorLayout,null);indirect.close();direct.close();inputs.close();}
    }
    private static void barrier(VkCommandBuffer cmd,MemoryStack stack,RtGpuImage image,int oldLayout,int newLayout,int src,int dst,int srcStage,int dstStage){var b=VkImageMemoryBarrier.calloc(1,stack);b.get(0).sType$Default().image(image.image()).oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(src).dstAccessMask(dst).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);vkCmdPipelineBarrier(cmd,srcStage,dstStage,0,null,null,b);}
    private static String luid(VulkanDeviceRuntime runtime){try(MemoryStack stack=MemoryStack.stackPush()){var id=VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();var props=VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());VK11.vkGetPhysicalDeviceProperties2(runtime.physicalDevice(),props);check(id.deviceLUIDValid(),"Vulkan device LUID unavailable");return Long.toUnsignedString(id.deviceLUID().order(ByteOrder.LITTLE_ENDIAN).getLong(0));}}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]).toAbsolutePath(),shader=Path.of(args[1]).toAbsolutePath();int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]),frames=Integer.parseInt(args[4]),sourceCount=Integer.parseInt(args[5]);Files.createDirectories(out);
        verifyGroupSelection();List<SparseLightSource> sourceList=RtxptNativeLightingCostBenchmark.sources(sourceCount);int groupCapacity=Arrays.asList(args).contains("--general-groups")?32:groupCapacity(sourceList);
        Files.copy(shader,out.resolve("reference.comp"),StandardCopyOption.REPLACE_EXISTING);
        byte[] spirv=compile(shader,groupCapacity);Files.write(out.resolve("reference.spv"),spirv);if(Arrays.asList(args).contains("--compile-only")){System.out.println("VULKAN_E12_COMPILE PASS bytes="+spirv.length+" groupCapacity="+groupCapacity);return;}
        check(sourceCount>0&&frames>=16,"Vulkan E12 requires sources and >=16 measured frames");String fixture=RtxptNativeLightingCostBenchmark.fixtureJson(width,height,sourceCount);Files.writeString(out.resolve("fixture.json"),fixture,StandardCharsets.UTF_8);String workload=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture.getBytes(StandardCharsets.UTF_8)));
        var capability=VulkanRtCapabilityProbe.capture();check(capability.hardwareRayTracingReady(),capability.summary());var configuration=RendererConfig.builder().gpuTimingsEnabled(false).validationEnabled(true).temporalRendering(TemporalRenderingOptions.disabled()).frameReconstruction(FrameReconstructionOptions.disabled()).frameGeneration(FrameGenerationOptions.disabled()).lowLatency(LowLatencyOptions.disabled()).denoising(DenoisingOptions.disabled()).rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        StringBuilder gpu=new StringBuilder("sequence,revision,native_frame,queue,event,parent,stage,inclusive_ms,begin_tick,end_tick,frequency\n"),cpu=new StringBuilder("sequence,submit_recording_ms,completion_wait_ms\n");String deviceLuid;long preparationStart=System.nanoTime(),preparation;var intervals=new ArrayList<Double>();String coreArtifact=VulkanSceneRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString();
        try(var scene=VulkanSceneRuntime.open(capability,RendererRtDiagnostics.noop(),true,false,configuration)){
            var residency=new VulkanSceneResidency();var prepared=residency.prepare(RtxptNativeLightingCostBenchmark.scene());scene.apply(prepared.changeSet(),0);residency.commit(prepared);long deadline=System.nanoTime()+30_000_000_000L;while(scene.poll(0).activeRevision()<1){check(System.nanoTime()<deadline,"Vulkan retained scene convergence timed out");Thread.sleep(1);}check(scene.snapshot().acceleration().activeMeshes()==4,"real old-backend resident BLAS count");
            try(var adapter=new Adapter(scene,spirv,width,height,sourceList)){deviceLuid=luid(scene.device());preparation=System.nanoTime()-preparationStart;for(int i=0;i<24;i++)adapter.dispatch();for(int i=0;i<frames;i++){long[] v=adapter.dispatch();double ms=(v[1]-v[0])*1000/adapter.frequency;check(v[1]>=v[0]&&Double.isFinite(ms),"real monotonically ordered GPU timestamps");intervals.add(ms);gpu.append(String.format(Locale.ROOT,"%d,1,%d,graphics,%d,-1,lighting_dispatch,%.9f,%d,%d,%.9f%n",i,i,i,ms,v[0],v[1],adapter.frequency));cpu.append(String.format(Locale.ROOT,"%d,%.9f,%.9f%n",i,v[2]/1e6,v[3]/1e6));}adapter.pfm(adapter.direct,out.resolve("direct.pfm"));adapter.pfm(adapter.indirect,out.resolve("indirect.pfm"));}
        }
        Files.writeString(out.resolve("gpu.csv"),gpu,StandardCharsets.UTF_8);Files.writeString(out.resolve("cpu.csv"),cpu,StandardCharsets.UTF_8);Collections.sort(intervals);
        Files.writeString(out.resolve("run.json"),json(map("format","rtr-e12-cost-run","formatVersion",1,"evidenceKind","REAL_GPU_RUN","backend","legacy-vulkan-retained-compute-adapter","workloadSha256",workload,"adapterLuid",deviceLuid,"timestampUtc",java.time.Instant.now().toString(),"measuredFrames",frames,"warmupFrames",24,"firstMeasuredSequence",0,"lastMeasuredSequence",frames-1,"timingValid",true,"gpuQueryDropped",0,"gpuQueryPending",0,"gpuTimerSemantics","queue_timestamp_inclusive_ms_including_scheduling_preemption","initialPreparationMs",preparation/1e6,"p50Ms",intervals.get((frames-1)/2),"groupCapacity",groupCapacity,"groupCapacityPolicy","actual declared non-additive group mask; capacity1 when <=1 bit, otherwise full32","coreArtifact",coreArtifact,"shaderSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(shader))),"spirvSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spirv)),"scope","real legacy GPUScene BLAS/TLAS; exact portable fixture GLSL compute adapter; 2 RGBA16F planes; source AVL tree and contribution pruning; static dispatch only","unmeasured",List.of("legacy built-in beauty algorithm differs and is not this comparison","GL import, host reconstruction and consumer handoff"))),StandardCharsets.UTF_8);
        System.out.println("VULKAN_E12_NATIVE TIMING_COMPLETE workload="+workload+" p50Ms="+intervals.get((frames-1)/2)+" core="+coreArtifact);
    }
}
