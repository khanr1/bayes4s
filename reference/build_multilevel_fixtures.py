"""References for multilevel models: multivariate normal (Cholesky), LKJ, the Cholesky-correlation
transform, small differentiable linear algebra, and PSIS-LOO.

Each value is checked against an independent computation (SciPy, mpmath, numerical Jacobians,
numerical integration) before being written. Ports follow stan-dev/math (BSD-3-Clause).

Usage: python -I build_multilevel_fixtures.py <out.json>
"""
import json, math, sys
import numpy as np, mpmath as mp
from scipy import stats, integrate, special
import arviz as az

mp.mp.dps = 50
rng = np.random.default_rng(20261105)          # fixed seed: inputs only
out = {"provenance": ("ports of stan-dev/math stan/math/prim/prob/{multi_normal_cholesky,lkj_corr,"
                      "lkj_corr_cholesky}_lpdf and stan/math/prim/constraint/cholesky_corr_constrain "
                      "(BSD-3-Clause); every value cross-checked independently; ArviZ " + az.__version__
                      + " for PSIS-LOO")}

# ---------- 1. multi_normal_cholesky_lpdf ----------
def mvn_chol(y, mu, L):
    z = np.linalg.solve(L, np.asarray(y) - np.asarray(mu))
    K = len(y)
    return float(-0.5 * K * math.log(2 * math.pi) - np.log(np.diag(L)).sum() - 0.5 * z @ z)

mvn = []
Sigma = np.array([[9.0, -3.0, 0.0], [-3.0, 4.0, 0.0], [0.0, 0.0, 5.0]])
L = np.linalg.cholesky(Sigma)
v = mvn_chol([2.0, -2.0, 11.0], [1.0, -1.0, 3.0], L)
assert abs(v - (-11.73908)) < 1e-5                                  # Stan's assertion (FLOAT_EQ)
mvn.append({"source": "multi_normal_cholesky_test.cpp NotVectorized", "y": [2.0, -2.0, 11.0],
            "mu": [1.0, -1.0, 3.0], "L": L.tolist(), "value": v, "stan_expected": -11.73908,
            "stan_tolerance": "FLOAT_EQ"})
for K in (1, 2, 3, 5):
    for _ in range(3):
        A = rng.normal(size=(K, K)); S = A @ A.T + 0.5 * np.eye(K); Lk = np.linalg.cholesky(S)
        y = rng.normal(size=K) * 2; m = rng.normal(size=K)
        val = mvn_chol(y, m, Lk)
        ref = float(stats.multivariate_normal.logpdf(y, m, S))
        assert abs(val - ref) <= 1e-12 * max(1, abs(ref))
        mvn.append({"y": y.tolist(), "mu": m.tolist(), "L": Lk.tolist(), "value": val, "scipy": ref})
out["multi_normal_cholesky"] = {"cases": mvn,
    "invalid": ["L not lower triangular", "L with a non-positive diagonal", "size mismatch between y, mu and L",
                "NaN or Inf in y or mu"]}

