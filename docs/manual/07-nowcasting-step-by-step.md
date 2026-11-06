# 7. Nowcasting step by step

The recent periods of a count series are incomplete: events have happened that nobody has heard
about yet. A nowcast is the answer to "how many really happened last month", and a forecast is the
same machinery pointed further forward. This chapter builds one from the pieces, on invented data.

```scala mdoc:silent
import bayes4s.*
import java.time.YearMonth
```

## The shape of the problem

Events are created in a period and reported some number of periods later. Arrange them by creation
period and delay, and the data is a grid with a corner missing — the cells whose reporting period
has not arrived yet.

```scala mdoc
val months = 18
val maxLag = 5
val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
(visible.visibleCells, months * (maxLag + 1))
```

Those missing cells are **unknown, not zero**, and that distinction is the whole chapter. A model
that treats them as zeros will say the recent months were quiet, which is exactly the wrong answer
and the most common mistake in this kind of work.

## The model, as three interchangeable pieces

A count model here is a rate component, a delay component, and the data:

```
log lambda[m] = rate.logRate(m)              a level, a trend, an AR(1) wobble
p[m](k)       = exp(delay.logPmf(t[m]))[k]   an ordered-logistic delay that drifts
y[m, k] ~ Poisson(lambda[m] p[m](k))         for the reported cells only
```

Trying a different story about the rate means passing a different component, not rewriting the
model. The model itself does not know which one it has.

```scala mdoc:silent
val rate = Ar1Trend()
val delay = OrderedLogitDrift()

val truth = DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](
  Ar1TrendParams(IArray(3.4), IArray(0.6), 0.5, 0.2, IArray.tabulate(months)(m => math.sin(m * 0.7))),
  OrderedLogitDriftParams(0.5, IArray(0.8, 0.6, 0.5, 0.4), 0.4)
)

val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get
val generative = DelayCountModel.generative(rate, delay, blank)
val data = generative.generative.simulateData[Rand](truth).runA(Seed(20261106L)).value
val model = DelayCountModel(rate, delay, data)
```

Two details in the components that are easy to get wrong and are worth knowing about. The AR(1)
wobble starts from its **stationary** distribution rather than from zero — starting at zero claims
the first month is known exactly. And the persistence has a Beta(2, 2) prior on `(phi + 1)/2`,
whose density vanishes at a unit root: that exactly cancels the stationary scale's blow-up there,
and without it the same fit reported 173 divergent transitions in 32 000 instead of one.

## Fitting, and what to look at first

```scala mdoc
val fit = Mcmc.runNuts(model, RunSettings(chains = 4, warmup = 1000, samples = 1000))
  .runA(Seed(4711L))
  .value
fit.map(chains => Summary.table(model, chains).linesIterator.take(6).mkString("\n"))
```

Read the divergent transitions and the effective sizes before anything else (chapter 4). A delay
model with a drift and a short history is a geometry where `tau` and the innovations can fight, and
the non-centred form the component uses is what keeps that manageable.

## What the recent months really were

The rate is a derived quantity, so the fit reports it per month — including the months whose counts
are still arriving. That is the nowcast.

```scala mdoc
val nowcast = fit.map { chains =>
  val samples = chains.flatMap(_.draws.map(q => model.derived(Model.constrain(model, q))("lambda")))
  val lastThree = (months - 3 until months).map { m =>
    val column = IArray.from(samples.map(_(m)))
    (
      m + 1,
      (Quantile.quantile(column, 0.05) * 10).round / 10.0,
      (Quantile.quantile(column, 0.5) * 10).round / 10.0,
      (Quantile.quantile(column, 0.95) * 10).round / 10.0
    )
  }
  lastThree.toList
}
```

The last month's interval is the widest of the three — about 15 wide against 13 for the two before
it — because it has been observed at delay zero only and most of its events are still to be
reported. The effect is modest here on purpose: with a maximum delay of five and eighteen months of
history, only the final few months are badly incomplete. Shorten the history or lengthen the delay
and the last month's interval grows until it is the only honest thing on the page, while a table of
raw counts would still show it as small and certain.

The comparison that matters is against what generated the data:

```scala mdoc
val truthRate = rate.logRate[Double](truth.rate, 0, t)
(months - 2 until months).map(m => (m + 1, (math.exp(truthRate(m)) * 10).round / 10.0)).toList
```

## Several groups at once

Counts usually arrive in groups — sites, regions, categories — and the question is what a quiet
group falls back on. Three answers, all of them a component rather than a rewrite:

```scala mdoc:silent
val groupCount = 3
// Independent: every group has its own level and trend, connected to nothing.
val independent = Ar1Trend()
// Partially pooled: each group's level and trend are drawn around a common one, so a quiet group
// is pulled towards the average of the others.
val pooled = Ar1TrendHierarchical(IArray.fill(groupCount)(0))
// Two populations: each has its own level and trend, and what they share is the spread of their
// groups and the wobble — the common level above them.
val populations = Ar1TrendHierarchical(IArray(0, 0, 1))
```

`centred` is a flag on the pooled component, not a second model. Both parameterisations describe
the same posterior and differ only in which coordinates the sampler walks in — which decides
whether it can walk at all when the group spread approaches zero. Start non-centred, which is the
default; switch to centred only if the groups are well separated and the non-centred fit mixes
badly. Chapter 4's divergent transitions and the spread's own R-hat are how you tell.

The delay can vary by group too, around a common one:

