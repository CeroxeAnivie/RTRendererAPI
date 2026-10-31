"""Reconcile native page-backing stacks with the runtime virtual-memory census.

The trace is a partial observation starting at attachment. Values describe only
observed committed intervals, not complete process memory or a proven leak.
"""
import argparse
import collections
import gzip
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

    # Disjoint intervals retain the stack that first committed/mapped those pages.
    live = []
    traffic = 0
    peak = 0
    timeline = []
    failures = []
    kinds = collections.Counter()
    snapshots = []
    census_file = directory / "virtual-memory.jsonl.gz"
    if census_file.exists():
        with gzip.open(census_file, "rt", encoding="utf-8") as stream:
            snapshots = [json.loads(line) for line in stream]
    baseline = snapshots[0]["regions"] if snapshots else []
    reconciled = []
    snapshot_index = 0

    def overlaps(begin, end, intervals):
        return sum(max(0, min(end, hi) - max(begin, lo)) for lo, hi, *_ in intervals)

    def reconcile(snapshot):
        by_type = {}
        unexplained = []
        for base, allocation, size, kind, protect in snapshot["regions"]:
            end = base + size
            matching = [(max(base, lo), min(end, hi), *extra)
                        for lo, hi, *extra in live if max(base, lo) < min(end, hi)]
            observed = sum(hi - lo for lo, hi, *_ in matching)
            # A baseline address is explicitly not a recovered allocation stack.
            initial = sum(max(0, min(end, lo + count) - max(base, lo))
                          - overlaps(max(base, lo), min(end, lo + count), matching)
                          for lo, _, count, old_kind, _ in baseline
                          if old_kind == kind and max(base, lo) < min(end, lo + count))
            unknown = size - observed - initial
            counts = by_type.setdefault(str(kind), {"total": 0, "stackAttributed": 0,
                                                    "baselineAddress": 0, "unattributedNew": 0})
            for key, value in (("total", size), ("stackAttributed", observed),
                               ("baselineAddress", initial), ("unattributedNew", unknown)):
                counts[key] += value
            if unknown:
                unexplained.append({"base": base, "allocation": allocation, "type": kind, "bytes": unknown})
        return {"epochSeconds": snapshot["epochSeconds"],
                "scanDurationSeconds": snapshot["scanDurationSeconds"],
                "processPrivateCommit": snapshot["processPrivateCommit"],
                "processCommitMinusPrivateVirtual": snapshot["processCommitMinusPrivateVirtual"],
                "byType": by_type, "unattributedRegions": sorted(unexplained, key=lambda item: item["bytes"], reverse=True)}

    binary = (directory / "native-commit.bin").read_bytes()
    record = struct.Struct("<IIiIQQQII96Q")
    events = list(record.iter_unpack(binary[:len(binary) // record.size * record.size]))

    def epoch(tick):
        delta = (tick - clock["tick"] + 2**31) % 2**32 - 2**31
        return (clock["epochMillis"] + delta) / 1000

    if clock:
        events.sort(key=lambda entry: epoch(entry[8]))
    for kind, thread, status, flags, base, size, allocation, count, tick, *frames in events:
        if clock:
            while snapshot_index < len(snapshots) and snapshots[snapshot_index]["epochSeconds"] < epoch(tick):
                reconciled.append(reconcile(snapshots[snapshot_index]))
                snapshot_index += 1
        kinds[str(kind)] += 1
        if status < 0:
            failures.append({"kind": kind, "status": hex(status & 0xffffffff), "bytes": size,
                             "thread": thread, "epochSeconds": epoch(tick) if clock else None,
                             "stack": [symbol(address) for address in frames[:count]]})
            continue
        if kind in (1, 3):
            stack = tuple(symbol(address) for address in frames[:count])
            traffic += size
            missing = [(base, base + size)]
            for begin, end, *_ in live:
                missing = [(a, b) for lo, hi in missing for a, b in
                           ((lo, min(hi, begin)), (max(lo, end), hi)) if a < b]
            live.extend((lo, hi, allocation, thread, stack) for lo, hi in missing)
        elif kind == 4:
            reservation = allocation or base
            live = [entry for entry in live if entry[2] != reservation]
        elif kind == 2:
            if flags & 0x8000:  # MEM_RELEASE frees every interval in the reservation.
                live = [entry for entry in live if entry[2] != base]
            elif flags & 0x4000:  # MEM_DECOMMIT can split a previously observed interval.
                live = [(a, b, *entry[2:]) for entry in live for a, b in
                        ((entry[0], min(entry[1], base)), (max(entry[0], base + size), entry[1])) if a < b]
        retained = sum(hi - lo for lo, hi, *_ in live)
        peak = max(peak, retained)
        if tick and clock:
            second = int(epoch(tick))
            if not timeline or timeline[-1][0] != second:
                timeline.append([second, retained])
            else:
                timeline[-1][1] = retained
    while snapshot_index < len(snapshots):
        reconciled.append(reconcile(snapshots[snapshot_index]))
        snapshot_index += 1
    grouped = collections.Counter()
    for lo, hi, allocation, thread, stack in live:
        grouped[(thread, stack)] += hi - lo
    result = {
        "coverage": "Virtual allocation/commit of every size, section mappings, Ex variants, frees and failures since attachment; individual heap objects are not traced",
        "eventKinds": dict(kinds),
        "records": len(binary) // record.size,
        "partialRecordBytes": len(binary) % record.size,
        "allocationTrafficBytes": traffic,
        "observedPeakRetainedBytes": peak,
        "observedEndRetainedBytes": sum(grouped.values()),
        "ranked": [{"bytes": size, "thread": thread, "stack": stack}
                   for (thread, stack), size in grouped.most_common()],
        "retainedTimeline": timeline,
        "failures": failures,
        "censusReconciliation": reconciled,
    }
    (directory / "retained-summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items()
                      if key not in ("ranked", "retainedTimeline", "censusReconciliation", "failures")}))
    if reconciled:
        print("Peak process census:", json.dumps(max(reconciled, key=lambda entry: entry["processPrivateCommit"])))
    for item in result["ranked"][:10]:
        print(f'{item["bytes"]/1e6:.3f} MB; thread={item["thread"]}; ' + " <- ".join(item["stack"]))


if __name__ == "__main__":
    main()
