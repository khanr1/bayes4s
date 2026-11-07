# 6. Predicting

A fit tells you about parameters. A forecast is about things that have not happened, and getting
from one to the other means simulating: take a **[sample from the posterior](glossary.md)**, play
the future forward under it, and do that for every sample you have. What comes back is an
**ensemble** — one complete story per sample — and every number anyone wants is a summary of it,
computed at the very end.

The order matters more than anything else in this chapter. Summarise first and you will add
percentiles together, which is wrong by an amount nobody can estimate afterwards.

```scala mdoc:silent
import bayes4s.*
import java.time.YearMonth

// `monthly` refuses a negative count, so it returns an `Either`. There is no `get` in this manual:
// where a neutral value exists — and an empty run of months is one — `getOrElse` is how a value
// comes out of an `Either`. If this ever took the fallback, every output below would be visibly
// empty rather than quietly wrong.
val horizon = Periods.monthly(YearMonth.of(2026, 1), 6).getOrElse(Periods.empty)
```

## An ensemble is aligned by sample

`Ensemble[K]` holds `values(key)(sample)(period)`. The alignment across keys is the whole point:
sample 3 of one key and sample 3 of another came from the same posterior sample, the same drawn
templates, the same bootstrap weights — one coherent future — so they may be added. Summaries of
them may not.

```scala mdoc
val byKey = Ensemble.of(
  draws = 4,
  periods = Periods.monthly(YearMonth.of(2026, 1), 2).getOrElse(Periods.empty),
  values = Map(
    "x" -> IArray(IArray(10.0, 12.0), IArray(30.0, 33.0), IArray(20.0, 21.0), IArray(5.0, 6.0)),
    "y" -> IArray(IArray(30.0, 28.0), IArray(10.0, 7.0), IArray(20.0, 19.0), IArray(35.0, 34.0))
  )
)
byKey.map(e => e.total.values(()).map(_.mkString(", ")).mkString(" | "))
```

The shape is checked once, when the ensemble is built, so nothing downstream has to look. A ragged
matrix or a sample count that disagrees with the data is an error at that boundary.

`regroup` moves to coarser keys, summing the ones that land together, and `total` is `regroup(_ =>
())`. Whatever the regrouping, the total is the same — which is worth knowing because it means you
can report at several degrees of aggregation from one simulation.

## Why percentiles are computed last

The two keys above are anti-correlated: `x` is high in the samples where `y` is low. That is what
makes their total steadier than either part, and it is invisible to anything that looks at the
parts one at a time.

```scala mdoc
val comparison = byKey.map { e =>
  val perKey = e.quantiles(List(0.9))
  val ofTotal = e.total.quantiles(List(0.9))
  (
    "sum of the parts' 90th percentiles" -> (perKey("x")(0)(0) + perKey("y")(0)(0)),
    "90th percentile of the total" -> ofTotal(())(0)(0)
  )
}
```

The first number is larger, always, unless the parts are perfectly correlated. It is the answer to
a question nobody asked: "what if every part were simultaneously at its own 90th percentile?" The
second is the answer to "what is a high total?" — and it is the one that belongs in a report.

Nothing in this library adds a quantile. `quantiles` is the only way to get one, and it reads an
ensemble, which is why it cannot be called too early by accident.

## Simulating a future

Three pieces, and only the middle one belongs to the library.

An application decides what an event is — a drawn template, a sampled arrival, whatever its model
produces — and writes a **rule** saying what one contributes: an amount, under a key, at an offset
from the period the event closes in. The offset is how a delay is expressed.

```scala mdoc:silent
/** An event: a size, and the parts it is spread over, each with its own delay. */
final case class Template(size: Double, parts: List[(String, Int, Double)])

val rule: ContributionRule[Template, String] =
  (payload, scaled) =>
    Contribution("total", 0, scaled) ::
      payload.parts.map((name, delay, share) => Contribution(name, delay, scaled * share))
```

A `SimulatedFuture` collects events into one story. A contribution that falls outside the run is
dropped — not clamped to the last period, which would pile every long delay onto the end of the
forecast and make it look busy.

```scala mdoc
val oneFuture = SimulatedFuture
  .empty[String](horizon)
  .add(closeOffset = 0, rule.contributions(Template(100.0, List(("a", 1, 0.25))), 100.0))
  .add(closeOffset = 2, rule.contributions(Template(60.0, List(("a", 1, 0.5))), 60.0))
oneFuture.totals.view.mapValues(_.mkString(", ")).toMap
```

A `Simulator` turns one sample from the posterior into one future, and `Simulator.run` does it for
every sample. Future `i` gets `seed.fold(i)`, so the result depends on the seed and not on the
order the futures are computed in: the loop is parallelisable without changing a single number.

```scala mdoc:silent
final case class Parameters(rate: Double, kappa: Double)

val simulator: Simulator[Parameters, String] =
  (parameters, seed) =>
    val program = for
      size <- Rng[Rand].uniform.map(u => parameters.rate * (0.5 + u))
      error <- ModelError.draw[Rand](0.2)
    yield (size, error)
    val (size, error) = program.runA(seed).value
    (0 until horizon.count).foldLeft(SimulatedFuture.empty[String](horizon)) { (future, close) =>
      // The trend grows the amount with how far into the future the period falls, and the model's
      // own error multiplies it. Both come from this future's own sample.
      val years = close.toDouble / 12.0
      val scaled = ModelError.applied(TrendScaling.scaled(size, parameters.kappa, years), error)
      future.add(close, rule.contributions(Template(scaled, List(("a", 1, 0.25))), scaled))
    }.ensemble
```

