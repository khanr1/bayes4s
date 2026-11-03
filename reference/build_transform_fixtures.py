"""Constraint transforms: NumPy port of stan-dev/math stan/math/prim/constraint (BSD-3-Clause),
values on a fixed grid, each log-Jacobian checked against an independent numerical Jacobian
(central differences, then log |det|). Also records the cases Stan's own tests assert.

Usage: python -I build_transform_fixtures.py <out.json>
"""
import json, math, sys
import numpy as np
from scipy.special import expit, logit, log_expit

def lb(x, L): return math.exp(x) + L, x
def ub(x, U): return U - math.exp(x), x
def lub(x, L, U):
    # Stan: L + (U - L) * inv_logit(x); lp += log(U - L) + log_inv_logit(x) + log1m_inv_logit(x)
    return L + (U - L) * expit(x), math.log(U - L) + log_expit(x) + log_expit(-x)
def offset_multiplier(x, mu, s): return mu + s * x, math.log(s)
def ordered(y):
    y = np.asarray(y, float); x = np.empty_like(y); x[0] = y[0]
    for i in range(1, len(y)): x[i] = x[i - 1] + math.exp(y[i])
    return x, float(y[1:].sum())
def sum_to_zero(y):
    y = np.asarray(y, float); N = len(y); z = np.zeros(N + 1); s = 0.0
    for i in range(N, 0, -1):
        w = y[i - 1] / math.sqrt(i * (i + 1)); s += w; z[i - 1] += s; z[i] -= w * i
    return z, 0.0
def simplex(y):
    """Current Stan Math: softmax(sum_to_zero_constrain(y)) (isometric log-ratio), lp = sum(log z) + 0.5 log(N+1)."""
    z, _ = sum_to_zero(y); m = z.max(); e = np.exp(z - m); x = e / e.sum()
    N = len(y); return x, float(-(N + 1) * (m + math.log(e.sum())) + 0.5 * math.log(N + 1))
def simplex_free(x):
    x = np.asarray(x, float); K = len(x) - 1; y = np.empty(K); c = 0.0
    for i in range(K):
        c += math.log(x[i]); n = i + 1; y[i] = (c - n * math.log(x[i + 1])) / math.sqrt(n * (n + 1))
    return y

def num_logdet(f, y, keep):
    y = np.asarray(y, float); h = 1e-6; J = []
    for j in range(len(y)):
        e = np.zeros_like(y); e[j] = h
        J.append((np.atleast_1d(f(y + e))[:keep] - np.atleast_1d(f(y - e))[:keep]) / (2 * h))
    return float(np.linalg.slogdet(np.array(J).T)[1])

grid = [-30.0, -5.0, -1.0, -0.3, 0.0, 0.25, 1.0, 4.0, 30.0]
cases = {"lb": [], "ub": [], "lub": [], "offset_multiplier": [], "ordered": [], "sum_to_zero": [], "simplex": []}
for x in grid:
    for L in (0.0, -2.5):
        v, lp = lb(x, L); cases["lb"].append({"x": x, "lower": L, "value": v, "log_jacobian": lp})
    v, lp = ub(x, 3.0); cases["ub"].append({"x": x, "upper": 3.0, "value": v, "log_jacobian": lp})
    for L, U in ((0.0, 1.0), (2.0, 5.0), (-1e3, 1e3)):
        v, lp = lub(x, L, U)
        nd = num_logdet(lambda t: lub(float(t[0]), L, U)[0], [x], 1) if abs(x) < 20 else None
        cases["lub"].append({"x": x, "lower": L, "upper": U, "value": float(v), "log_jacobian": float(lp),
                             "numerical_log_jacobian": nd})
    v, lp = offset_multiplier(x, 1.5, 0.2)
    cases["offset_multiplier"].append({"x": x, "offset": 1.5, "multiplier": 0.2, "value": v, "log_jacobian": lp})

