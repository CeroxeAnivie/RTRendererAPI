package top.ceroxe.rt.renderer.rtxpt;

import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptJson.object;

/** Retained resources and glTF asset translation. No tracing, denoising or scheduling. */
final class RtxptScene {
    final Map<Long,TextureAsset> textures=new LinkedHashMap<>();
    final Map<Long,MaterialAsset> materials=new LinkedHashMap<>();
    final Map<Long,MeshAsset> meshes=new LinkedHashMap<>();
    final Map<Long,SceneInstance> instances=new LinkedHashMap<>();
    final Map<Long,SceneLight> lights=new LinkedHashMap<>();
    final Map<String,MediumBoundary> interiors=new LinkedHashMap<>();
    RtxptScene() { }
    RtxptScene(RtxptScene src) {
        textures.putAll(src.textures);materials.putAll(src.materials);meshes.putAll(src.meshes);
        instances.putAll(src.instances);lights.putAll(src.lights);
    }
    static <T> void remove(Map<Long,T> m,LongBuffer ids){while(ids.hasRemaining())m.remove(ids.get());}
    void retain(SceneTransaction tx) {
        if(tx.reset()){textures.clear();materials.clear();meshes.clear();instances.clear();lights.clear();}
        var r=tx.removals();remove(textures,r.textureIds());remove(materials,r.materialIds());remove(meshes,r.meshIds());remove(instances,r.instanceIds());remove(lights,r.lightIds());
        var u=tx.upserts();u.textures().forEach(t->textures.put(t.id(),t));u.materials().forEach(m->materials.put(m.id(),m));
        u.meshes().forEach(m->meshes.put(m.id(),m));u.instances().forEach(i->instances.put(i.id(),i));u.lights().forEach(l->lights.put(l.id(),l));
        validate();
    }
    void validate() {
        for(var m:materials.values()) {
            if(m.shadingModel()!=MaterialAsset.ShadingModel.PHYSICALLY_BASED || m.surfaceOverlay().enabled())
                throw new IllegalArgumentException("RTXPT uses native physical materials; custom surface equations/overlays belong to Vulkan");
            for(long t:new long[]{m.baseColorTextureId(),m.normalTextureId(),m.metallicRoughnessTextureId(),m.emissiveTextureId()})
                if(t>=0 && !textures.containsKey(t))throw new IllegalArgumentException("Missing texture "+t);
        }
        for(var m:meshes.values()){var ids=m.triangleMaterialIds();while(ids.hasRemaining())if(!materials.containsKey(ids.get()))throw new IllegalArgumentException("Missing triangle material");}
        for(var i:instances.values())validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.renderState(),i.castsShadow(),i.visibilityMask());
    }
    void validateInstance(long id,AffineTransform transform,MediumBoundary b,InstanceRenderState state,boolean shadow,int mask) {
        var m=meshes.get(id);if(m==null)throw new IllegalArgumentException("Missing mesh "+id);
        if(b!=null && !m.isClosedManifold())throw new IllegalArgumentException("Medium requires a closed outward-wound manifold mesh");
        if(!state.equals(InstanceRenderState.defaults()) || !shadow || mask!=255)
            throw new IllegalArgumentException("Custom ray masks, no-shadow instances and game surface overrides belong to Vulkan");
        // Upstream scene nodes use TRS. Reject shear instead of silently changing geometry.
        for(int a=0;a<3;a++)for(int c=a+1;c<3;c++) {
            double dot=0,aa=0,cc=0;for(int r=0;r<3;r++){double x=transform.element(r*4+a),y=transform.element(r*4+c);dot+=x*y;aa+=x*x;cc+=y*y;}
            if(Math.abs(dot)>1e-5*Math.sqrt(aa*cc))throw new IllegalArgumentException("RTXPT scene transforms must be TRS without shear");
        }
    }
    record Item(String name,long mesh,AffineTransform transform,MediumBoundary boundary,float visibility) { }
    List<Item> items(RenderFrameRequest f) {
        var a=new ArrayList<Item>();for(var i:instances.values())a.add(new Item("i"+i.id(),i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.surfaceVisibility()));
        int k=0;for(var i:f.primitiveBatch().primitives()) {
            validateInstance(i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.renderState(),i.castsShadow(),i.visibilityMask());
            a.add(new Item("p"+k++,i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),i.surfaceVisibility()));
        }
        return a;
    }
    static boolean sameStructure(List<Item> a,List<Item> b) {
        if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++){
            var x=a.get(i);var y=b.get(i);if(!x.name.equals(y.name)||x.mesh!=y.mesh||!Objects.equals(x.boundary,y.boundary)||x.visibility!=y.visibility)return false;
        }return true;
    }
    static FloatBuffer floats(int n){return ByteBuffer.allocateDirect(Math.multiplyExact(n,4)).order(ByteOrder.nativeOrder()).asFloatBuffer();}
    void uploadInteriors(long handle) {
        if(interiors.isEmpty())return;
        var data=floats(interiors.size()*5);for(var b:interiors.values()) {
            var m=b.medium();data.put(m.absorption().red()).put(m.absorption().green()).put(m.absorption().blue()).put(m.indexOfRefraction()).put(b.priority());
        }
        RtxptNative.interiors(handle,interiors.keySet().toArray(String[]::new),data.flip());
    }
    Path export(Path dir,RenderFrameRequest frame,List<Item> items) throws IOException {
        Files.createDirectories(dir);interiors.clear();
        var root=object("asset",object("version","2.0","generator","RTRendererAPI 7 RTXPT thin scene adapter"));
        var views=new ArrayList<Object>();var accessors=new ArrayList<Object>();var gm=new ArrayList<Object>();var mats=new ArrayList<Object>();var nodes=new ArrayList<Object>();
        var images=new ArrayList<Object>();var tex=new ArrayList<Object>();var samplers=new ArrayList<Object>();var texIds=new HashMap<Long,Integer>();
        for(var t:textures.values()) {
            var image=new BufferedImage(t.width(),t.height(),BufferedImage.TYPE_INT_ARGB);var pixels=t.rgba8();
            for(int y=0;y<t.height();y++)for(int x=0;x<t.width();x++) {
                int r=pixels.get()&255,g=pixels.get()&255,b=pixels.get()&255,a=pixels.get()&255;image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
            }
            String name="t"+t.id()+".png";ImageIO.write(image,"png",dir.resolve(name).toFile());
            texIds.put(t.id(),tex.size());images.add(object("uri",name));
            samplers.add(object("wrapS",t.addressU()==TextureAsset.AddressMode.REPEAT?10497:33071,"wrapT",t.addressV()==TextureAsset.AddressMode.REPEAT?10497:33071));
            tex.add(object("source",images.size()-1,"sampler",samplers.size()-1));
        }
        var bin=new ByteArrayOutputStream();var geometry=new HashMap<Long,Map<String,Integer>>();
        for(var mesh:meshes.values()) {
            var attr=new LinkedHashMap<String,Integer>();attr.put("POSITION",accessor(bin,views,accessors,mesh.positions(),3,mesh.vertexCount(),true));
            if(mesh.normals().hasRemaining())attr.put("NORMAL",accessor(bin,views,accessors,mesh.normals(),3,mesh.vertexCount(),false));
            if(mesh.textureCoordinates().hasRemaining())attr.put("TEXCOORD_0",accessor(bin,views,accessors,mesh.textureCoordinates(),2,mesh.vertexCount(),false));
            if(mesh.vertexColorsRgba8().hasRemaining()) {
                var colors=mesh.vertexColorsRgba8();var fs=FloatBuffer.allocate(mesh.vertexCount()*4);
                while(colors.hasRemaining()){int c=colors.get();fs.put(((c>>>16)&255)/255f).put(((c>>>8)&255)/255f).put((c&255)/255f).put((c>>>24)/255f);}fs.flip();
                attr.put("COLOR_0",accessor(bin,views,accessors,fs,4,mesh.vertexCount(),false));
            }
            geometry.put(mesh.id(),attr);
        }
        var variants=new HashMap<String,Integer>();
        for(var item:items) {
            var mesh=meshes.get(item.mesh);String key=item.mesh+":"+item.boundary+":"+item.visibility;
            Integer meshIndex=variants.get(key);
            if(meshIndex==null) {
                var groups=new LinkedHashMap<Long,List<Integer>>();var ids=mesh.triangleMaterialIds();var indices=mesh.triangleIndices();
                for(int t=0;t<mesh.triangleCount();t++) {var group=groups.computeIfAbsent(ids.get(t),ignored->new ArrayList<>());for(int j=0;j<3;j++)group.add(indices.get(t*3+j));}
                var primitives=new ArrayList<Object>();
                for(var group:groups.entrySet()) {
                    var m=materials.get(group.getKey());String name="mat"+mats.size();
                    mats.add(material(name,m,item.boundary,item.visibility,texIds));if(item.boundary!=null)interiors.put(name,item.boundary);
                    int start=bin.size();for(int index:group.getValue())putInt(bin,index);
                    int view=views.size();views.add(object("buffer",0,"byteOffset",start,"byteLength",bin.size()-start));
                    int acc=accessors.size();accessors.add(object("bufferView",view,"componentType",5125,"count",group.getValue().size(),"type","SCALAR"));
                    primitives.add(object("attributes",geometry.get(item.mesh),"indices",acc,"material",mats.size()-1));
                }
                meshIndex=gm.size();variants.put(key,meshIndex);gm.add(object("primitives",primitives));
            }
            nodes.add(object("name",item.name,"mesh",meshIndex,"matrix",matrix(item.transform)));
        }
        root.put("buffers",List.of(object("uri","content.bin","byteLength",bin.size())));root.put("bufferViews",views);root.put("accessors",accessors);
        root.put("materials",mats);root.put("meshes",gm);root.put("nodes",nodes);root.put("scene",0);
        root.put("scenes",List.of(object("nodes",java.util.stream.IntStream.range(0,nodes.size()).boxed().toList())));
        root.put("extensionsUsed",List.of("KHR_materials_transmission","KHR_materials_ior","KHR_materials_emissive_strength"));
        if(!tex.isEmpty()){root.put("textures",tex);root.put("images",images);root.put("samplers",samplers);}
        Files.write(dir.resolve("content.bin"),bin.toByteArray());Files.writeString(dir.resolve("content.gltf"),RtxptJson.encode(root));
        var graph=new ArrayList<Object>();graph.add(object("name","RTRoot","model",0));
        for(var l:lights.values()) {
            var light=object("name","l"+l.id(),"type",switch(l.type()){case POINT->"PointLight";case SPOT->"SpotLight";case DIRECTIONAL->"DirectionalLight";},
                "translation",List.of(l.x(),l.y(),l.z()),"rotation",aim(l.directionX(),l.directionY(),l.directionZ()),"color",List.of(l.red(),l.green(),l.blue()));
            if(l.type()==SceneLight.Type.DIRECTIONAL)light.put("irradiance",l.intensity());else {light.put("intensity",l.intensity());light.put("radius",.03);light.put("range",0);}
            if(l.type()==SceneLight.Type.SPOT){light.put("innerAngle",Math.toDegrees(Math.acos(l.innerConeCosine())));light.put("outerAngle",Math.toDegrees(Math.acos(l.outerConeCosine())));}
            graph.add(light);
        }
        var e=frame.environment();
        if(e.sunIntensity()>0)graph.add(object("name","Sun","type","DirectionalLight","rotation",aim(e.sunDirectionX(),e.sunDirectionY(),e.sunDirectionZ()),"color",List.of(e.sunRed(),e.sunGreen(),e.sunBlue()),"irradiance",e.sunIntensity(),"angularSize",.53));
        writeSky(dir.resolve("sky.hdr"),e);
        graph.add(object("name","Sky","type","EnvironmentLight","path",dir.resolve("sky.hdr").toAbsolutePath().toString().replace('\\','/'),"intensity",1,"textureIndex",0,"rotation",0));
        graph.add(object("name","Camera","type","PerspectiveCameraEx","translation",List.of(frame.camera().x(),frame.camera().y(),frame.camera().z()),"verticalFov",2*Math.atan(frame.camera().tanHalfFovY()),"zNear",.001,"enableAutoExposure",false));
        Path scene=dir.resolve("managed.scene.json");Files.writeString(scene,RtxptJson.encode(object("models",List.of("content.gltf"),"graph",graph)));return scene;
    }
    private static Map<String,Object> material(String name,MaterialAsset m,MediumBoundary b,float visibility,Map<Long,Integer> textures) {
        int c=m.baseColorRgba8();var pbr=object("baseColorFactor",List.of(((c>>>16)&255)/255f,((c>>>8)&255)/255f,(c&255)/255f,((c>>>24)&255)/255f*visibility),"roughnessFactor",m.roughness(),"metallicFactor",m.metallic());
        slot(pbr,"baseColorTexture",m.baseColorTextureId(),textures);slot(pbr,"metallicRoughnessTexture",m.metallicRoughnessTextureId(),textures);
        var extensions=object("KHR_materials_ior",object("ior",b==null?m.indexOfRefraction():b.medium().indexOfRefraction()),"KHR_materials_transmission",object("transmissionFactor",m.transmission()));
        var result=object("name",name,"pbrMetallicRoughness",pbr,"doubleSided",m.doubleSided(),"alphaMode",switch(m.blendMode()){case OPAQUE->"OPAQUE";case MASKED->"MASK";case TRANSLUCENT->"BLEND";},"alphaCutoff",m.alphaCutoff(),"extensions",extensions);
        slot(result,"normalTexture",m.normalTextureId(),textures);slot(result,"emissiveTexture",m.emissiveTextureId(),textures);
        int ec=m.emissiveColorRgba8();result.put("emissiveFactor",List.of(((ec>>>16)&255)/255f,((ec>>>8)&255)/255f,(ec&255)/255f));
        extensions.put("KHR_materials_emissive_strength",object("emissiveStrength",m.emissiveStrength()));return result;
    }
    private static void slot(Map<String,Object> out,String key,long id,Map<Long,Integer> textures){if(id>=0)out.put(key,object("index",Objects.requireNonNull(textures.get(id))));}
    static List<Double> matrix(AffineTransform m){var r=new ArrayList<Double>(16);for(int c=0;c<4;c++)for(int row=0;row<4;row++)r.add(row==3?(c==3?1d:0d):(double)m.element(row*4+c));return r;}
    private static List<Double> aim(float x,float y,float z){double length=Math.sqrt(x*x+y*y+z*z);x/=length;y/=length;z/=length;if(z>.99999)return List.of(0d,1d,0d,0d);double w=1-z,n=Math.sqrt(x*x+y*y+w*w);return List.of(y/n,-x/n,0d,w/n);}
    private static int accessor(ByteArrayOutputStream bin,List<Object> views,List<Object> accessors,FloatBuffer data,int components,int count,boolean bounds) {
        int start=bin.size();float[] min=new float[components],max=new float[components];Arrays.fill(min,Float.POSITIVE_INFINITY);Arrays.fill(max,Float.NEGATIVE_INFINITY);
        for(int i=0;data.hasRemaining();i++){float v=data.get();min[i%components]=Math.min(min[i%components],v);max[i%components]=Math.max(max[i%components],v);putInt(bin,Float.floatToRawIntBits(v));}
        int view=views.size();views.add(object("buffer",0,"byteOffset",start,"byteLength",bin.size()-start));
        var a=object("bufferView",view,"componentType",5126,"count",count,"type","VEC"+components);if(bounds){a.put("min",min);a.put("max",max);}int result=accessors.size();accessors.add(a);return result;
    }
    private static void putInt(ByteArrayOutputStream out,int n){out.write(n);out.write(n>>>8);out.write(n>>>16);out.write(n>>>24);}
    private static void writeSky(Path file,EnvironmentState e) throws IOException {
        double r=e.skyRed()*e.ambientIntensity(),g=e.skyGreen()*e.ambientIntensity(),b=e.skyBlue()*e.ambientIntensity(),v=Math.max(r,Math.max(g,b));
        int exponent=v<1e-32?0:Math.getExponent(v)+1;double scale=v<1e-32?0:Math.scalb(256d,-exponent);
        try(var out=Files.newOutputStream(file)){out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));for(int i=0;i<8;i++)out.write(new byte[]{(byte)(r*scale),(byte)(g*scale),(byte)(b*scale),(byte)(v<1e-32?0:exponent+128)});}
    }
}
