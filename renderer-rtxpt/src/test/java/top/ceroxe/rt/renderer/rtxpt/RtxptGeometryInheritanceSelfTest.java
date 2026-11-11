package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Geometry transport reuses validated native input, independently of surfaces. */
public final class RtxptGeometryInheritanceSelfTest {
    private static final RenderFrameRequest REQUEST=RenderFrameRequest.builder(0,64,64,
            CameraState.lookAt(0,0,4,0,0,0).build()).build();
    private record Input(String name,String source,int fullBytes) { }
    private static String string(ByteBuffer data){
        var bytes=new byte[data.getInt()];data.get(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    private static List<Input> inputs(ByteBuffer memory){
        var data=memory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        require(data.getInt()==RtxptMemoryResources.PROTOCOL&&data.getInt()==0,"Geometry fixture has no texture transport");
        for(int count=data.getInt();count>0;count--){
            string(data);data.position(data.position()+92);if(data.getInt()!=0)data.position(data.position()+16);
        }
        var result=new ArrayList<Input>();
        for(int count=data.getInt();count>0;count--){
            String name=string(data),source=null;int bytes=0,kind=data.getInt();
            require(kind==0||kind==1,"Defined geometry transport kind");
            if(kind==1)source=string(data);
            else for(int channel=0;channel<5;channel++){
                int length=Math.multiplyExact(data.getInt(),4);bytes+=length;data.position(data.position()+length);
            }
            for(int groups=data.getInt();groups>0;groups--){string(data);int triangles=data.getInt();data.position(data.position()+triangles*4);}
            result.add(new Input(name,source,bytes));
        }
        return result;
    }
    private static MeshAsset mesh(long id,int edit){
        float[] p={0,0,0,1,0,0,0,1,0},n={0,0,1,0,0,1,0,0,1},uv={0,0,1,0,0,1};
        int[] indices={0,1,2};
        if(edit==1)p[0]=.1f;if(edit==2)n[0]=.1f;if(edit==3)uv[0]=.1f;
        if(edit==4)indices=new int[]{0,2,1};
        var builder=MeshAsset.builder(id,p,indices,new long[]{edit==5?2:1}).textureCoordinates(uv);
        if(edit!=6)builder.normals(n);
        if(edit==7)builder.vertexColorsRgba8(new int[]{-1,0xff00ff00,-1});
        return builder.build();
    }
    private static MeshAsset tetra(float edit){
        return MeshAsset.builder(8,new float[]{edit,0,0,1,0,0,0,1,0,0,0,1},
                new int[]{0,2,1,0,1,3,0,3,2,1,2,3},new long[]{1,1,1,1})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();
    }
    private static void orderedVariants(RtxptMemoryResources.PacketPool packets){
        var empty=new RtxptScene();var scene=new RtxptScene();var mesh=tetra(0);
        var tx=SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build()).upsert(mesh);
        for(int i=0;i<6;i++){
            var instance=SceneInstance.builder(80+i,8).surfaceVisibility((i%3+1)/4f);
            if(i>=3)instance.mediumBoundary(MediumBoundary.surface(new Medium(Rgb.ZERO,1.5f)));
            tx.upsert(instance.build());
        }
        scene.retain(tx.build());
        var first=RtxptDelta.prepare(empty,scene,empty.items(REQUEST),scene.items(REQUEST),Map.of(),packets);
        var initial=inputs(first.memory());
        require(initial.size()==6,"One public mesh supplies all visibility and boundary variants");
        long canonical=4L*(mesh.positions().remaining()+mesh.normals().remaining()+mesh.tangents().remaining()
                +mesh.textureCoordinates().remaining()+mesh.triangleIndices().remaining());
        require(initial.stream().mapToLong(Input::fullBytes).sum()==canonical,"First packet multiplied canonical bytes by semantic variant count");
        require(initial.stream().filter(input->input.source()==null).count()==1,"First packet must own canonical geometry exactly once");
        var written=new HashSet<String>();
        for(var input:initial){
            require(input.source()==null||written.contains(input.source()),"Packet inheritance must name a preceding mesh");
            written.add(input.name());
        }
        var changed=new RtxptScene(scene);changed.retain(SceneTransaction.builder(2).upsert(tetra(.125f)).build());
        var edited=inputs(RtxptDelta.prepare(scene,changed,scene.items(REQUEST),changed.items(REQUEST),Map.of(),packets).memory());
        require(edited.stream().mapToLong(Input::fullBytes).sum()==canonical&&edited.getFirst().source()==null,
                "A real geometry edit needs one fresh canonical owner before packet-local aliases");
        // Replacing A first shadows resident A. B still has the old coordinates:
        // it cannot inherit A's newly decoded inputs just because the name matches.
        var old=new RtxptScene();old.retain(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build())
                .upsert(mesh(1,0)).upsert(SceneInstance.builder(1,1).build()).build());
        var next=new RtxptScene(old);next.retain(SceneTransaction.builder(2).upsert(mesh(1,1))
                .upsert(mesh(2,0)).upsert(SceneInstance.builder(2,2).build()).build());
        var ordered=List.of(next.item(1),next.item(2));
        var guarded=inputs(RtxptMemoryResources.pack(old,next,ordered,List.of(),ordered,List.of(),List.of(),Map.of(),
                packets,List.of(),List.of(old.item(1))));
        require(guarded.stream().allMatch(input->input.source()==null&&input.fullBytes()>0),
                "A replaced source name must not alias the wrong generation");
    }
    public static void main(String[] args){
        try(var packets=new RtxptMemoryResources.PacketPool()){
            var empty=new RtxptScene();var resident=new RtxptScene();
            resident.retain(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build())
                    .upsert(MaterialAsset.builder(2).build()).upsert(mesh(1,0)).upsert(SceneInstance.builder(1,1).build()).build());
            var admitted=RtxptDelta.prepare(empty,resident,empty.items(REQUEST),resident.items(REQUEST),Map.of(),packets);
            require(inputs(admitted.memory()).getFirst().source()==null,"Initial admission transfers owned inputs");
            for(int edit=0;edit<=7;edit++){
                var next=new RtxptScene(resident);
                next.retain(SceneTransaction.builder(2).removeInstance(1).removeMesh(1)
                        .upsert(mesh(2,edit)).upsert(SceneInstance.builder(2,2).build()).build());
                var compact=RtxptDelta.prepare(resident,next,resident.items(REQUEST),next.items(REQUEST),Map.of(),packets);
                var input=inputs(compact.memory()).getFirst();
                boolean same=edit==0||edit==5||edit==7;
                require(same==(input.source()!=null),"Only equal consumed channels may inherit: edit="+edit);
                if(same)require(input.source().equals("mesh1_v3f800000")&&input.fullBytes()==0,
                        "Retiring source is retained by identity, with zero full channel bytes");
                else require(input.fullBytes()>0,"A real consumed-channel change carries its actual input");
            }
            var flat=new RtxptScene(resident);flat.retain(SceneTransaction.builder(2).upsert(mesh(1,6)).build());
            var flatNext=new RtxptScene(flat);flatNext.retain(SceneTransaction.builder(3).removeInstance(1).removeMesh(1)
                    .upsert(mesh(2,6)).upsert(SceneInstance.builder(2,2).build()).build());
            var inheritedFlat=RtxptDelta.prepare(flat,flatNext,flat.items(REQUEST),flatNext.items(REQUEST),Map.of(),packets);
            require(inputs(inheritedFlat.memory()).getFirst().source()!=null,"Generated flat-normal inputs inherit without regeneration");
            orderedVariants(packets);
        }
        System.out.println("GEOMETRY_INHERITANCE_CPU PASS identity=true surfaces=true tint=true realChannelsFull=true flatNormals=true orderedPacketSources=true multiVariantCanonicalOnce=true shadowedGenerationRejected=true");
    }
}
