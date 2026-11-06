# 8. Scoring a forecast

A forecast that was never scored is an opinion. This chapter is the four numbers worth computing
after the fact, what each of them can and cannot tell you, and the one place where the obvious
standard error is wrong by a factor of several.

```scala mdoc:silent
import bayes4s.*
import cats.syntax.all.*
```

## The score: how far the whole distribution was from the one number

The continuous ranked probability score is the distance between a predictive distribution and what
happened. It is in the units of the observation, it is zero only for a forecast that was certain
and right, and it is **proper**: no forecaster improves it by reporting anything but their honest
belief.

```scala mdoc
val forecast = IArray.tabulate(1000)(i => 10.0 + 2.0 * Special.invPhi((i + 0.5) / 1000.0))
(
  "a good forecast" -> Crps.ensemble(forecast, 10.3),
  "the same forecast, badly placed" -> Crps.ensemble(forecast, 18.0),
  "a certain forecast that was right" -> Crps.ensemble(IArray.fill(100)(10.3), 10.3)
)
```

Two things that are easy to get wrong, and that this library fixes in one direction rather than
leaving open:

The estimator of the spread term is `1/n²`, not `1/(n(n-1))`. Both appear in the literature; the
first is what properscoring and `scoringRules` report, and they differ by `n/(n-1)` — half a per
cent at a hundred samples. Mixing them is a quiet way to disagree with every published number.

And the score comes in two forms. `Crps.energy` is the definition and costs `O(n²)`;
`Crps.ensemble` sorts and costs `O(n log n)`. They are the same function:

```scala mdoc
val small = IArray(1.0, 1.0, 1.0, 4.0)
(Crps.ensemble(small, 1.0), Crps.energy(small, 1.0))
```

For a Gaussian forecast there is a closed form, which is the useful thing to check a sampler
against — it knows nothing about samples:

```scala mdoc
(Crps.gaussian(0.0, 1.0, 0.0), Crps.gaussian(10.0, 3.0, 14.0))
```

## Calibration: not how close, but what shape of wrong

A model can score well on average and still be systematically too wide, too narrow, or shifted.
The probability integral transform catches all three. It is where the observation fell inside the
forecast — and over many periods, it has to be uniform.

```scala mdoc
val values = IArray(1.0, 2.0, 3.0, 4.0)
(
  "above everything" -> Calibration.pit(values, 5.0),
  "in the middle" -> Calibration.pit(values, 2.5),
  "exactly on a sample" -> Calibration.pit(values, 2.0)
)
```

A tie counts as half, which is the expectation of the randomised transform. Without that, any
forecast with repeated values — every count forecast — piles its transforms on a bin edge and
looks miscalibrated when it is not.

Read the histogram's **shape**, not just its flatness: piled at the ends means the forecasts were
too narrow, piled in the middle means too wide, piled at one end means shifted. `pitUniformity`
gives the chi-squared statistic with `bins - 1` degrees of freedom when you want a number.

`bias` summarises the same thing in one number, from -1 to +1. The sign is worth memorising:
**positive means the forecast sat above what happened.**

```scala mdoc
(Calibration.bias(values, 0.0), Calibration.bias(values, 2.5), Calibration.bias(values, 5.0))
```

## Intervals: paying for width and for missing

The interval score charges the width of an interval plus a penalty, scaled by `2/alpha`, for an
observation outside it. It rewards sharpness subject to being right.

```scala mdoc
(
  "inside" -> Calibration.intervalScore(10.0, 20.0, 15.0, 0.1),
  "two below" -> Calibration.intervalScore(10.0, 20.0, 8.0, 0.1),
  "sharper and still right" -> Calibration.intervalScore(14.0, 16.0, 15.0, 0.1)
)
```

It is exactly `2/alpha` times the sum of the pinball losses at `alpha/2` and `1 - alpha/2`, which
is the bridge between reporting intervals and scoring quantiles; the suite asserts the identity
against a hand-written pinball loss.

Coverage is the blunter companion: the share of observations that fell inside their own interval,
boundaries counting as inside. Quote it next to the interval score, never instead of it — an
interval from minus infinity to plus infinity has perfect coverage.

## Backtesting, and the standard error that is wrong

`Backtest.run` scores a sequence of forecasts, one per date, taking each interval from the
forecast's own quantiles.

```scala mdoc:silent
val dates = (1 to 24).toList.map { month =>
  val centre = 100.0 + month
  val ensemble = IArray.tabulate(500)(i => centre + 8.0 * Special.invPhi((i + 0.5) / 500.0))
  (month, ensemble, centre + 3.0 * math.sin(month.toDouble))
}
val result = Backtest.run(dates, alpha = 0.1)
```

```scala mdoc
(result.meanCrps, result.coverage, result.coverageGap, result.meanBias, result.pitUniformity(5))
```

Read that output as a diagnosis rather than a grade. The coverage is 1.0 where it aimed at 0.9,
the bias is essentially zero, and the transform statistic is 18.9 on four degrees of freedom —
past the 0.1 % point of 18.47. Three readings, one conclusion: these forecasts are centred in the
right place and far too wide. The mean score alone would not have said which.

It scores forecasts; it does not produce them. Refitting the model at each origin, so that no fit
has seen its own target, belongs to whoever owns the model — a "backtest" that scored in-sample
forecasts would flatter every model and look identical from the outside.

Now the part that catches people. The mean score has a standard error, and `sd/sqrt(n)` is the
wrong one: scores on neighbouring dates are correlated, because a model that is wrong in March is
usually wrong in April. Resampling whole blocks of dates keeps that correlation inside the
resample, where it belongs.

