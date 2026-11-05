"""Verify the affected-correctness resource-chain release; never a performance gate."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_bytes', ROOT / 'tools/Verify-ResourceLatencyRelease.py')
bytes_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bytes_tool)
digest, entry_digest, pdb_identity, require = (
    bytes_tool.digest, bytes_tool.entry_digest, bytes_tool.pdb_identity, bytes_tool.require)
MODULES = bytes_tool.MODULES


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=ROOT / 'docs/quality/data/native-resource-chain.json')
    parser.add_argument('--record', type=Path, default=ROOT / 'docs/quality/data/native-resource-chain-release.json')
    parser.add_argument('--local', action='store_true')
    args = parser.parse_args()
    evidence = read(args.evidence)
    version = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                   if line.startswith('project_version='))
    require(evidence['version'] == version, 'Evidence version mismatch')
    require(evidence['acceptanceScope'] == 'affected-correctness-and-package-identity', 'Wrong release scope')
    require(evidence['performanceMeasured'] is False and evidence['performanceWindows'] == 0,
            'This gate must not imply performance acceptance')
    require(len(evidence['correctness']) == 10, 'Missing affected checks')
    for name, check in evidence['correctness'].items():
        require(check['passed'] and check['exit'] == 0 and digest(ROOT / check['log']) == check['logSha256'],
                f'Changed or failed correctness: {name}')
    reference = evidence['counterReference']
    require(reference['passed'] and reference['javaExit'] == 0 and reference['cases'] == 15
            and reference['sixCountersExact'] and reference['hdrFilesByteEqual'] == 80,
            'Incomplete direct-atomic reference')
    for name, sha in evidence['evidenceFilesSha256'].items():
        require(digest(ROOT / name) == sha, f'Changed evidence: {name}')
    for name, sha in evidence['productionSourceSha256'].items():
        require(digest(ROOT / name) == sha, f'Production source changed after acceptance: {name}')
    for name, sha in evidence['productionClassSha256'].items():
        require(digest(ROOT / name) == sha, f'Production class changed after acceptance: {name}')
    require(digest(ROOT / 'renderer-api/build/abi/renderer-api.abi') == evidence['javaAbiSha256'], 'Java ABI changed')
    runtime_inputs = evidence['acceptedRuntimeFilesSha256']
    require(runtime_inputs and runtime_inputs['rtrenderer_rtxpt.dll'] == evidence['nativeDllSha256'], 'Missing accepted runtime')
    for name, sha in runtime_inputs.items():
        require(digest(ROOT / 'build/rtxpt-sdk/bin' / name) == sha, f'Default runtime changed: {name}')
    artifacts = []
    for module in MODULES:
        module_root = ROOT / module
        for suffix in ('', '-sources', '-javadoc'):
            path = module_root / f'build/libs/{module}-{version}{suffix}.jar'
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if not suffix:
                    require('META-INF/LICENSE' in names, f'Missing license: {module}')
                    classes = module_root / 'build/classes/java/main'
                    expected = {p.relative_to(classes).as_posix(): digest(p) for p in classes.rglob('*.class')}
                    require(expected and {n for n in names if n.endswith('.class')} == set(expected),
                            f'Class inventory mismatch: {module}')
                    for name, sha in expected.items():
                        require(entry_digest(archive, name) == sha, f'Stale class: {module}/{name}')
                elif suffix == '-sources':
                    source_root = module_root / 'src/main/java'
                    for source in source_root.rglob('*.java'):
                        name = source.relative_to(source_root).as_posix()
                        require(entry_digest(archive, name) == digest(source), f'Stale source: {module}/{name}')
                else:
                    require('index.html' in names, f'Missing Javadoc index: {module}')
            artifacts.append({'module': module, 'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)})
        publication = module_root / 'build/publications/mavenJava'
        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
        pom = ET.parse(publication / 'pom-default.xml').getroot()
        require(pom.find('m:version', namespace).text == version, f'POM version: {module}')
        for dependency in pom.findall('m:dependencies/m:dependency', namespace):
            if dependency.find('m:groupId', namespace).text == 'top.ceroxe.rt':
                require(dependency.find('m:version', namespace).text == version, f'POM dependency: {module}')
        metadata = read(publication / 'module.json')
        require(metadata['component']['version'] == version, f'Module version: {module}')
        for variant in metadata['variants']:
            for dependency in variant.get('dependencies', []):
                if dependency['group'] == 'top.ceroxe.rt':
                    require(dependency['version'].get('requires') == version, f'Module dependency: {module}')
            for artifact in variant.get('files', []):
                path = module_root / 'build/libs' / artifact['name']
                require(path.is_file() and artifact['sha256'] == digest(path), f'Module artifact: {module}/{path.name}')
        for source, suffix in (('pom-default.xml', '.pom'), ('module.json', '.module')):
            path = publication / source
            artifacts.append({'module': module, 'file': f'{module}-{version}{suffix}', 'bytes': path.stat().st_size, 'sha256': digest(path)})
    runtime = ROOT / 'renderer-rtxpt/build/native-bundle/rtxpt-runtime.zip'
    runtime_sha = digest(runtime)
    with zipfile.ZipFile(ROOT / f'renderer-rtxpt/build/libs/renderer-rtxpt-{version}.jar') as archive:
        prefix = 'META-INF/native/windows-x86_64/'
        require(entry_digest(archive, prefix + 'rtxpt-runtime.zip') == runtime_sha, 'Embedded runtime mismatch')
        require(archive.read(prefix + 'rtxpt-runtime.sha256').decode().strip() == runtime_sha, 'Embedded runtime digest mismatch')
    with zipfile.ZipFile(runtime) as archive:
        names = set(archive.namelist())
        require(not any(n.lower().endswith('.pdb') for n in names), 'PDB belongs in symbols classifier')
        for name, sha in runtime_inputs.items():
            require(entry_digest(archive, name) == sha, f'Packaged runtime input changed: {name}')
        sdk_manifest = json.loads(archive.read('native-lighting-sdk.json'))
        require(sdk_manifest['version'] == version, 'SDK version mismatch')
        for item in sdk_manifest['files']:
            require(entry_digest(archive, item['path']) == item['sha256']
                    and archive.getinfo(item['path']).file_size == item['bytes'], f'SDK file: {item["path"]}')
        abi_inventory = ''.join(sorted(f'{item["abiPath"]}={item["sha256"]}\n'
                                       for item in sdk_manifest['files'] if item.get('abiPath')))
        require(hashlib.sha256(abi_inventory.encode('utf-8')).hexdigest() == sdk_manifest['shaderAbiSha256'],
                'SDK shader ABI inventory mismatch')
        shader = 'renderer-rtxpt/src/main/cpp/RtrNativeLighting.hlsl'
        require(entry_digest(archive, 'sdk/backend/RtrNativeLighting.hlsl') == digest(ROOT / shader), 'SDK shader source mismatch')
        require(entry_digest(archive, args.evidence.resolve().relative_to(ROOT).as_posix()) == digest(args.evidence), 'SDK evidence mismatch')
        require(entry_digest(archive, f'docs/release-{version}.md') == digest(ROOT / f'docs/release-{version}.md'), 'SDK release note mismatch')
    nvidia = ROOT / f'renderer-nvidia/build/libs/renderer-nvidia-{version}.jar'
    with zipfile.ZipFile(nvidia) as archive:
        prefix = 'META-INF/native/windows-x86_64/'
        manifest = archive.read(prefix + 'runtime-files.sha256').decode('utf-8')
        inventory = {}
        for line in manifest.splitlines():
            sha, name = line.split('\t', 1)
            inventory[name] = sha
            require(entry_digest(archive, prefix + name) == sha, f'NVIDIA payload changed: {name}')
        require({'rtrenderer_nvidia.dll', 'sl.interposer.dll'} <= inventory.keys(), 'Missing native NVIDIA payload')
        require(inventory['rtrenderer_nvidia.dll'] == digest(ROOT / 'renderer-nvidia/build/native/rtrenderer_nvidia.dll'), 'NVIDIA bridge mismatch')
    symbols = ROOT / f'renderer-rtxpt/build/distributions/renderer-rtxpt-{version}-native-symbols.zip'
    pdb = ROOT / 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.pdb'
    with zipfile.ZipFile(symbols) as archive:
        require(entry_digest(archive, pdb.name) == digest(pdb) == evidence['nativePdbSha256'], 'Symbols PDB mismatch')
    guid, age = pdb_identity(pdb)
    require(b'RSDS' + guid + struct.pack('<I', age) in (ROOT / 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll').read_bytes(), 'DLL/PDB CodeView mismatch')
    require(guid.hex() == evidence['dllPdbGuidBytes'] and age == evidence['dllPdbAge'], 'Accepted PDB identity mismatch')
    artifacts.append({'module': 'renderer-rtxpt', 'file': symbols.name, 'bytes': symbols.stat().st_size, 'sha256': digest(symbols)})
    record = {'version': version, 'acceptanceScope': evidence['acceptanceScope'], 'performanceMeasured': False,
              'correctnessPassed': len(evidence['correctness']), 'directAtomicCases': 15, 'directAtomicHdrFiles': 80,
              'runtimeZipSha256': runtime_sha, 'nativeDllSha256': evidence['nativeDllSha256'],
              'nativePdbSha256': digest(pdb), 'dllPdbGuidBytes': guid.hex(), 'dllPdbAge': age,
              'evidenceSha256': digest(args.evidence), 'shaderAbiSha256': sdk_manifest['shaderAbiSha256'],
              'artifacts': artifacts}
    if args.local:
        require(read(args.record) == record, 'Package changed after record')
        repository = Path.home() / '.m2/repository/top/ceroxe/rt'
        for artifact in artifacts:
            local = repository / artifact['module'] / version / artifact['file']
            require(digest(local) == artifact['sha256'], f'Maven Local differs: {local}')
    else:
        args.record.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'archivesVerified': True, 'mavenLocalVerified': args.local, 'artifactCount': len(artifacts),
                      'correctnessPassed': 10, 'performanceMeasured': False, 'runtimeZipSha256': runtime_sha}))


if __name__ == '__main__':
    main()
