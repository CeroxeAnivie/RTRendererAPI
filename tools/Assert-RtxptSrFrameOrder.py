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
assert "if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser())" in function
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