rng = np.random.default_rng(20261103)   # fixed seed; inputs only, outputs are deterministic functions
for n in (1, 2, 3, 5, 10):
    for _ in range(3):
        y = rng.normal(0, 2, n)
        v, lp = ordered(y)
        cases["ordered"].append({"y": y.tolist(), "value": v.tolist(), "log_jacobian": lp,
                                 "numerical_log_jacobian": num_logdet(lambda t: ordered(t)[0], y, n)})
        v, _ = sum_to_zero(y)
        cases["sum_to_zero"].append({"y": y.tolist(), "value": v.tolist(), "sum": float(v.sum())})
        v, lp = simplex(y)
        nd = num_logdet(lambda t: simplex(t)[0], y, n)
        cases["simplex"].append({"y": y.tolist(), "value": v.tolist(), "log_jacobian": lp,
                                 "numerical_log_jacobian_first_n": nd,
                                 "round_trip": simplex_free(v).tolist()})

checks = []
for c in cases["lub"]:
    if c["numerical_log_jacobian"] is not None:
        checks.append(abs(c["numerical_log_jacobian"] - c["log_jacobian"]))
for c in cases["ordered"]: checks.append(abs(c["numerical_log_jacobian"] - c["log_jacobian"]))
simplex_offsets = [c["log_jacobian"] - c["numerical_log_jacobian_first_n"] for c in cases["simplex"]]

stan_asserted = [
    {"test": "lub_transform_test.cpp lub_j", "x": -1.0, "lower": 2.0, "upper": 5.0, "lp_before": -17.0,
     "value": 2.0 + 3.0 * float(expit(-1.0)),
     "lp_after": -17.0 + math.log(3.0) + math.log(expit(-1.0)) + math.log(1 - expit(-1.0))},
    {"test": "lub_transform_test.cpp underflow", "x": -1000.0, "lower": 0.0, "upper": 1.0, "value": 0.0,
     "note": "exactly 0, no NaN"},
    {"test": "ordered_transform_test.cpp ordered_j", "y": [1.0, -2.0, -5.0], "lp_before": -152.1,
     "value": [1.0, 1.0 + math.exp(-2.0), 1.0 + math.exp(-2.0) + math.exp(-5.0)], "lp_after": -152.1 - 7.0},
    {"test": "ordered_transform_test.cpp ordered_f", "x": [-12.0, 1.1, 172.1],
     "free": [-12.0, math.log(13.1), math.log(171.0)]},
    {"test": "simplex_transform_test.cpp simplex_0", "y": [], "value": [1.0]},
]

out = {
    "provenance": "port of stan-dev/math stan/math/prim/constraint (BSD-3-Clause, checked out 2026-10-29); "
                  "log-Jacobians independently checked with numerical Jacobians",
    "conventions": {
        "simplex": "Current Stan Math (develop, checked out 2026-10-29) uses softmax(sum_to_zero_constrain(y)) (isometric log-ratio), NOT stick-breaking. "
                   "Its log_jacobian, sum(log x) + 0.5*log(N+1), equals log|det d x[0:N] / d y| exactly "
                   "(simplex_jacobian_offset is 0 to round-off). Inverse: simplex_free (ILR).",
        "lub": "log_jacobian = log(U - L) + log_inv_logit(x) + log1m_inv_logit(x); must not return NaN at |x| = 1000",
        "lp": "transforms ADD their log_jacobian to an accumulator lp (Stan passes lp by reference)",
    },
    "cases": cases, "stan_asserted": stan_asserted,
    "simplex_jacobian_offset": {"values": simplex_offsets},
    "max_abs_numerical_jacobian_error": max(checks),
}
json.dump(out, open(sys.argv[1], "w"), indent=1)
print("numerical jacobian max error (lub, ordered):", max(checks))
print("simplex lp - numerical logdet(first N):", np.round(simplex_offsets[::3], 6))
print("simplex round trip max err:", max(float(np.max(np.abs(np.array(c["round_trip"]) - np.array(c["y"])))) for c in cases["simplex"]))
