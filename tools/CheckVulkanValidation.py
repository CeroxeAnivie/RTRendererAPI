"""Fail a native verification run on Vulkan warnings/errors, dropped logs or missing close."""
import argparse
import json
from pathlib import Path


def check(path):
    messages = [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    failures = [row for row in messages if row.get('severity') in ('WARNING', 'ERROR')]
    closes = [row for row in messages if row.get('event') == 'closed']
    opened = sum(row.get('event') == 'opened' for row in messages)
    dropped = sum(row.get('droppedMessages', 0) for row in closes)
    passed = opened > 0 and len(closes) == opened and not failures and dropped == 0
    return dict(file=str(path), passed=passed, opened=opened, closed=len(closes),
                warningsAndErrors=len(failures), droppedMessages=dropped,
                messageIds=sorted({row.get('messageIdName', '') for row in failures}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logs', type=Path, nargs='+')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = [check(path) for path in args.logs]
    report = dict(passed=all(row['passed'] for row in result), logs=result)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered, encoding='utf-8')
    print(rendered, end='')
    raise SystemExit(0 if report['passed'] else 1)
