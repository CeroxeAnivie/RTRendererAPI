package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Common expert protocol; the upstream owns all rendering and rebuild work. */
final class RtxptFeatureController implements RendererFeatureController {
    interface Host { boolean initialized(); void apply(RendererFeatureProfile p); RendererFeatureProfile effective(RendererFeatureProfile p); RendererFeatureProfile normalize(RendererFeatureProfile p); void ready(); }
    private final Host host;
    private RendererFeatureProfile profile;
    private long generation,planned,applied,rejected;
    private RendererFeaturePlan latest;
    private RendererFeatureApplyResult result;
    RtxptFeatureController(Host host,RendererFeatureProfile profile){this.host=host;this.profile=profile;RtxptOptions.resolve(profile);}
    @Override public RendererFeatureProfile effectiveProfile(){host.ready();var actual=host.effective(profile);if(!actual.equals(profile)){profile=actual;generation++;}return profile;}
    @Override public RendererFeaturePlan plan(RendererFeatureProfile target){
        host.ready();target=host.normalize(Objects.requireNonNull(target));RtxptOptions.resolve(target);
        boolean same=profile.equals(target);
        boolean streamControlOnly=profile.toBuilder().lowLatency(target.lowLatency()).frameGeneration(target.frameGeneration()).build().equals(target);
        var disposition=same?RendererFeaturePlan.Disposition.UNCHANGED:!host.initialized()||streamControlOnly?
            RendererFeaturePlan.Disposition.APPLICABLE:RendererFeaturePlan.Disposition.REQUIRES_RENDERER_REBUILD;
        latest=new RendererFeaturePlan(++planned,generation,profile,target,disposition,
            disposition==RendererFeaturePlan.Disposition.REQUIRES_RENDERER_REBUILD?RendererFeaturePlan.Boundary.RENDERER_REBUILD:RendererFeaturePlan.Boundary.NEXT_FRAME,
            same?"Already effective":!host.initialized()?"Apply common API policy before native initialization":streamControlOnly?"Streamline generation and Reflex changes apply on the next native frame":"Recreate the native host with this profile; no hidden shader/device rebuild");
        result=null;return latest;
    }
    @Override public RendererFeatureApplyResult apply(RendererFeaturePlan plan){
        host.ready();Objects.requireNonNull(plan);
        if(plan!=latest || result!=null || plan.expectedGeneration()!=generation)
            return new RendererFeatureApplyResult(plan.id(),generation,RendererFeatureApplyResult.Outcome.STALE_PLAN,profile,"Plan is foreign, superseded or consumed");
        var outcome=RendererFeatureApplyResult.Outcome.UNCHANGED;
        if(plan.disposition()==RendererFeaturePlan.Disposition.REQUIRES_RENDERER_REBUILD){outcome=RendererFeatureApplyResult.Outcome.REQUIRES_RENDERER_REBUILD;rejected++;}
        else if(plan.disposition()==RendererFeaturePlan.Disposition.APPLICABLE){host.apply(plan.target());profile=plan.target();generation++;applied++;outcome=RendererFeatureApplyResult.Outcome.APPLIED;}
        return result=new RendererFeatureApplyResult(plan.id(),generation,outcome,profile,plan.reason());
    }
    @Override public RendererFeatureControlDiagnostics featureControlDiagnostics(){host.ready();return new RendererFeatureControlDiagnostics(generation,profile,planned,applied,rejected,Optional.ofNullable(latest),Optional.ofNullable(result));}
}
