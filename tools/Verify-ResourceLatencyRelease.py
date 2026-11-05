"""Verify final archives against accepted native/shader evidence and Maven Local."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('renderer-api', 'renderer-core', 'renderer-nvidia', 'renderer-rtxpt')


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    with path.open('rb') as stream:
        return stream_digest(stream)


def stream_digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def entry_digest(archive, name):
    with archive.open(name) as stream:
        return stream_digest(stream)


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def pdb_identity(path):
    data = path.read_bytes()
    require(data.startswith(b'Microsoft C/C++ MSF 7.00'), 'Unsupported PDB format')
    block_size, _, _, directory_size, _, block_map = struct.unpack_from('<6I', data, 32)
    block_count = (directory_size + block_size - 1) // block_size
    pages = struct.unpack_from(f'<{block_count}I', data, block_map * block_size)
    directory = b''.join(data[page * block_size:(page + 1) * block_size] for page in pages)[:directory_size]
    count = struct.unpack_from('<I', directory)[0]
    sizes = struct.unpack_from(f'<{count}I', directory, 4)
    offset = 4 + count * 4
    for index, size in enumerate(sizes):
        blocks = 0 if size == 0xffffffff else (size + block_size - 1) // block_size
        pages = struct.unpack_from(f'<{blocks}I', directory, offset)
        offset += blocks * 4
        if index == 1:
            info = b''.join(data[page * block_size:(page + 1) * block_size] for page in pages)[:size]
            return info[12:28], struct.unpack_from('<I', info, 8)[0]
    raise ValueError('PDB information stream missing')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=ROOT / 'docs/quality/data/resource-latency.json')
    parser.add_argument('--record', type=Path, default=ROOT / 'docs/quality/data/resource-latency-release.json')
    parser.add_argument('--local', action='store_true')
    parser.add_argument('--allow-reused-acceptance', action='store_true',
                        help='Package-only verification with explicitly recorded historical test reuse; never a final-DLL performance pass')
    args = parser.parse_args()
    evidence = read(args.evidence)
    version = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines() if line.startswith('project_version='))
    require(evidence['version'] == version, 'Performance evidence version mismatch')
    require(evidence['correctnessPassed'] == 13, 'Incomplete correctness evidence')
    if args.allow_reused_acceptance:
        require(evidence.get('acceptancePolicy', {}).get('mode') == 'passed-tests-not-rerun', 'Missing explicit no-rerun policy')
        previous = evidence['historicalEvidence']
        historical_path = ROOT / previous['path']
        require(digest(historical_path) == previous['sha256'], 'Changed historical evidence')
        historical = read(historical_path)
        require(historical['correctnessPassed'] == 13 and historical['performancePassed'] == 36 and not historical['missing'], 'Invalid historical acceptance')
        require(evidence['performancePassed'] == evidence['finalNativePerformanceWindows'] == 0
                and evidence['performanceReused'] == 36 and not evidence['windows'], 'Reused measurements mislabeled as final-DLL acceptance')
        runtime_inputs = evidence['packagedRuntimeFilesSha256']
        require(bool(runtime_inputs), 'Missing final package input identities')
    else:
        require(evidence['performancePassed'] == evidence['performanceExpected'] == 36 and not evidence['missing'], 'Incomplete final-DLL acceptance evidence')
        require(evidence['finalNativePerformanceWindows'] == 36 and evidence['retainedPredecessorPerformanceWindows'] == 0,
                'Final release requires all 36 windows on the final native identity')
        require(all(window.get('gpuBoundaryComplete') for window in evidence['windows']),
                'Incomplete request GPU boundary in final release')
        runtime_inputs = evidence['measuredRuntimeFilesSha256']
    require(all(not window['evidenceErrors'] for window in evidence['windows']), 'Invalid performance evidence')
    require(all(check['passed'] for check in evidence.get('additionalCorrectness', {}).values()),
            'Additional correctness check failed')
    for name, sha in evidence['productionClassesBeforeFinalBuildSha256'].items():
        require(digest(ROOT / name) == sha, f'Production class changed after performance measurement: {name}')
    runtime = ROOT / 'renderer-rtxpt/build/native-bundle/rtxpt-runtime.zip'
    runtime_hash = digest(runtime)
    artifacts = []
    for module in MODULES:
        module_root = ROOT / module
        for suffix in ('', '-sources', '-javadoc'):
            path = module_root / f'build/libs/{module}-{version}{suffix}.jar'
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if not suffix:
                    require('META-INF/LICENSE' in names, f'Missing license: {path.name}')
                    classes = module_root / 'build/classes/java/main'
                    for source in classes.rglob('*.class'):
                        entry = source.relative_to(classes).as_posix()
                        require(entry_digest(archive, entry) == digest(source), f'Stale class: {module}/{entry}')
                elif suffix == '-sources':
                    source_root = module_root / 'src/main/java'
                    for source in source_root.rglob('*.java'):
                        entry = source.relative_to(source_root).as_posix()
                        require(entry_digest(archive, entry) == digest(source), f'Stale source: {module}/{entry}')
                else:
                    require('index.html' in names, f'Missing Javadoc index: {module}')
            artifacts.append({'module': module, 'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)})
        publication = module_root / 'build/publications/mavenJava'
        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
        pom = ET.parse(publication / 'pom-default.xml').getroot()
        require(pom.find('m:version', namespace).text == version, f'POM version: {module}')
        for dependency in pom.findall('m:dependencies/m:dependency', namespace):
            if dependency.find('m:groupId', namespace).text == 'top.ceroxe.rt':
                require(dependency.find('m:version', namespace).text == version, f'Cross-module POM version: {module}')
        metadata = read(publication / 'module.json')
        require(metadata['component']['version'] == version, f'Gradle metadata version: {module}')
        for variant in metadata['variants']:
            for dependency in variant.get('dependencies', []):
                if dependency['group'] == 'top.ceroxe.rt':
                    require(dependency['version'].get('requires') == version, f'Cross-module metadata version: {module}')
            for artifact in variant.get('files', []):
                path = module_root / 'build/libs' / artifact['name']
                if path.exists():
                    require(artifact['sha256'] == digest(path), f'Stale artifact metadata: {path.name}')
        for source, suffix in (('pom-default.xml', '.pom'), ('module.json', '.module')):
            path = publication / source
            artifacts.append({'module': module, 'file': f'{module}-{version}{suffix}', 'bytes': path.stat().st_size, 'sha256': digest(path)})
    with zipfile.ZipFile(ROOT / f'renderer-rtxpt/build/libs/renderer-rtxpt-{version}.jar') as archive:
        prefix = 'META-INF/native/windows-x86_64/'
        require(entry_digest(archive, prefix + 'rtxpt-runtime.zip') == runtime_hash, 'Embedded runtime differs from final archive')
        require(archive.read(prefix + 'rtxpt-runtime.sha256').decode().strip() == runtime_hash, 'Embedded runtime digest mismatch')
    with zipfile.ZipFile(runtime) as archive:
        names = set(archive.namelist())
        for entry, sha in runtime_inputs.items():
            require(entry_digest(archive, entry) == sha, f'Runtime differs from recorded native/shader input: {entry}')
        require(not any(name.lower().endswith('.pdb') for name in names), 'PDB must use the separate symbols classifier')
        manifest = json.loads(archive.read('native-lighting-sdk.json'))
        require(manifest['version'] == version, 'SDK version mismatch')
        for item in manifest['files']:
            require(entry_digest(archive, item['path']) == item['sha256'], f'SDK manifest hash mismatch: {item["path"]}')
            require(archive.getinfo(item['path']).file_size == item['bytes'], f'SDK manifest size mismatch: {item["path"]}')
        require(entry_digest(archive, 'sdk/backend/RtrNativeLighting.hlsl') == digest(ROOT / 'renderer-rtxpt/src/main/cpp/RtrNativeLighting.hlsl'), 'SDK shader source mismatch')
        require(entry_digest(archive, 'docs/quality/data/resource-latency.json') == digest(args.evidence), 'SDK performance evidence mismatch')
    with zipfile.ZipFile(ROOT / f'renderer-nvidia/build/libs/renderer-nvidia-{version}.jar') as archive:
        prefix = 'META-INF/native/windows-x86_64/'
        require(prefix + 'sl.interposer.dll' in archive.namelist(), 'Missing NVIDIA runtime payload')
        require(entry_digest(archive, prefix + 'rtrenderer_nvidia.dll') == digest(ROOT / 'renderer-nvidia/build/native/rtrenderer_nvidia.dll'), 'NVIDIA bridge mismatch')
    symbols = ROOT / f'renderer-rtxpt/build/distributions/renderer-rtxpt-{version}-native-symbols.zip'
    pdb = ROOT / 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.pdb'
    with zipfile.ZipFile(symbols) as archive:
        require(entry_digest(archive, pdb.name) == digest(pdb), 'Symbols archive PDB mismatch')
    guid, age = pdb_identity(pdb)
    dll_data = (ROOT / 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll').read_bytes()
    require(b'RSDS' + guid + struct.pack('<I', age) in dll_data, 'Native DLL/PDB CodeView identity mismatch')
    artifacts.append({'module': 'renderer-rtxpt', 'file': symbols.name, 'bytes': symbols.stat().st_size, 'sha256': digest(symbols)})
    record = {'version': version, 'runtimeZipSha256': runtime_hash, 'artifacts': artifacts,
              'measuredRuntimeFilesSha256': evidence['measuredRuntimeFilesSha256'],
              'packagedRuntimeFilesSha256': runtime_inputs,
              'acceptancePolicy': evidence.get('acceptancePolicy', {'mode': 'complete-final-DLL-matrix'}),
              'correctnessCurrentPassed': evidence.get('correctnessCurrentPassed', evidence['correctnessPassed']),
              'correctnessReused': evidence.get('correctnessReused', []), 'performanceReused': evidence.get('performanceReused', 0),
              'performanceEvidenceSha256': digest(args.evidence), 'dllPdbGuidBytes': guid.hex(), 'dllPdbAge': age,
              'correctnessPassed': evidence['correctnessPassed'], 'performancePassed': evidence['performancePassed'],
              'p95Target': evidence['target'], 'gpuBenchmarkRepeatedForPackaging': False}
    if args.local:
        require(read(args.record) == record, 'Artifacts changed after release record')
        repository = Path.home() / '.m2/repository/top/ceroxe/rt'
        for artifact in artifacts:
            local = repository / artifact['module'] / version / artifact['file']
            require(digest(local) == artifact['sha256'], f'Maven Local differs from verified package: {local}')
    else:
        args.record.parent.mkdir(parents=True, exist_ok=True)
        args.record.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'archivesVerified': True, 'mavenLocalVerified': args.local, 'artifactCount': len(artifacts), 'runtimeZipSha256': runtime_hash, 'p95Target': evidence['target']}))


if __name__ == '__main__':
    main()
