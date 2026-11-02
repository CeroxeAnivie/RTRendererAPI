"""Exhaustively check residual proxy remapping and its discrete PDFs."""

from collections import Counter
from bisect import bisect_left
from itertools import combinations, product


def remap_global(proxy_indices, point_lights, residual_rank):
    proxy_index = residual_rank
    counters = Counter(proxy_indices)
    for light_index in sorted(point_lights):
        point_proxy_count = counters[light_index]
        if point_proxy_count == 0:
            continue
        first_point_proxy = bisect_left(proxy_indices, light_index)
        if proxy_index >= first_point_proxy:
            proxy_index += point_proxy_count
    return proxy_indices[proxy_index]


def pack_local_slots(weighted_lights):
    # Match LightsBaker::LastScanAndWriteOut: EVERY physical slot contains the
    # total number of occurrences of its light. This is not a unique-light list.
    return [(light, count) for light, count in sorted(weighted_lights)
            for _ in range(count)]


def local_residual_count(slots, point_lights):
    keys = [light for light, _ in slots]
    count = len(slots)
    for light in sorted(point_lights):
        first = bisect_left(keys, light)
        if first < len(slots) and keys[first] == light:
            count -= slots[first][1]
    return count


def sample_local(slots, point_lights, residual_rank):
    keys = [light for light, _ in slots]
    index = residual_rank
    for light in sorted(point_lights):
        first = bisect_left(keys, light)
        if first < len(slots) and index >= first and keys[first] == light:
            index += slots[first][1]
    return slots[index][0]


def check_global(proxy_indices, point_lights):
    residual = [light for light in proxy_indices if light not in point_lights]
    if not residual:
        return
    samples = [remap_global(proxy_indices, point_lights, i) for i in range(len(residual))]
    assert samples == residual, (samples, residual)
    counts = Counter(samples)
    assert abs(sum(count / len(residual) for count in counts.values()) - 1.0) < 1e-12
    assert not (set(samples) & point_lights)


def check_local(weighted_lights, point_lights):
    slots = pack_local_slots(weighted_lights)
    expected = [light for light, _ in slots if light not in point_lights]
    residual_count = local_residual_count(slots, point_lights)
    assert residual_count == len(expected), (residual_count, len(expected))
    if not expected:
        return
    samples = [sample_local(slots, point_lights, i) for i in range(residual_count)]
    assert samples == expected, (samples, expected)
    counts = Counter(samples)
    pdfs = {
        light_index: proxy_count / residual_count
        for light_index, proxy_count in slots if light_index not in point_lights
    }
    assert abs(sum(pdfs.values()) - 1.0) < 1e-12, pdfs
    assert all(counts[light] / residual_count == pdf for light, pdf in pdfs.items())
    # Verify an importance-sampling integral, not only the selected indices.
    estimate = sum((light + 0.5) / pdfs[light] for light in samples) / residual_count
    assert abs(estimate - sum(light + 0.5 for light in counts)) < 1e-9


def adjust_counts(local_count, global_count, local_residual, global_residual):
    if local_residual == 0 and global_residual == 0:
        return 0, 0
    if local_count > 0 and local_residual == 0:
        global_count += local_count
        local_count = 0
    if global_count > 0 and global_residual == 0:
        local_count += global_count
        global_count = 0
    assert local_count == 0 or local_residual > 0
    assert global_count == 0 or global_residual > 0
    return local_count, global_count


def main():
    check_global([0] * 4 + [1] * 40 + [2] * 3 + [3] * 8, {1, 3})
    check_global([0] * 7 + [2] * 2 + [4] * 9 + [5] * 1, {0, 4})
    check_global([0] * 5 + [1] * 3, {0, 1})
    check_local([(1, 5), (2, 3), (4, 2)], {1})
    check_local([(1, 4), (3, 7)], {1, 3})
    check_local([(2, 4), (5, 2)], set())
    # Real 128/256/512-slot layouts; dominant runs and absent excluded lights.
    for capacity in (128, 256, 512):
        check_local([(1, capacity // 2), (10, capacity * 3 // 8), (20, capacity // 8)], {1})
        check_local([(1, capacity - 1), (10, 1)], {1, 2, 3, 4})
        check_local([(1, capacity)], {1})
        check_local([(10, capacity)], {1, 2, 3, 4})
    # Exhaust every rank and exclusion subset for all small three-run layouts.
    for weights in product(range(1, 6), repeat=3):
        for excluded_count in range(4):
            for excluded in combinations((1, 2, 3), excluded_count):
                check_local(list(zip((1, 2, 3), weights)), set(excluded))
    regression_slots = pack_local_slots([(1, 64), (10, 48), (20, 16)])
    old_count = sum(count for light, count in regression_slots if light != 1)
    assert old_count == 2560 and local_residual_count(regression_slots, {1}) == 64
    assert adjust_counts(3, 5, 0, 11) == (0, 8)
    assert adjust_counts(3, 5, 6, 0) == (8, 0)
    assert adjust_counts(3, 5, 0, 0) == (0, 0)
    assert adjust_counts(0, 8, 0, 0) == (0, 0)
    assert adjust_counts(0, 8, 4, 0) == (8, 0)
    assert adjust_counts(5, 3, 0, 7) == (0, 8)
    print("RTXPT residual sampling: packed upstream slots, exhaustive ranks, PDFs, integrals and empty strata passed")


if __name__ == "__main__":
    main()
