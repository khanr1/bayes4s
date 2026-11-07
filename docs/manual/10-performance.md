# 10. Performance and pitfalls

This chapter is written in two halves. The cost of `Real[A]` and the vectorised primitives is
measured here, at phase 2, because that is when it can be; the pitfalls of simulation and scoring
arrive with the chapters that introduce them.

## What a gradient costs

Scala 3 has no `@specialized`, so a function written against an abstract scalar boxes its values.
That is the price of writing a density once and differentiating it three ways, and it is contained
rather than denied: the expensive operations of a realistic model are **vectorised primitives**
that work on an `IArray` and add a single node to the tape.

The figures below were measured by JMH on an Apple M2 Pro with 16 GB and OpenJDK 25, with
`sbt "benchmarks/Jmh/run -i 5 -wi 3 -f1 -t1 bayes4s.GradientBenchmark"`. They are not a promise
about your machine; the ratios between them are the interesting part.

| What | Time per call |
|---|---|
| A reporting-triangle model (36 months, 12 lags, 234 cells, 52 parameters): value | 73.9 µs |
| the same model: value **and** gradient | 148.9 µs |
| 10 000 observations in 50 clusters, vectorised: value and gradient | 79.0 µs |
| the same model with 100 observations | 2.5 µs |
| the same 10 000 observations with one predictor written out per observation | 458.4 µs |

Three things to read from that table.

**A gradient costs about twice a value.** That is the central claim of reverse-mode differentiation
and it holds here: 148.9 against 73.9 µs for a model with 52 parameters. Forward differences would
have cost 52 values, and a hand-written gradient would have cost a bug.

**A vectorised density is 5.8 times faster than the same arithmetic written out.** The last two
rows are the same model and the same answer; the difference is that one of them writes
`slope * predictor(i)` once per observation. That costs three tape nodes a row — the constant
`predictor(i)` is itself a node — so the tape holds 30 103 nodes instead of 89.

**The tape stops growing before the work does.** Ten thousand observations cost 32 times what a
hundred cost, not 100 times, and the node count is identical — 89 either way, of which 53 are the
parameters themselves. A vectorised primitive still reads
every observation; what it avoids is a node, an edge and a reverse sweep for each of them.

## Counting nodes

`Reverse.nodeCount` is the diagnostic to reach for when a model is slower than it should be. It
reports the size of the tape for one evaluation.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

val observations = 2_000
val clusters = 20
val group = IArray.tabulate(observations)(i => i % clusters)
val ys = IArray.tabulate(observations)(i => 0.5 * math.cos(i.toDouble))

/** Every observation gets its cluster's level, and the whole likelihood is one primitive. */
val vectorised: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val alpha = Real[A].vectorOf(clusters)(g => x(g))
    LogDensity.normal(ys, Real[A].gather(alpha, group), x(clusters))

/** The same function, one term at a time. */
val written: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val alpha = Real[A].vectorOf(clusters)(g => x(g))
    Real[A].sum(
      Real[A].vectorOf(observations)(i =>
        LogDensity.normal(ys(i).lift[A], alpha(group(i)), x(clusters))
      )
    )

