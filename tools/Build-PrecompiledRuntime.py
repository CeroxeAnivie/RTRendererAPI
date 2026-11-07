"""Build relocatable, checked RTXPT cache inputs; never runs in a consumer JVM.

DXC debug metadata supplies the actual compiler options. Reconstructed commands
must reproduce the original upstream cache hash before any entry is accepted.
Only the public, non-semantic material permutation debug label is canonicalized.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess


def sha(data):
    return hashlib.sha256(data).hexdigest()


def llvm_text(value):
    return re.sub(r'\\([0-9a-fA-F]{2})', lambda m: chr(int(m[1], 16)), value)


def metadata(text, label):
    ref = re.search(r'!dx.source.' + label + r' = !\{!(\d+)\}', text)
    if not ref:
        raise ValueError('Missing DXC debug metadata: ' + label)
    line = re.search(r'^!' + ref[1] + r' = !\{(.*)\}$', text, re.M)
    return [llvm_text(v) for v in re.findall(r'!"((?:[^"\\]|\\[0-9a-fA-F]{2})*)"', line[1])]


def command(source, args):
    # DXC moves target/entry to the front of its recorded args, and appends a
    # second copy of defines after -Fo. Restore the pinned upstream builder's
    # exact order, then verify its hash against the already compiled filename.
    args = args[:args.index('-Fo')] if '-Fo' in args else args[:]
    target = args[args.index('-T') + 1]
    entry = args[args.index('-E') + 1] if '-E' in args else None
    remaining = []
    i = 0
    while i < len(args):
        if args[i] in ('-T', '-E'):
            i += 2
        else:
            remaining.append(args[i]); i += 1
    result = ' "' + source + '"'
    i = 0
    target_written = False
    while i < len(remaining):
        token = remaining[i]
        if token in ('-D', '-I') and not target_written:
            result += ' -T ' + target
            if entry and not target.startswith('lib_'):
                result += ' -E ' + entry
            target_written = True
        result += ' ' + token
        if token in ('-D', '-I'):
            result += ' ' + ('"' + remaining[i+1] + '"' if token == '-I' else remaining[i+1])
            i += 1
        i += 1
    if not target_written:
        raise ValueError('Unrecognized upstream DXC option order')
    return result


def macros(args):
    result = {}
    for i, value in enumerate(args[:-1]):
        if value == '-D':
            name, _, definition = args[i+1].partition('=')
            if name in result and result[name] != definition:
                raise ValueError('Conflicting macro ' + name)
            result[name] = definition
    return result


def global_names(sdk):
    sample = (sdk/'Rtxpt/Sample.cpp').read_text(encoding='utf-8-sig')
    block = sample.split('void Sample::GetPTPipelineGlobalMacros(', 1)[1].split('\nextern ', 1)[0]
    names = re.findall(r'macros.push_back\(\{\s*"([A-Z0-9_]+)"', block)
    lights = (sdk/'Rtxpt/Lighting/LightsBaker.cpp').read_text(encoding='utf-8-sig')
    block = lights.split('void LightsBaker::SetGlobalShaderMacros(', 1)[1].split('\n}', 1)[0]
    names += re.findall(r'macros.push_back\(\{\s*"([A-Z0-9_]+)"', block)
    return sorted(set(names))


def build(runtime, sdk, compiler, output, plan_only=False):
    checked_plan = Path(__file__).with_name('rtxpt-precompiled-commands.json')
    if checked_plan.exists():
        return build_plan(runtime, compiler, output, checked_plan, plan_only)
    cache = runtime/'ShaderDynamic/Bin/dxil'
    output.mkdir(parents=True, exist_ok=True)
    debug = output/'metadata'
    debug.mkdir(exist_ok=True)
    names = global_names(sdk)
    entries, globals_, skipped, plans = {}, set(), [], {}
    candidates = sorted(cache.rglob('*.bin'))
    if not candidates:
        raise ValueError('No official runtime shader cache inputs')
    for number, binary in enumerate(candidates, 1):
        meta = debug/(sha(binary.read_bytes()) + '.txt')
        if not meta.exists():
            dumped = subprocess.run([str(compiler), '-dumpbin', str(binary)], capture_output=True, check=True)
            meta.write_bytes(dumped.stdout)
        text = meta.read_text(encoding='utf-8-sig')
        try:
            source = metadata(text, 'mainFileName')[0]
            args = metadata(text, 'args')
            raw = command(source, args)
            old_hash = binary.stem.rsplit('_', 1)[-1]
            if sha(raw.encode())[:len(old_hash)] != old_hash:
                raise ValueError('Original command hash cannot be reconstructed')
            root_match = re.search(r'^(.*?)\\ShaderDynamic\\Source\\Rtxpt\\', source, re.I)
            if not root_match:
                raise ValueError('Not a runtime-relative upstream source')
            old_root = root_match[1]
            raw = raw.replace(old_root, str(runtime))
            defs = macros(args)
            if source.endswith('PathTracerMaterialSpecializations.hlsl') and 'RTXPT_SHADER_ID' in defs:
                # -1 is the separate upstream fallback/ubershader. Other
                # historical specialized indices retain their real identity.
                if (defs['RTXPT_SHADER_ID'] == '0'
                        and defs.get('RTXPT_MATERIAL_PERMUTATIONS_ENABLED') == '1'
                        and defs.get('ActiveBSDF') == 'StandardBSDF'):
                    label = defs['RTXPT_MATERIAL_PERMUTATION_NAME'].split('_', 1)[0] + '_RtrStandard'
                    raw = raw.replace('RTXPT_MATERIAL_PERMUTATION_NAME='+defs['RTXPT_MATERIAL_PERMUTATION_NAME'],
                                      'RTXPT_MATERIAL_PERMUTATION_NAME='+label)
            key = sha(raw.encode())[:24]
            relative = binary.relative_to(cache)
            prefix = relative.as_posix().rsplit('_', 1)[0]
            alias = output/'compiled'/(prefix+'_'+key+'.bin')
            if all(name in defs for name in names):
                signature = ''.join(name+'='+defs[name]+'\n' for name in names)
                globals_.add(sha(signature.encode()))
            if not alias.exists() and not plan_only:
                # Build-time only, single process, unchanged upstream source and flags.
                # Parse the known emitted command using the Windows argv parser.
                import ctypes
                shell = ctypes.WinDLL('shell32', use_last_error=True)
                shell.CommandLineToArgvW.restype = ctypes.POINTER(ctypes.c_wchar_p)
                shell.CommandLineToArgvW.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
                kernel = ctypes.WinDLL('kernel32', use_last_error=True)
                kernel.LocalFree.argtypes = [ctypes.c_void_p]
                kernel.LocalFree.restype = ctypes.c_void_p
                count = ctypes.c_int()
                argv = shell.CommandLineToArgvW('dxc.exe'+raw, ctypes.byref(count))
                try:
                    values = [argv[i] for i in range(1, count.value)]
                finally:
                    kernel.LocalFree(ctypes.cast(argv, ctypes.c_void_p))
                alias.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run([str(compiler), *values, '-Fo', str(alias)], check=True)
            template = raw.replace(str(runtime), '${BIN}')
            if '${BIN}' not in template or old_root in template and old_root != str(runtime):
                raise ValueError('Non-relocatable command')
            payload = alias if alias.exists() else binary
            entries[(prefix, template)] = (payload, sha(payload.read_bytes()))
            plans[(prefix, template)] = dict(prefix=prefix, command=template)
        except (ValueError, IndexError) as failure:
            skipped.append({'file': str(binary), 'reason': str(failure)})
        if number % 25 == 0:
            print('PRECOMPILED_BUILD', number, '/', len(candidates), 'accepted=', len(entries), flush=True)
    report = dict(inputs=len(candidates), entries=len(entries), global_profiles=len(globals_),
                  compiler_sha256=sha(compiler.read_bytes()), skipped=skipped, plan_only=plan_only)
    (output/'report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    if skipped:
        raise ValueError('Unverified runtime cache inputs; see ' + str(output/'report.json'))
    (output/'commands.json').write_text(json.dumps(dict(format='rtr-precompiled-build-v1',
        upstream_commit='303b408ab81790a5051abd7573830b7a4112eb8e',
        compiler_sha256=sha(compiler.read_bytes()), global_names=names,
        global_profiles=sorted(globals_), commands=list(plans.values())), indent=2)+'\n', encoding='utf-8')
    if plan_only:
        print(json.dumps(report)); return
    if not entries or not globals_:
        raise ValueError('Empty compiled runtime catalogue')
    catalogue = runtime/'PrecompiledShaders'
    blobs = catalogue/'blobs'
    blobs.mkdir(parents=True, exist_ok=True)
    lines = ['rtr-precompiled-runtime-v1']
    import shutil
    for (prefix, template), (payload, digest) in sorted(entries.items()):
        target = blobs/(digest+'.bin')
        if not target.exists():
            shutil.copyfile(payload, target)
        lines.append('\t'.join((prefix, digest, base64.b64encode(template.encode()).decode())))
    (catalogue/'index.tsv').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    (catalogue/'globals.txt').write_text('rtr-precompiled-globals-v1\n'+';'.join(names)+'\n'+
                                      '\n'.join(sorted(globals_))+'\n', encoding='utf-8')
    print('PRECOMPILED_RUNTIME_BUILD_OK', json.dumps(report), flush=True)


def build_plan(runtime, compiler, output, checked_plan, plan_only):
    """A clean checkout builds from checked commands, never from a warm cache."""
    import ctypes
    import shutil
    plan = json.loads(checked_plan.read_text(encoding='utf-8'))
    if plan['format'] != 'rtr-precompiled-build-v1' or sha(compiler.read_bytes()) != plan['compiler_sha256']:
        raise ValueError('Precompiled shader build tool identity mismatch')
    if not plan['commands'] or not plan['global_profiles']:
        raise ValueError('Empty checked precompiled shader build plan')
    source_files = sorted((runtime/'ShaderDynamic/Source').rglob('*'))
    source_stamp = sha(''.join(str(p.relative_to(runtime))+':'+sha(p.read_bytes())+'\n'
                              for p in source_files if p.is_file()).encode())
    shell = ctypes.WinDLL('shell32', use_last_error=True)
    shell.CommandLineToArgvW.restype = ctypes.POINTER(ctypes.c_wchar_p)
    shell.CommandLineToArgvW.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    catalogue = runtime/'PrecompiledShaders'
    blobs = catalogue/'blobs'
    compiled = output/'checked-compiled'
    if not plan_only:
        compiled.mkdir(parents=True, exist_ok=True)
        blobs.mkdir(parents=True, exist_ok=True)
    lines = ['rtr-precompiled-runtime-v1']
    for number, item in enumerate(plan['commands'], 1):
        prefix, template = item['prefix'], item['command']
        if not re.fullmatch(r'[A-Za-z0-9_/]+', prefix) or '${BIN}' not in template:
            raise ValueError('Invalid checked precompiled command')
        raw = template.replace('${BIN}', str(runtime))
        key = sha((raw+source_stamp+plan['compiler_sha256']).encode())
        target = compiled/(key+'.bin')
        if not target.exists() and not plan_only:
            count = ctypes.c_int()
            argv = shell.CommandLineToArgvW('dxc.exe'+raw, ctypes.byref(count))
            if not argv:
                raise OSError(ctypes.get_last_error(), 'Cannot parse checked DXC command')
            try:
                values = [argv[i] for i in range(1, count.value)]
            finally:
                kernel.LocalFree(ctypes.cast(argv, ctypes.c_void_p))
            subprocess.run([str(compiler), *values, '-Fo', str(target)], check=True)
        if not plan_only:
            digest = sha(target.read_bytes())
            blob = blobs/(digest+'.bin')
            if not blob.exists() or sha(blob.read_bytes()) != digest:
                shutil.copyfile(target, blob)
            lines.append('\t'.join((prefix, digest, base64.b64encode(template.encode()).decode())))
        if number % 25 == 0:
            print('PRECOMPILED_CHECKED_BUILD', number, '/', len(plan['commands']), flush=True)
    if not plan_only:
        (catalogue/'index.tsv').write_text('\n'.join(lines)+'\n', encoding='utf-8')
        (catalogue/'globals.txt').write_text('rtr-precompiled-globals-v1\n'+';'.join(plan['global_names'])+'\n'+
            '\n'.join(plan['global_profiles'])+'\n', encoding='utf-8')
        # Source integrity makes timestamp rebasing safe: edited sources must not
        # silently consume binaries from a different build.
        source_lines = ['rtr-precompiled-sources-v1']
        for p in source_files:
            if p.is_file():
                source_lines.append(sha(p.read_bytes())+'\t'+p.relative_to(runtime).as_posix())
        (catalogue/'sources.tsv').write_text('\n'.join(source_lines)+'\n', encoding='utf-8')
    report = dict(entries=len(plan['commands']), global_profiles=len(plan['global_profiles']),
                  source_sha256=source_stamp, compiler_sha256=plan['compiler_sha256'], plan_only=plan_only)
    output.mkdir(parents=True, exist_ok=True)
    (output/'report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print('PRECOMPILED_CHECKED_RUNTIME_OK', json.dumps(report), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--compiler', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--plan-only', action='store_true')
    a = parser.parse_args()
    build(a.runtime.resolve(), a.sdk.resolve(), a.compiler.resolve(), a.output.resolve(), a.plan_only)
