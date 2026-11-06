package top.ceroxe.rt.renderer.rtxpt;
import java.util.List;
import top.ceroxe.rt.renderer.api.SparseLightSource;
/** Native layout v1: six float4 words, 96 bytes; identities retain all 64 bits. */
final class RtxptSparseSources {
    static float[] pack(List<SparseLightSource> sources){
        float[] data=new float[Math.multiplyExact(sources.size(),24)];int p=0;
        for(var s:sources){
            float[] r={(float)s.x(),(float)s.y(),(float)s.z(),s.shape().ordinal(),s.directionX(),s.directionY(),s.directionZ(),s.range(),
                s.red(),s.green(),s.blue(),s.intensity(),s.halfX(),s.halfY(),s.halfZ(),s.falloffExponent(),
                s.visibilityRadius()>=0?s.visibilityRadius():s.innerConeCosine(),s.outerConeCosine(),s.group(),s.composition().ordinal(),s.castsShadow()?1:0,
                0,0,s.layer().ordinal()+2*s.attenuation().ordinal()+4*s.visibility().ordinal()+(s.visibilityRadius()>=0?16:0)};
            System.arraycopy(r,0,data,p,24);p+=24;
        }
        return data;
    }
}
