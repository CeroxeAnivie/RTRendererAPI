package top.ceroxe.rt.renderer.rtxpt;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptJson.object;
import static top.ceroxe.rt.renderer.rtxpt.RtxptScene.*;
/** File oracle for standalone upstream comparisons. Not part of the production adapter. */
final class RtxptSceneExport {
    private final RtxptScene scene;
    RtxptSceneExport(RtxptScene scene){this.scene=scene;}
    Path exportModel(Path dir,List<Item> items,Map<Long,Path> textureFiles,List<Variant> extraMaterials) throws IOException {
        Files.createDirectories(dir);scene.interiors.clear();scene.materialIors.clear();
        var root=object("asset",object("version","2.0","generator","RTRendererAPI RTXPT thin scene adapter"));
        var views=new ArrayList<Object>();var accessors=new ArrayList<Object>();var gm=new ArrayList<Object>();var mats=new ArrayList<Object>();var nodes=new ArrayList<Object>();
        var images=new ArrayList<Object>();var tex=new ArrayList<Object>();var samplers=new ArrayList<Object>();var texIds=new HashMap<Long,Integer>();
        for(var t:scene.textures.values()) {
            Path imagePath=textureFiles.get(t.id());
            if(imagePath==null) {
            var image=new BufferedImage(t.width(),t.height(),BufferedImage.TYPE_INT_ARGB);var pixels=t.rgba8();
            for(int y=0;y<t.height();y++)for(int x=0;x<t.width();x++) {
                int r=pixels.get()&255,g=pixels.get()&255,b=pixels.get()&255,a=pixels.get()&255;image.setRGB(x,y,(a<<24)|(r<<16)|(g<<8)|b);
            }
            String name="t"+t.id()+".png";imagePath=dir.resolve(name).toAbsolutePath();ImageIO.write(image,"png",imagePath.toFile());textureFiles.put(t.id(),imagePath);
            }
            texIds.put(t.id(),tex.size());images.add(object("uri",imagePath.toString().replace('\\','/')));
            samplers.add(object("wrapS",t.addressU()==TextureAsset.AddressMode.REPEAT?10497:33071,"wrapT",t.addressV()==TextureAsset.AddressMode.REPEAT?10497:33071));
            tex.add(object("source",images.size()-1,"sampler",samplers.size()-1));
        }
        var bin=new ByteArrayOutputStream();var geometry=new HashMap<Long,Map<String,Integer>>();
        var exportedIndices=new HashMap<Long,IntBuffer>();
        for(var mesh:scene.meshes.values()) {
            var data=ExportGeometry.from(mesh);exportedIndices.put(mesh.id(),data.indices());
            var attr=new LinkedHashMap<String,Integer>();attr.put("POSITION",accessor(bin,views,accessors,data.positions(),3,data.vertexCount(),true));
            attr.put("NORMAL",accessor(bin,views,accessors,data.normals(),3,data.vertexCount(),false));
            if(data.tangents().hasRemaining())attr.put("TANGENT",accessor(bin,views,accessors,data.tangents(),4,data.vertexCount(),false));
            if(data.uvs().hasRemaining())attr.put("TEXCOORD_0",accessor(bin,views,accessors,data.uvs(),2,data.vertexCount(),false));
            if(data.colors().hasRemaining()) {
                var colors=data.colors();var fs=FloatBuffer.allocate(data.vertexCount()*4);
                while(colors.hasRemaining()){int c=colors.get();fs.put((c&255)/255f).put(((c>>>8)&255)/255f).put(((c>>>16)&255)/255f).put((c>>>24)/255f);}fs.flip();
                attr.put("COLOR_0",accessor(bin,views,accessors,fs,4,data.vertexCount(),false));
            }
            geometry.put(mesh.id(),attr);
        }
        var materialSlots=new LinkedHashMap<String,Integer>();
        for(var v:extraMaterials)addMaterial(v,mats,materialSlots,texIds);
        var variants=new HashMap<String,Integer>();
        for(var item:items) {
            var mesh=scene.meshes.get(item.mesh());String key=meshName(item);
            Integer meshIndex=variants.get(key);
            if(meshIndex==null) {
                var groups=new LinkedHashMap<Long,List<Integer>>();var ids=mesh.triangleMaterialIds();var indices=exportedIndices.get(item.mesh());
                for(int t=0;t<mesh.triangleCount();t++) {var group=groups.computeIfAbsent(ids.get(t),ignored->new ArrayList<>());for(int j=0;j<3;j++)group.add(indices.get(t*3+j));}
                var primitives=new ArrayList<Object>();
                for(var group:groups.entrySet()) {
                    int materialSlot=addMaterial(new Variant(group.getKey(),item.boundary(),item.visibility()),mats,materialSlots,texIds);
                    int start=bin.size();for(int index:group.getValue())putInt(bin,index);
                    int view=views.size();views.add(object("buffer",0,"byteOffset",start,"byteLength",bin.size()-start));
                    int acc=accessors.size();accessors.add(object("bufferView",view,"componentType",5125,"count",group.getValue().size(),"type","SCALAR"));
                    primitives.add(object("attributes",geometry.get(item.mesh()),"indices",acc,"material",materialSlot));
                }
                meshIndex=gm.size();variants.put(key,meshIndex);gm.add(object("name",key,"primitives",primitives));
            }
            nodes.add(object("name",item.name(),"mesh",meshIndex,"matrix",matrix(item.transform())));
        }
        root.put("buffers",List.of(object("uri","content.bin","byteLength",bin.size())));root.put("bufferViews",views);root.put("accessors",accessors);
        root.put("materials",mats);root.put("meshes",gm);root.put("nodes",nodes);root.put("scene",0);
        root.put("scenes",List.of(object("nodes",java.util.stream.IntStream.range(0,nodes.size()).boxed().toList())));
        root.put("extensionsUsed",List.of("KHR_materials_transmission","KHR_materials_ior","KHR_materials_emissive_strength"));
        if(!tex.isEmpty()){root.put("textures",tex);root.put("images",images);root.put("samplers",samplers);}
        try(var output=Files.newOutputStream(dir.resolve("content.bin"))){bin.writeTo(output);}
        Files.writeString(dir.resolve("content.gltf"),RtxptJson.encode(root));
        return dir.resolve("content.gltf");
    }
    Path export(Path dir,RenderFrameRequest frame,List<Item> items) throws IOException {
        exportModel(dir,items,new HashMap<>(),List.of());
        var graph=new ArrayList<Object>();graph.add(object("name","RTRoot","model",0));
        for(var l:scene.lights.values()) {
            var light=object("name","l"+l.id(),"type",switch(l.type()){case POINT->"PointLight";case SPOT->"SpotLight";case DIRECTIONAL->"DirectionalLight";},
                "translation",List.of(l.x(),l.y(),l.z()),"rotation",l.type()==SceneLight.Type.POINT?List.of(0,0,0,1):aim(l.directionX(),l.directionY(),l.directionZ()),"color",List.of(l.red(),l.green(),l.blue()));
            if(l.type()==SceneLight.Type.DIRECTIONAL)light.put("irradiance",l.intensity());else {light.put("intensity",l.intensity());light.put("radius",0);light.put("range",l.range());}
            if(l.type()==SceneLight.Type.SPOT){light.put("innerAngle",Math.toDegrees(Math.acos(l.innerConeCosine())));light.put("outerAngle",Math.toDegrees(Math.acos(l.outerConeCosine())));}
            graph.add(light);
        }
        var e=frame.environment();
        if(e.sunIntensity()>0)graph.add(object("name","Sun","type","DirectionalLight","rotation",aim(e.sunDirectionX(),e.sunDirectionY(),e.sunDirectionZ()),"color",List.of(e.sunRed(),e.sunGreen(),e.sunBlue()),"irradiance",e.sunIntensity(),"angularSize",.53));
        writeSky(dir.resolve("sky.hdr"),e);
        graph.add(object("name","Sky","type","EnvironmentLight","path",dir.resolve("sky.hdr").toAbsolutePath().toString().replace('\\','/'),"intensity",1,"textureIndex",0,"rotation",0));
        graph.add(object("name","Camera","type","PerspectiveCameraEx","translation",List.of(frame.camera().x(),frame.camera().y(),frame.camera().z()),"verticalFov",2*Math.atan(frame.camera().tanHalfFovY()),"zNear",.001,"enableAutoExposure",false));
        // Keep the API's original lighting units for the scene-lighting input path.
        // Native scene-graph conversion has different falloff and sky semantics.
        var sceneLights=new ArrayList<Object>();
        sceneLights.add(List.of(0,0,0,0,e.sunDirectionX(),e.sunDirectionY(),e.sunDirectionZ(),0,
            e.sunRed(),e.sunGreen(),e.sunBlue(),e.sunIntensity(),0,0,1,0));
        for(var l:scene.lights.values())sceneLights.add(List.of(l.x(),l.y(),l.z(),switch(l.type()){
            case DIRECTIONAL->0;case POINT->1;case SPOT->2;},
            l.directionX(),l.directionY(),l.directionZ(),l.range(),l.red(),l.green(),l.blue(),l.intensity(),
            l.innerConeCosine(),l.outerConeCosine(),l.castsShadow()?1:0,0));
        var sceneLighting=object("sky",List.of(e.skyRed(),e.skyGreen(),e.skyBlue(),e.ambientIntensity()),"lights",sceneLights);
        Path scene=dir.resolve("managed.scene.json");Files.writeString(scene,RtxptJson.encode(object("models",List.of("content.gltf"),"graph",graph,"rtrSceneLighting",sceneLighting)));return scene;
    }
    private int addMaterial(Variant v,List<Object> mats,Map<String,Integer> slots,Map<Long,Integer> texIds) {
        String name=v.name();Integer slot=slots.get(name);if(slot!=null)return slot;
        var m=scene.materials.get(v.material());int index=mats.size();slots.put(name,index);
        mats.add(material(name,m,v.boundary(),v.visibility(),texIds));
        if(v.boundary()!=null)scene.interiors.put(name,v.boundary());
        scene.materialIors.put(name,v.boundary()==null?m.indexOfRefraction():v.boundary().medium().indexOfRefraction());return index;
    }
    private static Map<String,Object> material(String name,MaterialAsset m,MediumBoundary b,float visibility,Map<Long,Integer> textures) {
        int c=m.baseColorRgba8();var pbr=object("baseColorFactor",List.of((c&255)/255f,((c>>>8)&255)/255f,((c>>>16)&255)/255f,((c>>>24)&255)/255f*visibility),"roughnessFactor",m.roughness(),"metallicFactor",m.metallic());
        slot(pbr,"baseColorTexture",m.baseColorTextureId(),textures);slot(pbr,"metallicRoughnessTexture",m.metallicRoughnessTextureId(),textures);
        var extensions=object("KHR_materials_ior",object("ior",b==null?m.indexOfRefraction():b.medium().indexOfRefraction()),"KHR_materials_transmission",object("transmissionFactor",m.transmission()));
        var result=object("name",name,"pbrMetallicRoughness",pbr,"doubleSided",m.doubleSided(),"alphaMode",switch(m.blendMode()){case OPAQUE->"OPAQUE";case MASKED->"MASK";case TRANSLUCENT->"BLEND";},"alphaCutoff",m.alphaCutoff(),"extensions",extensions);
        slot(result,"normalTexture",m.normalTextureId(),textures);slot(result,"emissiveTexture",m.emissiveTextureId(),textures);
        int ec=m.emissiveColorRgba8();result.put("emissiveFactor",List.of((ec&255)/255f,((ec>>>8)&255)/255f,((ec>>>16)&255)/255f));
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
