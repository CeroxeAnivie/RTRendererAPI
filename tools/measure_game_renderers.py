"""Serial native-engine measurements of the bounded game workload.

All timings retain their actual completion/throughput definitions. An unsupported
engine feature remains a declared difference, never an equal-quality speedup.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time


ROOT = Path(__file__).resolve().parent.parent
PYTHON = ROOT/'build/renderer-comparison-venv/Scripts/python.exe'


def run(command, log, timeout=360):
    with log.open('w',encoding='utf-8') as out:
        result=subprocess.run(list(map(str,command)),cwd=ROOT,stdout=out,stderr=subprocess.STDOUT,timeout=timeout)
    if result.returncode:
        print(log.read_text(encoding='utf-8',errors='replace')[-4000:],flush=True)
        raise RuntimeError('Engine failed: '+str(log))


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--output',type=Path,default=ROOT/'build/reports/game-renderer-comparison')
    p.add_argument('--engines',nargs='+',default=['flow','cycles','mitsuba','luxcore'])
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    rows=[]
    def record(row):
        rows.append(row)
        (out/'comparison.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
        print('GAME RESULT',json.dumps(row),flush=True)
    for engine in a.engines:
        for case in ['medium-off','medium-on']:
            fixture=out/'scene'/case/'fixture.json'
            dest=out/engine/case;dest.mkdir(parents=True,exist_ok=True)
            print('GAME START',engine,case,flush=True)
            if engine=='flow':
                run([PYTHON,'tools/compare_flow.py','--fixture',fixture,'--output',dest,'--cells','.07','--seconds','2'],dest/'run.log')
                for row in json.loads((dest/'comparison.json').read_text(encoding='utf-8')):
                    record(dict(engine=engine,case=case,**row))
            elif engine=='cycles':
                run([ROOT/'build/renderer-comparison-sdk/blender-4.5.9-windows-x64/blender.exe',
                     '--background','--factory-startup','--python-exit-code','1','--python','tools/compare_cycles.py',
                     '--','--fixture',fixture,'--case','mixed','--output',dest],dest/'run.log')
                r=json.loads((dest/'mixed.json').read_text(encoding='utf-8'))
                record(dict(engine=engine,case=case,fps=r['independent1SppFps'],
                            meanMs=1000/r['independent1SppFps'],samplingThroughput=r['batch64SppPerSecond'],
                            initialGpu=r['initialGpu'],metric='independent completed 1-SPP calls',
                            semanticDifferences=r['semanticDifferences']))
            elif engine=='mitsuba':
                run([PYTHON,'tools/compare_renderers.py','--fixture',fixture,'--output',dest,
                     '--case','mixed','--seconds','3','--rounds','12'],dest/'run.log')
                r=json.loads((dest/'mixed.json').read_text(encoding='utf-8'))
                record(dict(engine=engine,case=case,**r))
            elif engine=='luxcore':
                for mode in ['RTPATHOCL','PATHOCL','TILEPATHOCL']:
                    time.sleep(1.2)
                    gpu=subprocess.check_output(['nvidia-smi','-i','0','--query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm','--format=csv,noheader'],text=True).strip()
                    result=dest/(mode+'.json')
                    run([ROOT/'renderer-luxcore/build/native/luxcore_native_benchmark.exe',
                         fixture.parent/'render.cfg',ROOT/'build/luxcore-full/LuxCore/bin',mode,result,32,2,3],dest/(mode+'.log'))
                    r=json.loads(result.read_text(encoding='utf-8'))
                    fps=sum(x['sampleDelta'] for x in r['rounds'])/r['pixels']/sum(x['wallMs'] for x in r['rounds'])*1000
                    record(dict(engine=engine,mode=mode,case=case,fps=fps,meanMs=1000/fps if fps else None,
                                initialGpu=gpu,metric='progressive completed samples / pixels / wall time',rounds=r['rounds']))
            else:
                raise ValueError(engine)


if __name__=='__main__':
    main()
