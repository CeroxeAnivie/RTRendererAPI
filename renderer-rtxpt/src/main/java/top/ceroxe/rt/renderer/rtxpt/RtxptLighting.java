package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Complete immutable CPU generation; one native commit consumes the same layout as scene export. */
final class RtxptLighting {
    final EnvironmentState environment;
    final List<SceneLight> lights;
    private RtxptLighting(EnvironmentState environment,Collection<SceneLight> lights) {
        this(environment,lights.stream().sorted(Comparator.comparingLong(SceneLight::id)).toList());
    }
    private RtxptLighting(EnvironmentState environment,List<SceneLight> lights){this.environment=Objects.requireNonNull(environment);this.lights=lights;}
    static RtxptLighting of(EnvironmentState environment,Collection<SceneLight> lights) {return new RtxptLighting(environment,lights);}
    static RtxptLighting sorted(EnvironmentState environment,List<SceneLight> lights){return new RtxptLighting(environment,lights);}
    long[] ids() {var ids=new long[lights.size()+1];ids[0]=-1;for(int i=0;i<lights.size();i++)ids[i+1]=lights.get(i).id();return ids;}
    float[] sky() {var e=environment;return new float[]{e.skyRed(),e.skyGreen(),e.skyBlue(),e.ambientIntensity()};}
    float[] sun(){var e=environment;return new float[]{0,0,0,0,e.sunDirectionX(),e.sunDirectionY(),e.sunDirectionZ(),0,e.sunRed(),e.sunGreen(),e.sunBlue(),e.sunIntensity(),0,0,1,0};}
    float[] records() {
        var e=environment;var values=new float[Math.multiplyExact(lights.size()+1,16)];
        values[4]=e.sunDirectionX();values[5]=e.sunDirectionY();values[6]=e.sunDirectionZ();
        values[8]=e.sunRed();values[9]=e.sunGreen();values[10]=e.sunBlue();values[11]=e.sunIntensity();values[14]=1;
        for(int i=0;i<lights.size();i++) {
            var l=lights.get(i);int b=(i+1)*16;
            values[b]=position(l.x());values[b+1]=position(l.y());values[b+2]=position(l.z());
            values[b+3]=switch(l.type()){case DIRECTIONAL->0;case POINT->1;case SPOT->2;};
            values[b+4]=l.directionX();values[b+5]=l.directionY();values[b+6]=l.directionZ();values[b+7]=l.range();
            values[b+8]=l.red();values[b+9]=l.green();values[b+10]=l.blue();values[b+11]=l.intensity();
            values[b+12]=l.innerConeCosine();values[b+13]=l.outerConeCosine();values[b+14]=l.castsShadow()?1:0;
        }
        return values;
    }
    private static float position(double value) {
        float result=(float)value;if(!Float.isFinite(result))throw new IllegalArgumentException("RTXPT light position exceeds native float range");return result;
    }
    @Override public boolean equals(Object other) {return other instanceof RtxptLighting l&&environment.equals(l.environment)&&lights.equals(l.lights);}
    @Override public int hashCode(){return Objects.hash(environment,lights);}
}
