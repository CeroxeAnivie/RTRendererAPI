package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Real production JNI/resource/direct/lease proof, without UI automation or FPS claims. */
public final class RtxptProductionProof {
    private static final String INSTANCE="proof-instance", MESH="proof-mesh";
    private static long sequence;

    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: <runtime-bin> <output-directory> [beauty|controls|regression|hud|materials|materials-direct|materials-shadow|progress]");
        boolean beauty=args.length==3&&args[2].equals("beauty");
        boolean controls=args.length==3&&args[2].equals("controls");
        boolean hud=args.length==3&&args[2].equals("hud");
        Path runtime=Path.of(args[0]).toAbsolutePath().normalize();
        Path output=Path.of(args[1]).toAbsolutePath().normalize();Files.createDirectories(output);
        System.setProperty("top.ceroxe.rt.rtxpt.runtime",runtime.toString());
        RtxptNative.load();
        if(args.length==3&&args[2].equals("progress")){
            long[] values=RtxptNative.progressWindowProof();
            if(values.length!=8||values[0]<0||values[1]!=0||values[2]!=1||values[3]!=0
                    ||values[4]!=-1||values[5]!=0||values[6]!=0||values[7]!=1)
                throw new AssertionError("Original progress behavior mismatch: "+Arrays.toString(values));
            Files.writeString(output.resolve("progress-window-proof.json"),"{\"state\":"+Arrays.toString(values)+",\"pass\":true}\n",StandardCharsets.UTF_8);
            System.out.println("Original progress proof PASS: "+Arrays.toString(values));return;
        }
        if(args.length==3&&(args[2].equals("materials")||args[2].equals("materials-direct")||args[2].equals("materials-shadow"))){materialFixes(runtime,output,!args[2].equals("materials"),args[2].equals("materials-shadow"));return;}
        if(args.length==3&&args[2].equals("regression")){regression(runtime,output);return;}
        System.out.println("Production proof: creating original managed scene");
        long handle=RtxptNative.create(runtime.toString(),"",(controls||hud)?1600:beauty?256:64,(controls||hud)?1000:beauty?144:64,true,
                (beauty||controls||hud)?new String[]{"--nonInteractive=true","--stopAnimations=true","--overrideToRealtimeMode=true","--realtimeAA=0",
                        "--standaloneDenoiser=0","--useReSTIRDI=0","--useReSTIRGI=0",
                        "--overrideRadianceCacheMode=0","--overrideSubSamplingMode=0",
                        "--overrideAutoexposureOff=true","--overrideExposureOffset=0"}
                :new String[]{"--nonInteractive=true","--stopAnimations=true"});
        System.out.println("Production proof: managed scene initialized");
        boolean closed=false;
        try {
            if(RtxptNative.window(handle)==0)throw new AssertionError("No actual native window");
            RtxptNative.title(handle,"生产 API 功能证明 🌞");
            if(hud){
                proofScene(handle,new float[]{.1f,.2f,.3f,1});
                for(int i=0;i<4;i++)beautyFrame(handle,1);
                checkHud(handle,output);RtxptNative.awaitIdle(handle);RtxptNative.close(handle);closed=true;
                System.out.println("Final-window HUD proof PASS: frames="+sequence);return;
            }
            if(controls){
                controls(handle,output);RtxptNative.close(handle);closed=true;
                System.out.println("Presentation control proof PASS: frames="+sequence+" actual path depth, Reflex, 2x FG, exposure, original quality fields");
                return;
            }
            if(beauty){
                beauty(handle,output);RtxptNative.close(handle);closed=true;
                System.out.println("Beauty production proof PASS: frames="+sequence
                        +" original beauty, transform updates, analytic light/sky, remove/reuse, resize, no direct/sparse pass");
                return;
            }
            float[] parameters=parameters(64,64);
            RtxptNative.nativeLighting(handle,parameters);
            RtxptNative.resourcesMemory(handle,1,packet(true,false));
            attributes(handle,1);
            RtxptNative.sparseLighting(handle,new long[]{42},new float[]{
                    0,0,-1,0, 0,0,-1,8, 1,.5f,.2f,4, 0,0,0,1, 1,-1,0,0, 1,0,0,1
            },new long[0],true);
            RtxptNative.lightingPatch(handle,new long[0],new float[0],new long[0],
                    new float[]{0,0,0,0,0,0,-1,0,1,1,1,1,0,0,1,0},new float[]{.1f,.2f,.3f,1});
            for(int i=0;i<24;i++){render(handle,1,64,64,1);if(i==0||i==15||i==23)System.out.println("Production proof: completed frame "+(i+1));}
            RtxptNative.nativeLightingCapture(handle,output.resolve("direct.pfm").toString(),0);
            RtxptNative.nativeLightingCapture(handle,output.resolve("geometry.pfm").toString(),2);
            checkPfm(output.resolve("direct.pfm"),true);
            checkPfm(output.resolve("geometry.pfm"),true);
            long[] telemetry=RtxptNative.nativeLightingTelemetry(handle);
            if(telemetry.length!=8||telemetry[0]!=1||telemetry[4]<24||telemetry[5]!=0)
                throw new AssertionError("Production direct execution missing: "+Arrays.toString(telemetry));
            RtxptNative.resourcesMemory(handle,2,packet(false,true));render(handle,2,64,64,1);
            RtxptNative.resourcesMemory(handle,3,packet(true,false));attributes(handle,3);
            render(handle,3,64,64,1);
            RtxptNative.resetHistory(handle);RtxptNative.resize(handle,48,40);
            RtxptNative.nativeLighting(handle,parameters(48,40));render(handle,4,48,40,1);
            RtxptNative.nativeLightingEncoding(handle,1);render(handle,5,48,40,4);
            long[] rr=RtxptNative.nativeLightingReconstructionState(handle);
            if(rr.length!=7||rr[0]!=0||rr[1]!=0)throw new AssertionError("Unexpected RR state");
            RtxptNative.frameContext(handle,++sequence,6);
            if(RtxptNative.frame(handle)!=1)throw new AssertionError("Final frame did not complete");
            long[] lease=RtxptNative.nativeLightingFrame(handle);
            expectFailure(()->RtxptNative.close(handle),"close with outstanding consumer lease");
            expectFailure(()->RtxptNative.nativeLightingRelease(handle,(int)lease[1],lease[2]+1,lease[3],false),"stale lease identity");
            RtxptNative.nativeLightingRelease(handle,(int)lease[1],lease[2],lease[3],false);
            RtxptNative.awaitIdle(handle);
            if(!RtxptNative.nativeLightingConsumersRetired(handle))throw new AssertionError("Consumer lease not retired");
            RtxptNative.close(handle);closed=true;
            expectFailure(()->RtxptNative.frame(handle),"closed renderer identity");
            System.out.println("Production proof PASS: frames="+sequence+" direct="+Arrays.toString(telemetry)
                    +" sparse=owned resource remove/reuse, nearest/clamp, resize, compact, lease rejection/retirement, UTF-8 title");
        } finally {if(!closed)RtxptNative.close(handle);}
    }
    private static void materialFixes(Path runtime,Path output,boolean directOnly,boolean shadowOnly) throws Exception {
        String[] options={"--nonInteractive=true","--stopAnimations=true","--overrideToRealtimeMode=true",
                "--realtimeAA=0","--standaloneDenoiser=0","--useReSTIRDI=0","--useReSTIRGI=0",
                "--overrideRadianceCacheMode=0","--overrideSubSamplingMode=0",
                "--overrideAutoexposureOff=true","--overrideExposureOffset=0"};
        float[] emission={.0003f,.1234567f,3.14159f};
        if(!directOnly){
        long beauty=RtxptNative.create(runtime.toString(),"",256,144,true,options);
        try {
            RtxptNative.resourcesMemory(beauty,1,materialFixture(0,1,emission));
            RtxptNative.camera(beauty,"0,0,0,0,0,-1,0,1,0",(float)Math.toRadians(60));
            proofSky(beauty,new float[]{0,0,0,1});
            for(int i=0;i<4;i++)beautyFrame(beauty,1);
            Path file=output.resolve("emissive-linear-hdr.pfm");RtxptNative.nativeLightingCapture(beauty,file.toString(),21);
            for(int channel=0;channel<3;channel++){
                // Original ShadingData::SetEmission uses RGB10 log packing.
                // This proves native behavior, not raw-float visual equivalence.
                float expected=originalPackedEmission(emission[channel]);
                checkCenterSignal(file,256,144,channel,expected,Math.max(2e-6,expected*.002));
                System.out.println("Original emission channel="+channel+" canonical="+emission[channel]+" RGB10/fp16="+expected);
            }
        } finally {RtxptNative.close(beauty);}
        }
        long direct=RtxptNative.create(runtime.toString(),"",64,64,true,options);
        try {
            float[] parameters=parameters(64,64);parameters[44]=(float)Math.sqrt(.5);parameters[46]=(float)Math.sqrt(.5);parameters[70]=1;
            RtxptNative.nativeLighting(direct,parameters);
            if(!shadowOnly){
            RtxptNative.resourcesMemory(direct,1,materialFixture(1,.49999f,new float[3]));
            RtxptNative.nativeLightingAttributes(direct,1,new String[]{INSTANCE},new float[]{23,1,0,0,0,0,0,1},null,null);
            render(direct,1,64,64,1);
            Path below=output.resolve("masked-receiver-below.pfm");RtxptNative.nativeLightingCapture(direct,below.toString(),2);
            checkCenterSignal(below,64,64,2,0,0);
            RtxptNative.resourcesMemory(direct,2,materialOnly("proof-material",1,.50001f,new float[3]));render(direct,2,64,64,1);
            Path above=output.resolve("masked-receiver-above.pfm");RtxptNative.nativeLightingCapture(direct,above.toString(),2);
            checkCenterSignal(above,64,64,2,1,1e-5);
            }
            RtxptNative.resourcesMemory(direct,3,materialFixture(2,.49999f,new float[3]));
            RtxptNative.nativeLightingAttributes(direct,3,new String[]{INSTANCE},new float[]{23,1,0,0,0,0,0,1},null,null);
            render(direct,3,64,64,1);
            Path clear=output.resolve("masked-shadow-below.pfm");RtxptNative.nativeLightingCapture(direct,clear.toString(),0);
            checkCenterSignal(clear,64,64,0,1,.002);
            RtxptNative.resourcesMemory(direct,4,materialOnly("proof-blocker-material",1,.50001f,new float[3]));render(direct,4,64,64,1);
            Path blocked=output.resolve("masked-shadow-above.pfm");RtxptNative.nativeLightingCapture(direct,blocked.toString(),0);
            checkCenterSignal(blocked,64,64,0,.04,.002);
            RtxptNative.awaitIdle(direct);
        } finally {RtxptNative.close(direct);}
        System.out.println("Material fixes proof PASS: "+(directOnly?"":"original RGB10 emission behavior, ")+(shadowOnly?"":"masked receiver and ")+"shadow scalar threshold, material-only incremental update; frames="+sequence);
    }
    private static float originalPackedEmission(float value){
        // Original PathTracer/Utils/Packing.hlsli:294-321, with fp16 endpoints.
        float input=Float.float16ToFloat(Float.floatToFloat16(value));
        input=Math.max(0,Math.min(1_000_000,input));
        int q=(int)Math.rint(Math.log1p(input)/Math.log(2)/19.931570012018494*1023);
        float unpacked=(float)(Math.pow(2,q/1023.0*19.931570012018494)-1);
        return Float.float16ToFloat(Float.floatToFloat16(unpacked));
    }
    private static void checkCenterSignal(Path path,int width,int height,int channel,double expected,double tolerance) throws Exception {
        byte[] bytes=Files.readAllBytes(path);int offset=0;for(int i=0;i<3;i++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        String header=new String(bytes,0,offset,StandardCharsets.US_ASCII);
        if(!header.equals("PF\n"+width+" "+height+"\n-1.0\n")||bytes.length-offset!=width*height*12)
            throw new AssertionError("Invalid material proof PFM: "+header);
        ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);double sum=0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;int count=0;
        for(int y=height/2-2;y<=height/2+2;y++)for(int x=width/2-2;x<=width/2+2;x++){
            float value=data.getFloat(offset+((height-1-y)*width+x)*12+channel*4);
            if(!Float.isFinite(value)||Math.abs(value-expected)>tolerance)
                throw new AssertionError(path.getFileName()+" channel "+channel+" pixel "+x+","+y+" expected "+expected+" +/- "+tolerance+", got "+value);
            sum+=value;min=Math.min(min,value);max=Math.max(max,value);count++;
        }
        System.out.println(path.getFileName()+" channel="+channel+" expected="+expected+" mean="+(sum/count)+" range="+min+".."+max+" samples="+count);
    }
    private static ByteBuffer materialOnly(String name,int blend,float opacity,float[] emission){
        ByteBuffer b=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x52445257).putInt(0).putInt(1);
        proofMaterial(b,name,blend,opacity,emission);for(int i=0;i<5;i++)b.putInt(0);return b.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static ByteBuffer materialFixture(int mode,float opacity,float[] emission){
        boolean shadow=mode==2;ByteBuffer b=ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x52445257).putInt(0).putInt(shadow?2:1);
        proofMaterial(b,"proof-material",mode==1?1:0,mode==1?opacity:1,emission);
        if(shadow)proofMaterial(b,"proof-blocker-material",1,opacity,new float[3]);
        b.putInt(shadow?2:1);proofTriangle(b,MESH,"proof-material",new float[]{-2,-2,-3,2,-2,-3,0,2,-3});
        if(shadow)proofTriangle(b,"proof-blocker-mesh","proof-blocker-material",new float[]{.2f,-2,-2,1.8f,-2,-2,1,2,-2});
        b.putInt(0).putInt(shadow?2:1);proofNode(b,INSTANCE,MESH);
        if(shadow)proofNode(b,"proof-blocker-instance","proof-blocker-mesh");
        b.putInt(0).putInt(0);return b.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void proofMaterial(ByteBuffer b,String name,int blend,float opacity,float[] emission){
        text(b,name);b.putInt(-1);b.putFloat(opacity).putFloat(1).putFloat(0).putFloat(0).putFloat(.5f).putInt(blend).putInt(1);
        for(float color:emission)b.putFloat(color);b.putFloat(1.5f).putInt(1).putInt(-1).putInt(0);for(int i=0;i<4;i++)b.putLong(-1);b.putInt(0);
    }
    private static void proofTriangle(ByteBuffer b,String mesh,String material,float[] positions){
        text(b,mesh);floats(b,positions);floats(b,0,0,1,0,0,1,0,0,1);floats(b,1,0,0,1,1,0,0,1,1,0,0,1);floats(b,0,0,1,0,.5f,1);
        b.putInt(1);text(b,material);b.putInt(3).putInt(0).putInt(1).putInt(2);
    }
    private static void proofNode(ByteBuffer b,String instance,String mesh){
        text(b,instance);text(b,mesh);floats(b,1,0,0,0,0,1,0,0,0,0,1,0);
    }
    // Target only the newly repaired original beauty inputs and final HUD.
    // This entry does not repeat the direct-light/consumer-lease proof.
    private static void regression(Path runtime,Path output) throws Exception {
        String[] options={"--nonInteractive=true","--stopAnimations=true","--overrideToRealtimeMode=true",
                "--realtimeAA=0","--standaloneDenoiser=0","--useReSTIRDI=0","--useReSTIRGI=0",
                "--overrideRadianceCacheMode=0","--overrideSubSamplingMode=0",
                "--overrideAutoexposureOff=true","--overrideExposureOffset=0"};
        long motion=RtxptNative.create(runtime.toString(),"",256,144,true,options);
        try {
            proofScene(motion,new float[]{.1f,.2f,.3f,1});
            for(int i=0;i<4;i++)beautyFrame(motion,1);checkTransform(motion,0,0);
            RtxptNative.resourcesMemory(motion,2,transform(.08f));beautyFrame(motion,2);checkTransform(motion,.08,0);
            Path moved=output.resolve("motion-moved.pfm"),still=output.resolve("motion-still.pfm");
            RtxptNative.nativeLightingCapture(motion,moved.toString(),19);
            checkMotion(moved,-.08*144/(2*3*Math.tan(Math.toRadians(30))));
            beautyFrame(motion,2);checkTransform(motion,.08,.08);
            RtxptNative.nativeLightingCapture(motion,still.toString(),19);checkMotion(still,0);
        } finally {RtxptNative.close(motion);}
        options=Arrays.copyOf(options,options.length+1);options[3]="--realtimeAA=2";
        options[options.length-1]="--overrideDLSSMode=3";
        long sr=RtxptNative.create(runtime.toString(),"",1600,1000,true,options);
        try {
            proofScene(sr,new float[]{1,0,0,2});
            for(int i=0;i<4;i++)beautyFrame(sr,1);
            long[] actual=RtxptNative.renderingState(sr);
            if(actual.length!=16||actual[0]!=1||actual[1]!=2||actual[2]!=3||actual[4]!=0||actual[15]!=0)
                throw new AssertionError("SR/no-NRD original execution not active: "+Arrays.toString(actual));
            Path redRaw=output.resolve("sr-red-raw.pfm"),red=output.resolve("sr-red-output.pfm");
            RtxptNative.nativeLightingCapture(sr,redRaw.toString(),21);RtxptNative.nativeLightingCapture(sr,red.toString(),22);
            checkDominantSky(redRaw,0);checkDominantSky(red,0);
            proofSky(sr,new float[]{0,0,1,2});RtxptNative.resetHistory(sr);beautyFrame(sr,2);
            Path blueRaw=output.resolve("sr-blue-current-raw.pfm"),blue=output.resolve("sr-blue-current-output.pfm");
            RtxptNative.nativeLightingCapture(sr,blueRaw.toString(),21);RtxptNative.nativeLightingCapture(sr,blue.toString(),22);
            checkDominantSky(blueRaw,2);checkDominantSky(blue,2);
            System.out.println("SR original state="+Arrays.toString(actual)+"; window="+Arrays.toString(RtxptNative.windowState(sr)));
            checkHud(sr,output);RtxptNative.awaitIdle(sr);
        } finally {RtxptNative.close(sr);}
        System.out.println("New regression proof PASS: actual current/previous transforms, motion direction/magnitude/still zero, same-frame original SR input, final-window HUD; frames="+sequence);
    }
    private static void proofScene(long handle,float[] sky){
        RtxptNative.resourcesMemory(handle,1,packet(true,false,true));
        RtxptNative.camera(handle,"0,0,0,0,0,-1,0,1,0",(float)Math.toRadians(60));proofSky(handle,sky);
    }
    private static void proofSky(long handle,float[] sky){
        RtxptNative.lightingPatch(handle,new long[0],new float[0],new long[0],
                new float[]{0,0,0,0,0,0,-1,0,1,1,1,1,0,0,1,0},sky);
    }
    private static void checkDominantSky(Path path,int channel) throws Exception {
        byte[] bytes=Files.readAllBytes(path);int start=0;
        for(int line=0;line<3;line++){while(start<bytes.length&&bytes[start++]!='\n'){} }
        String[] header=new String(bytes,0,start,StandardCharsets.US_ASCII).trim().split("\\s+");
        if(header.length!=4||!header[0].equals("PF")||!header[3].equals("-1.0"))
            throw new AssertionError("Invalid original SR output PFM: "+path);
        int width=Integer.parseInt(header[1]),height=Integer.parseInt(header[2]);
        if(width<64||height<64||bytes.length-start!=width*height*12)throw new AssertionError("Invalid SR output extent");
        ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);double[] mean=new double[3];int count=0;
        // This corner is outside the test triangle and sees a constant environment.
        // SR may filter and rescale it, but a reset frame must use this frame's color.
        for(int y=height/20;y<height/10;y++)for(int x=width*9/10;x<width*19/20;x++){
            int offset=start+((height-1-y)*width+x)*12;
            for(int c=0;c<3;c++){float value=data.getFloat(offset+c*4);
                if(!Float.isFinite(value))throw new AssertionError("Nonfinite SR sky output: "+path);mean[c]+=value;}
            count++;
        }
        for(int c=0;c<3;c++)mean[c]/=count;
        if(mean[channel]<.01||mean[channel]<4*Math.max(mean[(channel+1)%3],mean[(channel+2)%3]))
            throw new AssertionError("Original SR output used stale/empty sky color: "+path+" RGB="+Arrays.toString(mean));
        System.out.println(path.getFileName()+": current original sky RGB="+Arrays.toString(mean)+", size="+width+"x"+height);
    }
    private static void beauty(long handle,Path output) throws Exception {
        RtxptNative.resourcesMemory(handle,1,packet(true,false,true));
        RtxptNative.camera(handle,"0,0,0,0,0,-1,0,1,0",(float)Math.toRadians(60));
        RtxptNative.lightingPatch(handle,new long[0],new float[0],new long[0],
                new float[]{0,0,0,0,0,0,-1,0,1,1,1,1,0,0,1,0},new float[]{.1f,.2f,.3f,1});
        for(int i=0;i<8;i++)beautyFrame(handle,1);
        Path before=output.resolve("beauty-before.png"),after=output.resolve("beauty-moved.png");
        RtxptNative.capture(handle,before.toString());
        checkTransform(handle,0,0);
        for(int i=0;i<12;i++){
            RtxptNative.resourcesMemory(handle,i+2,transform((i+1)*.08f));beautyFrame(handle,i+2);
            checkTransform(handle,(i+1)*.08f,i*.08f);
            if(i==0){
                Path moved=output.resolve("motion-moved.pfm"),still=output.resolve("motion-still.pfm");
                RtxptNative.nativeLightingCapture(handle,moved.toString(),19);
                // Original ComputeMotionVector exports previous-current in pixels.
                // The camera is fixed at z=0, the triangle at z=-3, vFOV=60 deg.
                double expected=-.08*144/(2*3*Math.tan(Math.toRadians(30)));
                checkMotion(moved,expected);
                beautyFrame(handle,i+2);checkTransform(handle,.08,.08);
                RtxptNative.nativeLightingCapture(handle,still.toString(),19);checkMotion(still,0);
            }
        }
        RtxptNative.capture(handle,after.toString());
        checkImages(before,after);
        RtxptNative.resourcesMemory(handle,20,packet(false,true));beautyFrame(handle,20);
        RtxptNative.resourcesMemory(handle,21,packet(true,false,true));beautyFrame(handle,21);beautyFrame(handle,21);
        RtxptNative.resize(handle,256,160);beautyFrame(handle,22);
        Path resized=output.resolve("beauty-resized.png");RtxptNative.capture(handle,resized.toString());
        var image=ImageIO.read(resized.toFile());
        if(image==null||image.getWidth()!=256||image.getHeight()!=160)throw new AssertionError("Beauty resize output mismatch");
        RtxptNative.awaitIdle(handle);
    }
    private static void controls(long handle,Path output) throws Exception {
        RtxptNative.presentation(handle,7,1,true,0);RtxptNative.exposure(handle,0);
        RtxptNative.quality(handle,0,2,7,1,.4f,2,6,2,true,.25f,false,3.5f,.01f,true,0);
        expectFailure(()->RtxptNative.exposure(handle,Float.NaN),"nonfinite exposure");
        expectFailure(()->RtxptNative.quality(handle,0,2,7,1,.4f,2,16,2,true,.25f,false,3.5f,.01f,true,0),"quality depth over packed original maximum");
        RtxptNative.resourcesMemory(handle,1,packet(true,false,true));
        RtxptNative.camera(handle,"0,0,0,0,0,-1,0,1,0",(float)Math.toRadians(60));
        RtxptNative.lightingPatch(handle,new long[0],new float[0],new long[0],
                new float[]{0,0,0,0,0,0,-1,0,1,1,1,1,0,0,1,0},new float[]{.1f,.2f,.3f,1});
        for(int i=0;i<8;i++)beautyFrame(handle,1);
        long[] off=RtxptNative.presentationState(handle);
        if(off.length!=10||off[0]!=7||off[1]!=1||off[3]!=0||off[6]!=0||off[8]!=1)
            throw new AssertionError("Original presentation settings not applied: "+Arrays.toString(off));
        float[] quality=RtxptNative.qualityState(handle),expected={0,2,7,1,.4f,2,6,2,1,.25f,0,3.5f,.01f,1};
        if(!Arrays.equals(quality,expected))throw new AssertionError("Original quality settings not applied: "+Arrays.toString(quality));
        Path bright=output.resolve("exposure-zero.png"),dark=output.resolve("exposure-minus.png");
        RtxptNative.capture(handle,bright.toString());RtxptNative.exposure(handle,-1.5f);
        for(int i=0;i<4;i++)beautyFrame(handle,1);RtxptNative.capture(handle,dark.toString());
        double original=mean(bright),lower=mean(dark);
        float[] exposure=RtxptNative.exposureState(handle);
        if(!Arrays.equals(exposure,new float[]{0,0,-1.5f}))throw new AssertionError("Original exposure state mismatch: "+Arrays.toString(exposure));
        if(original<=0||lower>=original*.9)throw new AssertionError("Dynamic exposure did not darken original output: "+original+" -> "+lower);
        RtxptNative.presentation(handle,7,1,true,1);
        for(int i=0;i<12;i++)beautyFrame(handle,1);
        long[] on=RtxptNative.presentationState(handle);
        System.out.println("Original presentation OFF="+Arrays.toString(off)+"; ON="+Arrays.toString(on));
        System.out.println("Original exposure mean: "+original+" -> "+lower);
        System.out.println("Original quality="+Arrays.toString(quality)+"; exposure="+Arrays.toString(exposure));
        if(on[3]!=1||on[4]!=1||on[6]!=1||on[7]<2||on[9]!=1)
            throw new AssertionError("Original 2x FG did not actually present generated frames: "+Arrays.toString(on));
        RtxptNative.presentation(handle,7,1,false,0);beautyFrame(handle,1);beautyFrame(handle,1);
        long[] disabled=RtxptNative.presentationState(handle);
        if(disabled[2]!=0||disabled[3]!=0||disabled[5]!=0||disabled[6]!=0)
            throw new AssertionError("Original FG/Reflex disable not applied: "+Arrays.toString(disabled));
        checkHud(handle,output);
        RtxptNative.awaitIdle(handle);
    }
    private static void checkTransform(long handle,double currentX,double previousX){
        double[] values=RtxptNative.instanceTransformState(handle,INSTANCE);
        if(values.length!=24)throw new AssertionError("Invalid original transform state");
        for(int i=0;i<values.length;i++){
            int component=i%12;
            double expected=component==0||component==5||component==10?1:
                    component==3?(i<12?currentX:previousX):0;
            if(!Double.isFinite(values[i])||Math.abs(values[i]-expected)>1e-6)
                throw new AssertionError("Original current/previous transform mismatch at "+i+": "+Arrays.toString(values));
        }
    }
    private static void checkMotion(Path path,double expectedX) throws Exception {
        byte[] bytes=Files.readAllBytes(path);int start=0;
        for(int line=0;line<3;line++){while(start<bytes.length&&bytes[start++]!='\n'){} }
        String header=new String(bytes,0,start,StandardCharsets.US_ASCII);
        if(!header.equals("PF\n256 144\n-1.0\n")||bytes.length-start!=256*144*3*4)
            throw new AssertionError("Invalid original motion-vector PFM: "+header);
        ByteBuffer values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,sum=0;int samples=0;
        for(int y=68;y<=76;y++)for(int x=124;x<=132;x++){
            int offset=start+((143-y)*256+x)*12;
            float dx=values.getFloat(offset),dy=values.getFloat(offset+4),dz=values.getFloat(offset+8);
            // Half-float quantization is <.002px here; .03px also allows normal
            // float projection error, while rejecting a missing or wrong vector.
            if(!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(dz)
                    ||Math.abs(dx-expectedX)>.03||Math.abs(dy)>.03||Math.abs(dz)>.03)
                throw new AssertionError("Original motion pixel "+x+","+y+" expected ("+expectedX+",0,0), got ("+dx+","+dy+","+dz+")");
            min=Math.min(min,dx);max=Math.max(max,dx);sum+=dx;samples++;
        }
        System.out.println(path.getFileName()+": original motion pixels="+samples+", expectedX="+expectedX
                +", meanX="+(sum/samples)+", range="+min+".."+max);
    }
    private static void checkHud(long handle,Path output) throws Exception {
        RtxptNative.overlayChoices(handle,new String[]{"FIRST SCENE","SECOND SCENE"},1);
        if(RtxptNative.overlaySelection(handle)!=1)throw new AssertionError("HUD scene selection was not applied");
        RtxptNative.overlayText(handle,"RTR HUD PIXEL PROOF\nMMMMMMMMMMMMMMMMMMMMMMMM");
        // Original ImGui hides the first auto-sized window frame to measure its
        // content (imgui.cpp: window_just_created/HiddenFramesCannotSkipItems).
        beautyFrame(handle,1);beautyFrame(handle,1);
        Path visible=output.resolve("hud-visible.png");RtxptNative.capture(handle,visible.toString());
        RtxptNative.overlayText(handle,"");RtxptNative.overlayChoices(handle,new String[0],-1);
        if(RtxptNative.overlaySelection(handle)!=-1)throw new AssertionError("HUD empty scene selection was not applied");
        beautyFrame(handle,1);Path hidden=output.resolve("hud-hidden.png");RtxptNative.capture(handle,hidden.toString());
        var on=ImageIO.read(visible.toFile());var off=ImageIO.read(hidden.toFile());
        if(on==null||off==null||on.getWidth()!=1600||on.getHeight()!=1000
                ||off.getWidth()!=1600||off.getHeight()!=1000)throw new AssertionError("HUD final-window capture extent mismatch");
        int glyphPixels=0;
        for(int y=20;y<130;y++)for(int x=24;x<360;x++){
            int a=on.getRGB(x,y),b=off.getRGB(x,y);
            int r=(a>>>16)&255,g=(a>>>8)&255,blue=a&255;
            int old=((b>>>16)&255)+((b>>>8)&255)+(b&255);
            if(Math.min(r,Math.min(g,blue))>=180&&Math.max(r,Math.max(g,blue))-Math.min(r,Math.min(g,blue))<12
                    &&r+g+blue-old>120)glyphPixels++;
        }
        if(glyphPixels<80)throw new AssertionError("HUD text absent from actual final swapchain capture: "+glyphPixels);
        System.out.println("Final-window HUD proof: visible text glyph pixels="+glyphPixels+", native scene selection 1 -> -1");
    }
    private static double mean(Path path) throws Exception {
        var image=ImageIO.read(path.toFile());
        if(image==null||image.getWidth()!=1600||image.getHeight()!=1000)throw new AssertionError("Invalid control capture");
        long sum=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int pixel=image.getRGB(x,y);sum+=(pixel&255)+((pixel>>8)&255)+((pixel>>16)&255);
        }
        return sum/(double)(image.getWidth()*image.getHeight()*3);
    }
    private static void beautyFrame(long handle,long revision){
        RtxptNative.frameContext(handle,++sequence,revision);
        if(RtxptNative.frame(handle)!=1)throw new AssertionError("Beauty frame did not complete");
        if(RtxptNative.nativeLightingFrame(handle).length!=0)throw new AssertionError("Beauty enabled direct signal publishing");
    }
    private static ByteBuffer transform(float x){
        ByteBuffer b=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x52445257).putInt(0).putInt(0).putInt(0).putInt(0).putInt(1);
        text(b,INSTANCE);text(b,MESH);floats(b,1,0,0,x,0,1,0,0,0,0,1,0);
        b.putInt(0).putInt(0);return b.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void checkImages(Path before,Path after) throws Exception {
        var a=ImageIO.read(before.toFile());var b=ImageIO.read(after.toFile());
        if(a==null||b==null||a.getWidth()!=256||a.getHeight()!=144||b.getWidth()!=256||b.getHeight()!=144)
            throw new AssertionError("Invalid original beauty capture");
        int changed=0,min=255,max=0;
        for(int y=0;y<144;y++)for(int x=0;x<256;x++){
            int pixel=a.getRGB(x,y);if(pixel!=b.getRGB(x,y))changed++;
            for(int shift:new int[]{0,8,16}){int channel=(pixel>>shift)&255;min=Math.min(min,channel);max=Math.max(max,channel);}
        }
        if(max-min<2||changed<16)throw new AssertionError("Beauty output lacks actual dynamic content: range="+min+".."+max+" changed="+changed);
        System.out.println("Beauty capture: channel range="+min+".."+max+", transform changed pixels="+changed+"/36864");
    }
    private static void attributes(long handle,long generation){
        RtxptNative.nativeLightingAttributes(handle,generation,new String[]{INSTANCE},
                new float[]{23,1,0,0,1,1,0,0},null,null);
        RtxptNative.nativeLightingAttributeBatch(handle,generation,new String[]{MESH+":0"},
                new float[][]{{.1f,.2f,.1f,.2f,.1f,.2f}},new int[][]{{-1,-1,-1}});
    }
    private static void render(long handle,long revision,int width,int height,int format){
        if(!RtxptNative.nativeLightingCanPublish(handle))throw new AssertionError("No publish capacity");
        RtxptNative.frameContext(handle,++sequence,revision);
        RtxptNative.nativeLightingClock(handle,System.nanoTime(),16_666_667);
        if(RtxptNative.frame(handle)!=1)throw new AssertionError("Production frame did not complete");
        long[] p=RtxptNative.nativeLightingFrame(handle);
        if(p.length<22||p[0]!=2||p[3]!=sequence||p[4]!=revision||p[6]!=width||p[7]!=height||p[8]!=5
                ||p[9]==0||p[10]>=p[11]||p[15]!=2||p[17]!=format)
            throw new AssertionError("Invalid signal packet: "+Arrays.toString(p));
        if(RtxptNative.nativeLightingFrame(handle).length!=0)throw new AssertionError("Lease acquired twice");
        RtxptNative.nativeLightingRelease(handle,(int)p[1],p[2],p[3],false);
    }
    private static float[] parameters(int width,int height){
        float[] p=new float[80];p[0]=p[5]=.57735026f;p[11]=-1;p[14]=-1000;p[15]=1000;
        p[16]=p[21]=p[26]=p[31]=1;p[36]=.9999f;p[40]=width;p[41]=height;p[42]=5;
        p[46]=p[47]=p[48]=p[49]=p[50]=1;p[53]=1;p[56]=32;p[58]=.003f;
        p[60]=p[61]=p[62]=p[63]=1;p[64]=p[65]=p[66]=1;
        p[68]=.04f;p[69]=12;p[70]=2;return p;
    }
    private static ByteBuffer packet(boolean mesh,boolean remove){
        return packet(mesh,remove,false);
    }
    private static ByteBuffer packet(boolean mesh,boolean remove,boolean originalSampler){
        ByteBuffer b=ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x52445257).putInt(mesh?1:0);
        if(mesh){
            b.putLong(7).putInt(2).putInt(2).putInt(1).putInt(0)
                    .putInt(originalSampler?0:1).putInt(originalSampler?0:1).putInt(originalSampler?1:0).putLong(1).putInt(0);
            b.putInt(16).put(new byte[]{-1,0,0,-1,0,-1,0,-1,0,0,-1,-1,-1,-1,-1,-1});
        }
        b.putInt(mesh?1:0);
        if(mesh){text(b,"proof-material");b.putInt(-1);
            for(float x:new float[]{1,1,0,0,.5f})b.putFloat(x);b.putInt(0).putInt(1);
            b.putFloat(0).putFloat(0).putFloat(0).putFloat(1.5f).putInt(1).putInt(-1).putInt(0);
            b.putLong(7).putLong(-1).putLong(-1).putLong(-1).putInt(0);}
        b.putInt(mesh?1:0);
        if(mesh){text(b,MESH);floats(b,-2,-2,-3,2,-2,-3,0,2,-3);floats(b,0,0,1,0,0,1,0,0,1);
            floats(b,1,0,0,1,1,0,0,1,1,0,0,1);floats(b,0,0,1,0,.5f,1);
            b.putInt(1);text(b,"proof-material");b.putInt(3).putInt(0).putInt(1).putInt(2);}
        b.putInt(remove?1:0);if(remove)text(b,INSTANCE);b.putInt(mesh?1:0);
        if(mesh){text(b,INSTANCE);text(b,MESH);floats(b,1,0,0,0,0,1,0,0,0,0,1,0);}
        b.putInt(0).putInt(0);return b.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void text(ByteBuffer b,String s){byte[] v=s.getBytes(StandardCharsets.UTF_8);b.putInt(v.length).put(v);}
    private static void floats(ByteBuffer b,float... values){b.putInt(values.length);for(float value:values)b.putFloat(value);}
    private static void expectFailure(Runnable action,String name){
        try{action.run();throw new AssertionError("Accepted "+name);}catch(IllegalStateException expected){}
    }
    private static void checkPfm(Path file,boolean varying) throws Exception {
        byte[] bytes=Files.readAllBytes(file);int start=0;for(int i=0;i<3;i++){while(bytes[start++]!='\n'){} }
        ByteBuffer data=ByteBuffer.wrap(bytes,start,bytes.length-start).order(ByteOrder.LITTLE_ENDIAN);
        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        while(data.hasRemaining()){float value=data.getFloat();if(!Float.isFinite(value))throw new AssertionError("Nonfinite "+file);min=Math.min(min,value);max=Math.max(max,value);}
        if(varying&&max-min<1e-5f)throw new AssertionError("Empty or constant capture: "+file+" range="+min+".."+max);
        System.out.println(file.getFileName()+": finite range="+min+".."+max);
    }
}
