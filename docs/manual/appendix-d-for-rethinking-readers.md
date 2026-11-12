# Appendix D. For readers of *Statistical Rethinking*

The optional `rethinking` module gives this library's functions the names McElreath's R package
uses, so that you can work through the book without translating as you read. It adds no statistics:
every function calls a module below it and returns this library's own types.

```
libraryDependencies += "io.github.bayes4s" %% "bayes4s-rethinking" % "<version>"
```

It is a separate artefact, so a user who does not want the book's vocabulary never sees it. Nothing
is published yet, so for now this is read against a local build.

## On the R package

This module is **not connected with the package or its author**, and is not endorsed by either. The
package is distributed under the GPL. Its function **names** and the **parameterisations its
documentation states** are reproduced here; its source code is not, and has not been read —
translating it would make this module a derived work under a licence incompatible with this
library's. None of its text, examples or datasets appears here either: the reference values this
module is tested against were computed independently with NumPy and SciPy, and the examples use the
invented world this repository generates itself.

CI enforces the mechanical floor under that rule — no R in the module, none of the package's
dataset names anywhere in the repository — and `docs/validation.md` records the review.

## The table

```scala mdoc:silent
import bayes4s.*
import bayes4s.rethinking.*
```

| In the book | Here | What it calls |
|---|---|---|
| `quap(flist, data, start)` | `Rethinking.quap(logPosterior, start, names)` | `Optimize.lbfgs` then `Optimize.hessian` |
| `ulam(flist, data, chains)` | `Fits.ulam(model, chains)` | `Mcmc.runNuts`; `Fits.ulamParallel` is the same in `IO` |
| `precis(fit, prob = 0.89)` | `Fits.precis(model, chains)`, `Fits.precisText(…, prob)` | `Summary` |
| `extract.samples(fit)` | `Fits.extractSamples` | `Mcmc.draws`, or the Laplace Gaussian for a `quap` fit |
| `extract.prior(fit)` | `Fits.extractPrior(model)` | `Model.priorOnly` |
| `link(fit, data)` | `Fits.link(model, chains)` | `Model.derived`, one row per sample |
| `sim(fit, data)` | `Fits.sim(generative, chains)` | `Generative.simulateData`, one dataset per sample |
| `ensemble(...)` | `Fits.ensemble(simulations, weights)` | the mixture `compare`'s weights describe |
| `PI(samples, prob = 0.89)` | `Rethinking.PI` | `Intervals.equalTailed` |
| `HPDI(samples, prob = 0.89)` | `Rethinking.HPDI` | `Intervals.hpd`, which is `coda::HPDinterval`'s rule |
| `WAIC(fit)` | `Rethinking.WAIC(logLik)` | the deviance scale, as the book uses it |
| `PSIS(fit)`, `LOO(fit)` | `Rethinking.PSIS(logLik)` | `Loo.psis`, reported as `-2 elpd` |
| `compare(...)` | `Fits.compare(models)` | differences, their errors, and Akaike weights |
| `coeftab(...)` | `Fits.coeftab(summaries)` | a coefficient table across models |
| `standardize`, `inv_logit`, `logit`, `log_sum_exp` | `Rethinking.standardize`, `invLogit`, `logit`, `logSumExp` | `core` |
| `dgampois(x, mu, scale)` | `Rethinking.dgampois` | `LogMass.negativeBinomial2` at `phi = mu / scale` |
| `dbetabinom(x, size, prob, theta)` | `Rethinking.dbetabinom` | `betaBinomial` at `a = prob θ`, `b = (1 - prob) θ` |
| `dordlogit(x, phi, a)` | `Rethinking.dordlogit` | `orderedLogistic`, categories from one |
| `dzipois(x, p, lambda)` | `Rethinking.dzipois` | `LogMass.zeroInflatedPoisson` |
| `dstudent(x, nu, mu, sigma)` | `Rethinking.dstudent` | `studentT` |
| `dmvnorm2(x, Mu, sigma, Rho)` | `Rethinking.dmvnorm2` | `multiNormalCholesky` of `diag(σ) Rho diag(σ)` |
| `dlkjcorr(x, eta)` | `Rethinking.dlkjcorr` | `LogDensity.lkjCorr` |
| `dens`, `shade`, `postcheck`, `traceplot`, `trankplot`, `plot(precis)` | `Figures.dens`, `shade`, `postcheck`, `traceplot`, `trankplot`, `precisPlot` | the `plot` catalogue |

Two things are deliberately absent. The `alist`/`flist` formula language: models are written with
this library's `Model` API, which the compiler checks and which chapter 2 covers. And `drawdag`: a
causal diagram is not a statistical figure, and this library has no graph drawing in it.

