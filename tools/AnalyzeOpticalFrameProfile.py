"""分析完整光学帧采样；GPU/API 子项包含于墙钟中，不能重复相加。"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3


def analyze(directory):
    directory = Path(directory)
    timing = json.loads((directory / 'frame-components.json').read_text(encoding='utf-8'))
    benchmark = json.loads((directory / 'benchmark.json').read_text(encoding='utf-8'))
    frames, rounds = timing['frames'], timing['exchangeRounds']
    assert frames == benchmark['completedFrames'] and rounds % frames == 0
    total = timing['fullCompletionWallNanos']
    record = timing['vulkanReleaseRecordSubmitWallNanos'] + timing['vulkanContinuationRecordSubmitWallNanos']
    post = timing['postSubmitPollTotalWallNanos'] - timing['snapshotCpuCopyWallNanos']
    buckets = {
        'CUDA交接': timing['cudaStageWallNanos'],
        'Vulkan后续栅栏等待': timing['vulkanContinuationFenceWaitWallNanos'],
        'Vulkan录制与提交': record,
        'CPU不可变HDR快照': timing['snapshotCpuCopyWallNanos'],
        '其余提交后轮询与等待': post,
    }
    buckets['其余CPU校验与调度'] = total - sum(buckets.values())
    assert min(buckets.values()) >= 0
    ranked = [dict(component=name, meanMs=value / frames / 1e6, percentOfFullFrame=value / total * 100)
              for name, value in sorted(buckets.items(), key=lambda pair: pair[1], reverse=True)]
    for rank, item in enumerate(ranked, 1):
        item['rank'] = rank
    database = sqlite3.connect(directory / 'complete-frame.sqlite')
    try:
        kernels = database.execute(
            'SELECT k.start,k.end,k.correlationId,k.registersPerThread,k.localMemoryPerThread '
            'FROM CUPTI_ACTIVITY_KIND_KERNEL k JOIN StringIds s ON k.demangledName=s.id '
            "WHERE s.value='native_optical_step' OR s.value GLOB 'native_optical_step_[0-3]' ORDER BY k.start DESC LIMIT ?", [rounds]).fetchall()[::-1]
        resets = database.execute(
            'SELECT k.start,k.end FROM CUPTI_ACTIVITY_KIND_KERNEL k JOIN StringIds s ON k.demangledName=s.id '
            "WHERE s.value='native_optical_reset' ORDER BY k.start DESC LIMIT ?", [rounds]).fetchall()[::-1]
        assert len(kernels) == len(resets) == rounds
        api = database.execute(
            'SELECT r.start,r.end,r.correlationId,r.globalTid,s.value,r.returnValue '
            'FROM CUPTI_ACTIVITY_KIND_RUNTIME r JOIN StringIds s ON r.nameId=s.id ORDER BY r.start').fetchall()
        indices = {row[2]: i for i, row in enumerate(api)}
        expected = ('cuWaitExternalSemaphoresAsync', 'cuLaunchKernel', 'cuLaunchKernel',
                    'cuStreamSynchronize', 'cuMemcpyDtoH_v2', 'cuSignalExternalSemaphoresAsync', 'cuStreamSynchronize')
        names = ('externalWaitEnqueue', 'resetKernelLaunch', 'opticalKernelLaunch',
                 'waitForProducerAndOpticalKernel', 'counterReadbackApi', 'externalSignalEnqueue', 'waitAfterSignal')
        durations = [0] * len(names)
        for kernel in kernels:
            index = indices[kernel[2]]
            sequence = api[index - 2:index + 5]
            assert tuple(row[4] for row in sequence) == expected
            assert len({row[3] for row in sequence}) == 1 and all(row[5] == 0 for row in sequence)
            for i, row in enumerate(sequence):
                durations[i] += row[1] - row[0]
        durations.append(timing['cudaStageWallNanos'] - sum(durations))
        assert durations[-1] >= 0
        api_breakdown = [dict(component=name, meanMs=value / frames / 1e6, percentOfFullFrame=value / total * 100)
                         for name, value in zip((*names, 'remainingJavaFFMContextAndScheduling'), durations)]
        gpu = {
            'cudaOpticalKernelMeanMs': sum(row[1] - row[0] for row in kernels) / frames / 1e6,
            'cudaResetKernelMeanMs': sum(row[1] - row[0] for row in resets) / frames / 1e6,
            'vulkanPrimaryMeanMs': timing['vulkanPrimaryGpuNanos'] / frames / 1e6,
            'vulkanContinuationMeanMs': timing['vulkanContinuationGpuNanos'] / frames / 1e6,
            'finalShadeAndGpuReadbackMeanMs': timing['finalShadeAndGpuReadbackNanos'] / frames / 1e6,
            'opticalRegistersPerThread': sorted({row[3] for row in kernels}),
            'opticalLocalMemoryPerThread': sorted({row[4] for row in kernels}),
        }
        per_round = [sum(row[1] - row[0] for row in kernels[i::rounds // frames]) / frames / 1e6
                     for i in range(rounds // frames)]
    finally:
        database.close()
    return dict(method='完整计时窗口；末尾内核与API关联ID逐轮匹配；含采样开销，不能替代无采样ABBA收益',
                directory=str(directory.resolve()), fullMeanMs=total / frames / 1e6, frames=frames, rounds=rounds,
                roundsPerFrame=rounds // frames, wallRanking=ranked, cudaStageApiBreakdown=api_breakdown,
                overlappingGpuEvidence=gpu, opticalKernelMeanMsByRound=per_round,
                originalThreeRanks={name: next(row['rank'] for row in ranked if row['component'] == name)
                                    for name in ('CUDA交接', 'Vulkan后续栅栏等待', 'Vulkan录制与提交')},
                finalHdrSha256=hashlib.sha256((directory / 'benchmark-final.rgba16f').read_bytes()).hexdigest(),
                rawTimings=timing, benchmark=benchmark)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = [analyze(directory) for directory in args.directories]
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for sample in result:
        print(sample['directory'], '整帧毫秒', sample['fullMeanMs'], '每帧交接', sample['roundsPerFrame'])
        for row in sample['wallRanking']:
            print(row)
        for row in sample['cudaStageApiBreakdown']:
            print(row)