```scala mdoc
val replicates = result.blockBootstrap[Rand](_.crps, blockSize = 6, replications = 1000)
  .runA(Seed(20261106L))
  .value
val mean = replicates.toList.sum / replicates.length
val spread = math.sqrt(replicates.toList.map(r => (r - mean) * (r - mean)).sum / (replicates.length - 1))
(mean, spread)
```

On a run of scores with an AR(1) correlation of 0.9, the true standard error is about 4.4 times the
independent one, and blocks recover most of that while single-date resampling sees none of it.
Choose the block length longer than the correlation you expect to see — a few months for monthly
data — and remember that `blockSize = 1` is the ordinary bootstrap, with the ordinary blindness.

The method returns the replicates themselves, not a summary: a standard deviation is one reading
of them, a compatibility interval is another, and a short run's score distribution is usually
skewed enough that the histogram is the honest thing to look at.

## Comparing two variants of a model

The reason to score anything is to choose. Here are two stories about the same count series — one
with a trend, one a random walk — scored against periods they did not see.

```scala mdoc:silent
val months = 16
val maxLag = 3
val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get

/** The data really does trend: a level of 3.3 rising at 0.6 a year. */
val truth = DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](
  Ar1TrendParams(IArray(3.3), IArray(0.6), 0.4, 0.12, IArray.tabulate(months)(m => math.sin(m * 0.9))),
  OrderedLogitDriftParams(0.5, IArray(0.8, 0.6, 0.4), 0.2)
)
val data = DelayCountModel
  .generative(Ar1Trend(), OrderedLogitDrift(), blank)
  .generative
  .simulateData[Rand](truth)
  .runA(Seed(20261107L))
  .value

val quick = RunSettings(chains = 2, warmup = 400, samples = 600)
```

Fit both, forecast the next four periods from each, and score the forecasts against what the
trending truth actually produced:

```scala mdoc:silent
// Ten periods, not four: a walk's forecast is flat at the last level while a trend keeps
// rising, so the two only part company as the horizon lengthens.
val futureT = IArray.tabulate(10)(h => (months + h - (months - 1) / 2.0) / months)

/** What really happens next, under the parameters that generated the history. */
val outcomes =
  // The future rate comes from the component's own projection at the future times — never by
  // extending the data's time axis, which has no innovations beyond the history and would give
  // `NaN` for every month past it.
  val future = Ar1Trend().project(truth.rate, 0, futureT, Seed(404L))
  val program = (0 until 10).toList.traverse(h => Sampling.poisson[Rand](math.exp(future(h))))
  program.runA(Seed(515L)).value.map(_.toDouble)

def scoreOf[R[_]](component: RateComponent[R], seed: Long): Either[BayesError, Double] =
  val model = DelayCountModel(component, OrderedLogitDrift(), data)
  Mcmc.runNuts(model, quick).runA(Seed(seed)).value.map { chains =>
    val parameters = chains.flatMap(_.draws.map(q => Model.constrain(model, q)))
    val projected = parameters.take(400).map(p => component.project(p.rate, 0, futureT, Seed(seed + 1)))
    // One ensemble per future period: the projected rate turned into counts.
    val scores = (0 until 10).map { h =>
      val ensemble = IArray.from(projected.map(row => math.exp(row(h))))
      Crps.ensemble(ensemble, outcomes(h))
    }
    scores.sum / scores.length
  }
```

```scala mdoc
val comparison = for
  withTrend <- scoreOf(Ar1Trend(), 11L)
  asWalk <- scoreOf(LocalLevel(), 21L)
yield (
  "mean CRPS with a trend" -> (withTrend * 100).round / 100.0,
  "mean CRPS as a walk" -> (asWalk * 100).round / 100.0
)
```

**The two scores are the same.** Not nearly the same — the same to within a quarter of a per
cent, on data that genuinely trends, with the trend component that generated it. Measured across
three trend strengths (0.6, 1.5 and 3.0 a year) and two horizons (10 and 24 periods), the ratio of
the walk's mean score to the trend's ran from 0.990 to 1.002: at the longer horizon the **walk**
was marginally ahead twice.

That is the most useful thing this chapter can show you, so it is worth understanding rather than
engineering around. A random walk's forecast starts from the last level, which already embodies
everything the trend has done so far, and its uncertainty widens with the horizon. On a smooth
exponential series that tracks the truth about as well as extrapolating the slope does — and the
score is dominated by the rate's own uncertainty, which both components inflate the same way. The
difference between the two stories is real, and the predictive score at this horizon is not where
it shows up.

So what does separate them? The **parameter**, not the score. Fit the trend component to data
generated by a walk and look at its trend: if the model is sound, the interval contains zero. The
library's slow suites assert exactly that, and it is a sharper question than "which forecast
scored better" because it is about the thing the two components disagree on.

Three further cautions, which this example happens to demonstrate rather than merely assert. Ten
periods is far too few to be confident: quote a block-bootstrap interval on the **difference**,
not two point scores. The two models' errors are correlated, because they are scored on the same
outcomes, so that error comes from the per-period differences — the same point chapter 4 makes
about `Loo.compare`. And a comparison on one realisation of the future is a comparison of one draw
of luck; repeat it over several, as the measurement above did.

## What to report

- The **mean score**, with a block-bootstrap interval, never on its own.
- The **coverage** against what it aimed at, and the **interval score** next to it.
- The **transform histogram**, read for its shape.
- When comparing two models, score them on the same dates and look at the per-date differences —
  the two models' errors are correlated, so the error of their difference is not the sum of their
  errors. Chapter 4's `Loo.compare` makes the same point for cross-validation.
