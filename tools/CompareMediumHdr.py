"""Compare frozen linear RGBA16F frames, optionally against predeclared small-error limits."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def compare(reference, candidate, bounded=False):
    a_raw, b_raw = reference.read_bytes(), candidate.read_bytes()
    if len(a_raw) != len(b_raw) or len(a_raw) % 8:
        raise ValueError(f"RGBA16F extent mismatch: {candidate}")
    a = np.frombuffer(a_raw, dtype='<f2').reshape(-1, 4).astype(np.float64)
    b = np.frombuffer(b_raw, dtype='<f2').reshape(-1, 4).astype(np.float64)
    if not np.isfinite(a).all() or not np.isfinite(b).all():
        raise ValueError(f"Non-finite HDR value: {candidate}")
    difference = b[:, :3] - a[:, :3]
    reference_energy = float(np.square(a[:, :3]).sum())
    rms = float(np.sqrt(np.square(difference).sum() / max(reference_energy, 1e-30)))
    luminance = np.array([.2126, .7152, .0722])
    light_a = float(a[:, :3].sum(axis=0) @ luminance)
    light_b = float(b[:, :3].sum(axis=0) @ luminance)
    energy_error = abs(light_b - light_a) / max(abs(light_a), 1e-30)
    p99 = float(np.quantile(np.abs(a[:, :3]), .99))
    absolute = float(np.max(np.abs(difference), initial=0))
    alpha_exact = np.array_equal(a[:, 3], b[:, 3])
    identical = a_raw == b_raw
    limits = dict(relativeRms=.001, luminanceEnergyRelativeError=.001,
                  maxAbsoluteChannelError=p99 * .005 + 1e-5)
    passed = identical if not bounded else (alpha_exact and rms <= limits['relativeRms']
        and energy_error <= limits['luminanceEnergyRelativeError']
        and absolute <= limits['maxAbsoluteChannelError'])
    return dict(file=candidate.name, bytes=len(b_raw), pixels=len(a), byteIdentical=identical,
        alphaExact=alpha_exact, relativeRms=rms, luminanceEnergyRelativeError=energy_error,
        maxAbsoluteChannelError=absolute, referenceAbsoluteP99=p99,
        changedRgbComponents=int(np.count_nonzero(difference)), limits=limits, passed=passed,
        referenceSha256=hashlib.sha256(a_raw).hexdigest(),
        candidateSha256=hashlib.sha256(b_raw).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--bounded', action='store_true')
    parser.add_argument('--reference-commit', help='Fixed mature renderer commit supporting this arithmetic policy')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.bounded and not args.reference_commit:
        parser.error('bounded comparison requires --reference-commit')
    files = sorted(args.candidate.glob('*.rgba16f'))
    if not files:
        raise ValueError('No raw HDR frames')
    if {p.name for p in files} != {p.name for p in args.reference.glob('*.rgba16f')}:
        raise ValueError('Reference/candidate frame sets differ')
    results = [compare(args.reference / p.name, p, args.bounded) for p in files]
    passed = all(r['passed'] for r in results)
    report = dict(passed=passed, mode='bounded' if args.bounded else 'byte-identical',
        supportingReferenceCommit=args.reference_commit, frames=results,
        maxRelativeRms=max(r['relativeRms'] for r in results),
        maxLuminanceEnergyRelativeError=max(r['luminanceEnergyRelativeError'] for r in results),
        maxAbsoluteChannelError=max(r['maxAbsoluteChannelError'] for r in results))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'frames'}))
    if not passed:
        raise SystemExit('HDR error limit exceeded')


if __name__ == '__main__':
    main()
