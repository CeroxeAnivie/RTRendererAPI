package demo;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.Renderer;

/** Interactive, API-only nested-medium laboratory; every apply publishes a validated immutable state. */
public final class MediumTransportDemo {
    private MediumTransportDemo() {}
    private record State(EnvironmentState.Medium medium,MediumTransportOptions quality,float roughness,
            float transmission,int priority,boolean nested,boolean inside,boolean volumeOnly,boolean inward,boolean frameLocal) {}
    public static void main(String[] args) {
        DemoApiControls.parse(args);
        SwingUtilities.invokeLater(MediumTransportDemo::open);
    }
    private static void open() {
        JFrame window=new JFrame("RTRendererAPI "+DemoBuildInfo.version()+" - Medium Transport");
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        var running=new AtomicBoolean(true);
        window.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent event){running.set(false);}});
        JLabel canvas=new JLabel();canvas.setPreferredSize(new Dimension(640,360));
        JLabel status=new JLabel("Preparing hardware RT...");
        JPanel form=new JPanel(new GridLayout(0,2,6,4));
        Map<String,JTextField> fields=new LinkedHashMap<>();
        add(form,fields,"Absorption RGB","0.04,0.015,0.008");
        add(form,fields,"Scattering RGB","0.015,0.015,0.015");
        add(form,fields,"Emission RGB","0,0,0");
        add(form,fields,"Density","1");add(form,fields,"Interior IOR","1.333");add(form,fields,"HG anisotropy","0.2");
        add(form,fields,"Surface roughness","0.04");add(form,fields,"Surface transmission","1");add(form,fields,"Boundary priority","1");
        add(form,fields,"Interface events","12");add(form,fields,"Optical samples","1");add(form,fields,"Scattering intervals","8");
        add(form,fields,"Maximum distance","512");
        JCheckBox nested=new JCheckBox("Nested glass volume",true),inside=new JCheckBox("Camera inside volume");
        JCheckBox volumeOnly=new JCheckBox("Invisible volume interface"),inward=new JCheckBox("Inward winding");
        JCheckBox frameLocal=new JCheckBox("Use frame-local volume instances");
        form.add(nested);form.add(inside);form.add(volumeOnly);form.add(inward);
        form.add(frameLocal);form.add(new JLabel("Persistent / transient transport parity"));
        var state=new AtomicReference<State>();
        JButton apply=new JButton("Apply at next frame");
        Runnable publish=()->{
            try {
                float[] absorption=rgb(fields,"Absorption RGB"),scattering=rgb(fields,"Scattering RGB"),emission=rgb(fields,"Emission RGB");
                var medium=EnvironmentState.Medium.builder().absorption(absorption[0],absorption[1],absorption[2])
                    .scattering(scattering[0],scattering[1],scattering[2]).emission(emission[0],emission[1],emission[2])
                    .density(value(fields,"Density")).indexOfRefraction(value(fields,"Interior IOR")).phaseAnisotropy(value(fields,"HG anisotropy")).build();
                var quality=new MediumTransportOptions(integer(fields,"Interface events"),integer(fields,"Optical samples"),
                    integer(fields,"Scattering intervals"),value(fields,"Maximum distance"));
                float roughness=value(fields,"Surface roughness"),transmission=value(fields,"Surface transmission");
                MaterialAsset.builder(1).roughness(roughness).transmission(transmission).build();
                state.set(new State(medium,quality,roughness,transmission,integer(fields,"Boundary priority"),
                    nested.isSelected(),inside.isSelected(),volumeOnly.isSelected(),inward.isSelected(),frameLocal.isSelected()));
                status.setText("Validated; applying at next frame");
            } catch(RuntimeException failure) {status.setText("Rejected: "+failure.getMessage());}
        };
        apply.addActionListener(event->publish.run());form.add(apply);
        JButton reset=new JButton("Balanced defaults");form.add(reset);
        reset.addActionListener(event->{fields.get("Interface events").setText("12");fields.get("Optical samples").setText("1");
            fields.get("Scattering intervals").setText("8");fields.get("Maximum distance").setText("512");publish.run();});
        window.add(canvas,BorderLayout.CENTER);window.add(new JScrollPane(form),BorderLayout.EAST);window.add(status,BorderLayout.SOUTH);
        window.pack();window.setLocationRelativeTo(null);window.setVisible(true);publish.run();
        Thread.ofPlatform().name("medium-demo-renderer").start(()->{
            try(var renderer=Renderer.open(DemoApiControls.configure(RendererConfig.builder()
                    .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build()))) {
                long sequence=0,revision=0;State applied=null;
                while(running.get()) {
                    State snapshot=state.get();if(snapshot==null){Thread.sleep(10);continue;}
                    if(snapshot!=applied) {renderer.apply(scene(++revision,snapshot));applied=snapshot;}
                    var camera=snapshot.inside?CameraState.lookAt(0,0,.4,0,0,-4).aspectRatio(640.0/360).build()
                        :CameraState.lookAt(4,2,9,0,0,0).aspectRatio(640.0/360).build();
                    var request=DemoApiControls.frame(RenderFrameRequest.builder(++sequence,640,360,camera)
                        .minimumSceneRevision(revision).mediumTransport(snapshot.quality)
                        .primitiveBatch(primitiveVolumes(snapshot))
                        .environment(EnvironmentState.builder().skyRadiance(.12f,.18f,.3f).ambientIntensity(.15f)
                            .sunDirection(-.4f,-.8f,-.4472136f).sunIntensity(1).build()).build());
                    var submission=renderer.trySubmit(request);
                    if(!(submission instanceof Renderer.FrameSubmitted)){sequence--;Thread.sleep(2);continue;}
                    var ready=renderer.awaitFrame(Duration.ofSeconds(30));
                    if(ready.isEmpty())throw new IllegalStateException("hardware frame timed out");
                    var frame=ready.orElseThrow();var bytes=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
                    BufferedImage image=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_RGB);
                    for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++){
                        boolean hdr=frame.format()==TextureFormat.RGBA16_FLOAT;
                        int at=y*frame.rowBytes()+x*(hdr?8:4);
                        int r=hdr?display(Float.float16ToFloat(bytes.getShort(at))):Byte.toUnsignedInt(bytes.get(at));
                        int g=hdr?display(Float.float16ToFloat(bytes.getShort(at+2))):Byte.toUnsignedInt(bytes.get(at+1));
                        int b=hdr?display(Float.float16ToFloat(bytes.getShort(at+4))):Byte.toUnsignedInt(bytes.get(at+2));
                        image.setRGB(x,y,r<<16|g<<8|b);
                    }
                    SwingUtilities.invokeLater(()->{if(running.get()){canvas.setIcon(new ImageIcon(image));status.setText("Hardware RT | native 640x360 | "+request.antiAliasing().samplesPerPixel()+" SPP | "+request.mediumTransport());}});
                }
            }catch(Exception failure){SwingUtilities.invokeLater(()->status.setText("Renderer stopped: "+failure));}
        });
    }
    private static SceneTransaction scene(long revision,State state) {
        var tx=SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).transmission(state.transmission).roughness(state.roughness).doubleSided(true).build());
        tx.upsert(MaterialAsset.builder(2).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorTextureId(1).doubleSided(true).build());
        byte[] pixels=new byte[32*32*4];
        for(int y=0;y<32;y++)for(int x=0;x<32;x++){
            int at=(y*32+x)*4;boolean light=((x/2+y/2)&1)==0;
            pixels[at]=(byte)(light?240:20);pixels[at+1]=(byte)(light?230:55);pixels[at+2]=(byte)(light?210:220);pixels[at+3]=(byte)255;
        }
        tx.upsert(TextureAsset.builder(1,32,32).pixelsRgba8(pixels).filter(TextureAsset.Filter.NEAREST).colorSpace(TextureAsset.ColorSpace.SRGB).build());
        var box=box(state.inward);tx.upsert(box);
        var boundary=MediumBoundaryState.builder(state.medium).priority(state.priority).surfaceInteraction(!state.volumeOnly)
            .orientation(state.inward?MediumBoundaryState.Orientation.INWARD:MediumBoundaryState.Orientation.OUTWARD).build();
        if(!state.frameLocal)tx.upsert(SceneInstance.builder(1,1).transform(scale(2.5f)).mediumBoundary(boundary).build());
        if(state.nested && !state.frameLocal)tx.upsert(SceneInstance.builder(2,1).transform(scale(1.25f))
            .mediumBoundary(MediumBoundaryState.builder(EnvironmentState.Medium.builder().absorption(.005f,.015f,.04f)
                .indexOfRefraction(1.52f).density(1).build()).priority(state.priority==Integer.MAX_VALUE?state.priority:state.priority+1)
                .orientation(state.inward?MediumBoundaryState.Orientation.INWARD:MediumBoundaryState.Orientation.OUTWARD).build()).build());
        tx.upsert(MeshAsset.builder(2,new float[]{-8,-5,-4,8,-5,-4,8,5,-4,-8,5,-4},new int[]{0,1,2,2,3,0},new long[]{2,2})
            .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build()).upsert(SceneInstance.builder(3,2).build());
        tx.upsert(SceneLight.point(1,0,2,0).color(1,.6f,.3f).intensity(35).range(15).build());
        tx.upsert(SceneLight.spot(2,-3,1,3,0,0,-1).color(.2f,.6f,1).coneDegrees(20,55).intensity(50).range(20).build());
        return tx.build();
    }
    private static FramePrimitiveBatch primitiveVolumes(State state) {
        if(!state.frameLocal) return FramePrimitiveBatch.empty();
        var orientation=state.inward?MediumBoundaryState.Orientation.INWARD:MediumBoundaryState.Orientation.OUTWARD;
        var batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).transform(scale(2.5f))
            .mediumBoundary(MediumBoundaryState.builder(state.medium).priority(state.priority).surfaceInteraction(!state.volumeOnly).orientation(orientation).build()).build());
        if(state.nested)batch.add(PrimitiveInstance.builder(1).transform(scale(1.25f))
            .mediumBoundary(MediumBoundaryState.builder(EnvironmentState.Medium.builder().absorption(.005f,.015f,.04f)
                .indexOfRefraction(1.52f).density(1).build()).priority(state.priority==Integer.MAX_VALUE?state.priority:state.priority+1).orientation(orientation).build()).build());
        return batch.build();
    }
    private static AffineTransform scale(float value){return new AffineTransform(new float[]{value,0,0,0,0,value,0,0,0,0,value,0});}
    private static MeshAsset box(boolean inward){
        int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        if(inward)for(int t=0;t<indices.length;t+=3){int swap=indices[t];indices[t]=indices[t+1];indices[t+1]=swap;}
        return MeshAsset.triangles(1,new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},indices,1);
    }
    private static int display(float value){return Math.clamp(Math.round((float)Math.pow(Math.max(0,value)/(1+Math.max(0,value)),1/2.2)*255),0,255);}
    private static void add(JPanel panel,Map<String,JTextField> fields,String name,String value){var field=new JTextField(value,16);fields.put(name,field);panel.add(new JLabel(name));panel.add(field);}
    private static float value(Map<String,JTextField> fields,String name){return Float.parseFloat(fields.get(name).getText().trim());}
    private static int integer(Map<String,JTextField> fields,String name){return Integer.parseInt(fields.get(name).getText().trim());}
    private static float[] rgb(Map<String,JTextField> fields,String name){var parts=fields.get(name).getText().split(",");if(parts.length!=3)throw new IllegalArgumentException(name+" requires three components");return new float[]{Float.parseFloat(parts[0]),Float.parseFloat(parts[1]),Float.parseFloat(parts[2])};}
}
