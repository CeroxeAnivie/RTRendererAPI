package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Real official-session admission, resource publication, execution and capability boundaries. */
public final class RtxptOfficialSessionNativeSelfTest {
    public static void main(String[] args){
        var config=RendererConfig.pathTracedPresentation();
        try(var renderer=RtxptRenderers.openOfficialHiddenWindow(config)){
            require(!(renderer instanceof NativeLightingControl)&&!(renderer instanceof SparseLightingControl),"Adapter interfaces leaked");
            require(renderer.extension(NativeLightingControl.class).isEmpty()
                    &&renderer.extension(SparseLightingControl.class).isEmpty(),"Adapter capability advertised");
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var unsupported=SceneLight.point(8,0,1,1).range(10).castsShadow(false).build();
            try{renderer.apply(SceneTransaction.builder(1).upsert(unsupported).build());
                throw new AssertionError("Unsupported shadow-disable accepted");
            }catch(IllegalArgumentException expected){}
            require(renderer.diagnostics().latestAcceptedSceneRevision()==0,"Rejected transaction changed revision");
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).emissive(new Rgb(1,0,0)).doubleSided(true).build())
                    .upsert(MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,0,1,0},new int[]{0,1,2},new long[]{1})
                        .normals(new float[]{0,0,1,0,0,1,0,0,1}).build())
                    .upsert(SceneInstance.builder(1,1).build()).build());
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            for(int sequence=1;sequence<=12;sequence++){
                var result=renderer.submit(RenderFrameRequest.builder(sequence,320,320,camera).minimumSceneRevision(1).build());
                require(result.frameSequence()==sequence&&result.scheduledSceneRevision()==1,"Stale completion");
            }
            access.awaitIdle();
            var state=access.executionState();var rendering=access.renderingState();
            require(state.officialOnly()&&state.officialPipelineFrames()>=12&&state.dlssRrGuideFrames()>=12,"Official hooks did not execute");
            require(state.analyticBeautyFrames()==0&&state.independentLightingFrames()==0,"Adapter transport executed");
            require(!rendering.analyticBeauty()&&!rendering.externalLighting()
                    &&rendering.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Wrong original rendering mode");
            System.out.println("OFFICIAL_SESSION_NATIVE PASS exactFrames=12 unsupportedCapabilitiesAbsent=true rejectedTransactionAtomic=true originalPipeline="
                    +state.officialPipelineFrames()+" rrGuides="+state.dlssRrGuideFrames()+" customFrames=0");
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
