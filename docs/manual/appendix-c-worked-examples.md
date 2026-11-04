# Appendix C. Classic worked examples

Three textbook computations, on numbers invented for this library. They are here because they are
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
