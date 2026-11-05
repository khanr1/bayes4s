"""References for the optional façade that mirrors the user-facing functions of the `rethinking` R
package (McElreath, GPL >= 3). Nothing is copied from that package: only its documented
parameterisations are reproduced, and every value is computed here from first principles with
SciPy and NumPy. Values that depend on a convention we could not run R to confirm are flagged
"confirm_with_R".

Usage: python -I build_rethinking_fixtures.py <out.json>
"""
import json, math, sys
import numpy as np
from scipy import stats, special

rng = np.random.default_rng(20261105)
out = {"provenance": "computed with NumPy " + np.__version__ + " and SciPy; parameterisations from the "
                     "rethinking package documentation (dgampois, dbetabinom, dordlogit, dzipois, dstudent, "
                     "dmvnorm2, dlkjcorr, HPDI, PI)",
       "defaults": {"interval_prob": 0.89, "note": "PI, HPDI and precis default to 89% intervals in the book"}}

# ---- densities with rethinking's parameterisations ----
d = []
for x, mu, scale in [(0, 2.0, 0.5), (3, 2.0, 0.5), (10, 4.0, 3.0), (25, 20.0, 0.1)]:
    # size = mu / scale, prob = 1 / (1 + scale)
    d.append({"fn": "dgampois", "args": {"x": x, "mu": mu, "scale": scale},
              "log": float(stats.nbinom.logpmf(x, mu / scale, 1 / (1 + scale)))})
for x, size, prob, theta in [(0, 10, 0.3, 2.0), (4, 10, 0.3, 2.0), (7, 12, 0.6, 15.0)]:
    a, b = prob * theta, (1 - prob) * theta
    d.append({"fn": "dbetabinom", "args": {"x": x, "size": size, "prob": prob, "theta": theta},
              "log": float(stats.betabinom.logpmf(x, size, a, b))})
for x, phi, cuts in [(1, 0.0, [-1.0, 0.5, 2.0]), (2, 0.3, [-1.0, 0.5, 2.0]), (4, 1.2, [-1.0, 0.5, 2.0])]:
    # categories 1..K; P(y <= k) = logistic(a_k - phi), as MASS::polr and Stan ordered_logistic
    F = [0.0] + [float(special.expit(c - phi)) for c in cuts] + [1.0]
    d.append({"fn": "dordlogit", "args": {"x": x, "phi": phi, "a": cuts}, "log": math.log(F[x] - F[x - 1])})
for x, p, lam in [(0, 0.2, 3.0), (2, 0.2, 3.0), (0, 0.0, 1.5)]:
    pr = p + (1 - p) * math.exp(-lam) if x == 0 else (1 - p) * stats.poisson.pmf(x, lam)
    d.append({"fn": "dzipois", "args": {"x": x, "p": p, "lambda": lam}, "log": math.log(pr)})
for x, nu, mu, s in [(0.5, 2.0, 0.0, 1.0), (-3.0, 5.0, 1.0, 2.0)]:
    d.append({"fn": "dstudent", "args": {"x": x, "nu": nu, "mu": mu, "sigma": s},
              "log": float(stats.t.logpdf(x, nu, mu, s))})
for _ in range(3):
    sig = rng.uniform(0.5, 2, 3); A = rng.normal(size=(3, 3)); S = A @ A.T + np.eye(3)
    Rho = S / np.sqrt(np.outer(np.diag(S), np.diag(S))); Mu = rng.normal(size=3); x = rng.normal(size=3)
    cov = np.diag(sig) @ Rho @ np.diag(sig)
    d.append({"fn": "dmvnorm2", "args": {"x": x.tolist(), "Mu": Mu.tolist(), "sigma": sig.tolist(), "Rho": Rho.tolist()},
              "log": float(stats.multivariate_normal.logpdf(x, Mu, cov))})
out["densities"] = d
out["dlkjcorr_note"] = "dlkjcorr(x, eta) is the LKJ density on a correlation matrix: use lkj.corr_cases of multilevel.json"

# ---- PI and HPDI ----
def hpdi_coda(samples, prob):
    """Algorithm of coda::HPDinterval (used by rethinking::HPDI): sort, gap = round(n * prob) with
    R's round-half-even, choose the narrowest window of `gap` steps."""
    v = np.sort(samples); n = len(v); gap = max(1, min(n - 1, int(round(n * prob))))
    w = v[gap:] - v[:n - gap]; i = int(np.argmin(w))
    return [float(v[i]), float(v[i + gap])]
