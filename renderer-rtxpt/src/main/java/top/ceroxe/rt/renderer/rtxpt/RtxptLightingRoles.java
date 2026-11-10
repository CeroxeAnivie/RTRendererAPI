package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Native finite-signal ray roles, independent of the host's instance identity. */
final class RtxptLightingRoles {
    private RtxptLightingRoles() { }
    static int restrict(int requested,int mask,boolean castsShadow) {
        if(mask!=255&&(mask&~7)!=0)throw new IllegalArgumentException("Unsupported native lighting ray mask");
        int allowed=mask==255?7:mask;
        if(!castsShadow)allowed&=~2;
        return (requested&~7)|(requested&allowed&7);
    }
    static boolean same(PrimitiveInstance a,PrimitiveInstance b) {
        return a==b||a.visibilityMask()==b.visibilityMask()&&a.castsShadow()==b.castsShadow()
            &&a.surfaceVisibility()==b.surfaceVisibility()&&a.meshAssetId()==b.meshAssetId()&&a.packedLight()==b.packedLight()
            &&a.previousTransform().equals(b.previousTransform());
    }
    static NativeLightingPublication.Instance defaults(long id,int packedLight,float visibility,MeshAsset mesh,boolean explicitBaseline) {
        if(explicitBaseline||mesh.lightmapCoordinates().hasRemaining())
            return new NativeLightingPublication.Instance(id,23,visibility,0,1,1,0,0);
        return new NativeLightingPublication.Instance(id,23,visibility,0,0,0,(packedLight&0xffff)/240f,(packedLight>>>16)/240f);
    }
    static void pack(float[] packed,int offset,NativeLightingPublication.Instance value,int mask,boolean shadow) {
        packed[offset]=restrict(value.roles(),mask,shadow);packed[offset+1]=value.visibility();packed[offset+2]=value.fog();
        packed[offset+4]=value.localScale();packed[offset+5]=value.skyScale();
        packed[offset+6]=value.localBias();packed[offset+7]=value.skyBias();
    }
    static void packHistory(float[] packed,int offset,PrimitiveInstance primitive) {
        packed[offset+3]=3; // Dynamic coverage, with an authoritative previous object-to-world transform.
        primitive.previousTransform().elements().get(packed,offset+8,12);
    }
}
