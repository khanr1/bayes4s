"""Copy Stan's own diagnostic test data and record the expected values asserted by Stan's C++ tests
(stan/src/test/unit/analyze/mcmc/*_test.cpp), then recompute them with ArviZ as an independent check.

Usage: python -I build_diagnostics_fixtures.py <stan checkout> <out dir>
"""
import json, shutil, sys, pathlib
import numpy as np
import arviz as az
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import stan_analyze_reference as S

STAN = pathlib.Path(sys.argv[1]) / "src/test/unit/analyze/mcmc"
OUT = pathlib.Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
for i in range(1, 5):
    shutil.copy(STAN / f"test_csv_files/bern{i}.csv", OUT / f"bern{i}.csv")
shutil.copy(STAN / "test_csv_files/cmdstan-2-35-0-stansummary.csv", OUT / "cmdstan-2-35-0-stansummary.csv")
shutil.copy(STAN / "ar1.csv", OUT / "ar1.csv")

def load(col):
    m = []
    for i in range(1, 5):
        rows = [l for l in (OUT / f"bern{i}.csv").read_text().splitlines() if l and not l.startswith("#")]
        hdr = rows[0].split(","); j = hdr.index(col)
        m.append([float(r.split(",")[j]) for r in rows[1:]])
    return np.array(m)  # (chain, draw)

lp, theta = load("lp__"), load("theta")

def arviz_values(x):
    da = az.convert_to_dataset(x[None, :, :] if False else {"x": x})
    return {
        "rhat_rank": float(az.rhat(da, method="rank")["x"]),
        "rhat_basic_split": float(az.rhat(da, method="split")["x"]),
        "ess_bulk": float(az.ess(da, method="bulk")["x"]),
        "ess_tail": float(az.ess(da, method="tail")["x"]),
        "ess_basic": float(az.ess(da, method="mean")["x"]),
        "mcse_mean": float(az.mcse(da, method="mean")["x"]),
        "mcse_sd": float(az.mcse(da, method="sd")["x"]),
    }

# Expected values copied verbatim from Stan's tests, with the tolerance Stan itself uses.
expected = {
    "lp__": {
        "rhat_basic_split": [1.00035489482, 1e-10, "rhat_basic_test.cpp (cmdstan 2.35.0 stansummary)"],
        "ess_basic": [1335.41366787, 1e-8, "ess_basic_test.cpp (cmdstan 2.35.0 stansummary)"],
        "rhat_rank": [1.00073, 1e-5, "split_rank_normalized_rhat_test.cpp (R posterior), max(bulk, tail)"],
        "ess_bulk": [1512.7684, 1e-4, "split_rank_normalized_ess_test.cpp (R posterior)"],
        "ess_tail": [1591.9707, 1e-4, "split_rank_normalized_ess_test.cpp (R posterior)"],
        "mcse_mean": [0.02016, 1e-4, "mcse_test.cpp (R posterior)"],
        "mcse_sd": [0.03553, 1e-4, "mcse_test.cpp (R posterior)"],
    },
    "theta": {
        "rhat_basic_split": [1.00721797217, 1e-10, "rhat_basic_test.cpp (cmdstan 2.35.0 stansummary)"],
        "ess_basic": [1377.5030282, 1e-8, "ess_basic_test.cpp (cmdstan 2.35.0 stansummary)"],
        "rhat_rank": [1.006789, 1e-5, "split_rank_normalized_rhat_test.cpp (R posterior), max(bulk, tail)"],
        "ess_bulk": [1407.5124, 1e-4, "split_rank_normalized_ess_test.cpp (R posterior)"],
        "ess_tail": [1291.7131, 1e-4, "split_rank_normalized_ess_test.cpp (R posterior)"],
        "mcse_mean": [0.00323, 1e-4, "mcse_test.cpp (R posterior)"],
        "mcse_sd": [0.00216, 1e-4, "mcse_test.cpp (R posterior)"],
    },
}
report = {}
def port_values(x):
    c = x.T
    b, t = S.split_rank_normalized_rhat(c); eb, et = S.split_rank_normalized_ess(c)
    return {"rhat_rank": max(b, t), "rhat_basic_split": S.rhat_basic(c), "ess_bulk": eb,
            "ess_tail": et, "ess_basic": S.ess(c), "mcse_mean": S.mcse_mean(c), "mcse_sd": S.mcse_sd(c)}

for name, x in (("lp__", lp), ("theta", theta)):
    av = arviz_values(x); pv = port_values(x)
    for k, (v, tol, src) in expected[name].items():
        report.setdefault(name, {})[k] = {
            "expected": v, "tolerance": tol, "source": src,
            "numpy_port": float(pv[k]), "numpy_port_ok": bool(abs(pv[k] - v) <= tol),
            "arviz": av[k], "arviz_abs_diff": abs(av[k] - v),
        }

ar1 = np.array([float(l) for l in (OUT / "ar1.csv").read_text().split()])
n = len(ar1); xc = ar1 - ar1.mean()
acov_biased = np.array([np.dot(xc[: n - k], xc[k:]) / n for k in range(6)])

fixture = {
    "provenance": {
        "data": "stan-dev/stan src/test/unit/analyze/mcmc (BSD-3-Clause): bern1-4.csv are 4 CmdStan "
                "chains of the Bernoulli example, 1000 draws each; ar1.csv is an AR(1) series "
                "x[n] ~ normal(0.8 x[n-1], 1) generated in R.",
        "expected": "values asserted by Stan's own C++ tests (with Stan's tolerance). 'numpy_port' is "
                    "reference/stan_analyze_reference.py, a line-by-line port that reproduces all of them. "
                    "'arviz' is ArviZ " + az.__version__ + ", kept to document that it does NOT match Stan for "
                    "ess_basic (non-split), ess_tail (upper tail uses >=) and mcse_sd (ESS of |x - mean|): "
                    "the Scala library must follow Stan, not ArviZ 1.x.",
    },
    "columns": {"lp__": "column 'lp__' of each chain", "theta": "column 'theta' of each chain",
                "constant": "column 'divergent__' (all zero): every diagnostic must return NaN"},
    "chains": {"rows": "draw", "count": 4, "draws": 1000},
    "values": report,
    "edge_cases": [
        "constant chains (divergent__ column): rhat, ess_bulk, ess_tail, mcse_mean, mcse_sd are NaN",
        "one value set to +Inf in theta chain 0, draw 0: all diagnostics are NaN",
        "chains truncated to 3 draws: ess and mcse are NaN",
        "rank transform: the argmax and argmin positions are preserved",
        "rank transform of 8x2 matrix filled 1..16 column-major is antisymmetric: r[i,j] = -r[7-i,1-j]",
        "rank transform of 2x8 matrix with rows (1, 2): r[0,j] = -r[1,j]",
    ],
    "autocovariance_ar1": {
        "expected_first_6": [2.69, 2.16, 1.73, 1.38, 1.10, 0.89], "tolerance": 0.01,
        "source": "autocovariance_test.cpp; length of result equals 1000",
        "numpy_biased_estimator": acov_biased.tolist(),
    },
}
(OUT / "expected.json").write_text(json.dumps(fixture, indent=1))
for name in report:
    for k, r in report[name].items():
        print(f"{name:6s} {k:17s} stan={r['expected']:<14} port={r['numpy_port']:.10g} "
              f"{'ok' if r['numpy_port_ok'] else 'FAIL'} arviz={r['arviz']:.10g}")
print("acov", np.round(acov_biased, 3))
