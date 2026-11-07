package top.ceroxe.rt.renderer.rtxpt;

import java.io.*;
import java.lang.reflect.RecordComponent;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.api.*;

/** Logical streaming replay: counts are observed; geometry, identities and interval timing are synthetic. */
public final class RtxptStreamingSceneLoadTest {
    private static final int TEXTURES=16,MATERIALS=16,TEXTURE_EDGE=64;
    private static final long TIMEOUT=60_000_000_000L;
    private static final String SEMANTICS="Logical resource-count replay; not original geometry or source identity reconstruction. Source interval deltas use synthetic evenly spaced patch times. Only legal mesh/instance removals implied by resident-count decreases are replayed; the remainder of aggregate removals is unclassified input evidence and is not synthesized. Observed source bytes/index-node updates are input evidence, not synthetic/native upload bytes.";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static int number(Map<String,Object> map,String key){return Math.toIntExact(((Number)map.get(key)).longValue());}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> array(Object value){return (List<Object>)value;}
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void json(Path path,Object value)throws IOException{Files.writeString(path,RtxptJson.encode(value)+"\n",StandardCharsets.UTF_8);}
    private static Object record(Object value){
        if(value==null||value instanceof Number||value instanceof Boolean||value instanceof String)return value;
        if(value.getClass().isRecord()){var result=new LinkedHashMap<String,Object>();try{for(RecordComponent c:value.getClass().getRecordComponents())result.put(c.getName(),record(c.getAccessor().invoke(value)));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}return result;}
        return value.toString();
    }
    private static Map<String,Object> distribution(List<Long> input){
        long[] a=input.stream().mapToLong(Long::longValue).sorted().toArray();
        if(a.length==0)return map("samples",0,"meanMs",null,"p50Ms",null,"p95Ms",null,"p99Ms",null,"maxMs",null);
        return map("samples",a.length,"meanMs",Arrays.stream(a).average().orElseThrow()/1e6,"p50Ms",a[(int)Math.ceil(a.length*.50)-1]/1e6,"p95Ms",a[(int)Math.ceil(a.length*.95)-1]/1e6,"p99Ms",a[(int)Math.ceil(a.length*.99)-1]/1e6,"maxMs",a[a.length-1]/1e6);
    }
    private static final class Metrics {
        final Map<String,List<Long>> values=new TreeMap<>();
        synchronized void add(String name,long nanos){values.computeIfAbsent(name,k->new ArrayList<>()).add(nanos);}
        synchronized Object summary(){var result=new LinkedHashMap<String,Object>();values.forEach((k,v)->result.put(k,distribution(v)));return result;}
    }
    private record Spec(int vertices,int triangles,boolean normals,boolean tangents,int deformation,int attributes){}
    private record SourceAction(int additions,int updates,int removals){}
    private record Batch(SceneTransaction transaction,List<SparseLightSource> sources,long[] removedSources,long logicalAssetBytes,int meshes,int textures,int materials,int instances,int removals,int sourcePatches,int sourceUpserts,int sourceRemovals,long generationNanos){}

    private static final class Profile {
        final Map<String,Object> data;final List<Object> curves,observations;
        final Map<Integer,SourceAction> actions=new TreeMap<>();final Map<Integer,Map<String,Object>> observationsAt=new TreeMap<>();
        final int width,height,gridWidth,gridHeight,terminalMeshes,terminalVertices,terminalTriangles,initialSources;
        final long aggregateRemovals;final String sha256;
        Profile(Path path)throws Exception{
            byte[] bytes=Files.readAllBytes(path);sha256=hash(bytes);data=object(new Json(new String(bytes,StandardCharsets.UTF_8)).read());
            check("streaming-scene-load-v1".equals(data.get("schema")),"Unsupported load profile");
            curves=array(data.get("curves"));observations=array(data.get("source_observations"));
            var extent=array(data.get("output_extent"));var grid=array(data.get("lighting_extent"));
            width=((Number)extent.get(0)).intValue();height=((Number)extent.get(1)).intValue();gridWidth=((Number)grid.get(0)).intValue();gridHeight=((Number)grid.get(1)).intValue();
            var terminal=object(data.get("terminal_converged"));terminalMeshes=number(terminal,"meshes");terminalVertices=number(terminal,"vertices");terminalTriangles=number(terminal,"triangles");
            check(!curves.isEmpty()&&!observations.isEmpty(),"A curve and source observations are required");
            initialSources=number(object(observations.get(0)),"sources");long removalSum=0;int resident=number(object(curves.get(0)),"resident_meshes");
            for(int i=0;i<curves.size();i++){var c=object(curves.get(i));var u=object(c.get("updates"));check(number(c,"step")==i,"Contiguous curve steps");int next=number(c,"resident_meshes"),count=number(u,"mesh_upserts");
                check(Math.max(0,next-resident)<=count,"Mesh growth must fit upserts at step "+i);check(2*Math.max(0,resident-next)<=number(u,"removals"),"Resident decreases need legal removal operations at step "+i);
                check(number(u,"light_upserts")==0,"This profile declares sparse sources separately");
                check(count==0?number(u,"mesh_vertices")==0&&number(u,"mesh_triangles")==0:number(u,"mesh_vertices")>=3*count&&number(u,"mesh_triangles")>=count,"Geometry partition must fit every upsert");
                check(number(u,"normal_meshes")<=count&&number(u,"tangent_meshes")<=count,"Attribute counts must fit mesh upserts");
                check(number(u,"instance_upserts")<=next,"Instance upserts need distinct resident identities");
                removalSum+=number(u,"removals");resident=next;
            }aggregateRemovals=removalSum;
            for(int i=0;i<observations.size();i++){var observation=object(observations.get(i));int to=number(observation,"step");observationsAt.put(to,observation);check(number(observation,"index_nodes")==2*number(observation,"sources")-1,"Observed source index shape");if(i==0)continue;
                var previous=object(observations.get(i-1));var d=object(observation.get("deltas_since_previous_observation"));int from=number(previous,"step"),patches=number(d,"source_patches"),upserts=number(d,"source_upserts"),removals=number(d,"source_removals");
                int additions=number(observation,"sources")-number(previous,"sources")+removals,updates=upserts-additions;
                check(patches>0&&patches<=to-from&&additions>=0&&updates>=0,"Source interval admits a legal synthetic schedule");
                for(int p=0;p<patches;p++){int step=from+(int)Math.ceil((p+1.0)*(to-from)/patches);int add=part(additions,patches,p),update=part(updates,patches,p),remove=part(removals,patches,p);
                    check(add+update+remove>0,"Each synthetic source patch contains an operation");check(actions.put(step,new SourceAction(add,update,remove))==null,"Source patch times are distinct");}
            }
        }
    }
    private static int part(int total,int count,int index){return (int)(((long)(index+1)*total)/count-((long)index*total)/count);}

