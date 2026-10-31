"""Summarize captured >=64 KiB virtual commits, accounting for release/decommit.

The trace is a partial observation starting at attachment. Values describe only
observed committed intervals, not complete process memory or a proven leak.
"""
import argparse
import collections
import json
import pathlib
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    args = parser.parse_args()
    directory = args.directory
    modules = {}
    clock = None
    for line in (directory / "allocations.jsonl").read_text(encoding="utf-8").splitlines():
        value = json.loads(line)["message"].get("payload", {})
        for module in value.get("modules", []):
            modules[int(module["base"], 16)] = (module["size"], module["name"])
        if "tick" in value:
            clock = value

    def symbol(address):
        for base, (size, name) in modules.items():
            if base <= address < base + size:
                return name + "+" + hex(address - base)
        return hex(address)

    # Disjoint intervals retain the stack that first committed those pages.
    live = []
    traffic = 0
    peak = 0
    timeline = []
    binary = (directory / "native-commit.bin").read_bytes()
    record = struct.Struct("<IIiIQQQII96Q")
    for offset in range(0, len(binary) - record.size + 1, record.size):
        kind, thread, status, flags, base, size, allocation, count, tick, *frames = record.unpack_from(binary, offset)
        if kind == 1:
            stack = tuple(symbol(address) for address in frames[:count])
            traffic += size
            missing = [(base, base + size)]
            for begin, end, *_ in live:
                missing = [(a, b) for lo, hi in missing for a, b in
                           ((lo, min(hi, begin)), (max(lo, end), hi)) if a < b]
            live.extend((lo, hi, allocation, thread, stack) for lo, hi in missing)
        elif kind == 2:
            if flags & 0x8000:  # MEM_RELEASE frees every interval in the reservation.
                live = [entry for entry in live if entry[2] != base]
            elif flags & 0x4000:  # MEM_DECOMMIT can split a previously observed interval.
                live = [(a, b, *entry[2:]) for entry in live for a, b in
                        ((entry[0], min(entry[1], base)), (max(entry[0], base + size), entry[1])) if a < b]
        retained = sum(hi - lo for lo, hi, *_ in live)
        peak = max(peak, retained)
        if tick and clock:
            delta = (tick - clock["tick"] + 2**31) % 2**32 - 2**31
            second = int((clock["epochMillis"] + delta) // 1000)
            if not timeline or timeline[-1][0] != second:
                timeline.append([second, retained])
            else:
                timeline[-1][1] = retained
    grouped = collections.Counter()
    for lo, hi, allocation, thread, stack in live:
        grouped[(thread, stack)] += hi - lo
    result = {
        "coverage": "Observed >=64 KiB commits since attachment; frees included; end is probe detachment",
        "records": len(binary) // record.size,
        "partialRecordBytes": len(binary) % record.size,
        "allocationTrafficBytes": traffic,
        "observedPeakRetainedBytes": peak,
        "observedEndRetainedBytes": sum(grouped.values()),
        "ranked": [{"bytes": size, "thread": thread, "stack": stack}
                   for (thread, stack), size in grouped.most_common()],
        "retainedTimeline": timeline,
    }
    (directory / "retained-summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("ranked", "retainedTimeline")}))
    for item in result["ranked"][:10]:
        print(f'{item["bytes"]/1e6:.3f} MB; thread={item["thread"]}; ' + " <- ".join(item["stack"]))


if __name__ == "__main__":
    main()
