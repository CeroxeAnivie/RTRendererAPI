"""Exhaustively check residual proxy remapping and its discrete PDFs."""

from collections import Counter
from bisect import bisect_left


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


def sample_local(slots, point_lights, residual_rank):
    remaining = residual_rank
    for light_index, proxy_count in slots:
        if proxy_count == 0 or light_index in point_lights:
            continue
        if remaining == 0:
            return light_index
        remaining -= 1
    raise AssertionError("residual local rank escaped the slot list")


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
    slots = [
        (light_index, proxy_count)
        for light_index, proxy_count in weighted_lights
        for _ in range(proxy_count)
    ]
    residual = [slot for slot in slots if slot[0] not in point_lights]
    if not residual:
        return
    samples = [sample_local(slots, point_lights, i) for i in range(len(residual))]
    assert samples == [light for light, _ in residual], (samples, residual)
    counts = Counter(samples)
    pdfs = {
        light_index: proxy_count / len(residual)
        for light_index, proxy_count in weighted_lights
        if light_index not in point_lights
    }
    assert abs(sum(pdfs.values()) - 1.0) < 1e-12, pdfs
    assert all(counts[light] / len(residual) == pdf for light, pdf in pdfs.items())


def adjust_counts(local_count, global_count, local_residual, global_residual):
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
    assert adjust_counts(3, 5, 0, 11) == (0, 8)
    assert adjust_counts(3, 5, 6, 0) == (8, 0)
    assert adjust_counts(3, 5, 0, 0) == (8, 0)
    print("RTXPT residual sampling: all discrete PDF and empty-stratum checks passed")


if __name__ == "__main__":
    main()