```scala mdoc:silent
val delayByGroup = OrderedLogitDriftByGroup()
```

One offset per group shifts every cut point together, which is a group reporting faster or slower
overall. What it cannot express is a group that is quick at first and slow in the tail — that needs
its own increments, and the library does not offer it.

Forecasting several groups is one call, with one rule per group so the contributions can be keyed:

```scala mdoc:silent
val groupBlank = DelayCountData
  .of(
    t,
    IArray.tabulate(groupCount)(_ => IArray.tabulate(months)(_ => IArray.fill(maxLag + 1)(0))),
    visible
  )
  .toOption
  .get
val groupTruth = DelayCountParams[HierarchicalAr1Params, OrderedLogitDriftParams, Double](
  HierarchicalAr1Params(
    muA = IArray(3.2), muB = IArray(0.4), sigmaA = 0.4, sigmaB = 0.15,
    aRaw = IArray(1.0, -0.5, 0.3), bRaw = IArray(-0.5, 1.0, 0.0),
    phi = 0.5, tau = 0.15, eps = IArray.tabulate(months)(m => math.sin(m * 0.8))
  ),
  OrderedLogitDriftParams(0.5, IArray(0.8, 0.6, 0.5, 0.4), 0.3)
)
val futureGroupT = IArray.tabulate(4)(h => (months + h - (months - 1) / 2.0) / months)
val groupPeriods = Periods.monthly(YearMonth.of(2027, 7), 4 + maxLag).toOption.get
```

```scala mdoc
val perGroup = Forecast.byGroup(
  pooled, OrderedLogitDrift(), List.fill(200)(groupTruth), futureGroupT, groupPeriods,
  TemplatePool.of(IArray("one"), IArray(1.0)).toOption.get, SizeModel.unit, groupCount,
  g => (_, scaled) => List(Contribution(s"site-$g", 0, scaled)),
  ForecastSettings(), Seed(77L)
)
val perGroupMedian = perGroup.quantiles(List(0.5))
val totalMedian = perGroup.total.quantiles(List(0.5))(())(0)
(
  perGroup.values.keySet.toList.sorted,
  perGroupMedian("site-0")(0).take(4).map(_.round).mkString(", "),
  totalMedian.take(4).map(_.round).mkString(", ")
)
```

All three groups are simulated **inside the same future**, from the same sample from the posterior,
so what they share — the wobble, the delay, the hyperparameters — is shared in the simulation too.
Forecasting them separately and adding the results afterwards would make their correlation zero
when the model says it is not, and the total's interval would come out too narrow.

`total` then sums them sample by sample. Never add the groups' own percentiles: chapter 6 measures
what that costs.

## Forecasting further out

Beyond the data, the rate has to be projected and the events have to be placed. `Forecast.run`
assembles the pieces: the rate component's own projection, the delay's drift, a Poisson count
thinned by it, a template drawn from a bootstrap pool, a size, and a contribution rule that says
where what results belongs.

```scala mdoc:silent
val futureT = IArray.tabulate(6)(h => (months + h - (months - 1) / 2.0) / months)
val periods = Periods.monthly(YearMonth.of(2027, 7), 6 + maxLag).toOption.get
val pool = TemplatePool.of(IArray("standard", "large"), IArray(0.8, 0.2)).toOption.get
val sizes = SizeModel.logNormal[String](
  LogNormalTrendRegressionParams(mu = 1.0, kappa = 0.2, omega = -0.1, s = 0.4),
  cap = Some(50.0)
)
val rule: ContributionRule[String, String] =
  (payload, scaled) => List(Contribution(payload, 0, scaled))
```

```scala mdoc
val forecast = fit.map { chains =>
  val parameters = chains.flatMap(_.draws.map(q => Model.constrain(model, q)))
  val ensemble = Forecast.run(
    rate, delay, parameters.take(500), futureT, periods, pool, sizes, rule,
    ForecastSettings(perDraw = 2, firstPeriodFactor = 0.5), Seed(5L)
  )
  val total = ensemble.total.quantiles(List(0.05, 0.5, 0.95))(())
  (ensemble.draws, total.map(row => row.map(_.round).mkString(", ")))
}
```

Read the three rows as the horizon filling and then emptying. The median row rises over the six
forecast periods as closes accumulate, then falls away over the following five as the last closes'
delayed reports finish arriving — the tail is the delay distribution, seen from the other end.

Three things in that call are the lessons of the earlier chapters, and all three are mistakes if
left out:

- **`firstPeriodFactor = 0.5`**: the first forecast period has already half happened, so only half
  of its events are still to come.
- **`perDraw = 2`**: two simulated futures per sample from the posterior, when more futures are
  wanted than there are samples. Each future still uses **one** sample throughout — one rate, one
  delay, one set of bootstrap weights — which is what preserves the correlations the fit found.
- **`cap = Some(50.0)`** on the sizes: a log-normal has no largest value, and one absurd event can
  dominate a total.

And the summary comes from `quantiles` on the ensemble, at the end — never by adding the
percentiles of the parts (chapter 6).

## The checklist

- The unreported cells are unknown, not zero. Check your mask before anything else.
- Read the nowcast's intervals widening towards the present; if they do not, the delay model is not
  doing its job.
- Run the prior predictive before fitting and the posterior predictive after (chapter 5).
- Score the forecast when the periods close (chapter 8), and keep the scores: a nowcast that is
  never scored is a nowcast nobody can improve.
