# 4. Diagnosing

A fit is not an answer until you have looked at it. This chapter is the four numbers to look at,
what each of them is actually measuring, and what to do when one of them is wrong.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

val y = IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0)
val sigma = IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)

final case class Schools[A](mu: A, tau: A, theta: IArray[A])

/** The eight schools, centred: the parameterisation that struggles. */
val centred: Model[Schools] = new Model[Schools]:
  def params: ParamSpec[Schools] =
    (ParamSpec.real("mu"), ParamSpec.positive("tau"), ParamSpec.vector("theta", 8))
      .mapN[Schools]([A] => (mu: A, tau: A, theta: IArray[A]) => Schools(mu, tau, theta))

  def logPrior[A: Real](p: Schools[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.halfCauchy(p.tau, 5.0.lift[A]) +
      LogDensity.normal(p.theta, p.mu, p.tau)

  def logLikelihood[A: Real](p: Schools[A]): A =
    Real[A].normalSum(y, p.theta, Real[A].vectorOf(8)(j => sigma(j).lift[A]))

val fit = Mcmc.runNuts(centred, RunSettings()).runA(Seed(20261105L)).value
```

## The table

```scala mdoc
fit.map(chains => Summary.table(centred, chains))
```

Four columns decide whether to believe the rest.

**`Rhat`** compares the variance between chains with the variance within them. At 1 the chains
agree; above about 1.01 they do not, and the posterior you are looking at is a mixture of several
samplers' opinions. This is the rank-normalised version — the larger of a statistic on the samples
and one on their distance from the median — so it still means something for a parameter whose
posterior has no variance.

**`ESS_bulk`** is the effective number of samples for the middle of the distribution: how many
independent samples your correlated ones are worth. A thousand of them with an effective number of
two hundred give the precision of two hundred, and `MCSE` is that precision.

**`ESS_tail`** is the same for the 5 % and 95 % ends. It is usually the smaller of the two and it
is the one that matters when you report an interval, because an interval is about the tails.

**`MCSE`** is the Monte Carlo error of the mean. Read it against the `sd` column: if the error is
a tenth of the standard deviation, the third digit of the mean is noise.

## What the sampler says about itself

`Rhat` and the effective number of samples are about the samples. These are about the sampler, and a
fit can have excellent values above and still be wrong:

```scala mdoc
fit.map(chains => SamplerReport.of(chains, maxTreeDepth = 10))
```

**Divergent transitions** are the serious one. A divergence means the integrator could not follow
the trajectory, which means there is a region of the posterior the sampler cannot enter. More
samples do not fix it; a reparameterisation does. The centred eight schools above produce some,
which is why [chapter 2](02-writing-a-model.md) writes the model the other way round.

**A saturated tree depth** means the sampler was still going when it was stopped. It is a cost
problem rather than a correctness one: raise `maxTreeDepth`, or find out why the posterior needs
such long trajectories.

**E-BFMI** below about 0.3 means the chain is not exploring the energy distribution — the
signature of a posterior with a funnel in it. Measured on a centred funnel it comes out near 0.1
against near 1 for a Gaussian, so it separates the two cleanly.

## A `NaN` is a diagnosis

Every statistic here returns a `NaN` rather than an error when it cannot say anything, and the
reasons are worth knowing because each one tells you something different:

```scala mdoc
val constant = IArray(IArray.fill(100)(1.0), IArray.fill(100)(1.0))
val tooShort = IArray(IArray(1.0, 2.0, 3.0), IArray(2.0, 3.0, 4.0))
(
  Convergence.rhatRank(constant),
  Convergence.essBulk(constant),
  Convergence.essBulk(tooShort),
  Convergence.mcseSd(tooShort)
)
```

A constant chain has no variance to compare, three iterations have no autocorrelation to estimate,
and
`mcse_sd` needs eight because it needs a fourth moment. A `NaN` in a table means look at that row.

## Intervals

A summary's quantiles are the percentile interval. Two others are worth knowing about:

```scala mdoc
fit.map { chains =>
  val tau = chains.flatMap(_.draws.map(q => Model.constrain(centred, q).tau))
  val draws = IArray.from(tau)
  (
    Intervals.equalTailed(draws, 0.9),
    Intervals.hpd(draws, 0.9),
    Intervals.probabilityAbove(draws, 5.0)
  )
}
```

The **percentile** interval cuts 5 % from each end. The **shortest** interval — the empirical
highest posterior density one — is the narrowest window holding 90 % of the samples, and for a
skewed posterior such as a scale it sits lower and is narrower. Neither is better; they answer different questions,
and the shortest one is the honest choice when a posterior is pushed against a boundary.

One rule about both: compute them at the end, from the samples, and never add them up. The 90th
percentile of a sum is not the sum of the 90th percentiles.

## Which diagnostics follow Stan, and where ArviZ differs

All of them follow Stan, and that is a decision rather than a default. ArviZ 1.x computes three of
these differently — the basic effective size on split chains where Stan does not split, the upper
tail with a strict inequality where Stan includes the boundary, and `mcse_sd` from a different
effective size — and differs by between 0.5 % and 15 % as a result. On the four chains Stan's own
tests use, our tail effective size for `lp__` is 1591.97 where ArviZ gives 1544.09.

If you compare a summary from this library against one from ArviZ, those three columns will not
match, and neither of you is wrong about arithmetic.
