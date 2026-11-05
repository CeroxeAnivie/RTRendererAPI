"""Collect completed acceptance receipts and raw evidence without running the GPU."""
import argparse
import csv
import hashlib
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('resource_analysis', Path(__file__).with_name('Analyze-ResourceLatency.py'))
ANALYSIS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYSIS)
COUNTS = (719, 977, 4096)
SCENARIOS = ('stable', 'animation', 'local', 'stream')
LEVELS = ('OFF', 'SUMMARY', 'DEEP')
CHECKS = ('native-diagnostics', 'native-fault-and-retirement', 'java-diagnostics',
          'RtxptHighFramerateResourcesSelfTest', 'RtxptLightingInstrumentationNativeSelfTest',
          'RtxptSparseQueryOrderingNativeSelfTest', 'RtxptHighFramerateCompositionNativeSelfTest',
          'RtxptResourceLifecycleNativeSelfTest', 'RtxptNativeLightingGlSelfTest',
          'matrix-719', 'matrix-977', 'matrix-4096', 'RtxptResourceRemovalNativeSelfTest')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def fields(value):
    return {key: int(number) for key, number in re.findall(r'(\w+)=(-?\d+)', value)}


def collect_reused(directory, historical_path):
    """Preserve prior passes when the user forbids reruns; never certify new measurements."""
    directory = directory.resolve()
    historical = read(historical_path)
    if historical['correctnessPassed'] != 13 or historical['performancePassed'] != 36 or historical['missing']:
        raise ValueError('Historical evidence is incomplete')
    receipts = read(directory / 'receipts.json')
    checks = {}
    for key in CHECKS:
        current = receipts.get(key, {})
        value = dict(current if current.get('passed') else historical['correctness'][key])
        value['provenance'] = 'current-release-run' if current.get('passed') else 'reused-historical-pass'
        value['logSha256'] = digest(Path(value['log']))
        checks[key] = value
    additional = {key: dict(value, provenance='current-release-run', logSha256=digest(Path(value['log'])))
                  for key, value in receipts.items() if key not in CHECKS
                  and key not in ('native-build', 'java-build') and not key.startswith('production-')}
    if any(not value['passed'] for value in additional.values()):
        raise ValueError('Unresolved current correctness failure')
    runtime = ROOT / 'build/rtxpt-sdk/bin'
    files = sorted(list(runtime.glob('*.dll')) + list((runtime / 'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses').glob('*.bin')))
    structured = {}
    for name in ('lifecycle.json', 'resource-removal.json'):
        path = directory / name
        if path.exists():
            structured[name] = {'sha256': digest(path), 'data': read(path), 'provenance': 'current-release-run'}
    failures = [{'file': path.relative_to(ROOT).as_posix(), 'sha256': digest(path)}
                for path in sorted(directory.glob('*-failed-*')) if path.is_file()]
    return {'version': historical['version'], 'correctness': checks, 'additionalCorrectness': additional,
        'correctnessPassed': sum(value['passed'] for value in checks.values()),
        'correctnessCurrentPassed': sum(value['provenance'] == 'current-release-run' for value in checks.values()) + len(additional),
        'correctnessReused': [key for key, value in checks.items() if value['provenance'] == 'reused-historical-pass'],
        'structuredCorrectnessEvidence': structured, 'preservedCurrentFailures': failures,
        'acceptancePolicy': {'mode': 'passed-tests-not-rerun',
            'authorization': '用户明确要求：已经跑通过的测试，禁止再跑。仅失败项允许诊断复验；发布物文件校验照常执行。',
            'date': '2026-11-05', 'performanceOptimizationStopped': True},
        'historicalEvidence': {'path': historical_path.relative_to(ROOT).as_posix(), 'sha256': digest(historical_path)},
        'performancePassed': 0, 'performanceExpected': 36, 'performanceReused': 36,
        'performanceCountMeaning': 'Zero new final-DLL performance windows. The 36 historical windows retain their original identities and legacy GPU boundary; they are references, not final-DLL certification.',
        'finalNativePerformanceWindows': 0, 'retainedPredecessorPerformanceWindows': 36, 'windows': [],
        'missing': ['Final-identity performance matrix not rerun under the user instruction'],
        'measuredRuntimeFilesSha256': {},
        'packagedRuntimeFilesSha256': {path.relative_to(runtime).as_posix(): digest(path) for path in files},
        'productionClassesBeforeFinalBuildSha256': read(directory / 'production-classes-before-final-build.json'),
        'completeRequestReference': {'path': 'docs/quality/data/tail-latency-review.json',
            'sha256': digest(ROOT / 'docs/quality/data/tail-latency-review.json'),
            'nativeDllSha256': '80803215305918e7113e043cd365cc5714f2ee96564bbf42beef823f7470c749',
            'fullGpuP95Ms': 5.400128, 'finalPackagedNativeMeasured': False},
        'target': {'fullGpuP95Ms': 4, 'eligibleWindows': 0, 'passedWindows': 0,
            'currentReleaseMeasured': False, 'referenceTargetMet': False},
        'currentTimeoutReview': {'test': 'RtxptAttributeTopologyNativeSelfTest', 'firstResult': 'timeout-after-300-seconds',
            'diagnosticRetry': 'passed-with-JFR-no-assertion-change', 'retryWallSeconds': 63.630,
            'productionCodeChanged': False, 'firstTimeoutCause': 'unknown'}}


