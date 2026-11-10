package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Canonical row-major matrices and dynamic shader ABI v1 parameter publication. */
final class RtxptNativeLightingParameters {
    static float[] pack(NativeLightingPlan plan,RenderFrameRequest frame) {
        float[] values=new float[88];var camera=frame.camera();
        if(camera.hasExactProjection()) {
            var exact=camera.exactProjection();
            if(!exact.hasPerspectiveProjection())
                throw new IllegalArgumentException("Native lighting currently requires a perspective exact projection");
            var inverse=exact.inverseClipFromView();var toWorld=exact.cameraToWorld();
            for(int i=0;i<16;i++){values[i]=(float)inverse[i];values[16+i]=(float)toWorld[i];}
            double near=switch(exact.depthConvention()){case ZERO_TO_ONE->0;case NEGATIVE_ONE_TO_ONE->-1;case REVERSED_ZERO_TO_ONE,REVERSED_NEGATIVE_ONE_TO_ONE->1;};
            double far=switch(exact.depthConvention()){case ZERO_TO_ONE,NEGATIVE_ONE_TO_ONE->1;case REVERSED_ZERO_TO_ONE->0;case REVERSED_NEGATIVE_ONE_TO_ONE->-1;};
            values[35]=(float)near;values[36]=(float)(near+(far-near)*.9999);
            if(exact.jitterConvention()==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET) {
                values[37]=(float)(exact.jitterX()*2/exact.viewportWidth());values[38]=(float)(-exact.jitterY()*2/exact.viewportHeight());
            } else if(exact.jitterConvention()==ExactProjectionState.JitterConvention.NDC_OFFSET) {
                values[37]=(float)exact.jitterX();values[38]=(float)exact.jitterY();
            }
        } else {
            float near=.001f,far=1e7f;
            values[0]=camera.tanHalfFovX();values[5]=camera.tanHalfFovY();values[11]=-1;
            values[14]=(near-far)/(far*near);values[15]=1/near;
            values[16]=camera.rightX();values[17]=camera.upX();values[18]=-camera.forwardX();values[19]=(float)camera.x();
            values[20]=camera.rightY();values[21]=camera.upY();values[22]=-camera.forwardY();values[23]=(float)camera.y();
            values[24]=camera.rightZ();values[25]=camera.upZ();values[26]=-camera.forwardZ();values[27]=(float)camera.z();values[31]=1;
            values[35]=0;values[36]=.9999f;
        }
        values[32]=(float)camera.x();values[33]=(float)camera.y();values[34]=(float)camera.z();
        values[40]=plan.width();values[41]=plan.height();values[42]=plan.signalMask();values[43]=plan.giSamples();
        var environment=frame.environment();values[44]=-environment.sunDirectionX();values[45]=-environment.sunDirectionY();values[46]=-environment.sunDirectionZ();
        if(plan.sun()==null&&environment.sunIntensity()>1)throw new IllegalArgumentException("Native lighting style requires environment sunIntensity in [0,1], or an explicit normalized directional plan");
        values[47]=environment.sunIntensity();values[48]=environment.sunRed();values[49]=environment.sunGreen();values[50]=environment.sunBlue();
        if(plan.sun()!=null){var sun=plan.sun();values[44]=sun.x();values[45]=sun.y();values[46]=sun.z();values[47]=sun.weight();values[48]=sun.red();values[49]=sun.green();values[50]=sun.blue();}
        if(plan.moon()!=null){var moon=plan.moon();values[52]=moon.x();values[53]=moon.y();values[54]=moon.z();values[55]=moon.weight();}
        else values[53]=1;values[56]=plan.giDistance();values[57]=plan.giStrength();values[58]=.003f;
        var style=plan.style();values[60]=style.localDimming();values[61]=style.localBoost();values[62]=style.daylightDimming();values[63]=style.moonlightDimming();
        values[64]=style.moonRed();values[65]=style.moonGreen();values[66]=style.moonBlue();values[67]=style.additionMode().ordinal();
        if(plan.moon()!=null){values[64]=plan.moon().red();values[65]=plan.moon().green();values[66]=plan.moon().blue();}
        values[68]=style.minimumGain();values[69]=style.maximumGain();values[70]=style.maximumGi();
        values[71]=(plan.opaqueFastPath()?0:1)|(plan.directAlphaMode()==NativeLightingPlan.DirectAlphaMode.RECONSTRUCTION_COVERAGE_V1?2:0);
        var fog=frame.fog();values[80]=fog.sphericalStart();values[81]=fog.sphericalEnd();
        values[82]=fog.cylindricalStart();values[83]=fog.cylindricalEnd();values[84]=fog.opacity();return values;
    }
}
