# Appendix A. Coming from Stan

Every name here is Stan's, and every name in the right column is this library's. The naming rule is
that there is no naming rule beyond Stan's: a function that exists in Stan keeps its name in
camel case, and where this library has something Stan does not, the name says what it is.

The table is checked by `scripts/check-stan-names.py` in CI, which looks each Scala name up in the
sources. A function that is renamed and a row that is not breaks the build.

## Densities and masses

<!-- stan-names:begin -->

| Stan | bayes4s |
|---|---|
| `normal_lpdf` | `LogDensity.normal` |
| `std_normal_lpdf` | `LogDensity.normal` |
| `lognormal_lpdf` | `LogDensity.logNormal` |
| `gamma_lpdf` | `LogDensity.gamma` |
| `beta_lpdf` | `LogDensity.beta` |
| `logistic_lpdf` | `LogDensity.logistic` |
| `student_t_lpdf` | `LogDensity.studentT` |
| `cauchy_lpdf` | `LogDensity.cauchy` |
| `exponential_lpdf` | `LogDensity.exponential` |
| `ordered_logistic_lpmf` | `LogDensity.orderedLogistic` |
| `multi_normal_cholesky_lpdf` | `LogDensity.multiNormalCholesky` |
| `lkj_corr_cholesky_lpdf` | `LogDensity.lkjCorrCholesky` |
| `poisson_lpmf` | `LogMass.poisson` |
| `poisson_log_lpmf` | `LogMass.poissonLog` |
| `neg_binomial_2_lpmf` | `LogMass.negativeBinomial2` |
| `neg_binomial_lpmf` | `LogMass.negativeBinomial` |
| `binomial_lpmf` | `LogMass.binomial` |
| `bernoulli_logit_lpmf` | `LogMass.bernoulliLogit` |
| `binomial_logit_lpmf` | `LogMass.binomialLogit` |
| `beta_binomial_lpmf` | `LogMass.betaBinomial` |
| `inv_logit` | `LogExp.invLogit` |
| `log_inv_logit` | `LogExp.logInvLogit` |
| `log1m_inv_logit` | `LogExp.log1mInvLogit` |
| `log1p_exp` | `LogExp.log1pExp` |
| `log1m_exp` | `LogExp.log1mExp` |
| `logit` | `LogExp.logit` |
| `log_sum_exp` | `LogExp.logSumExp` |
| `log_diff_exp` | `LogExp.logDiffExp` |
| `lgamma` | `Special.lgamma` |
| `digamma` | `Special.digamma` |
| `lbeta` | `Special.lbeta` |
| `inv_Phi` | `Special.invPhi` |
| `quantile` | `Quantile.quantile` |
| `cumulative_sum` | `Real.cumulativeSum` |
| `dot_product` | `Real.dot` |
| `softmax` | `Transform.of` |
| `cholesky_decompose` | `Linalg.cholesky` |
| `mdivide_left_tri_low` | `Linalg.lowerSolve` |
| `sum_to_zero_constrain` | `Constraint.SumToZero` |
| `simplex_constrain` | `Constraint.Simplex` |
| `ordered_constrain` | `Constraint.Ordered` |
| `offset_multiplier_constrain` | `Constraint.OffsetMultiplier` |
| `cholesky_corr_constrain` | `Constraint.CholeskyFactorCorr` |
| `rhat` | `Convergence.rhatRank` |
| `ess_bulk` | `Convergence.essBulk` |
| `ess_tail` | `Convergence.essTail` |
| `mcse_mean` | `Convergence.mcseMean` |
| `mcse_sd` | `Convergence.mcseSd` |
| `autocovariance` | `Convergence.autocovariance` |

<!-- stan-names:end -->

Two rows need a word. `std_normal_lpdf` is `LogDensity.normal` with a mean of zero and a scale of
one, because a second name for the same formula is a second thing to keep right.
`softmax` is not a function here: it is what `Constraint.Simplex` applies, and a model that wants a
simplex declares one rather than calling anything.

## Structure

| Stan | bayes4s |
|---|---|
| `data { ... }` | whatever the model closes over: a case class, or arguments to the function that builds it |
| `transformed data { ... }` | computed once where the model is built |
| `parameters { ... }` | a `ParamSpec`, combined with `mapN` |
| `real<lower=0> sigma` | `ParamSpec.positive("sigma")` |
| `real<lower=0, upper=1> theta` | `ParamSpec.unitInterval("theta")` |
| `real<lower=a, upper=b> x` | `ParamSpec.bounded("x", a, b)` |
| `vector[n] v` | `ParamSpec.vector("v", n)` |
| `ordered[n] c` | `ParamSpec.vector("c", n, Constraint.Ordered)` |
| `simplex[n] p` | `ParamSpec.vector("p", n, Constraint.Simplex)` |
| `cholesky_factor_corr[k] L` | `ParamSpec.choleskyCorr("L", k)` |
| `transformed parameters { ... }` | a `def` on the model, or `derived` when it is only to be reported |
| `model { x ~ d(...); }` | `logPrior` or `logLikelihood`, whichever it is |
| `target += ...` | the value `logLikelihood` returns |
| `generated quantities { ... }` | `derived`, or the `predictive` module |

