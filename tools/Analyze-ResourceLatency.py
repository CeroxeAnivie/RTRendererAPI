"""Analyze every accepted request and independent GPU interval without rendering again."""
import csv
import json
import hashlib
from pathlib import Path
import argparse
import importlib.util

spec=importlib.util.spec_from_file_location('previous',Path(__file__).with_name('Analyze-HighFramerate.py'))
previous=importlib.util.module_from_spec(spec);spec.loader.exec_module(previous)
distribution=previous.distribution

def analyze(path):
    summary=json.loads(path.read_text(encoding='utf-8-sig'))
    with path.with_suffix('.csv').open(encoding='utf-8-sig',newline='') as f: rows=list(csv.DictReader(f))
    changed=lambda r:any(int(r.get(k,0)) for k in ('meshes','textures','added','removed'))
    groups={
        'all':rows,
        'updates':[r for r in rows if changed(r)],
        'steady':[r for r in rows if not changed(r)],
        'local':[r for r in rows if 0<int(r['meshes'])<=4 and not int(r.get('added',0))],
        'texture':[r for r in rows if int(r['textures'])>0 and not int(r['meshes']) and not int(r.get('added',0))],
        'inflow':[r for r in rows if int(r.get('added',0)) or int(r.get('removed',0))],
    }
    cpu={}
    for category,subset in groups.items():
        cpu[category]={
            'requests':len(subset),'deferredAttempts':sum(int(r['deferred']) for r in subset),
            'requestUntilAccepted':distribution((int(r['applyNanos'])+int(r['callNanos']))/1e6 for r in subset),
            'synchronousAttempts':distribution((int(r['applyNanos'])+int(r['attemptNanos']))/1e6 for r in subset),
            'withOutputRelease':distribution((int(r['applyNanos'])+int(r['callNanos'])+int(r['releaseNanos']))/1e6 for r in subset),
            'acceptedCall':distribution(int(r['acceptedNanos'])/1e6 for r in subset),
        }
    gpu_path=path.with_name(path.stem+'-full-gpu.csv')
    with gpu_path.open(encoding='utf-8-sig',newline='') as f: samples=list(csv.DictReader(f))
    identities={int(r['sequence']):r for r in rows}
    full=distribution(int(r['fullGpuNanos'])/1e6 for r in samples)
    render=distribution(int(r.get('renderGpuNanos',r['fullGpuNanos']))/1e6 for r in samples)
    request_boundary=summary.get('gpuBoundary')=='request-before-admission-through-output'
    boundary_complete=request_boundary and all(int(r.get('requestBoundaryComplete',0))==1 for r in samples)
    sequences_by_kind={kind:{int(r['sequence']) for r in subset} for kind,subset in groups.items()}
    by_kind={kind:distribution(int(s['fullGpuNanos'])/1e6 for s in samples if int(s['sequence']) in sequences) for kind,sequences in sequences_by_kind.items()}
    status_path=path.with_name(path.stem+'-timing-status.json')
    runtime_path=path.with_name(path.stem+'-runtime.json')
    status=json.loads(status_path.read_text(encoding='utf-8-sig')) if status_path.exists() else {}
    runtime=json.loads(runtime_path.read_text(encoding='utf-8-sig')) if runtime_path.exists() else {}
    baseline_utilization=None
    if runtime.get('gpuBaselineAvailable'):
        try:baseline_utilization=float(next(csv.reader([runtime['gpuBefore']]))[2].strip().rstrip('%').strip())
        except (ValueError,IndexError,StopIteration):pass
    diagnostic_only=bool(summary.get('diagnosticGiOnly',False))
    absolute_eligible=not diagnostic_only and baseline_utilization is not None and baseline_utilization<=10
    inputs=[path,path.with_suffix('.csv'),gpu_path]+([runtime_path] if runtime_path.exists() else [])
    errors=[]
    if not summary['signalReuseDisabled']:errors.append('Signal reuse was not explicitly disabled')
    if len(samples)!=len(rows):errors.append('Missing independent full GPU samples')
    if len(identities)!=len(rows) or len({int(s['sequence']) for s in samples})!=len(samples):errors.append('Duplicate request or GPU sequence')
    if any(int(s['sequence']) not in identities or int(s['revision'])!=int(identities[int(s['sequence'])]['revision']) for s in samples):errors.append('GPU request identity mismatch')
    if any(int(s['mode'])!={'OFF':0,'SUMMARY':1,'DEEP':2}[summary['diagnostics']] for s in samples):errors.append('GPU mode mismatch')
    if status.get('droppedQueries',0):errors.append('Independent GPU queries were dropped')
    if request_boundary and not boundary_complete:errors.append('A formal GPU sample lacks the complete request boundary')
    if any(int(r['fullGpuNanos'])<int(r.get('renderGpuNanos',r['fullGpuNanos'])) for r in samples):errors.append('Request GPU interval is shorter than its contained render interval')
    scope_path=path.with_name(path.stem+'-gpu.json')
    contained_resource_scopes=0
    if request_boundary and summary['diagnostics']=='DEEP' and scope_path.exists():
        scopes=json.loads(scope_path.read_text(encoding='utf-8-sig'))
        requests={r['sequence']:r for r in scopes if r['name']=='NativeLightingRequestFull' and r['sequence'] in identities}
        for scope in scopes:
            if scope['sequence'] not in identities or scope['name'] not in ('ResourceBlasBuild','ResourceBlasRefit','ResourceBlasClone','TLAS Update'):continue
            request=requests.get(scope['sequence'])
            if not request or scope['queue']!=request['queue'] or scope['frequency']!=request['frequency'] or scope['start']<request['start'] or scope['end']>request['end']:
                errors.append('Resource GPU scope is outside its complete request: '+scope['name']+' sequence='+str(scope['sequence']))
            else:contained_resource_scopes+=1
        inputs.append(scope_path)
    return {'run':summary,'cpu':cpu,'fullGpu':full,'renderGpu':render,'gpuBoundaryComplete':boundary_complete,'fullGpuByKind':by_kind,'timingStatus':status,'runtime':runtime,'evidenceErrors':errors,
        'containedResourceGpuScopes':contained_resource_scopes,
        'gpuBaselineUtilizationPercent':baseline_utilization,'absoluteTargetEligible':absolute_eligible,
        'diagnosticOnly':diagnostic_only,
        'p95ObservedBelowTarget':not diagnostic_only and boundary_complete and full.get('p95Ms',float('inf'))<=4 and not errors,
        'p95TargetMet':boundary_complete and absolute_eligible and full.get('p95Ms',float('inf'))<=4 and not errors,
        'inputSha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
        'boundary':('Independent request GPU interval starts before admission and native resource commits, ends after output handoff, and retains the previous render interval separately. ' if boundary_complete else 'Legacy render-to-output GPU interval excludes earlier resource-build submissions and is ineligible for complete-request 4 ms acceptance. ')
            +'No product ray atomics in OFF/SUMMARY. CPU requestUntilAccepted includes apply and all retry pauses; initialization and final drain are recorded separately. API fixture is not MC acceptance.'}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('summary',type=Path);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    result=analyze(args.summary);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'fps':result['run']['fps'],'fullGpu':result['fullGpu'],'errors':result['evidenceErrors']},ensure_ascii=False))
    if result['evidenceErrors']:raise SystemExit(1)