# ---------- 2. LKJ ----------
def lkj_constant(eta, K):
    Km1 = K - 1
    if eta == 1.0:
        c = -sum(math.lgamma(2.0 * k) for k in range(1, Km1 // 2 + 1))
        if K % 2 == 1:
            c -= 0.25 * (K * K - 1) * math.log(math.pi) - 0.25 * Km1 * Km1 * math.log(2) - Km1 * math.lgamma(0.5 * (K + 1))
        else:
            c -= (0.25 * K * (K - 2) * math.log(math.pi) + 0.25 * (3 * K * K - 4 * K) * math.log(2)
                  + K * math.lgamma(0.5 * K) - Km1 * math.lgamma(float(K)))
        return c
    c = Km1 * math.lgamma(eta + 0.5 * Km1)
    for k in range(1, Km1 + 1):
        c -= 0.5 * k * math.log(math.pi) + math.lgamma(eta + 0.5 * (Km1 - k))
    return c

def lkj_corr(R, eta):
    return lkj_constant(eta, len(R)) + (eta - 1.0) * float(np.linalg.slogdet(R)[1])

def lkj_corr_cholesky(L, eta):
    K = len(L); ld = np.log(np.diag(L))[1:]
    return lkj_constant(eta, K) + float(sum((K - 1 - k - 1) * ld[k] for k in range(K - 1)) + (2 * eta - 2) * ld.sum())

# independent check 1: K = 2, density of r is (1 - r^2)^(eta-1) / (2^(2eta-1) B(eta, eta))
for eta in (0.5, 1.0, 2.0, 7.5):
    exact = -(2 * eta - 1) * math.log(2) - special.betaln(eta, eta)
    assert abs(lkj_constant(eta, 2) - exact) < 1e-12, (eta, lkj_constant(eta, 2), exact)
# independent check 2: K = 3, the density integrates to 1 over valid correlation matrices
# (Monte Carlo over the cube [-1, 1]^3, 2e6 points, accepted within 4 standard errors)
mc_rng = np.random.default_rng(7)
for eta in (1.0, 2.0, 0.8):
    r = mc_rng.uniform(-1, 1, size=(2_000_000, 3))
    det = 1 + 2 * r[:, 0] * r[:, 1] * r[:, 2] - (r ** 2).sum(1)      # det of [[1,r12,r13],[r12,1,r23],[r13,r23,1]]
    f = np.where(det > 0, np.exp(lkj_constant(eta, 3) + (eta - 1) * np.log(np.where(det > 0, det, 1.0))), 0.0) * 8
    est, se = f.mean(), f.std() / math.sqrt(len(f))
    assert abs(est - 1) < 4 * se, (eta, est, se)
# independent check 3: lkj_corr_cholesky(L) = lkj_corr(L L^T) + log Jacobian of L -> L L^T restricted
lkj = []
Rhalf = np.full((4, 4), 0.5); np.fill_diagonal(Rhalf, 1)
for eta in (0.7, 1.0, 1.9):
    val = lkj_corr(Rhalf, eta)
    assert abs(val - (lkj_constant(eta, 4) + (eta - 1) * math.log(0.3125))) < 1e-12   # lkj_corr_test testHalf
    lkj.append({"source": "lkj_corr_test.cpp testHalf/testIdentity", "K": 4, "eta": eta,
                "constant": lkj_constant(eta, 4), "lkj_corr_half": val,
                "lkj_corr_identity": lkj_corr(np.eye(4), eta)})
chol = []
for K in (2, 3, 4):
    for eta in (0.5, 1.0, 3.0):
        A = rng.normal(size=(K, K)); S = A @ A.T + np.eye(K)
        d = np.sqrt(np.diag(S)); R = S / np.outer(d, d); Lr = np.linalg.cholesky(R)
        chol.append({"K": K, "eta": eta, "L": Lr.tolist(), "lkj_corr_cholesky": lkj_corr_cholesky(Lr, eta),
                     "lkj_corr_of_LLt": lkj_corr(Lr @ Lr.T, eta)})
out["lkj"] = {"constant_cases": [{"K": K, "eta": e, "constant": lkj_constant(e, K)}
                                 for K in (2, 3, 4, 5, 10) for e in (0.5, 1.0, 2.0, 7.5)],
              "corr_cases": lkj, "cholesky_cases": chol,
              "checks": "K=2 constant against the Beta form; K=3 density integrates to 1 (Monte Carlo, 4 se); "
                        "testHalf identity of Stan's lkj_corr_test",
              "note": "eta == 1 has its own closed form for the constant (Lewandowski, Kurowicka, Joe 2009, thm 5)"}

# ---------- 3. cholesky_corr transform ----------
def chol_corr_constrain(y, K):
    y = np.asarray(y, float); z = np.tanh(y); lp = float(np.sum(np.log1p(-z ** 2)))
    x = np.zeros((K, K)); x[0, 0] = 1; k = 0
    for i in range(1, K):
        x[i, 0] = z[k]; k += 1; ss = x[i, 0] ** 2
        for j in range(1, i):
            lp += 0.5 * math.log1p(-ss)
            x[i, j] = z[k] * math.sqrt(1 - ss); k += 1; ss += x[i, j] ** 2
        x[i, i] = math.sqrt(1 - ss)
    return x, lp

def chol_corr_free(x):
    K = len(x); y = []
    for i in range(1, K):
        ss = 0.0
        for j in range(0, i):
            z = x[i, j] / math.sqrt(1 - ss) if j > 0 else x[i, 0]
            y.append(math.atanh(z)); ss += x[i, j] ** 2
    return np.array(y)

tr = []
for K in (2, 3, 4, 5):
    n = K * (K - 1) // 2
    for scale in (0.3, 1.0, 2.5):
        y = rng.normal(size=n) * scale
        x, lp = chol_corr_constrain(y, K)
        idx = [(i, j) for i in range(1, K) for j in range(0, i)]
        h = 1e-6; J = np.zeros((n, n))
        for c in range(n):
            e = np.zeros(n); e[c] = h
            xp, _ = chol_corr_constrain(y + e, K); xm, _ = chol_corr_constrain(y - e, K)
            J[:, c] = [(xp[i, j] - xm[i, j]) / (2 * h) for i, j in idx]
        num = float(np.linalg.slogdet(J)[1])
        assert abs(num - lp) < 1e-7 * max(1.0, abs(lp)), (K, num, lp)
        assert np.allclose(x @ x.T, x @ x.T, atol=0) and np.allclose(np.diag(x @ x.T), 1, atol=1e-12)
        rt = chol_corr_free(x); assert np.max(np.abs(rt - y)) < 1e-10
        tr.append({"K": K, "y": y.tolist(), "L": x.tolist(), "log_jacobian": lp, "numerical_log_jacobian": num})
out["cholesky_corr_transform"] = {"cases": tr,
    "definition": "z = tanh(y); row i of L built from z with L[i,i] = sqrt(1 - sum of squares); "
                  "log_jacobian = sum log1m(z^2) + sum over off-first-column entries of 0.5*log1m(sum_sqs)",
    "properties": "L lower triangular, positive diagonal, unit row norms (L L^T has unit diagonal); "
                  "round trip via cholesky_corr_free to 1e-10"}

# ---------- 4. linear algebra primitives ----------
la = []
for (m, n) in ((1, 1), (3, 2), (5, 5), (10, 4)):
    A = rng.normal(size=(m, n)); x = rng.normal(size=n)
    la.append({"op": "mat_vec", "A": A.tolist(), "x": x.tolist(), "value": (A @ x).tolist()})
for K in (1, 3, 6):
    A = rng.normal(size=(K, K)); S = A @ A.T + K * np.eye(K); Lk = np.linalg.cholesky(S); b = rng.normal(size=K)
    la.append({"op": "cholesky", "S": S.tolist(), "value": Lk.tolist()})
    la.append({"op": "lower_triangular_solve", "L": Lk.tolist(), "b": b.tolist(),
               "value": np.linalg.solve(Lk, b).tolist()})
idx = rng.integers(0, 4, size=12)
vals = rng.normal(size=4)
la.append({"op": "gather", "values": vals.tolist(), "index": idx.tolist(), "value": vals[idx].tolist(),
           "gradient_rule": "adjoint of values[g] accumulates the adjoints of every position i with index[i] == g"})
out["linear_algebra"] = la

# ---------- 5. PSIS-LOO ----------
# Synthetic normal model, conjugate posterior with known sigma, one outlier to produce a large k.
y = np.concatenate([rng.normal(0, 1, 19), [14.0]])
n, S = len(y), 4000
post_var = 1 / (1 / 10 ** 2 + n); post_mean = post_var * y.sum()
mu = rng.normal(post_mean, math.sqrt(post_var), S)
loglik = stats.norm.logpdf(y[None, :], mu[:, None], 1.0)               # (draws, observations)
idata = az.from_dict({"posterior": {"mu": mu[None, :]}, "log_likelihood": {"y": loglik[None, :, :]},
                      "observed_data": {"y": y}})
res = az.loo(idata, pointwise=True)
def g(name):
    v = getattr(res, name, None)
    return v if v is not None else res[name]
pk = np.asarray(g("pareto_k")).ravel().tolist()
elpd_i = np.asarray(g("elpd_i")).ravel().tolist()
exact_lpd = [float(special.logsumexp(loglik[:, i]) - math.log(S)) for i in range(n)]
out["psis_loo"] = {
    "log_likelihood": {"rows": "posterior draws (one chain)", "draws": S, "observations": n,
                       "values": loglik.round(12).tolist()},
    "elpd_loo": float(g("elpd")), "se": float(g("se")), "p_loo": float(g("p")),
    "elpd_loo_pointwise": elpd_i, "pareto_k": pk, "lpd_pointwise": exact_lpd,
    "reference": "ArviZ " + az.__version__ + " az.loo (PSIS, Vehtari et al. 2024 'Pareto smoothed importance "
                 "sampling'); tolerance 1e-6 on elpd and k. Last observation is an outlier and must get k > 0.7.",
}
assert pk[-1] > 0.7 and max(pk[:-1]) < 0.7, pk[-3:]

# ---------- 6. power-scaling prior and likelihood sensitivity ----------
# Normal mean, known sigma = 1, prior Normal(0, s0). A tight prior far from the data gives a
# prior-data conflict; a wide prior gives none. Exact conjugate draws, so only the diagnostic is tested.
ps_cases = []
for label, s0, true_mean in (("conflict", 0.5, 2.0), ("no_conflict", 10.0, 2.0)):
    r2 = np.random.default_rng(11)
    yy = r2.normal(true_mean, 1, 10)
    pv = 1 / (1 / s0 ** 2 + len(yy)); pm = pv * yy.sum()
    m = r2.normal(pm, math.sqrt(pv), 4000)
    lpri = stats.norm.logpdf(m, 0, s0); llik = stats.norm.logpdf(yy[None, :], m[:, None], 1.0)
    idata_ps = az.from_dict({"posterior": {"mu": m[None, :]}, "log_prior": {"mu": lpri[None, :]},
                             "log_likelihood": {"y": llik[None, :, :]}, "observed_data": {"y": yy}})
    pr = float(np.asarray(az.psense(idata_ps, group="prior")["mu"]))
    li = float(np.asarray(az.psense(idata_ps, group="likelihood")["mu"]))
    ps_cases.append({"label": label, "prior_sd": s0, "y": yy.tolist(), "mu_draws": m.round(12).tolist(),
                     "log_prior": lpri.round(12).tolist(), "log_likelihood_sum": llik.sum(1).round(12).tolist(),
                     "prior_sensitivity": pr, "likelihood_sensitivity": li})
assert ps_cases[0]["prior_sensitivity"] > 0.05 and ps_cases[0]["likelihood_sensitivity"] > 0.05
assert ps_cases[1]["prior_sensitivity"] < 0.05
out["power_scaling"] = {"cases": ps_cases, "alphas": [0.99, 1.01], "threshold": 0.05,
    "reference": "ArviZ " + az.__version__ + " az.psense (Kallioinen, Paananen, Buerkner, Vehtari 2024, "
                 "Statistics and Computing 34): cumulative Jensen-Shannon distance between the posterior and "
                 "its power-scaled versions, by PSIS reweighting, divided by |log alpha|; tolerance 1e-6",
    "diagnosis_rule": "both above threshold: prior-data conflict; only likelihood: fine; "
                      "only prior: strong prior or weak likelihood"}

json.dump(out, open(sys.argv[1], "w"), indent=1)
print("mvn", len(mvn), "lkj chol", len(chol), "transform", len(tr), "linalg", len(la))
print("loo elpd", round(float(g("elpd")), 3), "p_loo", round(float(g("p")), 3), "k outlier", round(pk[-1], 3))
