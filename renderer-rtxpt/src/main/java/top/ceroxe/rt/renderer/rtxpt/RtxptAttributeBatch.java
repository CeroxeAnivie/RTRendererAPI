package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.MeshAsset;

/** Primitive corner grouping, shared by memory geometry and attribute publications. */
final class RtxptAttributeBatch {
    // The wire and the native importer identify whole triangles. Keep a
    // single-material mesh implicit; multi-material meshes store one ordinal
    // per triangle instead of three copies of its consecutive corner indices.
    record Group(long material,int triangleCount,int[] ordinals) {
        int triangle(int index){return ordinals==null?index:ordinals[index];}
        int corner(int index){return 3*triangle(index/3)+index%3;}
    }
    static List<Group> groups(MeshAsset mesh){
        var materials=mesh.triangleMaterialIds();int count=materials.remaining();long first=materials.get(0);
        int split=1;while(split<count&&materials.get(split)==first)split++;
        if(split==count)return List.of(new Group(first,count,null));
        var index=new GroupIndex();index.get(first).count=split;
        for(int t=split;t<count;t++)index.get(materials.get(t)).count++;
        for(var group:index.order)group.ordinals=new int[group.count];
        for(int t=0;t<count;t++){var g=index.get(materials.get(t));g.ordinals[g.cursor++]=t;}
        return index.order.stream().map(g->new Group(g.material,g.count,g.ordinals)).toList();
    }
    private static final class MutableGroup {final long material;int count,cursor;int[] ordinals;MutableGroup(long material){this.material=material;}}
    private static final class GroupIndex {
        MutableGroup[] table=new MutableGroup[16];final List<MutableGroup> order=new ArrayList<>();
        int slot(long id){long h=id;h^=h>>>33;h*=0xff51afd7ed558ccdl;h^=h>>>33;return (int)h&(table.length-1);}
        MutableGroup get(long id){int slot=slot(id);while(table[slot]!=null){if(table[slot].material==id)return table[slot];slot=(slot+1)&(table.length-1);}
            var result=new MutableGroup(id);table[slot]=result;order.add(result);
            if(order.size()*2>table.length){table=new MutableGroup[table.length*2];for(var g:order){int i=slot(g.material);while(table[i]!=null)i=(i+1)&(table.length-1);table[i]=g;}}
            return result;
        }
    }
    record Entry(String name,float[] baseline,int[] colors) { }
    static Entry canonical(String name,MeshAsset mesh,float[] override,boolean baseline,boolean colors){
        return convert(name,mesh,null,override,baseline,colors);
    }
    static Entry convert(String name,MeshAsset mesh,Group group,float[] override,boolean baseline,boolean colors){
        var indices=mesh.triangleIndices();var uv=mesh.lightmapCoordinates();var tint=mesh.vertexColorsRgba8();
        int count=group==null?indices.remaining():group.triangleCount()*3;
        var b=baseline?new float[count*2]:null;var c=colors?new int[count]:null;
        for(int i=0;i<count;i++){int vertex=indices.get(group==null?i:group.corner(i));
            if(b!=null){b[i*2]=override!=null?override[vertex*2]:uv.hasRemaining()?uv.get(vertex*2):0;
                b[i*2+1]=override!=null?override[vertex*2+1]:uv.hasRemaining()?uv.get(vertex*2+1):0;}
            if(c!=null)c[i]=tint.hasRemaining()?tint.get(vertex):0xffffffff;}
        return new Entry(name,b,c);
    }
    static void submit(long handle,long revision,List<Entry> entries){
        if(entries.isEmpty())return;
        String[] names=new String[entries.size()];float[][] baselines=new float[entries.size()][];int[][] colors=new int[entries.size()][];
        for(int i=0;i<names.length;i++){var entry=entries.get(i);names[i]=entry.name;baselines[i]=entry.baseline;colors[i]=entry.colors;}
        RtxptNative.nativeLightingAttributeBatch(handle,revision,names,baselines,colors);
    }
}
