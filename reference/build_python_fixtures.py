"""Independent numerical references generated with mpmath (50 digits), SciPy and properscoring.

Usage: python -I build_python_fixtures.py <out dir>
"""
import json, math, sys, pathlib
import numpy as np, mpmath as mp, scipy, properscoring as ps
from scipy import stats

OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
mp.mp.dps = 50
def f(x): return float(x) if mp.isfinite(x) else (math.inf if x > 0 else -math.inf)

# ---- 1. special functions, mpmath at 50 digits (GUIDE Step 1) ----
pos = sorted(set([1e-3, 2e-3, 0.01, 0.1, 0.25, 0.5, 0.75, 0.999, 1.0, 1.001, 1.5, 2.0, 2.5, 3.0, 5.0,
                  7.5, 9.99, 10.0, 10.01, 12.5, 20.0, 50.0, 100.0, 171.5, 500.0, 1e3, 1e4, 99999.0, 1e5]
                 + list(np.geomspace(1e-3, 1e5, 40).round(12))))
neg = [-0.5, -1.5, -2.5, -0.001, -3.75]
special = {
    "lgamma": [{"x": x, "value": f(mp.loggamma(x).real) if x > 0 else f(mp.log(abs(mp.gamma(x))))} for x in pos + neg],
    "lgamma_poles": {"x": [0.0, -1.0, -2.0, -10.0], "expected": "+Inf (log|Gamma| at a pole), never throws"},
    "digamma": [{"x": x, "value": f(mp.digamma(x))} for x in pos + neg],
    "digamma_poles": {"x": [0.0, -1.0, -2.0], "expected": "NaN or +/-Inf, never throws"},
}
xs = [-800.0, -745.0, -100.0, -37.0, -36.0, -20.0, -5.0, -1.0, -1e-10, 0.0, 1e-10, 1.0, 5.0, 18.0, 20.0,
      33.3, 37.0, 100.0, 709.0, 710.0, 800.0]
special["sigmoid"] = [{"x": x, "value": f(1 / (1 + mp.exp(-x)))} for x in xs]
special["log_sigmoid"] = [{"x": x, "value": f(-mp.log1p(mp.exp(-x)))} for x in xs]
special["softplus_log1p_exp"] = [{"x": x, "value": f(mp.log1p(mp.exp(x)))} for x in xs]
special["log1m_exp"] = [{"x": x, "value": f(mp.log(-mp.expm1(x)))} for x in
                        [-800.0, -50.0, -1.0, -0.6931471805599453, -0.1, -1e-5, -1e-10, -1e-20]]
special["logit"] = [{"p": p, "value": f(mp.log(p / (1 - mp.mpf(p))))} for p in
                    [1e-300, 1e-10, 0.001, 0.25, 0.5, 0.75, 0.999, 1 - 1e-10]]
def nq(p):
    """Normal quantile by root finding on the exact CDF (erfinv(2p - 1) loses all digits for tiny p)."""
    p = mp.mpf(p); lo = p < 0.5; q = p if lo else 1 - p
    x0 = -mp.sqrt(-2 * mp.log(q)) if q < 0.1 else mp.mpf(-0.5)
    x = mp.findroot(lambda t: mp.log(mp.ncdf(t)) - mp.log(q), x0)
    return x if lo else -x
special["normal_quantile"] = [{"p": p, "value": f(nq(p))} for p in
                              [1e-300, 1e-20, 1e-10, 1e-5, 0.000187, 0.01, 0.025, 0.1, 0.3, 0.5, 0.7, 0.9,
                               0.975, 0.99, 0.999, 1 - 1e-10]]
special["log_sum_exp"] = [{"x": list(v), "value": f(mp.log(mp.fsum(mp.exp(t) for t in v)))} for v in
                          [(0.0, 0.0), (1000.0, 1000.0), (-1000.0, -1000.5), (1.0, 2.0, 3.0), (-1e300, 0.0), (0.0,)]]
special["lbeta"] = [{"a": a, "b": b, "value": f(mp.log(mp.beta(a, b)))} for a, b in
                    [(0.5, 0.5), (1, 1), (2, 5), (1e-3, 1e3), (1e5, 1e5), (300, 0.25)]]
special["note"] = ("normal_quantile is needed by rank normalisation (diagnostics) and is missing from the GUIDE's "
                   "math package; log_sum_exp of [-inf] or empty input must be -inf")
(OUT / "special_functions.json").write_text(json.dumps(
    {"provenance": f"mpmath {mp.__version__}, 50 significant digits, rounded to double",
     "tolerance": "1e-13 relative above magnitude 1, absolute below (GUIDE G.4); "
                  "lgamma near its zeros at x = 1 and 2 is absolute", "functions": special}, indent=1))

