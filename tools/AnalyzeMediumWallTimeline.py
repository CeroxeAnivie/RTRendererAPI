"""Validate raw nested wall intervals and rank exclusive boundary time in ms."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def analyze(directory, filenames=None):
    groups, files, threads = {}, [], set()
    wall = frames = events = dropped = 0
    paths = ([directory / name for name in filenames] if filenames is not None
             else sorted(directory.glob('*wall-timeline-round-*.json')))
    if not paths:
        raise ValueError(f'No wall timeline rounds in {directory}')
    for path in paths:
        raw = path.read_bytes()
        report = json.loads(raw.decode('utf-8-sig'))
        intervals = report['rawIntervals']
        names = report['stageNames']
        children = [[] for _ in intervals]
        labels, self_times = [], []
        for index, (kind, parent, start, duration, thread) in enumerate(intervals):
            if start < 0 or duration < 0 or not -1 <= parent < index:
                raise ValueError(f'Invalid interval {path}:{index}')
            if (index == 0) != (parent == -1):
                raise ValueError('Exactly one root interval is required')
            labels.append(names[kind] if parent < 0 else labels[parent] + '/' + names[kind])
            self_times.append(duration)
            threads.add(thread)
            if parent >= 0:
                outer = intervals[parent]
                if start < outer[2] or start + duration > outer[2] + outer[3]:
                    raise ValueError(f'Child escapes parent {path}:{index}')
                self_times[parent] -= duration
                children[parent].append((start, start + duration))
        for siblings in children:
            ordered = sorted(siblings)
            if any(left[1] > right[0] for left, right in zip(ordered, ordered[1:])):
                raise ValueError(f'Overlapping siblings in {path}')
        if min(self_times) < 0 or sum(self_times) != intervals[0][3]:
            raise ValueError(f'Exclusive times do not partition wall in {path}')
        for label, own, interval in zip(labels, self_times, intervals):
            group = groups.setdefault(label, {'inclusive': 0, 'self': []})
            group['inclusive'] += interval[3]
            group['self'].append(own)
        frames += report['frames']
        events += len(intervals)
        dropped += report['droppedEvents']
        wall += intervals[0][3]
        files.append({'file': path.name, 'sha256': hashlib.sha256(raw).hexdigest()})
    ranked = []
    for label, group in sorted(groups.items(), key=lambda pair: sum(pair[1]['self']), reverse=True):
        values = sorted(group['self'])
        total = sum(values)
        ranked.append(dict(rank=len(ranked) + 1, path=label, count=len(values),
            selfMsPerFrame=total / frames / 1e6, selfMsPerSecond=total * 1000 / wall,
            selfPercentWall=total * 100 / wall, selfTotalMs=total / 1e6,
            inclusiveMsPerFrame=group['inclusive'] / frames / 1e6,
            selfMeanMs=total / len(values) / 1e6, selfP50Ms=values[(len(values) - 1) // 2] / 1e6,
            selfP95Ms=values[math.ceil(len(values) * .95) - 1] / 1e6, selfMaxMs=values[-1] / 1e6))
    return dict(interpretation='Exclusive host boundary times partition wall; waits enclose GPU work. Do not add GPU work to this table.',
        frames=frames, wallMs=wall / 1e6, msPerFrame=wall / frames / 1e6, fps=frames * 1e9 / wall,
        events=events, droppedEvents=dropped, threadsObserved=len(threads),
        coveragePercent=100, uncoveredMs=0, intervalContainmentAndNonoverlapVerified=True,
        rawFiles=files, rankedHostBoundaries=ranked)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.directory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with args.output.with_suffix('.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=result['rankedHostBoundaries'][0].keys())
        writer.writeheader()
        writer.writerows(result['rankedHostBoundaries'])
    print(f"{result['frames']} frames, {result['msPerFrame']:.6f} ms/frame, {result['events']} events, "
          f"{result['droppedEvents']} dropped; exclusive wall sum = 1000 ms/second")
