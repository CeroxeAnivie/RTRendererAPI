package top.ceroxe.rt.renderer.rtxpt;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Real retained-scene streaming through the public renderer and shared-output contracts. */
public final class RtxptStreamingWorkload {
    private static final int OUTPUT_W=2560,OUTPUT_H=1600,GRID_W=1280,GRID_H=800;
    private static final int QUADS_X=18,QUADS_Z=25,ENTITY_COUNT=8;
    private static final double TILE=16,DISTANCE=709,TURN=Math.toRadians(72);
    private static final long ENTITY_FIRST=1_000_000_000L;
    private static final LongBuffer NO_REMOVALS=LongBuffer.allocate(0).asReadOnlyBuffer();

    private record Config(int meshes,int sources,int ticks,int framesPerTick,int frames,int warmup,Path output) {
        static Config parse(String[] args) {
            var values=new HashMap<String,String>();
            var allowed=Set.of("meshes","sources","ticks","frames-per-tick","frames","warmup-frames","output");
            for(int i=0;i<args.length;i+=2) {
                if(!args[i].startsWith("--")||i+1==args.length||!allowed.contains(args[i].substring(2)))
                    throw new IllegalArgumentException("Expected --option value: "+args[i]);
                if(values.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Duplicate option: "+args[i]);
            }
            int meshes=positive(values,"meshes",3662),sources=positive(values,"sources",45000);
            int ticks=positive(values,"ticks",300),perTick=positive(values,"frames-per-tick",9);
            int frames=positive(values,"frames",Math.multiplyExact(ticks,perTick));
            int warmup=Integer.parseInt(values.getOrDefault("warmup-frames","90"));
            if(meshes<2||sources>262144||warmup<0||frames>Math.multiplyExact(ticks,perTick))
                throw new IllegalArgumentException("Invalid workload extent or source capacity");
            return new Config(meshes,sources,ticks,perTick,frames,warmup,
                    Path.of(values.getOrDefault("output","build/reports/streaming-workload")).toAbsolutePath());
        }
        private static int positive(Map<String,String> values,String name,int fallback) {
            int value=Integer.parseInt(values.getOrDefault(name,Integer.toString(fallback)));
            if(value<1)throw new IllegalArgumentException(name+" must be positive");return value;
        }
        boolean fullSpecification() {return meshes==3662&&sources==45000&&ticks==300&&framesPerTick==9&&frames==2700;}
    }

    private record Position(double x,double z,double heading) { }
    private record Cell(int x,int z) { }
    private record Tile(long id,Cell cell) { }
    private record Step(SceneTransaction scene,List<SparseLightSource> sources,
            NativeLightingPublication attributes,RenderFrameRequest frame,int meshUpserts,int meshRemovals,
            int instanceUpserts,int instanceRemovals,long preparationNanos) { }

