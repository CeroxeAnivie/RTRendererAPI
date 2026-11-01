package top.ceroxe.rt.renderer.luxcore;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** SDL translation only. No transport, sampling, intersection or GPU scheduling. */
final class LuxCoreScene {
    final Map<Long, TextureAsset> textures = new LinkedHashMap<>();
    final Map<Long, MaterialAsset> materials = new LinkedHashMap<>();
    final Map<Long, MeshAsset> meshes = new LinkedHashMap<>();
    final Map<Long, SceneInstance> instances = new LinkedHashMap<>();
    final Map<Long, SceneLight> lights = new LinkedHashMap<>();
    final Map<Long, List<Part>> parts = new HashMap<>();
    final Set<String> objects = new LinkedHashSet<>();
    long uploads, imageUploads;
    int generation;
    record Part(String shape, long material) { }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static ByteBuffer buffer(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    static ByteBuffer floats(FloatBuffer data) {
        if (!data.hasRemaining()) return null;
        var b=buffer(data.remaining()*4); b.asFloatBuffer().put(data.duplicate()); return b;
    }
    static void property(StringBuilder out, String key, Object value) { out.append(key).append(" = ").append(value).append('\n'); }
    static String rgb(Rgb c) { return c.red()+" "+c.green()+" "+c.blue(); }
    static String gain(float v) { return v+" "+v+" "+v; }
    static String color(int c) { return ((c>>>16)&255)/255f+" "+((c>>>8)&255)/255f+" "+(c&255)/255f; }
    static String matrix(AffineTransform t) {
        var b=new StringBuilder();
        // LuxCore Property<Matrix4x4> stores columns, while the public API stores rows.
        for(int col=0;col<4;col++) for(int row=0;row<4;row++)
            b.append(row==3?(col==3?1:0):t.element(row*4+col)).append(' ');
        return b.toString();
    }
    static <T> void remove(Map<Long,T> map, LongBuffer ids) { while(ids.hasRemaining()) map.remove(ids.get()); }
    void retain(SceneTransaction tx) {
        if(tx.reset()) { textures.clear(); materials.clear(); meshes.clear(); instances.clear(); lights.clear(); parts.clear(); }
        var r=tx.removals();remove(textures,r.textureIds());remove(materials,r.materialIds());remove(meshes,r.meshIds());
        remove(instances,r.instanceIds());remove(lights,r.lightIds());
        var u=tx.upserts();u.textures().forEach(t->textures.put(t.id(),t));u.materials().forEach(m->materials.put(m.id(),m));
        u.meshes().forEach(m->meshes.put(m.id(),m));u.instances().forEach(i->instances.put(i.id(),i));u.lights().forEach(l->lights.put(l.id(),l));
    }
    void upload(long handle, SceneTransaction tx) {
        generation++;
        for(var t:tx.upserts().textures()) {
            var src=t.rgba8();var rgba=buffer(t.width()*t.height()*16);
            for(int i=0;i<t.width()*t.height();i++) for(int c=0;c<4;c++) {
                float v=(src.get()&255)/255f;
                if(c<3 && t.colorSpace()==TextureAsset.ColorSpace.SRGB) v=v<=.04045f?v/12.92f:(float)Math.pow((v+.055f)/1.055f,2.4);
                rgba.putFloat(v);
            }
            LuxCoreNative.defineImage(handle,bytes("image"+t.id()),t.width(),t.height(),rgba.clear());imageUploads++;
        }
        for(var mesh:tx.upserts().meshes()) {
            var groups=new LinkedHashMap<Long,List<Integer>>(); var ids=mesh.triangleMaterialIds();
            for(int i=0;i<mesh.triangleCount();i++) groups.computeIfAbsent(ids.get(i),k->new ArrayList<>()).add(i);
            var list=new ArrayList<Part>();
            for(var group:groups.entrySet()) {
                String name="mesh"+mesh.id()+"g"+generation+"m"+group.getKey();
                uploadPart(handle,mesh,group.getValue(),name,false);uploadPart(handle,mesh,group.getValue(),name+"in",true);
                list.add(new Part(name,group.getKey()));
            }
            parts.put(mesh.id(),List.copyOf(list));
        }
    }
    void uploadPart(long h, MeshAsset mesh, List<Integer> triangles, String name, boolean reverse) {
        var indices=buffer(triangles.size()*12);var source=mesh.triangleIndices();
        for(int t:triangles) {indices.putInt(source.get(t*3));indices.putInt(source.get(t*3+(reverse?2:1)));indices.putInt(source.get(t*3+(reverse?1:2)));}
        var normals=floats(mesh.normals());
        if(reverse&&normals!=null)for(int i=0;i<normals.capacity();i+=4)normals.putFloat(i,-normals.getFloat(i));
        LuxCoreNative.defineMesh(h,bytes(name),mesh.vertexCount(),triangles.size(),floats(mesh.positions()),indices.clear(),normals,floats(mesh.textureCoordinates()));uploads++;
    }
    String scene(RenderFrameRequest frame) {
        var out=new StringBuilder();
        for(var t:textures.values()) {
            String p="scene.textures.tex"+t.id();property(out,p+".type","imagemap");property(out,p+".file","\"image"+t.id()+"\"");property(out,p+".gamma",1);
            property(out,p+".wrap",t.addressU()==TextureAsset.AddressMode.REPEAT?"repeat":"clamp");
        }
        var transport=frame.mediumTransport();
        medium(out,"world",frame.environment().cameraMedium(),0,transport,false);
        property(out,"scene.world.volume.default","world");
        for(var i:instances.values()) i.mediumBoundary().ifPresent(b->medium(out,"v"+i.id(),b.medium(),b.priority(),transport,true));
        int k=0;for(var i:frame.primitiveBatch().primitives()) {int index=k++;i.mediumBoundary().ifPresent(b->medium(out,"vf"+index,b.medium(),b.priority(),transport,true));}
        Set<String> next=new LinkedHashSet<>();
        for(var i:instances.values()) object(out,next,"o"+i.id(),i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),"v"+i.id(),i.visibilityMask(),i.castsShadow(),i.surfaceVisibility());
        k=0;for(var i:frame.primitiveBatch().primitives()) {String name="f"+k++;object(out,next,name,i.meshAssetId(),i.transform(),i.mediumBoundary().orElse(null),"v"+name,i.visibilityMask(),i.castsShadow(),i.surfaceVisibility());}
        objects.clear();objects.addAll(next);
        for(var l:lights.values()) light(out,l);
        var e=frame.environment();
        property(out,"scene.lights.sky.type","constantinfinite");property(out,"scene.lights.sky.color",e.skyRed()+" "+e.skyGreen()+" "+e.skyBlue());
        property(out,"scene.lights.sky.gain",gain(Math.max(0,e.ambientIntensity())));
        property(out,"scene.lights.sun.type","sharpdistant");property(out,"scene.lights.sun.direction",e.sunDirectionX()+" "+e.sunDirectionY()+" "+e.sunDirectionZ());
        property(out,"scene.lights.sun.color",e.sunRed()+" "+e.sunGreen()+" "+e.sunBlue());property(out,"scene.lights.sun.gain",gain(e.sunIntensity()));
        camera(out,frame.camera());return out.toString();
    }
    void medium(StringBuilder out,String name,Medium m,int priority,MediumTransport budget,boolean local) {
        String p="scene.volumes."+name;boolean grid=!m.densityField().isUniform();
        property(out,p+".type",grid?"heterogeneous":m.scattering().isZero()?"clear":"homogeneous");
        String density=null;
        if(grid) {
            var f=m.densityField();var texture=Objects.requireNonNull(textures.get(f.textureAssetId()),"missing medium density texture");
            int nx=texture.width(), nz=f.slices(), ny=texture.height()/nz;
            if(ny<1||texture.height()%nz!=0)throw new IllegalArgumentException("Density atlas height must be divisible by slices");
            density="density"+name;String t="scene.textures."+density;
            property(out,t+".type","densitygrid");property(out,t+".nx",nx);property(out,t+".ny",ny);property(out,t+".nz",nz);property(out,t+".wrap","black");property(out,t+".storage","float");
            property(out,t+".mapping.type",local?"localmapping3d":"globalmapping3d");property(out,t+".mapping.transformation",matrix(f.transform()));
            out.append(t).append(".data = ");var data=texture.rgba8();for(int i=0;i<nx*ny*nz;i++)out.append((data.get(i*4)&255)/255f).append(' ');out.append('\n');
            property(out,p+".steps.size",System.getProperty("top.ceroxe.rt.luxcore.volumeStep","0.05"));
            property(out,p+".steps.maxcount",Math.max(64,budget.scatteringSteps()*64));
        }
        coefficient(out,p,"absorption",m.absorption().multiply(m.density()),density,name);
        coefficient(out,p,"scattering",m.scattering().multiply(m.density()),density,name);
        coefficient(out,p,"emission",m.emission().multiply(m.density()),density,name);
        property(out,p+".ior",m.indexOfRefraction());property(out,p+".asymmetry",m.anisotropy());property(out,p+".priority",priority);
        property(out,p+".multiscattering",budget.scatteringBounces()>0);
    }
    static void coefficient(StringBuilder out,String p,String field,Rgb value,String density,String name) {
        if(density==null) {property(out,p+"."+field,rgb(value));return;}
        String tex=name+field;property(out,"scene.textures."+tex+".type","scale");
        property(out,"scene.textures."+tex+".texture1",density);property(out,"scene.textures."+tex+".texture2",rgb(value));property(out,p+"."+field,tex);
    }
    void object(StringBuilder out,Set<String> names,String name,long mesh,AffineTransform transform,MediumBoundary boundary,String volume,int mask,boolean shadow,float visibility) {
        if(mask==0)return;
        var list=Objects.requireNonNull(parts.get(mesh),"Missing mesh "+mesh);
        int k=0;for(var part:list) {
            String object=name+"p"+k++,mat="mat"+object;
            material(out,mat,Objects.requireNonNull(materials.get(part.material()),"Missing material "+part.material()),boundary,volume,shadow,visibility);
            String p="scene.objects."+object;property(out,p+".shape",part.shape()+(boundary!=null&&boundary.orientation()==MediumBoundary.Orientation.INWARD?"in":""));
            property(out,p+".material",mat);property(out,p+".transformation",matrix(transform));names.add(object);
        }
    }
    static void material(StringBuilder out,String name,MaterialAsset m,MediumBoundary boundary,String volume,boolean shadow,float visibility) {
        String p="scene.materials."+name;
        if(boundary!=null&&!boundary.surfaceInteraction())property(out,p+".type","null");
        else {
            String base=m.baseColorTextureId()<0?color(m.baseColorRgba8()):"tex"+m.baseColorTextureId();
            if(m.transmission()>0) {
                String glass=name+"glass",g="scene.materials."+glass;
                property(out,g+".type",m.roughness()<.001f?"glass":"roughglass");property(out,g+".kr","1 1 1");property(out,g+".kt",base);
                property(out,g+".interiorior",m.indexOfRefraction());property(out,g+".uroughness",Math.max(.001f,m.roughness()));property(out,g+".vroughness",Math.max(.001f,m.roughness()));
                if(m.transmission()<1) {
                    disney(out,name+"base",m,base);property(out,p+".type","mix");property(out,p+".material1",name+"base");property(out,p+".material2",glass);property(out,p+".amount",m.transmission());
                } else {property(out,p+".type",m.roughness()<.001f?"glass":"roughglass");property(out,p+".kr","1 1 1");property(out,p+".kt",base);property(out,p+".interiorior",m.indexOfRefraction());property(out,p+".uroughness",Math.max(.001f,m.roughness()));property(out,p+".vroughness",Math.max(.001f,m.roughness()));}
            } else disney(out,name,m,base);
            if(m.emissiveStrength()>0) {property(out,p+".emission",m.emissiveTextureId()<0?color(m.emissiveColorRgba8()):"tex"+m.emissiveTextureId());property(out,p+".emission.gain",gain(m.emissiveStrength()));}
            float alpha=((m.baseColorRgba8()>>>24)&255)/255f*visibility;
            if(m.blendMode()!=MaterialAsset.BlendMode.OPAQUE)property(out,p+".transparency",1-alpha);
            if(m.normalTextureId()>=0) {String n="normal"+name;property(out,"scene.textures."+n+".type","normalmap");property(out,"scene.textures."+n+".texture","tex"+m.normalTextureId());property(out,p+".bumptex",n);}
        }
        if(!shadow) {property(out,p+".transparency.shadow","1 1 1");property(out,p+".transparency.shadowoverride",true);}
        if(boundary!=null)property(out,p+".volume.interior",volume);
    }
    static void disney(StringBuilder out,String name,MaterialAsset m,String base) {
        String p="scene.materials."+name;property(out,p+".type","disney");property(out,p+".basecolor",base);property(out,p+".roughness",m.roughness());property(out,p+".metallic",m.metallic());
    }
    static void camera(StringBuilder out,CameraState c) {
        if(c.hasExactProjection())throw new IllegalArgumentException("LuxCore requires a native camera; exact Vulkan projections belong to the Vulkan provider");
        property(out,"scene.camera.type","perspective");property(out,"scene.camera.lookat.orig",c.x()+" "+c.y()+" "+c.z());
        property(out,"scene.camera.lookat.target",(c.x()+c.forwardX())+" "+(c.y()+c.forwardY())+" "+(c.z()+c.forwardZ()));
        property(out,"scene.camera.up",c.upX()+" "+c.upY()+" "+c.upZ());
        property(out,"scene.camera.fieldofview",Math.toDegrees(2*Math.atan(Math.min(c.tanHalfFovX(),c.tanHalfFovY()))));
        property(out,"scene.camera.autovolume.enable",false);property(out,"scene.camera.volume","world");
    }
    static void light(StringBuilder out,SceneLight l) {
        String p="scene.lights.l"+l.id();property(out,p+".type",switch(l.type()){case POINT->"point";case SPOT->"spot";case DIRECTIONAL->"sharpdistant";});
        property(out,p+".position",l.x()+" "+l.y()+" "+l.z());property(out,p+".direction",l.directionX()+" "+l.directionY()+" "+l.directionZ());
        property(out,p+".target",(l.x()+l.directionX())+" "+(l.y()+l.directionY())+" "+(l.z()+l.directionZ()));
        property(out,p+".color",l.red()+" "+l.green()+" "+l.blue());property(out,p+".gain",gain(l.intensity()));
        property(out,p+".coneangle",Math.toDegrees(Math.acos(l.outerConeCosine())));property(out,p+".conedeltaangle",Math.toDegrees(Math.acos(l.outerConeCosine())-Math.acos(l.innerConeCosine())));
    }
}