iv = []
for name, s in [("gamma", rng.gamma(3.0, 1.0, 1001)), ("normal", rng.normal(0, 1, 500)),
                ("skewed_small", rng.lognormal(0, 1, 37))]:
    for prob in (0.5, 0.89, 0.97):
        iv.append({"samples_name": name, "samples": s.tolist(), "prob": prob,
                   "PI": [float(np.quantile(s, (1 - prob) / 2)), float(np.quantile(s, 1 - (1 - prob) / 2))],
                   "HPDI": hpdi_coda(s, prob)})
out["intervals"] = {"cases": iv, "PI_rule": "R quantile type 7 at (1-prob)/2 and 1-(1-prob)/2",
                    "HPDI_rule": "coda::HPDinterval as in hpdi_coda(); confirm_with_R"}

# ---- WAIC on the deviance scale ----
y = np.concatenate([rng.normal(0, 1, 19), [14.0]]); S = 4000; n = len(y)
pv = 1 / (1 / 10 ** 2 + n); mu = rng.normal(pv * y.sum(), math.sqrt(pv), S)
ll = stats.norm.logpdf(y[None, :], mu[:, None], 1.0)
lppd_i = special.logsumexp(ll, axis=0) - math.log(S)
pw_i = ll.var(axis=0, ddof=1)                     # R var() uses n - 1
waic_i = -2 * (lppd_i - pw_i)
out["waic"] = {"log_likelihood": ll.round(12).tolist(), "lppd": float(lppd_i.sum()), "penalty": float(pw_i.sum()),
               "WAIC": float(waic_i.sum()), "std_err": float(math.sqrt(n * waic_i.var(ddof=1))),
               "pointwise": waic_i.tolist(),
               "rule": "WAIC = -2 (lppd - pWAIC), pWAIC = sum of per-observation variances (n - 1) of the log "
                       "likelihood, std_err = sqrt(n var(pointwise)); deviance scale as in the book; confirm_with_R",
               "relation_to_loo": "compare with psis_loo of multilevel.json: same log likelihood matrix construction"}

# ---- quap (Laplace approximation) ----
q = []
y2 = rng.normal(1.5, 1.0, 12); s0 = 3.0
pv2 = 1 / (1 / s0 ** 2 + len(y2)); pm2 = pv2 * y2.sum()
q.append({"model": "y ~ Gaussian(mu, 1), mu ~ Gaussian(0, 3)", "y": y2.tolist(),
          "mode": {"mu": pm2}, "sd": {"mu": math.sqrt(pv2)},
          "note": "Gaussian posterior: the quadratic approximation is exact"})
a, b = 7.5, 1.6                                    # posterior Gamma(a, b) of a Poisson rate
q.append({"model": "posterior proportional to lambda^(a-1) exp(-b lambda) (Gamma(7.5, 1.6) kernel), lambda > 0",
          "scale": "original (constrained) parameter scale, as quap does",
          "mode": {"lambda": (a - 1) / b}, "sd": {"lambda": math.sqrt(a - 1) / b},
          "note": "mode (a-1)/b; curvature -(a-1)/lambda^2 at the mode gives sd sqrt(a-1)/b"})
out["quap"] = {"cases": q, "rule": "mode by optimisation, covariance = inverse of the negative Hessian of the "
               "log posterior at the mode, on the parameter scale written in the model (not the unconstrained "
               "scale), to match the book"}

# ---- small helpers ----
z = rng.normal(5, 2, 9)
out["helpers"] = {"standardize": {"x": z.tolist(), "value": ((z - z.mean()) / z.std(ddof=1)).tolist(),
                                  "rule": "(x - mean) / sd with sd using n - 1, like R scale()"},
                  "inv_logit": [{"x": v, "value": float(special.expit(v))} for v in (-30.0, -2.0, 0.0, 3.0, 30.0)],
                  "log_sum_exp": [{"x": [1.0, 2.0, 3.0], "value": float(special.logsumexp([1.0, 2.0, 3.0]))}]}

json.dump(out, open(sys.argv[1], "w"), indent=1)
print("densities", len(d), "intervals", len(iv), "WAIC", round(out["waic"]["WAIC"], 3),
      "quap", len(q))