    /** Read-only direct buffers are shared only on the input side; every mesh has a distinct identity. */
    private record Geometry(FloatBuffer positions,FloatBuffer normals,FloatBuffer uv,FloatBuffer baseline,
            IntBuffer colors,IntBuffer indices,LongBuffer materials) {
        MeshAsset mesh(long id) {
            return MeshAsset.wrapImmutableDirect(id,positions,normals,emptyFloats(),uv,baseline,colors,indices,materials);
        }
        long bytes() {return 4L*(positions.remaining()+normals.remaining()+uv.remaining()+baseline.remaining()
                +colors.remaining()+indices.remaining())+8L*materials.remaining();}
        int vertices(){return positions.remaining()/3;}
        int triangles(){return indices.remaining()/3;}
    }
    private static Geometry terrain() {
        int vertices=QUADS_X*QUADS_Z*4;
        float[] p=new float[vertices*3],n=new float[p.length],uv=new float[vertices*2],baseline=new float[uv.length];
        int[] colors=new int[vertices],indices=new int[QUADS_X*QUADS_Z*6];long[] materials=new long[indices.length/3];
        int vertex=0,index=0;
        for(int z=0;z<QUADS_Z;z++)for(int x=0;x<QUADS_X;x++) {
            double x0=x*TILE/QUADS_X,x1=(x+1)*TILE/QUADS_X,z0=z*TILE/QUADS_Z,z1=(z+1)*TILE/QUADS_Z;
            double[][] corners={{x0,height(x0,z0),z0},{x0,height(x0,z1),z1},{x1,height(x1,z1),z1},{x1,height(x1,z0),z0}};
            for(int corner=0;corner<4;corner++) {
                int v=vertex+corner;double[] a=corners[corner];
                p[v*3]=(float)a[0];p[v*3+1]=(float)a[1];p[v*3+2]=(float)a[2];n[v*3+1]=1;
                uv[v*2]=(float)(a[0]/TILE);uv[v*2+1]=(float)(a[2]/TILE);
                baseline[v*2]=.15f;baseline[v*2+1]=.8f;colors[v]=0xffa0b080;
            }
            for(int offset:new int[]{0,1,2,0,2,3})indices[index++]=vertex+offset;
            vertex+=4;
        }
        Arrays.fill(materials,1);
        return new Geometry(floats(p),floats(n),floats(uv),floats(baseline),ints(colors),ints(indices),longs(materials));
    }
    private static double height(double x,double z){return .65*Math.sin(x*Math.PI/8)*Math.cos(z*Math.PI/8);}
    private static MeshAsset entityMesh() {
        float[] p={-.5f,0,-.5f,.5f,0,-.5f,.5f,2,-.5f,-.5f,2,-.5f,
                -.5f,0,.5f,.5f,0,.5f,.5f,2,.5f,-.5f,2,.5f};
        int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        float[] n=new float[p.length];for(int i=0;i<8;i++){n[i*3]=p[i*3];n[i*3+1]=p[i*3+1]-.5f;n[i*3+2]=p[i*3+2];}
        long[] materials=new long[12];Arrays.fill(materials,2);
        return MeshAsset.builder(1,p,indices,materials).normals(n).build();
    }
    private static FloatBuffer floats(float[] values){var b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b.asReadOnlyBuffer();}
    private static IntBuffer ints(int[] values){var b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asIntBuffer();b.put(values).flip();return b.asReadOnlyBuffer();}
    private static LongBuffer longs(long[] values){var b=ByteBuffer.allocateDirect(values.length*8).order(ByteOrder.nativeOrder()).asLongBuffer();b.put(values).flip();return b.asReadOnlyBuffer();}
    private static FloatBuffer emptyFloats(){return FloatBuffer.allocate(0).asReadOnlyBuffer();}
    private static AffineTransform translation(double x,double y,double z){return new AffineTransform(new float[]{1,0,0,(float)x,0,1,0,(float)y,0,0,1,(float)z});}