    private static final class World {
        final Profile profile;final LinkedHashMap<Long,Spec> meshes=new LinkedHashMap<>();final Set<Long> instances=new HashSet<>();final LinkedHashSet<Long> sourceIds=new LinkedHashSet<>();
        long nextMesh=1,nextSource=1,revision;int meshCursor,instanceCursor;
        World(Profile profile){this.profile=profile;}
        Batch seed(int count,int vertices,int triangles,int sources){
            long start=System.nanoTime(),bytes=0;var tx=SceneTransaction.builder(++revision).resetScene();
            for(int i=1;i<=TEXTURES;i++){var texture=texture(i,0);tx.upsert(texture);bytes+=4L*TEXTURE_EDGE*TEXTURE_EDGE;}
            for(int i=1;i<=MATERIALS;i++)tx.upsert(material(i,0));
            for(int i=0;i<count;i++){long id=nextMesh++;var spec=new Spec(part(vertices,count,i),part(triangles,count,i),true,true,0,0);meshes.put(id,spec);var mesh=mesh(id,spec);tx.upsert(mesh).upsert(instance(id,0));instances.add(id);bytes+=RendererResourceOptions.meshBytes(mesh);}
            var lights=new ArrayList<SparseLightSource>(sources);for(int i=0;i<sources;i++){long id=nextSource++;sourceIds.add(id);lights.add(source(id,0));}
            return new Batch(tx.build(),lights,new long[0],bytes,count,TEXTURES,MATERIALS,count,0,sources==0?0:1,sources,0,System.nanoTime()-start);
        }
        Batch curve(int step){
            long start=System.nanoTime(),bytes=0;var c=object(profile.curves.get(step));var u=object(c.get("updates"));var tx=SceneTransaction.builder(++revision);
            int target=number(c,"resident_meshes"),m=number(u,"mesh_upserts"),removed=0;
            while(meshes.size()>target){long id=meshes.keySet().iterator().next();meshes.remove(id);tx.removeMesh(id);removed++;if(instances.remove(id)){tx.removeInstance(id);removed++;}}
            var changed=new ArrayList<Long>(m);while(meshes.size()<target){long id=nextMesh++;meshes.put(id,new Spec(3,1,false,false,0,0));changed.add(id);}
            var candidates=new ArrayList<>(meshes.keySet());var selected=new HashSet<>(changed);
            for(int attempt=0;changed.size()<m;attempt++){long id=candidates.get(Math.floorMod(meshCursor++,candidates.size()));if(selected.add(id))changed.add(id);check(attempt<=candidates.size()+m,"Distinct mesh upsert identities");}
            for(int i=0;i<m;i++){long id=changed.get(i);var spec=new Spec(part(number(u,"mesh_vertices"),m,i),part(number(u,"mesh_triangles"),m,i),i<number(u,"normal_meshes"),i<number(u,"tangent_meshes"),step+1,step+1);meshes.put(id,spec);var mesh=mesh(id,spec);tx.upsert(mesh);bytes+=RendererResourceOptions.meshBytes(mesh);}
            int textures=number(u,"texture_upserts"),materials=number(u,"material_upserts"),instanceCount=number(u,"instance_upserts");
            check(textures<=TEXTURES&&materials<=MATERIALS,"Distinct canonical resource slots");
            for(int i=0;i<textures;i++){tx.upsert(texture(i+1,step+1));bytes+=4L*TEXTURE_EDGE*TEXTURE_EDGE;}
            for(int i=0;i<materials;i++)tx.upsert(material(i+1,step+1));
            var instanceTargets=new LinkedHashSet<Long>();for(long id:changed)if(!instances.contains(id)&&instanceTargets.size()<instanceCount)instanceTargets.add(id);
            while(instanceTargets.size()<instanceCount)instanceTargets.add(candidates.get(Math.floorMod(instanceCursor++,candidates.size())));
            for(long id:instanceTargets){tx.upsert(instance(id,step+1));instances.add(id);}
            check(removed<=number(u,"removals"),"Legal resident removals do not exceed the aggregate observation");
            var lights=new ArrayList<SparseLightSource>();long[] removedSources=new long[0];var action=profile.actions.get(step);
            if(action!=null){removedSources=new long[action.removals()];for(int i=0;i<removedSources.length;i++){check(!sourceIds.isEmpty(),"Source removals only target resident identities");long id=sourceIds.iterator().next();sourceIds.remove(id);removedSources[i]=id;}
                var existing=new ArrayList<>(sourceIds);check(action.updates()<=existing.size(),"Source updates use distinct surviving identities");for(int i=0;i<action.updates();i++)lights.add(source(existing.get(i),step+1));
                for(int i=0;i<action.additions();i++){long id=nextSource++;sourceIds.add(id);lights.add(source(id,step+1));}
                check(new HashSet<>(lights.stream().map(SparseLightSource::id).toList()).size()==lights.size(),"Source additions and updates must be disjoint");
            }
            var observation=profile.observationsAt.get(step);if(observation!=null)check(sourceIds.size()==number(observation,"sources"),"Exact source observation at step "+step);
            return new Batch(tx.build(),lights,removedSources,bytes,m,textures,materials,instanceCount,removed,action==null?0:1,lights.size(),removedSources.length,System.nanoTime()-start);
        }
        Batch convergence(){
            long start=System.nanoTime(),bytes=0;var tx=SceneTransaction.builder(++revision);while(meshes.size()<profile.terminalMeshes)meshes.put(nextMesh++,new Spec(3,1,true,true,0,0));check(meshes.size()==profile.terminalMeshes,"Terminal convergence is a separate growth target");
            var ids=new ArrayList<>(meshes.keySet());for(int i=0;i<ids.size();i++){long id=ids.get(i);var spec=new Spec(part(profile.terminalVertices,ids.size(),i),part(profile.terminalTriangles,ids.size(),i),true,true,0,0);meshes.put(id,spec);var mesh=mesh(id,spec);tx.upsert(mesh).upsert(instance(id,0));instances.add(id);bytes+=RendererResourceOptions.meshBytes(mesh);}
            return new Batch(tx.build(),List.of(),new long[0],bytes,ids.size(),0,0,ids.size(),0,0,0,0,System.nanoTime()-start);
        }
        Batch modify(boolean attributes,int step){
            long start=System.nanoTime(),bytes=0;var tx=SceneTransaction.builder(++revision);int count=Math.min(64,meshes.size()),i=0;
            for(var entry:meshes.entrySet()){if(i++>=count)break;var old=entry.getValue();var spec=new Spec(old.vertices(),old.triangles(),true,true,attributes?old.deformation():step+1,attributes?step+1:old.attributes());entry.setValue(spec);var mesh=mesh(entry.getKey(),spec);tx.upsert(mesh);bytes+=RendererResourceOptions.meshBytes(mesh);}
            return new Batch(tx.build(),List.of(),new long[0],bytes,count,0,0,0,0,0,0,0,System.nanoTime()-start);
        }
        Batch unchanged(){return new Batch(null,List.of(),new long[0],0,0,0,0,0,0,0,0,0,0);}
        Map<String,Object> resident(){long vertices=0,triangles=0;for(var spec:meshes.values()){vertices+=spec.vertices();triangles+=spec.triangles();}return map("meshes",meshes.size(),"instances",instances.size(),"vertices",vertices,"triangles",triangles,"sources",sourceIds.size());}
    }
    private static MaterialAsset material(long id,int step){return MaterialAsset.builder(id).baseColorTextureId((id-1)%TEXTURES+1).baseColorRgba8(0xff8090a0^((step&65535)<<8)).roughness(.7f+(step%3)*.05f).build();}
    private static TextureAsset texture(long id,int step){byte[] rgba=new byte[4*TEXTURE_EDGE*TEXTURE_EDGE];for(int i=0;i<rgba.length;i+=4){rgba[i]=(byte)(80+((i/4+step+(int)id)%100));rgba[i+1]=(byte)150;rgba[i+2]=(byte)110;rgba[i+3]=(byte)255;}rgba[0]=(byte)step;rgba[1]=(byte)(step>>>8);return TextureAsset.color(id,TEXTURE_EDGE,TEXTURE_EDGE,rgba);}
    private static MeshAsset mesh(long id,Spec spec){
        int n=spec.vertices(),t=spec.triangles();float[] positions=new float[3*n],uv=new float[2*n],baseline=new float[2*n],normals=spec.normals()?new float[3*n]:new float[0],tangents=spec.tangents()?new float[4*n]:new float[0];int[] colors=new int[n],indices=new int[3*t];long[] materials=new long[t];
        int edge=(int)Math.ceil(Math.sqrt(n));for(int v=0;v<n;v++){float x=(v%edge)/(float)Math.max(1,edge-1),z=(v/edge)/(float)Math.max(1,edge-1);positions[3*v]=1.6f*x;positions[3*v+1]=(float)(Math.sin(v*.31)*.03+Math.sin(spec.deformation()*.07+v*.3)*.01);positions[3*v+2]=1.6f*z;uv[2*v]=x+(spec.attributes()%7)*.003f;uv[2*v+1]=z;baseline[2*v]=baseline[2*v+1]=.5f;colors[v]=0xffa0b0c0;if(normals.length>0){normals[3*v]=(spec.attributes()%5)*.01f;normals[3*v+1]=(float)Math.sqrt(1-normals[3*v]*normals[3*v]);}if(tangents.length>0){tangents[4*v]=1;tangents[4*v+3]=1;}}
        for(int i=0;i<t;i++){int first=(i*2)%n;indices[3*i]=first;indices[3*i+1]=(first+1)%n;indices[3*i+2]=(first+edge)%n;if(indices[3*i+2]==first||indices[3*i+2]==indices[3*i+1])indices[3*i+2]=(first+2)%n;materials[i]=1;}
        return MeshAsset.builder(id,positions,indices,materials).normals(normals).tangents(tangents).textureCoordinates(uv).lightmapCoordinates(baseline).vertexColorsRgba8(colors).build();
    }
    private static SceneInstance instance(long id,int step){float x=((id-1)%128)*2,z=((id-1)/128%128)*2;return SceneInstance.builder(id,id).transform(new AffineTransform(new float[]{1,0,0,x+(step%5)*.002f,0,1,0,0,0,0,1,z})).build();}
    private static SparseLightSource source(long id,int step){return SparseLightSource.point(id,(id%128)*2+.5,1.5+(id%3)*.25+step*.0001,(id/128%128)*2+.5,.4f,.6f,.25f,.4f,6).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0).withAttenuation(SparseLightSource.Attenuation.MANHATTAN_LINEAR);}

    private static final class CpuCollector implements AutoCloseable {
        final RtxptDiagnostics.Monitor monitor;final AtomicBoolean stop=new AtomicBoolean();final AtomicReference<Throwable> failure=new AtomicReference<>();final Thread worker;final Metrics metrics=new Metrics();
        final Map<Long,String> phases;final BufferedWriter output;long nanos,events,failed,truncated,overwritten,missed,droppedActive,missedSubmissions;int finalActive;
        CpuCollector(RtxptRenderer renderer,Path path,Map<Long,String> phases)throws IOException{this.phases=phases;monitor=renderer.diagnosticMonitor();output=Files.newBufferedWriter(path,StandardCharsets.UTF_8);output.write("phase,sequence,revision,ordinal,stage,inclusive_nanos,self_nanos,thread,thread_cpu_nanos,flags,items,bytes,reason,resource_identity,resource_generation,scope_id,parent_scope_id\n");worker=new Thread(()->{try{while(!stop.get()){poll();Thread.sleep(2);}poll();}catch(Throwable t){failure.set(t);}},"streaming-diagnostic-writer");worker.setDaemon(true);worker.start();}
        void poll()throws IOException{long begin=System.nanoTime();var snapshot=monitor.poll();overwritten=snapshot.overwrittenEvents();missed=snapshot.missedSinceRegistration();droppedActive=snapshot.droppedActiveScopes();missedSubmissions=monitor.missedSubmissions();finalActive=snapshot.activeScopes().size();for(var e:snapshot.events()){String phase=phases.getOrDefault(e.sequence(),"unassigned");output.write(phase+","+e.sequence()+","+e.revision()+","+e.ordinal()+","+e.stage()+","+e.inclusiveNanos()+","+e.selfNanos()+","+e.thread()+","+e.threadCpuNanos()+","+e.flags()+","+e.items()+","+e.bytes()+","+e.reason()+","+e.resourceIdentity()+","+e.resourceGeneration()+","+e.scopeId()+","+e.parentScopeId()+"\n");events++;if(e.failed())failed++;if(e.truncated())truncated++;if(e.completed()){metrics.add(phase+"/"+e.stage()+"/inclusive",e.inclusiveNanos());metrics.add(phase+"/"+e.stage()+"/self",e.selfNanos());}}nanos+=System.nanoTime()-begin;}
        Object status(){return map("events",events,"failedEvents",failed,"truncatedEvents",truncated,"overwrittenRingEvents",overwritten,"missedSinceRegistration",missed,"missedSubmissions",missedSubmissions,"droppedActiveScopes",droppedActive,"finalActiveScopes",finalActive,"observerWorkNanos",nanos,"observerFailure",failure.get()==null?null:failure.get().toString(),"completedStageDistributions",metrics.summary());}
        public void close()throws Exception{stop.set(true);worker.join(30_000);check(!worker.isAlive(),"Diagnostic writer must terminate before renderer close");output.close();if(failure.get()!=null)throw new IllegalStateException("Diagnostic writer failed",failure.get());}
    }
    private static final class Harness implements AutoCloseable {
        final Profile profile;final Path output;final BufferedWriter frames,gpu,resources;final Map<Long,String> phases=new ConcurrentHashMap<>();final Metrics metrics=new Metrics(),gpuMetrics=new Metrics();final List<Object> sessions=new ArrayList<>(),captures=new ArrayList<>();long sequence,gpuScopes,gpuPollNanos,gpuDropped,gpuOverwritten,gpuPending,gpuMissed,identityChecks;
        Harness(Profile profile,Path output)throws IOException{this.profile=profile;this.output=output;frames=Files.newBufferedWriter(output.resolve("frames.csv"),StandardCharsets.UTF_8);gpu=Files.newBufferedWriter(output.resolve("gpu-scopes.csv"),StandardCharsets.UTF_8);resources=Files.newBufferedWriter(output.resolve("resources.jsonl"),StandardCharsets.UTF_8);frames.write("session,phase,step,sequence,revision,owner_apply_to_fresh_nanos,apply_nanos,submit_active_nanos,admission_wait_nanos,acquire_nanos,release_nanos,generation_nanos,deferrals,resident_meshes,resident_vertices,resident_triangles,resident_sources,mesh_upserts,texture_upserts,material_upserts,instance_upserts,replayed_mesh_instance_removals,observed_aggregate_removals,unclassified_aggregate_removals,source_patches,source_upserts,source_removals,synthetic_asset_bytes,native_source_record_bytes,native_source_index_bytes,diagnostic_poll_nanos\n");gpu.write("phase,native_frame,sequence,revision,queue,event,parent,name,inclusive_millis,begin_tick,end_tick,frequency,resource_identity,resource_generation,reason\n");}
        void pollGpu(RtxptDiagnostics.GpuMonitor monitor)throws IOException{long begin=System.nanoTime();var snapshot=monitor.poll();gpuDropped=snapshot.droppedQueries();gpuOverwritten=snapshot.overwrittenScopes();gpuPending=snapshot.pendingQueries();gpuMissed=monitor.missedScopes();for(var s:snapshot.scopes()){String phase=phases.getOrDefault(s.sequence(),"unassigned");gpu.write(phase+","+s.nativeFrame()+","+s.sequence()+","+s.revision()+","+s.queue()+","+s.event()+","+s.parent()+",\""+s.name().replace("\"","\"\"")+"\","+s.inclusiveMillis()+","+s.beginTick()+","+s.endTick()+","+s.frequency()+","+s.resourceIdentity()+","+s.resourceGeneration()+","+s.reason()+"\n");gpuMetrics.add(phase+"/"+s.name(),Math.round(s.inclusiveMillis()*1e6));gpuScopes++;}gpuPollNanos+=System.nanoTime()-begin;}
        LightingFrameLease frame(RtxptRenderer renderer,RtxptDiagnostics.GpuMonitor monitor,World world,Batch batch,String session,String phase,int step,boolean retain,boolean deferAllowed)throws Exception{
            phases.put(sequence,phase);long beforeRecords=renderer.sparseLightingStatus().uploadedRecordBytes(),beforeIndex=renderer.sparseLightingStatus().uploadedIndexBytes();
            var request=RenderFrameRequest.builder(sequence,profile.width,profile.height,CameraState.lookAt(116,52,154,70,0,70).verticalFieldOfViewDegrees(52).aspectRatio((double)profile.width/profile.height).build()).minimumSceneRevision(world.revision).clock(FrameClock.of(sequence*16_666_667L,16_666_667L)).environment(EnvironmentState.neutral()).build();
            long start=System.nanoTime();if(batch.transaction()!=null)renderer.apply(batch.transaction(),batch.sources(),LongBuffer.wrap(batch.removedSources()));
            if(phase.equals("cold")||phase.equals("reopen-cold")){float[] values=new float[NativeLightingHostResponse.SAMPLES];for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++)for(int c=0;c<3;c++)values[(sky*16+local)*3+c]=1/255f+.9f*local/15f+.05f*sky/15f;renderer.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(),null,null,new NativeLightingHostResponse(values)));}
            long applied=System.nanoTime();long submitActive=0;int deferrals=0;
            while(true){long submitStart=System.nanoTime();var result=renderer.trySubmit(request);submitActive+=System.nanoTime()-submitStart;if(result instanceof Renderer.FrameSubmitted)break;check(result instanceof Renderer.FrameSubmissionDeferred,"Only explicit deferral may be retried");if(deferAllowed)return null;check(System.nanoTime()-start<TIMEOUT,"Fresh frame admission timeout at sequence "+sequence);deferrals++;Thread.sleep(1);}
            long admitted=System.nanoTime();var lease=renderer.acquireLightingFrame().orElseThrow();long fresh=System.nanoTime();
            check(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==world.revision,"Fresh lease must preserve sequence and revision");check(lease.frame().planes().size()==1,"Only direct gain is requested");identityChecks++;long releaseStart=System.nanoTime();if(!retain)lease.close();long released=System.nanoTime();
            long diagnosticStart=System.nanoTime();check(renderer.diagnostics().residentMeshes()==world.meshes.size(),"Exact resident mesh count after accepted step "+step);pollGpu(monitor);
            resources.write(RtxptJson.encode(map("session",session,"phase",phase,"step",step,"sequence",sequence,"revision",world.revision,"upload",record(renderer.uploadMemory()),"pressure",record(renderer.resourcePressure()),"maintenance",record(renderer.resourceMaintenance()),"dynamic",record(renderer.dynamicResourceStatistics()),"sparse",record(renderer.sparseLightingStatus())))+"\n");long diagnostic=System.nanoTime()-diagnosticStart;
            var resident=world.resident();var status=renderer.sparseLightingStatus();long total=fresh-start,apply=applied-start,acquire=fresh-admitted,wait=admitted-applied-submitActive,release=retain?0:released-releaseStart;
            metrics.add(phase+"/ownerApplyToFresh",total);metrics.add(phase+"/apply",apply);metrics.add(phase+"/submitActive",submitActive);metrics.add(phase+"/admissionWait",wait);metrics.add(phase+"/acquire",acquire);metrics.add(phase+"/release",release);metrics.add(phase+"/generation",batch.generationNanos());
            frames.write(session+","+phase+","+step+","+sequence+","+world.revision+","+total+","+apply+","+submitActive+","+wait+","+acquire+","+release+","+batch.generationNanos()+","+deferrals+","+world.meshes.size()+","+resident.get("vertices")+","+resident.get("triangles")+","+world.sourceIds.size()+","+batch.meshes()+","+batch.textures()+","+batch.materials()+","+batch.instances()+","+batch.removals()+","+(step>=0&&phase.equals("streaming")?number(object(object(profile.curves.get(step)).get("updates")),"removals"):0)+","+(step>=0&&phase.equals("streaming")?number(object(object(profile.curves.get(step)).get("updates")),"removals")-batch.removals():0)+","+batch.sourcePatches()+","+batch.sourceUpserts()+","+batch.sourceRemovals()+","+batch.logicalAssetBytes()+","+(status.uploadedRecordBytes()-beforeRecords)+","+(status.uploadedIndexBytes()-beforeIndex)+","+diagnostic+"\n");sequence++;return retain?lease:null;
        }
        void boundary(RtxptRenderer renderer,RtxptDiagnostics.GpuMonitor monitor,String phase)throws Exception{long start=System.nanoTime();renderer.awaitIdle();pollGpu(monitor);metrics.add(phase+"/boundaryDrain",System.nanoTime()-start);}
        void capture(RtxptRenderer renderer,String name)throws Exception{Path path=output.resolve(name+".pfm");long start=System.nanoTime();renderer.captureNativeLighting(path,0);byte[] bytes=Files.readAllBytes(path);int cursor=0,lines=0;String[] header=new String[3];while(lines<3){int begin=cursor;while(cursor<bytes.length&&bytes[cursor]!='\n')cursor++;check(cursor<bytes.length,"PFM header");String line=new String(bytes,begin,cursor-begin,StandardCharsets.US_ASCII).trim();cursor++;if(!line.startsWith("#")&&!line.isEmpty())header[lines++]=line;}check(header[0].equals("PF")||header[0].equals("Pf"),"PFM signal format");var dims=header[1].split("\\s+");int width=Integer.parseInt(dims[0]),height=Integer.parseInt(dims[1]),channels=header[0].equals("PF")?3:1;check(width==profile.gridWidth&&height==profile.gridHeight,"Capture has requested receiver extent");check(bytes.length-cursor==4L*width*height*channels,"Complete PFM payload");var floats=ByteBuffer.wrap(bytes,cursor,bytes.length-cursor).order(Float.parseFloat(header[2])<0?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).asFloatBuffer();while(floats.hasRemaining())check(Float.isFinite(floats.get()),"Finite fresh signal capture");captures.add(map("name",name,"width",width,"height",height,"sha256",hash(bytes),"captureNanosOutsideTiming",System.nanoTime()-start));}
        int session(World world,boolean full,int reopenSources)throws Exception{
            String session=full?"primary":"reopen";long openStart=System.nanoTime();var config=RtxptRenderers.nativeLightingConfiguration().copyBuilder().resources(RendererResourceOptions.medium().withAssetBytes(1024L<<20,256L<<20,1280L<<20)).maxFramesInFlight(2).cpuFrameReadbackEnabled(false).gpuTimingsEnabled(true).frameGeneration(FrameGenerationOptions.disabled()).lowLatency(LowLatencyOptions.disabled()).build();
            CpuCollector observer=null;var evidence=new LinkedHashMap<String,Object>();evidence.put("session",session);RtxptRenderer renderer=new RtxptRenderer(config,true);evidence.put("rendererOpenNanos",System.nanoTime()-openStart);
            try{
                renderer.nativeLightingPlan(new NativeLightingPlan(profile.gridWidth,profile.gridHeight,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,new NativeLightingStyle(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,0,0,0,0,1,1,1,0,255,1),new NativeLightingPlan.Directional(0,1,0,.8f,1,1,1),null,true).withEncoding(NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8));
                renderer.diagnosticLevel(RtxptDiagnostics.Level.DEEP);observer=new CpuCollector(renderer,output.resolve(session+"-cpu-scopes.csv"),phases);var monitor=renderer.diagnosticGpuMonitor();
                int initialMeshes=number(object(profile.curves.get(0)),"resident_meshes");int count=full?initialMeshes:profile.terminalMeshes,vertices=full?(int)((long)profile.terminalVertices*count/profile.terminalMeshes):profile.terminalVertices,triangles=full?(int)((long)profile.terminalTriangles*count/profile.terminalMeshes):profile.terminalTriangles;
                frame(renderer,monitor,world,world.seed(count,vertices,triangles,full?profile.initialSources:reopenSources),session,full?"cold":"reopen-cold",-1,false,false);boundary(renderer,monitor,session+"-bootstrap");capture(renderer,session+"-bootstrap");
                for(int i=0;i<24;i++)frame(renderer,monitor,world,world.unchanged(),session,full?"warmup":"reopen-warmup",i,false,false);boundary(renderer,monitor,session+"-warmup");
                for(int i=0;i<48;i++)frame(renderer,monitor,world,world.unchanged(),session,full?"stationary":"reopen-stationary",i,false,false);
                if(full){
                    for(int i=0;i<profile.curves.size();i++){frame(renderer,monitor,world,world.curve(i),session,"streaming",i,false,false);var observation=profile.observationsAt.get(i);if(observation!=null){var status=renderer.sparseLightingStatus();check(status.representedSources()==number(observation,"sources")&&status.indexNodes()==number(observation,"index_nodes"),"Source/index observation matches accepted step "+i);}}
                    boundary(renderer,monitor,"streaming");frame(renderer,monitor,world,world.convergence(),session,"convergence",-1,false,false);boundary(renderer,monitor,"convergence");capture(renderer,"terminal-converged");
                    for(int i=0;i<12;i++)frame(renderer,monitor,world,world.unchanged(),session,"terminal-warmup",i,false,false);
                    for(int i=0;i<48;i++)frame(renderer,monitor,world,world.unchanged(),session,"terminal-stationary",i,false,false);
                    for(int i=0;i<16;i++)frame(renderer,monitor,world,world.modify(false,i),session,"deformation",i,false,false);
                    for(int i=0;i<16;i++)frame(renderer,monitor,world,world.modify(true,i),session,"attributes",i,false,false);
                    var held=new ArrayList<LightingFrameLease>();boolean deferred=false;long retrySequence=-1;
                    try{for(int i=0;i<64;i++){var lease=frame(renderer,monitor,world,world.unchanged(),session,"slow-consumer",i,true,true);if(lease==null){deferred=true;retrySequence=sequence;break;}held.add(lease);}}finally{for(var lease:held)lease.close();}
                    evidence.put("heldFreshLeases",held.size());evidence.put("admissionDeferred",deferred);check(deferred,"Retained fresh leases must reach explicit bounded admission");frame(renderer,monitor,world,world.unchanged(),session,"consumer-released",-1,false,false);check(sequence==retrySequence+1,"The deferred identity must publish exactly once after release");
                }
                boundary(renderer,monitor,session+"-final");var execution=renderer.nativeLightingExecution();check(execution.fullBeautyFrames()==0&&execution.gridWidth()==profile.gridWidth&&execution.gridHeight()==profile.gridHeight&&execution.actualSignalMask()==1,"Direct-only live execution and receiver extent");check(renderer.lightingReconstruction().actual()==NativeLightingReconstruction.NONE,"Reconstruction remains disabled");
                var upload=renderer.uploadMemory();check(upload.createdBytes()-upload.releasedBytes()==upload.retainedBytes(),"Upload memory lifetime accounting");check(upload.retainedBytes()>=0&&upload.fencePendingBytes()>=0&&upload.fenceRetiredBytes()>=0,"Nonnegative upload ownership");
                evidence.put("resident",world.resident());evidence.put("sparseLighting",record(renderer.sparseLightingStatus()));evidence.put("uploadMemory",record(upload));evidence.put("resourcePressure",record(renderer.resourcePressure()));evidence.put("resourceMaintenance",record(renderer.resourceMaintenance()));evidence.put("dynamicResources",record(renderer.dynamicResourceStatistics()));evidence.put("execution",record(execution));evidence.put("assetBudgets",record(config.resources()));evidence.put("gpuStatus",map("droppedQueries",gpuDropped,"overwrittenScopes",gpuOverwritten,"pendingQueries",gpuPending,"missedScopes",gpuMissed));
                return world.sourceIds.size();
            }finally{
                try{if(observer!=null){try{observer.close();}finally{evidence.put("cpuDiagnostics",observer.status());evidence.put("cpuEvidenceComplete",observer.missed==0&&observer.missedSubmissions==0&&observer.truncated==0&&observer.droppedActive==0&&observer.finalActive==0&&observer.failure.get()==null);}}}finally{long closeStart=System.nanoTime();try{renderer.close();}finally{evidence.put("rendererCloseNanos",System.nanoTime()-closeStart);sessions.add(evidence);}}
            }
        }
        Object summary(String status,String failure){return map("schema","streaming-scene-load-result-v1","status",status,"failure",failure,"profileSha256",profile.sha256,"semantics",SEMANTICS,"observedGeometryBytes",null,"syntheticTextureEdge",TEXTURE_EDGE,"ownerTimingDefinition","apply entry through accepted exact fresh lease acquisition; resource generation, release, diagnostics, captures and boundary GPU drains reported separately; a fresh lease is not GPU-consumer completion","frameCap","NONE","giSamples",0,"reconstruction","DISABLED","freshIdentityChecks",identityChecks,"acceptedFrames",sequence,"ownerDistributions",metrics.summary(),"gpuDistributions",gpuMetrics.summary(),"gpuScopes",gpuScopes,"gpuDroppedQueries",gpuDropped,"gpuOverwrittenRingScopes",gpuOverwritten,"gpuMissedScopes",gpuMissed,"gpuPendingQueriesAtLastPoll",gpuPending,"gpuPollNanosOutsideOwnerTiming",gpuPollNanos,"sessions",sessions,"captures",captures,"sourceObservationEvidence",profile.observations,"firstSourceInterval","Unknown; initial source population is a synthetic bootstrap and not an observed delta");}
        public void close()throws IOException{try{frames.close();}finally{try{gpu.close();}finally{resources.close();}}}
    }

    public static void main(String[] args)throws Exception{
        check(args.length>=2,"Arguments: profile.json output-directory [--fixture-only]");var profile=new Profile(Path.of(args[0]));var output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        json(output.resolve("workload.json"),map("schema","streaming-scene-load-contract-v1","profileSha256",profile.sha256,"semantics",SEMANTICS,"curveSteps",profile.curves.size(),"sourceObservations",profile.observations.size(),"syntheticSourcePatches",profile.actions.size(),"terminalTarget",profile.data.get("terminal_converged"),"firstObservationBootstrapSources",profile.initialSources,"observedGeometryBytes",null));
        if(args.length>2&&args[2].equals("--fixture-only")){System.out.println("STREAMING_FIXTURE PASS profile="+profile.sha256+" noRenderer=true");return;}
        try(var harness=new Harness(profile,output)){String status="FAIL",failure=null;try{var world=new World(profile);int sources=harness.session(world,true,0);harness.session(new World(profile),false,sources);status="PASS";System.out.println("STREAMING_LOAD PASS profile="+profile.sha256+" freshFrames="+harness.sequence);}catch(Exception|Error e){failure=e.toString();try(var writer=Files.newBufferedWriter(output.resolve("failure.txt"),StandardCharsets.UTF_8)){e.printStackTrace(new PrintWriter(writer));}throw e;}finally{json(output.resolve("summary.json"),harness.summary(status,failure));}}
    }

    /** Minimal strict JSON reader for the checked-in numeric workload; no optional parser dependency. */
    private static final class Json {
        final String text;int cursor;Json(String text){this.text=text;}
        Object read(){Object value=value();space();check(cursor==text.length(),"Trailing JSON content");return value;}
        void space(){while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;}
        char take(){check(cursor<text.length(),"Unexpected JSON end");return text.charAt(cursor++);}
        Object value(){space();check(cursor<text.length(),"Missing JSON value");char c=text.charAt(cursor);if(c=='{'){cursor++;var map=new LinkedHashMap<String,Object>();space();if(text.charAt(cursor)=='}'){cursor++;return map;}while(true){space();String key=string();space();check(take()==':',"JSON object colon");check(!map.containsKey(key),"Duplicate JSON key");map.put(key,value());space();char separator=take();if(separator=='}')return map;check(separator==',',"JSON object separator");}}if(c=='['){cursor++;var list=new ArrayList<Object>();space();if(text.charAt(cursor)==']'){cursor++;return list;}while(true){list.add(value());space();char separator=take();if(separator==']')return list;check(separator==',',"JSON array separator");}}if(c=='\"')return string();for(String literal:List.of("null","true","false"))if(text.startsWith(literal,cursor)){cursor+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}int begin=cursor;while(cursor<text.length()&&"-+0123456789.eE".indexOf(text.charAt(cursor))>=0)cursor++;check(cursor>begin,"Invalid JSON token");String token=text.substring(begin,cursor);return token.indexOf('.')>=0||token.indexOf('e')>=0||token.indexOf('E')>=0?Double.valueOf(token):Long.valueOf(token);}
        String string(){check(take()=='\"',"JSON string");var result=new StringBuilder();while(true){char c=take();if(c=='\"')return result.toString();check(c>=32,"JSON control character");if(c!='\\'){result.append(c);continue;}char escape=take();switch(escape){case '\"','\\','/'->result.append(escape);case 'b'->result.append('\b');case 'f'->result.append('\f');case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');case 'u'->{check(cursor+4<=text.length(),"JSON Unicode escape");result.append((char)Integer.parseInt(text.substring(cursor,cursor+4),16));cursor+=4;}default->throw new IllegalArgumentException("Invalid JSON escape");}}}
    }
}
