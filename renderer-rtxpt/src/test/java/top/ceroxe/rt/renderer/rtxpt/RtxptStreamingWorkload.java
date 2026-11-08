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
    private static final float FINITE_LEVEL=6,FINITE_HALF_EXTENT=.5f,FINITE_DYNAMIC_VISIBILITY_RADIUS=.12f;

    private record Config(int meshes,int sources,int ticks,int framesPerTick,int frames,int warmup,Path output,
            boolean textureUpdates,int textureSize,RtxptDiagnostics.Level diagnostics,String motionProfile,int stationaryFrames,int admissionBatches,boolean deformGeometry,int flowPerFrame,int materialCount,
            boolean exportRaw,boolean requireTarget,String sourceModel,int staticSources) {
        static Config parse(String[] args) {
            var values=new HashMap<String,String>();
            var allowed=Set.of("meshes","sources","ticks","frames-per-tick","frames","warmup-frames","output",
                    "texture-updates","texture-size","diagnostics","motion-profile","stationary-frames","admission-batches","deform-geometry","flow-per-frame","material-count","export-raw","require-target","source-model","static-sources");
            for(int i=0;i<args.length;i+=2) {
                if(!args[i].startsWith("--")||i+1==args.length||!allowed.contains(args[i].substring(2)))
                    throw new IllegalArgumentException("Expected --option value: "+args[i]);
                if(values.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Duplicate option: "+args[i]);
            }
            int meshes=positive(values,"meshes",3662),sources=positive(values,"sources",45000);
            String sourceModel=values.getOrDefault("source-model","physical-sum");
            if(!Set.of("physical-sum","finite-channel-max").contains(sourceModel))
                throw new IllegalArgumentException("source-model must be physical-sum or finite-channel-max");
            boolean finite=sourceModel.equals("finite-channel-max");
            if(!finite&&values.containsKey("static-sources"))
                throw new IllegalArgumentException("static-sources requires finite-channel-max");
            int staticSources=finite?Integer.parseInt(values.getOrDefault("static-sources",Integer.toString(Math.max(0,sources-150)))):0;
            if(staticSources<0||staticSources>sources)throw new IllegalArgumentException("static-sources must be in 0..sources");
            int ticks=positive(values,"ticks",300),perTick=positive(values,"frames-per-tick",9);
            int frames=positive(values,"frames",Math.multiplyExact(ticks,perTick));
            int warmup=Integer.parseInt(values.getOrDefault("warmup-frames","90"));
            String textures=values.getOrDefault("texture-updates","false");
            if(!textures.equals("true")&&!textures.equals("false"))throw new IllegalArgumentException("texture-updates must be true or false");
            boolean textureUpdates=Boolean.parseBoolean(textures);
            String deformation=values.getOrDefault("deform-geometry","false");
            if(!deformation.equals("true")&&!deformation.equals("false"))throw new IllegalArgumentException("deform-geometry must be true or false");
            int textureSize=positive(values,"texture-size",1024);
            if(textureSize<16||textureSize>4096)throw new IllegalArgumentException("texture-size must be 16..4096");
            var diagnostics=RtxptDiagnostics.Level.valueOf(values.getOrDefault("diagnostics","DEEP"));
            String motion=values.getOrDefault("motion-profile","legacy");
            if(!Set.of("legacy","medium","fast").contains(motion))throw new IllegalArgumentException("motion-profile must be legacy, medium or fast");
            int stationary=Integer.parseInt(values.getOrDefault("stationary-frames","0"));
            if(stationary<0)throw new IllegalArgumentException("stationary-frames must not be negative");
            int admissionBatches=positive(values,"admission-batches",1);
            int flow=Integer.parseInt(values.getOrDefault("flow-per-frame","0"));
            int materialCount=positive(values,"material-count",2);
            if(flow<0||flow>=meshes||materialCount<2||materialCount>256)
                throw new IllegalArgumentException("Invalid flow-per-frame or material-count");
            if(admissionBatches>meshes-1)throw new IllegalArgumentException("admission-batches must not exceed the terrain mesh count");
            if(meshes<2||sources>262144||warmup<0||frames>Math.multiplyExact(ticks,perTick))
                throw new IllegalArgumentException("Invalid workload extent or source capacity");
            return new Config(meshes,sources,ticks,perTick,frames,warmup,
                    Path.of(values.getOrDefault("output","build/reports/streaming-workload")).toAbsolutePath(),
                    textureUpdates,textureSize,diagnostics,motion,stationary,admissionBatches,Boolean.parseBoolean(deformation),flow,materialCount,
                    flag(values,"export-raw"),flag(values,"require-target"),sourceModel,staticSources);
        }
        private static boolean flag(Map<String,String> values,String name){
            String value=values.getOrDefault(name,"false");
            if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException(name+" must be true or false");
            return Boolean.parseBoolean(value);
        }
        private static int positive(Map<String,String> values,String name,int fallback) {
            int value=Integer.parseInt(values.getOrDefault(name,Integer.toString(fallback)));
            if(value<1)throw new IllegalArgumentException(name+" must be positive");return value;
        }
        boolean fullSpecification() {return meshes==3662&&sources==45000&&ticks==300&&framesPerTick==9&&frames==2700
                &&motionProfile.equals("legacy")&&stationaryFrames==0&&admissionBatches==1&&!textureUpdates&&!deformGeometry&&flowPerFrame==0&&materialCount==2&&diagnostics==RtxptDiagnostics.Level.DEEP&&sourceModel.equals("physical-sum");}
        Map<String,Object> sourceContract(){
            boolean finite=sourceModel.equals("finite-channel-max");
            return map("model",sourceModel,"shape",finite?"BOX":"POINT","composition",finite?"VISIBLE_GROUP_CHANNEL_MAX":"PHYSICAL_SUM",
                    "attenuation",finite?"MANHATTAN_LINEAR":"INVERSE_SQUARE_FINITE","group",0,"casts_shadow",true,
                    "static_sources",staticSources,"dynamic_sources",sources-staticSources,
                    "range",FINITE_LEVEL,"intensity",finite?FINITE_LEVEL/15:.35f,"falloff_exponent",finite?1:2,
                    "half_extent",finite?FINITE_HALF_EXTENT:0,"rgb",List.of(.7f,.5f,.3f),
                    "static_visibility",finite?"CENTER_BOX_SUPPORT_V1":null,
                    "dynamic_visibility",finite?"CENTER_SPHERE_SUPPORT_V1":"NEAREST_POINT",
                    "dynamic_visibility_radius",finite?FINITE_DYNAMIC_VISIBILITY_RADIUS:null,
                    "layer_distribution",finite?"authored fixture; default dynamic count matches the existing 150-source update batch, not a captured host distribution":"original all-dynamic fixture",
                    "host_response","HOST_RESPONSE_RATIO_V1; shared authored table .06 + .7*sky/15 + .24*local/15; baseline (.15,.8)");
        }
        double logicalSpeed() {return switch(motionProfile){case "medium"->16;case "fast"->48;default->DISTANCE*20/ticks;};}
        double distance() {return motionProfile.equals("legacy")?DISTANCE:logicalSpeed()*ticks/20;}
    }

    private record Position(double x,double z,double heading) { }
    private record NativeFrameIdentity(long sequence,long sceneRevision) { }
    private record Cell(int x,int z) { }
    private record Tile(long id,Cell cell) { }
    private record Step(SceneTransaction scene,List<SparseLightSource> sources,
            NativeLightingPublication attributes,RenderFrameRequest frame,int meshUpserts,int meshRemovals,
            int instanceUpserts,int instanceRemovals,int sceneTicks,int entityPoseUpserts,long preparationNanos) { }
    private record RawCapture(String phase,int phaseFrameIndex,long sequence,long revision,long preparationNanos,
            long applyBegin,long applyEnd,long attributesBegin,long attributesEnd,long submitBegin,long submitEnd,long freshEnd,
            long pollBegin,long pollEnd,int meshUpserts,int meshRemovals,int instanceUpserts,int instanceRemovals,int sourceUpserts,
            RtxptDiagnostics.Snapshot javaSnapshot,RtxptDiagnostics.NativeSnapshot nativeSnapshot) { }

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
    private static MeshAsset entityMesh() {return entityMesh(0);}
    private static MeshAsset entityMesh(float shear) {
        float[] p={-.5f,0,-.5f,.5f,0,-.5f,.5f,2,-.5f,-.5f,2,-.5f,
                -.5f,0,.5f,.5f,0,.5f,.5f,2,.5f,-.5f,2,.5f};
        int[] indices={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        float[] n=new float[p.length];for(int i=0;i<8;i++){n[i*3]=p[i*3];n[i*3+1]=p[i*3+1]-.5f;n[i*3+2]=p[i*3+2];}
        for(int i=0;i<8;i++){n[i*3+1]-=shear*.5f*n[i*3];p[i*3]+=shear*.5f*p[i*3+1];}
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
        final List<Geometry> layouts=new ArrayList<>();
        final Map<Cell,Tile> tiles=new LinkedHashMap<>();
        final int tileCount,columns,rows;long nextId=2,sequence,lastClock;Cell center;
        TextureAsset texture;long textureFullGenerations,texturePatches,textureSamplerChanges;
        long tileCenterChanges,xTileBoundaries,zTileBoundaries,deformationMeshUpserts;
        Position previous=new Position(0,0,0);double travelled;int sourceUpdates;
        World(Config config){
            this.config=config;tileCount=config.meshes()-1;columns=(int)Math.ceil(Math.sqrt(tileCount));rows=(tileCount+columns-1)/columns;
            if(config.materialCount()>2)for(int variant=0;variant<config.materialCount();variant++) {
                long[] materialIds=new long[prototype.triangles()];
                int groups=1+variant%3;
                for(int triangle=0;triangle<materialIds.length;triangle++)
                    materialIds[triangle]=1+(variant+(long)triangle*groups/materialIds.length)%config.materialCount();
                layouts.add(new Geometry(prototype.positions(),prototype.normals(),prototype.uv(),prototype.baseline(),
                        prototype.colors(),prototype.indices(),longs(materialIds)));
            }
        }
        private MeshAsset tileMesh(Tile tile) {
            return (layouts.isEmpty()?prototype:layouts.get((int)(tile.id()%layouts.size()))).mesh(tile.id());
        }
        private Cell cell(int index,Cell origin){return new Cell(origin.x()+index%columns-columns/2,origin.z()+index/columns-rows/2);}
        Step prepare(boolean initial,int movementFrame,boolean updateSources) {
            return prepare(initial,movementFrame,updateSources,tileCount,0,config.sources(),false);
        }
        Step prepareAdmission(int batch) {
            require(batch>=0&&batch<config.admissionBatches(),"Admission batch index is outside the configured range");
            int admitted=(int)((long)tileCount*(batch+1)/config.admissionBatches());
            int sourceBegin=(int)((long)config.sources()*batch/config.admissionBatches());
            int sourceEnd=(int)((long)config.sources()*(batch+1)/config.admissionBatches());
            return prepare(batch==0,-1,false,admitted,sourceBegin,sourceEnd,true);
        }
        private Step prepare(boolean initial,int movementFrame,boolean updateSources,int admittedTiles,int sourceBegin,int sourceEnd,boolean admission) {
            long begin=System.nanoTime();long identity=++sequence;
            double progress=movementFrame<0?0:(movementFrame+1.0)/(config.ticks()*config.framesPerTick());
            double angle=TURN*progress,radius=config.distance()/TURN;
            var position=new Position(radius*(1-Math.cos(angle)),radius*Math.sin(angle),angle);
            if(movementFrame>=0)travelled+=Math.hypot(position.x()-previous.x(),position.z()-previous.z());
            previous=position;
            var tx=SceneTransaction.builder(identity);var attributes=new ArrayList<NativeLightingPublication.Instance>();
            int added=0,removed=0,instanceUpserts=0,entityPoseUpserts=0;
            if(initial) {
                var material=MaterialAsset.builder(1).doubleSided(true).baseColorRgba8(0xffb0b0b0);
                if(config.textureUpdates()) {
                    byte[] pixels=new byte[Math.multiplyExact(Math.multiplyExact(config.textureSize(),config.textureSize()),4)];
                    Arrays.fill(pixels,(byte)-1);
                    texture=textureSnapshot(pixels,false);tx.upsert(texture);material.baseColorTextureId(7);
                }
                tx.resetScene().upsert(material.build())
                        .upsert(MaterialAsset.builder(2).doubleSided(true).baseColorRgba8(0xff80b0d0).build()).upsert(entity);
                for(int id=3;id<=config.materialCount();id++)
                    tx.upsert(MaterialAsset.builder(id).doubleSided(true).baseColorRgba8(0xff8090a0|((id*11&63)<<16)).build());
            } else if(config.textureUpdates()&&movementFrame>=0) {
                // The same immutable generation chain reaches both runtimes.
                // Full no-ops alternate with real 16x16 rectangles; every 32
                // frames a no-op slot changes only the public sampler policy.
                if((movementFrame&1)==0) {
                    byte[] pixels=new byte[texture.rgba8().remaining()];texture.rgba8().get(pixels);
                    boolean alternate=texture.filter()==TextureAsset.Filter.NEAREST;
                    if(movementFrame>0&&movementFrame%32==0) {
                        alternate=!alternate;textureSamplerChanges++;
                    } else textureFullGenerations++;
                    texture=textureSnapshot(pixels,alternate);
                } else {
                    byte[] rectangle=new byte[16*16*4];
                    for(int pixel=0;pixel<rectangle.length;pixel+=4) {
                        rectangle[pixel]=(byte)((movementFrame&2)==0?96:192);
                        rectangle[pixel+1]=(byte)192;rectangle[pixel+2]=(byte)224;rectangle[pixel+3]=(byte)-1;
                    }
                    // Every edit toggles the same authored rectangle, keeping
                    // each odd frame a true content change even at small sizes.
                    texture=texture.patched(new TexturePatch(7,0,0,0,16,16,64,ByteBuffer.wrap(rectangle)));texturePatches++;
                }
                tx.upsert(texture);
            }
            boolean deform=config.deformGeometry()&&!initial&&!admission;
            if(deform){
                // Stable identity/connectivity with fresh vertex positions on every frame.
                // Pose-only movement cannot expose scene-table rebuilds caused by animation.
                tx.upsert(entityMesh((float)(.3*Math.sin(identity*.037))));deformationMeshUpserts++;
            }
            var newCenter=new Cell((int)Math.floor(position.x()/TILE),(int)Math.floor(position.z()/TILE));
            if(!newCenter.equals(center)||tiles.size()<admittedTiles) {
                if(center!=null&&movementFrame>=0){tileCenterChanges++;xTileBoundaries+=Math.abs((long)newCenter.x()-center.x());zTileBoundaries+=Math.abs((long)newCenter.z()-center.z());}
                var desired=new LinkedHashSet<Cell>();for(int i=0;i<admittedTiles;i++)desired.add(cell(i,newCenter));
                var iterator=tiles.entrySet().iterator();while(iterator.hasNext()){
                    var entry=iterator.next();if(!desired.contains(entry.getKey())){
                        tx.removeInstance(entry.getValue().id()).removeMesh(entry.getValue().id());iterator.remove();removed++;
                    }
                }
                for(var cell:desired)if(!tiles.containsKey(cell)){
                    var tile=new Tile(nextId++,cell);tiles.put(cell,tile);
                    tx.upsert(tileMesh(tile)).upsert(SceneInstance.builder(tile.id(),tile.id())
                            .transform(translation(cell.x()*TILE,0,cell.z()*TILE)).build());
                    attributes.add(NativeLightingPublication.Instance.receiver(tile.id()));added++;instanceUpserts++;
                }
                center=newCenter;
            }
            if(movementFrame>=0&&config.flowPerFrame()>0) {
                // A streaming producer can publish fresh identities between spatial
                // boundary crossings. Exercise that real graph work every frame,
                // with mixed material-group layouts and exact resident accounting.
                var departing=new ArrayList<Tile>(config.flowPerFrame());
                var entries=tiles.entrySet().iterator();
                while(entries.hasNext()&&departing.size()<config.flowPerFrame()) {
                    var tile=entries.next().getValue();departing.add(tile);entries.remove();
                    tx.removeInstance(tile.id()).removeMesh(tile.id());removed++;
                }
                for(var old:departing) {
                    var tile=new Tile(nextId++,old.cell());tiles.put(tile.cell(),tile);
                    tx.upsert(tileMesh(tile)).upsert(SceneInstance.builder(tile.id(),tile.id())
                            .transform(translation(tile.cell().x()*TILE,0,tile.cell().z()*TILE)).build());
                    attributes.add(NativeLightingPublication.Instance.receiver(tile.id()));added++;instanceUpserts++;
                }
            }
            if(initial||!admission&&(updateSources||!config.motionProfile().equals("legacy")))for(int i=0;i<ENTITY_COUNT;i++){
                long id=ENTITY_FIRST+i;
                double phase=config.motionProfile().equals("legacy")
                        ?(movementFrame<0?0:movementFrame/(double)config.framesPerTick())*.11+i
                        :(identity-1)/(20.0*config.framesPerTick())*2.2+i;
                tx.upsert(SceneInstance.builder(id,1).transform(translation(position.x()+Math.sin(phase)*7,
                        1+Math.sin(phase*.7)*.3,position.z()+6+i*2)).build());instanceUpserts++;entityPoseUpserts++;
                if(initial)attributes.add(NativeLightingPublication.Instance.receiver(id));
            }
            var sources=new ArrayList<SparseLightSource>();
            if(initial||admission)for(int i=sourceBegin;i<sourceEnd;i++)sources.add(source(i,0,newCenter));
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
            return new Step(transaction,List.copyOf(sources),publication,frame,added+(initial||deform?1:0),removed,
                    instanceUpserts,removed,movementFrame>=0&&updateSources?1:0,entityPoseUpserts,System.nanoTime()-begin);
        }
        private SparseLightSource source(int index,int epoch,Cell origin) {
            int slot=(int)(Integer.toUnsignedLong(index*1664525+1013904223)%tileCount);var cell=cell(slot,origin);
            double x=cell.x()*TILE+1+(index%17)*.8,z=cell.z()*TILE+1+(index%19)*.7;
            double sourceX=x+Math.sin(epoch*.13+index)*.12;
            if(config.sourceModel().equals("physical-sum"))return SparseLightSource.point(index+1L,sourceX,2.5,z,
                    .7f,.5f,.3f,.35f,6).withLayer(SparseLightSource.Layer.DYNAMIC);
            boolean dynamic=index>=config.staticSources();
            // Range remains the authored six world units. The finite field's
            // level-to-energy contract is level/15, independent of composition.
            var source=new SparseLightSource(index+1L,SparseLightSource.Shape.BOX,sourceX,2.5,z,
                    FINITE_HALF_EXTENT,FINITE_HALF_EXTENT,FINITE_HALF_EXTENT,0,-1,0,.7f,.5f,.3f,
                    FINITE_LEVEL/15,FINITE_LEVEL,1,1,-1,SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0,true,
                    dynamic?SparseLightSource.Layer.DYNAMIC:SparseLightSource.Layer.STATIC,SparseLightSource.Attenuation.MANHATTAN_LINEAR);
            return dynamic?source.withVisibility(SparseLightSource.Visibility.CENTER_SPHERE_SUPPORT_V1,FINITE_DYNAMIC_VISIBILITY_RADIUS)
                    :source.withVisibility(SparseLightSource.Visibility.CENTER_BOX_SUPPORT_V1);
        }
        private TextureAsset textureSnapshot(byte[] pixels,boolean alternate) {
            return TextureAsset.builder(7,config.textureSize(),config.textureSize())
                    .addressMode(TextureAsset.AddressMode.REPEAT)
                    .filter(alternate?TextureAsset.Filter.NEAREST:TextureAsset.Filter.LINEAR).pixelsRgba8(pixels).build();
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
        final Samples preparation=new Samples(),apply=new Samples(),attributes=new Samples(),submit=new Samples(),fresh=new Samples(),poll=new Samples(),simulationFrame=new Samples();
        long simulationPhaseNanos;
        final Set<NativeFrameIdentity> completedFrameIds=new LinkedHashSet<>();
        final Map<String,Map<NativeFrameIdentity,Long>> nativeInclusiveFrames=new TreeMap<>(),nativeSelfFrames=new TreeMap<>(),nativeCpuFrames=new TreeMap<>(),nativeSelfCpuFrames=new TreeMap<>();
        final EnumMap<RtxptDiagnostics.Stage,Samples> boundary=new EnumMap<>(RtxptDiagnostics.Stage.class);
        final EnumMap<RtxptDiagnostics.Stage,Long> boundaryFailures=new EnumMap<>(RtxptDiagnostics.Stage.class);
        final Map<String,Samples> nativeInclusive=new TreeMap<>(),nativeSelf=new TreeMap<>(),nativeCpu=new TreeMap<>(),nativeSelfCpu=new TreeMap<>();
        final Map<String,long[]> nativeWork=new TreeMap<>();
        long nativeLostEvents,nativeFailedEvents,nativeIncompleteObservations,nativeCpuUnavailable,nativePolls;
        boolean nativeDiagnosticsDisabled;long[] resourceWorkBegin,resourceWorkEnd,commandWorkBegin,commandWorkEnd;String resourceWorkUnavailable,commandWorkUnavailable;
        long[] meshInputRetentionBegin,meshInputRetentionEnd;String meshInputRetentionUnavailable;
        static final String[] WORK_NAMES={"full_upload_count","full_upload_bytes","rectangle_upload_count","rectangle_upload_bytes","gpu_copy_bytes","identical_generation_count","sampler_only_count","resident_mesh_validation_visits","resident_mesh_validation_skipped"};
        static final String[] COMMAND_NAMES={"resource_command_lists_created","resource_command_lists_reused","structure_command_lists_created","structure_command_lists_reused","pose_only_transactions","pose_node_updates","empty_transactions","lean_native_lighting_frames","full_native_lighting_frame_fallbacks","empty_scene_native_lighting_frames"};
        private static Map<String,Object> nativeSummary(Map<String,Samples> source){
            var result=new LinkedHashMap<String,Object>();source.forEach((stage,samples)->result.put(stage,samples.summary()));return result;
        }
        Map<String,Object> nativeFrameSummary(Map<String,Map<NativeFrameIdentity,Long>> source,boolean cpu){
            var result=new LinkedHashMap<String,Object>();
            if(nativeDiagnosticsDisabled||nativePolls==0)return result;
            source.forEach((stage,frames)->{
                var samples=new Samples();long unmatched=0;
                for(var id:completedFrameIds){
                    // CPU UINT64_MAX means unavailable, not zero. A wall stage
                    // absent on a successfully sampled frame did no stage work.
                    boolean noStageWork=!nativeInclusiveFrames.getOrDefault(stage,Map.of()).containsKey(id);
                    if(!cpu||frames.containsKey(id)||noStageWork)samples.add(frames.getOrDefault(id,0L));
                }
                for(var id:frames.keySet())if(!completedFrameIds.contains(id))unmatched++;
                var values=new LinkedHashMap<>(samples.summary());values.put("unmatched_event_frame_identities",unmatched);
                values.put("aggregation","sum of same-stage events per native sequence/revision; different stages are not added");
                values.put("zero_work_frames_included",true);values.put("cpu_unavailable_work_frames_excluded",cpu);result.put(stage,values);
            });return result;
        }
        private static void frameValue(Map<String,Map<NativeFrameIdentity,Long>> destination,String stage,NativeFrameIdentity frame,long nanos){
            destination.computeIfAbsent(stage,key->new HashMap<>()).merge(frame,nanos,Math::addExact);
        }
        Map<String,Object> resourceSummary(){
            return workSummary(resourceWorkBegin,resourceWorkEnd,WORK_NAMES,resourceWorkUnavailable);
        }
        Map<String,Object> commandSummary(){
            return workSummary(commandWorkBegin,commandWorkEnd,COMMAND_NAMES,commandWorkUnavailable);
        }
        Map<String,Object> meshInputRetentionSummary(){
            String[] names={"current_retained_bytes","peak_retained_bytes","live_packets"};
            var gauges=new LinkedHashMap<String,Object>();
            for(int index=0;index<names.length;index++)gauges.put(names[index],map("begin",meshInputRetentionBegin==null?null:meshInputRetentionBegin[index],
                    "end",meshInputRetentionEnd==null?null:meshInputRetentionEnd[index]));
            boolean available=meshInputRetentionBegin!=null&&meshInputRetentionEnd!=null;
            return map("available",available,"reason",available?null:meshInputRetentionUnavailable==null?"phase-end-not-reached":meshInputRetentionUnavailable,
                    "gauges",gauges,"measurement","phase-boundary gauges; no accumulated-counter subtraction; owned backing packets counted once",
                    "peak_scope","native retention tracker lifetime; endpoint readings do not isolate a phase-local peak",
                    "memory_domain","retained owned input packet bytes; not process working set, commit, or GPU residency");
        }
        private static Map<String,Object> workSummary(long[] begin,long[] end,String[] names,String unavailable){
            if(begin==null||end==null)return map("available",false,"reason",unavailable==null?"phase-end-not-reached":unavailable);
            require(begin.length==end.length,"Native work packet width changed within a phase");
            var values=new LinkedHashMap<String,Object>();
            for(int index=0;index<names.length;index++){
                if(index>=begin.length){values.put(names[index],map("available",false,"reason","counter-not-exposed-by-this-runtime"));continue;}
                require(end[index]>=begin[index],"Native work counter moved backwards");
                values.put(names[index],map("available",true,"begin",begin[index],"end",end[index],"delta",end[index]-begin[index]));
            }
            return map("available",true,"counters",values,"measurement","native command work counters outside frame timing");
        }
        Map<String,Object> nativeWorkSummary(){
            var result=new TreeMap<String,Object>();nativeWork.forEach((stage,counts)->result.put(stage,map("events",counts[0],"items",counts[1],"bytes",counts[2],"cache_hit_events",counts[3],"cache_hit_items",counts[4],"tasks",counts[5])));return result;
        }
        void nativeEvents(RtxptDiagnostics.NativeSnapshot snapshot){
            nativePolls++;nativeLostEvents+=snapshot.lostEvents();
            require(snapshot.lostEvents()==0,"Native boundary diagnostic events were lost");
            for(var event:snapshot.events()){
                if((event.flags()&(2L|1024L))!=0)nativeFailedEvents++;
                require((event.flags()&512L)==0,"Native boundary diagnostic scope was truncated");
                if((event.flags()&256L)!=0){nativeIncompleteObservations++;continue;}
                var work=nativeWork.computeIfAbsent(event.stage(),key->new long[6]);work[0]++;work[1]+=event.items();work[2]+=event.bytes();work[5]+=event.tasks();
                if((event.flags()&4L)!=0){work[3]++;work[4]+=event.items();}
                var frame=new NativeFrameIdentity(event.sequence(),event.sceneRevision());
                nativeInclusive.computeIfAbsent(event.stage(),key->new Samples()).add(event.inclusiveNanos());
                nativeSelf.computeIfAbsent(event.stage(),key->new Samples()).add(event.selfNanos());
                frameValue(nativeInclusiveFrames,event.stage(),frame,event.inclusiveNanos());frameValue(nativeSelfFrames,event.stage(),frame,event.selfNanos());
                if(event.threadCpuNanos()>=0){nativeCpu.computeIfAbsent(event.stage(),key->new Samples()).add(event.threadCpuNanos());frameValue(nativeCpuFrames,event.stage(),frame,event.threadCpuNanos());}else nativeCpuUnavailable++;
                if(event.threadCpuSelfNanos()>=0){nativeSelfCpu.computeIfAbsent(event.stage(),key->new Samples()).add(event.threadCpuSelfNanos());frameValue(nativeSelfCpuFrames,event.stage(),frame,event.threadCpuSelfNanos());}
            }
            require(nativeFailedEvents==0,"A native boundary failed or was cancelled");
        }
        long frames,verifiedFrames,meshUpserts,meshRemovals,instanceUpserts,instanceRemovals,sourceUpserts,sceneTicks,entityPoseUpserts,failedEvents,lostEvents;
        void delta(Step step){meshUpserts+=step.meshUpserts();meshRemovals+=step.meshRemovals();instanceUpserts+=step.instanceUpserts();instanceRemovals+=step.instanceRemovals();sourceUpserts+=step.sources().size();sceneTicks+=step.sceneTicks();entityPoseUpserts+=step.entityPoseUpserts();}
        Map<String,Object> summary(){
            var stages=new LinkedHashMap<String,Object>();for(var stage:RtxptDiagnostics.Stage.values()){
                var values=new LinkedHashMap<>(boundary.getOrDefault(stage,new Samples()).summary());
                values.put("failed_events",boundaryFailures.getOrDefault(stage,0L));stages.put(stage.name(),values);
            }
            double rate=fresh.rate();
            return map("accepted_frames",frames,"verified_fresh_frames",verifiedFrames,"pipeline_frames_per_second",Double.isFinite(rate)?rate:null,"preparation_outside_timing",preparation.summary(),"apply_wall",apply.summary(),
                    "simulation_frames_per_second",simulationPhaseNanos>0?verifiedFrames*1e9/simulationPhaseNanos:null,
                    "simulation_phase_wall_ms",simulationPhaseNanos/1e6,"simulation_frame_wall",simulationFrame.summary(),
                    "simulation_timing_boundary","complete phase loop including input preparation, frame validation, diagnostic polls, resource counter reads and loop bookkeeping; renderer open, endpoint capture and close remain separate",
                    "attributes_wall",attributes.summary(),"submit_wall",submit.summary(),"apply_to_fresh_wall",fresh.summary(),
                    "diagnostic_poll_outside_timing",poll.summary(),"boundary_wall",stages,"failed_events",failedEvents,"lost_events",lostEvents,
                    "native_work_totals",nativeWorkSummary(),"native_boundary_inclusive_wall",nativeSummary(nativeInclusive),"native_boundary_self_wall",nativeSummary(nativeSelf),
                    "native_boundary_thread_cpu",nativeSummary(nativeCpu),"native_boundary_self_cpu",nativeSummary(nativeSelfCpu),
                    "native_frame_inclusive_wall",nativeFrameSummary(nativeInclusiveFrames,false),"native_frame_self_wall",nativeFrameSummary(nativeSelfFrames,false),
                    "native_frame_thread_cpu",nativeFrameSummary(nativeCpuFrames,true),"native_frame_self_cpu",nativeFrameSummary(nativeSelfCpuFrames,true),
                    "native_diagnostics",map("available",!nativeDiagnosticsDisabled&&nativePolls>0,"disabled",nativeDiagnosticsDisabled,
                            "polls",nativePolls,"lost_events",nativeLostEvents,"failed_events",nativeFailedEvents,"incomplete_scope_observations",nativeIncompleteObservations,
                            "thread_cpu_unavailable_events",nativeCpuUnavailable,"initial_frame","monitor-created-after-first-fresh-frame",
                            "frame_scope_boundary","JNI Frame wall includes owner-thread resource work and rendering waits; self wall excludes only attributed child scopes, is neither exclusive CPU nor GPU busy time, and must not be added to ResourcePlan",
                            "frame_aggregation","event sums grouped by native sequence/revision; stages and threads can overlap; per-event distributions remain separate",
                            "scope","adapter scopes and RTXPT call boundary only; upstream internals not expanded"),
                    "resource_work",resourceSummary(),"command_work",commandSummary(),"mesh_input_retention",meshInputRetentionSummary(),
                    "lost_event_stage_attribution","unavailable_for_overwritten_events",
                    "mesh_upserts",meshUpserts,"mesh_removals",meshRemovals,"instance_upserts",instanceUpserts,
                    "instance_removals",instanceRemovals,"source_upserts",sourceUpserts,"scene_ticks",sceneTicks,"entity_pose_upserts",entityPoseUpserts);
        }
    }
    private static final class Run {
        final Config config;final World world;final Map<String,Phase> phases=new LinkedHashMap<>();
        final List<RawCapture> rawCaptures=new ArrayList<>();boolean rawExported;String rawExportFailure;
        long cursor=-1,accepted,verified;Double openMillis;Long residentMeshes,residentInstances;
        boolean captured,completed,initialSceneApplied;int captureWidth,captureHeight;String failure;
        RtxptDiagnostics.NativeMonitor nativeMonitor;Phase activePhase;String resourceCounterUnsupported,commandCounterUnsupported,meshInputRetentionUnsupported;
        Run(Config config){this.config=config;world=new World(config);}
        void executeAdmission(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,SparseLightingControl sparse) throws Throwable {
            if(config.admissionBatches()==1){executePhase(renderer,access,lighting,sparse,"initial",1,true,false);return;}
            var phase=phases.computeIfAbsent("bootstrap",key->new Phase());long begin=System.nanoTime();
            try {
                for(int batch=0;batch<config.admissionBatches();batch++){
                    long frameBegin=System.nanoTime();
                    try {
                        execute(renderer,access,lighting,sparse,"bootstrap",world.prepareAdmission(batch));
                        long expectedTiles=(long)world.tileCount*(batch+1)/config.admissionBatches();
                        require(residentMeshes!=null&&residentMeshes==expectedTiles+1,"An admission batch lost resident meshes");
                        require(residentInstances!=null&&residentInstances==expectedTiles+ENTITY_COUNT,"An admission batch lost resident instances");
                        if(batch+1==config.admissionBatches())finishPhase(renderer);
                    } finally {phase.simulationFrame.add(System.nanoTime()-frameBegin);}
                }
                require(phase.meshUpserts==config.meshes()&&phase.meshRemovals==0&&phase.sourceUpserts==config.sources(),"Bootstrap did not admit the complete authored workload exactly once");
                require(phase.verifiedFrames==config.admissionBatches(),"A bootstrap batch has no verified fresh output");
            } finally {phase.simulationPhaseNanos+=System.nanoTime()-begin;}
        }
        void executePhase(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,SparseLightingControl sparse,
                String name,int count,boolean initial,boolean moving) throws Throwable {
            if(count==0)return;
            var phase=phases.computeIfAbsent(name,key->new Phase());long begin=System.nanoTime();
            try {
                for(int index=0;index<count;index++){
                    long frameBegin=System.nanoTime();
                    try {
                        execute(renderer,access,lighting,sparse,name,world.prepare(initial,moving?index:-1,moving&&index%config.framesPerTick()==0));
                        if(index+1==count)finishPhase(renderer);
                    } finally {phase.simulationFrame.add(System.nanoTime()-frameBegin);}
                }
            } finally {phase.simulationPhaseNanos+=System.nanoTime()-begin;}
        }
        long[] resourceWork(Renderer renderer) throws ReflectiveOperationException {
            if(resourceCounterUnsupported!=null)return null;
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            if(handle==0)return null;
            try {
                long[] values=RtxptNative.resourceWorkState(handle);
                require(values.length==7||values.length==9,"Invalid native resource work counter packet");return values;
            } catch(UnsatisfiedLinkError unavailable){resourceCounterUnsupported="native-runtime-method-unavailable";return null;}
        }
        long[] commandWork(Renderer renderer) throws ReflectiveOperationException {
            if(commandCounterUnsupported!=null)return null;
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            if(handle==0)return null;
            try {
                long[] values=RtxptNative.commandWorkState(handle);
                require(values.length==7||values.length==Phase.COMMAND_NAMES.length,"Invalid native command work counter packet");return values;
            } catch(UnsatisfiedLinkError unavailable){commandCounterUnsupported="native-runtime-method-unavailable";return null;}
        }
        long[] meshInputRetention(Renderer renderer) throws ReflectiveOperationException {
            if(meshInputRetentionUnsupported!=null)return null;
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            if(handle==0)return null;
            try {
                long[] values=RtxptNative.meshInputRetentionState(handle);
                require(values.length==3,"Invalid native input retention gauge packet");
                for(long value:values)require(value>=0,"Native input retention gauge is negative");
                return values;
            } catch(UnsatisfiedLinkError unavailable){meshInputRetentionUnsupported="native-runtime-method-unavailable";return null;}
        }
        void finishPhase(Renderer renderer) throws ReflectiveOperationException {
            if(activePhase==null)return;
            activePhase.resourceWorkEnd=resourceWork(renderer);
            if(resourceCounterUnsupported!=null)activePhase.resourceWorkUnavailable=resourceCounterUnsupported;
            activePhase.commandWorkEnd=commandWork(renderer);
            if(commandCounterUnsupported!=null)activePhase.commandWorkUnavailable=commandCounterUnsupported;
            activePhase.meshInputRetentionEnd=meshInputRetention(renderer);
            if(meshInputRetentionUnsupported!=null)activePhase.meshInputRetentionUnavailable=meshInputRetentionUnsupported;
            activePhase=null;
        }
        void execute(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,SparseLightingControl sparse,
                String phaseName,Step step) throws Throwable {
            var phase=phases.computeIfAbsent(phaseName,key->new Phase());phase.preparation.add(step.preparationNanos());
            if(activePhase!=phase){
                finishPhase(renderer);activePhase=phase;
                phase.nativeDiagnosticsDisabled=config.diagnostics()==RtxptDiagnostics.Level.OFF;
                phase.resourceWorkBegin=resourceWork(renderer);
                if(phase.resourceWorkBegin==null)phase.resourceWorkUnavailable=resourceCounterUnsupported==null?"renderer-not-initialized-at-phase-start":resourceCounterUnsupported;
                phase.commandWorkBegin=commandWork(renderer);
                if(phase.commandWorkBegin==null)phase.commandWorkUnavailable=commandCounterUnsupported==null?"renderer-not-initialized-at-phase-start":commandCounterUnsupported;
                phase.meshInputRetentionBegin=meshInputRetention(renderer);
                if(phase.meshInputRetentionBegin==null)phase.meshInputRetentionUnavailable=meshInputRetentionUnsupported==null?"renderer-not-initialized-at-phase-start":meshInputRetentionUnsupported;
            }
            Throwable problem=null;
            long begin=0,applyEnd=0,attributesBegin=0,attributesEnd=0,submitBegin=0,submitEnd=0,freshEnd=0;
            RtxptDiagnostics.Snapshot javaSnapshot=null;RtxptDiagnostics.NativeSnapshot nativeSnapshot=null;
            try {
                require(renderer.status()==Renderer.Status.READY,"Renderer is not ready");
                begin=System.nanoTime();
                sparse.apply(step.scene(),step.sources(),NO_REMOVALS);applyEnd=System.nanoTime();phase.apply.add(applyEnd-begin);phase.delta(step);
                if(step.scene().reset())initialSceneApplied=true;
                attributesBegin=System.nanoTime();lighting.publishLightingAttributes(step.attributes());attributesEnd=System.nanoTime();phase.attributes.add(attributesEnd-attributesBegin);
                submitBegin=System.nanoTime();var receipt=renderer.submit(step.frame());submitEnd=System.nanoTime();phase.submit.add(submitEnd-submitBegin);phase.frames++;accepted++;
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
                freshEnd=System.nanoTime();phase.fresh.add(freshEnd-begin);phase.verifiedFrames++;verified++;
                phase.completedFrameIds.add(new NativeFrameIdentity(step.frame().sequence(),step.scene().revision()));
                require(renderer.status()==Renderer.Status.READY,"Renderer closed during submission");
            } catch(Throwable failure) {problem=failure;throw failure;}
            finally {
                long pollStart=System.nanoTime();
                try {
                    var current=renderer.diagnostics();residentMeshes=current.residentMeshes();residentInstances=current.residentInstances();
                    var snapshot=access.diagnosticSnapshot(cursor);javaSnapshot=snapshot;phase.lostEvents+=snapshot.lostEvents();
                    require(!snapshot.upstreamInternalsExpanded(),"Unexpected private upstream diagnostics");
                    for(var event:snapshot.events()){
                        phase.boundary.computeIfAbsent(event.stage(),key->new Samples()).add(event.durationNanos());
                        if(event.failed()){phase.failedEvents++;phase.boundaryFailures.merge(event.stage(),1L,Long::sum);}
                    }
                    cursor=snapshot.nextOrdinal()-1;
                    require(snapshot.lostEvents()==0,"Boundary diagnostic events were lost");
                    require(phase.failedEvents==0,"A renderer boundary failed");
                    if(nativeMonitor==null){if(verified>0)nativeMonitor=access.nativeDiagnosticMonitor();}
                    else {nativeSnapshot=nativeMonitor.poll();phase.nativeEvents(nativeSnapshot);}
                } catch(Throwable captureFailure) {if(problem==null)throw captureFailure;problem.addSuppressed(captureFailure);}
                finally {
                    long pollEnd=System.nanoTime();phase.poll.add(pollEnd-pollStart);
                    // Only retain already collected values outside the existing
                    // frame/poll intervals. Disk export happens after the run.
                    if(config.exportRaw())rawCaptures.add(new RawCapture(phaseName,phase.simulationFrame.values.size(),
                            step.frame().sequence(),step.scene().revision(),step.preparationNanos(),begin,applyEnd,
                            attributesBegin,attributesEnd,submitBegin,submitEnd,freshEnd,pollStart,pollEnd,
                            step.meshUpserts(),step.meshRemovals(),step.instanceUpserts(),step.instanceRemovals(),step.sources().size(),javaSnapshot,nativeSnapshot));
                }
            }
        }
        void exportRaw() throws IOException {
            try(var frames=Files.newBufferedWriter(config.output().resolve("frames.csv"));
                    var javaEvents=Files.newBufferedWriter(config.output().resolve("java-events.csv"));
                    var nativeEvents=Files.newBufferedWriter(config.output().resolve("native-events.csv"))) {
                csvRow(frames,"phase","phase_frame_index","sequence","scene_revision","verified_fresh","preparation_ns",
                        "apply_begin_ns","apply_end_ns","attributes_begin_ns","attributes_end_ns","submit_begin_ns","submit_end_ns","fresh_end_ns",
                        "poll_begin_ns","poll_end_ns","simulation_frame_ns","mesh_upserts","mesh_removals","instance_upserts","instance_removals","source_upserts",
                        "java_lost_events","native_generation","native_next_ordinal","native_lost_events");
                csvRow(javaEvents,"phase","poll_sequence","poll_revision","ordinal","sequence","scene_revision","stage","begin_ns","duration_ns","items","bytes","failed");
                csvRow(nativeEvents,"phase","poll_sequence","poll_revision","generation","ordinal","sequence","scene_revision","thread_id","scope_id","parent_scope_id",
                        "stage","begin_ns","inclusive_ns","self_ns","thread_cpu_ns","thread_cpu_self_ns","items","bytes","flags");
                for(var raw:rawCaptures) {
                    var phase=phases.get(raw.phase());var simulation=phase.simulationFrame.values;
                    var javaSnapshot=raw.javaSnapshot();var nativeSnapshot=raw.nativeSnapshot();
                    csvRow(frames,raw.phase(),raw.phaseFrameIndex(),raw.sequence(),raw.revision(),raw.freshEnd()!=0,raw.preparationNanos(),
                            raw.applyBegin(),raw.applyEnd(),raw.attributesBegin(),raw.attributesEnd(),raw.submitBegin(),raw.submitEnd(),raw.freshEnd(),
                            raw.pollBegin(),raw.pollEnd(),raw.phaseFrameIndex()<simulation.size()?simulation.get(raw.phaseFrameIndex()):null,
                            raw.meshUpserts(),raw.meshRemovals(),raw.instanceUpserts(),raw.instanceRemovals(),raw.sourceUpserts(),
                            javaSnapshot==null?null:javaSnapshot.lostEvents(),nativeSnapshot==null?null:nativeSnapshot.generation(),
                            nativeSnapshot==null?null:nativeSnapshot.nextOrdinal(),nativeSnapshot==null?null:nativeSnapshot.lostEvents());
                    if(javaSnapshot!=null)for(var event:javaSnapshot.events())csvRow(javaEvents,raw.phase(),raw.sequence(),raw.revision(),event.ordinal(),event.sequence(),event.sceneRevision(),
                            event.stage(),event.beginNanos(),event.durationNanos(),event.items(),event.bytes(),event.failed());
                    if(nativeSnapshot!=null)for(var event:nativeSnapshot.events())csvRow(nativeEvents,raw.phase(),raw.sequence(),raw.revision(),nativeSnapshot.generation(),event.ordinal(),event.sequence(),event.sceneRevision(),
                            event.thread(),event.scopeId(),event.parentScopeId(),event.stage(),event.beginNanos(),event.inclusiveNanos(),event.selfNanos(),
                            event.threadCpuNanos(),event.threadCpuSelfNanos(),event.items(),event.bytes(),event.flags());
                }
            }
            Files.writeString(config.output().resolve("raw-manifest.json"),json(map("schema",1,"diagnostics",config.diagnostics().name(),"source_model",config.sourceModel(),"source_contract",config.sourceContract(),"frames",rawCaptures.size(),
                    "files",List.of("frames.csv","java-events.csv","native-events.csv"),"export_boundary","once after renderer close, outside every measured phase",
                    "retention_boundary","optional immutable references and primitive frame record retained after existing fresh/poll timing; simulation wall includes this optional retention",
                    "java_clock","System.nanoTime","native_clock","adapter native monotonic clock; not calibrated to the Java clock",
                    "join_key",List.of("sequence","scene_revision"),"native_event_key",List.of("generation","ordinal"),
                    "native_flags","raw incomplete observations retained; use scope flags and final event ordinal when reconstructing nesting",
                    "missing_timestamp",0,"gpu_snapshot",map("available",false,"reason","No existing supported GPU timestamp snapshot is exposed by RtxptAccess; no extra queries or upstream probes added"),
                    "off_contract","OFF remains OFF; only existing polls are retained, with empty event files when no events exist"))+"\n");
            rawExported=true;
        }
        private static void csvRow(java.io.Writer output,Object... fields) throws IOException {
            for(int index=0;index<fields.length;index++) {
                if(index!=0)output.write(',');String value=fields[index]==null?"":fields[index].toString();
                if(value.indexOf(',')>=0||value.indexOf('"')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0) {
                    output.write('"');output.write(value.replace("\"","\"\""));output.write('"');
                }else output.write(value);
            }
            output.write('\n');
        }
        Map<String,Object> summary(){
            var measurements=new LinkedHashMap<String,Object>();phases.forEach((name,value)->measurements.put(name,value.summary()));
            var allSubmit=new Samples();var allFresh=new Samples();
            phases.values().forEach(phase->{allSubmit.values.addAll(phase.submit.values);allFresh.values.addAll(phase.fresh.values);});
            var movement=phases.get("movement");double rate=movement==null?Double.NaN:movement.fresh.rate();
            double simulationRate=movement==null||movement.simulationPhaseNanos<=0?Double.NaN:movement.verifiedFrames*1e9/movement.simulationPhaseNanos;
            boolean pipelineTarget=Double.isFinite(rate)&&rate>=180,simulationTarget=Double.isFinite(simulationRate)&&simulationRate>=180;
            var stationary=phases.get("stationary");double stationaryRate=stationary==null?Double.NaN:stationary.fresh.rate();
            return map("schema",1,"success",completed&&failure==null&&rawExportFailure==null,"failure",failure,"formal_default_specification",config.fullSpecification(),
                    "source_model",config.sourceModel(),"source_contract",config.sourceContract(),
                    "configuration",map("resident_meshes",config.meshes(),"sparse_sources",config.sources(),"source_model",config.sourceModel(),"static_sources",config.staticSources(),"virtual_ticks",config.ticks(),
                            "render_frames_per_tick",config.framesPerTick(),"movement_frames",config.frames(),"warmup_frames",config.warmup(),"stationary_frames",config.stationaryFrames(),
                            "admission_batches",config.admissionBatches(),"admission_mode",config.admissionBatches()==1?"single-transaction":"progressive-fresh-output-per-batch",
                            "output_width",OUTPUT_W,"output_height",OUTPUT_H,"lighting_width",GRID_W,"lighting_height",GRID_H,"gi_samples",0,"reconstruction","NONE",
                            "texture_updates",config.textureUpdates(),"texture_size",config.textureSize(),"diagnostics",config.diagnostics().name(),
                            "continuous_geometry_deformation",config.deformGeometry(),
                            "flow_meshes_per_frame",config.flowPerFrame(),"authored_material_count",config.materialCount(),
                            "motion_profile",config.motionProfile(),"export_raw",config.exportRaw(),"require_target",config.requireTarget(),"scene_tick_hz",20,"logical_speed_units_per_second",config.logicalSpeed(),
                            "logical_movement_seconds",config.frames()/(20.0*config.framesPerTick()),"full_path_distance",config.distance()),
                    "actual",map("accepted_frames",accepted,"verified_fresh_frames",verified,"resident_meshes",residentMeshes,"resident_instances",residentInstances,
                            "unique_mesh_triangles",initialSceneApplied?world.triangles():null,"unique_mesh_vertices",initialSceneApplied?world.vertices():null,
                            "instanced_triangles",initialSceneApplied?world.instancedTriangles():null,
                            "accepted_input_sparse_sources",initialSceneApplied?config.sources():null,"source_delta_updates",movement==null?0:movement.sourceUpserts,
                            "planned_source_delta_updates",world.sourceUpdates,"retained_payload_bytes",world.payloadBytes(),
                            "texture_full_noop_generations",world.textureFullGenerations,"texture_rectangle_updates",world.texturePatches,
                            "texture_sampler_only_changes",world.textureSamplerChanges,"deformation_mesh_upserts",world.deformationMeshUpserts,
                            "planned_tile_center_changes",world.tileCenterChanges,"planned_x_tile_boundaries",world.xTileBoundaries,"planned_z_tile_boundaries",world.zTileBoundaries,
                            "movement_mesh_upserts_per_logical_second",movement==null?null:movement.meshUpserts/(config.frames()/(20.0*config.framesPerTick())),
                            "movement_mesh_removals_per_logical_second",movement==null?null:movement.meshRemovals/(config.frames()/(20.0*config.framesPerTick())),
                            "shared_input_prototype_bytes",world.prototype.bytes(),"travelled_units",world.travelled,"heading_degrees",Math.toDegrees(world.previous.heading())),
                    "renderer_open_ms",openMillis,"all_submit_wall",allSubmit.summary(),"all_apply_to_fresh_wall",allFresh.summary(),
                    "initial_upload",phases.containsKey("initial")?phases.get("initial").fresh.summary():null,
                    "stationary_pipeline_frames_per_second",Double.isFinite(stationaryRate)?stationaryRate:null,
                    "stationary_simulation_frames_per_second",stationary!=null&&stationary.simulationPhaseNanos>0?stationary.verifiedFrames*1e9/stationary.simulationPhaseNanos:null,
                    "movement_simulation_frames_per_second",Double.isFinite(simulationRate)?simulationRate:null,
                    "movement_pipeline_frames_per_second",Double.isFinite(rate)?rate:null,"target_pipeline_frames_per_second",180,
                    "target_simulation_frames_per_second",180,"meets_target_pipeline_rate",pipelineTarget,"meets_target_simulation_rate",simulationTarget,
                    "meets_target_rates",pipelineTarget&&simulationTarget,"target_gate_enabled",config.requireTarget(),
                    "target_gpu_qualification","external runner must qualify an idle known GPU; rates alone do not certify the target",
                    "raw_export",map("enabled",config.exportRaw(),"completed",rawExported,"failure",rawExportFailure,"manifest",rawExported?config.output().resolve("raw-manifest.json").toString():null),
                    "phases",measurements,"endpoint_capture",captured?config.output().resolve("endpoint.png").toString():null,
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
            access.diagnosticLevel(config.diagnostics());lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            run.executeAdmission(renderer,access,lighting,sparse);
            run.executePhase(renderer,access,lighting,sparse,"warmup",config.warmup(),false,false);
            run.executePhase(renderer,access,lighting,sparse,"stationary",config.stationaryFrames(),false,false);
            run.executePhase(renderer,access,lighting,sparse,"movement",config.frames(),false,true);
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
            if(config.frames()==config.ticks()*config.framesPerTick()){
                // The sampled trajectory sums chords, not the continuous arc.
                // At 60 steps its exact length is 708.987041646, not 709.
                // Keep the same tolerance and verify the unchanged full path.
                double expected=2*(config.distance()/TURN)*config.frames()*Math.sin(TURN/(2*config.frames()));
                require(Math.abs(run.world.travelled-expected)<.01,"Movement path was shortened");
            }
            require(run.accepted==run.verified,"An accepted frame has no verified fresh output");
            if(config.deformGeometry())require(run.world.deformationMeshUpserts==(long)config.warmup()+config.stationaryFrames()+config.frames(),
                    "Continuous geometry deformation did not reach every post-admission frame");
            if(config.requireTarget()){
                var movement=run.phases.get("movement");
                require(movement.fresh.rate()>=180,"Movement pipeline mean exceeds the explicit 180 Hz frame budget");
                require(movement.simulationPhaseNanos>0&&movement.verifiedFrames*1e9/movement.simulationPhaseNanos>=180,
                        "Complete movement simulation including preparation, diagnostics and loop work exceeds the explicit 180 Hz frame budget");
            }
            run.completed=true;
        } catch(Throwable failure) {run.failure=failure.getClass().getName()+": "+failure.getMessage();throw failure;}
        finally {
            IOException exportFailure=null;
            if(config.exportRaw())try {run.exportRaw();}
            catch(IOException failure) {exportFailure=failure;run.rawExportFailure=failure.getClass().getName()+": "+failure.getMessage();}
            String result=json(run.summary());Files.writeString(config.output().resolve("result.json"),result+"\n");
            System.out.println("STREAMING_WORKLOAD_RESULT "+result);
            if(exportFailure!=null&&run.failure==null)throw exportFailure;
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