    private static final class World {
        final Config config;final Geometry prototype=terrain();final MeshAsset entity=entityMesh();
        final Map<Cell,Tile> tiles=new LinkedHashMap<>();
        final int tileCount,columns,rows;long nextId=2,sequence,lastClock;Cell center;
        Position previous=new Position(0,0,0);double travelled;int sourceUpdates;
        World(Config config){this.config=config;tileCount=config.meshes()-1;columns=(int)Math.ceil(Math.sqrt(tileCount));rows=(tileCount+columns-1)/columns;}
        private Cell cell(int index,Cell origin){return new Cell(origin.x()+index%columns-columns/2,origin.z()+index/columns-rows/2);}
        Step prepare(boolean initial,int movementFrame,boolean updateSources) {
            long begin=System.nanoTime();long identity=++sequence;
            double progress=movementFrame<0?0:(movementFrame+1.0)/(config.ticks()*config.framesPerTick());
            double angle=TURN*progress,radius=DISTANCE/TURN;
            var position=new Position(radius*(1-Math.cos(angle)),radius*Math.sin(angle),angle);
            if(movementFrame>=0)travelled+=Math.hypot(position.x()-previous.x(),position.z()-previous.z());
            previous=position;
            var tx=SceneTransaction.builder(identity);var attributes=new ArrayList<NativeLightingPublication.Instance>();
            int added=0,removed=0,instanceUpserts=0;
            if(initial)tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).baseColorRgba8(0xffb0b0b0).build())
                    .upsert(MaterialAsset.builder(2).doubleSided(true).baseColorRgba8(0xff80b0d0).build()).upsert(entity);
            var newCenter=new Cell((int)Math.floor(position.x()/TILE),(int)Math.floor(position.z()/TILE));
            if(!newCenter.equals(center)) {
                var desired=new LinkedHashSet<Cell>();for(int i=0;i<tileCount;i++)desired.add(cell(i,newCenter));
                var iterator=tiles.entrySet().iterator();while(iterator.hasNext()){
                    var entry=iterator.next();if(!desired.contains(entry.getKey())){
                        tx.removeInstance(entry.getValue().id()).removeMesh(entry.getValue().id());iterator.remove();removed++;
                    }
                }
                for(var cell:desired)if(!tiles.containsKey(cell)){
                    var tile=new Tile(nextId++,cell);tiles.put(cell,tile);
                    tx.upsert(prototype.mesh(tile.id())).upsert(SceneInstance.builder(tile.id(),tile.id())
                            .transform(translation(cell.x()*TILE,0,cell.z()*TILE)).build());
                    attributes.add(NativeLightingPublication.Instance.receiver(tile.id()));added++;instanceUpserts++;
                }
                center=newCenter;
            }
            if(initial||updateSources)for(int i=0;i<ENTITY_COUNT;i++){
                long id=ENTITY_FIRST+i;double phase=(movementFrame<0?0:movementFrame/(double)config.framesPerTick())*.11+i;
                tx.upsert(SceneInstance.builder(id,1).transform(translation(position.x()+Math.sin(phase)*7,
                        1+Math.sin(phase*.7)*.3,position.z()+6+i*2)).build());instanceUpserts++;
                if(initial)attributes.add(NativeLightingPublication.Instance.receiver(id));
            }
            var sources=new ArrayList<SparseLightSource>();
            if(initial)for(int i=0;i<config.sources();i++)sources.add(source(i,0,newCenter));
            else if(updateSources){
                int count=Math.min(150,config.sources());int tick=movementFrame/config.framesPerTick();
                for(int i=0;i<count;i++)sources.add(source((int)(((long)tick*150+i)%config.sources()),tick+1,newCenter));
                sourceUpdates+=count;
            }
            long clock=Math.multiplyExact(identity-1,1_000_000_000L)/(20L*config.framesPerTick());
            var camera=CameraState.lookAt(position.x(),12,position.z(),position.x()+32*Math.sin(angle),6,
                    position.z()+32*Math.cos(angle)).verticalFieldOfViewDegrees(65).aspectRatio((double)OUTPUT_W/OUTPUT_H).build();
            var frame=RenderFrameRequest.builder(identity,OUTPUT_W,OUTPUT_H,camera).minimumSceneRevision(identity)
                    .environment(EnvironmentState.builder().skyRadiance(.35f,.45f,.6f).ambientIntensity(1)
                            .sunDirection(0,-1,0).sunIntensity(.6f).build())
                    .clock(FrameClock.of(clock,identity==1?0:clock-lastClock)).build();lastClock=clock;
            var publication=new NativeLightingPublication(identity,attributes,List.of(),null,null,initial?response():null);
            var transaction=tx.build();
            return new Step(transaction,List.copyOf(sources),publication,frame,added+(initial?1:0),removed,
                    instanceUpserts,removed,System.nanoTime()-begin);
        }
        private SparseLightSource source(int index,int epoch,Cell origin) {
            int slot=(int)(Integer.toUnsignedLong(index*1664525+1013904223)%tileCount);var cell=cell(slot,origin);
            double x=cell.x()*TILE+1+(index%17)*.8,z=cell.z()*TILE+1+(index%19)*.7;
            return SparseLightSource.point(index+1L,x+Math.sin(epoch*.13+index)*.12,2.5,z,
                    .7f,.5f,.3f,.35f,6).withLayer(SparseLightSource.Layer.DYNAMIC);
        }
        long triangles(){return (long)tiles.size()*prototype.triangles()+entity.triangleCount();}
        long vertices(){return (long)tiles.size()*prototype.vertices()+entity.vertexCount();}
        long payloadBytes(){return tiles.size()*prototype.bytes()+RendererResourceOptions.meshBytes(entity);}
        long instancedTriangles(){return triangles()+(ENTITY_COUNT-1L)*entity.triangleCount();}
    }
    private static NativeLightingHostResponse response(){
        float[] values=new float[NativeLightingHostResponse.SAMPLES];
        for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++)for(int channel=0;channel<3;channel++)
            values[(sky*16+local)*3+channel]=.06f+.7f*sky/15+.24f*local/15;
        return new NativeLightingHostResponse(values);
    }

    private static final class Samples {
        final List<Long> values=new ArrayList<>();
        void add(long value){if(value<0)throw new IllegalArgumentException("Negative duration");values.add(value);}
        Map<String,Object> summary(){
            if(values.isEmpty())return map("samples",0,"measured",false);
            long[] sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();double total=0;for(long value:sorted)total+=value;
            return map("samples",sorted.length,"mean_ms",total/sorted.length/1e6,"p50_ms",quantile(sorted,.5)/1e6,
                    "p95_ms",quantile(sorted,.95)/1e6,"p99_ms",quantile(sorted,.99)/1e6,"max_ms",sorted[sorted.length-1]/1e6);
        }
        double rate(){double total=0;for(long value:values)total+=value;return values.isEmpty()?Double.NaN:values.size()*1e9/total;}
        private static long quantile(long[] sorted,double q){return sorted[(int)Math.ceil(sorted.length*q)-1];}
    }
    private static final class Phase {
        final Samples preparation=new Samples(),apply=new Samples(),attributes=new Samples(),submit=new Samples(),fresh=new Samples(),poll=new Samples();
        final EnumMap<RtxptDiagnostics.Stage,Samples> boundary=new EnumMap<>(RtxptDiagnostics.Stage.class);
        final EnumMap<RtxptDiagnostics.Stage,Long> boundaryFailures=new EnumMap<>(RtxptDiagnostics.Stage.class);
        long frames,verifiedFrames,meshUpserts,meshRemovals,instanceUpserts,instanceRemovals,sourceUpserts,failedEvents,lostEvents;
        void delta(Step step){meshUpserts+=step.meshUpserts();meshRemovals+=step.meshRemovals();instanceUpserts+=step.instanceUpserts();instanceRemovals+=step.instanceRemovals();sourceUpserts+=step.sources().size();}
        Map<String,Object> summary(){
            var stages=new LinkedHashMap<String,Object>();for(var stage:RtxptDiagnostics.Stage.values()){
                var values=new LinkedHashMap<>(boundary.getOrDefault(stage,new Samples()).summary());
                values.put("failed_events",boundaryFailures.getOrDefault(stage,0L));stages.put(stage.name(),values);
            }
            return map("accepted_frames",frames,"verified_fresh_frames",verifiedFrames,"preparation_outside_timing",preparation.summary(),"apply_wall",apply.summary(),
                    "attributes_wall",attributes.summary(),"submit_wall",submit.summary(),"apply_to_fresh_wall",fresh.summary(),
                    "diagnostic_poll_outside_timing",poll.summary(),"boundary_wall",stages,"failed_events",failedEvents,"lost_events",lostEvents,
                    "lost_event_stage_attribution","unavailable_for_overwritten_events",
                    "mesh_upserts",meshUpserts,"mesh_removals",meshRemovals,"instance_upserts",instanceUpserts,
                    "instance_removals",instanceRemovals,"source_upserts",sourceUpserts);
        }
    }
    private static final class Run {
        final Config config;final World world;final Map<String,Phase> phases=new LinkedHashMap<>();
        long cursor=-1,accepted,verified;Double openMillis;Long residentMeshes,residentInstances;
        boolean captured,completed,initialSceneApplied;int captureWidth,captureHeight;String failure;
        Run(Config config){this.config=config;world=new World(config);}
        void execute(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,SparseLightingControl sparse,
                String phaseName,Step step) throws Throwable {
            var phase=phases.computeIfAbsent(phaseName,key->new Phase());phase.preparation.add(step.preparationNanos());
            Throwable problem=null;
            try {
                require(renderer.status()==Renderer.Status.READY,"Renderer is not ready");
                long begin=System.nanoTime();long part=begin;
                sparse.apply(step.scene(),step.sources(),NO_REMOVALS);phase.apply.add(System.nanoTime()-part);phase.delta(step);
                if(step.scene().reset())initialSceneApplied=true;
                part=System.nanoTime();lighting.publishLightingAttributes(step.attributes());phase.attributes.add(System.nanoTime()-part);
                part=System.nanoTime();var receipt=renderer.submit(step.frame());phase.submit.add(System.nanoTime()-part);phase.frames++;accepted++;
                require(receipt.frameSequence()==step.frame().sequence()&&receipt.scheduledSceneRevision()==step.scene().revision(),"Submission receipt identity changed");
                try(var lease=lighting.acquireLightingFrame().orElseThrow(()->new IllegalStateException("No current shared output"))) {
                    var frame=lease.frame();long identity=step.frame().sequence();
                    require(frame.sequence()==identity&&frame.sceneRevision()==step.scene().revision(),"Stale frame identity");
                    require(frame.attributeGeneration()==step.attributes().generation()&&frame.clock().equals(step.frame().clock()),"Stale attribute generation or virtual clock");
                    require(frame.width()==GRID_W&&frame.height()==GRID_H&&frame.planes().size()==1,"Output extent or signal count changed");
                    require(frame.encoding()==NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8,"Output encoding changed");
                    require(frame.fenceHandle()!=0&&frame.readyValue()>0&&frame.completionValue()>frame.readyValue(),"Invalid shared synchronization contract");
                    var plane=frame.planes().getFirst();
                    require(plane.signal()==NativeLightingPlan.Signal.DIRECT_GAIN&&plane.format()==LightingFrameLease.Format.RGBA8_UNORM
                            &&plane.memoryHandle()!=0&&plane.allocationBytes()>=GRID_W*(long)GRID_H*4,"Invalid direct output plane");
                    // Never imported: close explicitly abandons this real packet through the producer contract.
                }
                phase.fresh.add(System.nanoTime()-begin);phase.verifiedFrames++;verified++;
                require(renderer.status()==Renderer.Status.READY,"Renderer closed during submission");
            } catch(Throwable failure) {problem=failure;throw failure;}
            finally {
                long pollStart=System.nanoTime();
                try {
                    var current=renderer.diagnostics();residentMeshes=current.residentMeshes();residentInstances=current.residentInstances();
                    var snapshot=access.diagnosticSnapshot(cursor);phase.lostEvents+=snapshot.lostEvents();
                    require(!snapshot.upstreamInternalsExpanded(),"Unexpected private upstream diagnostics");
                    for(var event:snapshot.events()){
                        phase.boundary.computeIfAbsent(event.stage(),key->new Samples()).add(event.durationNanos());
                        if(event.failed()){phase.failedEvents++;phase.boundaryFailures.merge(event.stage(),1L,Long::sum);}
                    }
                    cursor=snapshot.nextOrdinal()-1;
                    require(snapshot.lostEvents()==0,"Boundary diagnostic events were lost");
                    require(phase.failedEvents==0,"A renderer boundary failed");
                } catch(Throwable captureFailure) {if(problem==null)throw captureFailure;problem.addSuppressed(captureFailure);}
                finally {phase.poll.add(System.nanoTime()-pollStart);}
            }
        }
        Map<String,Object> summary(){
            var measurements=new LinkedHashMap<String,Object>();phases.forEach((name,value)->measurements.put(name,value.summary()));
            var allSubmit=new Samples();var allFresh=new Samples();
            phases.values().forEach(phase->{allSubmit.values.addAll(phase.submit.values);allFresh.values.addAll(phase.fresh.values);});
            var movement=phases.get("movement");double rate=movement==null?Double.NaN:movement.fresh.rate();
            return map("schema",1,"success",completed&&failure==null,"failure",failure,"formal_default_specification",config.fullSpecification(),
                    "configuration",map("resident_meshes",config.meshes(),"sparse_sources",config.sources(),"virtual_ticks",config.ticks(),
                            "render_frames_per_tick",config.framesPerTick(),"movement_frames",config.frames(),"warmup_frames",config.warmup(),
                            "output_width",OUTPUT_W,"output_height",OUTPUT_H,"lighting_width",GRID_W,"lighting_height",GRID_H,"gi_samples",0,"reconstruction","NONE"),
                    "actual",map("accepted_frames",accepted,"verified_fresh_frames",verified,"resident_meshes",residentMeshes,"resident_instances",residentInstances,
                            "unique_mesh_triangles",initialSceneApplied?world.triangles():null,"unique_mesh_vertices",initialSceneApplied?world.vertices():null,
                            "instanced_triangles",initialSceneApplied?world.instancedTriangles():null,
                            "accepted_input_sparse_sources",initialSceneApplied?config.sources():null,"source_delta_updates",movement==null?0:movement.sourceUpserts,
                            "planned_source_delta_updates",world.sourceUpdates,"retained_payload_bytes",world.payloadBytes(),
                            "shared_input_prototype_bytes",world.prototype.bytes(),"travelled_units",world.travelled,"heading_degrees",Math.toDegrees(world.previous.heading())),
                    "renderer_open_ms",openMillis,"all_submit_wall",allSubmit.summary(),"all_apply_to_fresh_wall",allFresh.summary(),
                    "initial_upload",phases.containsKey("initial")?phases.get("initial").fresh.summary():null,
                    "movement_pipeline_frames_per_second",Double.isFinite(rate)?rate:null,"target_pipeline_frames_per_second",180,
                    "meets_target_pipeline_rate",Double.isFinite(rate)&&rate>=180,"phases",measurements,"endpoint_capture",captured?config.output().resolve("endpoint.png").toString():null,
                    "endpoint_capture_signal","public_display_color_not_host_composite_or_direct_gain_readback",
                    "endpoint_capture_width",captured?captureWidth:null,"endpoint_capture_height",captured?captureHeight:null,
                    "limits",List.of("Wall intervals include waits and are not GPU busy time","Input preparation and diagnostic polling are reported separately",
                            "All resident instances keep receiver and shadow roles","Input prototypes share immutable storage; native mesh identities remain distinct",
                            "A synthetic renderer pipeline rate is not a host application FPS result","GPU baseline and process-tree memory budget are supplied by the external runner"));
        }
    }

    public static void main(String[] args) throws Throwable {
        var config=Config.parse(args);Files.createDirectories(config.output());var run=new Run(config);
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,0,0,0,0,1,1,1,0,255,1);
        var plan=new NativeLightingPlan(GRID_W,GRID_H,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,style)
                .withEncoding(NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8);
        var resources=RendererResourceOptions.high().withAssetBytes(1024L<<20,256L<<20,1280L<<20);
        var rendererConfig=RtxptRenderers.nativeLightingConfiguration().copyBuilder().resources(resources).build();
        long opened=System.nanoTime();
        try(var renderer=RtxptRenderers.openNativeLighting(rendererConfig,plan)) {
            run.openMillis=(System.nanoTime()-opened)/1e6;
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            var sparse=renderer.extension(SparseLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            run.execute(renderer,access,lighting,sparse,"initial",run.world.prepare(true,-1,false));
            for(int index=0;index<config.warmup();index++)run.execute(renderer,access,lighting,sparse,"warmup",run.world.prepare(false,-1,false));
            for(int index=0;index<config.frames();index++)
                run.execute(renderer,access,lighting,sparse,"movement",run.world.prepare(false,index,index%config.framesPerTick()==0));
            var diagnostics=renderer.diagnostics();
            require(diagnostics.residentMeshes()==config.meshes()&&diagnostics.residentInstances()==run.world.tiles.size()+ENTITY_COUNT,"Resident workload was reduced");
            var execution=lighting.nativeLightingExecution();
            require(execution.active()&&execution.actualSignalMask()==1&&execution.fullBeautyFrames()==0,"Unexpected or inactive execution route");
            require(execution.attributeGeneration()==run.world.sequence,"Final attributes were not applied");
            var reconstruction=lighting.lightingReconstruction();
            require(reconstruction.actual()==NativeLightingReconstruction.NONE&&reconstruction.evaluatedFrames()==0,"Reconstruction was enabled");
            access.awaitIdle();var capture=config.output().resolve("endpoint.png");access.capture(capture);
            require(Files.isRegularFile(capture)&&Files.size(capture)>8,"Endpoint capture is missing");
            var image=ImageIO.read(capture.toFile());require(image!=null&&image.getWidth()>0&&image.getHeight()>0,"Endpoint capture is invalid");
            run.captured=true;run.captureWidth=image.getWidth();run.captureHeight=image.getHeight();
            if(config.frames()==config.ticks()*config.framesPerTick())require(Math.abs(run.world.travelled-DISTANCE)<.01,"Movement path was shortened");
            require(run.accepted==run.verified,"An accepted frame has no verified fresh output");
            if(config.fullSpecification())require(run.phases.get("movement").fresh.rate()>=180,"Formal pipeline mean exceeds the 180 Hz frame budget");
            run.completed=true;
        } catch(Throwable failure) {run.failure=failure.getClass().getName()+": "+failure.getMessage();throw failure;}
        finally {
            String result=json(run.summary());Files.writeString(config.output().resolve("result.json"),result+"\n");
            System.out.println("STREAMING_WORKLOAD_RESULT "+result);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static Map<String,Object> map(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static String json(Object value){
        if(value==null)return "null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> values){var rows=new ArrayList<String>();values.forEach((key,item)->rows.add(json(key.toString())+":"+json(item)));return "{"+String.join(",",rows)+"}";}
        if(value instanceof Iterable<?> values){var rows=new ArrayList<String>();for(var item:values)rows.add(json(item));return "["+String.join(",",rows)+"]";}
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }
}