The one difference that is not a renaming: Stan's `~` statements and `target +=` all add to one
accumulator, and here the prior and the likelihood are **separate methods**. That is what makes
`Model.priorOnly` possible, and `Model.priorOnly` is what a prior predictive simulation and
simulation-based calibration need.

## Sampler settings

| CmdStan | bayes4s |
|---|---|
| `num_samples` | `RunSettings(samples = ...)` |
| `num_warmup` | `RunSettings(warmup = ...)` |
| `num_chains` | `RunSettings(chains = ...)` |
| `adapt delta` | `AdaptationSettings(targetAccept = ...)` |
| `adapt gamma`, `kappa`, `t0` | the same names on `AdaptationSettings` |
| `adapt init_buffer`, `term_buffer`, `window` | `initBuffer`, `termBuffer`, `baseWindow` |
| `max_depth` | `NutsSettings(maxTreeDepth = ...)` |
| `metric = diag_e` | `AdaptationSettings(metric = MetricShape.Diagonal)`, the default |
| `metric = dense_e` | `AdaptationSettings(metric = MetricShape.Dense)` |
| `init = 2` | `Init.Uniform(2.0)`, the default |
| `init = <file>` | `Init.Given(values)` |
| `seed` | `Seed(...)`, passed to `runA` |
| `lp__`, `accept_stat__`, … | the fields of `Transition`, and the columns `StanCsv` writes |

## A model, line by line

posteriordb's `eight_schools_noncentered.stan`:

```stan
data {
  int<lower=0> J;
  array[J] real y;
  array[J] real<lower=0> sigma;
}
parameters {
  vector[J] theta_trans;
  real mu;
  real<lower=0> tau;
}
transformed parameters {
  vector[J] theta;
  theta = theta_trans * tau + mu;
}
model {
  theta_trans ~ normal(0, 1);
  y ~ normal(theta, sigma);
  mu ~ normal(0, 5);
  tau ~ cauchy(0, 5);
}
```

and the same model here:

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

val y = IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0)
val sigma = IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
val schools = y.length

final case class Params[A](thetaTrans: IArray[A], mu: A, tau: A)

def effects[A: Real](p: Params[A]): IArray[A] =
  Real[A].vectorOf(p.thetaTrans.length)(j => p.thetaTrans(j) * p.tau + p.mu)

val model: Model[Params] = new Model[Params]:
  def params: ParamSpec[Params] =
    (
      ParamSpec.vector("theta_trans", schools),
      ParamSpec.real("mu"),
      ParamSpec.positive("tau")
    ).mapN[Params]([A] => (t: IArray[A], mu: A, tau: A) => Params(t, mu, tau))

  def logPrior[A: Real](p: Params[A]): A =
    LogDensity.normal(p.thetaTrans, 0.0.lift[A], 1.0.lift[A]) +
      LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.halfCauchy(p.tau, 5.0.lift[A])

  def logLikelihood[A: Real](p: Params[A]): A =
    Real[A].normalSum(y, effects(p), Real[A].vectorOf(schools)(j => sigma(j).lift[A]))

  override def derived(p: Params[Double]): Map[String, IArray[Double]] =
    Map("theta" -> effects[Double](p))
```

```scala mdoc
Mcmc
  .runNuts(model, RunSettings())
  .runA(Seed(20261105L))
  .value
  .map(chains => Summary.of(model, chains).map(r => (r.name, r.rhat)).take(4))
```

Four differences from the Stan program, and each is a decision rather than an accident.

`tau ~ cauchy(0, 5)` becomes `halfCauchy`. Stan's `~` on a positively constrained parameter
evaluates the *untruncated* density and does not add the normalising constant, so its target differs
from this one by `log 2` — which moves `lp__` and leaves the posterior alone.

`theta` is `derived` rather than a parameter, because that is what it is: Stan's
`transformed parameters` block computes it from the parameters and reports it, and `derived` does
both.

`sigma` is lifted into the model's scalar with `.lift[A]`. Stan's data is always `double`; here a
density is read by three interpreters, so a constant has to become whichever scalar is reading it.

The `[A] => ...` in `mapN` is the one piece of ceremony Scala needs and Stan does not; chapter 2
explains why the function has to work for every scalar.

## What Stan has and this library does not

- the cumulative distribution functions (`_cdf`, `_lcdf`, `_lccdf`);
- the laws outside the specification: `chi_square`, `double_exponential`, `inv_gamma`;
- a parameter bound that depends on another parameter — `real<lower=0, upper=1-alpha1>`. Declare
  the raw parameter on the unit interval, multiply, and add `log(1 - alpha1)` to the target; that
  is exactly what Stan's own transform does, and appendix B of the validation notes records it;
- a differentiable `cholesky_decompose` as a primitive. A model that needs one writes the recursion
  out of the scalar operations, which the tape differentiates; the Gaussian process of posteriordb
  is fitted that way, at eleven dimensions.
