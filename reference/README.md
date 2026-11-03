# Reference data for `bayes4s`

Real test cases taken from Stan, Stan Math and posteriordb, plus high-precision values from mpmath,
SciPy and properscoring. The two folders were copied into the repository as follows.

```
reference/                                     Python scripts that generate the data (never run by sbt)
modules/testkit/src/main/resources/reference/  the data, on the test classpath of every module via testkit
LICENSES/                                      the BSD-3-Clause texts, moved to the repository root
```

Every value was checked twice before being committed: once by the source that asserts it (a Stan
test, mpmath at 50 digits) and once by an independent implementation (SciPy, or a NumPy port of the
Stan code). `reference/regenerate.sh` rebuilds everything from clones of the three Stan
repositories; a fresh regeneration was compared byte for byte with the committed files.

Sources: stan-dev/math `dc66941` (2026-10-29), stan-dev/stan `a8ec3d1` (2026-10-30),
stan-dev/posteriordb `91027f9` (2026-09-25). All BSD-3-Clause; the licence texts live in `LICENSES/`
at the repository root and must stay there.

## Contents

| File | Content | Plan phase |
|---|---|---|
| `python/special_functions.json` | lgamma, digamma (72 points over 0.001 to 1e5, negatives, poles), sigmoid, log-sigmoid, softplus at ±800, log1m_exp, logit, normal quantile down to p = 1e-300, log-sum-exp, lbeta; mpmath 50 digits | 1 |
| `stan-math/distributions.json` | Stan Math distribution tests: 196 valid cases, 361 invalid-argument cases, 18 distributions, lpdf/cdf/lcdf/lccdf; 124 confirmed by SciPy, 0 disagreements | 1, 2 |
| `python/densities_extreme.json` | 29 densities at extreme arguments, mpmath 50 digits | 1, 2 |
| `stan-math/transforms.json` | lb, ub, lub, offset-multiplier, ordered, sum-to-zero, ILR simplex; log-Jacobians checked numerically; cases asserted by Stan | 3 |
| `stan/hmc/hmc.json` | 11 leapfrog cases, the NUTS `build_tree` case, U-turn criterion, dual averaging, metric regularisation, warmup window schedules | 4 |
| `stan/diagnostics/` | `bern1-4.csv`, `ar1.csv` and the 14 values Stan asserts, NaN edge cases, autocovariance | 5 |
| `posteriordb/` | 9 reference posteriors (Stan model, data, summary of 10 x 1000 draws with MCSE) and centered eight schools for the divergence test | 6 |
| `python/scoring.json` | CRPS of ensembles and Gaussians (properscoring), type-7 quantiles | 8 |
| `python/worked_examples.json` | classic worked examples with made-up numbers: grid posterior, Gamma-Poisson conjugate (with and without exposure), HPD, negative-binomial predictive, shrinkage weights, Metropolis target | 1, 4, 5, 9, 12 |

## How the suites read the files

- `bayes4s.testkit.Reference.json(path)` loads from the classpath and returns
  `Either[Reference.Error, Json]`; the files use Python's `Infinity`, `-Infinity` and `NaN`
  tokens, which the loader accepts, including inside JSON strings where they must stay as prose.
- One Weaver expectation per case, with the case in the clue, and the tolerance recorded in `docs/validation.md`
  (or the one stored with the value, for diagnostics).
- Leapfrog and `build_tree` cases are deterministic and need no RNG. `z_propose` is not asserted
  because it depends on Stan's RNG.
- `reference/stan_analyze_reference.py` is a line-by-line port of Stan's diagnostics that
  reproduces every value Stan asserts; ArviZ 1.3 does not (plan 5.7). Read it as the executable spec.

## Regenerating

```
pip install -r reference/requirements.txt
git clone --depth 1 https://github.com/stan-dev/math        ext/math
git clone --depth 1 https://github.com/stan-dev/stan        ext/stan
git clone --depth 1 https://github.com/stan-dev/posteriordb ext/posteriordb
reference/regenerate.sh ext
```

Run the scripts with `python -I`. A newer Stan may change expected values; diffing the output
against the committed files shows exactly which cases moved.
