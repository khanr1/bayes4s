# 7. Nowcasting step by step

The recent months of a count series are incomplete: things have happened that nobody has heard
about yet. A nowcast is the answer to "how many really happened last month", and a forecast is the
same machinery pointed further forward. This chapter builds one from the pieces, end to end.

It is set in the invented world of `examples.FictionalWorld` — a lending library in four invented
cities, whose membership applications arrive at a drifting rate, are processed after a delay, and
bring a member who borrows books and returns them later. Everything below is simulated, and the
generator is a value with a fixed seed, so every number on this page is reproducible.

This is the longest chapter in the manual and the one that leans on the most machinery. If the idea
of a count series that is incomplete at its right-hand edge is new,
[the ideas page](the-ideas.md) explains it without code; the cities are
[clusters](glossary.md) in the sense of the [glossary](glossary.md), and every component named
below is listed with its arguments in [appendix E](appendix-e-every-knob.md).

```scala mdoc:silent
import bayes4s.*
import bayes4s.examples.*
import cats.syntax.all.*
import java.time.YearMonth

val prepared = LibraryHistory.of(months = 36, maxLag = 5)
```

`LibraryHistory.of` returns an `Either`, because arranging a world into a grid can fail — a mask
that disagrees with the counts — and this library has no exceptions. Every block below therefore
reads it through `map` or `flatMap`. That is what "errors are values" costs in a manual, and it is
the same three characters a reader will write.

## The shape of the problem

Applications are created in a month and processed some months later. Arrange them by month of
arrival and delay, and the data is a grid with a corner missing: the cells whose reports had not
arrived by the month the observer stands in.

```scala mdoc
prepared.map { history =>
  (
    "cells in the grid" -> history.months * (history.maxLag + 1),
    "cells visible" -> history.data.visible.visibleCells,
    "applications seen" -> history.data.visibleTotal
  )
}
```

Those missing cells are **unknown, not zero**, and that distinction is the whole chapter. A model
that treats them as zeros will say the recent months were quiet, which is exactly the wrong answer
and the most common mistake in this kind of work.

The **exposure** of a month is the share of its applications that could have been processed by now.
It falls to almost nothing at the right-hand edge:

```scala mdoc
prepared.map { history =>
  history.exposure.toList.takeRight(7).map(e => (e * 100).round / 100.0)
}
```

## Correcting counts, and refusing to

Dividing a visible count by its exposure is the cheapest nowcast there is. It is also the standard
way a nowcast explodes at its right-hand edge, so `correctedCounts` refuses below a visibility
threshold:

```scala mdoc
prepared.map { history =>
  val corrected = history.correctedCounts(City.Northgate, minimumVisibility = 0.3)
  val raw = history.visibleTotals(City.Northgate.ordinal)
  (
    "seen" -> raw.toList.takeRight(6).map(_.round),
    "corrected" -> corrected.toList.takeRight(6).map(c => if c.isNaN then -1L else c.round)
  )
}
```

The newest month comes back `NaN` — printed as `-1` above, because a list of doubles is harder to
read. `NaN` says "not yet knowable", which is true; a number there would be a confident mistake,
and an uncorrected count would be worse, because it looks like a number and would be plotted next
to the corrected ones.

A correction is not a model, though. It has no uncertainty, it cannot borrow strength from the
other months, and it cannot say anything about the month after next. That is what the rest of the
chapter is for.

## The model, as three interchangeable pieces

A count model here is a rate component, a delay component, and the data:

```
log lambda[g, m] = rate.logRate(g, m)        a level, a trend, an AR(1) wobble
p[g, m](k)       = exp(delay.logPmf(t[m]))   an ordered-logistic delay that drifts
y[g, m, k] ~ Poisson(lambda[g, m] p[g, m](k))   for the reported cells only
```

Trying a different story about the rate means passing a different component, not rewriting the
model. The model itself does not know which one it has.

Both of those words deserve a sentence before the code uses them.

An **AR(1)** wobble is the simplest way to say "this month resembles last month": each month's
deviation from the trend is `phi` times the previous one plus fresh noise, so a surprise fades at a
rate the fit estimates rather than one you choose. At `phi` near zero the months are independent;
near one the level wanders.

An **ordered-logistic** delay is the simplest way to say "most things are reported quickly and a
few take ages". It puts a cut point between each delay and the next, on a scale where "recorded
within two months" implies "within three" — which is true of delays and is exactly what an
unordered categorical would throw away. The **drift** lets the whole distribution slide earlier or
later over time, which is what happens when a process speeds up.

