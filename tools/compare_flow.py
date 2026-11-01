"""Flow-only native volume-stage comparison, never a full-benchmark equivalent.

The original fixture remains untouched. All rendering stays in the upstream SDK;
this host only adapts density data to Flow's temperature/smoke representation.
"""
import argparse
import ctypes as ct
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import time

import numpy as np
from PIL import Image


DIFFERENCES = [
    'Volume stage only: original surface geometry, BSDFs, surface lighting, caustics and their rendering cost are excluded.',
    'Same camera and 32 bounded volume transforms; camera medium v500 is not representable and is excluded.',
    'RGB absorption/scattering reduce to scalar mean extinction and a native Flow color map; not equivalent transport.',
    'Original 4x4x4 density grids are supplied to upstream EmitterTexture; Flow resamples them to its own sparse grid.',
    'HG anisotropy, per-medium multiple scattering, volume priorities and dielectric refraction are not implemented.',
    'Source emission is mapped to the native color map, not physically equivalent participating-medium emission.',
    'Uses native Flow precomputed volume shadows with the original directional-light direction, not the full original light set.',
    'Flow uses deterministic ray marching; 1 SPP is not a parameter or an equivalent workload.',
]


def prepare(fixture_path, output):
    f = json.loads(fixture_path.read_text(encoding='utf-8'))

    def texture(value):
        try:
            nums = [float(v) for v in str(value).split()]
            if len(nums) == 1:
                nums *= 3
            return np.asarray(nums, dtype=np.float32).reshape(1, 1, 1, 3)
        except ValueError:
            pass
        t = f['textures'][value]
        if t['type'] == 'densitygrid':
            a = np.asarray([float(v) for v in t['data'].split()], dtype=np.float32)
            return a.reshape(int(t['nz']), int(t['ny']), int(t['nx']), 1)
        if t['type'] == 'scale':
            return texture(t['texture1']) * texture(t['texture2'])
        raise ValueError('Unsupported density preparation texture: ' + value)

    records, inventory = [], []
    for name, obj in f['objects'].items():
        mat = f['materials'][obj['material']]
        volume_name = mat.get('volume.interior')
        if not volume_name or volume_name == 'v500':
            continue
        vol = f['volumes'][volume_name]
        absorption = np.broadcast_to(texture(vol.get('absorption', '0 0 0')), (4, 4, 4, 3))
        scattering = np.broadcast_to(texture(vol.get('scattering', '0 0 0')), (4, 4, 4, 3))
        emission = np.broadcast_to(texture(vol.get('emission', '0 0 0')), (4, 4, 4, 3))
        density = (absorption + scattering).mean(axis=-1)
        extinction = (absorption + scattering).mean(axis=(0, 1, 2))
        albedo = np.divide(scattering.mean(axis=(0, 1, 2)), extinction,
                           out=np.zeros(3), where=extinction > 0)
        color = albedo * np.array([.12, .16, .22])
        emit = emission.mean(axis=(0, 1, 2))
        transform = [float(v) for v in obj['transformation'].split()]
        records.append(struct.pack('<86f', *(transform + density.ravel().tolist() + color.tolist() + emit.tolist())))
        inventory.append({'object': name, 'volume': volume_name, 'type': vol['type'],
                          'meanExtinction': float(density.mean()), 'maxExtinction': float(density.max()),
                          'transform': transform})
    assert len(records) < 128, len(records)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'fixture.bin').write_bytes(struct.pack('<II', 0x314f4c46, len(records)) + b''.join(records))
    metadata = {'originalFixture': str(fixture_path.resolve()),
                'originalFixtureSha256': hashlib.sha256(fixture_path.read_bytes()).hexdigest(),
                'volumeCount': len(records), 'volumes': inventory,
                'fullBenchmarkSupported': False, 'semanticDifferences': DIFFERENCES}
    (output / 'fixture.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print('Flow fixture prepared:', len(records), 'bounded volumes; full benchmark supported: false', flush=True)
    return metadata


def gpu():
    return subprocess.check_output(['nvidia-smi', '-i', '0',
        '--query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm',
        '--format=csv,noheader'], text=True).strip()


def vulkan_target():
    """Vulkan enumeration is independent of nvidia-smi/CUDA adapter indices."""
    vk = ct.WinDLL('vulkan-1.dll')
    class CreateInfo(ct.Structure):
        _fields_ = [('type',ct.c_uint32),('next',ct.c_void_p),('flags',ct.c_uint32),
                    ('app',ct.c_void_p),('layerCount',ct.c_uint32),('layers',ct.c_void_p),
                    ('extensionCount',ct.c_uint32),('extensions',ct.c_void_p)]
    vk.vkCreateInstance.argtypes=[ct.c_void_p,ct.c_void_p,ct.c_void_p]
    vk.vkEnumeratePhysicalDevices.argtypes=[ct.c_void_p,ct.c_void_p,ct.c_void_p]
    vk.vkGetPhysicalDeviceProperties.argtypes=[ct.c_void_p,ct.c_void_p]
    vk.vkDestroyInstance.argtypes=[ct.c_void_p,ct.c_void_p]
    info=CreateInfo();info.type=1;instance=ct.c_void_p()
    assert vk.vkCreateInstance(ct.byref(info),None,ct.byref(instance))==0
    try:
        count=ct.c_uint32()
        assert vk.vkEnumeratePhysicalDevices(instance,ct.byref(count),None)==0
        devices=(ct.c_void_p*count.value)()
        assert vk.vkEnumeratePhysicalDevices(instance,ct.byref(count),devices)==0
        found=[]
        for index,device in enumerate(devices):
            properties=ct.create_string_buffer(4096)
            vk.vkGetPhysicalDeviceProperties(device,properties)
            name=properties.raw[20:276].split(b'\0')[0].decode('utf-8')
            print('Vulkan device:',index,name,flush=True)
            if 'RTX 5080 Laptop' in name:
                found.append((index,name))
        assert len(found)==1, 'RTX 5080 Laptop Vulkan device is not unique'
        return found[0]
    finally:
        vk.vkDestroyInstance(instance,None)


def measure(executable, output, seconds, cells):
    rows = []
    device_index, device_name = vulkan_target()
    for cell_size in cells:
        for refresh in (False, True):
            time.sleep(1.2)
            initial = gpu()
            name = f'cell-{cell_size}-' + ('refresh' if refresh else 'cached')
            result_path = output / (name + '.json')
            (output / (name + '.gpu.txt')).write_text(initial, encoding='utf-8')
            print('Flow start:', name, initial, flush=True)
            command = [str(executable), str(output / 'fixture.bin'), str(result_path),
                       str(cell_size), str(int(refresh)), str(seconds),str(device_index)]
            with (output / (name + '.stdout.log')).open('w', encoding='utf-8') as stdout, \
                 (output / (name + '.stderr.log')).open('w', encoding='utf-8') as stderr:
                subprocess.run(command, cwd=executable.parent, stdout=stdout, stderr=stderr, check=True, timeout=180)
            result = json.loads(result_path.read_text(encoding='utf-8'))
            pixels = np.fromfile(str(result_path) + '.rgba16f', dtype='<f2').reshape(1080, 1920, 4)
            assert np.isfinite(pixels).all(), 'Non-finite Flow image'
            rgb = pixels[:, :, :3].astype(np.float32)
            background = np.array([0x2fae, 0x311f, 0x330a], dtype=np.uint16).view(np.float16).astype(np.float32)
            changed = int(np.count_nonzero(np.max(np.abs(rgb - background), axis=-1) > .001))
            metadata=json.loads((output/'fixture.json').read_text(encoding='utf-8'))
            assert metadata['volumeCount']==0 or changed > 1000, 'Empty volume render rejected'
            png = np.where(rgb <= .0031308, rgb * 12.92, 1.055 * np.maximum(rgb, 0) ** (1/2.4) - .055)
            Image.fromarray((np.clip(png, 0, 1) * 255).astype(np.uint8)).save(output / (name + '.png'))
            result.update(initialGpu=initial, verifiedChangedPixels=changed, actualDevice=device_name,semanticDifferences=DIFFERENCES)
            result_path.write_text(json.dumps(result, indent=2), encoding='utf-8')
            Path(str(result_path) + '.rgba16f').unlink()  # Derived PNG is retained; not per-frame readback.
            rows.append(result)
            print(f'FLOW RESULT {name}: {result["fps"]:.3f} FPS, {result["meanMs"]:.4f} ms, '
                  f'blocks={result["activeBlocks"]}, visible={changed}', flush=True)
    (output / 'comparison.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', type=Path, default=Path('docs/quality/data/renderer-comparison-clean-20261101/fixture.json'))
    parser.add_argument('--output', type=Path, default=Path('build/reports/flow-comparison'))
    parser.add_argument('--executable', type=Path, default=Path('build/flow-sdk/flow/_build/windows-x86_64/release/flow_native_benchmark.exe'))
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--seconds', type=float, default=2)
    parser.add_argument('--cells', type=float, nargs='+', default=[.07, .035, .0175])
    options = parser.parse_args()
    destination = options.output.resolve()
    prepare(options.fixture.resolve(), destination)
    if not options.prepare_only:
        measure(options.executable.resolve(), destination, options.seconds, options.cells)
