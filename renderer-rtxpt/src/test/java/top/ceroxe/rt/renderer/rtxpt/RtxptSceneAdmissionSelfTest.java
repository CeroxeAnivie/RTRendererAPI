package top.ceroxe.rt.renderer.rtxpt;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** CPU admission/lifetime checks. JNI is loaded, but no device, window or frame is created. */
public final class RtxptSceneAdmissionSelfTest {
    private static int checks;
    private static void require(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private record Fixture(MeshAsset mesh,long bytes) { }
    private static Fixture mesh(long id,int triangles,int tint){
        int vertices=triangles*3;float[] p=new float[vertices*3],n=new float[vertices*3],t=new float[vertices*4],uv=new float[vertices*2],light=new float[vertices*2];
        int[] colors=new int[vertices],indices=new int[vertices];long[] materials=new long[triangles];
        Arrays.fill(colors,tint);Arrays.fill(materials,1);
        for(int i=0;i<vertices;i++){p[i*3]=i%3==1?1:0;p[i*3+1]=i%3==2?1:0;n[i*3+2]=1;t[i*4]=t[i*4+3]=1;indices[i]=i;}
        var result=MeshAsset.builder(id,p,indices,materials).normals(n).tangents(t).textureCoordinates(uv).lightmapCoordinates(light).vertexColorsRgba8(colors).build();
        long bytes=vertices*60L+vertices*4L+triangles*8L;
        require(RendererResourceOptions.meshBytes(result)==bytes,"Raw payload contract is 60 bytes/vertex plus indices and material IDs");return new Fixture(result,bytes);
    }
    private static SceneAssetBudgetExceededException over(Runnable work){try{work.run();throw new AssertionError("Budget violation accepted");}catch(SceneAssetBudgetExceededException expected){checks++;return expected;}}
    private static void oracle(){
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build()).build());
        var expected=new HashMap<Long,Fixture>();var random=new Random(519);
        for(int generation=2;generation<=501;generation++){
            var tx=SceneTransaction.builder(generation);long id=1+random.nextInt(32);
            if(expected.containsKey(id)&&random.nextBoolean()){tx.removeInstance(id).removeMesh(id);expected.remove(id);}
            else {var value=mesh(id,1+random.nextInt(7),random.nextInt());tx.upsert(value.mesh()).upsert(SceneInstance.builder(id,id).build());expected.put(id,value);}
            var prior=scene;scene=new RtxptScene(prior);scene.retain(tx.build());
            long bytes=expected.values().stream().mapToLong(Fixture::bytes).sum();
            scene.validateAssetBudget(new RendererResourceOptions(1,1,Math.max(1,bytes),1,Math.max(1,bytes)));
            require(scene.meshes.size()==expected.size(),"Active table agrees with the independent live-ID oracle");
            if(bytes>1){var target=scene;var failure=over(()->target.validateAssetBudget(new RendererResourceOptions(1,1,bytes-1,1,bytes)));
                require(failure.geometryBytes()==bytes&&failure.textureBytes()==0&&failure.totalAssetBytes()==bytes,"Cached admission total equals independent active payload sum");}
        }
        var tx=SceneTransaction.builder(502);for(long id:expected.keySet())tx.removeInstance(id).removeMesh(id);
        var empty=new RtxptScene(scene);empty.retain(tx.build());empty.validateAssetBudget(new RendererResourceOptions(1,1,1,1,1));
        require(empty.meshes.isEmpty()&&empty.instances.isEmpty(),"Complete deletion returns the active payload to zero despite retaining the old snapshot");
        empty.retain(SceneTransaction.builder(503).resetScene().build());empty.validateAssetBudget(new RendererResourceOptions(1,1,1,1,1));
    }
    private static Object field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    @SuppressWarnings("unchecked") private static Map<Object,Object> map(Object object,String name)throws Exception{return (Map<Object,Object>)field(object,name);}
    private static void hostProjection(){
        double[] world={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        double[] projection={1,0,0,.01,0,1,0,-.03,0,0,-1.001,-.1,.2,.1,-1,.03};
        var exact=ExactProjectionState.builder(96,64).matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE).cameraToWorld(world).clipFromView(projection).build();
        var camera=CameraState.exactProjection(exact);float[] packed=RtxptHostSurfaceView.pack(camera,camera,96,64);
        for(int i=0;i<16;i++)require(packed[16+i]==(float)projection[i],"Host view retains the complete perspective projection");
    }
    private static void renderer(RtxptDiagnostics.Level level)throws Exception {
        var small=mesh(1,1,0xff808080);var large=mesh(2,3,0xffffffff);
        var budget=new RendererResourceOptions(1,1,small.bytes()*2,4,small.bytes()*2+4);
        var config=RtxptRenderers.nativeLightingConfiguration().copyBuilder().resources(budget).build();
        var r=new RtxptRenderer(config,true);r.diagnosticLevel(level);r.nativeLightingPlan(NativeLightingPlan.defaults(8,8));
        try {
            r.apply(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build()).upsert(small.mesh()).upsert(SceneInstance.builder(1,1).build()).build());
            var source=SparseLightSource.point(1,0,0,0,1,1,1,1,4);
            var rejected=SceneTransaction.builder(2).upsert(large.mesh()).upsert(SceneInstance.builder(2,2).build()).build();
            var failure=over(()->r.apply(rejected,List.of(source),LongBuffer.allocate(0)));
            require(failure.geometryBytes()==small.bytes()+large.bytes()&&failure.limits().equals(budget),"Typed rejection carries actual totals and host limits");
            require(r.sceneSnapshot().revision()==1&&r.sceneSnapshot().upserts().meshes().size()==1,"Budget rejection preserves accepted revision and tables");
            require(((RtxptScene)field(r,"scene")).sparseSources.isEmpty(),"Budget rejection accepts no sparse-source prefix");
            try {r.apply(SceneTransaction.empty(2),List.of(source,source),LongBuffer.allocate(0));throw new AssertionError("Duplicate source accepted");}
            catch(IllegalArgumentException expected){checks++;}
            require(r.sceneSnapshot().revision()==1&&((RtxptScene)field(r,"scene")).sparseSources.isEmpty(),"Late validation rejection preserves all accepted state");
            Class<?> cache=Class.forName(RtxptRenderer.class.getName()+"$AttributeGroups");var constructor=cache.getDeclaredConstructor(MeshAsset.class,List.class);constructor.setAccessible(true);
            map(r,"attributeGroups").put(1L,constructor.newInstance(small.mesh(),RtxptAttributeBatch.groups(small.mesh())));
            var replacement=mesh(1,1,0xff202020);
            r.apply(SceneTransaction.builder(2).upsert(replacement.mesh()).build(),List.of(source),LongBuffer.allocate(0));
            Object cached=map(r,"attributeGroups").get(1L);var meshMethod=cache.getDeclaredMethod("mesh");meshMethod.setAccessible(true);
            require(meshMethod.invoke(cached)==replacement.mesh(),"Same material grouping must not retain the superseded MeshAsset");
            r.publishLightingAttributes(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.receiver(1)),
                    List.of(new NativeLightingPublication.MeshBaseline(1,new float[6])),new float[512],ByteBuffer.allocate(16)));
            r.apply(SceneTransaction.builder(3).removeInstance(1).removeMesh(1).build(),List.of(),LongBuffer.wrap(new long[]{1}));
            require(map(r,"attributeGroups").isEmpty()&&map(r,"lightingBaselines").isEmpty(),"Delete retires CPU mesh and baseline caches");
            require(((Set<?>)field(r,"pendingLightingColors")).isEmpty(),"Delete retires pending color identity");
            r.apply(SceneTransaction.builder(4).upsert(replacement.mesh()).upsert(SceneInstance.builder(1,1).build()).build());
            map(r,"attributeGroups").put(1L,constructor.newInstance(replacement.mesh(),RtxptAttributeBatch.groups(replacement.mesh())));
            r.publishLightingAttributes(new NativeLightingPublication(2,List.of(NativeLightingPublication.Instance.receiver(1)),
                    List.of(new NativeLightingPublication.MeshBaseline(1,new float[6])),new float[512],ByteBuffer.allocate(16)));
            map(r,"hostRequests").put(1L,new Object());
        }finally{r.close();}
        for(String name:List.of("attributeGroups","lightingInstances","lightingBaselines","hostRequests"))require(map(r,name).isEmpty(),"Close releases "+name);
        for(String name:List.of("pendingLightingInstances","pendingLightingBaselines","pendingLightingColors","pendingSparseIds","pendingApplicationPatches"))require(((Collection<?>)field(r,name)).isEmpty(),"Close releases "+name);
        require(field(r,"pendingBaselineLookup")==null&&field(r,"pendingApplicationData")==null,"Close releases full publication buffers");
        require(((RtxptScene)field(r,"scene")).meshes.isEmpty()&&field(r,"residentScene")==null,"Close releases accepted and resident generations");
    }
    public static void main(String[] arguments)throws Exception {
        hostProjection();oracle();for(var level:RtxptDiagnostics.Level.values())renderer(level);
        System.out.println("SCENE_ADMISSION_CPU PASS checks="+checks+" oracleGenerations=500 diagnosticLevels=3 activePayloadExact=true deletedPayloadZero=true typedBudgetAtomic=true sparseValidationAtomic=true oldMeshReleased=true closeCachesReleased=true noDevice=true");
    }
}