```scala mdoc:silent
// Partially pooled: each city's level and trend are drawn around a common one, so a quiet city is
// pulled towards the average of the others rather than left to its own thin history.
//
// The argument says which *population* each group belongs to, group by group. Four zeros is "all
// four cities are drawn around one common level"; `IArray(0, 0, 1, 1)` would be two pairs, each
// around its own.
val rate = Ar1TrendHierarchical(IArray(0, 0, 0, 0))
val delay = OrderedLogitDrift()

val model = prepared.map(history => DelayCountModel(rate, delay, history.data))
```

Two details in the components that are easy to get wrong. The AR(1) wobble starts from its
**stationary** distribution — the spread it settles into after running for a long time — rather
than from zero — starting at zero claims the first month is
known exactly. And the persistence has a Beta(2, 2) prior on `(phi + 1)/2`, whose density vanishes
at a unit root: that exactly cancels the stationary scale's blow-up there, and without it the same
fit reported 173 divergent transitions in 32 000 instead of one.

## Fitting, and what to look at first

```scala mdoc
val fit = model.flatMap { m =>
  Mcmc.runNuts(m, RunSettings(chains = 2, warmup = 600, samples = 600)).runA(Seed(4711L)).value
}
(fit, model).mapN((chains, m) => Summary.table(m, chains).linesIterator.take(7).mkString("\n"))
```

Read the divergent transitions and the effective numbers of samples before anything else
(chapter 4). A delay model with a drift and a short history is a geometry where the spread and the
innovations can fight, and the non-centred form the component uses by default is what keeps that
manageable.

`lp__`'s Rhat is a little above the 1.01 chapter 4 asks for, and that is the fit's fault rather
than the model's: two chains of six hundred samples is a small fit, chosen so that this page
compiles in seconds. A fit you intend to report wants four chains and more samples, and the first
thing to do with a borderline Rhat is to run more — not to argue about the threshold.

