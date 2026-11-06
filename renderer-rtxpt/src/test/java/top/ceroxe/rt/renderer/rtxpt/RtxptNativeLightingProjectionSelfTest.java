package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** CPU comparison of the published float shader ABI against an independent affine camera oracle. */
public final class RtxptNativeLightingProjectionSelfTest {
    private static final int WIDTH=1280,HEIGHT=800,GRID_W=41,GRID_H=29;
    private static double maximumDirectionError,maximumOriginError;
    private static int rays,oldShapeRejections;

    public static void main(String[] arguments) {
        double[] world=multiply(translation(17,72,-29),multiply(rotation(1,.35),rotation(0,-.2)));
        for(var depth:ExactProjectionState.DepthConvention.values())
            for(var jitter:ExactProjectionState.JitterConvention.values())
                for(int variant=0;variant<6;variant++) {
                    var effects=new Effects();
                    if(variant==1||variant==3) { // Walking bob: translation, roll, then pitch.
                        effects.add(translation(.035,-.08,0),translation(-.035,.08,0));
                        effects.rotate(2,.025);effects.rotate(0,.045);
                    }
                    if(variant==2||variant==3) { // Hurt tilt around a horizontal damage direction.
                        effects.rotate(1,-.7);effects.rotate(2,-.18);effects.rotate(1,.7);
                    }
                    if(variant==4) { // A rotated anisotropic screen effect still has one finite center.
                        effects.rotate(0,.4);effects.rotate(1,-.3);
                        effects.add(scale(1.3,1,1),scale(1/1.3,1,1));
                        effects.rotate(1,.3);effects.rotate(0,-.4);
                    }
                    double near=.05,far=1000,sx=1.1,sy=1.76;
                    double zn=nearNdc(depth),zf=farNdc(depth);
                    double b=(zn-zf)*near*far/(far-near),a=b/near-zn;
                    double[] base={sx,0,0,0, 0,sy,0,0, 0,0,a,b, 0,0,-1,0};
                    if(variant==5) {base[2]=.07;base[6]=-.03;}
                    double[] projection=multiply(base,effects.forward);
                    double jx=jitter==ExactProjectionState.JitterConvention.NONE?0:.25;
                    double jy=jitter==ExactProjectionState.JitterConvention.NONE?0:-.125;
                    var exact=ExactProjectionState.builder(WIDTH,HEIGHT)
                            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                            .depthConvention(depth).jitter(jitter,jx,jy)
                            .cameraToWorld(world).clipFromView(projection).build();
                    require(exact.hasPerspectiveProjection(),"Affine view effects must retain a finite perspective center");
                    if(projection[12]!=0||projection[13]!=0||projection[15]!=0)oldShapeRejections++;
                    var frame=RenderFrameRequest.builder(1,WIDTH,HEIGHT,CameraState.exactProjection(exact)).build();
                    float[] packed=RtxptNativeLightingParameters.pack(NativeLightingPlan.defaults(GRID_W,GRID_H),frame);
                    double[] origin=point(world,point(effects.inverse,new double[]{0,0,0}));
                    for(int y=0;y<GRID_H;y++)for(int x=0;x<GRID_W;x++) {
                        double ndcX=(x+.5)/GRID_W*2-1,ndcY=1-(y+.5)/GRID_H*2;
                        if(jitter==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET){ndcX+=jx*2/WIDTH;ndcY-=jy*2/HEIGHT;}
                        else if(jitter==ExactProjectionState.JitterConvention.NDC_OFFSET){ndcX+=jx;ndcY+=jy;}
                        double[] direction=normalize(vector(world,vector(effects.inverse,new double[]{(ndcX+base[2])/sx,(ndcY+base[6])/sy,-1})));
                        var cpu=exact.rayForPixel((x+.5)*WIDTH/GRID_W-.5,(y+.5)*HEIGHT/GRID_H-.5);
                        compare(new double[]{cpu.originX(),cpu.originY(),cpu.originZ()},origin,1e-10,"CPU origin");
                        compare(new double[]{cpu.directionX(),cpu.directionY(),cpu.directionZ()},direction,1e-10,"CPU direction");
                        compare(new double[]{packed[32],packed[33],packed[34]},origin,1e-5,"Shader origin");
                        compare(shaderDirection(packed,x,y),direction,2e-6,"Shader direction");
                        rays++;
                    }
                }
        // Parallel rays must keep per-pixel origins in the CPU contract and remain rejected by this native path.
        var parallel=ExactProjectionState.builder(WIDTH,HEIGHT)
                .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
                .cameraToWorld(identity()).clipFromView(identity()).build();
        require(!parallel.hasPerspectiveProjection(),"Parallel projection must not be classified by matrix shape alone");
        require(parallel.rayForPixel(0,0).originX()!=parallel.rayForPixel(WIDTH-1,0).originX(),"Parallel rays need distinct origins");
        try {
            RtxptNativeLightingParameters.pack(NativeLightingPlan.defaults(GRID_W,GRID_H),
                    RenderFrameRequest.builder(1,WIDTH,HEIGHT,CameraState.exactProjection(parallel)).build());
            throw new AssertionError("Parallel native lighting projection was accepted");
        } catch(IllegalArgumentException expected) { }
        System.out.println("NATIVE_LIGHTING_PROJECTION PASS rays="+rays+" depthConventions=4 jitterConventions=3 variants=6 oldShapeRejections="+oldShapeRejections
                +" maximumDirectionError="+maximumDirectionError+" maximumOriginError="+maximumOriginError+" parallelRejected=true");
    }