val at = IArray.from(List.tabulate(clusters)(g => 0.1 * g - 0.5) ++ List(1.1))
```

The two agree on the answer, to the last two digits of a log density near -2573 — they add the
same terms in a different order, which is all a vectorised primitive changes about the arithmetic:

```scala mdoc
Reverse.value(vectorised, at)
Reverse.value(written, at)
```

and disagree by three orders of magnitude on what they cost to differentiate:

```scala mdoc
Reverse.nodeCount(vectorised, at)
Reverse.nodeCount(written, at)
```

The rule of thumb that follows: if a model's node count grows with the number of observations, look
for a loop that should have been a vectorised primitive. The ones that exist are `sum`,
`cumulativeSum`, `logSumExp`, `dot`, `matVec`, `lowerSolve`, `gather` and one per density —
`LogDensity.normal`, `LogMass.poisson`, `LogMass.poissonLog`, `LogMass.bernoulliLogit` and
`LogMass.binomialLogit` all take vectors.

## Pitfalls

The pitfall of measuring a gradient at all comes first: a model whose density is wrong has a
perfectly fast gradient, so measure after the checks of [chapter 5](05-checking-a-model.md) pass
and not before.

The five below are the ones that have actually cost something. Each is a mistake you can make while
every diagnostic stays green, which is what makes them worth a list.

### Treating unreported cells as zeros

The recent rows of a count grid are incomplete, not quiet. A model fitted to the grid with the
missing cells filled in as zeros will report that the last few months collapsed, confidently, and
every diagnostic will agree with it. `ReportingTriangle` exists so that the mask is a value you
pass rather than an assumption you forget:

```scala mdoc
val shares = IArray(0.5, 0.3, 0.15, 0.05)
val mask = ReportingTriangle.rightCensored(creations = 6, delays = 4, current = 6)
(
  "exposure" -> mask.exposure(shares).map(e => (e * 100).round / 100.0).toList,
  "cells the likelihood reads" -> mask.visibleCells,
  "cells in the grid" -> 6 * 4
)
```

The exposure of the newest month is 0.5 and of the oldest is 1. A likelihood that read all
twenty-four cells would be reading nine zeros that are not zeros.
[Chapter 7](07-nowcasting-step-by-step.md) is the whole story.

### Adding percentiles

The 90th percentile of a total is not the sum of the parts' 90th percentiles, and the gap is not
small. Summing percentiles answers "what if every part were simultaneously at its own 90th
percentile?", which nobody asked and which no realisation of the future will satisfy.

```scala mdoc
val parts = Ensemble.of(
  draws = 4,
  periods = Periods.monthly(java.time.YearMonth.of(2027, 1), 1).getOrElse(Periods.empty),
  values = Map(
    "a" -> IArray(IArray(10.0), IArray(30.0), IArray(20.0), IArray(5.0)),
    "b" -> IArray(IArray(30.0), IArray(10.0), IArray(20.0), IArray(35.0))
  )
)
parts.map { e =>
  val perPart = e.quantiles(List(0.9))
  (
    "sum of the parts' percentiles" -> (perPart("a")(0)(0) + perPart("b")(0)(0)),
    "percentile of the total" -> e.total.quantiles(List(0.9))(())(0)(0)
  )
}
```

The two parts there are anti-correlated, which is the common case for shares of something, and the
sum of the percentiles comes out **half again** as large as the percentile of the total.
`quantiles` reads an ensemble and nothing else, so the only way to make this mistake is to compute
one summary per part and add the summaries by hand.

### Reusing a seed where you meant to split it

Randomness is a value here, which removes the usual bug — two runs that differ for no reason — and
introduces its opposite: two things that should be independent and are identical, because they were
handed the same seed.

```scala mdoc
val seed = Seed(42L)
val twice = (Rng[Rand].normal.runA(seed).value, Rng[Rand].normal.runA(seed).value)
val split = (Rng[Rand].normal.runA(seed.fold(0L)).value, Rng[Rand].normal.runA(seed.fold(1L)).value)
(twice, split)
```

`fold(i)` derives an independent stream by index, and that is what every parallel thing in this
library does: chain `c` runs at `seed.fold(c)`, future `i` at `seed.fold(i)`. The symptom of getting
it wrong is intervals that are too narrow, because what should have been independent randomness
was the same randomness twice — and nothing in a diagnostic table can see it.

### A prior so vague it decides the answer

A prior wide enough to "let the data speak" can end up doing the speaking, and the parameter it
lands on is usually the one with the fewest observations: the spread between clusters.
[Chapter 4](04-diagnosing.md) measures it with `PowerScaling.analyse` — on the eight schools the
between-school spread reads 2.1 against the prior and 0.02 against the likelihood — and
[chapter 11](11-multilevel-models.md) shows what the prior's tail is worth when the data has an
opinion and when it does not. The prior predictive simulation of
[chapter 5](05-checking-a-model.md) catches the gross version before any fitting at all: if it
implies a thousand times more events than anyone has seen, it is not uninformative, it is wrong.

### Holding an ensemble you do not need

An `Ensemble[K]` is `draws × periods` doubles per key. Ten thousand futures over sixty periods and
forty keys is 183 MB — and that is one ensemble, where a backtest holds one per origin, while the
report it feeds usually needs a handful of quantiles. Two cheap habits:
regroup to the keys you will actually report before you simulate anything further, and compute the
quantiles at the end and keep those rather than the ensemble.

```scala mdoc
def megabytes(draws: Int, periods: Int, keys: Int): Double =
  (draws.toLong * periods * keys * 8).toDouble / (1024 * 1024)
(megabytes(10000, 60, 40), megabytes(10000, 60, 4), megabytes(1000, 60, 4))
```

`ForecastSettings(perDraw = n)` is the knob that matters when the samples from the posterior are
fewer than the futures you want: `perDraw = 2` with five hundred samples gives a thousand futures
without a second fit, and each future still uses one sample throughout, which is what keeps the
correlations the fit found.
