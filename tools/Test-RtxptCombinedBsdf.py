"""Independent mixed-lobe integral and variance check, with complementary NEE MIS."""
import math
import random
import statistics

count = 180_000
rng = random.Random(20261102)
for probability in (.15, .6, .9):
    original, combined, light = [], [], []
    for _ in range(count):
        diffuse = rng.random() < probability
        x = rng.random() if diffuse else rng.random() ** (1 / 13)
        q_spec = 13 * x ** 12
        q_mix = probability + (1 - probability) * q_spec
        f_total = .7 + .3 * q_spec
        mis_bsdf = q_mix / (q_mix + 1)
        original.append((.7 / probability if diffuse else .3 / (1 - probability)) * mis_bsdf)
        combined.append(f_total / q_mix * mis_bsdf)
        y = rng.random()
        light.append((.7 + .3 * 13 * y ** 12) / (probability + (1 - probability) * 13 * y ** 12 + 1))
    estimate = statistics.mean(combined) + statistics.mean(light)
    sigma = math.sqrt((statistics.pvariance(combined) + statistics.pvariance(light)) / count)
    assert abs(estimate - 1) < 6 * sigma, (probability, estimate, sigma)
    assert statistics.pvariance(combined) <= statistics.pvariance(original) * 1.015
    print(f'p={probability}: integral={estimate:.6f}, sigma={sigma:.6f}, '
          f'variance {statistics.pvariance(original):.6f} -> {statistics.pvariance(combined):.6f}')
print('Combined continuous lobes: integral, reciprocal MIS and variance PASS')
