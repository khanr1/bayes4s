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

The funnel is visible, and so is where the divergent transitions are:

```scala mdoc:passthrough
println(Html.scripts)
println(
  fit
    .map { chains =>
      val draws = chains.flatMap(_.draws)
      Html.embed(
        "fig-04-pairs",
        VegaLite.spec(
          Catalogue.pairsPlot(
            IArray.from(draws.map(q => Model.constrain(centred, q).mu)),
            IArray.from(draws.map(q => math.log(Model.constrain(centred, q).tau))),
            ("mu", "log tau"),
            Some(IArray.from(chains.flatMap(_.transitions.map(_.divergent))))
          )
        ),
        "The common mean against the log of the between-school spread. The cloud narrows sharply " +
          "as the spread falls — the funnel — and the divergent transitions, in orange, are " +
          "concentrated in its neck where the sampler could not follow the trajectory."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

Plot the spread on the **log** scale to see this. On its own scale the funnel is squashed against
zero and looks like a cloud with a hard edge; on the log scale it is the triangle that gives the
geometry its name, and the divergent transitions sit where the triangle narrows.

```scala mdoc:passthrough
println(
  fit
    .map { chains =>
      val report = SamplerReport.of(chains, maxTreeDepth = 10)
      Html.embed(
        "fig-04-energy",
        VegaLite.spec(
          Catalogue.energyPlot(
            IArray.from(chains.headOption.toList.flatMap(_.transitions.map(_.energy))),
            report.eBfmi.headOption.getOrElse(Double.NaN)
          )
        ),
        "The energy of the first chain over its iterations, with its E-BFMI. A chain that is " +
          "exploring the energy distribution moves across the whole band; one that is stuck in " +
          "the funnel's neck shows long flat stretches."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

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
skewed posterior such as a scale it sits lower and is narrower. Neither is better; they answer
different questions, and the shortest one is the honest choice when a posterior is pushed against a
boundary.

One rule about both: compute them at the end, from the samples, and never add them up. The 90th
percentile of a sum is not the sum of the 90th percentiles.

## Criticising the model

The four numbers above ask whether the sampler did its job. They say nothing about whether the
model is any good. Two tools answer that, and both need something the model has not had to provide
so far: its log likelihood **one observation at a time**.

```scala mdoc:silent
/** The same model, with its likelihood also available per observation. */
val criticised: Model[Schools] & PointwiseLikelihood[Schools] =
  new Model[Schools] with PointwiseLikelihood[Schools]:
    def params: ParamSpec[Schools] = centred.params
    def logPrior[A: Real](p: Schools[A]): A = centred.logPrior(p)
    def logLikelihood[A: Real](p: Schools[A]): A = centred.logLikelihood(p)
    def pointwise(p: Schools[Double]): IArray[Double] =
      IArray.tabulate(8)(j => LogDensity.normal(y(j), p.theta(j), sigma(j)))
```

The one rule about `pointwise` is that it must sum to `logLikelihood` — the same arithmetic, split
up — and it is worth a test of your own, because nothing can check it for you.

### Leave-one-out cross-validation

`Loo.psis` estimates how well the model would predict each observation had it not been fitted to
it, from the fit you already have: it reweights the samples from the posterior by the inverse
likelihood of the observation being left out. The weights of that reweighting have a heavy tail,
and Pareto-smoothed importance sampling replaces the largest of them by the fitted quantiles of a
generalised Pareto distribution.

```scala mdoc
val loo = fit.map { chains =>
  val samples =
    chains.flatMap(_.draws).map(q => criticised.pointwise(Model.constrain(criticised, q)))
  // One row per observation, one column per sample from the posterior.
  val logLik = IArray.tabulate(8)(j => IArray.from(samples.map(_(j))))
  Loo.psis(logLik, 1.0)
}
loo.map(result => (result.elpd, result.se, result.pLoo))
```

`elpd` is the expected log predictive density, higher being better, and `se` is its standard error
over the observations. `pLoo` is the effective number of parameters: the difference between the
log likelihood at the fit and the cross-validated one, which is how much the model used its
flexibility.

The shape parameter the smoothing fitted is the diagnostic that matters:

```scala mdoc
loo.map(result => (result.paretoK, result.problematic(Loo.kThreshold(4000))))
```

Above the threshold — `min(1 - 1/log10(S), 0.7)`, so 0.7 at any realistic number of samples — the
estimate for that observation is not to be trusted, and the honest fix is to refit without the
observation rather than to quote the number anyway. `problematic` names the observations to look
at, and here it names three of the eight.

```scala mdoc:passthrough
println(
  loo
    .map { result =>
      Html.embed(
        "fig-04-pareto",
        VegaLite.spec(Catalogue.paretoKPlot(result)),
        "The Pareto shape of each school's importance weights, with the 0.5 and 0.7 lines. " +
          "Three of the eight sit above 0.7, in orange: for those schools the leave-one-out " +
          "estimate is not to be trusted."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

That is not a bug in the smoothing, and it is worth understanding before you reach for a different
tool. Each school contributes exactly one observation, and its own `theta[j]` is informed by that
observation and by the two hyperparameters and nothing else. Leaving the observation out therefore
removes most of what identified the parameter, the importance weights have to move the posterior a
long way, and their tail stops looking like one a generalised Pareto distribution with a finite
mean can describe. A model with one observation per cluster is the worst case for leave-one-out,
and the number to quote is a refit without each flagged observation — or a cross-validation that
leaves out a whole cluster at a time, which is a different question and a better-posed one.

To compare models, `Loo.compare` ranks them best first and reports each difference with its own
standard error — computed from the **pointwise differences**, not from the two models' standard
errors, because the two models are fitted to the same observations and their errors are
correlated. A difference smaller than its standard error means cross-validation cannot tell the two
apart; chapter 11 uses this to settle a real question.

### Sensitivity to the prior

Power scaling asks a different question: if the prior were slightly stronger or slightly weaker,
would the posterior move? It raises the prior to a power a little above and a little below one,
reweights the samples you already have — the same Pareto smoothing again, so no refitting — and
measures how far the posterior moved per unit of change, as a cumulative Jensen-Shannon distance.
The same is done for the likelihood.

```scala mdoc
fit.map { chains =>
  PowerScaling.analyse(centred, chains).filter(s => s.name == "mu" || s.name == "tau")
}
```

Reading the two numbers together gives four cases, and `diagnosis` says which one you are in:
neither sensitive, the data doing the work, the prior doing the work, or — both above the
threshold — prior-data conflict, where the prior and the data pull in different directions and the
posterior is a compromise neither would have chosen.

`tau` here reads 2.11 against the prior and 0.024 against the likelihood: the half-Cauchy prior on
the between-cluster spread is what decides its posterior, and the eight observations have almost
nothing to say about it. That is the eight schools' oldest lesson arriving as a number rather than
as folklore — with eight clusters of one observation each, the spread between clusters is a
question the data cannot answer, and whatever prior you put on it is the answer you will get.

```scala mdoc:passthrough
println(
  fit
    .map { chains =>
      Html.embed(
        "fig-04-sensitivity",
        VegaLite.spec(Catalogue.sensitivityPlot(PowerScaling.analyse(centred, chains))),
        "Prior sensitivity against likelihood sensitivity, one point per parameter, with the " +
          "0.05 thresholds. The between-school spread sits far along the prior axis and at zero " +
          "on the likelihood axis: its posterior is its prior."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

Two cautions. The reading is a sensitivity, not a verdict: a narrow prior is sensitive whether or
not it conflicts with the data, so a prior placed exactly where the data already is still reads as
a conflict, and only the size of the number separates the two. And a parameter the data says
little about will show a prior sensitivity because that is the correct answer — the prior is the
only thing informing it.

## Which diagnostics follow Stan, and where ArviZ differs

All of them follow Stan, and that is a decision rather than a default. ArviZ 1.x computes three of
these differently — the basic effective size on split chains where Stan does not split, the upper
tail with a strict inequality where Stan includes the boundary, and `mcse_sd` from a different
effective size — and differs by between 0.5 % and 15 % as a result. On the four chains Stan's own
tests use, our tail effective size for `lp__` is 1591.97 where ArviZ gives 1544.09.

If you compare a summary from this library against one from ArviZ, those three columns will not
match, and neither of you is wrong about arithmetic.
