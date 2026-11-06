# 8. Scoring a forecast

A forecast that was never scored is an opinion. This chapter is the four numbers worth computing
after the fact, what each of them can and cannot tell you, and the one place where the obvious
standard error is wrong by a factor of several.

```scala mdoc:silent
import bayes4s.*
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

## What to report

- The **mean score**, with a block-bootstrap interval, never on its own.
- The **coverage** against what it aimed at, and the **interval score** next to it.
- The **transform histogram**, read for its shape.
- When comparing two models, score them on the same dates and look at the per-date differences —
  the two models' errors are correlated, so the error of their difference is not the sum of their
  errors. Chapter 4's `Loo.compare` makes the same point for cross-validation.
