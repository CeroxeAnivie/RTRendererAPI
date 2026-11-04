package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** New alpha/AS and refit assertions against freshly produced GPU pixels. */
public final class RtxptHighFramerateResourcesNativeSelfTest {
    static TextureAsset texture(int alpha){return TextureAsset.color(2,1,1,new byte[]{-1,-1,-1,(byte)alpha});}
    static void render(RtxptRenderer r,long sequence,long revision){r.submit(frame(sequence,revision));try(var lease=r.acquireLightingFrame().orElseThrow()){
        require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Fresh atomic output");}}
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(directPlan(SUN_ON));r.apply(SceneTransaction.builder(revision).resetScene().upsert(texture(255))
                .upsert(material(1,128,128,128)).upsert(MaterialAsset.builder(2).baseColorTextureId(2).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
            publication(r,1,NativeLightingPublication.Instance.shadowOnly(2));render(r,sequence++,revision);r.awaitIdle();
            // Finish bootstrap pool/address bookkeeping before measuring animation.
            render(r,sequence++,revision);r.awaitIdle();
            var monitor=r.protocolMonitor();var before=r.resourceMaintenance();
            for(int i=0;i<12;i++){
                r.apply(SceneTransaction.builder(++revision).upsert(texture((i&1)==0?0:255)).build());render(r,sequence++,revision);
                near(capture(r,"new-alpha-"+i,0)[CENTER],(i&1)==0?1:.45,.002,"Animated coverage stays current");
            }
            var after=r.resourceMaintenance();require(after.blasBuilds()==before.blasBuilds()&&after.blasRefits()==before.blasRefits(),"Coverage never builds/refits geometry");
            require(after.tlasBuilds()==before.tlasBuilds(),"Coverage animation preserves TLAS: before="+before+" after="+after);
            var table=r.dynamicResourceStatistics().attributeTableVisits();before=after;
            r.apply(SceneTransaction.builder(++revision).upsert(plane(1,1,2.1f,0,0,1,false)).build());render(r,sequence++,revision);r.awaitIdle();after=r.resourceMaintenance();
            require(after.blasRefits()-before.blasRefits()==1&&after.blasBuilds()==before.blasBuilds(),"Same-index deformation uses a single refit");
            require(r.dynamicResourceStatistics().attributeTableVisits()==table,"Fixed-index geometry performs no global metadata scan");
            require(!monitor.snapshot().events().isEmpty(),"Independent protocol snapshot contains publication and lease observations");
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);long events=monitor.snapshot().events().getLast().ordinal();render(r,sequence++,revision);
            require(monitor.snapshot().events().getLast().ordinal()==events,"OFF suppresses protocol diagnostics");
            r.awaitIdle();System.out.println("HIGH_FRAMERATE_RESOURCES_NATIVE PASS alphaFrames=12 alphaBlas=0 alphaTlas=0 localRefit=1 metadataVisits=0 maintenance="+after);
        }
    }
}