# ---- 2. densities on a grid not covered by Stan's tests: extreme and boundary arguments ----
dens = []
def add(name, params, val): dens.append({"distribution": name, "args": params, "value": f(mp.mpf(val)) if not isinstance(val, float) else val})
for y, m, s in [(0, 0, 1e-8), (1e3, 0, 1), (-37.5, 2.0, 0.3), (5.0, 5.0, 1e5)]:
    add("normal_lpdf", [y, m, s], -mp.log(s) - mp.log(2 * mp.pi) / 2 - ((mp.mpf(y) - m) / s) ** 2 / 2)
for y, s in [(0.0, 1.0), (1e-12, 1e-3), (30.0, 2.0)]:
    add("half_normal_lpdf", [y, s], mp.log(2) - mp.log(s) - mp.log(2 * mp.pi) / 2 - (mp.mpf(y) / s) ** 2 / 2)
for k, lam in [(0, 1e-300), (0, 0.0), (5, 0.0), (1000, 1000.0), (0, 1e8), (12, 1e-6), (170, 3.5)]:
    if lam == 0.0:
        dens.append({"distribution": "poisson_lpmf", "args": [k, lam], "value": 0.0 if k == 0 else -math.inf}); continue
    add("poisson_lpmf", [k, lam], k * mp.log(lam) - lam - mp.loggamma(k + 1))
for k, a in [(0, -700.0), (3, 2.5), (1000, 6.9)]:
    add("poisson_log_lpmf", [k, a], k * a - mp.exp(a) - mp.loggamma(k + 1))
for k, mu, phi in [(0, 1e-8, 1.0), (50, 3.0, 1e-3), (10, 10.0, 1e8), (200, 150.0, 2.5)]:
    p = mp.mpf(phi) / (mu + phi)
    add("neg_binomial_2_lpmf", [k, mu, phi], mp.loggamma(k + phi) - mp.loggamma(phi) - mp.loggamma(k + 1)
        + phi * mp.log(p) + k * mp.log(1 - p))
for y, a, b in [(1e-6, 0.5, 0.5), (1 - 1e-9, 5.0, 2.0), (0.71, 1.0, 1.0), (0.5, 1e3, 1e3)]:
    add("beta_lpdf", [y, a, b], (a - 1) * mp.log(y) + (b - 1) * mp.log(1 - mp.mpf(y)) - mp.log(mp.beta(a, b)))
for y, m, s in [(1e-10, 0.0, 1.0), (1e6, 3.0, 0.9)]:
    add("lognormal_lpdf", [y, m, s], -mp.log(y) - mp.log(s) - mp.log(2 * mp.pi) / 2 - ((mp.log(y) - m) / s) ** 2 / 2)
for y, a, b in [(1e-8, 0.3, 2.0), (500.0, 50.0, 0.1)]:
    add("gamma_lpdf", [y, a, b], a * mp.log(b) - mp.loggamma(a) + (a - 1) * mp.log(y) - b * y)
(OUT / "densities_extreme.json").write_text(json.dumps(
    {"provenance": "mpmath 50 digits; complements stan-math/distributions.json at extreme arguments",
     "conventions": "full normalising constants (Stan's *_lpdf with propto = false); poisson(0 | 0) = 0, "
                    "poisson(k > 0 | 0) = -inf", "cases": dens}, indent=1))

# ---- 3. quantiles (R type 7 = numpy linear = stan::math::quantile) ----
qx = [3.2, -1.0, 7.5, 0.0, 0.0, 2.25, 9.0, -4.5, 1.1, 6.0, 5.5]
quant = [{"data": qx, "p": p, "value": float(np.quantile(qx, p))} for p in (0, 0.05, 0.1, 0.25, 0.5, 0.9, 0.95, 1)]

# ---- 4. CRPS ----
rng = np.random.default_rng(20261103)
crps = []
for n, y in [(1, 0.3), (2, 1.0), (5, -2.0), (10, 12.0), (100, 4.0), (1000, 0.0)]:
    ens = (10 + 3 * rng.standard_normal(n)) if y > 3 else rng.standard_normal(n)
    crps.append({"ensemble": ens.tolist(), "observation": y, "crps": float(ps.crps_ensemble(y, ens)),
                 "energy_form": float(np.mean(np.abs(ens - y)) - 0.5 * np.mean(np.abs(ens[:, None] - ens[None, :])))})
crps_tied = {"ensemble": [1.0, 1.0, 1.0, 4.0], "observation": 1.0,
             "crps": float(ps.crps_ensemble(1.0, np.array([1.0, 1.0, 1.0, 4.0])))}