```scala mdoc
val samples = IArray.tabulate(500)(s => Parameters(100.0 + s % 20, 0.05 + 0.001 * (s % 10)))
val ensemble = Simulator.run(simulator, samples, Seed(20261106L))
ensemble.total.quantiles(List(0.05, 0.5, 0.95))(()).map(row => row.map(_.round).mkString(", "))
```

```scala mdoc:passthrough
println(Html.scripts)
println(
  Html.embed(
    "fig-06-fan",
    VegaLite.spec(
      Catalogue.fanChart(ensemble.total, List(0.5, 0.9), None, "The simulated total, by month")
    ),
    "A fan chart of the simulated total over six months: a median that rises with the trend, " +
      "inside 50 and 90 per cent bands that widen as the horizon lengthens."
  )
)
```

Two things to notice in that simulator. The size and the parts come from the **same** drawn
template in the **same** future, so their correlation survives into the ensemble; drawing them
separately would leave every mean right and every interval too narrow. And the error term is drawn
once per future, not once per period, for the same reason.

## Drawing events from what you have seen

When the events themselves come from history rather than from a parametric model, the question is
which of the observed ones are representative.

The **Bayesian bootstrap** answers it. Instead of assuming the pool of past events is exactly
representative, it treats the pool's *composition* as unknown and samples an opinion about it: a
of weights over the observed events, one weight each, summing to one. Drawing those weights from a
Dirichlet(1, …, 1) is the uninformative choice — it says every composition consistent with having
seen these events is equally plausible — and each simulated future gets one such opinion, which is
how the uncertainty about "were these events typical?" ends up in the forecast.

```scala mdoc
val pool = for
  weights <- BayesianBootstrap.weights[Rand](5).runA(Seed(1L)).value match
    case w => TemplatePool.of(IArray("a", "b", "c", "d", "e"), w)
yield weights
pool.map(p => p.weights.map(w => (w * 1000).round / 1000.0).mkString(", "))
```

**One opinion per future, held for the whole horizon.** Redrawing the weights every period would
average their uncertainty away inside each future — twelve independent opinions averaged are far
more certain than one — and the intervals would come out too narrow while every mean stayed right.
The library takes one set of weights per future, and the suite measures what the
alternative would cost.

For groups that each have their own history, `StratifiedPools` gives a pool per group and mixes it
with the whole pool at `w = n / (n + k0)`: a group with plenty of events speaks for itself, one
with few borrows from everybody, and one below a minimum size has no pool of its own at all.

```scala mdoc
val events = IArray.tabulate(42)(i => if i < 40 then s"big-$i" else s"small-$i")
val stratified = StratifiedPools.of(
  events,
  (e: String) => e.takeWhile(_ != '-'),
  minimumSize = 5,
  mixingPrior = 10.0
)
stratified.map(s => (s.mixingWeight("big"), s.mixingWeight("small")))
```

## Counts that have not all arrived yet

An event created in one period is often reported in a later one. What you can see of a
(creation period, delay) grid is therefore only part of it, and `ReportingTriangle` carries which
part as an arbitrary mask — with the two usual shapes as constructors.

```scala mdoc
val shares = IArray(0.4, 0.3, 0.15, 0.1, 0.05)
val visible = ReportingTriangle
  .rightCensored(creations = 4, delays = 5, current = 3)
  .intersect(ReportingTriangle.leftTruncated(4, 5, firstVisible = 1))
visible.map(t => (t.visibleCells, t.exposure(shares).map(e => (e * 100).round).mkString("%, ")))
```

The **exposure** of a creation period is the share of its events that could have been reported by
now. For the censored mask it is `F(c - 1 - m)`, the delay distribution's cumulative function at
the period's age; for an arbitrary mask there is no closed form and the library sums the visible
shares, which is the definition the closed form is a special case of.

Correcting counts means dividing by that exposure — and refusing to when it is too small:

```scala mdoc
val corrected = ReportingTriangle
  .rightCensored(4, 5, current = 3)
  .correctedCounts(IArray(85.0, 42.0, 20.0, 0.0), shares, minimumVisibility = 0.3)
corrected.mkString(", ")
```

The newest period comes back `NaN`. It has seen almost none of its events, so dividing by its
exposure would turn one early report into an enormous estimate — the standard way a nowcast
explodes at its right-hand edge. `NaN` says "not yet knowable", which is true; a number there would
be a confident mistake, and an uncorrected count would be worse, because it looks like a number and
would be plotted next to the corrected ones.

## The pitfalls, in one list

- **Never add percentiles.** Add samples, summarise last. This is the one that costs the most.
- **Draw correlated quantities together.** An amount and its parts, a size and its delay: same
  sample, same future, or their correlation is gone and every interval narrows.
- **Draw bootstrap weights once per future**, not once per period.
- **A trend is multiplicative**, so a model error on the log scale raises the mean by
  `exp(sigma²/2)` even though it leaves the median alone. At a sigma of 0.3 that is 4.6 % on every
  total.
- **Do not clamp late contributions into the last period.** Dropping them is honest; clamping
  biases the end of the horizon upwards.
- **Do not correct a count whose exposure is tiny.** Set a visibility threshold and report nothing
  below it.
