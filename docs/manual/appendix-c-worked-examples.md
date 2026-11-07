# Appendix C. Classic worked examples

Five textbook computations, on numbers invented for this library. They are here because they are
the shortest honest demonstration that the pieces fit, and because the library's own test suite
asserts these same numbers against values computed independently in Python.

## A posterior on a grid

Five candidate rates, a uniform prior, and one observation of seven events. Nothing but a mass
function and a normalisation, done in the log domain because that is the habit worth having.

```scala mdoc
import bayes4s.*

val candidates = List(4.0, 6.0, 8.0, 10.0, 12.0)
val logPrior = candidates.map(_ => math.log(0.2))

def posterior(logWeights: List[Double]): List[Double] =
  val total = LogExp.logSumExp(IArray.from(logWeights))
  logWeights.map(w => math.exp(w - total))

val laws = candidates.map(Poisson.of).collect { case Right(law) => law }

val afterSeven = posterior(logPrior.zip(laws).map((lp, law) => lp + law.logMass(7)))
```

The mass moves to the middle of the grid, as it should. A second observation of eleven updates the
same way, the previous posterior now being the prior:

```scala mdoc
val afterEleven = posterior(
  afterSeven.zip(laws).map((p, law) => math.log(p) + law.logMass(11))
)
```

Two observations, and the rate of 8 is the most plausible of the five. Note what was never needed:
a sampler, a gradient, or a tolerance.

## The conjugate update, with an exposure

A Gamma prior on a rate and Poisson counts give a Gamma posterior, by adding the counts to the
shape and the exposure to the rate. Four periods of observation, four units of exposure:

```scala mdoc
val conjugate = for
  prior <- Gamma.of(1.5, 0.2)
  updated <- GammaPoisson.update(prior, IArray(7, 11, 5, 9), 4.0)
yield (updated.alpha, updated.beta, updated.mean, math.sqrt(updated.variance))

conjugate
```

The exposure is a separate argument rather than the number of counts, because the two are not the
same thing once part of the window is unobserved. Thirteen events seen over an exposure of 2.6:

```scala mdoc
val withExposure = for
  prior <- Gamma.of(1.5, 0.2)
  updated <- GammaPoisson.update(prior, IArray(13), 2.6)
yield (updated.alpha, updated.beta, updated.mean)

withExposure
```

## The predictive, against the Poisson that plugs in the mean

The posterior says what the rate might be. The predictive says what the next count might be, and
it is wider, by exactly the uncertainty about the rate:

```scala mdoc
val predictive = for
  posteriorRate <- Gamma.of(8.5, 1.2)
  counts <- GammaPoisson.predictive(posteriorRate)
  plugin <- Poisson.of(counts.mean)
yield (counts.mean, math.sqrt(counts.variance), plugin.mean, math.sqrt(plugin.variance))

predictive
```

Same mean, and a standard deviation of 3.60 against 2.66. A Poisson with the posterior mean plugged
into it looks like an answer and is not one: it has thrown away the posterior variance of the rate.
The ratio is modest here and grows with the uncertainty, which is why a forecast interval taken
from a plug-in Poisson is too narrow in exactly the cases where it matters.

The 90 % intervals say the same thing in counts, by summing the mass function:

```scala mdoc
def interval(mass: Int => Double, bound: Int): (Int, Int) =
  val cumulative = (0 to bound).scanLeft(0.0)((acc, k) => acc + math.exp(mass(k))).tail.toList
  (cumulative.indexWhere(_ >= 0.05), cumulative.indexWhere(_ >= 0.95))

val intervals = for
  posteriorRate <- Gamma.of(8.5, 1.2)
  counts <- GammaPoisson.predictive(posteriorRate)
  plugin <- Poisson.of(counts.mean)
yield (interval(counts.logMass, 400), interval(plugin.logMass, 200))

intervals
```

## Intervals and exceedance probabilities, from the same posterior

The conjugate update above gives a Gamma(33.5, 4.2) posterior for the rate. Everything a report
needs comes from samples of it, and `worked_examples.json` states each quantity in closed form so
that the sampled version can be checked rather than trusted.