gauss = [{"mu": m, "sigma": s, "observation": y, "crps": float(ps.crps_gaussian(y, m, s))}
         for m, s, y in [(0, 1, 0), (10, 3.0, 14), (0, 1, -3), (2, 0.1, 2.05)]]
(OUT / "scoring.json").write_text(json.dumps({
    "provenance": f"properscoring 0.1 (crps_ensemble, crps_gaussian), numpy {np.__version__}",
    "notes": "crps_ensemble is the sample (energy) form E|X-y| - 0.5 E|X-X'| with the 1/n^2 estimator, not 1/(n(n-1)). "
             "A sorted O(n log n) implementation must match it to 1e-12.",
    "crps_ensemble": crps, "crps_tied": crps_tied, "crps_gaussian": gauss,
    "quantile_type7": quant}, indent=1))

# ---- 5. Classic worked examples with made-up numbers (no external source) ----
lam = np.array([4, 6, 8, 10, 12.0])            # candidate event rates per period
obs1, obs2 = 7, 11                               # two made-up observed counts
like = stats.poisson.pmf(obs1, lam); post1 = like / like.sum()
post2 = post1 * stats.poisson.pmf(obs2, lam); post2 /= post2.sum()
prior_a, prior_b, counts = 1.5, 0.2, [7, 11, 5, 9]
ga, gb = prior_a + sum(counts), prior_b + len(counts)
g = stats.gamma(ga, scale=1 / gb)
exposure_counts, exposure = 13, 2.6              # visible events and exposure (partially observed periods)
ge = stats.gamma(prior_a + exposure_counts, scale=1 / (prior_b + exposure))
a1, b1 = prior_a + obs1, prior_b + 1             # posterior after one period
nb_sd = math.sqrt(a1 / b1 + a1 / b1 ** 2)
nb = stats.nbinom(a1, b1 / (b1 + 1))
def hpd(dist, mass=0.9):
    from scipy.optimize import minimize_scalar
    r = minimize_scalar(lambda lo: dist.ppf(dist.cdf(lo) + mass) - lo, bounds=(dist.ppf(1e-6), dist.ppf(1 - mass - 1e-6)), method="bounded")
    return [float(r.x), float(dist.ppf(dist.cdf(r.x) + mass))]
worked = {
    "source": "classic textbook-style examples; every input is made up for this library",
    "grid_posterior": {"candidates": lam.tolist(), "prior": [0.2] * 5, "observed": obs1,
                       "likelihood": like.tolist(), "posterior": post1.tolist(),
                       "after_second_observation": {"observed": obs2, "posterior": post2.tolist()}},
    "gamma_poisson_conjugate": {"prior": [prior_a, prior_b], "counts": counts, "posterior": [ga, gb],
                                "mean": float(g.mean()), "sd": float(g.std()),
                                "equal_tailed_90": [float(g.ppf(0.05)), float(g.ppf(0.95))],
                                "hpd_90": hpd(g), "p_gt_10": float(g.sf(10)), "p_gt_6": float(g.sf(6))},
    "gamma_poisson_with_exposure": {"prior": [prior_a, prior_b], "visible_count": exposure_counts,
                                    "exposure": exposure, "posterior": [prior_a + exposure_counts, prior_b + exposure],
                                    "mean": float(ge.mean())},
    "negative_binomial_predictive": {"posterior": [a1, b1], "mean": a1 / b1, "sd": nb_sd,
                                     "interval_90": [int(nb.ppf(0.05)), int(nb.ppf(0.95))],
                                     "plugin_poisson_sd": math.sqrt(a1 / b1),
                                     "plugin_interval_90": [int(stats.poisson.ppf(0.05, a1 / b1)), int(stats.poisson.ppf(0.95, a1 / b1))]},
    "shrinkage_weight": {"formula": "w = e / (alpha + e)",
                         "cases": [{"alpha": al, "e": e, "w": e / (al + e)} for al in (1.0, 5.0) for e in (1, 5, 20, 40)]},
    "metropolis_target": {"distribution": "Gamma", "shape": ga, "rate": gb, "mean": float(g.mean()), "sd": float(g.std())},
}
(OUT / "worked_examples.json").write_text(json.dumps(worked, indent=1))
print("grid", np.round(post1, 3), np.round(post2, 3))
print("conjugate", [ga, gb], round(g.mean(), 3), round(g.std(), 3), "HPD", np.round(hpd(g), 2))
print("NB sd", round(nb_sd, 2), worked["negative_binomial_predictive"]["interval_90"])
print("crps vs energy max diff", max(abs(c["crps"] - c["energy_form"]) for c in crps))