def collect(directory):
    receipts = read(directory / 'receipts.json')
    results = {}
    retention = read(ROOT / 'docs/quality/data/resource-latency-retained.json')
    runtime_files = None
    for name, receipt in receipts.items():
        match = re.fullmatch(r'production-(\d+)-(OFF|SUMMARY|DEEP)-(stable|animation|local|stream)-([a-f0-9]+)', name)
        if not match or not receipt['passed']:
            continue
        count, level, scenario, identity = match.groups()
        key = (int(count), scenario, level)
        if key in results:
            raise ValueError(f'Duplicate successful performance window: {key}')
        path = directory / (name + '.json')
        analysis = ANALYSIS.analyze(path)
        if analysis['evidenceErrors']:
            raise ValueError((name, analysis['evidenceErrors']))
        run, runtime = analysis['run'], analysis['runtime']
        if not run['formalWindow'] or not run['independentGpuTiming'] or run['beautyFrames']:
            raise ValueError(f'Non-production or incomplete window: {name}')
        if not analysis['gpuBoundaryComplete']:
            raise ValueError(f'Final release window lacks the complete request GPU boundary: {name}')
        if run['frames'] != analysis['fullGpu']['samples'] or 'actual=DLSS_RR' not in run['rr']:
            raise ValueError(f'Missing GPU samples or real RR: {name}')
        files = {Path(file).relative_to(ROOT / 'build/rtxpt-sdk/bin').as_posix(): value.lower()
                 for file, value in runtime['files'].items()}
        retained = name in retention['windows']
        if retained:
            if files['rtrenderer_rtxpt.dll'] != retention['nativeDllSha256']:
                raise ValueError('Retained predecessor DLL identity mismatch')
        else:
            if runtime_files is None:
                runtime_files = files
            elif files != runtime_files:
                raise ValueError('Final native/shader inputs changed across new windows')
        with path.with_suffix('.csv').open(encoding='utf-8-sig', newline='') as stream:
            rows = list(csv.DictReader(stream))
        slowest = sorted(rows, key=lambda row: int(row['applyNanos']) + int(row['callNanos']), reverse=True)[:5]
        pressure_path = directory / (name + '-pressure.json')
        pressure = [fields(value) for value in read(pressure_path)]
        pressure_summary = {
            'samples': len(pressure),
            'maxObserved': {field: max(sample[field] for sample in pressure) for field in pressure[0]} if pressure else {},
            'lastObserved': pressure[-1] if pressure else {},
            'boundary': 'Sampled pressure maxima may miss between-sample peaks; peak counters retain their native lifetime meaning. OFF has no diagnostic samples.'}
        formal = {int(row['sequence']) for row in rows}
        scopes_path = directory / (name + '-gpu.json')
        scopes = read(scopes_path)
        names = sorted({scope['name'] for scope in scopes})
        gpu_scopes = {scope_name: ANALYSIS.distribution(scope['ms'] for scope in scopes
                       if scope['name'] == scope_name and scope['sequence'] in formal) for scope_name in names}
        raw = [path, path.with_suffix('.csv'), directory / (name + '-full-gpu.csv'),
               directory / (name + '-runtime.json'), directory / (name + '-timing-status.json'), pressure_path, scopes_path]
        results[key] = {
            'meshes': int(count), 'scenario': scenario, 'level': level, 'measurementIdentity': identity,
            'retainedPredecessorWindow': retained,
            'measuredRuntimeFilesSha256': files,
            'rawStem': name, 'receipt': receipt, 'run': run, 'fullGpu': analysis['fullGpu'],
            'renderGpu': analysis['renderGpu'], 'gpuBoundaryComplete': analysis['gpuBoundaryComplete'],
            'fullGpuByKind': analysis['fullGpuByKind'], 'cpu': analysis['cpu'], 'pressure': pressure_summary,
            'gpuScopes': gpu_scopes, 'slowestRequests': [{key: int(value) for key, value in row.items()} for row in slowest],
            'timingStatus': analysis['timingStatus'], 'gpuBefore': runtime['gpuBefore'],
            'absoluteTargetEligible': analysis['absoluteTargetEligible'], 'p95TargetMet': analysis['p95TargetMet'],
            'evidenceErrors': analysis['evidenceErrors'], 'rawSha256': {file.name: digest(file) for file in raw}}
    expected = [(count, scenario, level) for count in COUNTS for scenario in SCENARIOS for level in LEVELS]
    windows = [results[key] for key in expected if key in results]
    if runtime_files:
        for window in windows:
            measured = window['measuredRuntimeFilesSha256']
            changed = {key for key in set(measured) | set(runtime_files) if measured.get(key) != runtime_files.get(key)}
            if changed and (not window['retainedPredecessorWindow'] or changed != {'rtrenderer_rtxpt.dll'}):
                raise ValueError('Retained window differs beyond the reviewed native lifetime fix')
    comparisons = []
    for count in COUNTS:
        for scenario in SCENARIOS:
            off = results.get((count, scenario, 'OFF'))
            if not off:
                continue
            for level in ('SUMMARY', 'DEEP'):
                other = results.get((count, scenario, level))
                if other:
                    comparisons.append({'meshes': count, 'scenario': scenario, 'level': level,
                        'fpsChangePercentFromOff': (other['run']['fps'] / off['run']['fps'] - 1) * 100,
                        'gpuP95ChangePercentFromOff': (other['fullGpu']['p95Ms'] / off['fullGpu']['p95Ms'] - 1) * 100})
    checks = {key: dict(receipts.get(key, {'passed': False})) for key in CHECKS}
    additional_checks = {key: dict(value) for key, value in receipts.items()
                         if key not in CHECKS and not key.startswith('production-')
                         and key not in ('native-build', 'java-build')}
    for name, check in (checks | additional_checks).items():
        if check['passed']:
            log = Path(check['log'])
            if not log.is_file():
                raise ValueError(f'Missing correctness log: {name}')
            check['logSha256'] = digest(log)
    structured_evidence = {}
    for name in ('lifecycle.json', 'matrix-719.json', 'matrix-977.json', 'matrix-4096.json', 'resource-removal.json'):
        path = directory / name
        if path.is_file():
            structured_evidence[name] = {'sha256': digest(path), 'data': read(path)}
    cpu_budgets = {'steady': {'p50Ms': 1, 'p95Ms': 2},
                   'ordinaryLocalUpdate': {'p50Ms': 2, 'p95Ms': 4, 'p99Ms': 8}}
    cpu_targets = []
    for window in windows:
        if window['scenario'] not in ('stable', 'local'):
            continue
        kind = 'steady' if window['scenario'] == 'stable' else 'ordinaryLocalUpdate'
        group = 'steady' if kind == 'steady' else 'updates'
        measured = window['cpu'][group]['requestUntilAccepted']
        observed = bool(measured.get('samples')) and all(
            measured.get(key, float('inf')) <= limit for key, limit in cpu_budgets[kind].items())
        cpu_targets.append({'meshes': window['meshes'], 'level': window['level'], 'kind': kind,
            'measured': measured, 'budget': cpu_budgets[kind], 'observedWithinBudget': observed,
            'absoluteTargetEligible': window['absoluteTargetEligible'],
            'targetMet': observed and window['absoluteTargetEligible']})
    version = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines() if line.startswith('project_version='))
    return {'version': version, 'correctness': checks, 'additionalCorrectness': additional_checks,
        'structuredCorrectnessEvidence': structured_evidence,
        'correctnessPassed': sum(bool(value['passed']) for value in checks.values()),
        'performancePassed': len(windows), 'performanceExpected': len(expected),
        'performanceCountMeaning': 'Completed windows with valid evidence; budget targets are reported separately.',
        'missing': [list(key) for key in expected if key not in results],
        'measuredRuntimeFilesSha256': runtime_files or {}, 'windows': windows, 'modeComparisons': comparisons,
        'productionClassesBeforeFinalBuildSha256': read(directory / 'production-classes-before-final-build.json'),
        'finalNativePerformanceWindows': sum(not window['retainedPredecessorWindow'] for window in windows),
        'retainedPredecessorPerformanceWindows': sum(window['retainedPredecessorWindow'] for window in windows),
        'target': {'fullGpuP95Ms': 4, 'eligibleWindows': sum(window['absoluteTargetEligible'] for window in windows),
                   'passedWindows': sum(window['p95TargetMet'] for window in windows)},
        'cpuTargets': cpu_targets,
        'analyzerSha256': digest(Path(ANALYSIS.__file__)),
        'retainedEvidence': retention,
        'historicalReference': read(ROOT / 'docs/quality/data/resource-latency-baseline.json'),
        'boundary': '1280x800 dual HDR; deterministic GI 2 samples, distance 6, strength .35; real RR; signal reuse disabled. Full-GPU intervals include handoff and possible queue supply gaps. API fixture is not MC acceptance. No FG/MFG.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'build/reports/resource-latency')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/quality/data/resource-latency.json')
    parser.add_argument('--require-complete', action='store_true')
    parser.add_argument('--reuse-reviewed-evidence', type=Path,
                        help='Explicitly preserve historical passes under a user no-rerun instruction; does not certify final-DLL performance')
    args = parser.parse_args()
    if args.reuse_reviewed_evidence and args.require_complete:
        raise SystemExit('Reused evidence cannot satisfy --require-complete final-DLL acceptance')
    result = (collect_reused(args.directory, args.reuse_reviewed_evidence.resolve())
              if args.reuse_reviewed_evidence else collect(args.directory))
    if args.require_complete and (result['missing'] or result['correctnessPassed'] != len(CHECKS)):
        raise SystemExit(f"Incomplete: {result['correctnessPassed']}/{len(CHECKS)} correctness; {result['performancePassed']}/36 performance")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('correctnessPassed', 'performancePassed', 'target', 'missing')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
