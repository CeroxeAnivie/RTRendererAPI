"""Pair every captured native heap object and attribute survivors to address regions.

Heap requested bytes and page commitment are different layers: never add them.
Hash partitions bound analysis memory even for millions of small compiler objects.
"""
import argparse
import bisect
import collections
import gzip
import json
import pathlib
import struct
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    args = parser.parse_args()
    directory = args.directory
    modules = {}
    clock = None
    observer = None
    for line in (directory / "allocations.jsonl").read_text(encoding="utf-8").splitlines():
        value = json.loads(line)["message"].get("payload", {})
        for module in value.get("modules", []):
            modules[int(module["base"], 16)] = (module["size"], module["name"])
        if value.get("kind") == "ready":
            clock = value
        if value.get("kind") == "heap-statistics":
            observer = value
    addresses = sorted(modules)

    def symbol(address):
        slot = bisect.bisect_right(addresses, address) - 1
        if slot >= 0:
            base = addresses[slot]
            size, name = modules[base]
            if address < base + size:
                return name + "+" + hex(address - base)
        return hex(address)

    def second(tick):
        return int((clock["epochMillis"] + (tick - clock["tick"] + 2**31) % 2**32 - 2**31) // 1000)

    full = struct.Struct("<IIiIQQQII96Q")
    stacks = {}
    with (directory / "native-heap-stacks.bin").open("rb") as source:
        while value := source.read(full.size):
            if len(value) != full.size:
                raise ValueError("Partial stack record")
            fields = full.unpack(value)
            stacks[fields[4]] = [symbol(address) for address in fields[9:9 + fields[7]]]
    record = struct.Struct("<IIIIQQQQ")
    counts = collections.Counter()
    traffic = collections.Counter()
    survivors = collections.Counter()
    objects = collections.Counter()
    changes = collections.Counter()
    region_stacks = collections.Counter()
    regions = []
    census_file = directory / "virtual-memory.jsonl.gz"
    if census_file.exists():
        with gzip.open(census_file, "rt", encoding="utf-8") as source:
            snapshots = [json.loads(line) for line in source]
        peak = max(snapshots, key=lambda item: item["processPrivateCommit"])
        regions = sorted(peak["regions"])
    region_bases = [entry[0] for entry in regions]
    partial = 0
    with tempfile.TemporaryDirectory(prefix="heap-analysis-", dir=directory) as temporary:
        files = [open(pathlib.Path(temporary) / f"{i}.bin", "wb") for i in range(64)]
        def partition(pointer):
            return ((pointer >> 4) ^ (pointer >> 16)) & 63
        try:
            with (directory / "native-heap.bin").open("rb") as source:
                while block := source.read(record.size * 65536):
                    partial += len(block) % record.size
                    buffers = [bytearray() for _ in files]
                    for kind, tid, tick, stack, heap, base, old, size in record.iter_unpack(block[:len(block) // record.size * record.size]):
                        counts[str(kind)] += 1
                        if kind == 1:
                            if base:
                                buffers[partition(base)].extend(record.pack(kind, tid, tick, stack, heap, base, old, size))
                                traffic[(tid, stack)] += size
                            else:
                                counts["failedAllocation"] += 1
                        elif kind == 2:
                            if base and old:
                                buffers[partition(old)].extend(record.pack(kind, tid, tick, stack, heap, base, old, size))
                        elif kind == 3:
                            if base:
                                buffers[partition(old)].extend(record.pack(2, tid, tick, stack, heap, 1, old, 0))
                                buffers[partition(base)].extend(record.pack(1, tid, tick, stack, heap, base, 0, size))
                                traffic[(tid, stack)] += size
                            else:
                                counts["failedReallocation"] += 1
                        elif kind == 4 and base == 0:
                            for output in buffers:
                                output.extend(record.pack(kind, tid, tick, stack, heap, base, old, size))
                    for output, buffer in zip(files, buffers):
                        output.write(buffer)
        finally:
            for output in files:
                output.close()
        for i in range(64):
            live = {}
            with open(pathlib.Path(temporary) / f"{i}.bin", "rb") as source:
                while block := source.read(record.size * 65536):
                    for kind, tid, tick, stack, heap, base, old, size in record.iter_unpack(block):
                        now = second(tick)
                        if kind == 1:
                            previous = live.get(base)
                            if previous:
                                counts["overwrittenLivePointer"] += 1
                                changes[(now, previous[1], previous[2])] -= previous[0]
                            live[base] = (size, tid, stack, heap)
                            changes[(now, tid, stack)] += size
                        elif kind == 2:
                            previous = live.pop(old, None)
                            if previous:
                                changes[(now, previous[1], previous[2])] -= previous[0]
                            else:
                                counts["freeWithoutCapturedAllocation"] += 1
                        elif kind == 4:
                            removed = [address for address, item in live.items() if item[3] == heap]
                            for address in removed:
                                previous = live.pop(address)
                                changes[(now, previous[1], previous[2])] -= previous[0]
            for address, (size, tid, stack, heap) in live.items():
                survivors[(tid, stack)] += size
                objects[(tid, stack)] += 1
                slot = bisect.bisect_right(region_bases, address) - 1
                if slot >= 0 and address < regions[slot][0] + regions[slot][2]:
                    region_stacks[(regions[slot][1], regions[slot][3], tid, stack)] += size
                else:
                    counts["liveObjectsOutsidePeakCensus"] += 1
    result = {"coverage": "All observed top-level Rtl heap allocations/reallocations/frees/destroy; requested object bytes, not committed pages",
              "recordBytes": record.size, "partialRecordBytes": partial, "counts": dict(counts),
              "lastObserverStatistics": observer, "stackDefinitions": len(stacks),
              "observedEndLiveRequestedBytes": sum(survivors.values()),
              "ranked": [{"bytes": size, "objects": objects[key], "thread": key[0], "stackId": key[1],
                          "trafficBytes": traffic[key], "stack": stacks.get(key[1], [])}
                         for key, size in survivors.most_common()],
              "liveByRegion": [{"allocationBase": key[0], "type": key[1], "thread": key[2],
                                "stackId": key[3], "bytes": size}
                               for key, size in region_stacks.most_common()],
              "liveChanges": [[second, tid, stack, size] for (second, tid, stack), size in sorted(changes.items()) if size],
              "limitations": ["Start is observer attachment; initial heap objects lack allocation records.",
                              "Up to 65520 bytes of the newest records can remain buffered on abrupt failure.",
                              "Stacks use two 64-bit hashes for interning; no heap-object sampling.",
                              "Peak address census and end-live object inventory are different instants."]}
    (directory / "heap-summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("ranked", "liveByRegion", "liveChanges")}))
    for item in result["ranked"][:12]:
        print(f'{item["bytes"]/1e6:.3f} MB; objects={item["objects"]}; thread={item["thread"]}; ' + " <- ".join(item["stack"][:12]))


if __name__ == "__main__":
    main()
