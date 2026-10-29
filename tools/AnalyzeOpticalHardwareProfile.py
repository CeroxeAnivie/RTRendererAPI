"""汇总光学内核硬件计数器与 SASS 访存，严格核对实际 kernel 数并去除重复导出段。"""
import argparse
import csv
import json
from pathlib import Path


def number(value):
    return float(value.replace(',', '')) if value and value != '-' else 0.0


def analyze(directory):
    directory = Path(directory)
    with (directory / 'raw.csv').open(encoding='utf-8-sig', newline='') as stream:
        kernels = [row for row in csv.DictReader(stream) if row['ID'].isdigit()]
    groups, current, header = [], [], None
    with (directory / 'source.csv').open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.reader(stream):
            if row and row[0] == 'Kernel Name':
                if current:
                    groups.append(current)
                current, header = [], None
            elif row and row[0] == 'Address':
                header = row
            elif header and row and row[0].startswith('0x'):
                current.append(dict(zip(header, row)))
    if current:
        groups.append(current)
    exported = len(groups)
    if len(groups) == 2 * len(kernels):
        assert all(groups[i] == groups[i + 1] for i in range(0, len(groups), 2)), '非相同相邻重复段，不能自动去重'
        groups = groups[::2]
    assert len(groups) == len(kernels) > 0, 'SASS 分段与实际 kernel 数不符'
    metrics = (
        'gpu__time_duration.sum', 'gpu__dram_throughput.avg.pct_of_peak_sustained_elapsed',
        'sm__throughput.avg.pct_of_peak_sustained_elapsed', 'dram__bytes.sum.per_second',
        'sm__warps_active.avg.pct_of_peak_sustained_active', 'launch__registers_per_thread',
        'launch__stack_size', 'smsp__average_warps_issue_stalled_long_scoreboard_per_issue_active.ratio',
        'smsp__average_warp_latency_per_inst_issued.ratio',
    )
    fields = ('L2 Theoretical Sectors Global', 'L2 Theoretical Sectors Global Excessive',
              'L2 Theoretical Sectors Global Ideal', 'L2 Theoretical Sectors Local')
    rounds = []
    for kernel, instructions in zip(kernels, groups):
        totals = {key: sum(number(row.get(key)) for row in instructions) for key in fields}
        hotspots = sorted(instructions, key=lambda row: number(row.get(fields[1])), reverse=True)[:12]
        rounds.append(dict(kernelId=int(kernel['ID']), kernel=kernel['Kernel Name'],
                           metrics={key: number(kernel[key]) for key in metrics if key in kernel},
                           theoreticalSectors=totals,
                           excessiveFraction=totals[fields[1]] / totals[fields[0]],
                           largestExcessiveInstructions=[{key: row.get(key) for key in ('Address', 'Source', 'Address Space', *fields)} for row in hotspots]))
    return dict(scope='NCU 内核重放硬件定位；不代表完整帧性能或整帧占比；未锁定时钟及缓存',
                directory=str(directory.resolve()), exportedSourceSections=exported, actualKernels=len(kernels), rounds=rounds)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.directory)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for row in result['rounds']:
        print(row['kernelId'], row['kernel'], row['theoreticalSectors'], row['excessiveFraction'])
