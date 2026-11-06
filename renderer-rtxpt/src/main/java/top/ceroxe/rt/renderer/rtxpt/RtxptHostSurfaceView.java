package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Exact host matrices; JNI converts the explicit RH/depth convention to the core's LH reverse-Z view. */
final class RtxptHostSurfaceView {
    static float[] pack(CameraState current,CameraState previous,int width,int height){
        float[] result=new float[70];write(result,0,current,width,height);write(result,35,previous,previous.hasExactProjection()?previous.exactProjection().viewportWidth():width,previous.hasExactProjection()?previous.exactProjection().viewportHeight():height);return result;
    }
    private static void write(float[] result,int offset,CameraState camera,int width,int height){
        if(camera.hasExactProjection()){
            var exact=camera.exactProjection();
            if(!exact.hasPerspectiveProjection())
                throw new IllegalArgumentException("Host primary surfaces require a perspective exact projection");
            if(exact.viewportWidth()!=width||exact.viewportHeight()!=height)
                throw new IllegalArgumentException("Exact projection viewport must match the host frame output extent");
            var world=exact.cameraToWorld();var clip=exact.clipFromView();
            for(int i=0;i<16;i++){result[offset+i]=(float)world[i];result[offset+16+i]=(float)clip[i];}
            result[offset+32]=(float)(exact.jitterConvention()==ExactProjectionState.JitterConvention.NDC_OFFSET?exact.jitterX()*width/2:exact.jitterX());
            result[offset+33]=(float)(exact.jitterConvention()==ExactProjectionState.JitterConvention.NDC_OFFSET?-exact.jitterY()*height/2:exact.jitterY());
            result[offset+34]=exact.depthConvention().ordinal();
        }else{
            float[] world={camera.rightX(),camera.upX(),-camera.forwardX(),(float)camera.x(),
                camera.rightY(),camera.upY(),-camera.forwardY(),(float)camera.y(),
                camera.rightZ(),camera.upZ(),-camera.forwardZ(),(float)camera.z(),0,0,0,1};
            System.arraycopy(world,0,result,offset,16);
            result[offset+16]=1/camera.tanHalfFovX();result[offset+21]=1/camera.tanHalfFovY();
            result[offset+27]=.001f;result[offset+30]=-1;result[offset+34]=2;
        }
        for(int i=offset;i<offset+35;i++)if(!Float.isFinite(result[i]))throw new IllegalArgumentException("Host view exceeds the GPU float range");
    }
}
