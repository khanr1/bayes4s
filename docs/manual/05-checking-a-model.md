# 5. Checking a model

A fit that converged is not a fit that is right. This chapter is the three checks that find
different kinds of wrong, in the order they cost least to run. [The ideas page](the-ideas.md) says
what each one is for in plain words; this is how to run them.

The data a check simulates is **[fake data](glossary.md)** throughout — simulated on purpose, from a
known answer, which is what makes it possible to tell whether the check works.

```scala mdoc:silent
import bayes4s.*
```

## Before any data: the prior predictive

Simulate from the priors alone and look at what they imply. This costs nothing, needs no fit, and
catches the most embarrassing errors — priors that imply a thousand times more events than anyone
has seen, or a scale that is physically impossible.

```scala mdoc:silent
val months = 8
val maxLag = 3
val t = IArray.tabulate(months)(m => (m - 3.5) / 8.0)
val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)

// An empty grid of the right shape: `blank` refuses a mask that disagrees with it, so it returns
// an `Either` and everything below reads it through `map`. There is no `get` anywhere in this
// manual, which is the same rule the library holds itself to.
val blank = DelayCountData.blank(months, maxLag, t, visible)
val model = blank.map(data => DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data))
```

```scala mdoc
val priorTotals = model.map { m =>
  val prior = Checks.priorPredictive(m, draws = 200, Seed(1L))
  val totals = IArray.tabulate(prior.replications)(r =>
    (0 until prior.periods).foldLeft(0.0)((sum, p) => sum + prior.replicated(r)(p))
  )
  val sorted = IArray.from(totals.toList.sorted)
  (sorted.head, Quantile.quantile(sorted, 0.5), sorted(sorted.length - 1))
}
```

Those run over orders of magnitude, which is what a barely regularizing prior on a log rate
means. Whether that
is *right* depends on the application — and that is the point of looking. A prior predictive that
spans a factor of a million when you know the answer is between ten and a hundred is telling you
the prior is doing nothing, and a fit under it will be slower and less stable than it needs to be.

## After the fit: the posterior predictive

Replicate the data from the posterior and compare it with what happened — period by period, not in
total, because a model can get the total right while being wrong about every period in it.

```scala mdoc:silent
val truth = DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](
  Ar1TrendParams(
    IArray(3.2), IArray(0.4), 0.5, 0.15, IArray.tabulate(months)(m => math.sin(m.toDouble))
  ),
  OrderedLogitDriftParams(0.6, IArray(0.7, 0.5), 0.3)
)
val generative =
  blank.map(data => DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), data))
val fitted = generative.map { g =>
  val observed = g.generative.simulateData[Rand](truth).runA(Seed(20261106L)).value
  DelayCountModel(Ar1Trend(), OrderedLogitDrift(), observed)
}
```

```scala mdoc
val check = for
  f <- fitted
  chains <- Mcmc.runNuts(f, RunSettings(chains = 2, warmup = 600, samples = 1000))
    .runA(Seed(4711L))
    .value
  draws <- Mcmc.draws(f, chains)
  sample <- Checks.posteriorPredictive(f, draws, Seed(99L))
yield (sample.periods, sample.outsideBand(0.9), sample)
```

```scala mdoc:passthrough
println(Html.scripts)
println(
  check
    .map { (_, _, sample) =>
      Html.embed(
        "fig-05-ppc",
        VegaLite.spec(
          Catalogue.predictivePlot(
            sample.observed,
            sample.replicated,
            0.9,
            "Reports arriving, observed against replicated"
          )
        ),
        "Reports arriving in each period: the observed series as points against the 90 per cent " +
          "band of series replicated from the posterior. The observed series stays inside the " +
          "band except in one period."
      )
    }
    .getOrElse("<p>the check failed, so there is no figure</p>")
)
```

The statistic is the number of reports that **arrived** in each period — the diagonal of the grid,
`month + delay = period`. Not the number of events created, which in the recent months is precisely
what nobody has seen and what the model is for: checking a model against its own estimate of the
unobserved would be checking it against itself.

Read `outsideBand` in both directions. About a tenth of the periods should fall outside a 90 %
band; **zero is as much a warning as half**, because a band nothing escapes is too wide to say
anything.

## The check that finds a model written twice: calibration

The two above compare a model with data. Simulation-based calibration compares a model **with
itself**: draw parameters from the prior, simulate data from them, fit, and ask where the true
value falls among the posterior samples. Over many replications those ranks must be uniform.

```scala mdoc
val calibration = generative.flatMap { g =>
  Sbc.run(g, RunSettings(chains = 2, warmup = 400, samples = 500), 16, 63).runA(Seed(7L)).value
}
calibration.map(result => result.uniformityChiSquared(4))
```

```scala mdoc:passthrough
println(
  calibration
    .map { result =>
      Html.embed(
        "fig-05-sbc",
        VegaLite.spec(
          Catalogue.sbcPlot(result.ranks.getOrElse("phi", IArray.empty), result.thinnedDraws)
        ),
        "The rank histogram of the persistence over sixteen replications, with the cumulative " +
          "difference from a flat histogram and its simultaneous 95 per cent band. Sixteen " +
          "replications is too few to conclude anything, and the band is correspondingly wide."
      )
    }
    .getOrElse("<p>the calibration failed, so there is no figure</p>")
)
```

Sixteen replications is far too few to conclude anything — the library's own suite uses 40 to 256 —
but it shows the shape of the answer. What makes this check worth its cost is what it notices that
nothing else does: a prior written once as a density and once as a sampler, and differently; a
transform whose Jacobian is wrong; a sampler that cannot explore the model. All three leave every
other diagnostic looking healthy.

## What each check cannot do

- **The prior predictive** says nothing about whether the model fits. It only says what the model
  believed before it saw anything.
- **The posterior predictive** can look perfect for a model that is badly over-parameterised: it is
  being compared against the data it was fitted to. Use leave-one-out (chapter 4) for that.
- **Calibration** says the model, the sampler and the generative part agree with each other. It
  says nothing about whether any of them resembles the world — a model calibrated against its own
  prior can still be the wrong model.

Run all three. They are cheap relative to being wrong.
