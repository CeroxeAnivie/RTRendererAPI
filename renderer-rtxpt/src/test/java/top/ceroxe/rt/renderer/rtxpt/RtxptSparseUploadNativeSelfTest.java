package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.util.ArrayList;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Growth, removal and repeated moving updates through the actual upload path. */
public final class RtxptSparseUploadNativeSelfTest {
    private static SparseLightSource source(int id,int step){
        return SparseLightSource.point(id,(id%64)*2+(id<128?step*.5:0),1,(id/64)*2,
            .4f,.6f,.2f,.1f,6).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0);
    }
    public static void main(String[] args)throws Exception {
        long maxRequest=0,maxAllocation=0,stagingRequests=0,stagingBytes=0,recordUploadBytes=0,indexUploadBytes=0;
        int uploads=0,initializations=0,defaultCreates=0,cpuResizes=0,ommRefreshes=0;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(NativeLightingPlan.defaults(64,48));r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            for(int step=0;step<24;step++){
                long revision=step+1;var transaction=SceneTransaction.builder(revision);
                if(step==0)transaction.resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
                    .upsert(RtxptFramePreparationNativeSelfTest.card(1)).upsert(SceneInstance.builder(1,1).build());
                int count=step==8?4096:128;var sources=new ArrayList<SparseLightSource>();
                for(int id=0;id<count;id++)sources.add(source(id,step));
                long[] removed=new long[step==16?3072:0];for(int i=0;i<removed.length;i++)removed[i]=1024+i;
                r.apply(transaction.build(),sources,LongBuffer.wrap(removed));
                r.submit(RtxptFramePreparationNativeSelfTest.frame(step,revision,128,96).toBuilder()
                    .environment(EnvironmentState.neutral()).build());
                try(var lease=r.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==step&&lease.frame().sceneRevision()==revision,"Fresh matching lighting output");
                }
                String captures=System.getProperty("upload.captureOutput");
                if(captures!=null&&(step==0||step==8||step==16||step==23)){
                    var output=java.nio.file.Path.of(captures);java.nio.file.Files.createDirectories(output);
                    r.captureNativeLighting(output.resolve("upload-"+step+".pfm"),0);
                }
                var snapshot=r.diagnosticSnapshot();
                for(var event:snapshot.events())if(event.sequence()==step){
                    if(event.stage()==RtxptDiagnostics.Stage.SPARSE_REGION_INITIALIZE){
                        require(step==0,"Moving/growing/removing sources rebuilt the regional pool");initializations++;
                    }
                    if(event.stage()==RtxptDiagnostics.Stage.SPARSE_UPLOAD)uploads++;
                    if(event.stage()==RtxptDiagnostics.Stage.DEFAULT_BUFFER_COMMITTED)defaultCreates++;
                    if(event.stage()==RtxptDiagnostics.Stage.SCENE_CPU_RESIZE)cpuResizes++;
                    if(event.stage()==RtxptDiagnostics.Stage.SCENE_OMM_DEBUG_REFRESH){
                        System.out.println("OMM_DEBUG_REFRESH step="+step+" geometries="+event.items()+" oldRows="+event.tasks());
                        require(step==0,"Independent native lighting refreshed beauty OMM debug data after activation");ommRefreshes++;
                    }
                    if(event.stage()==RtxptDiagnostics.Stage.STAGING_ACQUIRE){maxRequest=Math.max(maxRequest,event.bytes());++stagingRequests;stagingBytes+=event.bytes();}
                    if(event.stage()==RtxptDiagnostics.Stage.STAGING_ALLOCATE)maxAllocation=Math.max(maxAllocation,event.bytes());
                }
                var status=r.sparseLightingStatus();
                System.out.println("SPARSE_UPLOAD_FRAME step="+step+" recordBytes="+(status.uploadedRecordBytes()-recordUploadBytes)
                    +" indexBytes="+(status.uploadedIndexBytes()-indexUploadBytes));
                recordUploadBytes=status.uploadedRecordBytes();indexUploadBytes=status.uploadedIndexBytes();
            }
            r.awaitIdle();
            if(!Boolean.getBoolean("upload.baseline")){
                var memory=r.uploadMemory();
                require(memory.createdBytes()-memory.releasedBytes()==memory.retainedBytes(),"Upload chunk lifetime accounting");
                require(memory.fenceRetiredBytes()>0&&memory.releasedBytes()>0,"Oversized upload pool did not retire completed storage");
                System.out.println("UPLOAD_MEMORY "+memory);
            }
        }
        require(initializations==1&&uploads==24,"Actual regional initialization and every new upload were observed");
        require(maxRequest>0&&maxRequest<=1024*1024,"A sparse upload exceeded its 1MiB write bound");
        require(maxAllocation<=16*1024*1024,"An oversized staging chunk was created");
        require(defaultCreates>0&&cpuResizes>0,"Default GPU creation or CPU scene resize evidence missing");
        System.out.println("SPARSE_UPLOAD PASS frames=24 grow128to4096=true remove3072=true sameFrame=true initializations="
            +initializations+" maxWriteBytes="+maxRequest+" maxStagingAllocationBytes="+maxAllocation
            +" defaultCreates="+defaultCreates+" cpuResizes="+cpuResizes+" ommRefreshes="+ommRefreshes
            +" stagingRequests="+stagingRequests+" stagingBytes="+stagingBytes+" recordBytes="+recordUploadBytes+" indexBytes="+indexUploadBytes);
    }
}
