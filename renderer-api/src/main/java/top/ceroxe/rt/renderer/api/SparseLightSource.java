package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * A compact, finite-support source, without SceneGraph leaves or face expansion.
 * Positions use the scene's world coordinate system. RGB is linear, nonnegative.
 * BOX uses the nearest point on its axis-aligned extent as its effective origin;
 * it is a declared spatial light-field model, rather than a sampled area emitter.
 * PHYSICAL_SUM adds all supported contributions. VISIBLE_GROUP_MAX selects the
 * largest luminance after RGB visibility within each group (lower ID breaks ties).
 * VISIBLE_GROUP_CHANNEL_MAX takes the maximum independently per RGB channel,
 * after visibility. Each (composition, group) forms a separate bucket; buckets
 * are then added. No composition truncates the candidate set. Layer identifies
 * static baseline sources and additional dynamic sources for lighting correction.
 */
public record SparseLightSource(long id,Shape shape,double x,double y,double z,
        float halfX,float halfY,float halfZ,float directionX,float directionY,float directionZ,
        float red,float green,float blue,float intensity,float range,float falloffExponent,
        float innerConeCosine,float outerConeCosine,Composition composition,int group,boolean castsShadow,Layer layer,Attenuation attenuation,Visibility visibility) {
    public enum Shape { POINT, BOX, SPOT }
    public enum Composition { PHYSICAL_SUM, VISIBLE_GROUP_MAX, VISIBLE_GROUP_CHANNEL_MAX }
    public enum Layer { STATIC, DYNAMIC }
    /** Finite inverse-square field, or intensity * max(1 - L1(nearest-point delta)/range, 0). */
    public enum Attenuation { INVERSE_SQUARE_FINITE, MANHATTAN_LINEAR }
    /** Opt-in center visibility fields, with attenuation still measured to the
     * nearest AABB point. V1 uses world-unit tolerances: support +.005, endpoint
     * inset .004, shading-normal origin offset .003, ray minimum .001, hemisphere
     * cutoff .0001. BOX stops at the center ray's near AABB boundary and treats
     * points in its expanded AABB as visible. SPHERE treats the common half extent
     * as radius, uses distance-radius-.004 (at least .002) and direct visibility
     * at distance<=radius+.005. No instance/occluder is globally excluded.
     * NEAREST_POINT preserves the original behavior and does not use these rules. */
    public enum Visibility { NEAREST_POINT, CENTER_BOX_SUPPORT_V1, CENTER_SPHERE_SUPPORT_V1 }
    public SparseLightSource {
        MaterialAsset.requireId(id,"source id");Objects.requireNonNull(shape);Objects.requireNonNull(composition);Objects.requireNonNull(layer);Objects.requireNonNull(attenuation);Objects.requireNonNull(visibility);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>Float.MAX_VALUE/4||Math.abs(y)>Float.MAX_VALUE/4||Math.abs(z)>Float.MAX_VALUE/4)
            throw new IllegalArgumentException("Source position must fit finite GPU world coordinates");
        for(float v:new float[]{halfX,halfY,halfZ,red,green,blue,intensity})
            if(!Float.isFinite(v)||v<0)throw new IllegalArgumentException("Source extent, RGB and intensity must be finite and nonnegative");
        if(!Float.isFinite(range)||range<=0||range>Float.MAX_VALUE/8||!Float.isFinite(falloffExponent)||falloffExponent<=0||falloffExponent>32)
            throw new IllegalArgumentException("Source range must be positive and falloff exponent in (0,32]");
        if(group<0||group>=32)throw new IllegalArgumentException("Source group must be in [0,31]");
        double length=(double)directionX*directionX+(double)directionY*directionY+(double)directionZ*directionZ;
        if(!Double.isFinite(length)||Math.abs(length-1)>.001||!Float.isFinite(innerConeCosine)||!Float.isFinite(outerConeCosine)
                ||innerConeCosine>1||outerConeCosine< -1||innerConeCosine<outerConeCosine)
            throw new IllegalArgumentException("Source direction must be normalized and cone cosines ordered in [-1,1]");
        if(shape!=Shape.BOX&&(halfX!=0||halfY!=0||halfZ!=0))throw new IllegalArgumentException("Only BOX has a spatial extent");
        if(visibility!=Visibility.NEAREST_POINT&&shape==Shape.SPOT)throw new IllegalArgumentException("Center support visibility applies to POINT or BOX");
        if(visibility==Visibility.CENTER_SPHERE_SUPPORT_V1&&(halfX!=halfY||halfX!=halfZ))throw new IllegalArgumentException("Sphere visibility requires equal BOX half extents");
        double bound=(double)range+Math.max(halfX,Math.max(halfY,halfZ));
        if(bound>Float.MAX_VALUE/4)throw new IllegalArgumentException("Source support bounds overflow");
    }
    public SparseLightSource(long id,Shape shape,double x,double y,double z,
            float halfX,float halfY,float halfZ,float directionX,float directionY,float directionZ,
            float red,float green,float blue,float intensity,float range,float falloffExponent,
            float innerConeCosine,float outerConeCosine,Composition composition,int group,boolean castsShadow,Layer layer,Attenuation attenuation) {
        this(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,intensity,range,
            falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,layer,attenuation,Visibility.NEAREST_POINT);
    }
    public SparseLightSource(long id,Shape shape,double x,double y,double z,
            float halfX,float halfY,float halfZ,float directionX,float directionY,float directionZ,
            float red,float green,float blue,float intensity,float range,float falloffExponent,
            float innerConeCosine,float outerConeCosine,Composition composition,int group,boolean castsShadow,Layer layer) {
        this(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,intensity,range,
            falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,layer,Attenuation.INVERSE_SQUARE_FINITE);
    }
    public SparseLightSource(long id,Shape shape,double x,double y,double z,
            float halfX,float halfY,float halfZ,float directionX,float directionY,float directionZ,
            float red,float green,float blue,float intensity,float range,float falloffExponent,
            float innerConeCosine,float outerConeCosine,Composition composition,int group,boolean castsShadow) {
        this(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,intensity,range,
            falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,Layer.STATIC);
    }
    public SparseLightSource withLayer(Layer value) {
        return new SparseLightSource(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,
            intensity,range,falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,value,attenuation,visibility);
    }
    public SparseLightSource withComposition(Composition value,int group) {
        return new SparseLightSource(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,
            intensity,range,falloffExponent,innerConeCosine,outerConeCosine,value,group,castsShadow,layer,attenuation,visibility);
    }
    public SparseLightSource withAttenuation(Attenuation value) {
        return new SparseLightSource(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,
            intensity,range,falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,layer,value,visibility);
    }
    public SparseLightSource withVisibility(Visibility value) {
        return new SparseLightSource(id,shape,x,y,z,halfX,halfY,halfZ,directionX,directionY,directionZ,red,green,blue,
            intensity,range,falloffExponent,innerConeCosine,outerConeCosine,composition,group,castsShadow,layer,attenuation,value);
    }
    public static SparseLightSource point(long id,double x,double y,double z,float red,float green,float blue,float intensity,float range) {
        return new SparseLightSource(id,Shape.POINT,x,y,z,0,0,0,0,-1,0,red,green,blue,intensity,range,2,1,-1,Composition.PHYSICAL_SUM,0,true);
    }
}