```scala mdoc:passthrough
println(Html.scripts)
println(
  (fit, model)
    .mapN { (chains, m) =>
      val mu = IArray.from(
        chains.map(c => IArray.from(c.draws.map(q => Model.values(m, q)("muA")(0))))
      )
      Html.embed(
        "fig-07-trace",
        VegaLite.spec(Catalogue.tracePlot(mu, "muA")),
        "Two chains of the common level of the log rate over 600 iterations. They overlap and " +
          "stay in the same band, which is what a healthy fit looks like."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

## What the recent months really were

The rate is a derived quantity, so the fit reports it per city and month — including the months
whose counts are still arriving. That is the nowcast.

```scala mdoc
val nowcast = (fit, model, prepared).mapN { (chains, m, history) =>
  val samples = chains.flatMap(_.draws.map(q => m.derived(Model.constrain(m, q))("lambda")))
  // `lambda` is one row per group and month, flattened group-major.
  val northgate = (history.months - 4 until history.months).map { month =>
    val column = IArray.from(samples.map(_(month)))
    (
      month + 1,
      (Quantile.quantile(column, 0.05) * 10).round / 10.0,
      (Quantile.quantile(column, 0.5) * 10).round / 10.0,
      (Quantile.quantile(column, 0.95) * 10).round / 10.0
    )
  }
  northgate.toList
}
```

The last month's interval is the widest of the four, because it has been observed at delay zero
only and most of its applications are still to be processed. Read the intervals widening towards
the present; if they do not, the delay model is not doing its job.

```scala mdoc:passthrough
println(
  (fit, model, prepared)
    .mapN { (chains, m, history) =>
      val samples = chains.flatMap(_.draws.map(q => m.derived(Model.constrain(m, q))("lambda")))
      val rows = (0 until history.months).toList.map { month =>
        val column = IArray.from(samples.map(_(month)))
        (
          s"${month + 1}",
          Quantile.quantile(column, 0.5),
          Quantile.quantile(column, 0.05),
          Quantile.quantile(column, 0.95)
        )
      }
      Html.embed(
        "fig-07-nowcast",
        VegaLite.spec(Catalogue.intervalPlot(rows.takeRight(12), "The rate, month by month")),
        "The fitted rate for the last twelve months of Northgate, each with its 90 per cent " +
          "compatibility interval. The intervals are narrow for the older months and widen over " +
          "the last five, where the applications are still being processed."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

## Several cities at once

Counts usually arrive in groups, and the question is what a quiet group falls back on. Three
answers, all of them a component rather than a rewrite:

```scala mdoc:silent
// Independent: every group has its own level and trend, connected to nothing.
val independent = Ar1Trend()
// Partially pooled: the four cities are drawn around one common level.
val pooled = Ar1TrendHierarchical(IArray(0, 0, 0, 0))
// Two populations: two groups share one level above them and two share another — what they have
// in common is the spread of their cities and the wobble.
val populations = Ar1TrendHierarchical(IArray(0, 0, 1, 1))
// A level that wanders instead of reverting: no trend, no mean to come back to.
val wandering = LocalLevel()
```

`centred` is a flag on the pooled component, not a second model. **Non-centred** — the default —
means the model asks for each city's value as "how many population standard deviations from the
common level", and multiplies back; **centred** asks for the value itself. Both parameterisations
describe the same posterior and differ only in which coordinates the sampler walks in — which
decides whether it can walk at all when the group spread approaches zero. Start non-centred, which
is the default; switch to centred only if the groups are well separated and the non-centred fit
mixes badly. Chapter 4's divergent transitions and the spread's own Rhat are how you tell.

`LocalLevel` is the variant worth knowing about because of what it refuses to do: fitted to a
series with no trend it finds no trend, since it has no trend parameter to find one with. A random
walk is the right story when the level has no particular value to return to, and the wrong one when
it has — it will happily extrapolate the last wobble forever.

The delay can vary by group too, around a common one:

```scala mdoc:silent
val delayByCity = OrderedLogitDriftByGroup()
```

One offset per group shifts every cut point together, which is a city reporting faster or slower
overall. What it cannot express is a city that is quick at first and slow in the tail — that needs
its own increments, and the library does not offer it.

## Forecasting further out

Beyond the data, the rate has to be projected and the events have to be placed. `Forecast.run`
assembles the pieces: the rate component's own projection, the delay's drift, a Poisson count
thinned by it, a template drawn from a bootstrap pool, a size, and a contribution rule that says
where what results belongs.

```scala mdoc:silent
val horizon = 6

val pieces = prepared.flatMap { history =>
  for
    periods <- Periods.monthly(YearMonth.of(2027, 1), horizon + history.maxLag)
    pool <- TemplatePool.of(IArray("standard", "large"), IArray(0.8, 0.2))
  yield
    val futureT =
      IArray.tabulate(horizon)(h =>
        (history.months + h - (history.months - 1) / 2.0) / history.months
      )
    val sizes = SizeModel.logNormal[String](
      LogNormalTrendRegressionParams(mu = 1.0, kappa = 0.2, omega = -0.1, s = 0.4),
      cap = Some(50.0)
    )
    val rule: ContributionRule[String, String] =
      (payload, scaled) => List(Contribution(payload, 0, scaled))
    (periods, pool, futureT, sizes, rule)
}
```

```scala mdoc
val forecast = (fit, model, pieces).mapN {
  case (chains, m, (periods, pool, futureT, sizes, rule)) =>
  val parameters = chains.flatMap(_.draws.map(q => Model.constrain(m, q)))
  val ensemble = Forecast.byGroup(
    rate, delay, parameters.take(400), futureT, periods, pool, sizes, 4,
    g => (_, scaled) => List(Contribution(City.fromOrdinal(g).toString, 0, scaled)),
    ForecastSettings(perDraw = 2, firstPeriodFactor = 0.5), Seed(5L)
  )
  val total = ensemble.total.quantiles(List(0.05, 0.5, 0.95))(())
  (ensemble.draws, total.map(row => row.map(_.round).mkString(", ")))
}
```

Read the three rows as the horizon filling and then emptying. The median rises over the six
forecast months as applications accumulate, then falls away over the following five as the last
months' delayed reports finish arriving — the tail is the delay distribution, seen from the other
end.

Three things in that call are the lessons of the earlier chapters, and all three are mistakes if
left out:

- **`firstPeriodFactor = 0.5`**: the first forecast month has already half happened, so only half
  of its applications are still to come.
- **`perDraw = 2`**: two simulated futures per sample from the posterior, when more futures are
  wanted than there are samples. Each future still uses **one** sample throughout — one rate, one
  delay, one set of bootstrap weights — which is what preserves the correlations the fit found.
- **`cap = Some(50.0)`** on the sizes: a log-normal has no largest value, and one absurd event can
  dominate a total.

All four cities are simulated **inside the same future**, from the same sample, so what they share
— the wobble, the delay, the hyperparameters — is shared in the simulation too. Forecasting them
separately and adding the results afterwards would make their correlation zero when the model says
it is not, and the total's interval would come out too narrow.

```scala mdoc:passthrough
println(
  (fit, model, pieces)
    .mapN { case (chains, m, (periods, pool, futureT, sizes, rule)) =>
      val parameters = chains.flatMap(_.draws.map(q => Model.constrain(m, q)))
      val ensemble = Forecast.byGroup(
        rate, delay, parameters.take(400), futureT, periods, pool, sizes, 4,
        g => (_, scaled) => List(Contribution(City.fromOrdinal(g).toString, 0, scaled)),
        ForecastSettings(perDraw = 2, firstPeriodFactor = 0.5), Seed(5L)
      )
      Html.embed(
        "fig-07-fan",
        VegaLite.spec(
          Catalogue
            .fanChart(ensemble.total, List(0.5, 0.9), None, "Applications per month, forecast")
        ),
        "A fan chart of the forecast total: the median rises over the six forecast months as " +
          "applications accumulate, then falls away over the five months in which the last " +
          "delayed reports arrive. The 50 and 90 per cent bands widen with the horizon."
      )
    }
    .getOrElse("<p>the forecast failed, so there is no figure</p>")
)
```

## Reporting by quarter

A report usually wants quarters, and a quarter is a sum of months. Sum **inside each sample** and
summarise at the end — adding the months' percentiles would answer a question nobody asked.

```scala mdoc
val byQuarter = (fit, model, pieces).mapN {
  case (chains, m, (periods, pool, futureT, sizes, rule)) =>
  val parameters = chains.flatMap(_.draws.map(q => Model.constrain(m, q)))
  val ensemble = Forecast
    .byGroup(
      rate, delay, parameters.take(400), futureT, periods, pool, sizes, 4,
      g => (_, scaled) => List(Contribution(City.fromOrdinal(g).toString, 0, scaled)),
      ForecastSettings(perDraw = 2, firstPeriodFactor = 0.5), Seed(5L)
    )
    .total
  val quarters = periods.quarters.toList
  val rows = ensemble.values.getOrElse((), IArray.empty[IArray[Double]])
  // One row per sample, one column per quarter: the sum is taken inside the sample.
  val perSample = rows.map { row =>
    quarters.map { quarter =>
      (0 until periods.count)
        .filter(offset => periods.quarterOf(offset).contains(quarter))
        .foldLeft(0.0)((sum, offset) => sum + row(offset))
    }
  }
  quarters.zipWithIndex.map { (quarter, index) =>
    val column = IArray.from(perSample.map(_(index)).toList)
    (quarter.toString, Quantile.quantile(column, 0.5).round, Quantile.quantile(column, 0.95).round)
  }
}
```

The first quarter is partial — the forecast starts inside it — and the last is partial for the
opposite reason: only the delayed reports of earlier months land in it. Both are honest; neither
should be compared with a whole quarter without saying so.

## Books, returned later

An application brings a member who borrows books, and each book comes back some months after that.
A return is therefore a **derived quantity of an event, spread over time** — the shape
`Contribution` exists for — and the world's own returns show what that looks like in data:

```scala mdoc
prepared.map { history =>
  val returns = history.returnsByMonth
  val applications = history.visibleTotals.map(_.sum).sum
  (
    "books returned in the window" -> returns.sum,
    "the first six months" -> returns.toList.take(6),
    "returns per application" -> (returns.sum / applications * 100).round / 100.0
  )
}
```

The first month is empty and the next few are nearly so: nobody has joined yet, and a book cannot
come back before it was borrowed. The same rule, applied in a forecast rather than to history, is
chapter 6's `ContributionRule` with a non-zero offset.

## The checklist

- The unreported cells are unknown, not zero. Check your mask before anything else.
- Read the nowcast's intervals widening towards the present; if they do not, the delay model is not
  doing its job.
- Correct counts only where the exposure is large enough, and report nothing below the threshold.
- Run the prior predictive simulation before fitting and the posterior predictive check after
  (chapter 5).
- Sum inside the sample, summarise at the end — quarters included (chapter 6).
- Score the forecast when the months close (chapter 8), and keep the scores: a nowcast that is
  never scored is a nowcast nobody can improve.