## The intervals are 89 %, here only

The book's default interval mass is 89 %, and so is this module's. The rest of the library always
takes the mass explicitly, because a default that differs between two parts of one library is a
trap rather than a convenience.

```scala mdoc
val samples = IArray.tabulate(2000)(i => -2.0 * math.log((i + 0.5) / 2000.0))
(
  "PI at the book's default" -> Rethinking.PI(samples),
  "the same, asked for" -> Rethinking.PI(samples, 0.89),
  "HPDI, which is narrower on a skewed posterior" -> Rethinking.HPDI(samples)
)
```

`HPDI` is `coda::HPDinterval`'s rule exactly, down to the rounding: the interval spans
`round(n × prob)` gaps between order statistics, where `round` is R's — half to even. That last
detail is the difference between reproducing the reference and nearly reproducing it.

## WAIC is a deviance, and smaller is better

This is the sign that catches people moving between the book and the rest of this library.
`Rethinking.WAIC` and `Rethinking.PSIS` are on the **deviance** scale, `-2 × (lppd - penalty)` and
`-2 × elpd`, so a better model has a *smaller* number. `Loo.psis` reports `elpd`, where a better
model has a *larger* one.

```scala mdoc
// Twenty observations, four thousand samples: a log likelihood matrix as any fit produces.
val logLik = IArray.tabulate(20)(i => IArray.tabulate(400)(s => -2.0 - 0.001 * s - 0.1 * i))
val waic = Rethinking.WAIC(logLik)
val (deviance, loo) = Rethinking.PSIS(logLik)
(
  "WAIC" -> (waic.waic * 100).round / 100.0,
  "its penalty" -> (waic.penalty * 1e6).round / 1e6,
  "PSIS as a deviance" -> (deviance * 100).round / 100.0,
  "the same as elpd" -> (loo.elpd * 100).round / 100.0
)
```

`compare` therefore ranks smallest first, and its weights are the Akaike weights:
`exp(-difference/2)`, normalised. The standard error of a difference comes from the pointwise
differences, because two models scored on the same observations have correlated errors — the same
point [chapter 4](04-diagnosing.md) makes about `Loo.compare`, and the same arithmetic.

## `quap` works on the parameters' own scale

The book's `quap` is a quadratic approximation of the log posterior **as written**, not of its
unconstrained reparameterisation, and this follows it. For a positive parameter that matters: the
mode of a Gamma(a, b) kernel is `(a-1)/b` on its own scale, and something else on the log scale.

```scala mdoc
// The Gamma(7.5, 1.6) kernel, whose mode is 6.5/1.6 and whose curvature gives sd sqrt(6.5)/1.6.
val kernel = (p: IArray[Double]) =>
  if p(0) <= 0.0 then Double.NegativeInfinity else 6.5 * math.log(p(0)) - 1.6 * p(0)

Rethinking.quap(kernel, IArray(3.0), List("lambda")).map { fitted =>
  (
    "mode" -> (fitted.mode(0) * 1e6).round / 1e6,
    "sd" -> (fitted.standardDeviation(0) * 1e6).round / 1e6,
    "analytic mode" -> (6.5 / 1.6 * 1e6).round / 1e6,
    "analytic sd" -> (math.sqrt(6.5) / 1.6 * 1e6).round / 1e6
  )
}
```

The mode comes from L-BFGS and the covariance from the inverse of the negative Hessian there. A
flat or runaway posterior has no quadratic approximation, and `quap` returns the reason rather than
a number:

```scala mdoc
(
  // A flat posterior has no mode; an increasing one runs away. Both are refused.
  Rethinking.quap(_ => 0.0, IArray(0.0), List("mu")).isLeft,
  Rethinking.quap(p => p(0), IArray(0.0), List("mu")).isLeft
)
```

`quap`'s gradient is central differences, because a log posterior given as a function carries no
derivative. For anything large enough for that to matter, `ulam` is the path — which is what the
book says too.

## Where the vocabularies differ

The prose of this manual uses the book's words, and the [glossary](glossary.md) is the bridge to
the API's. Two differences are worth naming because they are not in the glossary:

- the book's `n_eff` is this library's **effective number of samples**, reported as `ESS_bulk` and
  `ESS_tail` — two numbers rather than one, because the middle of a distribution and its tails are
  not estimated equally well;
- the book's `sigma` in a `quap` summary is a marginal standard deviation of the Laplace
  Gaussian, which is not the posterior standard deviation unless the posterior really is one.
  [Chapter 4](04-diagnosing.md)'s diagnostics apply to `ulam` fits and not to `quap` ones, because
  a quadratic approximation has no chains to compare.
