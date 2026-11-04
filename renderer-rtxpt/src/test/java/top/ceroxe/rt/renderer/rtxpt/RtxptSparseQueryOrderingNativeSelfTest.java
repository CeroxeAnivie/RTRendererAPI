package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Independent exhaustive per-pixel oracle for packed tree ordering and pruning. */
public final class RtxptSparseQueryOrderingNativeSelfTest {
    static List<SparseLightSource> sources(int phase){
        var random=new Random(98124+phase);var result=new ArrayList<SparseLightSource>();
        for(int i=0;i<577;i++){
            boolean box=i%2==0;int group=phase==0?i%4:phase==1?i%2:31;
            var layer=phase==0?SparseLightSource.Layer.STATIC:phase==1||i%2==0?SparseLightSource.Layer.DYNAMIC:SparseLightSource.Layer.STATIC;
            result.add(new SparseLightSource(i,box?SparseLightSource.Shape.BOX:SparseLightSource.Shape.POINT,
                random.nextDouble()*8-4,random.nextDouble()*8-4,.3+random.nextDouble()*2,
                box?.08f:0,box?.12f:0,box?.05f:0,0,-1,0,
                random.nextFloat(),random.nextFloat(),random.nextFloat(),.002f+random.nextFloat()*.008f,
                1+random.nextFloat()*5,new float[]{.5f,1,2,3}[i%4],1,-1,
                SparseLightSource.Composition.VISIBLE_GROUP_MAX,group,true,layer,
                i%7==0?SparseLightSource.Attenuation.MANHATTAN_LINEAR:SparseLightSource.Attenuation.INVERSE_SQUARE_FINITE));
        }
        return result;
    }
    static float[] local(List<SparseLightSource> sources,double x,double y,boolean dynamic){
        var values=new float[32][3];var scores=new double[32];Arrays.fill(scores,-1);
        for(var source:sources){
            if(dynamic&&source.layer()!=SparseLightSource.Layer.DYNAMIC)continue;
            double dx=Math.max(Math.abs(source.x()-x)-source.halfX(),0),dy=Math.max(Math.abs(source.y()-y)-source.halfY(),0),dz=source.z()-source.halfZ();
            double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);if(distance>=source.range())continue;
            double attenuation=source.attenuation()==SparseLightSource.Attenuation.MANHATTAN_LINEAR
                ?Math.max(1-(dx+dy+dz)/source.range(),0):Math.pow(Math.max(1-distance/source.range(),0),source.falloffExponent())/Math.max(distance*distance,1);
            float r=(float)(source.red()*source.intensity()*attenuation),g=(float)(source.green()*source.intensity()*attenuation),b=(float)(source.blue()*source.intensity()*attenuation);
            double score=r*.2126+g*.7152+b*.0722;int group=source.group();
            if(score>scores[group]){scores[group]=score;values[group]=new float[]{r,g,b};}
        }
        var sum=new float[3];for(var value:values)for(int c=0;c<3;c++)sum[c]+=value[c];return sum;
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);double error=0;int checked=0;
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            for(int phase=0;phase<3;phase++){
                var style=phase==0?NativeLightingStyle.defaults().withAdditionMode(NativeLightingStyle.AdditionMode.CLASSIC_ALL_LOCAL_V1):NativeLightingStyle.defaults();
                renderer.nativeLightingPlan(new NativeLightingPlan(W,H,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,style,SUN_OFF,null,true));
                var sources=sources(phase);var transaction=SceneTransaction.builder(phase+1);
                if(phase==0)transaction.resetScene().upsert(material(1,255,255,255)).upsert(plane(1,1,8,0,0,0,false)).upsert(SceneInstance.builder(1,1).build());
                renderer.apply(transaction.build(),sources,LongBuffer.allocate(0));renderer.submit(frame(phase,phase+1));
                var gain=capture(renderer,"query-order-"+phase+"-gain",0);var gi=capture(renderer,"query-order-"+phase+"-gi",1);
                for(int y=0;y<H;y++)for(int x=0;x<W;x++){
                    double px=((x+.5)/W*2-1)*Math.tan(Math.toRadians(26))*3*1.5;
                    double py=(1-(y+.5)/H*2)*Math.tan(Math.toRadians(26))*3;
                    var all=local(sources,px,py,false);var dynamic=local(sources,px,py,true);
                    for(int c=0;c<3;c++){
                        double expected=style.directGain(c,0,0,1,0,all[c],dynamic[c],0,1,0,1);
                        error=Math.max(error,Math.abs(gain[(y*W+x)*3+c]-expected));checked++;
                        require(Math.abs(gi[(y*W+x)*3+c])<.00001,"No indirect energy without a bounce surface");
                    }
                }
            }
            require(error<=.001,"Exhaustive current-source oracle matches every pixel: "+error);
            require(renderer.nativeLightingExecution().fullBeautyFrames()==0,"Packed ordering uses native lighting only");renderer.awaitIdle();
        }
        System.out.println("SPARSE_QUERY_ORDER_GPU PASS samples="+checked+" maxHalfError="+error+" groups=4,2,31 static=true dynamic=true mixed=true");
    }
}
