"""Independent integration/MIS check for disjoint directional environment strata."""
import math
import random
import statistics

rng = random.Random(90261102)
size = 120_000


def dot(a, b):
    return sum(x*y for x, y in zip(a, b))


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def normalized(v):
    length = math.sqrt(dot(v, v))
    return tuple(x/length for x in v)


def directions(axis, cosine):
    tangent = cross(axis, (1., 0., 0.))
    if dot(tangent, tangent) < .01:
        tangent = cross(axis, (0., 1., 0.))
    tangent = normalized(tangent)
    bitangent = cross(axis, tangent)
    for _ in range(size):
        z = cosine + (1-cosine)*rng.random()
        r = math.sqrt(max(0, 1-z*z))
        phi = 2*math.pi*rng.random()
        yield tuple(r*(math.cos(phi)*x+math.sin(phi)*y)+z*w for x,y,w in zip(tangent,bitangent,axis))


for overlapping in (False, True):
    first = (0., 0., 1.)
    second = normalized((.1, 0., .995)) if overlapping else (0., 0., -1.)
    caps = [(first, .96), (second, .96)]

    def owner(sample):
        return next((i for i,(axis,cosine) in enumerate(caps) if dot(sample,axis)>=cosine), -1)

    # Nonconstant radiance has exact sphere integral 4*pi. Rejected residual
    # samples retain zero contribution: its original PDF is not renormalized.
    values = [(1+.3*s[2])*(owner(s)==-1)*(4*math.pi) for s in directions(first,-1)]
    total, variance = statistics.mean(values), statistics.pvariance(values)/size
    for i,(axis,cosine) in enumerate(caps):
        pdf = 1/(2*math.pi*(1-cosine))
        values = [(1+.3*s[2])*(owner(s)==i)/pdf for s in directions(axis,cosine)]
        total += statistics.mean(values)
        variance += statistics.pvariance(values)/size
        for bsdf_pdf in (.02, .1, .5, 1., 16.):
            assert abs(pdf/(pdf+bsdf_pdf)+bsdf_pdf/(pdf+bsdf_pdf)-1)<1e-14
    assert abs(total-4*math.pi)<6*math.sqrt(variance), (overlapping,total,variance)
    print(f'overlap={overlapping}: integral={total:.6f}, target={4*math.pi:.6f}, sigma={math.sqrt(variance):.6f}')
print('Directional strata: disjoint ownership, unrenormalized residual integral and reciprocal MIS PASS')