```scala mdoc:silent
import cats.Monad

val sampled = Gamma
  .of(33.5, 4.2)
  .map(law => IArray.from(Monad[Rand].replicateA(200_000, law.sample[Rand]).runA(Seed(5L)).value))
```

```scala mdoc
sampled.map { draws =>
  (
    Intervals.equalTailed(draws, 0.9),
    Intervals.hpd(draws, 0.9),
    Intervals.probabilityAbove(draws, 10.0),
    Intervals.probabilityAbove(draws, 6.0)
  )
}
```

The closed forms are 5.8527 to 10.3700 for the percentile interval, 5.7102 to 10.1973 for the
shortest one, 0.0783 for the probability of exceeding 10 and 0.9350 for exceeding 6. The sampled
answers agree to within four Monte Carlo standard errors, which for a quantile is
`sqrt(p(1-p)/n)` divided by the density there — the suite states that error rather than picking a
tolerance.

Two things to notice. The shortest interval is **narrower** and sits **lower**, which is what a
right-skewed posterior does; and the probability of exceeding a threshold is a count, so its own
error is `sqrt(p(1-p)/n)` and nothing more exotic.

## Metropolis, by hand

The last one is a sampler rather than a formula. A random walk with a Gaussian proposal and a
Metropolis correction needs no gradient and no tuning worth the name, and it is the shortest way to
see what every sampler in this library is doing underneath: propose, compare, accept or stay.

```scala mdoc:silent
val shape = 33.5
val rate = 4.2

/** A Gamma(33.5, 4.2) as a model with no data: the prior *is* the posterior. */
val gamma: Model[cats.Id] = new Model[cats.Id]:
  val params: ParamSpec[cats.Id] = ParamSpec.positive("rate")
  def logPrior[A: Real](p: A): A = LogDensity.gamma(p, Real[A].const(shape), Real[A].const(rate))
  def logLikelihood[A: Real](p: A): A = Real[A].const(0.0)

val target = Model.target(gamma)

// A random walk wants an acceptance near 0.23, where a Hamiltonian method wants 0.8: driving a
// walk to 0.8 leaves a step so small that the chain crawls.
val walk = Mcmc.run[Rand](
  RandomWalkMetropolis.kernel[Rand](target, 1.0),
  target,
  IArray(2.0),
  warmup = 1000,
  samples = 20000,
  AdaptationSettings(targetAccept = 0.234)
)
```

```scala mdoc
walk.runA(Seed(77L)).value.map { chain =>
  // The chain walks on the unconstrained scale; the law is about the rate itself.
  val rates = IArray.from(chain.draws.map(q => math.exp(q(0))))
  val values = (0 until rates.length).map(rates.apply).toList
  val mean = values.sum / values.length
  val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
  (
    "mean" -> (mean * 1000).round / 1000.0,
    "sd" -> (math.sqrt(variance) * 1000).round / 1000.0,
    "acceptance" -> {
      val accepted = chain.transitions.map(_.acceptStat).sum / chain.transitions.length
      (accepted * 100).round / 100.0
    }
  )
}
```

The law's own mean is `shape / rate` = 7.976 and its standard deviation is `sqrt(shape) / rate` =
1.378. Twenty thousand samples from a random walk are worth far fewer independent ones, so compare
the answers against the Monte Carlo error rather than against the third decimal: at an effective
number of samples in the hundreds, the mean is good to about a hundredth of its standard deviation
and no better.

Two things this example is here to show. The **parameter is positive and the walk is not**: the
model declares `ParamSpec.positive` and the sampler moves in an unconstrained coordinate, so
`exp` appears in the summary and nowhere in the model — the transform and its log Jacobian are
`Model.target`'s business. And the acceptance rate comes out at 0.21 against the 0.234 it was
asked for — the adaptation doing its job on a kernel that knows nothing about gradients, and
landing where a dual-averaging scheme lands rather than exactly on its target.