    private static float[] unproject(float[] p,float x,float y,float z) {
        float[] r=new float[4];for(int row=0;row<4;row++)r[row]=p[row*4]*x+p[row*4+1]*y+p[row*4+2]*z+p[row*4+3];
        return new float[]{r[0]/r[3],r[1]/r[3],r[2]/r[3]};
    }
    private static double[] shaderDirection(float[] p,int x,int y) {
        float nx=((float)x+.5f)/GRID_W*2-1+p[37],ny=((float)y+.5f)/GRID_H*-2+1+p[38];
        float[] n=unproject(p,nx,ny,p[35]),f=unproject(p,nx,ny,p[36]);
        float dx=f[0]-n[0],dy=f[1]-n[1],dz=f[2]-n[2];
        return normalize(new double[]{p[16]*dx+p[17]*dy+p[18]*dz,p[20]*dx+p[21]*dy+p[22]*dz,p[24]*dx+p[25]*dy+p[26]*dz});
    }
    private static double nearNdc(ExactProjectionState.DepthConvention d){return switch(d){case ZERO_TO_ONE->0;case NEGATIVE_ONE_TO_ONE->-1;default->1;};}
    private static double farNdc(ExactProjectionState.DepthConvention d){return switch(d){case ZERO_TO_ONE,NEGATIVE_ONE_TO_ONE->1;case REVERSED_ZERO_TO_ONE->0;case REVERSED_NEGATIVE_ONE_TO_ONE->-1;};}
    private static void compare(double[] actual,double[] expected,double tolerance,String label) {
        for(int i=0;i<3;i++) {
            double error=Math.abs(actual[i]-expected[i]);
            require(Double.isFinite(error)&&error<=tolerance,label+" error="+error+" component="+i);
            if(label.endsWith("origin"))maximumOriginError=Math.max(maximumOriginError,error);
            else maximumDirectionError=Math.max(maximumDirectionError,error);
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static double[] identity(){return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static double[] translation(double x,double y,double z){double[] m=identity();m[3]=x;m[7]=y;m[11]=z;return m;}
    private static double[] scale(double x,double y,double z){double[] m=identity();m[0]=x;m[5]=y;m[10]=z;return m;}
    private static double[] rotation(int axis,double angle){double[] m=identity();int a=(axis+1)%3,b=(axis+2)%3;m[a*4+a]=m[b*4+b]=Math.cos(angle);m[a*4+b]=-Math.sin(angle);m[b*4+a]=Math.sin(angle);return m;}
    private static double[] multiply(double[] a,double[] b){double[] r=new double[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)r[i*4+j]+=a[i*4+k]*b[k*4+j];return r;}
    private static double[] vector(double[] m,double[] v){return new double[]{m[0]*v[0]+m[1]*v[1]+m[2]*v[2],m[4]*v[0]+m[5]*v[1]+m[6]*v[2],m[8]*v[0]+m[9]*v[1]+m[10]*v[2]};}
    private static double[] point(double[] m,double[] v){double[] r=vector(m,v);r[0]+=m[3];r[1]+=m[7];r[2]+=m[11];return r;}
    private static double[] normalize(double[] v){double n=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);return new double[]{v[0]/n,v[1]/n,v[2]/n};}
    private static final class Effects {
        double[] forward=identity(),inverse=identity();
        void add(double[] next,double[] undo){forward=multiply(forward,next);inverse=multiply(undo,inverse);}
        void rotate(int axis,double angle){add(rotation(axis,angle),rotation(axis,-angle));}
    }
}
