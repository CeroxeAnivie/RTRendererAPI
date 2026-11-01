"""Check SR's current-color dependency in source and measured GPU commands."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument("--source",type=Path,default=Path("build/rtxpt-sdk/Rtxpt/Sample.cpp"))
parser.add_argument("--profile",type=Path)
args=parser.parse_args()
source=args.source.read_text(encoding="utf-8-sig")
function=source.split("void Sample::PostProcessAA(",1)[1].split("void Sample::DenoisedScreenshot",1)[0]
merge=function.index("PostProcess::ComputePassType::NoDenoiserFinalMerge")
assert merge<function.index("copyTexture("), "No-AA copy must read current merged color"
assert merge<function.index("TemporalResolve("), "TAA must read current merged color"
assert merge<function.index("TagResourcesDLSSNIS("), "SR tags must follow current-color production"
assert merge<function.index("EvaluateDLSS("), "SR must read current merged color"
assert function.count("PostProcess::ComputePassType::NoDenoiserFinalMerge")==1, "Only one merge is allowed"
assert "if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser()" in function
assert "!m_ui.ActualUseRTXDIPasses()" in function, "A late merge must not erase RTXDI's accumulated light"
advanced=args.source.with_name("AdvancedSample.cpp").read_text(encoding="utf-8-sig")
pathtrace=advanced.split("void AdvancedPathTracer::PathTrace(",1)[1]
assert pathtrace.count("PostProcess::ComputePassType::NoDenoiserFinalMerge")==1
assert pathtrace.index("PostProcess::ComputePassType::NoDenoiserFinalMerge")<pathtrace.index("m_rtxdiPass->Execute("), "Initialize raw color before RTXDI adds lighting"
assert "!m_ui.ActualUseStandaloneDenoiser() && m_ui.ActualUseRTXDIPasses()" in pathtrace
nee=args.source.parent.joinpath("Shaders/PathTracer/PathTracerNEE.hlsli").read_text(encoding="utf-8-sig")
assert "candidateSample.Direction = lightSample.Position;" in nee
assert "candidateSample.Distance = 1e6;" in nee
assert "PolymorphicLightType::kDirectional" in nee, "General NEE must match upstream directional sample semantics"
strata=Path("renderer-rtxpt/src/main/cpp/RtrLightSamplerStrata.hlsli").read_text(encoding="utf-8-sig")
assert "ControlBuffer[0].TriangleLightCount != 0" in strata
assert "ControlBuffer[0].LightClusterCount != 0" in strata
assert "RtrEnvironmentLocalProxyCount" in strata and "RtrSampleEnvironmentLocal" in strata
assert "proxyCountPerLight / float(totalProxyCount)" in args.source.parent.joinpath(
    "Shaders/PathTracer/Lighting/LightSampler.hlsli").read_text(encoding="utf-8-sig")
light_sampler=args.source.parent.joinpath("Shaders/PathTracer/Lighting/LightSampler.hlsli").read_text(encoding="utf-8-sig")
assert "RTR conditional local MIS PDF fallback" in light_sampler
assert "uint packedValue = LocalLightBinarySearch" in light_sampler
assert "RTR preserve native feedback PDF semantics" in light_sampler
assert "feedbackPdf = RtrNativeGlobalPdfForFeedback(lightIndex)" in light_sampler
assert "localEnvironmentProxyCount == 0" in nee and "globalCount += localCount" in nee
assert "if (lightSampler.RtrSplitPointEnvironment())" in nee
assert "lightSampler.RtrEnvironmentProxyCount()" in nee
assert "lightSampler.RtrEnvironmentLocalProxyCount()" in nee
assert "RTR conditional NEE sample budget" in nee
assert nee.count("localEnvironmentProxyCount == 0") == 1
assert "const bool updateNeeAtFeedback" in nee
assert "updateNeeAtFeedback && lightSample.LightIndex" in nee
assert "workingContext, true); // RTR sampled candidates train NEE-AT" in nee
small_nee=args.source.parent.joinpath("Shaders/PathTracer/RtrSmallPointNee.hlsli").read_text(encoding="utf-8-sig")
assert "context, false); // Exact lights do not train the stochastic sampler." in small_nee
assert "environmentLightProxies) / float(environmentProxyCount)" in args.source.parent.joinpath(
    "Shaders/PathTracer/Lighting/LightSampler.hlsli").read_text(encoding="utf-8-sig")
result={"source_contract":"PASS","gpu_contract":"not_requested"}
if args.profile:
    frames=defaultdict(dict)
    with args.profile.open(encoding="utf-8-sig",newline="") as file:
        rows=csv.DictReader(line for line in file if not line.startswith("#"))
        for row in rows:
            if row["name"] in ("DLSS","NoDenoiserFinalMerge","DLSS-RR"):
                frames[(row["frame"],row["queue"])][row["name"]]=row
    assert frames, "No sampled GPU stages"
    for key,stages in frames.items():
        assert "DLSS-RR" not in stages, f"RR ran in SR comparison: {key}"
        assert "DLSS" in stages and "NoDenoiserFinalMerge" in stages, f"Missing producer or consumer: {key}"
        assert int(stages["NoDenoiserFinalMerge"]["end_tick"])<=int(stages["DLSS"]["begin_tick"]), f"SR consumed stale color: {key}"
    result.update(gpu_contract="PASS",sampled_frames=len(frames),profile=str(args.profile))
print(json.dumps(result,ensure_ascii=False))
